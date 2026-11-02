package com.example.hello;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdLoader;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.appopen.AppOpenAd;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdOptions;
import com.google.android.gms.ads.nativead.NativeAdView;

import java.lang.ref.WeakReference;

/**
 * Centralized, audio-safe Google AdMob controller for TakeOne with offline
 * caching, automatic network-reconnect recovery, and in-house offline fallback.
 *
 * <ul>
 *   <li><b>Official Google Test Ad Unit IDs:</b> Configured with Google's
 *       dedicated Android test App ID and test Ad Unit IDs for Native Advanced,
 *       Anchored Adaptive Banner, Interstitial, App Open, and Rewarded formats.</li>
 *   <li><b>Strict Audio-Studio Safety:</b> Mutes all ad audio globally
 *       ({@code MobileAds.setAppMuted(true)} and {@code setAppVolume(0f)}) so
 *       ads never hijack {@code AudioFocus}, bleed into a live microphone, or
 *       blast into studio monitoring headphones. Never renders ads inside
 *       {@link StudioFragment} while recording, pausing, scrubbing, or using
 *       the Teleprompter / 52-effect DSP Rack.</li>
 *   <li><b>Post-Save, Post-Export &amp; Pre-Share Interstitials:</b> Fires
 *       reliably on take saves ({@link #onTakeSaved(Activity)}), folder exports
 *       ({@link #onExportCompleted(Activity)}), and Share sheet launches
 *       ({@link #onShareRequested(Activity, Runnable)}). Explicitly allows
 *       post-save interstitials while {@link RecordingEngine#isSaving()} is
 *       handing the raw file off to the background conversion worker thread.</li>
 *   <li><b>Offline Pre-Caching, Auto-Reconnect &amp; House Ad Fallback:</b>
 *       <ul>
 *         <li>Preloads {@link InterstitialAd} and {@link AppOpenAd} into RAM
 *             while online (and on entering {@link StudioFragment}) so if the
 *             user switches to Airplane Mode mid-recording, the cached
 *             post-save interstitial still displays offline (valid for up to
 *             1 hour in RAM; 4 hours for App Open).</li>
 *         <li>Registers a {@link ConnectivityManager.NetworkCallback} so when
 *             the device reconnects to the internet after starting offline,
 *             AdMob automatically initializes, reloads the Library card, and
 *             preloads interstitials without restarting the app.</li>
 *         <li>Renders a non-intrusive offline in-house card inside
 *             {@code libraryAdContainer} when no network is available and
 *             replaces it automatically once a live AdMob ad loads.</li>
 *       </ul>
 *   </li>
 * </ul>
 */
public final class AdMobManager {

    // ---- Official Google AdMob Android Test IDs ----
    public static final String TEST_APP_ID =
            "ca-app-pub-3940256099942544~3347511713";
    public static final String TEST_ADAPTIVE_BANNER_ID =
            "ca-app-pub-3940256099942544/9214589741";
    public static final String TEST_BANNER_ID =
            "ca-app-pub-3940256099942544/6300978111";
    public static final String TEST_INTERSTITIAL_ID =
            "ca-app-pub-3940256099942544/1033173712";
    public static final String TEST_NATIVE_ADVANCED_ID =
            "ca-app-pub-3940256099942544/2247696110";
    public static final String TEST_APP_OPEN_ID =
            "ca-app-pub-3940256099942544/9257395921";
    public static final String TEST_REWARDED_ID =
            "ca-app-pub-3940256099942544/5224354917";

    // ---- Non-intrusive Frequency Cap & Cooldown Constants ----
    /**
     * Completed user workflows (take saved, folder exported, or shared) per
     * interstitial. Set to 1 so every completed save, export, or share is
     * eligible (each workflow already involves multiple user actions).
     */
    public static final int INTERSTITIAL_ACTION_INTERVAL = 1;

    /** Minimum 15-second cooldown between back-to-back full-screen ads. */
    public static final long INTERSTITIAL_COOLDOWN_MS = 15_000L;

    /** Minimum 10-minute cooldown between App Open ads on warm resume. */
    public static final long APP_OPEN_COOLDOWN_MS = 10L * 60_000L;

    /** Google AdMob Interstitial ads cached in RAM expire after 1 hour. */
    public static final long INTERSTITIAL_EXPIRY_MS = 60L * 60_000L;

