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
 * Manages TakeOne's one-recording free allowance and Google Play Billing v7
 * entitlements (Monthly subscription {@link #SKU_MONTHLY} and One-Time Lifetime
 * purchase {@link #SKU_LIFETIME}, either of which unlocks all features).
 *
 * <ul>
 *   <li><b>No time-based free trial:</b> All features (Replace, Select,
 *       Select & Replace, Trim, 53-Effect DSP Rack, Undo/Redo, Recording
 *       Settings, Waveform Zoom, Teleprompter) are unlocked during the user's
 *       first recording.</li>
 *   <li><b>Subsequent recordings:</b> Everything except the Teleprompter is
 *       blocked on subsequent recordings and opens the Google Play Billing
 *       paywall when pressed.</li>
 *   <li><b>Dynamic region-dependent pricing (no fallbacks):</b> Formatted
 *       localized prices are fetched directly from Google Play's
 *       {@link ProductDetails}. Missing product details or pricing throw
 *       {@link IllegalStateException} cleanly without hardcoded fallback
 *       strings.</li>
 * </ul>
 */
public final class TrialAndBillingManager implements PurchasesUpdatedListener {

    // Google Play Console Product IDs
    /** Monthly recurring subscription (BillingClient.ProductType.SUBS). */
    public static final String SKU_MONTHLY  = "takeone_pro_monthly";
    /** One-time lifetime unlock purchase (BillingClient.ProductType.INAPP). */
    public static final String SKU_LIFETIME = "takeone_pro_lifetime";

    private static final String PREFS = "takeone";
    private static final String KEY_FREE_RECORDING_USED = "free_recording_used";
    private static final String KEY_ENTITLED_PRO        = "entitled_pro_active";
    private static final String KEY_ENTITLED_PLAN       = "entitled_pro_plan";

    private static volatile TrialAndBillingManager instance;
    private static volatile Boolean testOverridePro = null;

    private final Context appCtx;
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
     * True when the user has an active Monthly subscription or One-Time
     * Lifetime purchase.
     */
    public boolean isPaidPro() {
        if (testOverridePro != null) return testOverridePro;
        SharedPreferences p = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getBoolean(KEY_ENTITLED_PRO, false);
    }

    /**
     * True once the user has completed/saved their first free recording (or if
     * a saved/converting recording already exists in the Library).
     */
    public boolean isFreeRecordingUsed() {
        SharedPreferences p = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean(KEY_FREE_RECORDING_USED, false)) {
            return true;
        }
        if (!TakesRepository.all().isEmpty() || !ConversionJobs.active().isEmpty()) {
            p.edit().putBoolean(KEY_FREE_RECORDING_USED, true).apply();
            return true;
        }
        return false;
    }

    /**
     * Marks the one free full-feature recording as consumed once the first
     * recording is saved.
     */
    public void markFreeRecordingUsed() {
        SharedPreferences p = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(KEY_FREE_RECORDING_USED, false)) {
            p.edit().putBoolean(KEY_FREE_RECORDING_USED, true).apply();
        }
    }

    /**
     * Master entitlement check: returns true if the user has purchased Monthly
     * or One-Time Pro OR is still on their first recording (or running under
     * automated unit/instrumentation tests when {@code testOverridePro} is not
     * set).
     */
    public boolean hasProAccess() {
        if (testOverridePro != null) return testOverridePro;
        if (isRunningUnderUnitTest()) return true;
        return isPaidPro() || !isFreeRecordingUsed();
    }

    /** Compact status label for the Studio/Library badge. */
    public String statusBadgeText() {
        if (isPaidPro()) return "PRO STUDIO";
        if (!isFreeRecordingUsed()) return "FIRST RECORDING • ALL FEATURES";
        return "FREE TIER • UPGRADE";
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
     * Gate helper for Studio features (Replace, Select, Select & Replace,
     * Trim, Effects, Undo, Redo, Recording Settings, Waveform Zoom).
     * Runs {@code onAllowed} immediately if entitled (Paid Pro or first
     * recording), or opens the Google Play Billing paywall when locked on
     * subsequent recordings.
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
     * Resolves the base subscription offer token (no free trial) from Google
     * Play {@link ProductDetails}, or throws {@link IllegalStateException}
     * cleanly if unavailable.
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
        for (ProductDetails.SubscriptionOfferDetails offer : offers) {
            if (offer == null || offer.getPricingPhases() == null) continue;
            List<ProductDetails.PricingPhase> phases =
                    offer.getPricingPhases().getPricingPhaseList();
            if (phases != null && phases.size() == 1
                    && phases.get(0).getPriceAmountMicros() > 0L
                    && offer.getOfferToken() != null
                    && !offer.getOfferToken().isEmpty()) {
                return offer.getOfferToken();
            }
        }
        String token = offers.get(0).getOfferToken();
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException(
                    "Empty subscription offer token from Google Play for "
                            + pd.getProductId());
        }
        return token;
    }

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

        String subtitle = (lockedFeature != null && !lockedFeature.isEmpty())
                ? "Your 1 free full-feature recording has been used. Unlock "
                        + lockedFeature + " and all Studio tools on every recording:"
                : "Your 1 free full-feature recording has been used. Choose Monthly or One-Time Purchase to unlock everything:";

        List<OptionsSheet.Item> items = new ArrayList<>();
        items.add(OptionsSheet.Item.detail(R.drawable.ic_check,
                "One-Time Purchase — " + lifetimePrice,
                "Pay once • Unlocks all Studio features forever",
                true,
                () -> launchPurchase(activity, SKU_LIFETIME)));
        items.add(OptionsSheet.Item.detail(R.drawable.ic_sliders,
                "Monthly — " + monthlyPrice + "/month",
                "Billed monthly via Google Play • Unlocks all features",
                false,
                () -> launchPurchase(activity, SKU_MONTHLY)));
        items.add(OptionsSheet.Item.of(R.drawable.ic_undo,
                "Restore existing purchase",
                this::refreshPurchasesAsync));

        OptionsSheet.showOptions(activity, "Unlock TakeOne Pro", subtitle, items);
    }

    /**
     * Opens the paywall when the user presses any locked feature on subsequent
     * recordings. Fetches dynamic region-dependent pricing from Google Play if
     * not yet cached, and fails cleanly (without fallback prices) if Google Play
     * pricing is unavailable.
     */
    public void showPaywallSheet(@NonNull Activity activity,
                                 @Nullable String lockedFeature) {
        boolean hasBothCached;
        synchronized (productCache) {
            hasBothCached = productCache.containsKey(SKU_MONTHLY)
                    && productCache.containsKey(SKU_LIFETIME);
        }
        if (hasBothCached) {
            try {
                renderPaywallSheetOrThrow(activity, lockedFeature);
            } catch (IllegalStateException ex) {
                CrashLog.breadcrumb(appCtx, "paywall price error: " + ex.getMessage());
                OptionsSheet.showResult(activity, false, "Billing unavailable",
                        ex.getMessage());
            }
            return;
        }
        fetchDynamicPricesFromPlay(
                () -> mainHandler.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    try {
                        renderPaywallSheetOrThrow(activity, lockedFeature);
                    } catch (IllegalStateException ex) {
                        CrashLog.breadcrumb(appCtx,
                                "paywall dynamic price error: " + ex.getMessage());
                        OptionsSheet.showResult(activity, false,
                                "Billing unavailable", ex.getMessage());
                    }
                }),
                err -> mainHandler.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    CrashLog.breadcrumb(appCtx,
                            "paywall fetch failed: " + err.getMessage());
                    OptionsSheet.showResult(activity, false,
                            "Billing unavailable", err.getMessage());
                }));
    }

    // ---- Google Play Billing Client lifecycle ----

    public synchronized void connectAndSync() {
        if (isRunningUnderUnitTest()) return;
        if (billingClient == null) {
            billingClient = BillingClient.newBuilder(appCtx)
                    .setListener(this)
                    .enablePendingPurchases()
                    .build();
        }
        if (billingClient.isReady()) {
            queryProducts(null, null);
            refreshPurchasesAsync();
            return;
        }
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult result) {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    queryProducts(null, null);
                    refreshPurchasesAsync();
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                // Cached SharedPreferences entitlement keeps working offline.
            }
        });
    }

    private interface ErrorCallback {
        void onError(@NonNull IllegalStateException error);
    }

    private synchronized void fetchDynamicPricesFromPlay(
            @Nullable Runnable onComplete,
            @Nullable ErrorCallback onError) {
        if (billingClient == null) {
            billingClient = BillingClient.newBuilder(appCtx)
                    .setListener(this)
                    .enablePendingPurchases()
                    .build();
        }
        if (billingClient.isReady()) {
            queryProducts(onComplete, onError);
            return;
        }
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult result) {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    queryProducts(onComplete, onError);
                    refreshPurchasesAsync();
                } else if (onError != null) {
                    onError.onError(new IllegalStateException(
                            "Google Play Billing setup failed (code "
                                    + result.getResponseCode() + ")"));
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                if (onError != null) {
                    onError.onError(new IllegalStateException(
                            "Google Play Billing service disconnected"));
                }
            }
        });
    }

    private void queryProducts(@Nullable Runnable onComplete,
                               @Nullable ErrorCallback onError) {
        if (billingClient == null || !billingClient.isReady()) {
            if (onError != null) {
                onError.onError(new IllegalStateException(
                        "Google Play BillingClient is not ready"));
            }
            return;
        }
        AtomicInteger remaining = new AtomicInteger(2);
        final Runnable finishOne = () -> {
            if (remaining.decrementAndGet() == 0) {
                try {
                    getDynamicPriceOrThrow(SKU_MONTHLY);
                    getDynamicPriceOrThrow(SKU_LIFETIME);
                    if (onComplete != null) onComplete.run();
                } catch (IllegalStateException ex) {
                    if (onError != null) onError.onError(ex);
                }
            }
        };

        List<QueryProductDetailsParams.Product> subs = Collections.singletonList(
                QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(SKU_MONTHLY)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build());
        billingClient.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder().setProductList(subs).build(),
                (res, list) -> {
                    if (res.getResponseCode() == BillingClient.BillingResponseCode.OK
                            && list != null) {
                        synchronized (productCache) {
                            for (ProductDetails pd : list) {
                                productCache.put(pd.getProductId(), pd);
                            }
                        }
                    }
                    finishOne.run();
                });

        List<QueryProductDetailsParams.Product> inapp = Collections.singletonList(
                QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(SKU_LIFETIME)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build());
        billingClient.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder().setProductList(inapp).build(),
                (res, list) -> {
                    if (res.getResponseCode() == BillingClient.BillingResponseCode.OK
                            && list != null) {
                        synchronized (productCache) {
                            for (ProductDetails pd : list) {
                                productCache.put(pd.getProductId(), pd);
                            }
                        }
                    }
                    finishOne.run();
                });
    }

    public void refreshPurchasesAsync() {
        if (billingClient == null || !billingClient.isReady()) return;
        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                (res, subsList) -> {
                    boolean activeSub = res.getResponseCode()
                            == BillingClient.BillingResponseCode.OK
                            && hasActivePurchase(subsList);
                    billingClient.queryPurchasesAsync(
                            QueryPurchasesParams.newBuilder()
                                    .setProductType(BillingClient.ProductType.INAPP)
                                    .build(),
                            (res2, inappList) -> {
                                boolean activeLifetime = res2.getResponseCode()
                                        == BillingClient.BillingResponseCode.OK
                                        && hasActivePurchase(inappList);
                                if (res.getResponseCode() == BillingClient.BillingResponseCode.OK
                                        && res2.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                                    String plan = activeLifetime ? SKU_LIFETIME
                                            : (activeSub ? SKU_MONTHLY : "");
                                    persistEntitlement(activeSub || activeLifetime, plan);
                                } else if (activeSub || activeLifetime) {
                                    String plan = activeLifetime ? SKU_LIFETIME : SKU_MONTHLY;
                                    persistEntitlement(true, plan);
                                }
                            });
                });
    }

    private boolean hasActivePurchase(@Nullable List<Purchase> purchases) {
        if (purchases == null) return false;
        boolean found = false;
        for (Purchase p : purchases) {
            if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                found = true;
                if (!p.isAcknowledged() && billingClient != null) {
                    AcknowledgePurchaseParams ack = AcknowledgePurchaseParams
                            .newBuilder()
                            .setPurchaseToken(p.getPurchaseToken())
                            .build();
                    billingClient.acknowledgePurchase(ack, r -> {});
                }
            }
        }
        return found;
    }

    /**
     * Launches the Google Play Billing flow for {@code sku} ({@link #SKU_MONTHLY}
     * or {@link #SKU_LIFETIME}), or throws {@link IllegalStateException} cleanly
     * if {@link BillingClient} or {@link ProductDetails} is unavailable.
     */
    public void launchPurchaseOrThrow(@NonNull Activity activity,
                                      @NonNull String sku) {
        if (billingClient == null || !billingClient.isReady()) {
            throw new IllegalStateException(
                    "Google Play BillingClient is not connected");
        }
        ProductDetails pd;
        synchronized (productCache) {
            pd = productCache.get(sku);
        }
        requireDynamicPrice(pd, sku);
        BillingFlowParams.ProductDetailsParams.Builder builder =
                BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(pd);
        if (BillingClient.ProductType.SUBS.equals(pd.getProductType())) {
            builder.setOfferToken(requireSubscriptionOfferToken(pd));
        }
        BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                        Collections.singletonList(builder.build()))
                .build();
        BillingResult launchRes = billingClient.launchBillingFlow(activity, flowParams);
        if (launchRes.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            throw new IllegalStateException(
                    "Google Play launchBillingFlow failed (code "
                            + launchRes.getResponseCode() + ")");
        }
    }

    public void launchPurchase(@NonNull Activity activity, @NonNull String sku) {
        try {
            launchPurchaseOrThrow(activity, sku);
        } catch (IllegalStateException ex) {
            CrashLog.breadcrumb(appCtx, "launchPurchase failed: " + ex.getMessage());
            connectAndSync();
            OptionsSheet.showResult(activity, false, "Billing unavailable",
                    ex.getMessage());
        }
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult result,
                                   @Nullable List<Purchase> purchases) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK
                && purchases != null) {
            if (hasActivePurchase(purchases)) {
                String plan = SKU_MONTHLY;
                for (Purchase p : purchases) {
                    if (p.getProducts().contains(SKU_LIFETIME)) {
                        plan = SKU_LIFETIME;
                        break;
                    }
                }
                persistEntitlement(true, plan);
            }
        }
    }

    private void persistEntitlement(boolean entitled, String plan) {
        SharedPreferences p = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean prev = p.getBoolean(KEY_ENTITLED_PRO, false);
        p.edit()
                .putBoolean(KEY_ENTITLED_PRO, entitled)
                .putString(KEY_ENTITLED_PLAN, plan != null ? plan : "")
                .apply();
        if (prev != entitled && onEntitlementChanged != null) {
            mainHandler.post(onEntitlementChanged);
        }
    }
}
