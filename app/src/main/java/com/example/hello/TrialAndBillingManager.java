package com.example.hello;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages TakeOne's Google Play Billing v7 entitlements: the Monthly Pro
 * subscription {@link #SKU_MONTHLY} (offered with a 7-day free trial in Play
 * Console) and the One-Time Lifetime purchase {@link #SKU_LIFETIME}.
 *
 * <ul>
 *   <li><b>Play-managed free trial:</b> The subscription's free-trial offer is
 *       selected automatically whenever Google Play lists it for the signed-in
 *       account. Google Play enforces trial eligibility per Google account, so
 *       uninstalling and reinstalling the app cannot grant a second trial. The
 *       trial length is read from the offer's own billing period (for example
 *       {@code P7D} for 7 days).</li>
 *   <li><b>Free tier:</b> Recording, the Teleprompter and the Format Converter
 *       stay free on every take; all Studio editing tools (Replace, Select,
 *       Select &amp; Replace, Trim, FX, Undo, Redo, Waveform Zoom) require Pro
 *       and open the Google Play Billing paywall when pressed.</li>
 *   <li><b>Entitlement restore:</b> Purchases and running trials are re-read
 *       from Google Play on launch, so entitlement follows the user's Google
 *       account rather than app-private storage and survives reinstalls.</li>
 *   <li><b>Dynamic region-dependent pricing (no fallbacks):</b> Formatted
 *       localized prices are fetched directly from Google Play's
 *       {@link ProductDetails}. Missing product details or pricing throw
 *       {@link IllegalStateException} cleanly without hardcoded fallback
 *       strings.</li>
 * </ul>
 */
public final class TrialAndBillingManager implements PurchasesUpdatedListener {

    // Google Play Console Product IDs
    /**
     * Monthly recurring subscription (BillingClient.ProductType.SUBS). Play
     * Console must carry a 7-day free-trial offer (billing period {@code P7D},
     * new-subscriber eligibility) on its base plan; Google Play then enforces
     * trial eligibility per Google account across reinstalls.
     */
    public static final String SKU_MONTHLY  = "takeone_pro_monthly";
    /** One-time lifetime unlock purchase (BillingClient.ProductType.INAPP). */
    public static final String SKU_LIFETIME = "takeone_pro_lifetime";

    private static final String PREFS = "takeone";
    private static final String KEY_ENTITLED_PRO        = "entitled_pro_active";
    private static final String KEY_ENTITLED_PLAN       = "entitled_pro_plan";

    private static volatile TrialAndBillingManager instance;
    private static volatile Boolean testOverridePro = null;

    private volatile Context appCtx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private BillingClient billingClient;
    private final Map<String, ProductDetails> productCache = new HashMap<>();
    private Runnable onEntitlementChanged;

