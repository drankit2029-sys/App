package com.example.hello;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Name-first studio on top of the crash-safe 32-bit float engine.
 *
 * Flow: open -> name modal (no recording yet) -> tap mic to start (FAB morphs
 * into pause/play + split stop) -> stop asks save/discard -> convert to the
 * chosen preset with a live progress bar -> back to the library.
 *
 * Recovery and edit punch-ins arrive with an attached session: the timer
 * shows the loaded duration immediately and (for crash recovery) capture
 * resumes straight away.
 */
public class StudioFragment extends Fragment {

    private final RecordingEngine engine = RecordingEngine.get();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ObjectAnimator dotPulse;

    private TextView timerText;
    private TextView statusText;
    private TextView stateLabel;
    private TextView takeNameView;
    private TextView hintText;
    private TextView draftBadge;
    private TextView formatBadge;
    private View statusDot;
    private WaveformView waveform;
    private View editorStrip;
    private TextView btnPreviewPlay;
    private TextView btnSelectRange;
    private TextView btnTrimCrop;
    private TextView btnCutDelete;
    private TextView btnDspFx;
    private TextView btnEditUndo;
    private RawFloatPlayer studioPreviewPlayer;
    private DspChain.Config dspConfig = new DspChain.Config();
    private DspChain livePreviewDsp;
    private EditEngine.UndoJournal undoJournal;
    private DbMeterView dbMeter;
    private View settingsRow;
    private TextView settingsName;
    private TextView settingsSub;
    private View telePanel;
    private TeleprompterView teleView;
    private View teleToggle;
    private android.widget.ImageView teleToggleIcon;
    private TextView teleState;
    private TextView teleTitle;
    private View teleEditBtn;
    private View teleHideBtn;
    private TextView telePauseBtn;
    private TextView teleMirrorBtn;
    private TeleValue teleSizeValue;
    private TeleValue teleSpeedValue;
    private TeleValue teleWplValue;
    private TeleValue zoomValue;
    private boolean telePaused = true;
    private long initialSessionBytes = 0L;
    private TeleSettings tele;
    /** Manual scroll override: auto-scroll stays off briefly after a drag. */
    private long teleManualUntil;
    private long teleCountdownUntil;
    /** True while the "Name this take" modal is up on initial Studio entry. */
    private boolean namingModalOpen;
    private long lastTeleAdvanceMs;
    private ActivityResultLauncher<String[]> scriptPicker;

    private String pendingName;
    private RecordPreset preset = RecordPreset.MEMO;
    private SaveFormat saveFormat = SaveFormat.AUTO;
    private boolean nameAsked;
    private boolean saving; // save/discard sequence running
    private RecordingEngine.State lastPaintedState;
    private String lastPaintedName = "\0";
    private boolean lastWaveLive;

    /** Library take being punched into (-1 when this is a fresh take). */
    private long editSourceId = -1;
    private String editSourceTitle;


    private AudioManager audioManager;

    private ActivityResultLauncher<String> micPermission;
    private ActivityResultLauncher<String> notifPermission;
    private boolean notifAsked;