    /** Google AdMob App Open cached ads expire after 4 hours. */
    public static final long APP_OPEN_EXPIRY_MS = 4L * 3600_000L;

    /** Grace window to display an interstitial that finishes loading right after save. */
    private static final long LATE_INTERSTITIAL_GRACE_MS = 4_000L;

    /** Delay after Studio -> Library transition before launching Interstitial AdActivity. */
    private static final long POST_SAVE_TRANSITION_DELAY_MS = 350L;

    private static final String PREFS = "takeone";
    static final String KEY_AD_COMPLETED_ACTIONS = "ad_completed_actions";
    static final String KEY_LAST_FULLSCREEN_AD_MS = "ad_last_fullscreen_ms";
    static final String KEY_LAST_APP_OPEN_AD_MS = "ad_last_app_open_ms";

    private static volatile AdMobManager instance;
    private static volatile Boolean testOverrideAdsEnabled = null;

    private volatile Context appCtx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean sdkInitialized = false;
    private volatile boolean sdkInitInProgress = false;
    private volatile boolean networkCallbackRegistered = false;
    private volatile boolean audioPlaybackActive = false;
    private volatile boolean showingFullScreenAd = false;
    private volatile boolean coldStartSeen = false;

    private InterstitialAd cachedInterstitial;
    private long interstitialLoadedAtMs = 0L;
    private boolean loadingInterstitial = false;
    private WeakReference<Activity> pendingInterstitialActivityRef;
    private long pendingInterstitialRequestedAtMs = 0L;

    private AppOpenAd cachedAppOpenAd;
    private long appOpenLoadedAtMs = 0L;
    private boolean loadingAppOpen = false;

    private NativeAd activeNativeAd;
    private AdView activeBannerAdView;
    private boolean libraryAdLoaded = false;
    private boolean showingOfflineHouseAd = false;
    private WeakReference<Activity> boundLibraryActivityRef;
    private WeakReference<FrameLayout> boundLibraryContainerRef;
    private boolean boundLibraryHideForUiMode = false;

    private AdMobManager(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public static AdMobManager get(@NonNull Context ctx) {
        AdMobManager mgr = instance;
        if (mgr == null) {
            synchronized (AdMobManager.class) {
                mgr = instance;
                if (mgr == null) {
                    mgr = new AdMobManager(ctx);
                    instance = mgr;
                }
            }
        }
        if (ctx.getApplicationContext() != mgr.appCtx) {
            mgr.appCtx = ctx.getApplicationContext();
        }
        return mgr;
    }

    /** Test hook for unit/instrumentation tests to force-enable or disable ads. */
    public static void setTestOverrideAdsEnabled(@Nullable Boolean override) {
        testOverrideAdsEnabled = override;
    }

    /**
     * Returns true when ads should be served. Automatically suppresses all ads
     * if the user owns a paid Pro entitlement (for future hybrid releases) or
     * if running inside automated Robolectric/Instrumentation suites unless
     * explicitly overridden in {@link #setTestOverrideAdsEnabled(Boolean)}.
     */
    public boolean isAdsEnabled() {
        if (testOverrideAdsEnabled != null) {
            return testOverrideAdsEnabled;
        }
        if (TrialAndBillingManager.get(appCtx).isPaidPro()) {
            return false;
        }
        if (isRunningUnderAutomatedTest()) {
            return false;
        }
        return true;
    }

    private static boolean isRunningUnderAutomatedTest() {
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
     * Checks whether the device currently has an active internet-capable network.
     */
    public boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network nw = cm.getActiveNetwork();
                if (nw == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(nw);
                return caps != null && (caps.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET));
            } else {
                //noinspection deprecation
                android.net.NetworkInfo info = cm.getActiveNetworkInfo();
                //noinspection deprecation
                return info != null && info.isConnected();
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Registers a system network listener so if the app starts without internet,
     * the moment Wi-Fi or mobile data connects, AdMob automatically initializes,
     * preloads full-screen ads, and upgrades the offline house card in
     * {@link LibraryFragment} to a live AdMob ad.
     */
    private synchronized void registerNetworkReconnectCallback() {
        if (networkCallbackRegistered) return;
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(
                        new ConnectivityManager.NetworkCallback() {
                            @Override
                            public void onAvailable(@NonNull Network network) {
                                mainHandler.post(() -> onNetworkReconnected());
                            }
                        });
                networkCallbackRegistered = true;
            }
        } catch (Throwable ignored) {}
    }