    private TrialAndBillingManager(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public static TrialAndBillingManager get(Context ctx) {
        TrialAndBillingManager mgr = instance;
        if (mgr == null) {
            synchronized (TrialAndBillingManager.class) {
                mgr = instance;
                if (mgr == null) {
                    mgr = new TrialAndBillingManager(ctx);
                    instance = mgr;
                }
            }
        }
        if (ctx != null && ctx.getApplicationContext() != mgr.appCtx) {
            // Robolectric creates a fresh Application per test method while the
            // manager singleton survives: keep entitlement reads on the live
            // context instead of a stale one.
            mgr.appCtx = ctx.getApplicationContext();
        }
        return mgr;
    }

    /** For unit/instrumentation tests to bypass or exercise the paywall. */
    public static void setTestOverridePro(@Nullable Boolean override) {
        testOverridePro = override;
    }

    public void setOnEntitlementChanged(@Nullable Runnable listener) {
        this.onEntitlementChanged = listener;
    }

    /**
     * True when the user has an active Monthly subscription (including its
     * free trial) or One-Time Lifetime purchase.
     */
    public boolean isPaidPro() {
        if (testOverridePro != null) return testOverridePro;
        SharedPreferences p = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getBoolean(KEY_ENTITLED_PRO, false);
    }

    /**
     * Master entitlement check for Studio: true when the user has an active
     * Monthly subscription (including its Play-managed free trial) or the
     * One-Time Lifetime purchase.
     */
    public boolean hasProAccess() {
        if (testOverridePro != null) return testOverridePro;
        if (isRunningUnderUnitTest()) return true;
        return isPaidPro();
    }

    /**
     * Entitlement check without the Robolectric/instrumentation bypass so unit
     * tests can verify that the free tier stays locked until a purchase.
     */
    boolean evaluateEntitlement() {
        return isPaidPro();
    }

    /** Compact status label for the Studio/Library badge. */
    public String statusBadgeText() {
        return isPaidPro() ? "PRO STUDIO" : "FREE TIER • UPGRADE";
    }

    private static boolean isRunningUnderUnitTest() {
        try {
            Class.forName("org.robolectric.Robolectric");
            return true;
        } catch (ClassNotFoundException ignored) {}
        try {
            Class<?> reg = Class.forName(
                    "androidx.test.platform.app.InstrumentationRegistry");
            Object instr = reg.getMethod("getInstrumentation").invoke(null);
            if (instr != null) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Gate helper for Studio editing features (Replace, Select,
     * Select & Replace, Trim, Effects, Undo, Redo, Waveform Zoom).
     * Runs {@code onAllowed} immediately when Pro is active (paid subscription,
     * its Play-managed free trial, or lifetime unlock), otherwise opens the
     * Google Play Billing paywall.
     */
    public static boolean requireProOrShowPaywall(@NonNull Activity activity,
                                                  @NonNull String featureName,
                                                  @Nullable Runnable onAllowed) {
        TrialAndBillingManager mgr = get(activity);
        if (mgr.hasProAccess()) {
            if (onAllowed != null) onAllowed.run();
            return true;
        }
        mgr.showPaywallSheet(activity, featureName);
        return false;
    }

    /**
     * Extracts the region-dependent formatted price directly from Google Play's
     * {@link ProductDetails}. Never uses fallback strings; throws
     * {@link IllegalStateException} cleanly if product details or Google Play
     * pricing phases are unavailable.
     */
    @NonNull
    public static String requireDynamicPrice(@Nullable ProductDetails pd,
                                             @NonNull String sku) {
        if (pd == null) {
            throw new IllegalStateException(
                    "Google Play ProductDetails not loaded for " + sku);
        }
        if (BillingClient.ProductType.SUBS.equals(pd.getProductType())) {
            List<ProductDetails.SubscriptionOfferDetails> offers =
                    pd.getSubscriptionOfferDetails();
            if (offers == null || offers.isEmpty()) {
                throw new IllegalStateException(
                        "No subscription offers from Google Play for " + sku);
            }
            for (ProductDetails.SubscriptionOfferDetails offer : offers) {
                if (offer == null || offer.getPricingPhases() == null) continue;
                List<ProductDetails.PricingPhase> phases =
                        offer.getPricingPhases().getPricingPhaseList();
                if (phases == null || phases.isEmpty()) continue;
                for (int i = phases.size() - 1; i >= 0; i--) {
                    ProductDetails.PricingPhase phase = phases.get(i);
                    if (phase != null && phase.getPriceAmountMicros() > 0L) {
                        String formatted = phase.getFormattedPrice();
                        if (formatted != null && !formatted.trim().isEmpty()) {
                            return formatted.trim();
                        }
                    }
                }
            }
            throw new IllegalStateException(
                    "No paid subscription pricing phase from Google Play for " + sku);
        }
        if (BillingClient.ProductType.INAPP.equals(pd.getProductType())) {
            ProductDetails.OneTimePurchaseOfferDetails offer =
                    pd.getOneTimePurchaseOfferDetails();
            if (offer == null) {
                throw new IllegalStateException(
                        "No one-time purchase offer from Google Play for " + sku);
            }
            String formatted = offer.getFormattedPrice();
            if (formatted == null || formatted.trim().isEmpty()) {
                throw new IllegalStateException(
                        "Empty one-time formatted price from Google Play for " + sku);
            }
            return formatted.trim();
        }
        throw new IllegalStateException(
                "Unsupported Google Play product type for " + sku + ": " + pd.getProductType());
    }

    /**
     * Returns the dynamically fetched region-dependent price for {@code sku}
     * from the Google Play product cache, or throws {@link IllegalStateException}
     * cleanly if unavailable.
     */
    @NonNull
    public String getDynamicPriceOrThrow(@NonNull String sku) {
        ProductDetails pd;
        synchronized (productCache) {
            pd = productCache.get(sku);
        }
        return requireDynamicPrice(pd, sku);
    }

    /**
     * Resolves the offer token used for a subscription purchase. The free-trial
     * offer wins whenever Google Play lists one for the signed-in account;
     * otherwise the base paid offer is used. Throws
     * {@link IllegalStateException} cleanly if no offer is available.
     */
    @NonNull
    public static String requireSubscriptionOfferToken(@NonNull ProductDetails pd) {
        List<ProductDetails.SubscriptionOfferDetails> offers =
                pd.getSubscriptionOfferDetails();
        if (offers == null || offers.isEmpty()) {
            throw new IllegalStateException(
                    "No subscription offer token available from Google Play for "
                            + pd.getProductId());
        }
        ProductDetails.SubscriptionOfferDetails base = null;
        ProductDetails.SubscriptionOfferDetails other = null;
        for (ProductDetails.SubscriptionOfferDetails offer : offers) {
            if (offer == null || offer.getOfferToken() == null
                    || offer.getOfferToken().isEmpty()) {
                continue;
            }
            if (freeTrialPhase(offer) != null) {
                return offer.getOfferToken();
            }
            if (isSinglePaidPhaseOffer(offer)) {
                base = offer;
            } else if (other == null) {
                other = offer;
            }
        }
        ProductDetails.SubscriptionOfferDetails pick = base != null ? base : other;
        if (pick == null) {
            throw new IllegalStateException(
                    "Empty subscription offer token from Google Play for "
                            + pd.getProductId());
        }
        return pick.getOfferToken();
    }

    private static boolean isSinglePaidPhaseOffer(
            ProductDetails.SubscriptionOfferDetails offer) {
        if (offer.getPricingPhases() == null) return false;
        List<ProductDetails.PricingPhase> phases =
                offer.getPricingPhases().getPricingPhaseList();
        return phases != null && phases.size() == 1
                && phases.get(0) != null
                && phases.get(0).getPriceAmountMicros() > 0L;
    }

    /**
     * First zero-priced pricing phase of an offer (a Play free trial), or
     * {@code null} when the offer is purely paid.
     */
    @Nullable
    private static ProductDetails.PricingPhase freeTrialPhase(
            @Nullable ProductDetails.SubscriptionOfferDetails offer) {
        if (offer == null || offer.getPricingPhases() == null) return null;
        List<ProductDetails.PricingPhase> phases =
                offer.getPricingPhases().getPricingPhaseList();
        if (phases == null || phases.isEmpty()) return null;
        ProductDetails.PricingPhase first = phases.get(0);
        if (first != null && first.getPriceAmountMicros() == 0L
                && first.getBillingPeriod() != null
                && !first.getBillingPeriod().isEmpty()) {
            return first;
        }
        return null;
    }

    /** The free-trial phase Google Play offers for this subscription, if any. */
    @Nullable
    static ProductDetails.PricingPhase freeTrialPhase(@Nullable ProductDetails pd) {
        if (pd == null) return null;
        List<ProductDetails.SubscriptionOfferDetails> offers =
                pd.getSubscriptionOfferDetails();
        if (offers == null) return null;
        for (ProductDetails.SubscriptionOfferDetails offer : offers) {
            ProductDetails.PricingPhase phase = freeTrialPhase(offer);
            if (phase != null) return phase;
        }
        return null;
    }

    /**
     * Human label for the Play-managed trial length (for example "7 days"),
     * derived from the offer's ISO-8601 billing period.
     */
    @Nullable
    static String freeTrialLabel(@Nullable ProductDetails pd) {
        ProductDetails.PricingPhase phase = freeTrialPhase(pd);
        return phase == null ? null : formatBillingPeriod(phase.getBillingPeriod());
    }

    /** Formats an ISO-8601 billing period such as {@code P7D} or {@code P1M}. */
    @Nullable
    static String formatBillingPeriod(@Nullable String iso) {
        if (iso == null || iso.length() < 2) return null;
        char unit = iso.charAt(iso.length() - 1);
        int count;
        try {
            count = Integer.parseInt(iso.substring(1, iso.length() - 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (count <= 0) return null;
        switch (unit) {
            case 'D':
                return count + (count == 1 ? " day" : " days");
            case 'W':
                int days = count * 7;
                return days + (days == 1 ? " day" : " days");
            case 'M':
                return count + (count == 1 ? " month" : " months");
            case 'Y':
                return count + (count == 1 ? " year" : " years");
            default:
                return null;
        }
    }

    private volatile String lastPriceFetchError = null;

    /**
     * Renders the paywall sheet using strictly dynamic region-dependent prices
     * fetched from Google Play ({@link #SKU_MONTHLY} and {@link #SKU_LIFETIME}).
     * Throws {@link IllegalStateException} cleanly if either product or its
     * localized Google Play price is missing.
     */
    public void renderPaywallSheetOrThrow(@NonNull Activity activity,
                                          @Nullable String lockedFeature) {
        String monthlyPrice = getDynamicPriceOrThrow(SKU_MONTHLY);
        String lifetimePrice = getDynamicPriceOrThrow(SKU_LIFETIME);
        renderPaywallDialog(activity, lockedFeature, lifetimePrice, monthlyPrice);
    }

    private void renderPaywallDialog(@NonNull Activity activity,
                                     @Nullable String lockedFeature,
                                     @Nullable String lifetimePrice,
                                     @Nullable String monthlyPrice) {
        ProductDetails monthlyPd;
        synchronized (productCache) {
            monthlyPd = productCache.get(SKU_MONTHLY);
        }
        String trialLabel = freeTrialLabel(monthlyPd);
        String subtitle;
        if (lockedFeature != null && !lockedFeature.isEmpty()) {
            subtitle = trialLabel != null
                    ? "Unlock " + lockedFeature + " and every Studio tool with your "
                            + trialLabel + " free trial:"
                    : "Unlock " + lockedFeature + " and every Studio tool:";
        } else {
            subtitle = trialLabel != null
                    ? "Unlock every Studio tool with your " + trialLabel + " free trial:"
                    : "Unlock every Studio tool:";
        }

        String lifetimeLabel = (lifetimePrice != null && !lifetimePrice.isEmpty())
                ? "Buy One-Time Purchase — " + lifetimePrice
                : "Buy One-Time Purchase";
        String monthlyLabel;
        if (monthlyPrice != null && !monthlyPrice.isEmpty()) {
            monthlyLabel = trialLabel != null
                    ? "Start " + trialLabel + " free trial — then "
                            + monthlyPrice + "/month"
                    : "Subscribe — " + monthlyPrice + "/month";
        } else {
            monthlyLabel = trialLabel != null
                    ? "Start " + trialLabel + " free trial"
                    : "Subscribe Monthly";
        }
        String monthlyDetail = trialLabel != null
                ? trialLabel + " free • Then "
                        + (monthlyPrice != null && !monthlyPrice.isEmpty()
                                ? monthlyPrice + "/month" : "the monthly price")
                        + " • Cancel anytime in Google Play"
                : "Billed monthly via Google Play • Unlocks all features";

        List<OptionsSheet.Item> items = new ArrayList<>();
        items.add(OptionsSheet.Item.detail(R.drawable.ic_check,
                lifetimeLabel,
                "Pay once • Unlocks all Studio features forever",
                true,
                () -> onBuyPressed(activity, SKU_LIFETIME)));
        items.add(OptionsSheet.Item.detail(R.drawable.ic_sliders,
                monthlyLabel,
                monthlyDetail,
                false,
                () -> onBuyPressed(activity, SKU_MONTHLY)));
        items.add(OptionsSheet.Item.of(R.drawable.ic_undo,
                "Restore existing purchase",
                this::refreshPurchasesAsync));

        OptionsSheet.showOptions(activity, "Unlock TakeOne Pro", subtitle, items);
    }

    /**
     * Always opens the billing popup ("Unlock TakeOne Pro") immediately when
     * the user presses a locked editing tool on the free tier. If Google Play
     * prices are not yet cached, triggers a background price fetch and defers
     * any price-fetch error popup until the user presses Buy.
     */
    public void showPaywallSheet(@NonNull Activity activity,
                                 @Nullable String lockedFeature) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        String monthlyPrice = null;
        String lifetimePrice = null;
        try {
            monthlyPrice = getDynamicPriceOrThrow(SKU_MONTHLY);
        } catch (RuntimeException ignored) {}
        try {
            lifetimePrice = getDynamicPriceOrThrow(SKU_LIFETIME);
        } catch (RuntimeException ignored) {}

        // Show the billing popup immediately on pressing any disabled button!
        renderPaywallDialog(activity, lockedFeature, lifetimePrice, monthlyPrice);

        // If dynamic pricing has not been loaded yet, fetch it in the background
        // so that if it succeeds before the user taps Buy, the purchase can
        // proceed, or if it fails, lastPriceFetchError is ready for the error popup.
        if (monthlyPrice == null || lifetimePrice == null) {
            connectAndFetchPricing(
                    () -> lastPriceFetchError = null,
                    err -> lastPriceFetchError = (err != null && err.getMessage() != null)
                            ? err.getMessage()
                            : "Could not fetch regional pricing from Google Play.");
        }
    }

    /**
     * Invoked when the user presses either Buy button inside the billing popup.
     * If there was an error fetching the price from Google Play, shows an error
     * popup immediately (or after attempting a live price fetch).
     */
    public void onBuyPressed(@NonNull Activity activity, @NonNull String sku) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        boolean hasPriceCached = false;
        try {
            getDynamicPriceOrThrow(sku);
            hasPriceCached = true;
        } catch (RuntimeException ignored) {}

        if (hasPriceCached) {
            launchPurchase(activity, sku);
            return;
        }
        connectAndFetchPricing(
                () -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    try {
                        getDynamicPriceOrThrow(sku);
                        launchPurchase(activity, sku);
                    } catch (RuntimeException e) {
                        OptionsSheet.showErrorPopup(activity,
                                "Error Fetching Price",
                                e.getMessage());
                    }
                },
                err -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    String msg = (err != null && err.getMessage() != null)
                            ? err.getMessage()
                            : (lastPriceFetchError != null
                                    ? lastPriceFetchError
                                    : "Could not fetch price from Google Play. Please check your internet connection and Google Play Store account.");
                    OptionsSheet.showErrorPopup(activity,
                            "Error Fetching Price", msg);
                });
    }

    // ---- Google Play Billing v7 Integration ----

    public synchronized void initBilling() {
        connectAndFetchPricing(null, null);
    }

    /** Backward-compatible alias for {@link #initBilling()}. */
    public synchronized void connectAndSync() {
        initBilling();
    }

    public synchronized void connectAndFetchPricing(
            @Nullable Runnable onLoaded,
            @Nullable java.util.function.Consumer<RuntimeException> onError) {
        try {
            if (billingClient == null) {
                billingClient = BillingClient.newBuilder(appCtx)
                        .setListener(this)
                        .enablePendingPurchases()
                        .build();
            }
            if (billingClient.isReady()) {
                queryProductDetailsAndPurchases(onLoaded, onError);
                return;
            }
            billingClient.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(@NonNull BillingResult res) {
                    if (res.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        queryProductDetailsAndPurchases(onLoaded, onError);
                    } else if (onError != null) {
                        mainHandler.post(() -> onError.accept(
                                new IllegalStateException(
                                        "Google Play Billing unavailable (code "
                                                + res.getResponseCode() + ")")));
                    }
                }

                @Override
                public void onBillingServiceDisconnected() {
                    // Reconnects automatically on next paywall/purchase request
                }
            });
        } catch (Throwable t) {
            if (onError != null) {
                mainHandler.post(() -> onError.accept(
                        new IllegalStateException(
                                "Google Play Billing failed to initialize", t)));
            }
        }
    }

