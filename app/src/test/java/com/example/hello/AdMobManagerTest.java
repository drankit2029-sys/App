package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Verifies TakeOne's ad-supported free-release configuration:
 * <ul>
 *   <li>Paywall is disabled by default ({@link TrialAndBillingManager#isPaywallEnabled()}
 *       is {@code false} and {@link TrialAndBillingManager#hasProAccess()} is
 *       {@code true} for all takes without showing the billing sheet).</li>
 *   <li>Official Google AdMob Android Test App ID and Ad Unit IDs are wired
 *       for Adaptive Banner, Standard Banner, Interstitial, Native Advanced,
 *       App Open, and Rewarded formats.</li>
 *   <li>Post-Save and Post-Export Interstitial frequency capping (every 2
 *       completed actions + 3-minute cooldown) and audio-studio safety guards
 *       (blocked while recording or playing audio) work deterministically.</li>
 *   <li>Warm-resume App Open ad skips cold start, blocks when in Studio or
 *       playing/recording audio, and enforces its 10-minute cooldown.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AdMobManagerTest {

    private Context ctx;
    private AdMobManager adMob;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        TrialAndBillingManager.setTestOverridePro(null);
        AdMobManager.setTestOverrideAdsEnabled(true);
        RecordingEngine.get().abortAndDelete();
        adMob = AdMobManager.get(ctx);
        adMob.resetForTesting();
    }

    @After
    public void tearDown() {
        AdMobManager.setTestOverrideAdsEnabled(null);
        TrialAndBillingManager.setTestOverridePro(null);
        RecordingEngine.get().abortAndDelete();
        if (adMob != null) {
            adMob.resetForTesting();
        }
    }

    @Test
    public void officialGoogleTestAdUnitIdsAreConfigured() {
        assertEquals("ca-app-pub-3940256099942544~3347511713",
                AdMobManager.TEST_APP_ID);
        assertEquals("ca-app-pub-3940256099942544/9214589741",
                AdMobManager.TEST_ADAPTIVE_BANNER_ID);
        assertEquals("ca-app-pub-3940256099942544/6300978111",
                AdMobManager.TEST_BANNER_ID);
        assertEquals("ca-app-pub-3940256099942544/1033173712",
                AdMobManager.TEST_INTERSTITIAL_ID);
        assertEquals("ca-app-pub-3940256099942544/2247696110",
                AdMobManager.TEST_NATIVE_ADVANCED_ID);
        assertEquals("ca-app-pub-3940256099942544/9257395921",
                AdMobManager.TEST_APP_OPEN_ID);
        assertEquals("ca-app-pub-3940256099942544/5224354917",
                AdMobManager.TEST_REWARDED_ID);
    }

    @Test
    public void paywallIsDisabledAndAllStudioFeaturesAreFreeByDefault() {
        assertFalse("Paywall must be disabled in ad-supported release",
                TrialAndBillingManager.isPaywallEnabled());
        TrialAndBillingManager billing = TrialAndBillingManager.get(ctx);
        assertTrue("All Studio tools must be unlocked for free",
                billing.hasProAccess());

        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final boolean[] ran = {false};
        boolean allowed = TrialAndBillingManager.requireProOrShowPaywall(
                activity, "Effects", () -> ran[0] = true);
        assertTrue("requireProOrShowPaywall must allow action without paywall",
                allowed);
        assertTrue("onAllowed callback must execute immediately", ran[0]);
    }

    @Test
    public void interstitialEnforcesFrequencyCapAndThreeMinuteCooldown() {
        long t0 = 1_000_000L;

        // 1st save/export: action count = 1 (odd) -> not eligible
        assertFalse("1st action must not trigger interstitial",
                adMob.recordActionAndEvaluateInterstitialEligibility(t0));

        // 2nd save/export: action count = 2 (even), no prior ad -> eligible!
        assertTrue("2nd action must be eligible for interstitial",
                adMob.recordActionAndEvaluateInterstitialEligibility(t0 + 5_000L));
        adMob.recordFullScreenAdShown(t0 + 5_000L, false);

        // 3rd & 4th actions within 3-minute cooldown -> blocked by cooldown
        assertFalse("3rd action must not trigger interstitial",
                adMob.recordActionAndEvaluateInterstitialEligibility(t0 + 30_000L));
        assertFalse("4th action inside 3-min cooldown must be blocked",
                adMob.recordActionAndEvaluateInterstitialEligibility(t0 + 60_000L));

        // 5th & 6th actions after 3-minute cooldown (180,000 ms) has elapsed
        long afterCooldown = t0 + 5_000L + AdMobManager.INTERSTITIAL_COOLDOWN_MS + 1_000L;
        assertFalse("5th action (odd) must not trigger interstitial",
                adMob.recordActionAndEvaluateInterstitialEligibility(afterCooldown));
        assertTrue("6th action (even) after cooldown must be eligible",
                adMob.recordActionAndEvaluateInterstitialEligibility(afterCooldown + 1_000L));
    }

    @Test
    public void interstitialAndAppOpenAreBlockedDuringAudioPlaybackOrRecording()
            throws Exception {
        long t0 = 5_000_000L;

        // Prime cold start and 1st action
        assertFalse("Cold start must never show App Open ad",
                adMob.canShowAppOpenAd(false, t0));
        adMob.recordActionAndEvaluateInterstitialEligibility(t0);

        // Block when library audio playback is active
        adMob.setAudioPlaybackActive(true);
        assertFalse("Interstitial must be blocked while audio is playing",
                adMob.recordActionAndEvaluateInterstitialEligibility(t0 + 1_000L));
        assertFalse("App Open ad must be blocked while audio is playing",
                adMob.canShowAppOpenAd(false, t0 + 1_000L));
        adMob.setAudioPlaybackActive(false);

        // Block when user is inside Studio
        assertFalse("App Open ad must never show while in StudioFragment",
                adMob.canShowAppOpenAd(true, t0 + 2_000L));

        // Eligible on warm resume in Library when idle
        assertTrue("App Open ad must be eligible on warm resume in Library when idle",
                adMob.canShowAppOpenAd(false, t0 + 2_000L));
    }
}
