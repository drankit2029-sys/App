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
    private TextView btnPreviewPlay; // Now acts as the "Replace" mode toggle button
    private TextView btnSelectRange;
    private TextView btnReplaceSeg;  // "Select & Replace" dedicated sub-editor button
    private TextView btnTrimCrop;
    private TextView btnCutDelete;
    private TextView btnDspFx;
    private TextView btnEditUndo;

    // Inline FX Window views (replaces telePanel when open)
    private View fxPanel;
    private TextView fxPresetDropdown;
    private View fxCloseBtn;
    private android.widget.LinearLayout fxListContainer;
    private TextView fxPreviewBtn;
    private TextView fxApplyBtn;
    private boolean fxPanelOpen = false;
    private boolean teleWasVisibleBeforeFx = true;

    /**
     * False by default when scrolling in PAUSED state -> Resume FAB plays audio
     * from playhead. True when user taps "Replace" -> Resume FAB overwrites audio
     * in-place from playhead.
     */
    private boolean replaceModeArmed = false;

    /**
     * True once the user has scrolled or tapped the waveform while paused, so
     * pressing Resume plays from the playhead unless Replace is armed.
     */
    private boolean userScrolledWhilePaused = false;

    /**
     * True when inside the dedicated "Select & Replace" sub-editor mode.
     */
    private boolean inSelectAndReplaceEditor = false;
    private EditEngine.UndoJournal subEditorUndoJournal;

    private boolean replacingSelectionActive = false;
    private File replaceSavedTailFile = null;
    private RawFloatPlayer studioPreviewPlayer;
    private DspChain.Config dspConfig = new DspChain.Config();
    private DspChain livePreviewDsp;
    /**
     * True once the current dspConfig has been baked into `raw` via "Apply FX",
     * so live preview does not double-process the audio while keeping the UI
     * mode badge ("FX • Polish" / "FX • Custom") visible. Reset to false
     * whenever the user changes any FX setting or records/undos audio.
     */
    private boolean dspBakedToRaw = false;
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
        btnReplaceSeg = v.findViewById(R.id.btnReplaceSeg);
        btnTrimCrop = v.findViewById(R.id.btnTrimCrop);
        btnCutDelete = v.findViewById(R.id.btnCutDelete);
        btnDspFx = v.findViewById(R.id.btnDspFx);
        btnEditUndo = v.findViewById(R.id.btnEditUndo);

        fxPanel = v.findViewById(R.id.fxPanel);
        fxPresetDropdown = v.findViewById(R.id.fxPresetDropdown);
        fxCloseBtn = v.findViewById(R.id.fxCloseBtn);
        fxListContainer = v.findViewById(R.id.fxListContainer);
        fxPreviewBtn = v.findViewById(R.id.fxPreviewBtn);
        fxApplyBtn = v.findViewById(R.id.fxApplyBtn);

        dspConfig = DspChain.Config.load(requireContext());
        dspBakedToRaw = false;
        undoJournal = new EditEngine.UndoJournal(RecordingEngine.takesDir(requireContext()));
        subEditorUndoJournal = new EditEngine.UndoJournal(
                new File(RecordingEngine.takesDir(requireContext()), "sub_undo"));

        if (btnCutDelete != null) {
            btnCutDelete.setVisibility(View.GONE);
        }

        // 1) "Replace" Mode Toggle Button (btnPreviewPlay):
        //    Pure toggle: pressing once selects Replace (red pill, main FAB
        //    morphs to Mic icon), pressing again deselects Replace (normal pill,
        //    main FAB morphs back to Play icon). NEVER calls startOrResumeCapture()
        //    directly, so pressing Replace NEVER hides or flickers editorStrip!
        if (btnPreviewPlay != null) {
            PressAnim.applySoft(btnPreviewPlay, 0.95f);
            btnPreviewPlay.setOnClickListener(view -> {
                if (!ensurePausedForEditing()) return;
                stopStudioPreview();
                replaceModeArmed = !replaceModeArmed;
                if (replaceModeArmed) {
                    RawFloatPlayer.releaseWarmSpeaker();
                } else {
                    RawFloatPlayer.warmUpSpeaker(engine.channels);
                }
                paintEditorStrip();
                paintFxPanelControls();
                syncStudioFabIcon();
            });
        }

        // 2) "Select" Button: stays labeled "Select", simply turns red when active
        if (btnSelectRange != null) {
            PressAnim.applySoft(btnSelectRange, 0.95f);
            btnSelectRange.setOnClickListener(view -> {
                if (!ensurePausedForEditing()) return;
                stopStudioPreview();
                waveform.toggleSelectionAtPlayhead();
                paintEditorStrip();
            });
        }

        // 3) "Select & Replace" Button: opens dedicated sub-editor configuration
        if (btnReplaceSeg != null) {
            PressAnim.applySoft(btnReplaceSeg, 0.95f);
            btnReplaceSeg.setOnClickListener(view -> {
                if (!ensurePausedForEditing()) return;
                openSelectAndReplaceSubEditor();
            });
        }

        // 4) "Trim" Button: opens stylized Keep Selected / Delete Selected popup
        if (btnTrimCrop != null) {
            PressAnim.applySoft(btnTrimCrop, 0.95f);
            btnTrimCrop.setOnClickListener(view -> {
                if (!ensurePausedForEditing()) return;
                showTrimOptionsPopup();
            });
        }

        // 5) "FX" Button: opens inline Pro FX window in place of Teleprompter
        if (btnDspFx != null) {
            PressAnim.applySoft(btnDspFx, 0.95f);
            btnDspFx.setOnClickListener(view -> {
                if (!ensurePausedForEditing()) return;
                toggleFxPanel();
            });
        }

        // 6) "Undo" Button
        if (btnEditUndo != null) {
            PressAnim.applySoft(btnEditUndo, 0.95f);
            btnEditUndo.setOnClickListener(view -> {
                if (!ensurePausedForEditing()) return;
                performStudioUndo();
            });
        }

        if (fxCloseBtn != null) {
            PressAnim.applySoft(fxCloseBtn, 0.95f);
            fxCloseBtn.setOnClickListener(view -> setFxPanelVisible(false));
        }
        if (fxPresetDropdown != null) {
            PressAnim.applySoft(fxPresetDropdown, 0.95f);
            fxPresetDropdown.setOnClickListener(view -> showFxPresetsMenu());
        }
        if (fxPreviewBtn != null) {
            PressAnim.applySoft(fxPreviewBtn, 0.95f);
            fxPreviewBtn.setOnClickListener(view -> {
                if (replaceModeArmed) {
                    Toast.makeText(requireContext(),
                            "Disable Replace mode to preview effects",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                toggleStudioPreview();
                paintFxPanelControls();
            });
        }
        if (fxApplyBtn != null) {
            PressAnim.applySoft(fxApplyBtn, 0.95f);
            fxApplyBtn.setOnClickListener(view -> {
                if (replaceModeArmed) {
                    Toast.makeText(requireContext(),
                            "Disable Replace mode to apply effects",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!ensurePausedForEditing()) return;
                commitDspFx();
            });
        }

        waveform.setTimelineListener(new WaveformView.TimelineListener() {
            @Override
            public void onScrollStarted() {
                if (engine.state != RecordingEngine.State.PAUSED) return;
                RawFloatPlayer.warmUpSpeaker(engine.channels);
                engine.warmUpMic(requireContext(), preset.audioSource, engine.channels);
            }

            @Override
            public void onPlayheadScrubbed(int bucketIndex, float fraction) {
                if (engine.state != RecordingEngine.State.PAUSED) return;
                stopStudioPreview();
                long totalFrames = engine.totalFrames();
                long targetFrame = Math.round(fraction * totalFrames);
                // When the user scrolls all the way to the end of the record,
                // switch back to normal append-record mode so pressing Resume
                // records more audio instead of snapping back to 00:00.00!
                if (fraction >= 0.985f || targetFrame >= totalFrames - 1200) {
                    targetFrame = totalFrames;
                    userScrolledWhilePaused = false;
                    replaceModeArmed = false;
                } else {
                    userScrolledWhilePaused = true;
                }
                engine.seekCursorFrame(targetFrame);
                timerText.setText(formatTimer(engine.cursorMs()));
                paintEditorStrip();
                paintFxPanelControls();
            }

            @Override
            public void onSelectionChanged(boolean active, float startFrac, float endFrac) {
                paintEditorStrip();
                paintFxPanelControls();
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
    /**
     * Strictly enforces that editing options can only be used when the Studio
     * is in the PAUSED state with recorded audio.
     */
    private boolean ensurePausedForEditing() {
        if (!isAdded()) return false;
        if (engine.state == RecordingEngine.State.RECORDING) {
            Toast.makeText(requireContext(),
                    "Pause the recording first to use editing options",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        if (engine.state != RecordingEngine.State.PAUSED
                || !engine.hasSession() || engine.dataBytes <= 0) {
            Toast.makeText(requireContext(),
                    "Record and pause first to edit audio",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    /**
     * Auto-enables the Select button (making it red and placing selection handles
     * on the waveform) if the user taps Trim, Select & Replace, or Apply FX
     * without selecting a region first.
     */
    private boolean ensureSelectionOrAutoEnable(String actionName) {
        if (waveform == null || !engine.hasSession() || engine.dataBytes <= 0) {
            return false;
        }
        if (waveform.hasSelection()) return true;
        waveform.toggleSelectionAtPlayhead();
        paintEditorStrip();
        paintFxPanelControls();
        Toast.makeText(requireContext(),
                "Select enabled — drag the red [ ] handles, then tap "
                        + actionName + " again",
                Toast.LENGTH_SHORT).show();
        return false;
    }

    /** Main FAB: name -> start -> pause / play-preview / replace-record. */
    public void onFabPressed() {
        if (saving) return;
        // If preview playback is currently running while paused, tapping the
        // main FAB pauses the preview playback!
        if (studioPreviewPlayer != null) {
            stopStudioPreview();
            paintEditorStrip();
            paintFxPanelControls();
            activity().syncFabWithEngine();
            return;
        }
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

    private void resumeTake() {
        if (engine.state != RecordingEngine.State.PAUSED || saving) return;
        long totalFrames = engine.totalFrames();
        long curFrames = engine.cursorFrames();
        boolean atEnd = (curFrames >= totalFrames - 1200);
        boolean hasSel = waveform != null && waveform.hasSelection();

        // 1) If Replace mode is selected (armed): record replacement audio in-place!
        //    If the user armed Replace while parked at the very end of the take
        //    (or if a selection is active), start from selection start (if selected)
        //    or from the current playhead!
        if (replaceModeArmed) {
            if (hasSel) {
                long selStart = Math.round(
                        waveform.selectionStartFraction() * totalFrames);
                long selEnd = Math.round(
                        waveform.selectionEndFraction() * totalFrames);
                if (selEnd > selStart) {
                    engine.armPunchRange(selStart, selEnd);
                } else {
                    engine.seekCursorFrame(selStart);
                }
            } else if (atEnd && totalFrames > 0) {
                // User armed Replace while at the very end without scrolling back:
                // step back 2 seconds (or to start of take) so Replace actually
                // overwrites the last phrase instead of appending at the end!
                long backFrame = Math.max(0L,
                        totalFrames - RecordingEngine.SAMPLE_RATE * 2L);
                engine.seekCursorFrame(backFrame);
                if (waveform != null) {
                    waveform.setPlayheadFraction(backFrame / (float) totalFrames);
                }
            } else {
                // Lock cursorExplicit = true at the exact current playhead!
                engine.seekCursorFrame(curFrames);
            }
            startOrResumeCapture();
            return;
        }

        // 2) Replace is NOT selected: if parked at the end with no selection,
        //    append more audio to the recording!
        if (atEnd && !hasSel) {
            userScrolledWhilePaused = false;
            engine.seekCursorFrame(totalFrames);
            startOrResumeCapture();
            return;
        }

        // 3) Replace is NOT selected and user is scrolled back (or has a selection):
        //    play preview from the playhead!
        toggleStudioPreview();
        syncStudioFabIcon();
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
        // If inside the Select & Replace sub-editor, Stop offers to splice the
        // replacement take into the main take or discard the replacement!
        if (inSelectAndReplaceEditor) {
            List<OptionsSheet.Item> items = new ArrayList<>();
            items.add(OptionsSheet.Item.of(R.drawable.ic_check,
                    "Apply Replacement to Take",
                    () -> closeSelectAndReplaceSubEditor(true)));
            items.add(OptionsSheet.Item.danger(R.drawable.ic_trash,
                    "Discard Replacement",
                    () -> closeSelectAndReplaceSubEditor(false)));
            OptionsSheet.showOptions(requireContext(),
                    "Finish Select & Replace?",
                    "Splice this replacement recording into your take, or discard it.",
                    items);
            return;
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
        if (inSelectAndReplaceEditor) {
            if (engine.state == RecordingEngine.State.RECORDING) {
                engine.pauseCapture();
                setTelePaused(true);
                paint();
                activity().syncFabWithEngine();
            }
            onStopPressed();
            return true;
        }
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

    /**
     * Dedicated Select & Replace: allows the user to select `[selStart, selEnd]`
     * on the red waveform and record a new replacement of ANY length (shorter
     * or longer than the selected part). Both the audio file AND the in-memory
     * red waveform peaks preserve the post-selection tail and splice it back on Pause!
     */
    private void startSelectAndReplaceRecording() {
        if (!isAdded() || saving) return;
        stopStudioPreview();
        if (engine.state == RecordingEngine.State.RECORDING) return;
        if (!engine.hasSession() || engine.dataBytes <= 0 || waveform == null) return;
        if (!waveform.hasSelection()) {
            waveform.toggleSelectionAtPlayhead();
            paintEditorStrip();
            Toast.makeText(requireContext(),
                    "Drag [ ] handles on the waveform, then tap Replace again",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (RecordingService.isCallActive(requireContext())) {
            Toast.makeText(requireContext(), R.string.msg_call_active,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            File raw = engine.rawFile;
            long totalFrames = engine.totalFrames();
            float sFrac = waveform.selectionStartFraction();
            float eFrac = waveform.selectionEndFraction();
            long startF = Math.round(sFrac * totalFrames);
            long endF = Math.round(eFrac * totalFrames);
            if (endF <= startF) return;

            if (undoJournal != null) {
                undoJournal.recordSnapshot(raw);
            }
            // Snapshot & stash post-selection waveform peaks so the red waveform
            // stays at 20 bars/sec without reloading 400 bars/sec from disk!
            waveform.recordUndoPeaks();
            waveform.beginReplaceSelection();

            replaceSavedTailFile = new File(raw.getParentFile(),
                    raw.getName() + ".reptail");
            EditEngine.saveTailFrom(raw, endF, replaceSavedTailFile);
            replacingSelectionActive = true;
            dspBakedToRaw = false;

            engine.seekCursorFrame(startF);
            ensurePermissionThenStart();
        } catch (Exception e) {
            replacingSelectionActive = false;
            if (replaceSavedTailFile != null) {
                // noinspection ResultOfMethodCallIgnored
                replaceSavedTailFile.delete();
                replaceSavedTailFile = null;
            }
            Toast.makeText(requireContext(), "Could not start replace",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** Splices both the audio tail and the red waveform peak tail back on Pause/Stop. */
    private void finishSelectAndReplaceIfNeeded() {
        if (!replacingSelectionActive) return;
        replacingSelectionActive = false;
        File tail = replaceSavedTailFile;
        replaceSavedTailFile = null;
        File raw = engine.rawFile;
        if (raw == null || !raw.exists() || tail == null || !tail.exists()) return;
        try {
            long replaceEndFrame = engine.cursorFrames();
            long newBytes = EditEngine.spliceTailAfterReplace(
                    raw, replaceEndFrame, tail);
            engine.abortStreaming();
            engine.dataBytes = newBytes;
            long newTotal = engine.totalFrames();
            engine.seekCursorFrame(Math.min(replaceEndFrame, newTotal));
            if (waveform != null) {
                waveform.finishReplaceSelection();
                refreshZoomValue();
            }
            paintEditorStrip();
        } catch (Exception ignored) {
            // noinspection ResultOfMethodCallIgnored
            tail.delete();
        }
    }

    private void showTrimOptionsPopup() {
        if (!ensurePausedForEditing()) return;
        stopStudioPreview();
        if (!ensureSelectionOrAutoEnable("Trim")) return;

        long durMs = Math.max(1L, engine.durationMs());
        long sMs = Math.round(waveform.selectionStartFraction() * durMs);
        long eMs = Math.round(waveform.selectionEndFraction() * durMs);
        String rangeLabel = formatTimer(sMs) + " – " + formatTimer(eMs);

        List<OptionsSheet.Item> items = new ArrayList<>();
        items.add(OptionsSheet.Item.of(R.drawable.ic_check,
                "Keep Selected", () -> runEditOperation(true)));
        items.add(OptionsSheet.Item.danger(R.drawable.ic_trash,
                "Delete Selected", () -> runEditOperation(false)));

        OptionsSheet.showOptions(requireContext(),
                "Trim Selection",
                "Selected region: " + rangeLabel,
                items);
    }

    /**
     * Opens a new editor configuration similar to a new recording for
     * replacing the selected region `[selStart, selEnd]`, prompting the user
     * to start recording the replaced take. All editing options (`Replace`,
     * `Select`, `Trim`, `FX`, `Undo`) are available on this replacement
     * recording except `Select & Replace`.
     */
    private void openSelectAndReplaceSubEditor() {
        if (!ensurePausedForEditing()) return;
        if (inSelectAndReplaceEditor) return;
        stopStudioPreview();
        if (!ensureSelectionOrAutoEnable("Select & Replace")) return;

        long totalFrames = engine.totalFrames();
        long startF = Math.round(waveform.selectionStartFraction() * totalFrames);
        long endF = Math.round(waveform.selectionEndFraction() * totalFrames);
        if (endF <= startF) return;

        long durMs = Math.max(1L, engine.durationMs());
        String rangeStr = formatTimer(Math.round(waveform.selectionStartFraction() * durMs))
                + " – " + formatTimer(Math.round(waveform.selectionEndFraction() * durMs));

        try {
            if (subEditorUndoJournal != null) subEditorUndoJournal.clear();
            waveform.enterSubEditorMode();
            engine.enterSelectAndReplaceSubSession(requireContext(), startF, endF);
            inSelectAndReplaceEditor = true;
            replaceModeArmed = false;
            userScrolledWhilePaused = false;
            timerText.setText(formatTimer(0));
            refreshZoomValue();
            paint();
            activity().syncFabWithEngine();

            // Prompt the user to start recording the replaced take
            OptionsSheet.showConfirm(requireContext(),
                    "Select & Replace",
                    "Ready to record replacement for " + rangeStr
                            + ". Tap Start Recording now, or tap the mic button when ready.",
                    "Start Recording",
                    R.drawable.ic_mic,
                    false,
                    this::ensurePermissionThenStart);
        } catch (Exception e) {
            inSelectAndReplaceEditor = false;
            Toast.makeText(requireContext(), "Could not open Select & Replace editor",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void closeSelectAndReplaceSubEditor(boolean commit) {
        if (!inSelectAndReplaceEditor) return;
        stopStudioPreview();
        try {
            boolean hasReplacementAudio = commit
                    && engine.rawFile != null
                    && RecordingEngine.payloadBytesOf(engine.rawFile) > 0;
            if (hasReplacementAudio && waveform != null) {
                waveform.recordUndoPeaks();
            }
            engine.exitSelectAndReplaceSubSession(hasReplacementAudio, undoJournal);
            inSelectAndReplaceEditor = false;
            replaceModeArmed = false;
            userScrolledWhilePaused = false;
            if (waveform != null) {
                waveform.exitSubEditorMode(hasReplacementAudio);
                long total = Math.max(1L, engine.totalFrames());
                waveform.setPlayheadFraction(engine.cursorFrames() / (float) total);
                refreshZoomValue();
            }
            if (timerText != null) {
                timerText.setText(formatTimer(engine.cursorMs()));
            }
            paint();
            activity().syncFabWithEngine();
            Toast.makeText(requireContext(),
                    hasReplacementAudio
                            ? "Replacement spliced into take (Undo available)"
                            : "Select & Replace cancelled",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            inSelectAndReplaceEditor = false;
            paint();
        }
    }

    private void startOrResumeCapture() {
        if (!isAdded() || saving) return;
        stopStudioPreview();
        RawFloatPlayer.releaseWarmSpeaker();
        // Block capture if a phone/VoIP call is active so the mic never records
        // silent 0.0f samples during a call!
        if (RecordingService.isCallActive(requireContext())) {
            Toast.makeText(requireContext(), R.string.msg_call_active,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        boolean wasPaused = (engine.state == RecordingEngine.State.PAUSED);
        boolean isEditOrRecovery = (editSourceId != -1 || engine.dataBytes > 0);
        try {
            if (engine.hasSession() && engine.dataBytes > 0 && waveform != null) {
                long totalFrames = engine.totalFrames();
                long curFrames = engine.cursorFrames();
                if (curFrames < totalFrames - 1200) {
                    // In-place Replace from playhead: snapshot for Undo, keep
                    // the entire take forward of curFrames intact (do NOT
                    // call waveform.truncateToPlayhead()), and smoothly morph
                    // the waveform bars as new audio is recorded!
                    activeUndoJournal().recordSnapshot(engine.rawFile);
                    waveform.recordUndoPeaks();
                    dspBakedToRaw = false;
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
                if (!inSelectAndReplaceEditor) {
                    engine.beginStreaming(requireContext(), preset, saveFormat);
                }
            } catch (Exception ignored) {}
            if (engine.state == RecordingEngine.State.RECORDING) {
                if (!wasPaused && !isEditOrRecovery && !fxPanelOpen) {
                    teleprompterArmForTake();
                } else if (!fxPanelOpen) {
                    setTelePaused(false);
                }
            }
            RecordingService.start(requireContext(), preset.id);
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.msg_speaker_engaged,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        handler.post(tick);
        paint();
        syncStudioFabIcon();
    }

    private void pauseTake() {
        pauseTake(false);
    }

    private void pauseTake(boolean fromInterruption) {
        if (engine.state != RecordingEngine.State.RECORDING) return;
        long pausedAtFrame = engine.cursorFrames();
        engine.pauseCapture();
        setTelePaused(true);
        userScrolledWhilePaused = false;
        // Disarm Replace after finishing a replacement pass so the user can
        // immediately press Play to audition the replaced audio (or tap Replace
        // again if they want to record another pass).
        replaceModeArmed = false;
        if (replacingSelectionActive) {
            finishSelectAndReplaceIfNeeded();
        } else {
            long total = Math.max(1L, engine.totalFrames());
            long target = (pausedAtFrame >= 0 && pausedAtFrame <= total)
                    ? pausedAtFrame : total;
            engine.seekCursorFrame(target);
            if (waveform != null) {
                waveform.setPlayheadFraction(target / (float) total);
            }
            RawFloatPlayer.warmUpSpeaker(engine.channels);
        }
        if (fromInterruption) {
            try {
                Context c = getContext();
                if (c != null) {
                    boolean call = RecordingService.isCallActive(c);
                    Toast.makeText(c,
                            call ? R.string.msg_paused_call : R.string.msg_speaker_engaged,
                            Toast.LENGTH_SHORT).show();
                }
            } catch (Exception ignored) {}
        }
        try {
            if (isAdded() && getView() != null) {
                paint();
                syncStudioFabIcon();
            }
        } catch (Exception ignored) {}
    }

    private EditEngine.UndoJournal activeUndoJournal() {
        return inSelectAndReplaceEditor && subEditorUndoJournal != null
                ? subEditorUndoJournal : undoJournal;
    }

    // ---- stop: save (convert) or discard ----

    private void doSaveSequence() {
        if (saving || !engine.hasSession()) return;
        // A Stop tap while a Select & Replace take is paused must splice the
        // saved tail back before dispatching, or the save ships a truncated take.
        if (replacingSelectionActive
                && engine.state != RecordingEngine.State.RECORDING) {
            finishSelectAndReplaceIfNeeded();
        }
        // If the user enabled FX (Noise Suppression or Custom) in the FX dialog
        // and tapped "Done" without tapping "Apply to Take", bake the active
        // DspChain into `raw` now so the saved file actually contains the FX!
        if (dspConfig != null && dspConfig.isActive() && !dspBakedToRaw) {
            try {
                File raw = engine.rawFile;
                if (raw != null && raw.exists()) {
                    FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
                    long totalFrames = info.dataBytes / 4 / Math.max(1, info.channels);
                    if (totalFrames > 0) {
                        DspChain chain = new DspChain(info.channels, dspConfig);
                        EditEngine.applyDspToRange(raw, 0, totalFrames, chain, null);
                        engine.abortStreaming();
                        engine.dataBytes = RecordingEngine.payloadBytesOf(raw);
                        dspBakedToRaw = true;
                    }
                }
            } catch (Exception ignored) {}
        }
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
        // Discarding abandons any in-flight Select & Replace: drop the flag
        // and its orphan tail file so nothing leaks or resurrects later.
        replacingSelectionActive = false;
        if (replaceSavedTailFile != null) {
            // noinspection ResultOfMethodCallIgnored
            replaceSavedTailFile.delete();
            replaceSavedTailFile = null;
        }
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
                        // Do NOT call reloadWaveformCleanly() here! Leaving the
                        // red waveform untouched on Pause prevents the 20x
                        // bar-explosion that filled the whole screen.
                        paintEditorStrip();
                    }
                }
                // Live dBFS meter during both recording AND preview playback!
                float lvl = live ? engine.level
                        : (studioPreviewPlayer != null ? studioPreviewPlayer.level : 0f);
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

    /** Fills the waveform at 20 bars/sec for an attached edit/recovery file. */
    private void restoreWaveformForAttachedSession() {
        try {
            if (waveform == null || !engine.hasSession()) return;
            File raw = engine.rawFile;
            if (raw == null || !raw.exists()) return;
            if (RecordingEngine.payloadBytesOf(raw) <= 0) return;
            if (waveform.historyCount() > 0) return;
            waveform.loadFromFloatWavTwentyHz(raw, () -> {
                if (isAdded() && getView() != null) {
                    engine.seekCursorFrame(engine.totalFrames());
                    waveform.setPlayheadFraction(1f);
                    refreshZoomValue();
                    paintEditorStrip();
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
        if (RecordingService.isCallActive(requireContext())) {
            Toast.makeText(requireContext(), R.string.msg_speaker_engaged_play,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        File raw = engine.rawFile;
        if (raw == null || !raw.exists() || waveform == null) return;
        try {
            FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
            if (info.channels < 1 || info.channels > 2) return;
            long totalFrames = info.dataBytes / 4 / info.channels;
            if (totalFrames <= 0) return;
            // Only run livePreviewDsp if dspConfig has NOT already been baked
            // into `raw` (prevents double-applying FX while keeping badge active).
            DspChain.Config activeCfg = dspBakedToRaw
                    ? new DspChain.Config() : dspConfig;
            livePreviewDsp = new DspChain(info.channels, activeCfg);
            final RawFloatPlayer[] slot = new RawFloatPlayer[1];
            slot[0] = new RawFloatPlayer(raw, completed -> handler.post(() -> {
                if (studioPreviewPlayer == slot[0]) {
                    if (completed && engine.hasSession()) {
                        long endF = waveform != null && waveform.hasSelection()
                                ? Math.round(waveform.selectionEndFraction() * engine.totalFrames())
                                : engine.totalFrames();
                        engine.seekCursorFrame(endF);
                        if (waveform != null && engine.totalFrames() > 0) {
                            waveform.setPlayheadFraction(endF / (float) engine.totalFrames());
                        }
                    }
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
                // Play from the current playhead position! If parked at the very
                // end of the take (within 25ms), rewind to 0 so Preview plays the take.
                long start = engine.cursorFrames();
                if (start >= totalFrames - 1200) {
                    start = 0;
                    engine.seekCursorFrame(0);
                    waveform.setPlayheadFraction(0f);
                }
                player.playFrom(start);
            }
            if (btnPreviewPlay != null) {
                btnPreviewPlay.setText("■ Stop");
                btnPreviewPlay.setBackgroundResource(R.drawable.bg_chip_selected);
                btnPreviewPlay.setTextColor(0xFFFFFFFF);
            }
        } catch (Exception e) {
            stopStudioPreview();
        }
        paintEditorStrip();
    }

    private void stopStudioPreview() {
        RawFloatPlayer player = studioPreviewPlayer;
        studioPreviewPlayer = null;
        if (player != null) {
            long lastFrame = player.positionFrames();
            try {
                player.stop();
            } catch (Exception ignored) {}
            if (engine.hasSession() && engine.state != RecordingEngine.State.RECORDING) {
                engine.seekCursorFrame(lastFrame);
            }
        }
        // NOTE: Do NOT set btnPreviewPlay.setText("▶ Preview") or reset its
        // background here! btnPreviewPlay is the "Replace" toggle button and
        // is styled exclusively by paintEditorStrip() based on replaceModeArmed.
        if (btnPreviewPlay != null) {
            btnPreviewPlay.setText("Replace");
            btnPreviewPlay.setBackgroundResource(replaceModeArmed
                    ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            btnPreviewPlay.setTextColor(replaceModeArmed ? 0xFFFFFFFF : 0xFF9AA3AF);
        }
    }

    private void paintEditorStrip() {
        if (editorStrip == null || !isAdded()) return;
        // Editing options are ONLY available when the Studio is in PAUSED state!
        boolean show = engine.hasSession()
                && engine.dataBytes > 0
                && engine.state == RecordingEngine.State.PAUSED;
        if (show && editorStrip.getVisibility() != View.VISIBLE) {
            editorStrip.setVisibility(View.VISIBLE);
            editorStrip.setAlpha(0f);
            editorStrip.setTranslationY(-6f);
            editorStrip.animate().cancel();
            editorStrip.animate()
                    .alpha(1f).translationY(0f)
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                    .start();
        } else if (!show && editorStrip.getVisibility() == View.VISIBLE) {
            editorStrip.setVisibility(View.GONE);
            stopStudioPreview();
            return;
        }
        if (!show) return;

        boolean hasSel = waveform != null && waveform.hasSelection();
        long durMs = Math.max(1L, engine.durationMs());

        // 1) Replace Mode Toggle Button (btnPreviewPlay)
        if (btnPreviewPlay != null) {
            btnPreviewPlay.setText("Replace");
            btnPreviewPlay.setBackgroundResource(replaceModeArmed
                    ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            btnPreviewPlay.setTextColor(replaceModeArmed ? 0xFFFFFFFF : 0xFF9AA3AF);
        }

        // 2) Select Button: never changes text to "Selected" — stays "Select"
        //    and simply turns red (bg_chip_selected) when active!
        if (btnSelectRange != null) {
            btnSelectRange.setText("Select");
            btnSelectRange.setBackgroundResource(hasSel
                    ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            btnSelectRange.setTextColor(hasSel ? 0xFFFFFFFF : 0xFF9AA3AF);
        }

        // 3) Select & Replace Button: hidden when already inside Select & Replace sub-editor
        if (btnReplaceSeg != null) {
            btnReplaceSeg.setVisibility(inSelectAndReplaceEditor ? View.GONE : View.VISIBLE);
            btnReplaceSeg.setText("Select & Replace");
            btnReplaceSeg.setBackgroundResource(hasSel
                    ? R.drawable.bg_btn_danger : R.drawable.bg_chip);
            btnReplaceSeg.setTextColor(hasSel ? 0xFFFF453A : 0xFF9AA3AF);
        }

        // 4) Trim Button
        if (btnTrimCrop != null) {
            btnTrimCrop.setText("Trim");
            btnTrimCrop.setAlpha(hasSel ? 1f : 0.75f);
            btnTrimCrop.setTextColor(hasSel ? 0xFFF2F4F7 : 0xFF9AA3AF);
        }

        // 5) FX Button
        if (btnDspFx != null) {
            boolean fxActive = fxPanelOpen || (dspConfig != null && dspConfig.isActive());
            btnDspFx.setText(dspConfig != null && dspConfig.isActive()
                    ? dspConfig.badgeSummary() : "FX");
            btnDspFx.setBackgroundResource(fxActive
                    ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            btnDspFx.setTextColor(fxActive ? 0xFFFFFFFF : 0xFF9AA3AF);
        }

        // 6) Undo Button
        if (btnEditUndo != null) {
            EditEngine.UndoJournal uj = activeUndoJournal();
            boolean canUndo = uj != null && uj.canUndo();
            btnEditUndo.setAlpha(canUndo ? 1f : 0.40f);
            btnEditUndo.setTextColor(canUndo ? 0xFFF2F4F7 : 0xFF5D6672);
        }

        if (hintText != null) {
            if (inSelectAndReplaceEditor) {
                hintText.setText("Select & Replace Take • Tap Stop to splice into main take");
            } else if (studioPreviewPlayer != null) {
                hintText.setText("Playing preview • Tap Pause FAB or scroll to stop");
            } else if (replaceModeArmed) {
                hintText.setText("Replace armed at " + formatTimer(engine.cursorMs())
                        + " • Tap Resume to overwrite");
            } else if (hasSel) {
                long sMs = Math.round(waveform.selectionStartFraction() * durMs);
                long eMs = Math.round(waveform.selectionEndFraction() * durMs);
                hintText.setText(formatTimer(sMs) + " – " + formatTimer(eMs)
                        + " • Tap Trim, FX, or Select & Replace");
            } else if (userScrolledWhilePaused
                    || engine.cursorFrames() < engine.totalFrames() - 1200) {
                hintText.setText("Playhead " + formatTimer(engine.cursorMs())
                        + " • Tap Resume to play or tap Replace");
            } else {
                hintText.setText("Scroll waveform to scrub or tap Select");
            }
        }
        syncStudioFabIcon();
    }

    /**
     * Updates the main bottom FAB icon while paused in Studio so the user has
     * unmistakable visual feedback of what pressing the main button will do:
     * - When preview is playing: shows Pause icon.
     * - When Replace mode is selected (or parked at the end with no selection):
     *   shows Mic icon (pressing main button records/replaces audio).
     * - When scrolled back (or selection active) with Replace deselected:
     *   shows Play icon (pressing main button plays preview from playhead).
     */
    private void syncStudioFabIcon() {
        if (!isAdded()) return;
        try {
            MainActivity act = activity();
            if (engine.state == RecordingEngine.State.RECORDING) {
                act.syncFabWithEngine();
                return;
            }
            if (engine.state == RecordingEngine.State.PAUSED && engine.hasSession()) {
                act.setStopVisible(true);
                if (studioPreviewPlayer != null) {
                    act.morphFabIcon(R.drawable.ic_pause);
                    return;
                }
                long totalFrames = engine.totalFrames();
                long curFrames = engine.cursorFrames();
                boolean atEnd = (curFrames >= totalFrames - 1200);
                boolean hasSel = waveform != null && waveform.hasSelection();
                boolean willRecord = replaceModeArmed || (atEnd && !hasSel);
                act.morphFabIcon(willRecord ? R.drawable.ic_mic : R.drawable.ic_play);
                return;
            }
            act.syncFabWithEngine();
        } catch (Exception ignored) {}
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
            waveform.recordUndoPeaks();
            if (isTrim) {
                EditEngine.cropToSelection(raw, start, end, undoJournal);
                waveform.cropToSelectionBuckets();
            } else {
                EditEngine.deleteSelection(raw, start, end, undoJournal);
                waveform.deleteSelectionBuckets();
            }
            engine.abortStreaming();
            engine.dataBytes = RecordingEngine.payloadBytesOf(raw);
            long newTotal = engine.totalFrames();
            long nextCursor = isTrim ? newTotal : Math.min(start, newTotal);
            engine.seekCursorFrame(nextCursor);
            refreshZoomValue();
            timerText.setText(formatTimer(engine.cursorMs()));
            Toast.makeText(requireContext(),
                    isTrim ? "Trimmed to selection" : "Selection removed",
                    Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException iae) {
            waveform.popUndoPeaks();
            Toast.makeText(requireContext(), iae.getMessage(),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            waveform.popUndoPeaks();
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
                    if (!waveform.popUndoPeaks()) {
                        waveform.loadFromFloatWavTwentyHz(raw, this::refreshZoomValue);
                    } else {
                        refreshZoomValue();
                    }
                }
                if (timerText != null) {
                    timerText.setText(formatTimer(engine.durationMs()));
                }
                Toast.makeText(requireContext(), "Undid last edit",
                        Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {}
        paint();
    }

    /**
     * Opens or closes the inline Pro FX Rack in place of the Teleprompter.
     * Both cannot be open at the same time; while FX is open, the Teleprompter
     * toggle is disabled.
     */
    private void toggleFxPanel() {
        setFxPanelVisible(!fxPanelOpen);
    }

    private void setFxPanelVisible(boolean open) {
        if (fxPanel == null || telePanel == null) return;
        fxPanelOpen = open;
        if (open) {
            // If no effects are currently enabled when opening the FX rack,
            // activate the default Voice Polish rack so Preview & Apply work
            // immediately out of the box!
            if (dspConfig != null && !dspConfig.isActive()) {
                dspConfig.applyPreset(DspChain.Preset.VOICE_POLISH);
                dspConfig.save(requireContext());
            }
            teleWasVisibleBeforeFx = (telePanel.getVisibility() == View.VISIBLE);
            setTelePaused(true);
            telePanel.setVisibility(View.GONE);
            fxPanel.setVisibility(View.VISIBLE);
            if (teleToggle != null) {
                teleToggle.setEnabled(false);
                teleToggle.setAlpha(0.35f);
            }
            if (teleState != null) {
                teleState.setText("Disabled (FX Open)");
            }
            rebuildFxRackList();
            paintFxPanelControls();
        } else {
            fxPanel.setVisibility(View.GONE);
            if (teleToggle != null) {
                teleToggle.setEnabled(true);
                teleToggle.setAlpha(1f);
            }
            setTeleprompterVisible(teleWasVisibleBeforeFx);
        }
        paintEditorStrip();
    }

    private void paintFxPanelControls() {
        if (fxPanel == null || !isAdded()) return;
        if (fxPresetDropdown != null) {
            String presetTitle = "Custom";
            for (DspChain.Preset p : DspChain.Preset.values()) {
                if (p.id.equals(dspConfig.activePresetId)) {
                    presetTitle = p.title;
                    break;
                }
            }
            fxPresetDropdown.setText("Preset: " + presetTitle + " ▾");
        }
        boolean playing = (studioPreviewPlayer != null);
        boolean canUsePreviewAndApply = !replaceModeArmed
                && engine.state == RecordingEngine.State.PAUSED;
        if (fxPreviewBtn != null) {
            fxPreviewBtn.setText(playing ? "■ Stop Preview" : "▶ Preview");
            fxPreviewBtn.setEnabled(canUsePreviewAndApply);
            fxPreviewBtn.setAlpha(canUsePreviewAndApply ? 1f : 0.38f);
            fxPreviewBtn.setBackgroundResource(playing
                    ? R.drawable.bg_chip_selected : R.drawable.bg_btn_ghost);
        }
        if (fxApplyBtn != null) {
            boolean anyEnabled = dspConfig != null && dspConfig.isActive();
            fxApplyBtn.setText("Apply");
            fxApplyBtn.setEnabled(canUsePreviewAndApply);
            fxApplyBtn.setAlpha((canUsePreviewAndApply && anyEnabled) ? 1f : 0.38f);
            fxApplyBtn.setBackgroundResource((canUsePreviewAndApply && anyEnabled)
                    ? R.drawable.bg_btn_primary : R.drawable.bg_btn_ghost);
        }
    }

    /**
     * Populates the single scrollable FX section with collapsible effect pills
     * (each having a checkmark enable/disable toggle, collapsible TeleValue
     * settings without any progress bar, and a delete button) followed by the
     * "+ Add Effect" button at the bottom of the list.
     */
    private void rebuildFxRackList() {
        if (fxListContainer == null || !isAdded()) return;
        Context ctx = requireContext();
        fxListContainer.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(10 * density);
        int gap = Math.round(6 * density);

        for (int i = 0; i < dspConfig.slots.size(); i++) {
            final DspChain.EffectSlot slot = dspConfig.slots.get(i);
            android.widget.LinearLayout pill = new android.widget.LinearLayout(ctx);
            pill.setOrientation(android.widget.LinearLayout.VERTICAL);
            pill.setBackgroundResource(slot.enabled
                    ? R.drawable.bg_pill_option_selected : R.drawable.bg_card);
            pill.setPadding(pad, Math.round(8 * density), pad, Math.round(8 * density));

            android.widget.LinearLayout.LayoutParams pillLp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) pillLp.topMargin = gap;

            // Header Row: [Checkmark Toggle] | [Title + Value Summary (Tap to Expand)] | [Delete]
            android.widget.LinearLayout header = new android.widget.LinearLayout(ctx);
            header.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            header.setGravity(android.view.Gravity.CENTER_VERTICAL);

            // 1) Checkmark Enable/Disable Toggle
            TextView checkBtn = new TextView(ctx);
            checkBtn.setText(slot.enabled ? "✓" : "");
            checkBtn.setTextSize(13f);
            checkBtn.setGravity(android.view.Gravity.CENTER);
            checkBtn.setTextColor(0xFFFFFFFF);
            checkBtn.setBackgroundResource(slot.enabled
                    ? R.drawable.bg_chip_selected : R.drawable.bg_select_ring);
            int chkSz = Math.round(24 * density);
            android.widget.LinearLayout.LayoutParams chkLp =
                    new android.widget.LinearLayout.LayoutParams(chkSz, chkSz);
            chkLp.rightMargin = Math.round(10 * density);
            header.addView(checkBtn, chkLp);

            // 2) Title + Collapsible Chevron Summary
            android.widget.LinearLayout titleCol = new android.widget.LinearLayout(ctx);
            titleCol.setOrientation(android.widget.LinearLayout.VERTICAL);
            TextView titleTv = new TextView(ctx);
            titleTv.setText(slot.type.title);
            titleTv.setTextSize(13f);
            titleTv.setTextColor(slot.enabled ? 0xFFF2F4F7 : 0xFF9AA3AF);
            titleTv.setTypeface(android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL));

            TextView subTv = new TextView(ctx);
            subTv.setText((slot.enabled ? "Enabled • " : "Bypassed • ")
                    + slot.type.paramLabel + ": " + slot.value + slot.type.unit
                    + (slot.expanded ? "  ▴" : "  ▾"));
            subTv.setTextSize(10.5f);
            subTv.setTextColor(slot.enabled ? 0xFFFF453A : 0xFF5D6672);
            titleCol.addView(titleTv);
            titleCol.addView(subTv);
            header.addView(titleCol, new android.widget.LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // 3) Delete Button (removes effect from menu)
            android.widget.ImageView delBtn = new android.widget.ImageView(ctx);
            delBtn.setImageResource(R.drawable.ic_trash);
            delBtn.setColorFilter(0xFF9AA3AF);
            int delSz = Math.round(28 * density);
            int delPad = Math.round(5 * density);
            delBtn.setPadding(delPad, delPad, delPad, delPad);
            delBtn.setBackgroundResource(R.drawable.bg_round_btn);
            header.addView(delBtn, new android.widget.LinearLayout.LayoutParams(delSz, delSz));

            pill.addView(header);

            // Collapsible Settings Body (NO progress bar — clean TeleValue control)
            android.widget.LinearLayout body = new android.widget.LinearLayout(ctx);
            body.setOrientation(android.widget.LinearLayout.VERTICAL);
            body.setVisibility(slot.expanded ? View.VISIBLE : View.GONE);
            android.widget.LinearLayout.LayoutParams bodyLp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            bodyLp.topMargin = Math.round(8 * density);

            TextView descTv = new TextView(ctx);
            descTv.setText(slot.type.description);
            descTv.setTextSize(10.5f);
            descTv.setTextColor(0xFF8A93A0);
            body.addView(descTv);

            TeleValue ctl = new TeleValue(ctx);
            ctl.setCaption(slot.type.paramLabel);
            ctl.configure(slot.type.minVal, slot.type.maxVal,
                    slot.type.stepVal, slot.value, slot.type.unit);
            android.widget.LinearLayout.LayoutParams ctlLp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            ctlLp.topMargin = Math.round(4 * density);
            body.addView(ctl, ctlLp);
            pill.addView(body, bodyLp);

            Runnable onSlotMutated = () -> {
                dspBakedToRaw = false;
                dspConfig.activePresetId = DspChain.Preset.CUSTOM.id;
                dspConfig.syncLegacyFieldsFromSlots();
                dspConfig.save(ctx);
                if (livePreviewDsp != null) {
                    livePreviewDsp.updateConfig(dspConfig);
                }
                subTv.setText((slot.enabled ? "Enabled • " : "Bypassed • ")
                        + slot.type.paramLabel + ": " + slot.value + slot.type.unit
                        + (slot.expanded ? "  ▴" : "  ▾"));
                paintEditorStrip();
                paintFxPanelControls();
            };

            checkBtn.setOnClickListener(v -> {
                slot.enabled = !slot.enabled;
                onSlotMutated.run();
                rebuildFxRackList();
            });

            titleCol.setOnClickListener(v -> {
                slot.expanded = !slot.expanded;
                body.setVisibility(slot.expanded ? View.VISIBLE : View.GONE);
                dspConfig.save(ctx);
                subTv.setText((slot.enabled ? "Enabled • " : "Bypassed • ")
                        + slot.type.paramLabel + ": " + slot.value + slot.type.unit
                        + (slot.expanded ? "  ▴" : "  ▾"));
            });

            delBtn.setOnClickListener(v -> {
                dspConfig.removeSlot(slot);
                dspConfig.save(ctx);
                if (livePreviewDsp != null) {
                    livePreviewDsp.updateConfig(dspConfig);
                }
                rebuildFxRackList();
                paintEditorStrip();
                paintFxPanelControls();
            });

            TeleValue.OnScrubListener scrub = new TeleValue.OnScrubListener() {
                @Override
                public void onScrubValueChanged(int value) {
                    slot.value = value;
                    if (!slot.enabled && value > 0) slot.enabled = true;
                    onSlotMutated.run();
                }

                @Override
                public void onScrubFinished(int value) {
                    slot.value = value;
                    onSlotMutated.run();
                    rebuildFxRackList();
                }
            };
            ctl.setOnScrubListener(scrub);
            ctl.setOnValueChanged(val -> {
                slot.value = val;
                if (!slot.enabled && val > 0) slot.enabled = true;
                onSlotMutated.run();
                rebuildFxRackList();
            });

            fxListContainer.addView(pill, pillLp);
        }

        // "+ Add Effect" Button at the bottom of the scrollable list
        TextView addBtn = new TextView(ctx);
        addBtn.setText("+ Add Effect");
        addBtn.setTextSize(12.5f);
        addBtn.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        addBtn.setGravity(android.view.Gravity.CENTER);
        addBtn.setTextColor(0xFFF2F4F7);
        addBtn.setBackgroundResource(R.drawable.bg_btn_ghost);
        int addPad = Math.round(10 * density);
        addBtn.setPadding(addPad, addPad, addPad, addPad);
        android.widget.LinearLayout.LayoutParams addLp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.topMargin = Math.round(8 * density);
        PressAnim.applySoft(addBtn, 0.96f);
        addBtn.setOnClickListener(v -> showAddEffectSearchDialog());
        fxListContainer.addView(addBtn, addLp);
    }

    /** Dropdown menu of premade presets (templates of effects and their settings). */
    private void showFxPresetsMenu() {
        if (!isAdded()) return;
        DspChain.Preset[] presets = {
                DspChain.Preset.VOICE_POLISH,
                DspChain.Preset.PODCAST_BROADCAST,
                DspChain.Preset.NOISY_ROOM,
                DspChain.Preset.WARM_VOCAL,
                DspChain.Preset.CRISP_CLARITY,
                DspChain.Preset.CLEAR_ALL
        };
        List<OptionsSheet.Item> items = new ArrayList<>();
        for (DspChain.Preset p : presets) {
            final DspChain.Preset pick = p;
            boolean checked = p.id.equals(dspConfig.activePresetId);
            items.add(new OptionsSheet.Item(0, p.title, false, checked, () -> {
                dspBakedToRaw = false;
                dspConfig.applyPreset(pick);
                dspConfig.save(requireContext());
                if (livePreviewDsp != null) {
                    livePreviewDsp.updateConfig(dspConfig);
                }
                rebuildFxRackList();
                paintFxPanelControls();
                paintEditorStrip();
            }));
        }
        OptionsSheet.showOptions(requireContext(),
                "FX Presets",
                "Premade effect chains & settings",
                items);
    }

    /** Searchable menu of available effects opened by "+ Add Effect". */
    private void showAddEffectSearchDialog() {
        if (!isAdded()) return;
        Context ctx = requireContext();
        android.app.Dialog d = new android.app.Dialog(ctx, R.style.TakeOneCenterDialog);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(18 * density);
        android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_modal);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText("Add Effect");
        title.setTextSize(17f);
        title.setTextColor(0xFFF2F4F7);
        title.setGravity(android.view.Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        root.addView(title);

        android.widget.EditText searchBox = new android.widget.EditText(ctx);
        searchBox.setHint("Search effects (e.g. noise, de-ess, warm)...");
        searchBox.setHintTextColor(0xFF5D6672);
        searchBox.setTextColor(0xFFF2F4F7);
        searchBox.setTextSize(13.5f);
        searchBox.setSingleLine(true);
        searchBox.setBackgroundResource(R.drawable.bg_search);
        int sPad = Math.round(10 * density);
        searchBox.setPadding(sPad, sPad, sPad, sPad);
        android.widget.LinearLayout.LayoutParams sLp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = Math.round(10 * density);
        root.addView(searchBox, sLp);

        android.widget.ScrollView listScroll = new android.widget.ScrollView(ctx);
        android.widget.LinearLayout listCol = new android.widget.LinearLayout(ctx);
        listCol.setOrientation(android.widget.LinearLayout.VERTICAL);
        listScroll.addView(listCol);
        android.widget.LinearLayout.LayoutParams scLp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Math.round(240 * density));
        scLp.topMargin = Math.round(10 * density);
        root.addView(listScroll, scLp);

        Runnable populateList = () -> {
            String q = searchBox.getText().toString().trim().toLowerCase(Locale.US);
            listCol.removeAllViews();
            for (DspChain.EffectType t : DspChain.EffectType.values()) {
                if (!q.isEmpty()
                        && !t.title.toLowerCase(Locale.US).contains(q)
                        && !t.description.toLowerCase(Locale.US).contains(q)) {
                    continue;
                }
                android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
                row.setOrientation(android.widget.LinearLayout.VERTICAL);
                row.setBackgroundResource(R.drawable.bg_pill_option);
                row.setPadding(sPad, sPad, sPad, sPad);
                android.widget.LinearLayout.LayoutParams rLp =
                        new android.widget.LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                rLp.topMargin = Math.round(6 * density);

                TextView rTitle = new TextView(ctx);
                rTitle.setText(t.title);
                rTitle.setTextSize(13.5f);
                rTitle.setTextColor(0xFFF2F4F7);
                rTitle.setTypeface(android.graphics.Typeface.create(
                        "sans-serif-medium", android.graphics.Typeface.NORMAL));

                TextView rSub = new TextView(ctx);
                rSub.setText(t.description);
                rSub.setTextSize(11f);
                rSub.setTextColor(0xFF9AA3AF);

                row.addView(rTitle);
                row.addView(rSub);
                PressAnim.applySoft(row, 0.97f);
                row.setOnClickListener(v -> {
                    dspBakedToRaw = false;
                    dspConfig.addOrFocusSlot(t);
                    dspConfig.save(ctx);
                    if (livePreviewDsp != null) {
                        livePreviewDsp.updateConfig(dspConfig);
                    }
                    rebuildFxRackList();
                    paintFxPanelControls();
                    paintEditorStrip();
                    d.dismiss();
                });
                listCol.addView(row, rLp);
            }
        };
        populateList.run();

        searchBox.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                populateList.run();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        TextView cancelBtn = new TextView(ctx);
        cancelBtn.setText(R.string.cancel);
        cancelBtn.setGravity(android.view.Gravity.CENTER);
        cancelBtn.setTextColor(0xFF9AA3AF);
        cancelBtn.setTextSize(13.5f);
        cancelBtn.setBackgroundResource(R.drawable.bg_btn_ghost);
        cancelBtn.setPadding(sPad, sPad, sPad, sPad);
        cancelBtn.setOnClickListener(v -> d.dismiss());
        root.addView(cancelBtn, sLp);

        d.setContentView(root);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }


    /**
     * Applies enabled effects from the FX rack to the selected audio region.
     * Requires the user to select an area first (auto-enabling Select and
     * prompting them if nothing is selected). Works identically on both the
     * normal recording and the Select & Replace sub-editor recording!
     */

    /**
     * Applies enabled effects from the FX rack to the selected audio region.
     * Requires the user to select an area first (auto-enabling Select and
     * prompting them if nothing is selected). Works identically on both the
     * normal recording and the Select & Replace sub-editor recording!
     */
    private boolean commitDspFx() {
        if (!ensurePausedForEditing()) return false;
        if (replaceModeArmed) {
            Toast.makeText(requireContext(),
                    "Disable Replace mode before applying effects",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        stopStudioPreview();
        dspConfig.syncLegacyFieldsFromSlots();
        if (dspConfig == null || !dspConfig.isActive()) {
            Toast.makeText(requireContext(),
                    "Enable at least one effect (checkmark) first",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        // Require the user to select the audio region before applying FX!
        boolean hadSel = waveform.hasSelection();
        if (!hadSel && !ensureSelectionOrAutoEnable("Apply")) {
            return false;
        }
        File raw = engine.rawFile;
        if (raw == null || !raw.exists() || waveform == null) return false;
        try {
            FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
            long totalFrames = info.dataBytes / 4 / Math.max(1, info.channels);
            if (totalFrames <= 0) return false;
            long start = Math.round(waveform.selectionStartFraction() * totalFrames);
            long end = Math.round(waveform.selectionEndFraction() * totalFrames);
            if (end <= start) {
                Toast.makeText(requireContext(), "Select a valid audio region first",
                        Toast.LENGTH_SHORT).show();
                return false;
            }
            waveform.recordUndoPeaks();
            DspChain chain = new DspChain(info.channels, dspConfig);
            EditEngine.applyDspToRange(raw, start, end, chain, activeUndoJournal());
            engine.abortStreaming();
            engine.dataBytes = RecordingEngine.payloadBytesOf(raw);
            if (!hadSel) {
                // Full-range bake: keep the future baked flag set so the next
                // save() won't re-apply the DSP chain on top of these samples!
                dspBakedToRaw = true;
                // Redraw the whole waveform from the freshly baked WAV!
                waveform.reloadSelectionPeaksFromWav(raw, 0f, 1f);
            } else {
                waveform.reloadSelectionPeaksFromWav(raw,
                        waveform.selectionStartFraction(),
                        waveform.selectionEndFraction());
            }
            waveform.clearSelection();
            Toast.makeText(requireContext(),
                    "Effects applied to selected region (Undo available)",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            if (waveform != null) waveform.popUndoPeaks();
            Toast.makeText(requireContext(), "FX failed",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        paint();
        paintFxPanelControls();
        return true;
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

        syncStudioFabIcon();
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
