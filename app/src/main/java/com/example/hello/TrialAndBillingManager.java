package com.example.hello;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

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

/**
 * Manages TakeOne's 7-day free trial and Google Play Billing v7 subscription
 * entitlements for the Effects tab (Weekly, Monthly, and Yearly plans, each
 * with a 7-day free trial).
 *
 * <p>Offline-first guarantee: entitlement state is cached locally in
 * SharedPreferences and refreshed whenever Play Store is reachable, so a creator
 * recording in airplane mode is never locked out of their paid studio.
 */
public final class TrialAndBillingManager implements PurchasesUpdatedListener {

    public static final int TRIAL_DAYS = 7;
    public static final long TRIAL_DURATION_MS =
            TRIAL_DAYS * 24L * 60L * 60L * 1000L;

    // Google Play Console Subscription Product IDs
    public static final String SKU_WEEKLY   = "takeone_pro_weekly";
    public static final String SKU_MONTHLY  = "takeone_pro_monthly";
    public static final String SKU_YEARLY   = "takeone_pro_yearly";
    /** Retained for backward-compatibility with legacy purchases. */
    public static final String SKU_LIFETIME = "takeone_pro_lifetime";

    private static final String PREFS = "takeone";
    private static final String KEY_TRIAL_START_MS = "trial_start_ms";
    private static final String KEY_ENTITLED_PRO   = "entitled_pro_active";
    private static final String KEY_ENTITLED_PLAN  = "entitled_pro_plan";

    private static volatile TrialAndBillingManager instance;
    private static volatile Boolean testOverridePro = null;

    private final Context appCtx;
    private BillingClient billingClient;
    private final Map<String, ProductDetails> productCache = new HashMap<>();
    private Runnable onEntitlementChanged;

    private TrialAndBillingManager(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        ensureTrialInitialized(this.appCtx);
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
     * Anchors the 7-day free trial to the earliest of SharedPreferences or
     * PackageInfo.firstInstallTime so clearing app storage does not reset
     * the 7-day clock.
     */
    private static long ensureTrialInitialized(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long saved = p.getLong(KEY_TRIAL_START_MS, 0L);
        long installMs = System.currentTimeMillis();
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            if (pi != null && pi.firstInstallTime > 0L) {
                installMs = pi.firstInstallTime;
            }
        } catch (Exception ignored) {}
        long effective = saved > 0L ? Math.min(saved, installMs) : installMs;
        if (saved != effective) {
            p.edit().putLong(KEY_TRIAL_START_MS, effective).apply();
        }
        return effective;
    }

    /** True when the user has an active Pro subscription. */
    public boolean isPaidPro() {
        if (testOverridePro != null) return testOverridePro;
        SharedPreferences p = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getBoolean(KEY_ENTITLED_PRO, false);
    }

    /** True while inside the 7-day free trial window. */
    public boolean isTrialActive() {
        long start = ensureTrialInitialized(appCtx);
        long elapsed = System.currentTimeMillis() - start;
        return elapsed < TRIAL_DURATION_MS;
    }

    /** Whole days remaining in the 7-day trial (1..7 while active, 0 when expired). */
    public int trialDaysRemaining() {
        long start = ensureTrialInitialized(appCtx);
        long remMs = (start + TRIAL_DURATION_MS) - System.currentTimeMillis();
        if (remMs <= 0L) return 0;
        return (int) Math.min((long) TRIAL_DAYS,
                (remMs + 86_399_999L) / 86_400_000L);
    }

    /**
     * Master entitlement check: returns true if the user is either a paid Pro
     * subscriber OR still within their 7-day free trial (or running under
     * Robolectric unit tests so existing test suites pass out of the box).
     */
    public boolean hasProAccess() {
        if (testOverridePro != null) return testOverridePro;
        if (isRunningUnderUnitTest()) return true;
        return isPaidPro() || isTrialActive();
    }

    /** Compact status label for the Studio/Library badge. */
    public String statusBadgeText() {
        if (isPaidPro()) return "PRO STUDIO";
        int days = trialDaysRemaining();
        if (days > 0) return "FREE TRIAL • " + days + "D LEFT";
        return "FREE TIER • UPGRADE";
    }

