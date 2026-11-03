package com.takeone.studio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

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
 *   <li>Post-Save Interstitial fires on the very first save EVEN while
 *       {@link RecordingEngine#hasSession()} is still {@code true} and
 *       {@link RecordingEngine#isSaving()} is {@code true} during background
 *       save dispatch.</li>
 *   <li>Share-from-Export menu ({@link AdMobManager#onShareRequested}) triggers
 *       interstitial eligibility and always runs the share chooser callback.</li>
 *   <li>Offline House Ad fallback renders into {@code libraryAdContainer} when
 *       the device is offline.</li>
 *   <li>MainActivity enforces portrait-only orientation and TeleprompterView
 *       preserves reading line position across size changes.</li>
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
    public void postSaveInterstitialIsEligibleOnFirstSaveWhileEngineIsSaving()
            throws Exception {
        RecordingEngine engine = RecordingEngine.get();
        engine.startNewSession(ctx, "SaveAdTest", 2);
        engine.state = RecordingEngine.State.PAUSED;
        engine.setSaving(true);
        try {
            assertTrue("Engine still holds session while background save starts",
                    engine.hasSession());
            long t0 = 1_000_000L;
            assertTrue("First save must be eligible for post-save interstitial even while engine.hasSession() is true during save handoff",
                    adMob.recordActionAndEvaluateInterstitialEligibility(t0));
            adMob.recordFullScreenAdShown(t0, false);

            // Immediate back-to-back trigger within 15s cooldown is blocked
            assertFalse("Second trigger within 15s cooldown must be blocked",
                    adMob.recordActionAndEvaluateInterstitialEligibility(t0 + 5_000L));

            // Next save/export after 15s cooldown is eligible
            long afterCooldown = t0 + AdMobManager.INTERSTITIAL_COOLDOWN_MS + 1_000L;
            assertTrue("Next save/export after cooldown must be eligible",
                    adMob.recordActionAndEvaluateInterstitialEligibility(afterCooldown));
        } finally {
            engine.setSaving(false);
            engine.abortAndDelete();
        }
    }

    @Test
    public void shareFromExportMenuTriggersAdCheckAndRunsShareChooserCallback() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final boolean[] shareLaunched = {false};
        adMob.onShareRequested(activity, () -> shareLaunched[0] = true);
        assertTrue("Share chooser callback must run after onShareRequested",
                shareLaunched[0]);
    }

    @Test
    public void offlineHouseAdFallbackRendersIntoLibraryContainer() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout container = new FrameLayout(activity);
        adMob.bindOfflineHouseAdFallback(activity, container);

        assertEquals(View.VISIBLE, container.getVisibility());
        assertTrue(adMob.isShowingOfflineHouseAd());
        TextView headline = container.findViewById(R.id.adHeadline);
        assertNotNull(headline);
        assertTrue(headline.getText().toString().contains("TakeOne Pro Studio"));
    }

    @Test
    public void mainActivityEnforcesPortraitAndTeleprompterPreservesLineOnResize() {
        MainActivity main = Robolectric.buildActivity(MainActivity.class).create().get();
        assertEquals("MainActivity must lock orientation to PORTRAIT",
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                main.getRequestedOrientation());

        TeleprompterView tv = new TeleprompterView(main);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("word").append(i).append(' ');
        tv.setScript(sb.toString());
        tv.measure(
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        tv.layout(0, 0, 900, 600);

        float targetLine = 5.0f;
        tv.setScrollOffset(targetLine * tv.lineHeight());
        assertEquals(targetLine, tv.linePosition(), 0.05f);

        // Simulate a viewport size change: line position must not reset to 0
        tv.measure(
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
        tv.layout(0, 0, 600, 900);
        assertTrue("Teleprompter must not reset to 0 on size change",
                tv.scrollOffset() > 0f);
        assertEquals(targetLine, tv.linePosition(), 0.1f);
    }
}