    private void onNetworkReconnected() {
        if (!isAdsEnabled()) return;
        if (!sdkInitialized && !sdkInitInProgress) {
            init(appCtx);
        } else {
            preloadInterstitial();
            preloadAppOpenAd();
        }
        Activity act = boundLibraryActivityRef != null
                ? boundLibraryActivityRef.get() : null;
        FrameLayout container = boundLibraryContainerRef != null
                ? boundLibraryContainerRef.get() : null;
        if (act != null && container != null
                && (!libraryAdLoaded || showingOfflineHouseAd)) {
            bindLibraryAd(act, container, boundLibraryHideForUiMode);
        }
    }

    /**
     * Initializes the Google Mobile Ads SDK, registers the automatic
     * network-reconnect listener, enforces global ad audio muting so ads never
     * disrupt microphone capture or audio focus, and preloads background
     * Interstitial and App Open inventory into RAM.
     */
    public synchronized void init(@NonNull Context context) {
        this.appCtx = context.getApplicationContext();
        if (!isAdsEnabled()) return;
        registerNetworkReconnectCallback();
        if (sdkInitialized) {
            applyAudioSafeSettings();
            preloadInterstitial();
            preloadAppOpenAd();
            return;
        }
        if (sdkInitInProgress) return;
        sdkInitInProgress = true;
        try {
            MobileAds.initialize(appCtx, initializationStatus -> {
                sdkInitialized = true;
                sdkInitInProgress = false;
                mainHandler.post(() -> {
                    applyAudioSafeSettings();
                    preloadInterstitial();
                    preloadAppOpenAd();
                    Activity act = boundLibraryActivityRef != null
                            ? boundLibraryActivityRef.get() : null;
                    FrameLayout container = boundLibraryContainerRef != null
                            ? boundLibraryContainerRef.get() : null;
                    if (act != null && container != null && showingOfflineHouseAd
                            && isNetworkAvailable()) {
                        bindLibraryAd(act, container, boundLibraryHideForUiMode);
                    }
                });
            });
        } catch (Throwable ignored) {
            sdkInitInProgress = false;
        }
    }

    /**
     * Globally mutes AdMob video/rich-media audio so an ad never steals
     * Android AudioFocus or plays over the device speaker in an audio studio.
     */
    public void applyAudioSafeSettings() {
        try {
            MobileAds.setAppMuted(true);
            MobileAds.setAppVolume(0f);
        } catch (Throwable ignored) {}
    }

    public void setAudioPlaybackActive(boolean active) {
        this.audioPlaybackActive = active;
    }

    public boolean isAudioPlaybackActive() {
        return audioPlaybackActive;
    }

    // ---- Placement 1: Library Native Advanced Card (with Adaptive Banner + Offline House Fallback) ----