    private BroadcastReceiver doneReceiver;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        micPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        ensureNotifThenStart();
                    } else {
                        Toast.makeText(requireContext(), R.string.msg_need_mic,
                                Toast.LENGTH_LONG).show();
                    }
                });
        notifPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    notifAsked = true;
                    beginCapture();
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_studio, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        timerText = v.findViewById(R.id.timerText);
        statusText = v.findViewById(R.id.statusText);
        stateLabel = v.findViewById(R.id.stateLabel);
        takeNameView = v.findViewById(R.id.takeName);
        hintText = v.findViewById(R.id.hintText);
        draftBadge = v.findViewById(R.id.draftBadge);
        formatBadge = v.findViewById(R.id.formatBadge);
        statusDot = v.findViewById(R.id.statusDot);
        waveform = v.findViewById(R.id.waveform);
        dbMeter = v.findViewById(R.id.dbMeter);
        settingsRow = v.findViewById(R.id.settingsRow);
        settingsName = v.findViewById(R.id.settingsName);
        settingsSub = v.findViewById(R.id.settingsSub);
        telePanel = v.findViewById(R.id.telePanel);
        teleView = v.findViewById(R.id.teleView);
        teleToggle = v.findViewById(R.id.teleToggle);
        teleToggleIcon = v.findViewById(R.id.teleToggleIcon);
        teleState = v.findViewById(R.id.teleState);
        teleTitle = v.findViewById(R.id.teleTitle);
        teleEditBtn = v.findViewById(R.id.teleEditBtn);
        teleHideBtn = v.findViewById(R.id.teleHideBtn);
        telePauseBtn = v.findViewById(R.id.telePauseBtn);
        teleMirrorBtn = v.findViewById(R.id.teleMirrorBtn);
        teleSizeValue = v.findViewById(R.id.teleSizeValue);
        teleSpeedValue = v.findViewById(R.id.teleSpeedValue);
        teleWplValue = v.findViewById(R.id.teleWplValue);
        zoomValue = v.findViewById(R.id.zoomValue);

        preset = RecordPreset.load(requireContext());
        saveFormat = SaveFormat.load(requireContext());
        audioManager = (AudioManager) requireContext()
                .getSystemService(Context.AUDIO_SERVICE);

        v.findViewById(R.id.backBtn).setOnClickListener(view -> onBackPressed());
        PressAnim.apply(v.findViewById(R.id.backBtn));

        attachRenameGestures(takeNameView);
        attachRenameGestures(statusText);

        settingsRow.setOnClickListener(view -> showRecordingSettings());
        // Soft variant: plain apply() resets alpha to 1 on release, which
        // would wipe the converting dim. applySoft only touches scale.
        PressAnim.applySoft(settingsRow, 0.97f);
        teleToggle.setOnClickListener(view -> toggleTeleprompter());
        PressAnim.applySoft(teleToggle, 0.97f);
        teleHideBtn.setOnClickListener(view -> setTeleprompterVisible(false));
        PressAnim.applySoft(teleHideBtn, 0.95f);
        teleEditBtn.setOnClickListener(view -> openTeleEditor());
        PressAnim.applySoft(teleEditBtn, 0.95f);
        telePauseBtn.setOnClickListener(view -> toggleTelePaused());
        PressAnim.applySoft(telePauseBtn, 0.95f);
        teleMirrorBtn.setOnClickListener(view -> {
            tele = TeleSettings.load(requireContext());
            TeleSettings.toggleMirror(requireContext(), !tele.mirror);
            applyTeleSettings();
            paintTeleMirror();
        });
        PressAnim.applySoft(teleMirrorBtn, 0.95f);
        // Vertical drag on the waveform zooms, like a timeline in a DAW.
        waveform.setOnZoomGesture((dir, dy) -> stepZoom(dir > 0 ? 1 : -1));
        // Touching the script lets the performer scrub it by hand. The view
        // itself tracks the active drag (TeleprompterView.isDragging), so a
        // tap never pauses scrolling and auto-scroll resumes on finger lift.
        initTeleprompter();
        initTeleValueControls();

        editorStrip = v.findViewById(R.id.editorStrip);
        btnPreviewPlay = v.findViewById(R.id.btnPreviewPlay);
        btnSelectRange = v.findViewById(R.id.btnSelectRange);
        btnTrimCrop = v.findViewById(R.id.btnTrimCrop);
        btnCutDelete = v.findViewById(R.id.btnCutDelete);
        btnDspFx = v.findViewById(R.id.btnDspFx);
        btnEditUndo = v.findViewById(R.id.btnEditUndo);

        dspConfig = DspChain.Config.load(requireContext());
        undoJournal = new EditEngine.UndoJournal(RecordingEngine.takesDir(requireContext()));

        if (btnPreviewPlay != null) {
            PressAnim.applySoft(btnPreviewPlay, 0.95f);
            btnPreviewPlay.setOnClickListener(view -> toggleStudioPreview());
        }
        if (btnSelectRange != null) {
            PressAnim.applySoft(btnSelectRange, 0.95f);
            btnSelectRange.setOnClickListener(view -> {
                waveform.toggleSelectionAtPlayhead();
                paintEditorStrip();
            });
        }
        if (btnTrimCrop != null) {
            PressAnim.applySoft(btnTrimCrop, 0.95f);
            btnTrimCrop.setOnClickListener(view -> runEditOperation(true));
        }
        if (btnCutDelete != null) {
            PressAnim.applySoft(btnCutDelete, 0.95f);
            btnCutDelete.setOnClickListener(view -> runEditOperation(false));
        }
        if (btnDspFx != null) {
            PressAnim.applySoft(btnDspFx, 0.95f);
            btnDspFx.setOnClickListener(view -> showDspFxDialog());
        }
        if (btnEditUndo != null) {
            PressAnim.applySoft(btnEditUndo, 0.95f);
            btnEditUndo.setOnClickListener(view -> performStudioUndo());
        }

        waveform.setTimelineListener(new WaveformView.TimelineListener() {
            @Override
            public void onPlayheadScrubbed(int bucketIndex, float fraction) {
                stopStudioPreview();
                long totalFrames = engine.totalFrames();
                long targetFrame = Math.round(fraction * totalFrames);
                engine.seekCursorFrame(targetFrame);
                timerText.setText(formatTimer(engine.cursorMs()));
            }

            @Override
            public void onSelectionChanged(boolean active, float startFrac, float endFrac) {
                paintEditorStrip();
            }
        });

        // Recovery (crash prompt / notification tap) or an edit punch-in:
        // a session is already attached — never show the name modal.
        Bundle args = getArguments();
        boolean attached = false;
        boolean autostart = false;
        if (args != null && args.containsKey("recover_raw")) {
            try {
                File raw = new File(args.getString("recover_raw"));
                if (raw.exists() && !engine.hasSession()) {
                    // Attach with the file's real geometry: a mono session
                    // must stay mono, or punch-in + re-save silently upmixes.
                    int attachCh = 2;
                    try {
                        FormatConverter.WavInfo ai =
                                FormatConverter.readFloatWav(raw);
                        if (ai.channels == 1 || ai.channels == 2) {
                            attachCh = ai.channels;
                        }
                    } catch (Exception ignored) {}
                    engine.attachExisting(requireContext(), raw,
                            engine.takeName != null ? engine.takeName
                                    : prettyBase(raw),
                            attachCh, RecordingEngine.payloadBytesOf(raw));
                }
                pendingName = engine.takeName;
                autostart = args.getBoolean("autostart", false);
                editSourceId = args.getLong("edit_source_id", -1);
                editSourceTitle = args.getString("edit_source_title");
                if (editSourceId == -1 && engine.rawFile != null
                        && EditLink.matches(requireContext(),
                        engine.rawFile.getAbsolutePath())) {
                    // Killed mid-edit: re-adopt the stashed linkage so save
                    // still offers replace-vs-new for the right take.
                    editSourceId = EditLink.id(requireContext());
                    editSourceTitle = EditLink.title(requireContext());
                }
                nameAsked = true;
                attached = true;
            } catch (Exception e) {
                pendingName = null;
            }
            args.remove("recover_raw");
        }
        if (!attached && engine.hasSession()) {
            pendingName = engine.takeName;
            if (engine.rawFile != null && EditLink.matches(requireContext(),
                    engine.rawFile.getAbsolutePath())) {
                editSourceId = EditLink.id(requireContext());
                editSourceTitle = EditLink.title(requireContext());
            }
            nameAsked = true;
            attached = true;
        }
        restoreWaveformForAttachedSession();

        // Snapshot the attached session size so backing out of an untouched
        // edit can skip the save nag, and park the prompter unless capture
        // is actively running.
        initialSessionBytes = engine.hasSession() ? engine.dataBytes : 0L;
        setTelePaused(engine.state != RecordingEngine.State.RECORDING);

        paint();
        handler.post(tick);
        activity().syncFabWithEngine();

        doneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!RecordingService.BROADCAST_DONE.equals(intent.getAction())) return;
                boolean ok = intent.getBooleanExtra(RecordingService.EXTRA_OK, false);
                String msg = intent.getStringExtra(RecordingService.EXTRA_MESSAGE);
                String kind = intent.getStringExtra(RecordingService.EXTRA_KIND);
                onBackgroundActionDone(ok, "cancel".equals(kind), msg);
            }
        };
        try {
            IntentFilter f = new IntentFilter(RecordingService.BROADCAST_DONE);
            // Compat registration handles the API 33+ exported-flag
            // requirement on every version, killing the lint error and the
            // hand-rolled version branch.
            androidx.core.content.ContextCompat.registerReceiver(
                    requireContext(), doneReceiver, f,
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Exception ignored) {
            doneReceiver = null;
        }

        if (!attached && !nameAsked) {
            nameAsked = true;
            v.post(this::askForName);
        } else if (attached && autostart
                && engine.state != RecordingEngine.State.RECORDING) {
            // Crash recovery: reopen directly in the recording state.
            v.post(() -> {
                if (!isAdded()) return;
                if (ContextCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                    ensureNotifThenStart();
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO);
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.post(tick);
        paint();
        activity().syncFabWithEngine();
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        persistTeleScript();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        stopDotPulse();
        stopStudioPreview();
        persistTeleScript();
        // Never abort here: the take keeps recording in the background via
        // the foreground service when the user leaves the studio.
        try {
            if (doneReceiver != null) requireContext().unregisterReceiver(doneReceiver);
        } catch (Exception ignored) {}
        doneReceiver = null;
        super.onDestroyView();
    }

    // ---- entry: name first, never auto-record ----

    private void askForName() {
        if (!isAdded() || getView() == null) return;
        if (engine.hasSession()) return;
        namingModalOpen = true;
        OptionsSheet.showInputWithCancel(requireContext(),
                getString(R.string.name_take_title),
                nextTakeName(),
                getString(R.string.name_take_save),
                text -> {
                    namingModalOpen = false;
                    pendingName = RecordingEngine.sanitize(text);
                    paint();
                    activity().syncFabWithEngine();
                },
                () -> {
                    namingModalOpen = false;
                    if (!isAdded()) return;
                    if (!engine.hasSession() && pendingName == null) {
                        activity().exitStudio();
                    }
                },
                true);
    }

    private void showRenameModal() {
        if (!isAdded()) return;
        if (engine.state == RecordingEngine.State.RECORDING) {
            Toast.makeText(requireContext(), R.string.studio_recording_hint,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String current = engine.hasSession() && engine.takeName != null
                ? engine.takeName : pendingName;
        if (current == null) current = nextTakeName();
        OptionsSheet.showInput(requireContext(), getString(R.string.rename_title),
                current, getString(R.string.rename_save), text -> {
                    String clean = RecordingEngine.sanitize(text);
                    try {
                        if (engine.hasSession()) {
                            engine.renameSession(requireContext(), clean);
                            pendingName = engine.takeName;
                            if (editSourceId != -1 && engine.rawFile != null) {
                                editSourceTitle = clean;
                                EditLink.save(requireContext(), editSourceId,
                                        clean, engine.rawFile.getAbsolutePath());
                            }
                        } else {
                            pendingName = clean;
                        }
                        paint();
                    } catch (Exception e) {
                        Toast.makeText(requireContext(),
                                R.string.rename_failed_msg, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void attachRenameGestures(View target) {
        final GestureDetector gd = new GestureDetector(requireContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (pendingName != null || engine.hasSession()) showRenameModal();
                        else askForName();
                        return true;
                    }

                    @Override
                    public void onLongPress(MotionEvent e) {
                        try {
                            if (pendingName != null || engine.hasSession()) {
                                showRenameModal();
                            } else {
                                askForName();
                            }
                        } catch (Exception ignored) {}
                    }
                });
        target.setOnTouchListener((view, event) -> gd.onTouchEvent(event));
    }

    // ---- unified recording settings: presets + format toggles ----

    private void showRecordingSettings() {
        if (!isAdded()) return;
        OptionsSheet.SettingsState state = new OptionsSheet.SettingsState() {
            @Override
            public List<OptionsSheet.Item> presets() {
                List<OptionsSheet.Item> items = new ArrayList<>();
                for (RecordPreset p : RecordPreset.all()) {
                    final RecordPreset pick = p;
                    items.add(OptionsSheet.Item.detail(
                            getString(p.titleRes), getString(p.subRes),
                            p == preset,
                            () -> {
                                // NEW-06 fix: lock preset changes whenever
                                // recording OR when an attached session
                                // already holds recorded audio on disk
                                // (whose channel geometry is fixed).
                                if (engine.state
                                        == RecordingEngine.State.RECORDING
                                        || (engine.hasSession()
                                        && engine.dataBytes > 0)) {
                                    try {
                                        PressAnim.nudge(settingsRow, 1.02f);
                                        Toast.makeText(requireContext(),
                                                R.string.preset_locked,
                                                Toast.LENGTH_SHORT).show();
                                    } catch (Exception ignored) {}
                                    return;
                                }
                                preset = pick;
                                preset.save(requireContext());
                                paint();
                            }));
                }
                return items;
            }

            @Override
            public List<OptionsSheet.Chip> formats() {
                List<OptionsSheet.Chip> chips = new ArrayList<>();
                for (SaveFormat f : SaveFormat.values()) {
                    if (!f.available(requireContext())) continue;
                    final SaveFormat pick = f;
                    String label = f == SaveFormat.AUTO
                            ? getString(R.string.save_auto)
                            : chipLabel(f);
                    // Size is resolved against AUTO so the Auto chip shows
                    // what it will actually produce, and re-read on every
                    // preset change so mono/stereo estimates stay honest.
                    SaveFormat shown = f == SaveFormat.AUTO ? f.effective(preset)
                            : f;
                    chips.add(OptionsSheet.Chip.of(label,
                            shown.sizePerMinuteLabel(preset),
                            f == saveFormat,
                            () -> {
                                if (saving) {
                                    try {
                                        Toast.makeText(requireContext(),
                                                R.string.save_locked,
                                                Toast.LENGTH_SHORT).show();
                                    } catch (Exception ignored) {}
                                    return;
                                }
                                saveFormat = pick;
                                saveFormat.save(requireContext());
                                // A4-07: keep the incremental encoder in sync
                                // with the new container, otherwise it keeps
                                // producing the old format and the partial is
                                // discarded at save time.
                                if (engine.hasSession()
                                        && engine.dataBytes > 0
                                        && engine.streaming() != null) {
                                    engine.restartStreaming(requireContext(),
                                            preset, saveFormat);
                                }
                                paint();
                            }));
                }
                return chips;
            }

            @Override
            public String formatHint() {
                return getString(R.string.save_auto_sub) + " • "
                        + TakeSaver.labelFor(preset, SaveFormat.AUTO);
            }
        };
        OptionsSheet.showRecordingSettings(requireContext(),
                getString(R.string.settings_title),
                getString(R.string.preset_title), micCapabilityLine(),
                getString(R.string.settings_format), state,
                getString(R.string.settings_done));
    }

    /** Short chip text: "M4A", "WAV 16", "WAV 24", "AMR", "FLAC", … */
    private String chipLabel(SaveFormat f) {
        switch (f) {
            case WAV16: return "WAV 16";
            case WAV24: return "WAV 24";
            default: return TakeSaver.labelFor(preset, f);
        }
    }

    private String micCapabilityLine() {
        int n = MicSupport.physicalMicCount(audioManager);
        if (n >= 2) return getString(R.string.mic_stereo_ok, n);
        if (n == 1) return getString(R.string.mic_mono_only);
        return getString(R.string.mic_unknown);
    }

    // ---- teleprompter ----

    private void initTeleprompter() {
        tele = TeleSettings.load(requireContext());
        String savedScript = loadTeleScript();
        teleView.setScript(savedScript);
        // A4-10: the header title only ever updated on an explicit edit, so
        // reopening the studio showed a stale/empty title.
        if (teleTitle != null) {
            teleTitle.setText(scriptNameOf(savedScript));
        }
        applyTeleSettings();
        // The control chrome overlays the top of the panel: keep the reading
        // centre in the middle of the visible area, not the middle of the
        // view, as the chrome height settles.
        final View chrome = requireView().findViewById(R.id.teleChrome);
        if (chrome != null && telePanel != null) {
            telePanel.addOnLayoutChangeListener(
                    (v, left, top, right, bottom,
                     oldLeft, oldTop, oldRight, oldBottom) -> {
                        if (teleView != null) {
                            teleView.setCenterShiftPx(chrome.getHeight() / 2f);
                        }
                    });
        }
        // On by default: a performer opening the studio wants the script,
        // not an empty recorder.
        setTeleprompterVisible(true);
        scriptPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri == null || !isAdded()) return;
                    ScriptImporter.Result r = ScriptImporter.read(
                            requireContext(), uri);
                    if (!r.ok()) {
                        Toast.makeText(requireContext(),
                                getString(R.string.tele_import_failed),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    persistTeleScript(r.text);
                    teleView.setScript(r.text);
                    teleTitle.setText(scriptNameOf(r.text));
                    // Reflect it in the editor too, if it is still open.
                    TeleDialogs.applyImported(requireContext(), r.text,
                            scriptNameOf(r.text), tele.wordsPerMinute);
                    Toast.makeText(requireContext(),
                            getString(R.string.tele_imported,
                                    scriptNameOf(r.text),
                                    TeleMath.wordCount(r.text)),
                            Toast.LENGTH_SHORT).show();
                });
    }

    private static String scriptNameOf(String text) {
        String first = "";
        if (text != null) {
            for (String line : text.split("\n")) {
                String t = line.trim();
                if (!t.isEmpty()) {
                    first = t;
                    break;
                }
            }
        }
        if (first.isEmpty()) return "Script";
        return first.length() > 28 ? first.substring(0, 28) + "…" : first;
    }

    /** Pushes settings into the view, applying the words-per-line clamp. */
    private void applyTeleSettings() {
        if (tele == null || teleView == null || getView() == null) return;
        float density = getResources().getDisplayMetrics().density;
        teleView.setTextSizePx(tele.textSizeSp * density);
        teleView.setWordsPerLine(tele.wordsPerLine);
        teleView.setMirror(tele.mirror);
    }

    private void setTeleprompterVisible(boolean visible) {
        if (telePanel == null) return;
        telePanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        setTeleActive(visible);
    }

    private void toggleTeleprompter() {
        setTeleprompterVisible(telePanel.getVisibility() != View.VISIBLE);
    }

    private void setTeleActive(boolean active) {
        if (teleToggle != null) {
            teleToggle.setBackgroundResource(active
                    ? R.drawable.bg_chip_selected : R.drawable.bg_search);
        }
        if (teleToggleIcon != null) {
            teleToggleIcon.setAlpha(active ? 1f : 0.55f);
        }
        if (teleState != null) {
            teleState.setText(active ? R.string.tele_hide_short : R.string.tele_show);
        }
    }

    private void openTeleEditor() {
        if (!isAdded()) return;
        TeleDialogs.showEditor(requireContext(), loadTeleScript(),
                tele != null ? tele.wordsPerMinute : 120,
                script -> {
                    persistTeleScript(script);
                    if (teleView != null) teleView.setScript(script);
                    if (teleTitle != null) teleTitle.setText(scriptNameOf(script));
                },
                () -> {
                    try {
                        scriptPicker.launch(new String[]{
                                "text/plain",
                                "application/vnd.openxmlformats-officedocument"
                                        + ".wordprocessingml.document",
                                "*/*"
                        });
                    } catch (Exception e) {
                        Toast.makeText(requireContext(),
                                getString(R.string.tele_import_failed),
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private String loadTeleScript() {
        try {
            return requireContext().getSharedPreferences("takeone",
                            Context.MODE_PRIVATE)
                    .getString("tele_script", "");
        } catch (Exception e) {
            return "";
        }
    }

    private void persistTeleScript() {
        if (teleView != null) persistTeleScript(teleView.getScript());
    }

    private void persistTeleScript(String text) {
        try {
            requireContext().getSharedPreferences("takeone", Context.MODE_PRIVATE)
                    .edit().putString("tele_script", text == null ? "" : text)
                    .apply();
        } catch (Exception ignored) {}
    }

    /**
     * Drives the prompter: advances whenever not paused (both while recording
     * AND before recording starts so the user can check the speed), yields
     * while the user's finger is actively dragging the script, and resumes
     * immediately on finger lift.
     */
    private void advanceTeleprompter() {
        if (teleView == null || tele == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!tele.autoScroll || telePaused || namingModalOpen) {
            lastTeleAdvanceMs = 0L;
            return;
        }
        if (telePanel == null || telePanel.getVisibility() != View.VISIBLE) {
            lastTeleAdvanceMs = 0L;
            return;
        }
        if (teleView.isDragging()) {
            // Yield only while the finger is actively dragging; resume
            // immediately on lift with zero delay.
            lastTeleAdvanceMs = now;
            return;
        }
        if (teleView.maxScroll() > 0f
                && teleView.scrollOffset() >= teleView.maxScroll() - 0.5f) {
            setTelePaused(true);
            return;
        }
        float dt = lastTeleAdvanceMs > 0
                ? Math.max(0f, (now - lastTeleAdvanceMs) / 1000f)
                : 0.05f;
        dt = Math.min(dt, 0.25f);
        lastTeleAdvanceMs = now;
        float px = TeleMath.scrollPxPerSecond(tele.wordsPerMinute,
                teleView.effectiveWordsPerLine(),
                teleView.renderedTextSizePx());
        if (px <= 0f) return;
        teleView.scrollBy(px * dt);
    }

    private void setTelePaused(boolean paused) {
        telePaused = paused;
        lastTeleAdvanceMs = 0L;
        if (!telePaused) teleManualUntil = 0L;
        if (telePauseBtn != null) {
            telePauseBtn.setText(telePaused
                    ? R.string.tele_resume_short : R.string.tele_pause_short);
            telePauseBtn.setSelected(telePaused);
        }
    }

    private void toggleTelePaused() {
        boolean nextPaused = !telePaused;
        if (!nextPaused && teleView != null && teleView.maxScroll() > 0f
                && teleView.scrollOffset() >= teleView.maxScroll() - 1f) {
            teleView.rewind();
        }
        setTelePaused(nextPaused);
        if (telePauseBtn != null) {
            PressAnim.nudge(telePauseBtn, 1.12f);
        }
    }

    private void paintTeleMirror() {
        if (teleMirrorBtn == null) return;
        boolean on = tele != null && tele.mirror;
        teleMirrorBtn.setSelected(on);
        teleMirrorBtn.setAlpha(on ? 1f : 0.55f);
    }

    /** Inline size / speed / words-per-line controls, plus zoom. */
    private void initTeleValueControls() {
        teleSizeValue.setCaption(getString(R.string.tele_size_short));
        teleSpeedValue.setCaption(getString(R.string.tele_speed_short));
        teleWplValue.setCaption(getString(R.string.tele_wpl_short));
        zoomValue.setCaption(getString(R.string.zoom));
        zoomValue.setStacked(true);
        // A vertical pass over the zoom rail zooms, like the waveform itself.
        zoomValue.setVerticalScrub(true);
        zoomValue.setValueScale(100); // 1 unit = 0.01% (10000 = 100%, 92 = 0.92%)
        zoomValue.setStepRule((cur, dir) -> {
            int ref = dir < 0 ? cur - 1 : cur;
            if (ref < 200) return 10;     // < 2%: 0.10% steps
            if (ref < 1000) return 50;    // 2%..10%: 0.50% steps
            if (ref < 5000) return 200;   // 10%..50%: 2% steps
            if (ref < 10000) return 500;  // 50%..100%: 5% steps
            return 2000;                  // 100%..400%: 20% steps
        });
        teleSizeValue.configure(12, 56, 1, tele.textSizeSp, "");
        teleSpeedValue.configure(TeleSettings.MIN_WPM, TeleSettings.MAX_WPM,
                5, tele.wordsPerMinute, "");
        teleWplValue.configure(TeleMath.MIN_WORDS_PER_LINE,
                TeleMath.MAX_WORDS_PER_LINE, 1, tele.wordsPerLine, "");

        teleSizeValue.setOnScrubListener(new TeleValue.OnScrubListener() {
            @Override
            public void onScrubValueChanged(int val) {
                commitTeleSize(val);
            }

            @Override
            public void onScrubFinished(int val) {
                commitTeleSize(val);
            }
        });
        teleSpeedValue.setOnScrubListener(new TeleValue.OnScrubListener() {
            @Override
            public void onScrubValueChanged(int val) {
                commitTeleSpeed(val);
            }

            @Override
            public void onScrubFinished(int val) {
                commitTeleSpeed(val);
            }
        });
        teleWplValue.setOnScrubListener(new TeleValue.OnScrubListener() {
            @Override
            public void onScrubValueChanged(int val) {
                // Re-wrap live like size/speed: the label alone moving while
                // the script keeps its old segmentation reads as broken.
                commitTeleWordsPerLine(val);
            }

            @Override
            public void onScrubFinished(int val) {
                commitTeleWordsPerLine(val);
            }
        });
        teleSizeValue.setOnValueChanged(this::commitTeleSize);
        teleSpeedValue.setOnValueChanged(this::commitTeleSpeed);
        teleWplValue.setOnValueChanged(this::commitTeleWordsPerLine);

        // Zoom shares the same control; its floor moves with the timeline.
        refreshZoomValue();
        zoomValue.setOnScrubListener(new TeleValue.OnScrubListener() {
            @Override
            public void onScrubValueChanged(int percent) {
                applyZoomValue(percent);
            }

            @Override
            public void onScrubFinished(int percent) {
                applyZoomValue(percent);
            }
        });
        zoomValue.setOnValueChanged(this::applyZoomValue);
        paintTeleMirror();
    }

    private void commitTeleSize(int textSizeSp) {
        TeleSettings.setTextSize(requireContext(), textSizeSp);
        tele = TeleSettings.load(requireContext());
        applyTeleSettings();
    }

    private void commitTeleSpeed(int wordsPerMinute) {
        TeleSettings.setWordsPerMinute(requireContext(), wordsPerMinute);
        tele = TeleSettings.load(requireContext());
        applyTeleSettings();
    }

    private void commitTeleWordsPerLine(int wordsPerLine) {
        TeleSettings.setWordsPerLine(requireContext(), wordsPerLine);
        tele = TeleSettings.load(requireContext());
        applyTeleSettings();
    }

    private void applyZoomValue(int bp) {
        if (waveform == null) return;
        if (bp <= zoomValue.getMin()) {
            waveform.setZoom(waveform.minimumZoom());
        } else {
            waveform.setZoom(bp / 10000f);
        }
        paintZoomLabel();
    }

    private int currentMinZoomBp() {
        if (waveform == null) return 5000;
        // Floor to basis points (0.01%) so minBp never exceeds minimumZoom()
        // and always accommodates 100% of the recording left of the playhead.
        return Math.max(50, (int) Math.floor(waveform.minimumZoom() * 10000f));
    }

    private void refreshZoomValue() {
        if (waveform == null || zoomValue == null) return;
        int minBp = currentMinZoomBp();
        boolean followMin = waveform.isFollowingMinZoom();
        int curBp = followMin ? minBp
                : Math.max(minBp, Math.round(waveform.getZoom() * 10000f));
        zoomValue.configure(minBp, 40000, 10, curBp, "%");
        if (followMin) {
            waveform.setZoom(waveform.minimumZoom());
        }
        paintZoomLabel();
    }

    private void paintZoomLabel() {
        // The zoom control renders its own percentage; nothing to mirror
        // elsewhere. Kept as a hook so the gesture path stays symmetric.
    }

    /** Called when capture starts so the script begins from the top. */
    private void teleprompterArmForTake() {
        if (teleView != null) teleView.rewind();
        teleCountdownUntil = 0L;
        setTelePaused(false);
        // The prompter starts with the recording. A countdown is a head start
        // for the performer, not a delay before the script moves — holding
        // the text still for N seconds desynchronised the take.
    }

    // ---- waveform zoom (time shown per unit length) ----

    private void stepZoom(int dir) {
        if (waveform == null || zoomValue == null) return;
        int minBp = currentMinZoomBp();
        int curBp = Math.max(minBp, Math.round(waveform.getZoom() * 10000f));
        int stepBp;
        int ref = dir < 0 ? curBp - 1 : curBp;
        if (ref < 200) stepBp = 10;
        else if (ref < 1000) stepBp = 50;
        else if (ref < 5000) stepBp = 200;
        else if (ref < 10000) stepBp = 500;
        else stepBp = 2000;
        int next = Math.max(minBp, Math.min(40000, curBp + dir * stepBp));
        zoomValue.setValue(next, true);
    }

    // ---- called by MainActivity's shared buttons ----

    /** Main FAB: name -> start -> pause/resume. Debounced in MainActivity. */
    public void onFabPressed() {
        if (saving) return;
        if (!engine.hasSession() && pendingName == null) {
            askForName();
            return;
        }
        if (engine.state == RecordingEngine.State.RECORDING) {
            pauseTake();
        } else if (engine.state == RecordingEngine.State.PAUSED) {
            resumeTake();
        } else {
            ensurePermissionThenStart();
        }
    }

    /** Split stop button: pause, then ask save vs discard in a styled modal. */
    public void onStopPressed() {
        if (saving) return;
        if (!engine.hasSession()) return;
        if (engine.state == RecordingEngine.State.RECORDING) {
            engine.pauseCapture();
            setTelePaused(true);
            paint();
            activity().syncFabWithEngine();
        }
        String name = engine.takeName != null ? engine.takeName : "";
        List<OptionsSheet.Item> items = new ArrayList<>();
        items.add(OptionsSheet.Item.of(R.drawable.ic_check,
                getString(R.string.action_save), this::doSaveSequence));
        items.add(OptionsSheet.Item.danger(R.drawable.ic_trash,
                getString(R.string.action_discard), this::doDiscardSequence));
        OptionsSheet.showOptions(requireContext(),
                getString(R.string.save_take_title),
                getString(R.string.save_take_msg, name, getString(preset.titleRes)),
                items);
    }

    /** Back: idle -> exit; active -> background-or-stop choice. */
    public boolean onBackPressed() {
        if (saving) return true;
        if (!engine.hasSession()) {
            activity().exitStudio();
            return true;
        }
        // If this is an edit session and the user hasn't recorded any new
        // audio yet, backing out cancels the edit immediately without nagging.
        if (editSourceId != -1
                && engine.state == RecordingEngine.State.IDLE
                && engine.dataBytes <= initialSessionBytes) {
            final Context appCtx = requireContext().getApplicationContext();
            editSourceId = -1;
            editSourceTitle = null;
            new Thread(() -> {
                engine.abortAndDelete();
                EditLink.clear(appCtx);
            }).start();
            activity().exitStudio();
            return true;
        }
        if (engine.state == RecordingEngine.State.RECORDING
                || engine.state == RecordingEngine.State.PAUSED) {
            List<OptionsSheet.Item> items = new ArrayList<>();
            items.add(OptionsSheet.Item.of(R.drawable.ic_mic,
                    getString(R.string.action_continue_bg), () -> {
                        Toast.makeText(requireContext(),
                                getString(R.string.recording_bg_msg,
                                        engine.takeName != null ? engine.takeName : ""),
                                Toast.LENGTH_LONG).show();
                        activity().exitStudio();
                    }));
            items.add(OptionsSheet.Item.of(R.drawable.ic_circle_stop,
                    getString(R.string.action_stop_take), this::onStopPressed));
            OptionsSheet.showOptions(requireContext(),
                    getString(R.string.end_take_title),
                    getString(R.string.recording_bg_title),
                    items);
            return true;
        }
        // Finalized raw waiting on a save choice: run the stop sequence.
        onStopPressed();
        return true;
    }

    // ---- capture ----

    private void ensurePermissionThenStart() {
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        ensureNotifThenStart();
    }

    private void ensureNotifThenStart() {
        if (Build.VERSION.SDK_INT >= 33 && !notifAsked) {
            try {
                if (ContextCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
                    return;
                }
            } catch (Exception ignored) {}
        }
        beginCapture();
    }

    private void beginCapture() {
        if (!isAdded() || saving) return;
        if (engine.hasSession()) {
            startOrResumeCapture();
            return;
        }
        // New session: dual-mic check first so a stereo preset on single-mic
        // hardware warns, then records mono instead of failing.
        if (preset.wantsStereo() && MicSupport.dualAbsent(audioManager)) {
            OptionsSheet.showConfirm(requireContext(),
                    getString(R.string.mono_warn_title),
                    getString(R.string.mono_warn_msg, getString(preset.titleRes)),
                    getString(R.string.ok_got_it), R.drawable.ic_mic, false,
                    () -> createSessionAndStart(1));
            return;
        }
        createSessionAndStart(preset.channels);
    }

    private void createSessionAndStart(int outChannels) {
        if (!isAdded() || saving) return;
        try {
            String name = pendingName != null ? pendingName : nextTakeName();
            engine.startNewSession(requireContext(), name, outChannels);
            pendingName = engine.takeName;
            // A fresh recording is never an edit: drop any stale linkage so
            // a later save cannot inherit a previous take's replace choice.
            editSourceId = -1;
            editSourceTitle = null;
            EditLink.clear(requireContext());
            waveform.clear();
            dbMeter.reset();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Could not start recording",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        startOrResumeCapture();
    }

    private void startOrResumeCapture() {
        if (!isAdded() || saving) return;
        stopStudioPreview();
        boolean wasPaused = (engine.state == RecordingEngine.State.PAUSED);
        boolean isEditOrRecovery = (editSourceId != -1 || engine.dataBytes > 0);
        try {
            // If a selection range is active (or the playhead was scrubbed back
            // into existing audio), arm a click-free punch-in and record an
            // undo snapshot before overwriting!
            if (engine.hasSession() && engine.dataBytes > 0 && waveform != null) {
                long totalFrames = engine.totalFrames();
                if (waveform.hasSelection()) {
                    long startF = Math.round(
                            waveform.selectionStartFraction() * totalFrames);
                    long endF = Math.round(
                            waveform.selectionEndFraction() * totalFrames);
                    if (endF > startF) {
                        if (undoJournal != null) {
                            undoJournal.recordSnapshot(engine.rawFile);
                        }
                        engine.armPunchRange(startF, endF);
                        waveform.clearSelection();
                    }
                } else if (engine.cursorFrames() < totalFrames - 1200) {
                    if (undoJournal != null) {
                        undoJournal.recordSnapshot(engine.rawFile);
                    }
                }
            }
            if (wasPaused) {
                engine.resumeCapture(requireContext(), preset.audioSource);
            } else if (engine.state != RecordingEngine.State.RECORDING) {
                engine.startCapture(requireContext(), preset.audioSource);
            } else {
                return;
            }
            if (engine.fellBackToMono) {
                Toast.makeText(requireContext(), R.string.mono_fallback_toast,
                        Toast.LENGTH_LONG).show();
            }
            try {
                engine.beginStreaming(requireContext(), preset, saveFormat);
            } catch (Exception ignored) {}
            if (engine.state == RecordingEngine.State.RECORDING) {
                if (!wasPaused && !isEditOrRecovery) {
                    teleprompterArmForTake();
                } else {
                    setTelePaused(false);
                }
            }
            RecordingService.start(requireContext(), preset.id);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Could not start recording",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        handler.post(tick);
        paint();
        activity().syncFabWithEngine();
    }

    private void pauseTake() {
        pauseTake(false);
    }

    private void pauseTake(boolean fromInterruption) {
        if (engine.state != RecordingEngine.State.RECORDING) return;
        engine.pauseCapture();
        setTelePaused(true);
        // Sync cursor to end of take on pause and reload high-density peaks
        // so even a sub-second take has plenty of buckets for [ ] Select.
        engine.seekCursorFrame(engine.totalFrames());
        reloadWaveformCleanly();
        if (fromInterruption) {
            try {
                Context c = getContext();
                if (c != null) {
                    Toast.makeText(c, R.string.msg_paused_call,
                            Toast.LENGTH_LONG).show();
                }
            } catch (Exception ignored) {}
        }
        try {
            if (isAdded() && getView() != null) {
                paint();
                activity().syncFabWithEngine();
            }
        } catch (Exception ignored) {}
    }

    /** Clears existing peaks and reloads high-resolution buckets from disk. */
    private void reloadWaveformCleanly() {
        try {
            if (waveform == null || !engine.hasSession()) return;
            File raw = engine.rawFile;
            if (raw == null || !raw.exists()) return;
            if (RecordingEngine.payloadBytesOf(raw) <= 0) return;
            waveform.clear();
            waveform.loadFromFloatWav(raw, () -> {
                if (isAdded() && getView() != null) {
                    long total = Math.max(1L, engine.totalFrames());
                    waveform.setPlayheadFraction(
                            Math.min(1f, engine.cursorFrames() / (float) total));
                    refreshZoomValue();
                    paintEditorStrip();
                }
            });
        } catch (Exception ignored) {}
    }

    private void resumeTake() {
        if (engine.state != RecordingEngine.State.PAUSED || saving) return;
        startOrResumeCapture();
    }

    // ---- stop: save (convert) or discard ----

    private void doSaveSequence() {
        if (saving || !engine.hasSession()) return;
        final long editId = editSourceId;
        final String editTitle = editSourceTitle;
        if (editId != -1) {
            // Edit punch-in: ask replace-vs-new BEFORE dispatching, so the
            // choice travels with a background job instead of being wiped
            // with the lineage when the fast path misses.
            askEditChoiceUpFront(editId, editTitle);
            return;
        }
        runSaveDispatch(-1, null, false);
    }

    /** Edit punch-in choice, made up front so background saves honour it. */
    private void askEditChoiceUpFront(long editId, String editTitle) {
        if (!isAdded()) return;
        String sessionTitle = engine.takeName != null ? engine.takeName : "";
        String target = editTitle != null && !editTitle.isEmpty()
                ? editTitle : sessionTitle;
        List<OptionsSheet.Item> items = new ArrayList<>();
        items.add(OptionsSheet.Item.of(R.drawable.ic_check,
                getString(R.string.edit_replace, target),
                () -> runSaveDispatch(editId, target, true)));
        items.add(OptionsSheet.Item.of(R.drawable.ic_upload,
                getString(R.string.edit_save_new), () -> {
                    // Save-as-new drops the lineage now; the save below is
                    // an ordinary one.
                    editSourceId = -1;
                    editSourceTitle = null;
                    try {
                        EditLink.clear(requireContext());
                    } catch (Exception ignored) {}
                    runSaveDispatch(-1, null, false);
                }));
        OptionsSheet.showOptions(requireContext(),
                getString(R.string.edit_save_title), target, items);
    }

    /**
     * Runs the save dispatch on a worker thread. When {@code replacing}, the
     * edit target travels into the background job; an adopted fast path
     * applies it directly with no second prompt.
     */
    private void runSaveDispatch(long editId, String replaceTitle,
                                 boolean replacing) {
        if (saving || !engine.hasSession()) return;
        saving = true;
        final RecordPreset p = preset;
        final SaveFormat s = saveFormat;
        // A4-02: resolve the application context while still attached; the
        // worker thread must not call requireContext() (and get detached
        // mid-save) to finish the save. Snapshot the title and raw too: the
        // failure path below must not read them off a cleared engine.
        final Context appCtx = requireContext().getApplicationContext();
        final String takeTitle =
                engine.takeName != null ? engine.takeName : "";
        final File saveRaw = engine.rawFile;
        final int saveChannels = engine.channels;
        new Thread(() -> {
            ConversionJobs.SaveResult r = null;
            String fail = null;
            try {
                r = ConversionJobs.dispatchSave(
                        appCtx, p, s, null,
                        replacing ? editId : -1,
                        replacing ? replaceTitle : null);
            } catch (Exception e) {
                fail = e.getMessage();
            }
            final ConversionJobs.SaveResult res = r;
            final String failure = fail;
            handler.post(() -> {
                try {
                    saving = false;
                    if (res == null) {
                        try {
                            if (saveRaw != null && saveRaw.exists()) {
                                RecordingEngine.writePendingMarker(saveRaw,
                                        takeTitle, saveChannels);
                            }
                        } catch (Exception ignored) {}
                    } else {
                        try {
                            RecordingService.stopService(appCtx);
                            engine.clearSession();
                            editSourceId = -1;
                            editSourceTitle = null;
                            EditLink.clear(appCtx);
                            if (!res.adopted) {
                                ConversionService.startJob(appCtx, res.job.jobId);
                            }
                        } catch (Exception ignored) {}
                    }
                    if (res != null && res.adopted) {
                        onSaveAdopted(res, replacing ? editId : -1,
                                replacing ? replaceTitle : null);
                        return;
                    }
                    if (!isAdded()) return;
                    activity().syncFabWithEngine();
                    if (res != null) {
                        Toast.makeText(requireContext(),
                                R.string.conv_bg_toast, Toast.LENGTH_LONG)
                                .show();
                        activity().exitStudio();
                    } else {
                        paint();
                        OptionsSheet.showResult(requireContext(), false,
                                getString(R.string.convert_failed_title),
                                getString(R.string.convert_failed_msg,
                                        takeTitle));
                    }
                    if (failure != null && res == null) {
                        CrashLog.breadcrumb(getContext(),
                                "save dispatch failed: " + failure);
                    }
                } catch (Exception ex) {
                    try {
                        CrashLog.breadcrumb(getContext(),
                                "completion-block FAILED: " + ex);
                    } catch (Exception ignored) {}
                }
            });
        }).start();
    }

    /** Incremental output adopted: the take is saved, synchronously. */
    private void onSaveAdopted(ConversionJobs.SaveResult res, long editId,
                               String editTitle) {
        // The file is already converted: record it even if a rotation
        // detached the fragment mid-save. UI feedback below still needs an
        // attached fragment.
        String target = null;
        boolean replaceFailed = false;
        if (editId == -1) {
            TakesRepository.addRecording(res.file, res.durationMs, res.title,
                    res.label);
        } else {
            // Replace was chosen up front: apply it directly, no second
            // prompt. The background path applies the same mutation in the
            // worker via the job's replace lineage.
            target = editTitle != null && !editTitle.isEmpty()
                    ? editTitle : res.title;
            try {
                TakesRepository.replaceRecording(editId, res.file,
                        res.durationMs, target, res.label);
            } catch (Exception e) {
                replaceFailed = true;
            }
        }
        engine.clearSession();
        if (!isAdded()) return;
        if (editId == -1) {
            OptionsSheet.showResult(requireContext(), true,
                    getString(R.string.convert_done_title),
                    getString(R.string.convert_done_msg, res.title, res.label),
                    () -> {
                        if (isAdded()) activity().exitStudio();
                    });
        } else {
            if (replaceFailed) {
                OptionsSheet.showResult(requireContext(), false,
                        getString(R.string.convert_failed_title),
                        getString(R.string.couldnt_save));
                return;
            }
            editSourceId = -1;
            try {
                EditLink.clear(requireContext());
            } catch (Exception ignored) {}
            OptionsSheet.showResult(requireContext(), true,
                    getString(R.string.convert_done_title),
                    getString(R.string.edit_replaced_msg, target),
                    () -> {
                        if (isAdded()) activity().exitStudio();
                    });
        }
    }

    private void doDiscardSequence() {
        if (saving) return;
        saving = true;
        final Context appCtx = requireContext().getApplicationContext();
        final boolean wasEdit = (editSourceId != -1);
        new Thread(() -> {
            String title = engine.takeName != null ? engine.takeName : "";
            boolean ok = engine.abortAndDelete();
            editSourceId = -1;
            editSourceTitle = null;
            EditLink.clear(appCtx);
            handler.post(() -> {
                saving = false;
                if (!isAdded()) return;
                RecordingService.stopService(requireContext());
                activity().syncFabWithEngine();
                if (wasEdit) {
                    // Discarding an edit leaves the original take untouched;
                    // do not claim the original take was deleted.
                    activity().exitStudio();
                    return;
                }
                OptionsSheet.showResult(requireContext(), ok,
                        ok ? getString(R.string.delete_success_title)
                                : getString(R.string.delete_failed_title),
                        ok ? getString(R.string.delete_success_one, title)
                                : getString(R.string.delete_failed_msg),
                        () -> {
                            if (isAdded()) activity().exitStudio();
                        });
            });
        }).start();
    }

    /** Notification save/cancel finished while the studio is open. */
    private void onBackgroundActionDone(boolean ok, boolean wasCancel, String msg) {
        if (!isAdded() || getView() == null) return;
        if (!engine.hasSession()) {
            editSourceId = -1;
            try {
                EditLink.clear(requireContext());
            } catch (Exception ignored) {}
            paint();
            try {
                activity().syncFabWithEngine();
            } catch (Exception ignored) {}
        }
        String doneTitle;
        if (wasCancel) {
            doneTitle = ok ? getString(R.string.delete_success_title)
                    : getString(R.string.delete_failed_title);
        } else {
            doneTitle = ok ? getString(R.string.convert_done_title)
                    : getString(R.string.convert_failed_title);
        }
        final String resultTitle = doneTitle;
        try {
            OptionsSheet.showResult(requireContext(), ok, resultTitle,
                    msg != null ? msg : "",
                    () -> {
                        if (isAdded() && !engine.hasSession()) {
                            activity().exitStudio();
                        } else {
                            paint();
                        }
                    });
        } catch (Exception ignored) {
            if (!engine.hasSession()) {
                try {
                    activity().exitStudio();
                } catch (Exception ignored2) {}
            }
        }
    }

    // ---- ui ----

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (getView() == null || !isAdded()) return;
            try {
                boolean live = engine.state == RecordingEngine.State.RECORDING;
                if (live != lastWaveLive) {
                    lastWaveLive = live;
                    waveform.setLive(live);
                    if (!live && engine.hasSession()) {
                        engine.seekCursorFrame(engine.totalFrames());
                        reloadWaveformCleanly();
                    }
                }
                float lvl = live ? engine.level : 0f;
                if (live) {
                    long total = Math.max(1L, engine.totalFrames());
                    long cur = engine.cursorFrames();
                    boolean appending = (cur >= total - 2048);
                    waveform.pushOrOverwrite(lvl,
                            Math.min(1f, cur / (float) total), appending);
                    timerText.setText(formatTimer(engine.cursorMs()));
                } else if (studioPreviewPlayer != null) {
                    long posMs = studioPreviewPlayer.positionMs();
                    timerText.setText(formatTimer(posMs));
                    long total = Math.max(1L, engine.totalFrames());
                    waveform.setPlayheadFraction(
                            Math.min(1f, studioPreviewPlayer.positionFrames()
                                    / (float) total));
                } else if (engine.hasSession()) {
                    long shownMs = engine.cursorFrames() > 0
                            ? engine.cursorMs() : engine.durationMs();
                    timerText.setText(formatTimer(shownMs));
                } else {
                    timerText.setText(formatTimer(0));
                }
                dbMeter.setLevel(lvl);
                if (live && zoomValue != null) {
                    int floor = currentMinZoomBp();
                    if (floor != zoomValue.getMin()) refreshZoomValue();
                }
                advanceTeleprompter();
            } catch (Exception ignored) {}
            if (engine.state != lastPaintedState
                    || !String.valueOf(displayName()).equals(lastPaintedName)) {
                paint();
            }
            handler.postDelayed(this, 50);
        }
    };

    private String displayName() {
        if (engine.hasSession() && engine.takeName != null) return engine.takeName;
        return pendingName;
    }

    /** Fills the waveform from an attached edit/recovery file (BUG-04 & NEW-05). */
    private void restoreWaveformForAttachedSession() {
        try {
            if (waveform == null || !engine.hasSession()) return;
            File raw = engine.rawFile;
            if (raw == null || !raw.exists()) return;
            if (RecordingEngine.payloadBytesOf(raw) <= 0) return;
            if (waveform.historyCount() > 0) return;
            waveform.loadFromFloatWav(raw, () -> {
                if (isAdded() && getView() != null) {
                    refreshZoomValue();
                }
            });
        } catch (Exception ignored) {}
    }

    private void toggleStudioPreview() {
        if (!isAdded()) return;
        if (studioPreviewPlayer != null) {
            stopStudioPreview();
            paintEditorStrip();
            return;
        }
        if (engine.state == RecordingEngine.State.RECORDING) return;
        File raw = engine.rawFile;
        if (raw == null || !raw.exists() || waveform == null) return;
        try {
            FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
            if (info.channels < 1 || info.channels > 2) return;
            long totalFrames = info.dataBytes / 4 / info.channels;
            if (totalFrames <= 0) return;
            livePreviewDsp = new DspChain(info.channels, dspConfig);
            final RawFloatPlayer[] slot = new RawFloatPlayer[1];
            slot[0] = new RawFloatPlayer(raw, completed -> handler.post(() -> {
                if (studioPreviewPlayer == slot[0]) {
                    stopStudioPreview();
                    paintEditorStrip();
                }
            }));
            RawFloatPlayer player = slot[0];
            player.setDspChain(livePreviewDsp);
            studioPreviewPlayer = player;
            if (waveform.hasSelection()) {
                long start = Math.round(
                        waveform.selectionStartFraction() * totalFrames);
                long end = Math.round(
                        waveform.selectionEndFraction() * totalFrames);
                player.playRange(start, end);
            } else {
                player.play();
            }
            if (btnPreviewPlay != null) btnPreviewPlay.setText("■ Stop");
        } catch (Exception e) {
            stopStudioPreview();
        }
        paintEditorStrip();
    }

    private void stopStudioPreview() {
        RawFloatPlayer player = studioPreviewPlayer;
        studioPreviewPlayer = null;
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {}
        }
        if (btnPreviewPlay != null) btnPreviewPlay.setText("▶ Preview");
    }

    private void paintEditorStrip() {
        if (editorStrip == null || !isAdded()) return;
        boolean show = engine.hasSession()
                && engine.dataBytes > 0
                && engine.state != RecordingEngine.State.RECORDING;
        editorStrip.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            stopStudioPreview();
            return;
        }
        boolean hasSel = waveform != null && waveform.hasSelection();
        if (btnSelectRange != null) {
            btnSelectRange.setText(hasSel ? "[✓] Selected" : "[ ] Select");
            btnSelectRange.setBackgroundResource(hasSel
                    ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            btnSelectRange.setTextColor(hasSel ? 0xFFFFFFFF : 0xFF9AA3AF);
            btnSelectRange.setAlpha(1f);
        }
        if (btnTrimCrop != null) {
            btnTrimCrop.setAlpha(hasSel ? 1f : 0.65f);
        }
        if (btnCutDelete != null) {
            btnCutDelete.setAlpha(hasSel ? 1f : 0.65f);
        }
        if (btnDspFx != null) {
            boolean fxOn = dspConfig != null && dspConfig.isActive();
            btnDspFx.setText(dspConfig != null ? dspConfig.badgeSummary() : "FX Off");
            btnDspFx.setBackgroundResource(fxOn
                    ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            btnDspFx.setTextColor(fxOn ? 0xFFFFFFFF : 0xFF9AA3AF);
        }
        if (btnEditUndo != null) {
            boolean canUndo = undoJournal != null && undoJournal.canUndo();
            btnEditUndo.setAlpha(canUndo ? 1f : 0.40f);
        }
        if (btnPreviewPlay != null && studioPreviewPlayer == null) {
            btnPreviewPlay.setText(hasSel ? "▶ Sel" : "▶ Preview");
        }
        if (hintText != null) {
            if (hasSel) {
                long durMs = engine.durationMs();
                long sMs = Math.round(waveform.selectionStartFraction() * durMs);
                long eMs = Math.round(waveform.selectionEndFraction() * durMs);
                hintText.setText(formatTimer(sMs) + " – " + formatTimer(eMs)
                        + " • Drag [ ] handles");
            } else if (engine.cursorFrames() < engine.totalFrames() - 1200) {
                hintText.setText("Playhead " + formatTimer(engine.cursorMs())
                        + " • Tap mic to punch in");
            }
        }
    }

    private void runEditOperation(boolean isTrim) {
        if (!isAdded()) return;
        stopStudioPreview();
        if (engine.state == RecordingEngine.State.RECORDING) return;
        File raw = engine.rawFile;
        if (raw == null || !raw.exists() || waveform == null) return;
        if (!waveform.hasSelection()) {
            // Auto-activate selection on the waveform so the user immediately
            // sees the red [ ] handles instead of a dead error toast!
            waveform.toggleSelectionAtPlayhead();
            paintEditorStrip();
            Toast.makeText(requireContext(),
                    "Drag the red [ ] handles on the waveform, then tap "
                            + (isTrim ? "Trim" : "Cut") + " again",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
            long totalFrames = info.dataBytes / 4 / Math.max(1, info.channels);
            long start = Math.round(
                    waveform.selectionStartFraction() * totalFrames);
            long end = Math.round(
                    waveform.selectionEndFraction() * totalFrames);
            if (isTrim) {
                EditEngine.cropToSelection(raw, start, end, undoJournal);
            } else {
                EditEngine.deleteSelection(raw, start, end, undoJournal);
            }
            // Invalidate any incremental StreamingEncoder so save encodes the
            // newly edited audio instead of adopting a stale pre-edit .part!
            engine.abortStreaming();
            engine.dataBytes = RecordingEngine.payloadBytesOf(raw);
            long newTotal = engine.totalFrames();
            long nextCursor = isTrim ? newTotal : Math.min(start, newTotal);
            engine.seekCursorFrame(nextCursor);
            waveform.clearSelection();
            reloadWaveformCleanly();
            timerText.setText(formatTimer(engine.cursorMs()));
        } catch (IllegalArgumentException iae) {
            Toast.makeText(requireContext(), iae.getMessage(),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Edit failed",
                    Toast.LENGTH_SHORT).show();
        }
        paint();
    }

    private void performStudioUndo() {
        if (!isAdded()) return;
        stopStudioPreview();
        File raw = engine.rawFile;
        if (raw == null || undoJournal == null || !undoJournal.canUndo()) return;
        try {
            if (undoJournal.undo(raw)) {
                engine.abortStreaming();
                engine.dataBytes = RecordingEngine.payloadBytesOf(raw);
                engine.seekCursorFrame(engine.totalFrames());
                if (waveform != null) {
                    waveform.clearSelection();
                    reloadWaveformCleanly();
                }
                if (timerText != null) {
                    timerText.setText(formatTimer(engine.durationMs()));
                }
            }
        } catch (Exception ignored) {}
        paint();
    }

    private void showDspFxDialog() {
        if (!isAdded()) return;
        Context ctx = requireContext();
        android.app.Dialog d = new android.app.Dialog(ctx, R.style.TakeOneCenterDialog);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_modal);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText("Mastering & Voice FX");
        title.setTextSize(18);
        title.setTextColor(0xFFF2F4F7);
        title.setGravity(android.view.Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        root.addView(title);

        // 1-Tap Preset Row: Off | Voice Polish | Custom
        android.widget.LinearLayout presetRow = new android.widget.LinearLayout(ctx);
        presetRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        int mTop = Math.round(12 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout.LayoutParams prLp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        prLp.topMargin = mTop;
        root.addView(presetRow, prLp);

        TeleValue noiseCtl = new TeleValue(ctx);
        noiseCtl.setCaption("Noise Suppression");
        noiseCtl.configure(0, 100, 5, dspConfig.noisePct, "%");

        TeleValue deEssCtl = new TeleValue(ctx);
        deEssCtl.setCaption("De-Esser (4.5–9.5 kHz)");
        deEssCtl.configure(0, 100, 5, dspConfig.deEssPct, "%");

        TeleValue compCtl = new TeleValue(ctx);
        compCtl.setCaption("Compressor & Limiter");
        compCtl.configure(0, 100, 5, dspConfig.compPct, "%");

        TextView hpfBtn = new TextView(ctx);
        hpfBtn.setText(dspConfig.hpfEnabled
                ? "80 Hz Rumble Cut: ON" : "80 Hz Rumble Cut: OFF");
        hpfBtn.setGravity(android.view.Gravity.CENTER);
        hpfBtn.setTextSize(12);
        hpfBtn.setTextColor(0xFFF2F4F7);
        hpfBtn.setBackgroundResource(dspConfig.hpfEnabled
                ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
        int hPad = Math.round(10 * getResources().getDisplayMetrics().density);
        hpfBtn.setPadding(hPad, hPad, hPad, hPad);

        Runnable syncLiveDsp = () -> {
            dspConfig.noisePct = noiseCtl.getValue();
            dspConfig.deEssPct = deEssCtl.getValue();
            dspConfig.compPct = compCtl.getValue();
            dspConfig.save(ctx);
            if (livePreviewDsp != null) livePreviewDsp.updateConfig(dspConfig);
            paintEditorStrip();
        };

        noiseCtl.setOnValueChanged(v -> syncLiveDsp.run());
        deEssCtl.setOnValueChanged(v -> syncLiveDsp.run());
        compCtl.setOnValueChanged(v -> syncLiveDsp.run());
        hpfBtn.setOnClickListener(v -> {
            dspConfig.hpfEnabled = !dspConfig.hpfEnabled;
            hpfBtn.setText(dspConfig.hpfEnabled
                    ? "80 Hz Rumble Cut: ON" : "80 Hz Rumble Cut: OFF");
            hpfBtn.setBackgroundResource(dspConfig.hpfEnabled
                    ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            syncLiveDsp.run();
        });

        TextView btnPolish = new TextView(ctx);
        btnPolish.setText("Voice Polish");
        btnPolish.setGravity(android.view.Gravity.CENTER);
        btnPolish.setTextColor(0xFFFFFFFF);
        btnPolish.setBackgroundResource(R.drawable.bg_chip_selected);
        btnPolish.setPadding(hPad, hPad / 2, hPad, hPad / 2);
        btnPolish.setOnClickListener(v -> {
            dspConfig = DspChain.Config.voicePolish();
            noiseCtl.setValue(dspConfig.noisePct, false);
            deEssCtl.setValue(dspConfig.deEssPct, false);
            compCtl.setValue(dspConfig.compPct, false);
            hpfBtn.setText("80 Hz Rumble Cut: ON");
            hpfBtn.setBackgroundResource(R.drawable.bg_chip_selected);
            syncLiveDsp.run();
        });

        TextView btnBypass = new TextView(ctx);
        btnBypass.setText("Reset Off");
        btnBypass.setGravity(android.view.Gravity.CENTER);
        btnBypass.setTextColor(0xFF9AA3AF);
        btnBypass.setBackgroundResource(R.drawable.bg_chip);
        btnBypass.setPadding(hPad, hPad / 2, hPad, hPad / 2);
        btnBypass.setOnClickListener(v -> {
            dspConfig = new DspChain.Config();
            noiseCtl.setValue(0, false);
            deEssCtl.setValue(0, false);
            compCtl.setValue(0, false);
            hpfBtn.setText("80 Hz Rumble Cut: OFF");
            hpfBtn.setBackgroundResource(R.drawable.bg_chip);
            syncLiveDsp.run();
        });

        android.widget.LinearLayout.LayoutParams halfLp =
                new android.widget.LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        halfLp.rightMargin = hPad / 2;
        presetRow.addView(btnPolish, halfLp);
        presetRow.addView(btnBypass, halfLp);

        root.addView(noiseCtl, prLp);
        root.addView(deEssCtl, prLp);
        root.addView(compCtl, prLp);
        root.addView(hpfBtn, prLp);

        TextView doneBtn = new TextView(ctx);
        doneBtn.setText(R.string.settings_done);
        doneBtn.setGravity(android.view.Gravity.CENTER);
        doneBtn.setTextColor(0xFFFFFFFF);
        doneBtn.setTextSize(14);
        doneBtn.setBackgroundResource(R.drawable.bg_btn_primary);
        doneBtn.setPadding(hPad, hPad, hPad, hPad);
        doneBtn.setOnClickListener(v -> d.dismiss());
        root.addView(doneBtn, prLp);

        d.setContentView(root);
        d.show();
    }

    private void paint() {
        if (getView() == null || !isAdded()) return;
        // A capture paused outside the studio (call, notification, mic
        // disconnect) must park the prompter too — and a resume must
        // release it — even though no studio button was involved.
        if (lastPaintedState == RecordingEngine.State.RECORDING
                && engine.state != RecordingEngine.State.RECORDING) {
            setTelePaused(true);
        } else if (lastPaintedState == RecordingEngine.State.PAUSED
                && engine.state == RecordingEngine.State.RECORDING) {
            setTelePaused(false);
        }
        lastPaintedState = engine.state;
        lastPaintedName = String.valueOf(displayName());
        boolean hasSession = engine.hasSession();
        boolean rec = engine.state == RecordingEngine.State.RECORDING;
        boolean paused = engine.state == RecordingEngine.State.PAUSED;

        String name = displayName();
        if (name != null) {
            statusText.setText(name);
            takeNameView.setText(name);
        } else {
            statusText.setText(R.string.studio_ready);
            takeNameView.setText(nextTakeName());
        }

        stateLabel.setText(!hasSession ? getString(R.string.studio_ready)
                : rec ? getString(R.string.studio_recording)
                : paused ? getString(R.string.studio_paused)
                : getString(R.string.studio_ready));
        statusText.setAlpha(name != null ? 1f : 0.7f);

        if (!hasSession) {
            hintText.setText(name != null
                    ? getString(R.string.studio_tap_to_start)
                    : getString(R.string.studio_hint));
        } else if (rec) {
            hintText.setText(R.string.studio_recording_hint);
        } else if (paused) {
            hintText.setText(R.string.studio_paused_hint);
        } else {
            hintText.setText(editSourceId != -1
                    ? getString(R.string.studio_tap_to_start)
                    : getString(R.string.studio_recovered));
        }

        // Unfinalised indicator: the pending marker means raw 32-bit float
        // bytes are streamed but the take is not finalised yet.
        boolean unfin = hasSession && isUnfinalised();
        draftBadge.setVisibility(unfin ? View.VISIBLE : View.GONE);

        formatBadge.setText(preset.shortLabel + " → "
                + TakeSaver.labelFor(preset, saveFormat));
        settingsName.setText(preset.titleRes);
        settingsSub.setText(preset.shortLabel + " → "
                + TakeSaver.labelFor(preset, saveFormat));
        settingsRow.setAlpha(saving ? 0.45f : 1f);
        setTeleActive(telePanel.getVisibility() == View.VISIBLE);

        statusDot.setBackgroundResource(rec ? R.drawable.dot_live : R.drawable.bg_round_btn);
        if (rec) startDotPulse(); else stopDotPulse();
        statusDot.setAlpha(rec ? 1f : 0.35f);
        paintEditorStrip();

        try {
            activity().syncFabWithEngine();
        } catch (Exception ignored) {}
    }

    private boolean isUnfinalised() {
        try {
            File raw = engine.rawFile;
            if (raw == null) return false;
            return new File(raw.getAbsolutePath() + ".pending").exists();
        } catch (Exception e) {
            return false;
        }
    }

    private void startDotPulse() {
        stopDotPulse();
        statusDot.setAlpha(1f);
        dotPulse = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.25f);
        dotPulse.setDuration(700);
        dotPulse.setRepeatCount(ObjectAnimator.INFINITE);
        dotPulse.setRepeatMode(ObjectAnimator.REVERSE);
        dotPulse.start();
    }

    private void stopDotPulse() {
        if (dotPulse != null) {
            dotPulse.cancel();
            dotPulse = null;
        }
    }

    // ---- files ----

    private String nextTakeName() {
        // A take still converting is not in the repository yet, so counting
        // rows alone handed the next recording the same "Take N" as the one
        // already encoding. Count those and never reuse a live title.
        List<Take> existing = TakesRepository.all();
        List<ConversionJob> converting = ConversionJobs.active();
        int n = existing.size() + converting.size() + 1;
        while (true) {
            String candidate = "Take " + n;
            boolean taken = false;
            for (Take t : existing) {
                if (candidate.equalsIgnoreCase(t.title)) { taken = true; break; }
            }
            if (!taken) {
                for (ConversionJob j : converting) {
                    if (candidate.equalsIgnoreCase(j.title)) { taken = true; break; }
                }
            }
            if (!taken) return candidate;
            n++;
        }
    }

    private static String prettyBase(File f) {
        return f.getName().replaceFirst("\\.[^.]+$", "").replace('_', ' ');
    }

    /** Centisecond timer (one more decimal place than before). */
    static String formatTimer(long ms) {
        return TimeFmt.formatTimer(ms);
    }

    private MainActivity activity() {
        return (MainActivity) requireActivity();
    }
}
