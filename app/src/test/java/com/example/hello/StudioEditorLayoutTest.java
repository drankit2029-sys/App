package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Roadmap Phase 5 layouts: portrait and landscape studio must expose the
 * identical editor-strip view IDs (gone until a take is paused/attached),
 * and the landscape transport rail must keep every FAB id Studio expects.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class StudioEditorLayoutTest {

    private static final int[] EDITOR_IDS = {
            R.id.editorStrip, R.id.btnPreviewPlay, R.id.btnSelectRange,
            R.id.btnTrimCrop, R.id.btnCutDelete, R.id.btnDspFx,
            R.id.btnEditUndo,
    };

    private static final int[] STUDIO_IDS = {
            R.id.backBtn, R.id.statusText, R.id.statusDot, R.id.stateLabel,
            R.id.draftBadge, R.id.formatBadge, R.id.telePanel, R.id.teleView,
            R.id.teleChrome, R.id.teleTitle, R.id.teleEditBtn, R.id.teleHideBtn,
            R.id.telePauseBtn, R.id.teleMirrorBtn, R.id.teleSizeValue,
            R.id.teleSpeedValue, R.id.teleWplValue, R.id.recStrip, R.id.dbMeter,
            R.id.waveform, R.id.zoomValue, R.id.timerText, R.id.settingsRow,
            R.id.settingsName, R.id.teleToggle, R.id.teleToggleIcon,
            R.id.teleState, R.id.takeName, R.id.hintText,
    };

    private Activity activity() {
        return Robolectric.buildActivity(Activity.class).setup().get();
    }

    private Context landscapeContext(Activity a) {
        Configuration cfg =
                new Configuration(a.getResources().getConfiguration());
        cfg.orientation = Configuration.ORIENTATION_LANDSCAPE;
        // Smallest-width in dp must stay a phone bucket so no sw600dp
        // overlay can shadow the layout-land split pane in this check.
        cfg.smallestScreenWidthDp = 400;
        return a.createConfigurationContext(cfg);
    }

    private static void assertIds(View root, int[] ids, String tag) {
        for (int id : ids) {
            assertTrue(tag + " missing id " + id,
                    root.findViewById(id) != null);
        }
    }

    @Test
    public void portraitStudioExposesHiddenEditorStrip() {
        Activity a = activity();
        View root = LayoutInflater.from(a)
                .inflate(R.layout.fragment_studio, null, false);
        assertIds(root, STUDIO_IDS, "portrait");
        assertIds(root, EDITOR_IDS, "portrait");
        assertEquals(View.GONE,
                root.findViewById(R.id.editorStrip).getVisibility());
    }

    @Test
    public void landscapeStudioKeepsEveryStudioId() {
        Activity a = activity();
        View root = LayoutInflater.from(landscapeContext(a))
                .inflate(R.layout.fragment_studio, null, false);
        assertIds(root, STUDIO_IDS, "landscape");
        assertIds(root, EDITOR_IDS, "landscape");
        assertEquals(View.GONE,
                root.findViewById(R.id.editorStrip).getVisibility());
    }

    @Test
    public void landscapeActivityDocksTransportRail() {
        Activity a = activity();
        View root = LayoutInflater.from(landscapeContext(a))
                .inflate(R.layout.activity_main, null, false);
        int[] ids = {R.id.root, R.id.container, R.id.fabWrap, R.id.fabHalo,
                R.id.fabCore, R.id.fabIcon, R.id.fabStopWrap, R.id.fabStopIcon};
        assertIds(root, ids, "landscape activity");
    }
}