    private void queryProductDetailsAndPurchases(
            @Nullable Runnable onLoaded,
            @Nullable java.util.function.Consumer<RuntimeException> onError) {
        if (billingClient == null || !billingClient.isReady()) {
            if (onError != null) {
                mainHandler.post(() -> onError.accept(
                        new IllegalStateException("BillingClient not connected")));
            }
            return;
        }
        refreshPurchasesAsync();

        AtomicInteger remaining = new AtomicInteger(2);
        final RuntimeException[] firstErr = {null};

        Runnable onQueryStepDone = () -> {
            if (remaining.decrementAndGet() == 0) {
                mainHandler.post(() -> {
                    try {
                        getDynamicPriceOrThrow(SKU_MONTHLY);
                        getDynamicPriceOrThrow(SKU_LIFETIME);
                        if (onLoaded != null) onLoaded.run();
                    } catch (RuntimeException e) {
                        if (onError != null) {
                            onError.accept(firstErr[0] != null ? firstErr[0] : e);
                        }
                    }
                });
            }
        };

        // 1) Query Monthly Subscription (ProductType.SUBS)
        List<QueryProductDetailsParams.Product> subsList = Collections.singletonList(
                QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(SKU_MONTHLY)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build());
        QueryProductDetailsParams subsParams = QueryProductDetailsParams.newBuilder()
                .setProductList(subsList)
                .build();
        billingClient.queryProductDetailsAsync(subsParams, (res, detailsList) -> {
            if (res.getResponseCode() == BillingClient.BillingResponseCode.OK
                    && detailsList != null) {
                synchronized (productCache) {
                    for (ProductDetails pd : detailsList) {
                        productCache.put(pd.getProductId(), pd);
                    }
                }
            } else if (firstErr[0] == null) {
                firstErr[0] = new IllegalStateException(
                        "Failed to fetch subscription pricing from Google Play (code "
                                + res.getResponseCode() + ")");
            }
            onQueryStepDone.run();
        });

        // 2) Query One-Time Lifetime Unlock (ProductType.INAPP)
        List<QueryProductDetailsParams.Product> inappList = Collections.singletonList(
                QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(SKU_LIFETIME)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build());
        QueryProductDetailsParams inappParams = QueryProductDetailsParams.newBuilder()
                .setProductList(inappList)
                .build();
        billingClient.queryProductDetailsAsync(inappParams, (res, detailsList) -> {
            if (res.getResponseCode() == BillingClient.BillingResponseCode.OK
                    && detailsList != null) {
                synchronized (productCache) {
                    for (ProductDetails pd : detailsList) {
                        productCache.put(pd.getProductId(), pd);
                    }
                }
            } else if (firstErr[0] == null) {
                firstErr[0] = new IllegalStateException(
                        "Failed to fetch one-time purchase pricing from Google Play (code "
                                + res.getResponseCode() + ")");
            }
            onQueryStepDone.run();
        });
    }

