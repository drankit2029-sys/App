package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Roadmap Phase 5 + UI Polish layout tests: portrait and landscape studio
 * expose every expected ID, top-bar inline state/streaming cluster, icon-based
 * teleprompter and waveform editor controls, landscape-bounded dialog widths,
 * non-overflowing script editor in landscape, and 50/50 split preset selector.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class StudioEditorLayoutTest {

    private static final int[] EDITOR_IDS = {
            R.id.editorStrip, R.id.btnPreviewPlay, R.id.btnSelectRange,
            R.id.btnReplaceSeg, R.id.btnTrimCrop, R.id.btnCutDelete,
            R.id.btnDspFx, R.id.btnEditUndo,
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

    @Test
    public void topBarPlacesStateAndDraftBadgeBetweenStudioAndPresetSelector() {
        Activity a = activity();
        ViewGroup root = (ViewGroup) LayoutInflater.from(a)
                .inflate(R.layout.fragment_studio, null, false);
        ViewGroup topBar = (ViewGroup) root.getChildAt(0);
        assertNotNull("stateLabel must live inside the top bar",
                topBar.findViewById(R.id.stateLabel));
        assertNotNull("draftBadge must live inside the top bar",
                topBar.findViewById(R.id.draftBadge));
        assertNotNull("settingsRow must live inside the top bar",
                topBar.findViewById(R.id.settingsRow));
    }

    @Test
    public void teleprompterAndWaveformEditorButtonsCarryIcons() {
        Activity a = activity();
        View root = LayoutInflater.from(a)
                .inflate(R.layout.fragment_studio, null, false);
        int[] iconButtons = {
                R.id.teleEditBtn, R.id.telePauseBtn, R.id.teleMirrorBtn,
                R.id.btnPreviewPlay, R.id.btnSelectRange, R.id.btnReplaceSeg,
                R.id.btnTrimCrop, R.id.btnDspFx, R.id.btnEditUndo
        };
        for (int id : iconButtons) {
            TextView tv = root.findViewById(id);
            assertNotNull("missing button " + id, tv);
            Drawable[] d = tv.getCompoundDrawablesRelative();
            Drawable start = d[0] != null ? d[0] : tv.getCompoundDrawables()[0];
            assertNotNull("button " + id + " missing start icon drawable", start);
        }
        // Teleprompter header buttons render visually as pure icons (0sp text)
        // while keeping underlying text for accessibility and StudioFlowTest.
        assertEquals(0f, ((TextView) root.findViewById(R.id.teleEditBtn)).getTextSize(), 0.01f);
        assertEquals(0f, ((TextView) root.findViewById(R.id.telePauseBtn)).getTextSize(), 0.01f);
        assertEquals(0f, ((TextView) root.findViewById(R.id.teleMirrorBtn)).getTextSize(), 0.01f);
    }

    @Test
    @Config(qualifiers = "land-w800dp-h360dp-xhdpi")
    public void landscapeDialogsAreBoundedAndEditorKeepSaveImportVisible() {
        Activity a = activity();
        float density = a.getResources().getDisplayMetrics().density;

        // 1) Teleprompter script editor in landscape must cap input height so
        //    Save and Import buttons stay visible inside a 360dp-tall screen.
        Dialog ed = TeleDialogs.showEditor(a, "line 1\nline 2", 120, s -> {}, () -> {});
        EditText input = ed.findViewById(R.id.teleEditorInput);
        View saveBtn = ed.findViewById(R.id.teleSaveBtn);
        View importBtn = ed.findViewById(R.id.teleImportBtn);
        assertNotNull(input);
        assertNotNull(saveBtn);
        assertNotNull(importBtn);
        assertEquals(View.VISIBLE, saveBtn.getVisibility());
        assertEquals(View.VISIBLE, importBtn.getVisibility());
        assertTrue("editor input height in landscape must fit screen: " + input.getLayoutParams().height,
                input.getLayoutParams().height <= Math.round(140 * density));
        ed.dismiss();

        // 2) Standard save/discard/confirm dialogs must be width-capped in landscape.
        OptionsSheet.showConfirm(a, "Save?", "Msg", "Save", false, () -> {});
        Dialog confirm = ShadowDialog.getLatestDialog();
        assertNotNull(confirm);
        int winW = confirm.getWindow().getAttributes().width;
        assertTrue("confirm dialog too wide in landscape: " + winW,
                winW > 0 && winW <= Math.round(OptionsSheet.MAX_DIALOG_WIDTH_DP * density) + 2);
        confirm.dismiss();

        // 3) Recording settings dialog splits horizontally (left = presets, right = formats) in landscape.
        OptionsSheet.showRecordingSettings(a, "Recording settings", "Preset", "Hint",
                "Save as", new OptionsSheet.SettingsState() {
                    @Override public List<OptionsSheet.Item> presets() {
                        return Collections.singletonList(
                                OptionsSheet.Item.detail("Voice Memo", "96k", true, () -> {}));
                    }
                    @Override public List<OptionsSheet.Chip> formats() {
                        List<OptionsSheet.Chip> c = new ArrayList<>();
                        c.add(OptionsSheet.Chip.of("Auto", "0.7 MB/min", true, () -> {}));
                        return c;
                    }
                    @Override public String formatHint() { return "Auto"; }
                }, "Done");
        Dialog settings = ShadowDialog.getLatestDialog();
        assertNotNull(settings);
        LinearLayout split = settings.findViewById(R.id.dialogOptions);
        assertNotNull(split);
        assertEquals("landscape preset selector should split horizontally",
                LinearLayout.HORIZONTAL, split.getOrientation());
        settings.dismiss();
    }
}