    /**
     * Loads a non-intrusive Native Advanced ad styled like a TakeOne library
     * card into {@code container} (located below the filter chips in
     * {@link LibraryFragment}, far away from the bottom Record FAB).
     * <ul>
     *   <li>If Native Advanced fails to load while online, falls back to an
     *       Anchored Adaptive Banner.</li>
     *   <li>If the device is offline (or both online requests fail), renders a
     *       sleek in-house offline card promoting upcoming TakeOne Pro features,
     *       and automatically swaps it for a live AdMob ad as soon as internet
     *       reconnects.</li>
     * </ul>
     */
    public void bindLibraryAd(@NonNull Activity activity,
                              @Nullable FrameLayout container,
                              boolean hideForUiMode) {
        if (container == null) return;
        boundLibraryActivityRef = new WeakReference<>(activity);
        boundLibraryContainerRef = new WeakReference<>(container);
        boundLibraryHideForUiMode = hideForUiMode;

        if (!isAdsEnabled() || hideForUiMode) {
            container.setVisibility(View.GONE);
            return;
        }
        if (libraryAdLoaded && !showingOfflineHouseAd
                && container.getChildCount() > 0) {
            container.setVisibility(View.VISIBLE);
            return;
        }
        if (!isNetworkAvailable()) {
            bindOfflineHouseAdFallback(activity, container);
            return;
        }
        if (container.getChildCount() == 0) {
            bindOfflineHouseAdFallback(activity, container);
        }
        try {
            AdLoader adLoader = new AdLoader.Builder(activity, TEST_NATIVE_ADVANCED_ID)
                    .forNativeAd(nativeAd -> {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            nativeAd.destroy();
                            return;
                        }
                        if (activeNativeAd != null && activeNativeAd != nativeAd) {
                            try {
                                activeNativeAd.destroy();
                            } catch (Throwable ignored) {}
                        }
                        activeNativeAd = nativeAd;
                        libraryAdLoaded = true;
                        showingOfflineHouseAd = false;
                        NativeAdView adView = (NativeAdView) LayoutInflater
                                .from(activity)
                                .inflate(R.layout.item_native_ad, container, false);
                        populateNativeAdView(nativeAd, adView);
                        container.removeAllViews();
                        container.addView(adView);
                        if (!boundLibraryHideForUiMode) {
                            container.setVisibility(View.VISIBLE);
                        }
                    })
                    .withAdListener(new AdListener() {
                        @Override
                        public void onAdFailedToLoad(@NonNull LoadAdError error) {
                            loadAdaptiveBannerFallback(activity, container);
                        }
                    })
                    .withNativeAdOptions(new NativeAdOptions.Builder()
                            .setRequestMultipleImages(false)
                            .build())
                    .build();
            adLoader.loadAd(new AdRequest.Builder().build());
        } catch (Throwable ignored) {
            loadAdaptiveBannerFallback(activity, container);
        }
    }

    /**
     * Renders an offline-capable in-house promo card inside {@code container}
     * using {@code R.layout.item_native_ad} when the device has no internet
     * connection or while live ads are still fetching.
     */
    public void bindOfflineHouseAdFallback(@NonNull Context context,
                                           @NonNull FrameLayout container) {
        try {
            View card = LayoutInflater.from(context)
                    .inflate(R.layout.item_native_ad, container, false);
            TextView badge = card.findViewById(R.id.adAttributionBadge);
            TextView headline = card.findViewById(R.id.adHeadline);
            TextView body = card.findViewById(R.id.adBody);
            TextView cta = card.findViewById(R.id.adCallToAction);
            ImageView icon = card.findViewById(R.id.adAppIcon);

            if (badge != null) badge.setText("PRO");
            if (headline != null) {
                headline.setText("TakeOne Pro Studio • Coming Soon");
            }
            if (body != null) {
                body.setText("Hardware mic switching & multi-channel editing in upcoming releases");
                body.setVisibility(View.VISIBLE);
            }
            if (cta != null) {
                cta.setText("Free Now");
                cta.setVisibility(View.VISIBLE);
            }
            if (icon != null) {
                icon.setImageResource(R.drawable.ic_mic);
                icon.setVisibility(View.VISIBLE);
            }
            container.removeAllViews();
            container.addView(card);
            libraryAdLoaded = true;
            showingOfflineHouseAd = true;
            if (!boundLibraryHideForUiMode) {
                container.setVisibility(View.VISIBLE);
            }
        } catch (Throwable ignored) {}
    }

    public boolean isShowingOfflineHouseAd() {
        return showingOfflineHouseAd;
    }

    private void populateNativeAdView(@NonNull NativeAd nativeAd,
                                      @NonNull NativeAdView adView) {
        TextView headlineView = adView.findViewById(R.id.adHeadline);
        TextView bodyView = adView.findViewById(R.id.adBody);
        TextView ctaView = adView.findViewById(R.id.adCallToAction);
        ImageView iconView = adView.findViewById(R.id.adAppIcon);

        adView.setHeadlineView(headlineView);
        adView.setBodyView(bodyView);
        adView.setCallToActionView(ctaView);
        adView.setIconView(iconView);

        if (headlineView != null) {
            headlineView.setText(nativeAd.getHeadline());
        }
        if (bodyView != null) {
            String body = nativeAd.getBody();
            String advertiser = nativeAd.getAdvertiser();
            String sub = (body != null && !body.trim().isEmpty())
                    ? body.trim()
                    : (advertiser != null ? advertiser.trim() : "Sponsored");
            bodyView.setText(sub);
            bodyView.setVisibility(View.VISIBLE);
        }
        if (ctaView != null) {
            String cta = nativeAd.getCallToAction();
            if (cta != null && !cta.trim().isEmpty()) {
                ctaView.setText(cta.trim());
                ctaView.setVisibility(View.VISIBLE);
            } else {
                ctaView.setVisibility(View.GONE);
            }
        }
        if (iconView != null) {
            NativeAd.Image icon = nativeAd.getIcon();
            if (icon != null && icon.getDrawable() != null) {
                iconView.setImageDrawable(icon.getDrawable());
            } else {
                iconView.setImageResource(R.drawable.ic_wave);
            }
            iconView.setVisibility(View.VISIBLE);
        }
        adView.setNativeAd(nativeAd);
    }

    private void loadAdaptiveBannerFallback(@NonNull Activity activity,
                                            @NonNull FrameLayout container) {
        if (activity.isFinishing() || activity.isDestroyed() || !isAdsEnabled()) {
            return;
        }
        if (!isNetworkAvailable()) {
            bindOfflineHouseAdFallback(activity, container);
            return;
        }
        try {
            if (activeBannerAdView != null) {
                try {
                    activeBannerAdView.destroy();
                } catch (Throwable ignored) {}
            }
            AdView adView = new AdView(activity);
            adView.setAdUnitId(TEST_ADAPTIVE_BANNER_ID);
            AdSize adSize = computeAnchoredAdaptiveSize(activity, container);
            adView.setAdSize(adSize);
            adView.setAdListener(new AdListener() {
                @Override
                public void onAdLoaded() {
                    libraryAdLoaded = true;
                    showingOfflineHouseAd = false;
                    container.removeAllViews();
                    container.addView(adView, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                    if (!boundLibraryHideForUiMode) {
                        container.setVisibility(View.VISIBLE);
                    }
                }

                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                    if (container.getChildCount() == 0) {
                        bindOfflineHouseAdFallback(activity, container);
                    }
                }
            });
            activeBannerAdView = adView;
            adView.loadAd(new AdRequest.Builder().build());
        } catch (Throwable ignored) {
            if (container.getChildCount() == 0) {
                bindOfflineHouseAdFallback(activity, container);
            }
        }
    }

    private AdSize computeAnchoredAdaptiveSize(@NonNull Activity activity,
                                               @NonNull View container) {
        try {
            DisplayMetrics outMetrics = activity.getResources().getDisplayMetrics();
            float density = outMetrics.density > 0 ? outMetrics.density : 1f;
            int widthPixels = container.getWidth();
            if (widthPixels <= 0) {
                widthPixels = outMetrics.widthPixels - Math.round(32 * density);
            }
            int adWidth = Math.max(320, (int) (widthPixels / density));
            return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                    activity, adWidth);
        } catch (Throwable ignored) {
            return AdSize.BANNER;
        }
    }

    /**
     * Updates the visibility of the Library ad slot so it hides cleanly when
     * the user is searching or multi-selecting takes.
     */
    public void updateLibraryAdVisibility(@Nullable FrameLayout container,
                                          boolean hideForUiMode) {
        boundLibraryHideForUiMode = hideForUiMode;
        if (container == null) return;
        if (!isAdsEnabled() || hideForUiMode || !libraryAdLoaded
                || container.getChildCount() == 0) {
            container.setVisibility(View.GONE);
        } else {
            container.setVisibility(View.VISIBLE);
        }
    }

    public void onLibraryPaused() {
        if (activeBannerAdView != null) {
            try {
                activeBannerAdView.pause();
            } catch (Throwable ignored) {}
        }
    }

    public void onLibraryResumed() {
        if (activeBannerAdView != null) {
            try {
                activeBannerAdView.resume();
            } catch (Throwable ignored) {}
        }
    }

    public void onLibraryDestroyed() {
        libraryAdLoaded = false;
        showingOfflineHouseAd = false;
        boundLibraryContainerRef = null;
        if (activeBannerAdView != null) {
            try {
                activeBannerAdView.destroy();
            } catch (Throwable ignored) {}
            activeBannerAdView = null;
        }
        if (activeNativeAd != null) {
            try {
                activeNativeAd.destroy();
            } catch (Throwable ignored) {}
            activeNativeAd = null;
        }
    }

    // ---- Placement 2, 3 & 4: Post-Save, Post-Export & Share Interstitial Ads ----

    private boolean isInterstitialAvailable() {
        if (cachedInterstitial == null) return false;
        long age = System.currentTimeMillis() - interstitialLoadedAtMs;
        if (age >= INTERSTITIAL_EXPIRY_MS) {
            cachedInterstitial = null;
            return false;
        }
        return true;
    }

    public synchronized void preloadInterstitial() {
        if (!isAdsEnabled() || isInterstitialAvailable() || loadingInterstitial) {
            return;
        }
        if (!isNetworkAvailable()) {
            return;
        }
        loadingInterstitial = true;
        try {
            InterstitialAd.load(appCtx, TEST_INTERSTITIAL_ID,
                    new AdRequest.Builder().build(),
                    new InterstitialAdLoadCallback() {
                        @Override
                        public void onAdLoaded(@NonNull InterstitialAd ad) {
                            cachedInterstitial = ad;
                            interstitialLoadedAtMs = System.currentTimeMillis();
                            loadingInterstitial = false;
                            maybeDeliverPendingInterstitial();
                        }

                        @Override
                        public void onAdFailedToLoad(@NonNull LoadAdError err) {
                            cachedInterstitial = null;
                            loadingInterstitial = false;
                        }
                    });
        } catch (Throwable ignored) {
            loadingInterstitial = false;
        }
    }

    private void maybeDeliverPendingInterstitial() {
        WeakReference<Activity> ref = pendingInterstitialActivityRef;
        pendingInterstitialActivityRef = null;
        if (ref == null) return;
        Activity act = ref.get();
        long now = System.currentTimeMillis();
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        if ((now - pendingInterstitialRequestedAtMs) > LATE_INTERSTITIAL_GRACE_MS) {
            return;
        }
        if (isActivelyCapturingOrPlaying()) return;
        if (act instanceof MainActivity && ((MainActivity) act).isInStudio()) {
            return;
        }
        showCachedInterstitialNow(act, now, null);
    }

    /**
     * Evaluates whether a post-action Interstitial ad is allowed to show right
     * now based on frequency capping ({@link #INTERSTITIAL_ACTION_INTERVAL}),
     * minimum cooldown ({@link #INTERSTITIAL_COOLDOWN_MS}), and audio-studio
     * safety.
     *
     * <p><b>Why Post-Save Previously Failed:</b> During
     * {@code StudioFragment.runSaveDispatch()}, {@link #onTakeSaved(Activity)}
     * runs on the UI thread while {@code ConversionJobs.dispatchSave()} is
     * still starting on a background thread—meaning
     * {@code RecordingEngine.get().hasSession()} is still {@code true} with
     * {@code engine.isSaving() == true}. Checking {@code hasSession()} blocked
     * every save while allowing exports. We now check
     * {@link #isActivelyCapturingOrPlaying()}, which allows the post-save ad
     * as soon as capture has stopped or entered saving state!
     */
    public boolean recordActionAndEvaluateInterstitialEligibility(long nowMs) {
        if (!isAdsEnabled()) return false;
        SharedPreferences prefs = appCtx.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        int count = prefs.getInt(KEY_AD_COMPLETED_ACTIONS, 0) + 1;
        long lastShown = prefs.getLong(KEY_LAST_FULLSCREEN_AD_MS, 0L);
        prefs.edit().putInt(KEY_AD_COMPLETED_ACTIONS, count).apply();

        if (isActivelyCapturingOrPlaying()) {
            return false;
        }
        if (count % INTERSTITIAL_ACTION_INTERVAL != 0) {
            return false;
        }
        if (lastShown > 0L && (nowMs - lastShown) < INTERSTITIAL_COOLDOWN_MS) {
            return false;
        }
        return true;
    }

    /**
     * True only when the user is actively recording audio into the microphone
     * (and NOT in the middle of saving/exiting Studio) or actively playing a
     * take in the Library.
     */
    private boolean isActivelyCapturingOrPlaying() {
        if (showingFullScreenAd || audioPlaybackActive) return true;
        try {
            RecordingEngine engine = RecordingEngine.get();
            if (engine.state == RecordingEngine.State.RECORDING
                    && !engine.isSaving()) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Stricter check used only by Warm-Resume App Open ads so an App Open ad
     * does not cover an unfinalised paused recording session.
     */
    private boolean hasActiveOrPausedRecordingOrPlayback() {
        if (showingFullScreenAd || audioPlaybackActive) return true;
        try {
            if (RecordingEngine.get().hasSession()
                    && !RecordingEngine.get().isSaving()) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Called when a take is saved in {@link StudioFragment} and transitions
     * back to {@link LibraryFragment}. Dispatches after a brief 350ms UI beat
     * so the {@code STU -> LIB} fragment transaction and "Take saved" banner
     * settle cleanly before {@code AdActivity} launches.
     */
    public void onTakeSaved(@Nullable Activity activity) {
        if (activity == null) return;
        final WeakReference<Activity> actRef = new WeakReference<>(activity);
        mainHandler.postDelayed(() -> {
            Activity act = actRef.get();
            if (act != null && !act.isFinishing() && !act.isDestroyed()) {
                maybeShowPostActionInterstitial(act, null);
            }
        }, POST_SAVE_TRANSITION_DELAY_MS);
    }

    /**
     * Called when an audio export to a folder completes in
     * {@link LibraryFragment}.
     */
    public void onExportCompleted(@Nullable Activity activity) {
        maybeShowPostActionInterstitial(activity, null);
    }

    /**
     * Called when the user selects <b>Share</b> on the Export menu in
     * {@link LibraryFragment}. If an Interstitial ad is preloaded and eligible,
     * shows the ad first and launches the system Share sheet via
     * {@code onProceedToShare} as soon as the user closes the ad; otherwise
     * launches the system Share sheet immediately without blocking.
     */
    public void onShareRequested(@Nullable Activity activity,
                                 @NonNull Runnable onProceedToShare) {
        long now = System.currentTimeMillis();
        boolean eligible = recordActionAndEvaluateInterstitialEligibility(now);
        if (!eligible || activity == null
                || activity.isFinishing() || activity.isDestroyed()
                || !isInterstitialAvailable()) {
            preloadInterstitial();
            onProceedToShare.run();
            return;
        }
        showCachedInterstitialNow(activity, now, onProceedToShare);
    }

    private void maybeShowPostActionInterstitial(@Nullable Activity activity,
                                                 @Nullable Runnable onAfterAd) {
        long now = System.currentTimeMillis();
        boolean eligible = recordActionAndEvaluateInterstitialEligibility(now);
        if (!eligible || activity == null
                || activity.isFinishing() || activity.isDestroyed()) {
            preloadInterstitial();
            if (onAfterAd != null) onAfterAd.run();
            return;
        }
        if (!isInterstitialAvailable()) {
            if (onAfterAd == null) {
                pendingInterstitialActivityRef = new WeakReference<>(activity);
                pendingInterstitialRequestedAtMs = now;
            }
            preloadInterstitial();
            if (onAfterAd != null) onAfterAd.run();
            return;
        }
        showCachedInterstitialNow(activity, now, onAfterAd);
    }

    private void showCachedInterstitialNow(@NonNull Activity activity,
                                           long now,
                                           @Nullable Runnable onAfterAd) {
        InterstitialAd ad = cachedInterstitial;
        if (ad == null) {
            if (onAfterAd != null) onAfterAd.run();
            return;
        }
        cachedInterstitial = null;
        pendingInterstitialActivityRef = null;
        showingFullScreenAd = true;
        recordFullScreenAdShown(now, false);
        applyAudioSafeSettings();
        final boolean[] callbackRan = {false};
        final Runnable fireAfter = () -> {
            if (callbackRan[0]) return;
            callbackRan[0] = true;
            if (onAfterAd != null) {
                try {
                    onAfterAd.run();
                } catch (Throwable ignored) {}
            }
        };
        try {
            ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    showingFullScreenAd = false;
                    preloadInterstitial();
                    fireAfter.run();
                }

                @Override
                public void onAdFailedToShowFullScreenContent(
                        @NonNull AdError adError) {
                    showingFullScreenAd = false;
                    preloadInterstitial();
                    fireAfter.run();
                }

                @Override
                public void onAdShowedFullScreenContent() {
                    showingFullScreenAd = true;
                }
            });
            ad.show(activity);
        } catch (Throwable ignored) {
            showingFullScreenAd = false;
            preloadInterstitial();
            fireAfter.run();
        }
    }

    // ---- Placement 5: Warm-Resume App Open Ad (Library Only) ----

    public synchronized void preloadAppOpenAd() {
        if (!isAdsEnabled() || isAppOpenAdAvailable() || loadingAppOpen) {
            return;
        }
        if (!isNetworkAvailable()) {
            return;
        }
        loadingAppOpen = true;
        try {
            AppOpenAd.load(appCtx, TEST_APP_OPEN_ID,
                    new AdRequest.Builder().build(),
                    new AppOpenAd.AppOpenAdLoadCallback() {
                        @Override
                        public void onAdLoaded(@NonNull AppOpenAd ad) {
                            cachedAppOpenAd = ad;
                            appOpenLoadedAtMs = System.currentTimeMillis();
                            loadingAppOpen = false;
                        }

                        @Override
                        public void onAdFailedToLoad(@NonNull LoadAdError err) {
                            cachedAppOpenAd = null;
                            loadingAppOpen = false;
                        }
                    });
        } catch (Throwable ignored) {
            loadingAppOpen = false;
        }
    }

    private boolean isAppOpenAdAvailable() {
        if (cachedAppOpenAd == null) return false;
        long age = System.currentTimeMillis() - appOpenLoadedAtMs;
        if (age >= APP_OPEN_EXPIRY_MS) {
            cachedAppOpenAd = null;
            return false;
        }
        return true;
    }

    /**
     * Evaluates whether a warm-resume App Open ad is eligible to be shown.
     * Skips the very first cold start so the user lands in TakeOne immediately,
     * and blocks whenever the user is in Studio, recording, or playing audio.
     */
    public boolean canShowAppOpenAd(boolean inStudio, long nowMs) {
        if (!isAdsEnabled() || inStudio
                || hasActiveOrPausedRecordingOrPlayback()) {
            return false;
        }
        if (!coldStartSeen) {
            coldStartSeen = true;
            return false;
        }
        SharedPreferences prefs = appCtx.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        long lastFull = prefs.getLong(KEY_LAST_FULLSCREEN_AD_MS, 0L);
        long lastAppOpen = prefs.getLong(KEY_LAST_APP_OPEN_AD_MS, 0L);
        if (lastFull > 0L && (nowMs - lastFull) < INTERSTITIAL_COOLDOWN_MS) {
            return false;
        }
        if (lastAppOpen > 0L && (nowMs - lastAppOpen) < APP_OPEN_COOLDOWN_MS) {
            return false;
        }
        return true;
    }

    /**
     * Called from {@link MainActivity#onResume()} to show an App Open ad on
     * warm foreground returns when the user is on the Library screen and no
     * recording or playback is active.
     */
    public void onMainActivityResumed(@NonNull Activity activity,
                                      boolean inStudio) {
        long now = System.currentTimeMillis();
        preloadInterstitial();
        if (!canShowAppOpenAd(inStudio, now)) {
            preloadAppOpenAd();
            return;
        }
        if (!isAppOpenAdAvailable()
                || activity.isFinishing() || activity.isDestroyed()) {
            preloadAppOpenAd();
            return;
        }
        AppOpenAd ad = cachedAppOpenAd;
        cachedAppOpenAd = null;
        showingFullScreenAd = true;
        recordFullScreenAdShown(now, true);
        applyAudioSafeSettings();
        try {
            ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    showingFullScreenAd = false;
                    preloadAppOpenAd();
                }

                @Override
                public void onAdFailedToShowFullScreenContent(
                        @NonNull AdError adError) {
                    showingFullScreenAd = false;
                    preloadAppOpenAd();
                }
            });
            ad.show(activity);
        } catch (Throwable ignored) {
            showingFullScreenAd = false;
            preloadAppOpenAd();
        }
    }

    void recordFullScreenAdShown(long nowMs, boolean isAppOpen) {
        SharedPreferences prefs = appCtx.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit()
                .putLong(KEY_LAST_FULLSCREEN_AD_MS, nowMs);
        if (isAppOpen) {
            ed.putLong(KEY_LAST_APP_OPEN_AD_MS, nowMs);
        }
        ed.apply();
    }

    /** Reset state helper for deterministic unit tests. */
    void resetForTesting() {
        coldStartSeen = false;
        audioPlaybackActive = false;
        showingFullScreenAd = false;
        showingOfflineHouseAd = false;
        libraryAdLoaded = false;
        pendingInterstitialActivityRef = null;
        pendingInterstitialRequestedAtMs = 0L;
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_AD_COMPLETED_ACTIONS)
                .remove(KEY_LAST_FULLSCREEN_AD_MS)
                .remove(KEY_LAST_APP_OPEN_AD_MS)
                .commit();
    }
}