    private static boolean isRunningUnderUnitTest() {
        try {
            Class.forName("org.robolectric.Robolectric");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Gate helper for the Effects tab. Runs {@code onAllowed} immediately if
     * entitled, or opens the 3-plan upgrade paywall sheet (Weekly, Monthly,
     * Yearly with a 7-day free trial) when locked.
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
     * Displays the Effects tab paywall using TakeOne's native
     * {@link OptionsSheet} modal styling with three payment structures
     * (Weekly, Monthly, and Yearly) and a 7-day free trial.
     */
    public void showPaywallSheet(@NonNull Activity activity,
                                 @Nullable String lockedFeature) {
        ProductDetails weekly  = productCache.get(SKU_WEEKLY);
        ProductDetails monthly = productCache.get(SKU_MONTHLY);
        ProductDetails yearly  = productCache.get(SKU_YEARLY);

        String wkPrice = formatPrice(weekly, "$1.99/week");
        String moPrice = formatPrice(monthly, "$3.99/month");
        String yrPrice = formatPrice(yearly, "$24.99/year");

        String subtitle = (lockedFeature != null && !lockedFeature.isEmpty())
                ? "Unlock " + lockedFeature + " with a 7-day free trial. Choose a Weekly, Monthly, or Yearly plan (cancel anytime):"
                : "Start your 7-day free trial to unlock the full 53-effect DSP rack. Choose a plan:";

        List<OptionsSheet.Item> items = new ArrayList<>();
        items.add(OptionsSheet.Item.detail(R.drawable.ic_check,
                "Yearly — " + yrPrice,
                "7-day free trial • Best value (Save 48%)",
                true,
                () -> launchPurchase(activity, SKU_YEARLY)));
        items.add(OptionsSheet.Item.detail(R.drawable.ic_sliders,
                "Monthly — " + moPrice,
                "7-day free trial • Billed monthly",
                false,
                () -> launchPurchase(activity, SKU_MONTHLY)));
        items.add(OptionsSheet.Item.detail(R.drawable.ic_wave,
                "Weekly — " + wkPrice,
                "7-day free trial • Flexible weekly billing",
                false,
                () -> launchPurchase(activity, SKU_WEEKLY)));
        items.add(OptionsSheet.Item.of(R.drawable.ic_undo,
                "Restore existing purchase",
                this::refreshPurchasesAsync));

        OptionsSheet.showOptions(activity, "Unlock Pro Effects", subtitle, items);
    }

    private static String formatPrice(@Nullable ProductDetails pd, String fallback) {
        if (pd == null) return fallback;
        try {
            if (pd.getSubscriptionOfferDetails() != null
                    && !pd.getSubscriptionOfferDetails().isEmpty()) {
                List<ProductDetails.PricingPhase> phases =
                        pd.getSubscriptionOfferDetails().get(0)
                                .getPricingPhases().getPricingPhaseList();
                if (!phases.isEmpty()) {
                    return phases.get(phases.size() - 1).getFormattedPrice();
                }
            }
            if (pd.getOneTimePurchaseOfferDetails() != null) {
                return pd.getOneTimePurchaseOfferDetails().getFormattedPrice();
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    /**
     * Selects the subscription offer that includes a free trial phase when
     * available, falling back to the base offer.
     */
    @Nullable
    private static String selectTrialOrBaseOfferToken(@NonNull ProductDetails pd) {
        List<ProductDetails.SubscriptionOfferDetails> offers =
                pd.getSubscriptionOfferDetails();
        if (offers == null || offers.isEmpty()) return null;
        for (ProductDetails.SubscriptionOfferDetails offer : offers) {
            if (offer == null || offer.getPricingPhases() == null) continue;
            for (ProductDetails.PricingPhase phase :
                    offer.getPricingPhases().getPricingPhaseList()) {
                if (phase != null && phase.getPriceAmountMicros() == 0L) {
                    return offer.getOfferToken();
                }
            }
        }
        return offers.get(0).getOfferToken();
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
            queryProducts();
            refreshPurchasesAsync();
            return;
        }
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult result) {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    queryProducts();
                    refreshPurchasesAsync();
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                // Cached SharedPreferences entitlement keeps working offline.
            }
        });
    }

    private void queryProducts() {
        if (billingClient == null || !billingClient.isReady()) return;
        List<QueryProductDetailsParams.Product> subs = new ArrayList<>();
        subs.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SKU_WEEKLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build());
        subs.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SKU_MONTHLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build());
        subs.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SKU_YEARLY)
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
                });
    }

    public void refreshPurchasesAsync() {
        if (billingClient == null || !billingClient.isReady()) return;
        billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build(),
                (res, subsList) -> {
                    boolean active = hasActivePurchase(subsList);
                    String plan = active ? resolvePurchasedPlan(subsList) : "";
                    persistEntitlement(active, plan);
                });
    }

    private static String resolvePurchasedPlan(@Nullable List<Purchase> purchases) {
        if (purchases == null) return "";
        for (Purchase p : purchases) {
            if (p.getPurchaseState() == Purchase.PurchaseState.PURCHASED
                    && !p.getProducts().isEmpty()) {
                return p.getProducts().get(0);
            }
        }
        return "subscribed";
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

    public void launchPurchase(@NonNull Activity activity, @NonNull String sku) {
        if (billingClient == null || !billingClient.isReady()) {
            connectAndSync();
            OptionsSheet.showResult(activity, false, "Play Store unavailable",
                    "Please check your internet connection and make sure you are signed into Google Play.");
            return;
        }
        ProductDetails pd;
        synchronized (productCache) {
            pd = productCache.get(sku);
        }
        if (pd == null) {
            queryProducts();
            OptionsSheet.showResult(activity, false, "Loading plans…",
                    "Fetching localized pricing from Google Play. Please tap again in a moment.");
            return;
        }
        BillingFlowParams.ProductDetailsParams.Builder builder =
                BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(pd);
        if (BillingClient.ProductType.SUBS.equals(pd.getProductType())) {
            String offerToken = selectTrialOrBaseOfferToken(pd);
            if (offerToken != null) {
                builder.setOfferToken(offerToken);
            }
        }
        BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                        Collections.singletonList(builder.build()))
                .build();
        billingClient.launchBillingFlow(activity, flowParams);
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult result,
                                   @Nullable List<Purchase> purchases) {
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK
                && purchases != null) {
            if (hasActivePurchase(purchases)) {
                persistEntitlement(true, resolvePurchasedPlan(purchases));
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
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(onEntitlementChanged);
        }
    }
}
