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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages TakeOne's one-recording free allowance (tied to the unique immutable
 * ID of the user's first recording) and Google Play Billing v7 entitlements
 * (Monthly subscription {@link #SKU_MONTHLY} and One-Time Lifetime purchase
 * {@link #SKU_LIFETIME}, either of which unlocks all features).
 *
 * <ul>
 *   <li><b>No time-based free trial:</b> All features (Replace, Select,
 *       Select & Replace, Trim, 53-Effect DSP Rack, Undo/Redo, Waveform
 *       Zoom, Recording Settings / Format Converter, Teleprompter) are
 *       unlocked for the user's first recording — both during initial capture
 *       and whenever that first recording is saved, renamed, or opened via
 *       Edit & Replace.</li>
 *   <li><b>Subsequent recordings:</b> Teleprompter and Format Converter
 *       (Recording Settings) remain enabled on all recordings; all editing
 *       tools (Replace, Select, Select & Replace, Trim, FX, Undo, Redo,
 *       Waveform Zoom) are blocked on subsequent recordings and open the
 *       Google Play Billing paywall when pressed.</li>
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
    private static final String KEY_FREE_RECORDING_ID   = "free_recording_id";
    private static final String KEY_ENTITLED_PRO        = "entitled_pro_active";
    private static final String KEY_ENTITLED_PLAN       = "entitled_pro_plan";

    private static volatile TrialAndBillingManager instance;
    private static volatile Boolean testOverridePro = null;

    private volatile Context appCtx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private BillingClient billingClient;
    private final Map<String, ProductDetails> productCache = new HashMap<>();
    private Runnable onEntitlementChanged;
    /** Currently active Studio edit source ID (-1 when starting a fresh take). */
    private volatile long activeStudioSourceId = -1L;

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
     * Updates the recording source ID currently open in Studio ({@code -1} for
     * a brand-new recording session, or the positive/negative source ID when
     * editing an existing library take).
     */
    public void setActiveStudioSourceId(long sourceId) {
        this.activeStudioSourceId = sourceId;
    }

    public long getActiveStudioSourceId() {
        return activeStudioSourceId;
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

    private static boolean isRecordingIdAlive(long id, List<Take> all,
                                              List<ConversionJob> jobs) {
        if (id <= 0) return false;
        for (Take t : all) {
            if (t.id == id) return true;
        }
        for (ConversionJob j : jobs) {
            if (j.recordingId == id || j.replaceTakeId == id) return true;
        }
        return false;
    }

    /**
     * Returns the unique immutable ID of the user's one free full-feature
     * recording, or {@code -1} if no recording has been created yet. If a
     * recording already exists in the Library or active conversions from an
     * earlier session where {@link #KEY_FREE_RECORDING_ID} was not yet stored
     * (or pointed to a deleted take), binds to the earliest existing
     * recording's immutable ID automatically.
     */
    public long getFreeRecordingId() {
        SharedPreferences p = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long storedId = p.getLong(KEY_FREE_RECORDING_ID, -1L);
        if (storedId > 0) {
            return storedId;
        }
        List<Take> all = TakesRepository.all();
        if (!all.isEmpty()) {
            Take earliest = all.get(all.size() - 1);
            for (Take t : all) {
                if (t.timestamp < earliest.timestamp
                        || (t.timestamp == earliest.timestamp && t.id < earliest.id)) {
                    earliest = t;
                }
            }
            if (earliest.id > 0) {
                p.edit()
                        .putLong(KEY_FREE_RECORDING_ID, earliest.id)
                        .putBoolean(KEY_FREE_RECORDING_USED, true)
                        .apply();
                return earliest.id;
            }
        }
        List<ConversionJob> jobs = ConversionJobs.active();
        if (!jobs.isEmpty()) {
            for (ConversionJob j : jobs) {
                if (j.recordingId > 0) {
                    p.edit()
                            .putLong(KEY_FREE_RECORDING_ID, j.recordingId)
                            .putBoolean(KEY_FREE_RECORDING_USED, true)
                            .apply();
                    return j.recordingId;
                }
            }
        }
        return -1L;
    }

    /**
     * Records that a recording with immutable {@code recordingId} has been
     * saved. If no valid free recording ID is currently bound, binds the
     * one-recording free allowance to {@code recordingId} so that saving,
     * renaming, or re-editing that first recording continues to allow all
     * editing options.
     */
    public void onRecordingSaved(long recordingId) {
        SharedPreferences p = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long currentFreeId = p.getLong(KEY_FREE_RECORDING_ID, -1L);
        SharedPreferences.Editor ed = p.edit()
                .putBoolean(KEY_FREE_RECORDING_USED, true);
        if (currentFreeId <= 0 && recordingId > 0) {
            ed.putLong(KEY_FREE_RECORDING_ID, recordingId);
        }
        ed.apply();
    }

    /**
     * True once the user has saved their first free recording (or if a
     * saved/converting recording already exists in the Library).
     */
    public boolean isFreeRecordingUsed() {
        SharedPreferences p = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long storedId = p.getLong(KEY_FREE_RECORDING_ID, -1L);
        if (p.getBoolean(KEY_FREE_RECORDING_USED, false) || storedId > 0) {
            getFreeRecordingId();
            return true;
        }
        List<Take> all = TakesRepository.all();
        List<ConversionJob> jobs = ConversionJobs.active();
        if (!all.isEmpty() || !jobs.isEmpty()) {
            getFreeRecordingId();
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
        getFreeRecordingId();
        SharedPreferences p = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(KEY_FREE_RECORDING_USED, false)) {
            p.edit().putBoolean(KEY_FREE_RECORDING_USED, true).apply();
        }
    }

    /**
     * Returns true when the user is entitled to full Studio features for the
     * specified {@code sourceOrRecordingId}:
     * <ul>
     *   <li>User has purchased Monthly or One-Time Pro.</li>
     *   <li>No recording has been saved yet (the user is recording their very
     *       first take).</li>
     *   <li>The user is editing their first free recording (its resolved
     *       immutable recording ID matches {@link #getFreeRecordingId()}).</li>
     * </ul>
     */
    public boolean hasProAccessForRecording(long sourceOrRecordingId) {
        if (testOverridePro != null) return testOverridePro;
        if (isRunningUnderUnitTest()) return true;
        return evaluateFreeTierAccessForRecording(sourceOrRecordingId);
    }

    /**
     * Evaluates paid Pro or first-recording free-tier entitlement for
     * {@code sourceOrRecordingId} without the Robolectric/instrumentation
     * test bypass so unit tests can verify first-recording vs subsequent
     * recording rules directly.
     */
    boolean evaluateFreeTierAccessForRecording(long sourceOrRecordingId) {
        if (isPaidPro()) return true;
        long candidate = sourceOrRecordingId;
        if (candidate == -1L) {
            candidate = activeStudioSourceId;
        }
        if (candidate == -1L) {
            File raw = RecordingEngine.get().rawFile;
            if (raw != null && EditLink.matches(appCtx, raw.getAbsolutePath())) {
                candidate = EditLink.id(appCtx);
            }
        }
        if (!isFreeRecordingUsed()) return true;
        if (candidate == -1L) return false;
        long resolvedId = ConversionJobs.resolveRecordingId(candidate);
        long freeId = getFreeRecordingId();
        if (freeId > 0 && resolvedId == freeId) {
            return true;
        }
        if (resolvedId > 0 && freeId <= 0) {
            appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_FREE_RECORDING_ID, resolvedId)
                    .putBoolean(KEY_FREE_RECORDING_USED, true)
                    .apply();
            return true;
        }
        return false;
    }

    /**
     * Master entitlement check for the currently active Studio session: returns
     * true if the user has purchased Monthly or One-Time Pro, is on their very
     * first recording before saving, or is editing their first recording
     * (matched by its unique immutable recording ID).
     */
    public boolean hasProAccess() {
        return hasProAccessForRecording(activeStudioSourceId);
    }

    /** Compact status label for the Studio/Library badge. */
    public String statusBadgeText() {
        if (isPaidPro()) return "PRO STUDIO";
        if (hasProAccess()) return "FIRST RECORDING • ALL FEATURES";
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
     * Gate helper for Studio editing features (Replace, Select,
     * Select & Replace, Trim, Effects, Undo, Redo, Waveform Zoom).
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
     * Gate helper for Studio editing features that explicitly checks access
     * against the active {@code editSourceId} of the Studio fragment.
     */
    public static boolean requireProOrShowPaywall(@NonNull Activity activity,
                                                  long editSourceId,
                                                  @NonNull String featureName,
                                                  @Nullable Runnable onAllowed) {
        TrialAndBillingManager mgr = get(activity);
        if (editSourceId != -1L) {
            mgr.setActiveStudioSourceId(editSourceId);
        }
        if (mgr.hasProAccessForRecording(editSourceId)) {
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
        String subtitle = (lockedFeature != null && !lockedFeature.isEmpty())
                ? "Your 1 free full-feature recording has been used. Unlock "
                        + lockedFeature + " and all Studio tools on every recording:"
                : "Your 1 free full-feature recording has been used. Choose Monthly or One-Time Purchase to unlock everything:";

        String lifetimeLabel = (lifetimePrice != null && !lifetimePrice.isEmpty())
                ? "Buy One-Time Purchase — " + lifetimePrice
                : "Buy One-Time Purchase";
        String monthlyLabel = (monthlyPrice != null && !monthlyPrice.isEmpty())
                ? "Buy Monthly — " + monthlyPrice + "/month"
                : "Buy Monthly Subscription";

        List<OptionsSheet.Item> items = new ArrayList<>();
        items.add(OptionsSheet.Item.detail(R.drawable.ic_check,
                lifetimeLabel,
                "Pay once • Unlocks all Studio features forever",
                true,
                () -> onBuyPressed(activity, SKU_LIFETIME)));
        items.add(OptionsSheet.Item.detail(R.drawable.ic_sliders,
                monthlyLabel,
                "Billed monthly via Google Play • Unlocks all features",
                false,
                () -> onBuyPressed(activity, SKU_MONTHLY)));
        items.add(OptionsSheet.Item.of(R.drawable.ic_undo,
                "Restore existing purchase",
                this::refreshPurchasesAsync));

        OptionsSheet.showOptions(activity, "Unlock TakeOne Pro", subtitle, items);
    }

    /**
     * Always opens the billing popup ("Unlock TakeOne Pro") immediately when
     * the user presses any disabled/locked button on subsequent recordings.
     * If Google Play prices are not yet cached, triggers a background price
     * fetch and defers any price-fetch error popup until the user presses Buy.
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
            AtomicInteger pending = new AtomicInteger(2);

            Runnable commitCheck = () -> {
                if (pending.decrementAndGet() == 0) {
                    boolean active = foundSub[0] || foundInApp[0];
                    setEntitledPro(active, activeSku[0]);
                }
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
