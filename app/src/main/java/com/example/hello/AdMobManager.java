package com.example.hello;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
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

/**
 * Centralized, audio-safe Google AdMob controller for TakeOne.
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
 *   <li><b>Non-Intrusive Placements &amp; Frequency Caps:</b>
 *       <ul>
 *         <li><i>Library Feed Card:</i> Styled Native Advanced card
 *             ({@code R.layout.item_native_ad}) placed below the filter chips
 *             and far above the bottom Record FAB, with automatic fallback to
 *             an Anchored Adaptive Banner. Automatically hides during search
 *             or multi-select mode.</li>
 *         <li><i>Post-Save &amp; Post-Export Interstitials:</i> Shown only at
 *             natural task-completion boundaries (returning from Studio to
 *             Library after saving a take, or finishing a folder export),
 *             capped at once every {@link #INTERSTITIAL_ACTION_INTERVAL}
 *             completed actions with a {@link #INTERSTITIAL_COOLDOWN_MS}
 *             minimum cooldown.</li>
 *         <li><i>Warm-Resume App Open Ad:</i> Shown only on warm foreground
 *             resumes in {@link MainActivity} when the user is on the Library
 *             screen with no active recording session and no audio playing.</li>
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
     * Minimum completed user workflows (take saved or batch exported) required
     * before an interstitial ad is eligible to fire. Complies with AdMob's
     * policy of at most 1 interstitial per 2 user actions.
     */
    public static final int INTERSTITIAL_ACTION_INTERVAL = 2;

    /** Minimum 3-minute cooldown between any two full-screen ads. */
    public static final long INTERSTITIAL_COOLDOWN_MS = 3L * 60_000L;

    /** Minimum 10-minute cooldown between App Open ads on warm resume. */
    public static final long APP_OPEN_COOLDOWN_MS = 10L * 60_000L;

    /** Google AdMob App Open cached ads expire after 4 hours. */
    public static final long APP_OPEN_EXPIRY_MS = 4L * 3600_000L;

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
    private volatile boolean audioPlaybackActive = false;
    private volatile boolean showingFullScreenAd = false;
    private volatile boolean coldStartSeen = false;

    private InterstitialAd cachedInterstitial;
    private boolean loadingInterstitial = false;

    private AppOpenAd cachedAppOpenAd;
    private long appOpenLoadedAtMs = 0L;
    private boolean loadingAppOpen = false;

    private NativeAd activeNativeAd;
    private AdView activeBannerAdView;
    private boolean libraryAdLoaded = false;

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
     * Initializes the Google Mobile Ads SDK, enforces global ad audio muting
     * so ads never disrupt microphone capture or audio focus, and preloads
     * background Interstitial and App Open inventory.
     */
    public synchronized void init(@NonNull Context context) {
        this.appCtx = context.getApplicationContext();
        if (!isAdsEnabled()) return;
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

    // ---- Placement 1: Library Native Advanced Card (with Adaptive Banner fallback) ----

    /**
     * Loads a non-intrusive Native Advanced ad styled like a TakeOne library
     * card into {@code container} (located below the filter chips in
     * {@link LibraryFragment}, far away from the bottom Record FAB). If native
     * inventory fails to load, falls back cleanly to an Anchored Adaptive Banner.
     */
    public void bindLibraryAd(@NonNull Activity activity,
                              @Nullable FrameLayout container,
                              boolean hideForUiMode) {
        if (container == null) return;
        if (!isAdsEnabled() || hideForUiMode) {
            container.setVisibility(View.GONE);
            return;
        }
        if (libraryAdLoaded && container.getChildCount() > 0) {
            container.setVisibility(View.VISIBLE);
            return;
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
                        NativeAdView adView = (NativeAdView) LayoutInflater
                                .from(activity)
                                .inflate(R.layout.item_native_ad, container, false);
                        populateNativeAdView(nativeAd, adView);
                        container.removeAllViews();
                        container.addView(adView);
                        container.setVisibility(View.VISIBLE);
                    })
                    .withAdListener(new AdListener() {
                        @Override
                        public void onAdFailedToLoad(@NonNull LoadAdError error) {
                            // Fallback to Anchored Adaptive Banner if Native fails
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
                    container.setVisibility(View.VISIBLE);
                }

                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                    container.setVisibility(View.GONE);
                }
            });
            activeBannerAdView = adView;
            container.removeAllViews();
            container.addView(adView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            adView.loadAd(new AdRequest.Builder().build());
        } catch (Throwable ignored) {
            container.setVisibility(View.GONE);
        }
    }

    private AdSize computeAnchoredAdaptiveSize(@NonNull Activity activity,
                                               @NonNull View container) {
        try {
            DisplayMetrics outMetrics = activity.getResources().getDisplayMetrics();
            float density = outMetrics.density > 0 ? outMetrics.density : 1f;
            int widthPixels = container.getWidth();
            if (widthPixels <= 0) {
                // Account for 16dp horizontal margins on each side
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

    // ---- Placement 2 & 3: Post-Save & Post-Export Interstitial Ads ----

    public synchronized void preloadInterstitial() {
        if (!isAdsEnabled() || cachedInterstitial != null || loadingInterstitial) {
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
                            loadingInterstitial = false;
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

    /**
     * Evaluates whether a post-action Interstitial ad is allowed to show right
     * now based on frequency capping ({@link #INTERSTITIAL_ACTION_INTERVAL}),
     * minimum cooldown ({@link #INTERSTITIAL_COOLDOWN_MS}), and audio-studio
     * safety (never during an active recording session or active playback).
     */
    public boolean recordActionAndEvaluateInterstitialEligibility(long nowMs) {
        if (!isAdsEnabled()) return false;
        SharedPreferences prefs = appCtx.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        int count = prefs.getInt(KEY_AD_COMPLETED_ACTIONS, 0) + 1;
        long lastShown = prefs.getLong(KEY_LAST_FULLSCREEN_AD_MS, 0L);
        prefs.edit().putInt(KEY_AD_COMPLETED_ACTIONS, count).apply();

        if (ShowingOrRecordingOrPlaying()) {
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

    private boolean ShowingOrRecordingOrPlaying() {
        if (showingFullScreenAd || audioPlaybackActive) return true;
        try {
            if (RecordingEngine.get().hasSession()) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Called when a take is saved in {@link StudioFragment} and transitions
     * back to {@link LibraryFragment}.
     */
    public void onTakeSaved(@Nullable Activity activity) {
        maybeShowPostActionInterstitial(activity);
    }

    /**
     * Called when an audio export to a folder completes in
     * {@link LibraryFragment}.
     */
    public void onExportCompleted(@Nullable Activity activity) {
        maybeShowPostActionInterstitial(activity);
    }

    private void maybeShowPostActionInterstitial(@Nullable Activity activity) {
        long now = System.currentTimeMillis();
        boolean eligible = recordActionAndEvaluateInterstitialEligibility(now);
        if (!eligible || activity == null
                || activity.isFinishing() || activity.isDestroyed()) {
            preloadInterstitial();
            return;
        }
        InterstitialAd ad = cachedInterstitial;
        if (ad == null) {
            preloadInterstitial();
            return;
        }
        cachedInterstitial = null;
        showingFullScreenAd = true;
        recordFullScreenAdShown(now, false);
        applyAudioSafeSettings();
        try {
            ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdDismissedFullScreenContent() {
                    showingFullScreenAd = false;
                    preloadInterstitial();
                }

                @Override
                public void onAdFailedToShowFullScreenContent(
                        @NonNull AdError adError) {
                    showingFullScreenAd = false;
                    preloadInterstitial();
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
        }
    }

    // ---- Placement 4: Warm-Resume App Open Ad (Library Only) ----

    public synchronized void preloadAppOpenAd() {
        if (!isAdsEnabled() || isAppOpenAdAvailable() || loadingAppOpen) {
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
        if (!isAdsEnabled() || inStudio || ShowingOrRecordingOrPlaying()) {
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
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_AD_COMPLETED_ACTIONS)
                .remove(KEY_LAST_FULLSCREEN_AD_MS)
                .remove(KEY_LAST_APP_OPEN_AD_MS)
                .commit();
    }
}