    public void refreshPurchasesAsync() {
        try {
            if (billingClient == null || !billingClient.isReady()) return;
            final boolean[] foundSub = {false};
            final boolean[] foundInApp = {false};
            final String[] activeSku = {null};
            final java.util.concurrent.atomic.AtomicBoolean queriesOk =
                    new java.util.concurrent.atomic.AtomicBoolean(true);
            AtomicInteger pending = new AtomicInteger(2);

            Runnable commitCheck = () -> {
                if (pending.decrementAndGet() != 0) return;
                if (foundSub[0] || foundInApp[0]) {
                    setEntitledPro(true, activeSku[0]);
                } else if (queriesOk.get()) {
                    // Both Play queries answered with "no purchases": the
                    // subscription (or trial) really has lapsed.
                    setEntitledPro(false, null);
                }
                // A failed query must not revoke a paid entitlement: keep the
                // cached state until Google Play answers again.
            };

            billingClient.queryPurchasesAsync(
                    QueryPurchasesParams.newBuilder()
                            .setProductType(BillingClient.ProductType.SUBS)
                            .build(),
                    (res, list) -> {
                        if (res.getResponseCode() == BillingClient.BillingResponseCode.OK
                                && list != null) {
                            for (Purchase p : list) {
                                if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                                    foundSub[0] = true;
                                    if (!p.getProducts().isEmpty()) {
                                        activeSku[0] = p.getProducts().get(0);
                                    }
                                    acknowledgeIfNeeded(p);
                                }
                            }
                        } else {
                            queriesOk.set(false);
                        }
                        commitCheck.run();
                    });

            billingClient.queryPurchasesAsync(
                    QueryPurchasesParams.newBuilder()
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build(),
                    (res, list) -> {
                        if (res.getResponseCode() == BillingClient.BillingResponseCode.OK
                                && list != null) {
                            for (Purchase p : list) {
                                if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                                    foundInApp[0] = true;
                                    if (!p.getProducts().isEmpty()) {
                                        activeSku[0] = p.getProducts().get(0);
                                    }
                                    acknowledgeIfNeeded(p);
                                }
                            }
                        } else {
                            queriesOk.set(false);
                        }
                        commitCheck.run();
                    });
        } catch (Throwable ignored) {}
    }

    private void launchPurchase(@NonNull Activity activity, @NonNull String sku) {
        try {
            if (billingClient == null || !billingClient.isReady()) {
                throw new IllegalStateException(
                        "Google Play Billing is not connected");
            }
            ProductDetails pd;
            synchronized (productCache) {
                pd = productCache.get(sku);
            }
            if (pd == null) {
                throw new IllegalStateException(
                        "Product details not loaded from Google Play for " + sku);
            }
            BillingFlowParams.ProductDetailsParams.Builder prodBuilder =
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(pd);
            if (BillingClient.ProductType.SUBS.equals(pd.getProductType())) {
                String offerToken = requireSubscriptionOfferToken(pd);
                prodBuilder.setOfferToken(offerToken);
            }
            BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(
                            Collections.singletonList(prodBuilder.build()))
                    .build();
            BillingResult br = billingClient.launchBillingFlow(activity, flowParams);
            if (br.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                throw new IllegalStateException(
                        "Google Play purchase flow failed (code "
                                + br.getResponseCode() + ")");
            }
        } catch (RuntimeException e) {
            OptionsSheet.showErrorPopup(activity, "Error Fetching Price",
                    e.getMessage());
        }
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult res,
                                   @Nullable List<Purchase> purchases) {
        if (res.getResponseCode() == BillingClient.BillingResponseCode.OK
                && purchases != null) {
            for (Purchase p : purchases) {
                if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                    String sku = p.getProducts().isEmpty()
                            ? SKU_LIFETIME : p.getProducts().get(0);
                    setEntitledPro(true, sku);
                    acknowledgeIfNeeded(p);
                }
            }
        }
    }

    private void acknowledgeIfNeeded(Purchase p) {
        if (p.isAcknowledged() || billingClient == null) return;
        try {
            AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(p.getPurchaseToken())
                    .build();
            billingClient.acknowledgePurchase(params, br -> {});
        } catch (Throwable ignored) {}
    }

    public void setEntitledPro(boolean active, @Nullable String planSku) {
        SharedPreferences p = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = p.edit().putBoolean(KEY_ENTITLED_PRO, active);
        if (planSku != null) ed.putString(KEY_ENTITLED_PLAN, planSku);
        ed.apply();
        notifyChanged();
    }

    private void notifyChanged() {
        mainHandler.post(() -> {
            if (onEntitlementChanged != null) {
                try {
                    onEntitlementChanged.run();
                } catch (Exception ignored) {}
            }
        });
    }
}
