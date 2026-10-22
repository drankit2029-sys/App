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
import android.content.res.ColorStateList;
import android.view.Choreographer;
import android.view.GestureDetector;
import androidx.core.widget.TextViewCompat;
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
    private final Handler completionHandler =
            new Handler(Looper.getMainLooper());
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
    private TextView fxTitle;
    private TextView fxPresetDropdown;
    private View fxCloseBtn;
    private android.widget.LinearLayout fxListContainer;
    private TextView fxHint;
    private boolean fxPanelOpen = false;
    private boolean teleWasVisibleBeforeFx = true;
    /**
     * The region the FX rack commits to, frozen when the rack opens.
     *
     * <p>The user selects the audio they want to treat before opening the
     * rack, and that selection stays the apply target even if they then
     * re-select a different range (for example to learn a noise profile).
     */
    private float fxApplyTargetStart = 0f;
    private float fxApplyTargetEnd = 1f;
    private boolean fxApplyTargetHasSelection = false;
    /**
     * Monotonic token for the live waveform render. Every rack change bumps
     * it so a slow render built from an older rack is discarded instead of
     * painting stale bars.
     */
    private int fxPreviewGeneration = 0;
    private final java.util.Set<DspChain.EffectSlot> fxAdvancedOpen =
            java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<DspChain.EffectSlot, Boolean>());

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
    private long replacePassStartFrame = -1L;
    private long replaceTargetEndFrame = -1L;
    private long lastPushedCursorFrame = -1L;
    private File replaceSavedTailFile = null;
    private RawFloatPlayer studioPreviewPlayer;
    private boolean previewPlaysLiveFx = false;
    private DspChain.Config dspConfig = new DspChain.Config();
    private DspChain livePreviewDsp;
    /**
     * True once the current dspConfig has been baked into `raw` via "Apply FX",
     * so live preview does not double-process the audio while keeping the UI
     * mode badge ("FX • Polish" / "FX • Custom") visible. Reset to false
     * whenever the user changes any FX setting or records/undos audio.
     */
    private boolean dspBakedToRaw = false;
    private boolean dspSubRangeBaked = false;
    /**
     * True while `raw` still carries a sub-range FX bake whose untouched
     * pre-bake snapshot is the top of {@link #activeUndoJournal()}, even
     * after an FX knob tweak cleared {@link #dspSubRangeBaked}. A save that
     * would bake the whole take must roll that snapshot back first so the
     * sub-selection is never double-baked.
     */
    private boolean lastEditWasSubRangeBake = false;
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
    private long lastTeleFrameNanos;
    private boolean teleChoreographerActive;

    private final Choreographer.FrameCallback teleFrameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            teleChoreographerActive = false;
            if (!isAdded() || getView() == null || telePaused || namingModalOpen || fxPanelOpen) {
                lastTeleFrameNanos = 0L;
                return;
            }
            advanceTeleprompterFrame(frameTimeNanos);
            if (!telePaused && tele != null && tele.autoScroll
                    && telePanel != null && telePanel.getVisibility() == View.VISIBLE) {
                scheduleTeleFrame();
            }
        }
    };

    private void scheduleTeleFrame() {
        if (teleChoreographerActive || telePaused || namingModalOpen || fxPanelOpen) return;
        try {
            Choreographer.getInstance().postFrameCallback(teleFrameCallback);
            teleChoreographerActive = true;
        } catch (Throwable ignored) {
            teleChoreographerActive = false;
        }
    }

    private void cancelTeleFrame() {
        teleChoreographerActive = false;
        lastTeleFrameNanos = 0L;
        try {
            Choreographer.getInstance().removeFrameCallback(teleFrameCallback);
        } catch (Throwable ignored) {}
    }
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
        if (formatBadge != null) {
            formatBadge.setOnClickListener(view -> showRecordingSettings());
        }
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
        fxTitle = v.findViewById(R.id.fxTitle);
        fxPresetDropdown = v.findViewById(R.id.fxPresetDropdown);
        fxCloseBtn = v.findViewById(R.id.fxCloseBtn);
        fxListContainer = v.findViewById(R.id.fxListContainer);
        fxHint = v.findViewById(R.id.fxHint);

        dspConfig = DspChain.Config.load(requireContext());
        dspBakedToRaw = false;
        dspSubRangeBaked = false;
        lastEditWasSubRangeBake = false;
        undoJournal = new EditEngine.UndoJournal(RecordingEngine.takesDir(requireContext()));
        subEditorUndoJournal = new EditEngine.UndoJournal(
                new File(RecordingEngine.takesDir(requireContext()), "sub_undo"));

        if (btnCutDelete != null) {
            btnCutDelete.setVisibility(View.GONE);
        }

        // 1) "Replace" Mode Toggle Button (btnPreviewPlay):
        //    Pure toggle: pressing once selects Replace (red pill, main FAB
        //    morphs to Mic icon) and locks all other editing options; pressing
        //    again deselects Replace and re-enables the other editing options.
        if (btnPreviewPlay != null) {
            PressAnim.applySoft(btnPreviewPlay, 0.95f);
            btnPreviewPlay.setOnClickListener(view -> {
                if (fxPanelOpen) return;
                if (!ensurePausedForEditing()) return;
                stopStudioPreview();
                replaceModeArmed = !replaceModeArmed;
                if (replaceModeArmed) {
                    if (waveform != null) {
                        waveform.clearSelection();
                        waveform.setSelectionLocked(true);
                    }
                    if (fxPanelOpen) {
                        setFxPanelVisible(false);
                    }
                    RawFloatPlayer.releaseWarmSpeaker();
                } else {
                    if (waveform != null) {
                        waveform.setSelectionLocked(false);
                    }
                    RawFloatPlayer.warmUpSpeaker(engine.channels);
                }
                paintEditorStrip();
                paintFxPanelControls();
                syncStudioFabIcon();
            });
        }

        // 2) "Select" Button: stays labeled "Select", simply turns red when active
        //    (does NOT activate Select & Replace, and is blocked while Replace is on)
        if (btnSelectRange != null) {
            PressAnim.applySoft(btnSelectRange, 0.95f);
            btnSelectRange.setOnClickListener(view -> {
                if (replaceModeArmed || fxPanelOpen) return;
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
                if (replaceModeArmed || fxPanelOpen) return;
                if (!ensurePausedForEditing()) return;
                openSelectAndReplaceSubEditor();
            });
        }

        // 4) "Trim" Button: opens stylized Keep Selected / Delete Selected popup
        if (btnTrimCrop != null) {
            PressAnim.applySoft(btnTrimCrop, 0.95f);
            btnTrimCrop.setOnClickListener(view -> {
                if (replaceModeArmed || fxPanelOpen) return;
                if (!ensurePausedForEditing()) return;
                showTrimOptionsPopup();
            });
        }

        // 5) "FX" Button: opens inline Pro FX window in place of Teleprompter
        if (btnDspFx != null) {
            PressAnim.applySoft(btnDspFx, 0.95f);
            btnDspFx.setOnClickListener(view -> {
                if (replaceModeArmed) return;
                toggleFxPanel();
            });
        }

        // 6) "Undo" Button
        if (btnEditUndo != null) {
            PressAnim.applySoft(btnEditUndo, 0.95f);
            btnEditUndo.setOnClickListener(view -> {
                if (replaceModeArmed || fxPanelOpen) return;
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
        waveform.setTimelineListener(new WaveformView.TimelineListener() {
            @Override
            public void onScrollStarted() {
                if (!isSessionEditable()) return;
                RawFloatPlayer.warmUpSpeaker(engine.channels);
                engine.warmUpMic(requireContext(), preset.audioSource, engine.channels);
            }

            @Override
            public void onPlayheadScrubbed(int bucketIndex, float fraction) {
                if (!isSessionEditable()) return;
                stopStudioPreview();
                long totalFrames = engine.totalFrames();
                long targetFrame = Math.round(fraction * totalFrames);
                // Never disarm replaceModeArmed while scrubbing! If the user
                // enabled Replace at the end of the take and starts dragging
                // back, the initial move events are near 1.0f and must keep
                // Replace armed.
                //
                // The playhead is NEVER snapped forward here: the timer must
                // show the real scrubbed position. At low zoom a short drag
                // covers many buckets, so forcing targetFrame = totalFrames as
                // soon as the drag came within 480 frames of the end made the
                // timer jump to the whole take duration mid-scroll. Parking at
                // the end is still recognised by the 480-frame tolerance in
                // resumeTake()/syncStudioFabIcon(), which is all this flag is for.
                if (!replaceModeArmed
                        && (fraction >= 0.995f || targetFrame >= totalFrames - 480)) {
                    userScrolledWhilePaused = false;
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

        if (editorStrip != null) {
            editorStrip.setVisibility(View.VISIBLE);
        }
        paint();
        handler.removeCallbacks(tick);
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
        handler.removeCallbacks(tick);
        handler.post(tick);
        if (!telePaused) scheduleTeleFrame();
        paint();
        activity().syncFabWithEngine();
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        cancelTeleFrame();
        persistTeleScript();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        cancelTeleFrame();
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
        // Teleprompter is always present unless the inline FX screen is open.
        boolean showTele = visible || !fxPanelOpen;
        telePanel.setVisibility(showTele ? View.VISIBLE : View.GONE);
        setTeleActive(showTele);
    }

    private void toggleTeleprompter() {
        // Teleprompter is permanently visible when not in the FX screen; if FX is
        // open, switching back restores the teleprompter.
        if (fxPanelOpen) {
            setFxPanelVisible(false);
        } else {
            setTeleprompterVisible(true);
        }
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
    /**
     * Native display refresh-rate (60Hz/90Hz/120Hz) VSYNC callback so the
     * teleprompter scrolls smoothly every single display frame.
     */
    private void advanceTeleprompterFrame(long frameTimeNanos) {
        if (teleView == null || tele == null) return;
        long nowMs = SystemClock.elapsedRealtime();
        if (!tele.autoScroll || telePaused || namingModalOpen) {
            lastTeleAdvanceMs = 0L;
            lastTeleFrameNanos = 0L;
            return;
        }
        if (telePanel == null || telePanel.getVisibility() != View.VISIBLE) {
            lastTeleAdvanceMs = 0L;
            lastTeleFrameNanos = 0L;
            return;
        }
        if (teleView.isDragging()) {
            lastTeleAdvanceMs = nowMs;
            lastTeleFrameNanos = frameTimeNanos;
            return;
        }
        if (teleView.maxScroll() > 0f
                && teleView.scrollOffset() >= teleView.maxScroll() - 0.5f) {
            setTelePaused(true);
            return;
        }
        float dt = lastTeleFrameNanos > 0L
                ? Math.max(0f, (frameTimeNanos - lastTeleFrameNanos) / 1_000_000_000f)
                : (1f / 60f);
        dt = Math.min(dt, 0.08f);
        lastTeleFrameNanos = frameTimeNanos;
        lastTeleAdvanceMs = nowMs;
        float px = TeleMath.scrollPxPerSecond(tele.wordsPerMinute,
                teleView.effectiveWordsPerLine(),
                teleView.renderedTextSizePx());
        if (px <= 0f) return;
        teleView.scrollBy(px * dt);
    }

    /**
     * Fallback called from the 50ms tick only when Choreographer has not
     * already advanced the current frame (e.g. in headless Robolectric tests).
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
        // If Choreographer is actively driving VSYNC frames, let it own the
        // scroll update so we never step at 20Hz on top of 60/120Hz.
        if (teleChoreographerActive && lastTeleFrameNanos > 0L
                && (now - lastTeleAdvanceMs) < 40L) {
            return;
        }
        if (!teleChoreographerActive) {
            scheduleTeleFrame();
        }
        if (teleView.isDragging()) {
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
                : (1f / 60f);
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
        lastTeleFrameNanos = 0L;
        if (!telePaused) {
            teleManualUntil = 0L;
            scheduleTeleFrame();
        } else {
            cancelTeleFrame();
        }
        if (telePauseBtn != null) {
            telePauseBtn.setText(telePaused
                    ? R.string.tele_resume_short : R.string.tele_pause_short);
            telePauseBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    telePaused ? R.drawable.ic_tele_play : R.drawable.ic_tele_pause,
                    0, 0, 0);
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
        TextViewCompat.setCompoundDrawableTintList(teleMirrorBtn,
                ColorStateList.valueOf(on ? 0xFFFF3B30 : 0xFFF2F4F7));
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
     * True when the attached session holds recorded/imported audio and is not
     * actively capturing (either PAUSED after recording, or IDLE right after
     * opening a take via Library -> Edit or crash recovery).
     */
    private boolean isSessionEditable() {
        return engine.hasSession()
                && !engine.isSaving()
                && engine.dataBytes > 0
                && (engine.state == RecordingEngine.State.PAUSED
                        || engine.state == RecordingEngine.State.IDLE);
    }

    /**
     * Enforces that editing options can only be used when the Studio has
     * recorded/imported audio and is not actively capturing.
     */
    private boolean ensurePausedForEditing() {
        if (!isAdded()) return false;
        if (engine.state == RecordingEngine.State.RECORDING) {
            Toast.makeText(requireContext(),
                    "Pause the recording first to use editing options",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        if (!isSessionEditable()) {
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
        if (saving || engine.isSaving()) return;
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
        } else if (isSessionEditable()) {
            resumeTake();
        } else {
            ensurePermissionThenStart();
        }
    }

    private void resumeTake() {
        if (!isSessionEditable() || saving) return;
        long totalFrames = engine.totalFrames();
        long curFrames = engine.cursorFrames();
        if (waveform != null && totalFrames > 0) {
            long wfFrame = Math.round(waveform.playheadFraction() * totalFrames);
            if (Math.abs(wfFrame - curFrames) > 240) {
                curFrames = wfFrame;
                engine.seekCursorFrame(curFrames);
            }
        }
        boolean atEnd = (curFrames >= totalFrames - 480);
        boolean hasSel = !replaceModeArmed && waveform != null && waveform.hasSelection();

        // 0) Effects mode: the FX rack is open, so the transport plays the
        //    effect-applied audio instead of starting a new recording, and it
        //    always plays from the top when parked at the end of the take.
        if (fxPanelOpen) {
            if (atEnd) {
                curFrames = 0;
                engine.seekCursorFrame(0);
                if (waveform != null) waveform.setPlayheadFraction(0f);
            }
            toggleStudioPreview(true);
            syncStudioFabIcon();
            return;
        }

        // 1) If Replace mode is selected (armed): record replacement audio in-place!
        //    Nuance: if the playhead is already at the end of the take, deactivate
        //    Replace and append to the recording like normal.
        if (replaceModeArmed) {
            if (atEnd) {
                replaceModeArmed = false;
                replacePassStartFrame = -1L;
                replaceTargetEndFrame = -1L;
                if (waveform != null) {
                    waveform.setSelectionLocked(false);
                }
                userScrolledWhilePaused = false;
                engine.seekCursorFrame(totalFrames);
                paintEditorStrip();
                paintFxPanelControls();
                startOrResumeCapture();
                return;
            }
            // Lock cursorExplicit = true at the exact current playhead!
            replacePassStartFrame = curFrames;
            replaceTargetEndFrame = totalFrames;
            engine.seekCursorFrame(curFrames);
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
        toggleStudioPreview(false);
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
        // Effects mode: Stop commits the rack to the selected region, which
        // replaces the selected audio with the effect-applied audio.
        if (fxPanelOpen) {
            confirmApplyEffects();
            return;
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

    /**
     * Asks for confirmation before committing the FX rack.
     *
     * <p>In effects mode the target is the region the user selected before
     * opening the rack; committing replaces that region with the
     * effect-applied audio. Without a selection the whole take is the target.
     */
    private void confirmApplyEffects() {
        if (!isAdded()) return;
        if (dspConfig == null || !dspConfig.isActive()) {
            Toast.makeText(requireContext(),
                    "Enable at least one effect first",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        boolean hasSel = fxApplyTargetHasSelection;
        String target = hasSel
                ? "the selected region"
                : "the entire take";
        List<OptionsSheet.Item> items = new ArrayList<>();
        items.add(OptionsSheet.Item.of(R.drawable.ic_check,
                "Apply Effects",
                this::applyEffectsToSelection));
        items.add(OptionsSheet.Item.danger(R.drawable.ic_trash,
                "Cancel",
                () -> { }));
        OptionsSheet.showOptions(requireContext(),
                "Apply Effects to " + target + "?",
                "This replaces " + target
                        + " with the effect-applied audio. It can be undone.",
                items);
    }

    /**
     * Commits the rack to the apply target, replacing that region with the
     * effect-applied audio.
     */
    void applyEffectsToSelection() {
        if (!isAdded()) return;
        if (commitDspFx()) {
            setFxPanelVisible(false);
        }
    }

    /** True while the FX rack is open (effects mode). */
    public boolean isFxPanelOpen() {
        return fxPanelOpen;
    }

    /** Back: idle -> exit; active -> background-or-stop choice. */
    public boolean onBackPressed() {
        if (saving || engine.isSaving()) return true;
        if (fxPanelOpen) {
            setFxPanelVisible(false);
            return true;
        }
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
        // audio or applied any edits/FX yet, backing out cancels the edit
        // immediately without nagging.
        if (editSourceId != -1
                && engine.state == RecordingEngine.State.IDLE
                && engine.dataBytes == initialSessionBytes
                && !dspBakedToRaw
                && !engine.isInPlaceEdited()
                && (activeUndoJournal() == null || !activeUndoJournal().canUndo())) {
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
        if (!isAdded() || saving || engine.isSaving()) return;
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
            dspBakedToRaw = false;
            dspSubRangeBaked = false;
            lastEditWasSubRangeBake = false;
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

            // Only mirror the audio snapshot into the waveform peak stack when
            // the snapshot actually landed: recordSnapshot() refuses takes
            // over 30 MB, and an unconditional peaks push would desynchronise
            // the two undo stacks on long takes.
            if (undoJournal != null && undoJournal.recordSnapshot(raw)) {
                // Snapshot & stash post-selection waveform peaks so the red
                // waveform stays at 20 bars/sec without reloading 400 bars/sec
                // from disk!
                waveform.recordUndoPeaks();
            }
            waveform.beginReplaceSelection();

            replaceSavedTailFile = new File(raw.getParentFile(),
                    raw.getName() + ".reptail");
            EditEngine.saveTailFrom(raw, endF, replaceSavedTailFile);
            replacingSelectionActive = true;
            dspBakedToRaw = false;
            dspSubRangeBaked = false;
            lastEditWasSubRangeBake = false;

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
            dspBakedToRaw = false;
            dspSubRangeBaked = false;
            lastEditWasSubRangeBake = false;
            replaceModeArmed = false;
            if (waveform != null) waveform.setSelectionLocked(false);
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
            if (hasReplacementAudio) {
                dspBakedToRaw = false;
                dspSubRangeBaked = false;
                lastEditWasSubRangeBake = false;
            }
            replaceModeArmed = false;
            if (waveform != null) waveform.setSelectionLocked(false);
            userScrolledWhilePaused = false;
            if (waveform != null) {
                waveform.exitSubEditorMode(hasReplacementAudio);
                long total = Math.max(1L, engine.totalFrames());
                waveform.syncFramesPerBar(total);
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
        if (fxPanelOpen) {
            setFxPanelVisible(false);
        }
        boolean wasPaused = (engine.state == RecordingEngine.State.PAUSED);
        boolean isEditOrRecovery = (editSourceId != -1 || engine.dataBytes > 0);
        try {
            if (engine.hasSession() && engine.dataBytes > 0 && waveform != null) {
                long totalFrames = engine.totalFrames();
                long curFrames = engine.cursorFrames();
                waveform.syncFramesPerBar(totalFrames);
                if (replaceModeArmed && curFrames < totalFrames - 480) {
                    // In-place Replace from playhead: snapshot for Undo, keep
                    // the entire take forward of curFrames intact (do NOT
                    // call waveform.truncateToPlayhead()), and smoothly morph
                    // the waveform bars as new audio is recorded!
                    replacePassStartFrame = curFrames;
                    replaceTargetEndFrame = totalFrames;
                    if (activeUndoJournal().recordSnapshot(engine.rawFile)) {
                        waveform.recordUndoPeaks();
                    }
                    dspBakedToRaw = false;
                    dspSubRangeBaked = false;
                    lastEditWasSubRangeBake = false;
                } else {
                    if (replaceModeArmed) {
                        // Already at end of take: deactivate Replace and append
                        replaceModeArmed = false;
                        waveform.setSelectionLocked(false);
                    }
                    // Appending new dry audio invalidates the "whole take is
                    // baked" flag and any pending sub-range rollback.
                    dspBakedToRaw = false;
                    dspSubRangeBaked = false;
                    lastEditWasSubRangeBake = false;
                    replacePassStartFrame = -1L;
                    replaceTargetEndFrame = -1L;
                }
            } else {
                replacePassStartFrame = -1L;
                replaceTargetEndFrame = -1L;
                if (waveform != null) {
                    waveform.syncFramesPerBar(0L);
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
        lastPushedCursorFrame = engine.cursorFrames();
        handler.removeCallbacks(tick);
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
        if (replacingSelectionActive) {
            replaceModeArmed = false;
            if (waveform != null) waveform.setSelectionLocked(false);
            finishSelectAndReplaceIfNeeded();
        } else {
            long total = Math.max(1L, engine.totalFrames());
            long target = (pausedAtFrame >= 0 && pausedAtFrame <= total)
                    ? pausedAtFrame : total;
            // If the user was in Replace mode and reached the end of the take,
            // Replace auto-deactivates; if they paused mid-take before the end,
            // keep Replace active so the user can disable it manually after pausing!
            if (replaceModeArmed && target >= total - 480) {
                replaceModeArmed = false;
            }
            if (waveform != null) {
                waveform.setSelectionLocked(replaceModeArmed);
            }
            engine.seekCursorFrame(target);
            if (waveform != null) {
                // Do NOT call reloadSelectionPeaksFromWav here! The in-memory
                // bars were already updated frame-accurately during capture;
                // reloading from disk re-buckets the whole WAV and changes the
                // shape of the previous waveform on Pause.
                waveform.setPlayheadFraction(target / (float) total);
            }
            replacePassStartFrame = -1L;
            replaceTargetEndFrame = -1L;
            if (!replaceModeArmed) {
                RawFloatPlayer.warmUpSpeaker(engine.channels);
            }
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
        if (inSelectAndReplaceEditor) {
            closeSelectAndReplaceSubEditor(true);
        }
        // A Stop tap while a Select & Replace take is paused must splice the
        // saved tail back before dispatching, or the save ships a truncated take.
        if (replacingSelectionActive
                && engine.state != RecordingEngine.State.RECORDING) {
            finishSelectAndReplaceIfNeeded();
        }
        // Ensure any enabled-but-unbaked FX chain is saved to SharedPreferences
        // and flagged on the engine so ConversionJobs.dispatchSave() bakes it on
        // the background worker thread (after the user answers any Edit
        // Replace-vs-New prompt) without freezing the UI thread!
        if (dspConfig != null) {
            dspConfig.syncLegacyFieldsFromSlots();
            dspConfig.save(requireContext());
        }
        syncFxBakeRequired();
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
        if (saving || engine.isSaving() || !engine.hasSession()) return;
        // Only now that the user actually chose Replace/Save-as-new is it safe
        // to roll back the pre-sub-bake snapshot; doing it before the modal
        // destroyed the edit when the sheet was dismissed.
        if (lastEditWasSubRangeBake && dspConfig != null
                && dspConfig.isActive()) {
            File raw = engine.rawFile;
            EditEngine.UndoJournal uj = activeUndoJournal();
            if (raw != null && uj != null && uj.canUndo() && uj.undo(raw)) {
                if (waveform != null) waveform.popUndoPeaks();
                engine.abortStreaming();
                engine.dataBytes = RecordingEngine.payloadBytesOf(raw);
                dspBakedToRaw = false;
                dspSubRangeBaked = false;
            }
            lastEditWasSubRangeBake = false;
            syncFxBakeRequired();
        }
        saving = true;
        engine.setSaving(true);
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
            completionHandler.post(() -> {
                try {
                    saving = false;
                    engine.setSaving(false);
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
        if (saving || engine.isSaving()) return;
        saving = true;
        engine.setSaving(true);
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
            completionHandler.post(() -> {
                saving = false;
                engine.setSaving(false);
                RecordingService.stopService(appCtx);
                if (!isAdded()) return;
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
        if (inSelectAndReplaceEditor) {
            // The notification path already exited/spliced or deleted the
            // engine session; only the in-memory editor mode is left to clear.
            inSelectAndReplaceEditor = false;
            replaceModeArmed = false;
            if (waveform != null) {
                waveform.setSelectionLocked(false);
                waveform.exitSubEditorMode(false);
            }
        }
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
                    // If replacing and we reached the end of the original take,
                    // keep recording seamlessly (appending) and auto-deactivate
                    // the Replace button to indicate we are now appending!
                    if (replaceModeArmed
                            && ((replaceTargetEndFrame > 0 && cur >= replaceTargetEndFrame)
                            || cur >= total)) {
                        replaceModeArmed = false;
                        replaceTargetEndFrame = -1L;
                        if (waveform != null) {
                            waveform.setSelectionLocked(false);
                        }
                        paintEditorStrip();
                        paintFxPanelControls();
                    }
                    // Only advance/draw waveform bars once the mic has actually
                    // delivered audio frames (prevents blank waveform during mic warm-up).
                    if (cur != lastPushedCursorFrame && cur > 0) {
                        lastPushedCursorFrame = cur;
                        waveform.updateLiveFromFrames(lvl, cur, replaceTargetEndFrame);
                    }
                    timerText.setText(formatTimer(engine.cursorMs()));
                } else if (studioPreviewPlayer != null) {
                    long posMs = studioPreviewPlayer.positionMs();
                    timerText.setText(formatTimer(posMs));
                    long total = Math.max(1L, engine.totalFrames());
                    waveform.setPlayheadFraction(
                            Math.min(1f, studioPreviewPlayer.positionFrames()
                                    / (float) total));
                } else if (engine.hasSession()) {
                    long shownMs = (engine.cursorFrames() > 0 || userScrolledWhilePaused)
                            ? engine.cursorMs() : engine.durationMs();
                    timerText.setText(formatTimer(shownMs));
                } else {
                    timerText.setText(formatTimer(0));
                }
                dbMeter.setLevel(lvl);
                if (zoomValue != null && waveform != null && waveform.historyCount() > 0) {
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
                    long total = engine.totalFrames();
                    waveform.syncFramesPerBar(total);
                    engine.seekCursorFrame(total);
                    waveform.setPlayheadFraction(1f);
                    refreshZoomValue();
                    paintEditorStrip();
                }
            });
        } catch (Exception ignored) {}
    }

    /**
     * Chooses the preview chain. FX Preview asks for the live rack; the main
     * Play/Pause FAB asks for a dry pass so it always auditions the audio in
     * its current (already-applied) state. Once a whole take is baked the raw
     * samples are already wet, so even a live request must not process again;
     * a fresh selection after a sub-range bake is the one exception.
     */
    static DspChain.Config previewConfigFor(boolean withLiveFx, boolean baked,
                                            boolean subBaked, boolean hasSelection,
                                            DspChain.Config cfg) {
        boolean applyLive = withLiveFx
                && (!baked || (subBaked && hasSelection));
        return applyLive && cfg != null ? cfg : new DspChain.Config();
    }

    private void toggleStudioPreview(boolean withLiveFx) {
        if (!isAdded()) return;
        if (studioPreviewPlayer != null) {
            boolean sameMode = previewPlaysLiveFx == withLiveFx;
            stopStudioPreview();
            paintEditorStrip();
            if (sameMode) return;
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
            DspChain.Config activeCfg = previewConfigFor(withLiveFx,
                    dspBakedToRaw, dspSubRangeBaked,
                    waveform.hasSelection(), dspConfig);
            livePreviewDsp = new DspChain(info.channels, activeCfg);
            final RawFloatPlayer[] slot = new RawFloatPlayer[1];
            slot[0] = new RawFloatPlayer(raw, completed -> handler.post(() -> {
                if (studioPreviewPlayer == slot[0]) {
                    studioPreviewPlayer = null;
                    previewPlaysLiveFx = false;
                    livePreviewDsp = null;
                    try {
                        slot[0].stop();
                    } catch (Exception ignored) {}
                    if (engine.hasSession() && engine.state != RecordingEngine.State.RECORDING) {
                        long totalF = Math.max(1L, engine.totalFrames());
                        float endFrac = fxPanelOpen
                                ? fxApplyTargetEnd
                                : (waveform != null
                                        ? waveform.selectionEndFraction() : 1f);
                        boolean hasRange = fxPanelOpen
                                ? fxApplyTargetHasSelection
                                : (waveform != null && waveform.hasSelection());
                        long endF = completed
                                ? (hasRange
                                        ? Math.round(endFrac * totalF)
                                        : totalF)
                                : slot[0].positionFrames();
                        userScrolledWhilePaused = (endF < totalF - 480);
                        engine.seekCursorFrame(endF);
                        if (waveform != null) {
                            waveform.setPlayheadFraction(endF / (float) totalF);
                        }
                        if (timerText != null) {
                            timerText.setText(formatTimer(engine.cursorMs()));
                        }
                    }
                    paintEditorStrip();
                    paintFxPanelControls();
                }
            }));
            RawFloatPlayer player = slot[0];
            player.setDspChain(livePreviewDsp);
            studioPreviewPlayer = player;
            previewPlaysLiveFx = withLiveFx;
            // In effects mode the audition follows the frozen apply target,
            // not whatever happens to be selected right now.
            boolean rangeSel = fxPanelOpen
                    ? fxApplyTargetHasSelection
                    : (waveform != null && waveform.hasSelection());
            float rangeStart = fxPanelOpen
                    ? fxApplyTargetStart
                    : (waveform != null ? waveform.selectionStartFraction() : 0f);
            float rangeEnd = fxPanelOpen
                    ? fxApplyTargetEnd
                    : (waveform != null ? waveform.selectionEndFraction() : 1f);
            if (rangeSel) {
                long start = Math.round(rangeStart * totalFrames);
                long end = Math.round(rangeEnd * totalFrames);
                if (end > start) {
                    player.playRange(start, end);
                } else {
                    player.playFrom(0);
                }
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
        } catch (Exception e) {
            stopStudioPreview();
        }
        paintEditorStrip();
    }

    private void stopStudioPreview() {
        RawFloatPlayer player = studioPreviewPlayer;
        studioPreviewPlayer = null;
        previewPlaysLiveFx = false;
        livePreviewDsp = null;
        if (player != null) {
            long lastFrame = player.positionFrames();
            try {
                player.stop();
            } catch (Exception ignored) {}
            if (engine.hasSession() && engine.state != RecordingEngine.State.RECORDING) {
                long totalF = Math.max(1L, engine.totalFrames());
                userScrolledWhilePaused = (lastFrame < totalF - 480);
                engine.seekCursorFrame(lastFrame);
                if (waveform != null) {
                    waveform.setPlayheadFraction(lastFrame / (float) totalF);
                }
                if (timerText != null) {
                    timerText.setText(formatTimer(engine.cursorMs()));
                }
            }
        }
        // NOTE: Do NOT set btnPreviewPlay.setText("▶ Preview") or reset its
        // background here! btnPreviewPlay is the "Replace" toggle button and
        // is styled exclusively by paintEditorStrip() based on replaceModeArmed.
        if (btnPreviewPlay != null) {
            btnPreviewPlay.setText("Replace");
            btnPreviewPlay.setBackgroundResource(replaceModeArmed
                    ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            int col = replaceModeArmed ? 0xFFFFFFFF : 0xFF9AA3AF;
            btnPreviewPlay.setTextColor(col);
            setChipIconAndTint(btnPreviewPlay, R.drawable.ic_edit_replace, col);
        }
    }

    private void paintEditorStrip() {
        if (editorStrip == null || !isAdded()) return;
        // Keep the audio editing rack always visible in Studio; only enable its
        // buttons when the recording is in the PAUSED state with audio.
        if (editorStrip.getVisibility() != View.VISIBLE) {
            editorStrip.setVisibility(View.VISIBLE);
            editorStrip.setAlpha(1f);
            editorStrip.setTranslationY(0f);
        }
        boolean canEdit = isSessionEditable();
        if (!canEdit && studioPreviewPlayer != null) {
            stopStudioPreview();
        }

        boolean hasSel = canEdit && !replaceModeArmed
                && waveform != null && waveform.hasSelection();
        // Effects mode is a separate mode: the rack owns the selection and the
        // transport, so every other editing action is locked until it closes.
        boolean fxMode = canEdit && fxPanelOpen;
        boolean othersEnabled = canEdit && !replaceModeArmed && !fxMode;
        long durMs = Math.max(1L, engine.durationMs());

        // 1) Replace Mode Toggle Button (btnPreviewPlay):
        //    Enabled in PAUSED state; stays visually lit while actively
        //    recording in Replace mode until it reaches the end of the take
        //    (where it auto-deactivates) or the user pauses and disables it.
        if (btnPreviewPlay != null) {
            boolean showReplaceActive = replaceModeArmed
                    && (canEdit || engine.state == RecordingEngine.State.RECORDING);
            btnPreviewPlay.setText("Replace");
            btnPreviewPlay.setEnabled(canEdit);
            btnPreviewPlay.setClickable(canEdit);
            btnPreviewPlay.setAlpha(canEdit ? 1f : (showReplaceActive ? 0.75f : 0.35f));
            btnPreviewPlay.setBackgroundResource(showReplaceActive
                    ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            int col = showReplaceActive ? 0xFFFFFFFF
                    : (canEdit ? 0xFF9AA3AF : 0xFF5D6672);
            btnPreviewPlay.setTextColor(col);
            setChipIconAndTint(btnPreviewPlay, R.drawable.ic_edit_replace, col);
        }

        // 2) Select Button: stays "Select" and turns red ONLY when Select is active;
        //    disabled while Replace mode is on.
        if (btnSelectRange != null) {
            btnSelectRange.setText("Select");
            btnSelectRange.setEnabled(othersEnabled);
            btnSelectRange.setClickable(othersEnabled);
            btnSelectRange.setAlpha(othersEnabled ? 1f : 0.35f);
            btnSelectRange.setBackgroundResource(hasSel
                    ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            int col = !othersEnabled ? 0xFF5D6672
                    : (hasSel ? 0xFFFFFFFF : 0xFF9AA3AF);
            btnSelectRange.setTextColor(col);
            setChipIconAndTint(btnSelectRange, R.drawable.ic_edit_select, col);
        }

        // 3) Select & Replace Button: never turns red just because Select is pressed;
        //    disabled while Replace mode is on, hidden when inside sub-editor.
        if (btnReplaceSeg != null) {
            btnReplaceSeg.setVisibility(inSelectAndReplaceEditor ? View.GONE : View.VISIBLE);
            btnReplaceSeg.setText("Select & Replace");
            btnReplaceSeg.setEnabled(othersEnabled);
            btnReplaceSeg.setClickable(othersEnabled);
            btnReplaceSeg.setAlpha(othersEnabled ? 1f : 0.35f);
            btnReplaceSeg.setBackgroundResource(R.drawable.bg_chip);
            int col = !othersEnabled ? 0xFF5D6672
                    : (hasSel ? 0xFFF2F4F7 : 0xFF9AA3AF);
            btnReplaceSeg.setTextColor(col);
            setChipIconAndTint(btnReplaceSeg, R.drawable.ic_edit_select_replace, col);
        }

        // 4) Trim Button: disabled while Replace mode is on.
        if (btnTrimCrop != null) {
            btnTrimCrop.setText("Trim");
            btnTrimCrop.setEnabled(othersEnabled);
            btnTrimCrop.setClickable(othersEnabled);
            btnTrimCrop.setAlpha(!othersEnabled ? 0.35f : (hasSel ? 1f : 0.75f));
            btnTrimCrop.setBackgroundResource(R.drawable.bg_chip);
            int col = !othersEnabled ? 0xFF5D6672
                    : (hasSel ? 0xFFF2F4F7 : 0xFF9AA3AF);
            btnTrimCrop.setTextColor(col);
            setChipIconAndTint(btnTrimCrop, R.drawable.ic_edit_trim, col);
        }

        // 5) FX Button: disabled while Replace mode is on, and the only
        //    editing chip that stays live while effects mode is open (it is
        //    how the mode is closed).
        if (btnDspFx != null) {
            // The chip is only highlighted while the rack is actually open.
            // A rack with effects in it but a closed panel is a pending
            // edit, not an active mode, so it must not look selected.
            boolean fxAvailable = canEdit && !replaceModeArmed;
            boolean fxActive = fxAvailable && fxPanelOpen;
            boolean fxPending = dspConfig != null && dspConfig.isActive();
            btnDspFx.setText(fxPending ? dspConfig.badgeSummary() : "FX");
            btnDspFx.setEnabled(fxAvailable);
            btnDspFx.setClickable(fxAvailable);
            // Dim like Trim when a region still has to be selected.
            btnDspFx.setAlpha(!fxAvailable ? 0.35f
                    : (fxPanelOpen || hasSel ? 1f : 0.75f));
            btnDspFx.setBackgroundResource(fxActive
                    ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            int col = !fxAvailable ? 0xFF5D6672
                    : (fxActive ? 0xFFFFFFFF : 0xFF9AA3AF);
            btnDspFx.setTextColor(col);
            setChipIconAndTint(btnDspFx, R.drawable.ic_edit_fx, col);
        }

        // 6) Undo Button: disabled while Replace mode is on.
        if (btnEditUndo != null) {
            EditEngine.UndoJournal uj = activeUndoJournal();
            boolean canUndo = othersEnabled && uj != null && uj.canUndo();
            btnEditUndo.setEnabled(canUndo);
            btnEditUndo.setClickable(canUndo);
            btnEditUndo.setAlpha(canUndo ? 1f : 0.35f);
            int col = canUndo ? 0xFFF2F4F7 : 0xFF5D6672;
            btnEditUndo.setTextColor(col);
            setChipIconAndTint(btnEditUndo, R.drawable.ic_edit_undo, col);
        }

        if (hintText != null && canEdit) {
            if (fxPanelOpen) {
                hintText.setText("FX mode • Play auditions the effect-applied "
                        + "audio • Stop applies it");
            } else if (inSelectAndReplaceEditor) {
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
    private void setChipIconAndTint(TextView btn, int iconRes, int color) {
        if (btn == null) return;
        btn.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
        TextViewCompat.setCompoundDrawableTintList(btn, ColorStateList.valueOf(color));
    }

    private void syncStudioFabIcon() {
        if (!isAdded()) return;
        try {
            MainActivity act = activity();
            if (engine.state == RecordingEngine.State.RECORDING) {
                act.syncFabWithEngine();
                return;
            }
            if (isSessionEditable()) {
                act.setStopVisible(true);
                if (studioPreviewPlayer != null) {
                    act.morphFabIcon(R.drawable.ic_pause);
                    return;
                }
                long totalFrames = engine.totalFrames();
                long curFrames = engine.cursorFrames();
                boolean atEnd = (curFrames >= totalFrames - 1200);
                boolean hasSel = waveform != null && waveform.hasSelection();
                // In effects mode the FAB always plays: it never starts a new
                // recording, so the mic icon would be a lie.
                boolean willRecord = !fxPanelOpen
                        && (replaceModeArmed || (atEnd && !hasSel));
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
        boolean undoRecorded = false;
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
            EditEngine.UndoJournal uj = activeUndoJournal();
            // Only mirror the edit into the waveform peak stack when the audio
            // snapshot will actually land (takes over 30 MB are never
            // snapshotted), otherwise the two undo stacks desynchronise. The
            // snapshot itself is still taken by crop/delete AFTER they
            // validate the range, so a rejected edit leaves no orphan.
            undoRecorded = uj == null || uj.wouldRecord(raw);
            if (undoRecorded) waveform.recordUndoPeaks();
            if (isTrim) {
                EditEngine.cropToSelection(raw, start, end, uj);
                waveform.cropToSelectionBuckets();
            } else {
                EditEngine.deleteSelection(raw, start, end, uj);
                waveform.deleteSelectionBuckets();
            }
            dspBakedToRaw = false;
            dspSubRangeBaked = false;
            lastEditWasSubRangeBake = false;
            userScrolledWhilePaused = false;
            engine.abortStreaming();
            engine.dataBytes = RecordingEngine.payloadBytesOf(raw);
            long newTotal = engine.totalFrames();
            waveform.syncFramesPerBar(newTotal);
            long nextCursor = isTrim ? newTotal : Math.min(start, newTotal);
            engine.seekCursorFrame(nextCursor);
            if (newTotal > 0) {
                waveform.setPlayheadFraction(nextCursor / (float) newTotal);
            }
            refreshZoomValue();
            timerText.setText(formatTimer(engine.cursorMs()));
            Toast.makeText(requireContext(),
                    isTrim ? "Trimmed to selection" : "Selection removed",
                    Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException iae) {
            if (undoRecorded) waveform.popUndoPeaks();
            Toast.makeText(requireContext(), iae.getMessage(),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            if (undoRecorded) waveform.popUndoPeaks();
            Toast.makeText(requireContext(), "Edit failed",
                    Toast.LENGTH_SHORT).show();
        }
        paint();
    }

    private void performStudioUndo() {
        if (!isAdded()) return;
        stopStudioPreview();
        File raw = engine.rawFile;
        EditEngine.UndoJournal uj = activeUndoJournal();
        if (raw == null || uj == null || !uj.canUndo()) return;
        try {
            if (uj.undo(raw)) {
                dspBakedToRaw = false;
                dspSubRangeBaked = false;
                lastEditWasSubRangeBake = false;
                userScrolledWhilePaused = false;
                engine.abortStreaming();
                engine.dataBytes = RecordingEngine.payloadBytesOf(raw);
                long total = engine.totalFrames();
                engine.seekCursorFrame(total);
                if (waveform != null) {
                    if (!waveform.popUndoPeaks()) {
                        waveform.loadFromFloatWavTwentyHz(raw, () -> {
                            waveform.syncFramesPerBar(engine.totalFrames());
                            refreshZoomValue();
                        });
                    } else {
                        waveform.syncFramesPerBar(total);
                        waveform.setPlayheadFraction(1f);
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
        if (fxPanelOpen) {
            setFxPanelVisible(false);
            return;
        }
        if (!ensurePausedForEditing()) return;
        // Effects mode is a separate editing mode: it needs a region to
        // work on, so entering without one enables Select and asks for a
        // drag, exactly like Trim does.
        if (!ensureSelectionOrAutoEnable("FX")) return;
        setFxPanelVisible(true);
    }

    private void setFxPanelVisible(boolean open) {
        if (fxPanel == null || telePanel == null) return;
        if (open && (waveform == null || !waveform.hasSelection())) {
            // Defensive: the mode only exists for a selected region.
            return;
        }
        fxPanelOpen = open;
        if (open) {
            // If no effects are currently enabled on initial default state,
            // activate the default Voice Polish rack so Preview & Apply work
            // immediately out of the box without overwriting an intentional
            // CLEAR_ALL ("Flat / No Effects") or custom unchecked rack!
            if (dspConfig != null && !dspConfig.isActive()
                    && (DspChain.Preset.VOICE_POLISH.id.equals(dspConfig.activePresetId)
                            || (dspConfig.slots.isEmpty()
                                    && !DspChain.Preset.CLEAR_ALL.id.equals(
                                            dspConfig.activePresetId)))) {
                dspBakedToRaw = false;
                dspSubRangeBaked = false;
                dspConfig.applyPreset(DspChain.Preset.VOICE_POLISH);
                dspConfig.save(requireContext());
            }
            teleWasVisibleBeforeFx =
                    telePanel.getVisibility() == View.VISIBLE;
            // Freeze the apply target: the selection at the moment the rack
            // opens is the region Stop will commit to.
            fxApplyTargetHasSelection = true;
            fxApplyTargetStart = waveform.selectionStartFraction();
            fxApplyTargetEnd = waveform.selectionEndFraction();
            setTelePaused(true);
            telePanel.setVisibility(View.GONE);
            fxPanel.setVisibility(View.VISIBLE);
            rebuildFxRackList();
            paintFxPanelControls();
            refreshFxPreviewPeaks();
        } else {
            // Cancel any in-flight render and put the dry waveform back: the
            // effect-applied bars only exist inside effects mode.
            fxPreviewGeneration++;
            handler.removeCallbacks(fxPreviewRefresh);
            fxPanel.setVisibility(View.GONE);
            telePanel.setVisibility(teleWasVisibleBeforeFx
                    ? View.VISIBLE : View.GONE);
            setTeleActive(teleWasVisibleBeforeFx);
            if (waveform != null) waveform.clearFxRegionPeaks();
        }
        paintEditorStrip();
    }

    /**
     * Schedules a re-render of the effect-applied waveform for the frozen
     * apply target.
     *
     * <p>Rapid knob turns coalesce into one render and every request bumps a
     * generation token, so a slow render built from an older rack can never
     * paint over a newer one. The render runs off the UI thread and only
     * covers the target region, which keeps it responsive on long takes.
     */
    private void refreshFxPreviewPeaks() {
        handler.removeCallbacks(fxPreviewRefresh);
        if (!isAdded() || waveform == null) return;
        if (!fxPanelOpen) {
            waveform.clearFxRegionPeaks();
            return;
        }
        handler.postDelayed(fxPreviewRefresh, 70L);
    }

    private final Runnable fxPreviewRefresh = () -> {
        if (!isAdded() || waveform == null || !fxPanelOpen) return;
        if (dspConfig == null || !dspConfig.isActive()) {
            fxPreviewGeneration++;
            waveform.clearFxRegionPeaks();
            return;
        }
        final int buckets = waveform.getHistoryCount();
        if (buckets <= 0) {
            waveform.clearFxRegionPeaks();
            return;
        }
        final File raw = engine.rawFile;
        if (raw == null || !raw.exists()) {
            waveform.clearFxRegionPeaks();
            return;
        }
        final int gen = ++fxPreviewGeneration;
        final float startFrac = fxApplyTargetStart;
        final float endFrac = fxApplyTargetEnd;
        final int channels = Math.max(1, engine.channels);
        // Snapshot the chain on the UI thread: the worker gets an immutable
        // rack while the user keeps turning knobs.
        final DspChain chain = new DspChain(channels, dspConfig);
        if (!chain.isActive()) {
            waveform.clearFxRegionPeaks();
            return;
        }
        new Thread(() -> {
            final float[] peaks = FxPreviewPeaks.renderRegion(raw, chain,
                    buckets, DspChain.SAMPLE_RATE, startFrac, endFrac);
            handler.post(() -> {
                if (!isAdded() || waveform == null || !fxPanelOpen) return;
                if (gen != fxPreviewGeneration) return;
                if (peaks == null) {
                    waveform.clearFxRegionPeaks();
                } else {
                    waveform.setFxRegionPeaks(peaks, startFrac, endFrac);
                }
            });
        }, "takeone-fx-region").start();
    };

    private void paintFxPanelControls() {
        if (fxPanel == null || !isAdded()) return;
        if (fxTitle != null) {
            int active = 0;
            if (dspConfig != null) {
                for (DspChain.EffectSlot slot : dspConfig.slots) {
                    if (slot.enabled) active++;
                }
            }
            fxTitle.setText(active > 0
                    ? "PRO FX RACK · " + active + " ON"
                    : "PRO FX RACK");
            fxTitle.setTextColor(active > 0 ? 0xFFFF6B6B : 0xFF8A93A0);
        }
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
        // The panel no longer carries its own Preview/Apply buttons: in
        // effects mode the transport does both (Play auditions the
        // effect-applied audio, Stop commits it to the selection). The hint
        // line spells that out so the change is not surprising.
        boolean previewingFx = studioPreviewPlayer != null
                && previewPlaysLiveFx;
        boolean anyEnabled = dspConfig != null && dspConfig.isActive();
        boolean blocked = replaceModeArmed || !isSessionEditable();
        if (fxHint != null) {
            long durMs = Math.max(1L, engine.durationMs());
            String target = formatTimer(
                    Math.round(fxApplyTargetStart * durMs))
                    + " – " + formatTimer(
                    Math.round(fxApplyTargetEnd * durMs));
            if (blocked) {
                fxHint.setText("Disable Replace mode to use the FX rack");
            } else if (previewingFx) {
                fxHint.setText("Playing the effect-applied audio · "
                        + "Stop applies to " + target);
            } else if (anyEnabled) {
                String tail = rackHasOfflineEffects()
                        ? "\nLength-changing effects apply on Stop" : "";
                fxHint.setText("Play auditions the effect-applied audio · "
                        + "Stop applies to " + target + tail);
            } else {
                fxHint.setText("Add an effect below — the waveform and "
                        + "playback update live");
            }
        }
        // Keep the save path honest: while a chain is enabled but not baked,
        // the incremental part must never be adopted.
        syncFxBakeRequired();
    }

    /**
     * Mirrors the "enabled-but-unbaked FX chain" state into the engine so the
     * save path can never adopt an incremental part that skipped the chain.
     * Called from both {@link #paint()} (which runs after Undo/Trim/Replace
     * reset {@link #dspBakedToRaw}) and {@link #paintFxPanelControls()} so the
     * flag is never stale when the FX panel happens to be closed.
     */
    private void syncFxBakeRequired() {
        if (engine == null) return;
        engine.setFxBakeRequired(!dspBakedToRaw
                && dspConfig != null && dspConfig.isActive());
    }

    /** Small pill button used by the FX inspector's per-effect controls. */
    private TextView makeFxChip(Context ctx, String text, float density) {
        TextView chip = new TextView(ctx);
        chip.setText(text);
        chip.setTextSize(11.5f);
        chip.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setTextColor(0xFFF2F4F7);
        chip.setBackgroundResource(R.drawable.bg_btn_ghost);
        int padH = Math.round(12 * density);
        int padV = Math.round(7 * density);
        chip.setPadding(padH, padV, padH, padV);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Math.round(6 * density);
        chip.setLayoutParams(lp);
        PressAnim.applySoft(chip, 0.95f);
        return chip;
    }

    /** Per-effect accent used by the rack cards and their enable dot. */
    static int fxAccentColor(DspChain.EffectType type) {
        if (type == null) return 0xFFFF6B6B;
        switch (type) {
            case NOISE_SUPPRESSION:
            case ROOM_GATE:
                return 0xFF30D158;
            case DE_ESSER:
            case WARMTH_BODY:
                return 0xFFFF9F0A;
            case COMPRESSOR:
                return 0xFF0A84FF;
            case LOW_CUT_HPF:
            case HIGH_PASS_FILTER:
                return 0xFF64D2FF;
            case VOCAL_PRESENCE:
                return 0xFFFFD60A;
            case BRICKWALL_LIMITER:
            case NOTCH_FILTER:
                return 0xFFFF6B6B;
            case PARAMETRIC_EQ6:
                return 0xFFBF5AF2;
            case GRAPHIC_EQ:
                return 0xFF5E5CE6;
            case FILTER_CURVE_EQ:
                return 0xFFDA8FFF;
            case BASS_TREBLE:
                return 0xFFFFB340;
            case LOW_PASS_FILTER:
                return 0xFF6AC4FF;
            case SHELF_FILTER:
                return 0xFFA0D96A;
            case CLASSIC_FILTERS:
                return 0xFFB0B8C4;
            default:
                return fxCategoryAccent(DspChain.categoryOf(type));
        }
    }

    /**
     * Accent color for a whole catalog category, so the Stage 4-7 effects
     * read as families instead of one undifferentiated red.
     */
    static int fxCategoryAccent(FxCategory category) {
        if (category == null) return 0xFFFF6B6B;
        switch (category) {
            case EQUALIZER:
                return 0xFF5E5CE6;
            case DYNAMICS:
                return 0xFF0A84FF;
            case RESTORATION:
                return 0xFF30D158;
            case SPATIAL:
                return 0xFF64D2FF;
            case TIME:
                return 0xFFFF9F0A;
            case CREATIVE:
                return 0xFFBF5AF2;
            case TOOLS:
                return 0xFFA0D96A;
            case ANALYZER:
                return 0xFFB0B8C4;
            default:
                return 0xFFFF6B6B;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /** Rounded, accent-bordered rack card; keeps the system ripple. */
    private static android.graphics.drawable.Drawable fxCardBackground(
            boolean enabled, int accent, float density) {
        android.graphics.drawable.GradientDrawable shape =
                new android.graphics.drawable.GradientDrawable();
        shape.setCornerRadius(14f * density);
        shape.setColor(enabled ? 0xFF15191F : 0xFF0F1216);
        shape.setStroke(Math.max(1, Math.round(density)),
                enabled ? withAlpha(accent, 0x70) : 0x1AFFFFFF);
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x18FFFFFF),
                shape, null);
    }

    /** Solid accent dot when enabled, hollow ring when bypassed. */
    private static android.graphics.drawable.Drawable fxToggleBackground(
            boolean enabled, int accent, float density) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        if (enabled) {
            g.setColor(accent);
        } else {
            g.setColor(0x00000000);
            g.setStroke(Math.max(1, Math.round(1.4f * density)),
                    0xFF3E4652);
        }
        return g;
    }

    /**
     * True for the parameter that a slot's primary TeleValue already controls
     * ({@code amt} for percentage stages, {@code cutoff} for the HPF). Every
     * other {@link FxParameter} the processor exposes is rendered as an extra
     * knob in the expanded drawer, so multi-parameter effects get a full
     * inspector with no bespoke UI.
     */
    private static boolean isPrimaryFxParam(DspChain.EffectType type,
                                            String paramId) {
        if (type != null && !type.isLegacyStage()) {
            return paramId != null
                    && paramId.equals(DspChain.primaryParamId(type));
        }
        return "amt".equals(paramId)
                || "cutoff".equals(paramId)
                || "drive".equals(paramId)
                || "warmth".equals(paramId)
                || "presence".equals(paramId);
    }

    static String formatSlotSubtitle(DspChain.EffectSlot slot) {
        return formatSlotSubtitle(slot, null);
    }

    static String formatSlotSubtitle(DspChain.EffectSlot slot,
                                     DspChain.Config cfg) {
        if (slot == null || slot.type == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(slot.enabled ? "Enabled • " : "Bypassed • ");
        AudioProcessor meta =
                FxRackChain.getPrototypeProcessor(slot.type.id);
        FxParameter primary = (meta != null && !slot.type.isLegacyStage())
                ? meta.getParam(DspChain.primaryParamId(slot.type)) : null;
        if (primary != null) {
            sb.append(primary.name).append(": ")
                    .append(primary.formatRaw(slot.value));
        } else {
            sb.append(slot.type.paramLabel).append(": ")
                    .append(slot.value).append(slot.type.unit);
        }
        if (supportsNoiseProfile(slot.type) && slot.noiseProfile != null) {
            sb.append(" • Profile");
        }
        boolean isTone = slot.type == DspChain.EffectType.WARMTH_BODY
                || slot.type == DspChain.EffectType.VOCAL_PRESENCE;
        boolean mixActive = !isTone || cfg == null
                || !cfg.hasBothToneSlotsEnabled();
        int mixPct = Math.round(slot.wetDry * 100f);
        if (mixActive && mixPct < 100) {
            sb.append(" • Mix ").append(mixPct).append("%");
        }
        boolean curveEditor = slot.type == DspChain.EffectType.PARAMETRIC_EQ6
                || slot.type == DspChain.EffectType.GRAPHIC_EQ
                || slot.type == DspChain.EffectType.FILTER_CURVE_EQ;
        if (!slot.extraParams.isEmpty() && !curveEditor) {
            if (meta != null) {
                for (FxParameter param : meta.getParameters()) {
                    if (isPrimaryFxParam(slot.type, param.id)
                            || param.type == FxParameter.Type.ACTION) {
                        continue;
                    }
                    Integer raw = slot.extraParams.get(param.id);
                    if (raw != null && raw != param.defaultRaw) {
                        sb.append(" • ").append(param.name).append(": ")
                                .append(param.formatRaw(raw));
                    }
                }
            }
        }
        sb.append(slot.expanded ? "  ▴" : "  ▾");
        return sb.toString();
    }

    /**
     * True for the effects that learn a noise profile from a selected range.
     */
    private static boolean supportsNoiseProfile(DspChain.EffectType type) {
        return type == DspChain.EffectType.NOISE_SUPPRESSION
                || type == DspChain.EffectType.SPECTRAL_NOISE_REDUCTION;
    }

    /**
     * Learns a spectral noise profile from the current waveform selection and
     * stores it on the Noise Suppression Pro slot. The profile is persisted in
     * the rack config, so Apply FX, live preview and later sessions all use the
     * same learned floor.
     */
    private void learnNoiseProfile(DspChain.EffectSlot slot,
                                   Runnable onSlotMutated) {
        if (slot == null || !ensurePausedForEditing()) return;
        if (waveform == null || !waveform.hasSelection()) {
            Toast.makeText(requireContext(),
                    "Select a noise-only region on the waveform first",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // In effects mode the selection starts as the apply target. Learning
        // from that is useless (it is the speech being treated), so the first
        // tap explains the two-step flow instead of learning garbage.
        boolean stillOnTarget = fxPanelOpen
                && Math.abs(waveform.selectionStartFraction()
                        - fxApplyTargetStart) < 0.0005f
                && Math.abs(waveform.selectionEndFraction()
                        - fxApplyTargetEnd) < 0.0005f;
        if (stillOnTarget) {
            Toast.makeText(requireContext(),
                    "Drag the [ ] handles over a noise-only part of the "
                            + "waveform, then tap Learn again",
                    Toast.LENGTH_LONG).show();
            return;
        }
        File rawFile = engine.rawFile;
        if (rawFile == null || !rawFile.exists()) {
            Toast.makeText(requireContext(), "No editable audio yet",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            FormatConverter.WavInfo info =
                    FormatConverter.readFloatWav(rawFile);
            long totalFrames = info.dataBytes / 4
                    / Math.max(1, info.channels);
            if (totalFrames <= 0) return;
            long start = Math.round(
                    waveform.selectionStartFraction() * totalFrames);
            long end = Math.round(
                    waveform.selectionEndFraction() * totalFrames);
            if (end <= start) {
                Toast.makeText(requireContext(),
                        "Select a valid noise-only region first",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            long maxFrames = 8L * Math.max(8000, info.sampleRate);
            if (end - start > maxFrames) end = start + maxFrames;
            float[] samples = EditEngine.readFloatSelection(
                    rawFile, start, end);

            // Feed the sample through every effect ABOVE this one, so a
            // profiler that sits later in the rack learns the audio it will
            // actually receive. This is what makes "applied on top of the
            // previous effect" true for learning as well as playback.
            int ch = Math.max(1, info.channels);
            DspChain prefix = new DspChain(ch, prefixConfigFor(dspConfig, slot));
            if (prefix.isActive()) {
                prefix.prepareOffline(rawFile, start, end,
                        info.sampleRate, ch);
                prefix.reset();
                int frames = samples.length / ch;
                int chunk = 2048;
                float[] block = new float[chunk * ch];
                for (int off = 0; off < frames; off += chunk) {
                    int n = Math.min(chunk, frames - off);
                    System.arraycopy(samples, off * ch, block, 0, n * ch);
                    prefix.processInPlace(block, n);
                    System.arraycopy(block, 0, samples, off * ch, n * ch);
                }
            }

            float[] profile = slot.type
                    == DspChain.EffectType.SPECTRAL_NOISE_REDUCTION
                    ? SpectralNoiseReductionProcessor.learnProfile(
                            samples, ch)
                    : NoiseSuppressionProcessor.learnProfile(
                            samples, ch);
            if (profile == null) {
                Toast.makeText(requireContext(),
                        "Select at least 0.1 s of steady noise",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            slot.noiseProfile = profile;
            if (slot.value <= 0) slot.value = slot.type.defaultVal;
            slot.enabled = true;
            // Restore the apply target so effects mode keeps showing the
            // region that Stop will commit; the learned profile is already
            // part of the live waveform and playback.
            if (fxPanelOpen) {
                waveform.selectRange(fxApplyTargetStart, fxApplyTargetEnd);
            }
            onSlotMutated.run();
            rebuildFxRackList();
            double seconds = (end - start)
                    / (double) Math.max(1, info.sampleRate);
            Toast.makeText(requireContext(), String.format(Locale.US,
                    "Noise profile learned from %.1f s — waveform and "
                            + "playback updated", seconds),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    "Could not analyze the selection",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** True when the rack contains a duration-changing effect. */
    private boolean rackHasOfflineEffects() {
        if (dspConfig == null) return false;
        for (DspChain.EffectSlot s : dspConfig.slots) {
            if (s == null || !s.enabled || s.type == null) continue;
            AudioProcessor p = FxRackChain.getPrototypeProcessor(s.type.id);
            if (p != null && p.isOfflineOnly()) return true;
        }
        return false;
    }

    /**
     * Snapshot of the enabled effects above {@code target} in rack order, so a
     * learning effect samples the signal exactly as it enters that effect.
     */
    static DspChain.Config prefixConfigFor(DspChain.Config rack,
                                          DspChain.EffectSlot target) {
        DspChain.Config prefix = new DspChain.Config();
        prefix.applyPreset(DspChain.Preset.CLEAR_ALL);
        prefix.activePresetId = DspChain.Preset.CUSTOM.id;
        if (rack == null || target == null) return prefix;
        for (DspChain.EffectSlot s : rack.slots) {
            if (s == target) break;
            if (s == null || s.type == null || !s.enabled) continue;
            DspChain.EffectSlot copy = new DspChain.EffectSlot(
                    s.type, true, s.value, false, s.instanceId);
            copy.wetDry = s.wetDry;
            copy.extraParams.putAll(s.extraParams);
            copy.noiseProfile = s.noiseProfile;
            prefix.slots.add(copy);
        }
        prefix.syncLegacyFieldsFromSlots();
        return prefix;
    }

    private void commitFxSlotEdit(DspChain.EffectSlot slot) {
        if (slot == null || !isAdded()) return;
        if (dspSubRangeBaked) lastEditWasSubRangeBake = true;
        dspBakedToRaw = false;
        dspSubRangeBaked = false;
        dspConfig.activePresetId = DspChain.Preset.CUSTOM.id;
        dspConfig.syncLegacyFieldsFromSlots();
        dspConfig.save(requireContext());
        if (livePreviewDsp != null) {
            livePreviewDsp.updateConfig(dspConfig);
        }
        // The effect-applied waveform overlay follows every rack edit.
        refreshFxPreviewPeaks();
        paintEditorStrip();
        paintFxPanelControls();
    }

    /**
     * Opens one effect in an expanded, self-contained inspector. The pill body
     * stays compact; this modal gives the same slot a large curve canvas, a
     * full-width primary control and every secondary parameter without the
     * inline card's "advanced" fold.
     */
    private void showEffectDetailModal(DspChain.EffectSlot slot) {
        if (slot == null || slot.type == null || !isAdded()) return;
        final Context ctx = requireContext();
        final float density = getResources().getDisplayMetrics().density;

        final android.app.Dialog dialog = new android.app.Dialog(ctx,
                R.style.TakeOneCenterDialog);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_modal);
        int pad = Math.round(16 * density);
        root.setPadding(pad, pad, pad, Math.round(12 * density));

        android.widget.LinearLayout header =
                new android.widget.LinearLayout(ctx);
        header.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView title = new TextView(ctx);
        title.setText(slot.type.title);
        title.setTextSize(17f);
        title.setTextColor(0xFFF2F4F7);
        title.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setPadding(0, 0, Math.round(8 * density), 0);
        header.addView(title, new android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView enableChip = makeFxChip(ctx, "", density);
        TextView closeChip = makeFxChip(ctx, "Close", density);
        header.addView(enableChip);
        header.addView(closeChip);
        root.addView(header);

        TextView desc = new TextView(ctx);
        desc.setText(slot.type.description);
        desc.setTextSize(11f);
        desc.setTextColor(0xFF8A93A0);
        desc.setLineSpacing(0f, 1.08f);
        android.widget.LinearLayout.LayoutParams descLp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = Math.round(6 * density);
        root.addView(desc, descLp);

        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
        scroll.setFillViewport(true);
        android.widget.LinearLayout body =
                new android.widget.LinearLayout(ctx);
        body.setOrientation(android.widget.LinearLayout.VERTICAL);
        scroll.addView(body, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        android.widget.LinearLayout.LayoutParams scrollLp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = Math.round(4 * density);
        root.addView(scroll, scrollLp);

        final java.util.List<Runnable> refreshers = new java.util.ArrayList<>();
        final Runnable onChanged = () -> {
            commitFxSlotEdit(slot);
            for (Runnable refresh : refreshers) refresh.run();
        };

        EqCurveView[] eqHolder = new EqCurveView[1];
        boolean hasEqView = slot.type == DspChain.EffectType.PARAMETRIC_EQ6
                || slot.type == DspChain.EffectType.GRAPHIC_EQ
                || slot.type == DspChain.EffectType.FILTER_CURVE_EQ;
        if (hasEqView) {
            EqCurveView eqView;
            if (slot.type == DspChain.EffectType.PARAMETRIC_EQ6) {
                eqView = new ParametricEqView(ctx, slot, onChanged);
            } else if (slot.type == DspChain.EffectType.GRAPHIC_EQ) {
                eqView = new GraphicEqView(ctx, slot, onChanged);
            } else {
                eqView = new FilterCurveEqView(ctx, slot, onChanged);
            }
            eqHolder[0] = eqView;
            android.widget.LinearLayout.LayoutParams eqLp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Math.round(210 * density));
            eqLp.topMargin = Math.round(10 * density);
            body.addView(eqView, eqLp);
        }

        TeleValue primary = new TeleValue(ctx);
        primary.setCaption(slot.type.paramLabel);
        if (!slot.type.isLegacyStage()) {
            AudioProcessor dialMeta =
                    FxRackChain.getPrototypeProcessor(slot.type.id);
            FxParameter dialParam = dialMeta == null ? null
                    : dialMeta.getParam(DspChain.primaryParamId(slot.type));
            if (dialParam != null) {
                primary.setValueScale(Math.max(1, dialParam.scaleDivisor));
                primary.setValueFormatter(dialParam::formatRaw);
            }
        }
        primary.setCompact(false);
        primary.configure(slot.type.minVal, slot.type.maxVal,
                slot.type.stepVal, slot.value, slot.type.unit);
        android.widget.LinearLayout.LayoutParams primaryLp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        primaryLp.topMargin = Math.round(10 * density);
        body.addView(primary, primaryLp);
        refreshers.add(() -> primary.setValue(slot.value, false));

        java.util.function.IntConsumer syncPrimaryLinks = (int val) -> {
            if (slot.type == DspChain.EffectType.NOTCH_FILTER) {
                Integer preset = slot.extraParams.get("preset");
                if (preset != null && ((preset == 1 && val != 50)
                        || (preset == 2 && val != 60))) {
                    slot.extraParams.put("preset", 0);
                }
            } else if (slot.type == DspChain.EffectType.BASS_TREBLE) {
                Integer link = slot.extraParams.get("link");
                if (link != null && link != 0) {
                    slot.extraParams.put("treble", val);
                }
            }
        };
        java.util.function.IntConsumer applyPrimary = (int value) -> {
            slot.value = value;
            syncPrimaryLinks.accept(value);
            boolean engages = slot.type.isLegacyStage()
                    ? (value > 0) : (value != slot.type.defaultVal);
            if (!slot.enabled && engages) slot.enabled = true;
            onChanged.run();
        };
        primary.setOnScrubListener(new TeleValue.OnScrubListener() {
            @Override
            public void onScrubValueChanged(int value) {
                applyPrimary.accept(value);
            }

            @Override
            public void onScrubFinished(int value) {
                applyPrimary.accept(value);
            }
        });
        primary.setOnValueChanged(applyPrimary::accept);

        if (supportsNoiseProfile(slot.type)) {
            TextView profileTv = new TextView(ctx);
            profileTv.setTextSize(11f);
            profileTv.setTextColor(0xFF8A93A0);
            android.widget.LinearLayout.LayoutParams profileLp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            profileLp.topMargin = Math.round(8 * density);
            body.addView(profileTv, profileLp);
            Runnable refreshProfile = () -> {
                boolean learned = slot.noiseProfile != null;
                profileTv.setText(learned
                        ? "Noise profile: learned ("
                                + slot.noiseProfile.length + " bins)"
                        : "Noise profile: adaptive — select noise, then Learn");
                profileTv.setTextColor(learned
                        ? 0xFF34C759 : 0xFF8A93A0);
            };
            refreshProfile.run();
            refreshers.add(refreshProfile);

            android.widget.LinearLayout profileOps =
                    new android.widget.LinearLayout(ctx);
            profileOps.setOrientation(
                    android.widget.LinearLayout.HORIZONTAL);
            TextView learn = makeFxChip(ctx, "Learn from selection", density);
            learn.setOnClickListener(v ->
                    learnNoiseProfile(slot, onChanged));
            profileOps.addView(learn);
            if (fxPanelOpen) {
                // The apply target is frozen, so the user is free to pick a
                // different range for the profile without moving the target.
                TextView learnHint = makeFxChip(ctx,
                        "Drag a noise range, then Learn", density);
                learnHint.setEnabled(false);
                learnHint.setAlpha(0.55f);
                profileOps.addView(learnHint);
            }
            if (slot.noiseProfile != null) {
                TextView clear = makeFxChip(ctx, "Clear profile", density);
                clear.setOnClickListener(v -> {
                    slot.noiseProfile = null;
                    onChanged.run();
                });
                profileOps.addView(clear);
            }
            android.widget.LinearLayout.LayoutParams profileOpsLp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            profileOpsLp.topMargin = Math.round(4 * density);
            body.addView(profileOps, profileOpsLp);
        }

        TextView extrasTitle = new TextView(ctx);
        extrasTitle.setText("All parameters");
        extrasTitle.setTextSize(11f);
        extrasTitle.setTextColor(0xFF8A93A0);
        extrasTitle.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        android.widget.LinearLayout.LayoutParams extrasLp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        extrasLp.topMargin = Math.round(12 * density);
        body.addView(extrasTitle, extrasLp);

        AudioProcessor meta = FxRackChain.getPrototypeProcessor(slot.type.id);
        if (meta != null) {
            for (FxParameter param : meta.getParameters()) {
                if (isPrimaryFxParam(slot.type, param.id)) continue;
                if (slot.type == DspChain.EffectType.WARMTH_BODY
                        && "presence_freq".equals(param.id)) continue;
                if (slot.type == DspChain.EffectType.VOCAL_PRESENCE
                        && "warmth_freq".equals(param.id)) continue;
                if (slot.type == DspChain.EffectType.GRAPHIC_EQ
                        && param.id.matches("g\\d+")) continue;
                if (slot.type == DspChain.EffectType.FILTER_CURVE_EQ
                        && (param.id.startsWith("pf")
                                || param.id.startsWith("pg")
                                || "npts".equals(param.id))) continue;

                if (param.type == FxParameter.Type.BOOLEAN) {
                    TextView chip = makeFxChip(ctx, "", density);
                    android.widget.LinearLayout.LayoutParams chipLp =
                            new android.widget.LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT);
                    chipLp.topMargin = Math.round(4 * density);
                    body.addView(chip, chipLp);
                    Runnable refresh = () -> {
                        int raw = slot.extraParams.containsKey(param.id)
                                ? slot.extraParams.get(param.id)
                                : param.defaultRaw;
                        chip.setText(param.name + ": "
                                + (raw != 0 ? "On" : "Off"));
                        chip.setAlpha(raw != 0 ? 1f : 0.72f);
                    };
                    refresh.run();
                    refreshers.add(refresh);
                    chip.setOnClickListener(v -> {
                        int raw = slot.extraParams.containsKey(param.id)
                                ? slot.extraParams.get(param.id)
                                : param.defaultRaw;
                        boolean nextOn = raw == 0;
                        slot.extraParams.put(param.id, nextOn ? 1 : 0);
                        if (nextOn && slot.type == DspChain.EffectType.BASS_TREBLE
                                && "link".equals(param.id)) {
                            slot.extraParams.put("treble", slot.value);
                        }
                        if (!slot.enabled) slot.enabled = true;
                        onChanged.run();
                    });
                    continue;
                }
                if (param.type == FxParameter.Type.ENUM
                        && param.enumLabels != null
                        && param.enumLabels.length > 0) {
                    TextView chip = makeFxChip(ctx, "", density);
                    android.widget.LinearLayout.LayoutParams chipLp =
                            new android.widget.LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT);
                    chipLp.topMargin = Math.round(4 * density);
                    body.addView(chip, chipLp);
                    Runnable refresh = () -> {
                        int cur = param.clampRaw(
                                slot.extraParams.containsKey(param.id)
                                        ? slot.extraParams.get(param.id)
                                        : param.defaultRaw);
                        chip.setText(param.name + ": "
                                + param.getEnumLabel(cur));
                    };
                    refresh.run();
                    refreshers.add(refresh);
                    chip.setOnClickListener(v -> {
                        int cur = param.clampRaw(
                                slot.extraParams.containsKey(param.id)
                                        ? slot.extraParams.get(param.id)
                                        : param.defaultRaw);
                        int next = (cur + 1) % param.enumLabels.length;
                        slot.extraParams.put(param.id, next);
                        if (slot.type == DspChain.EffectType.NOTCH_FILTER
                                && "preset".equals(param.id)) {
                            if (next == 1) slot.value = 50;
                            else if (next == 2) slot.value = 60;
                        }
                        if (!slot.enabled) slot.enabled = true;
                        onChanged.run();
                    });
                    continue;
                }
                if (param.type == FxParameter.Type.ACTION) {
                    TextView chip = makeFxChip(ctx, param.name, density);
                    android.widget.LinearLayout.LayoutParams chipLp =
                            new android.widget.LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT);
                    chipLp.topMargin = Math.round(4 * density);
                    body.addView(chip, chipLp);
                    chip.setOnClickListener(v -> {
                        int triggers = slot.extraParams.containsKey(param.id)
                                ? slot.extraParams.get(param.id) + 1 : 1;
                        slot.extraParams.put(param.id, triggers);
                        if (!slot.enabled) slot.enabled = true;
                        onChanged.run();
                    });
                    continue;
                }
                if (param.type != FxParameter.Type.INT
                        && param.type != FxParameter.Type.FLOAT) {
                    continue;
                }

                TeleValue pc = new TeleValue(ctx);
                pc.setCaption(param.name);
                pc.setValueScale(Math.max(1, param.scaleDivisor));
                pc.setValueFormatter(param::formatRaw);
                pc.configure(param.minRaw, param.maxRaw,
                        param.interactionStepRaw(),
                        slot.extraParams.containsKey(param.id)
                                ? slot.extraParams.get(param.id)
                                : param.defaultRaw,
                        param.unit);
                android.widget.LinearLayout.LayoutParams pcLp =
                        new android.widget.LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                pcLp.topMargin = Math.round(6 * density);
                body.addView(pc, pcLp);
                refreshers.add(() -> {
                    Integer raw = slot.extraParams.get(param.id);
                    pc.setValue(raw != null ? raw : param.defaultRaw, false);
                });
                java.util.function.IntConsumer applyExtra = (int value) -> {
                    slot.extraParams.put(param.id, value);
                    if (slot.type == DspChain.EffectType.BASS_TREBLE
                            && "treble".equals(param.id)) {
                        Integer link = slot.extraParams.get("link");
                        if (link != null && link != 0) {
                            slot.value = value;
                        }
                    }
                    if (!slot.enabled && value != param.defaultRaw) {
                        slot.enabled = true;
                    }
                    onChanged.run();
                };
                pc.setOnScrubListener(new TeleValue.OnScrubListener() {
                    @Override
                    public void onScrubValueChanged(int value) {
                        applyExtra.accept(value);
                    }

                    @Override
                    public void onScrubFinished(int value) {
                        applyExtra.accept(value);
                    }
                });
                pc.setOnValueChanged(applyExtra::accept);
            }
        }

        Runnable refreshHeader = () -> {
            enableChip.setText(slot.enabled ? "Bypass" : "Enable");
            enableChip.setAlpha(slot.enabled ? 1f : 0.75f);
            title.setTextColor(slot.enabled ? 0xFFF2F4F7
                    : 0xFF9AA3AF);
        };
        refreshHeader.run();
        refreshers.add(refreshHeader);
        enableChip.setOnClickListener(v -> {
            slot.enabled = !slot.enabled;
            onChanged.run();
        });
        closeChip.setOnClickListener(v -> dialog.dismiss());

        android.widget.LinearLayout footer =
                new android.widget.LinearLayout(ctx);
        footer.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        footer.setGravity(android.view.Gravity.END);
        TextView reset = makeFxChip(ctx, "Reset effect", density);
        TextView done = makeFxChip(ctx, "Done", density);
        reset.setOnClickListener(v -> {
            slot.value = slot.type.defaultVal;
            slot.wetDry = 1f;
            slot.extraParams.clear();
            slot.noiseProfile = null;
            onChanged.run();
        });
        done.setOnClickListener(v -> dialog.dismiss());
        footer.addView(reset);
        footer.addView(done);
        android.widget.LinearLayout.LayoutParams footerLp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        footerLp.topMargin = Math.round(8 * density);
        root.addView(footer, footerLp);

        dialog.setOnDismissListener(d -> {
            if (eqHolder[0] != null) eqHolder[0].invalidate();
            if (isAdded()) rebuildFxRackList();
        });
        dialog.setContentView(root);
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0x00000000));
            window.setDimAmount(0.62f);
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int dialogWidth = Math.round(dm.widthPixels * 0.94f);
            int maxHeight = Math.round(dm.heightPixels * 0.84f);
            int minHeight = Math.round(dm.heightPixels * 0.34f);
            root.measure(View.MeasureSpec.makeMeasureSpec(
                            dialogWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(
                            maxHeight, View.MeasureSpec.AT_MOST));
            int dialogHeight = Math.min(maxHeight,
                    Math.max(minHeight, root.getMeasuredHeight()));
            window.setLayout(dialogWidth, dialogHeight);
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
        int pad = Math.round(8 * density);
        int gap = Math.round(5 * density);

        for (int i = 0; i < dspConfig.slots.size(); i++) {
            final DspChain.EffectSlot slot = dspConfig.slots.get(i);
            final int accent = fxAccentColor(slot.type);
            android.widget.LinearLayout pill = new android.widget.LinearLayout(ctx);
            pill.setOrientation(android.widget.LinearLayout.VERTICAL);
            pill.setBackground(fxCardBackground(slot.enabled, accent, density));
            pill.setPadding(pad, Math.round(7 * density), pad,
                    Math.round(7 * density));

            android.widget.LinearLayout.LayoutParams pillLp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) pillLp.topMargin = gap;

            // Header Row: [Checkmark Toggle] | [Title + Value Summary (Tap to Expand)] | [Delete]
            android.widget.LinearLayout header = new android.widget.LinearLayout(ctx);
            header.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            header.setGravity(android.view.Gravity.CENTER_VERTICAL);

            // 0) Category accent rail: makes effect families recognizable at
            // a glance while keeping the card quiet when it is bypassed.
            android.view.View accentBar = new android.view.View(ctx);
            accentBar.setBackgroundColor(withAlpha(accent,
                    slot.enabled ? 0xFF : 0x40));
            int railW = Math.max(2, Math.round(3 * density));
            int railH = Math.round(22 * density);
            android.widget.LinearLayout.LayoutParams railLp =
                    new android.widget.LinearLayout.LayoutParams(railW, railH);
            railLp.rightMargin = Math.round(9 * density);
            header.addView(accentBar, railLp);

            // 1) Checkmark Enable/Disable Toggle
            TextView checkBtn = new TextView(ctx);
            checkBtn.setText(slot.enabled ? "✓" : "");
            checkBtn.setTextSize(13f);
            checkBtn.setGravity(android.view.Gravity.CENTER);
            checkBtn.setTextColor(slot.enabled ? 0xFF0B0D10 : 0x00000000);
            checkBtn.setBackground(fxToggleBackground(slot.enabled, accent,
                    density));
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
            subTv.setText(formatSlotSubtitle(slot, dspConfig));
            subTv.setTextSize(10.5f);
            subTv.setTextColor(slot.enabled ? accent : withAlpha(accent, 0x66));
            titleCol.addView(titleTv);
            titleCol.addView(subTv);
            header.addView(titleCol, new android.widget.LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // 3) Expand Button (opens this effect in its own full inspector)
            android.widget.ImageView expandBtn =
                    new android.widget.ImageView(ctx);
            expandBtn.setImageResource(R.drawable.ic_expand);
            expandBtn.setColorFilter(0xFF9AA3AF);
            expandBtn.setContentDescription("Expand effect");
            int expandSz = Math.round(28 * density);
            int expandPad = Math.round(6 * density);
            expandBtn.setPadding(expandPad, expandPad,
                    expandPad, expandPad);
            expandBtn.setBackgroundResource(R.drawable.bg_round_btn);
            android.widget.LinearLayout.LayoutParams expandLp =
                    new android.widget.LinearLayout.LayoutParams(
                            expandSz, expandSz);
            expandLp.rightMargin = Math.round(2 * density);
            header.addView(expandBtn, expandLp);

            // 4) Delete Button (removes effect from menu)
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
            descTv.setTextSize(9.5f);
            descTv.setTextColor(0xFF8A93A0);
            descTv.setMaxLines(2);
            descTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            body.addView(descTv);

            TeleValue ctl = new TeleValue(ctx);
            ctl.setCaption(slot.type.paramLabel);
            if (!slot.type.isLegacyStage()) {
                AudioProcessor dialMeta =
                        FxRackChain.getPrototypeProcessor(slot.type.id);
                FxParameter dialParam = dialMeta == null ? null
                        : dialMeta.getParam(DspChain.primaryParamId(slot.type));
                if (dialParam != null) {
                    ctl.setValueScale(Math.max(1, dialParam.scaleDivisor));
                    ctl.setValueFormatter(dialParam::formatRaw);
                }
            }
            ctl.configure(slot.type.minVal, slot.type.maxVal,
                    slot.type.stepVal, slot.value, slot.type.unit);
            ctl.setCompact(true);
            android.widget.LinearLayout.LayoutParams ctlLp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            ctlLp.topMargin = Math.round(4 * density);
            body.addView(ctl, ctlLp);
            pill.addView(body, bodyLp);

            final EqCurveView[] eqViewHolder = new EqCurveView[1];
            final java.util.Map<String, TeleValue> extraControls =
                    new java.util.LinkedHashMap<>();
            Runnable onSlotMutated = () -> {
                // Keep the fact that `raw` still has a sub-range bake at the
                // top of the undo journal: the next whole-take bake/save must
                // roll it back first or the selection gets baked twice.
                if (dspSubRangeBaked) lastEditWasSubRangeBake = true;
                dspBakedToRaw = false;
                dspSubRangeBaked = false;
                dspConfig.activePresetId = DspChain.Preset.CUSTOM.id;
                dspConfig.syncLegacyFieldsFromSlots();
                dspConfig.save(ctx);
                if (livePreviewDsp != null) {
                    livePreviewDsp.updateConfig(dspConfig);
                }
                refreshFxPreviewPeaks();
                ctl.setValue(slot.value, false);
                for (java.util.Map.Entry<String, TeleValue> entry
                        : extraControls.entrySet()) {
                    Integer v = slot.extraParams.get(entry.getKey());
                    if (v != null) {
                        entry.getValue().setValue(v, false);
                    }
                }
                if (eqViewHolder[0] != null) {
                    eqViewHolder[0].invalidate();
                }
                checkBtn.setText(slot.enabled ? "✓" : "");
                checkBtn.setTextColor(slot.enabled
                        ? 0xFF0B0D10 : 0x00000000);
                checkBtn.setBackground(fxToggleBackground(slot.enabled,
                        accent, density));
                pill.setBackground(fxCardBackground(slot.enabled, accent,
                        density));
                accentBar.setBackgroundColor(withAlpha(accent,
                        slot.enabled ? 0xFF : 0x40));
                titleTv.setTextColor(slot.enabled ? 0xFFF2F4F7 : 0xFF9AA3AF);
                subTv.setTextColor(slot.enabled ? accent
                        : withAlpha(accent, 0x66));
                subTv.setText(formatSlotSubtitle(slot, dspConfig));
                paintEditorStrip();
                paintFxPanelControls();
            };

            if (supportsNoiseProfile(slot.type)) {
                final boolean hasSelection = waveform != null
                        && waveform.hasSelection();
                TextView profileTv = new TextView(ctx);
                profileTv.setText(slot.noiseProfile != null
                        ? "Noise profile: learned ("
                                + slot.noiseProfile.length + " bins)"
                        : "Noise profile: adaptive — select noise, then Learn");
                profileTv.setTextSize(10.5f);
                profileTv.setTextColor(slot.noiseProfile != null
                        ? 0xFF34C759 : 0xFF8A93A0);
                android.widget.LinearLayout.LayoutParams profileLp =
                        new android.widget.LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                profileLp.topMargin = Math.round(6 * density);
                body.addView(profileTv, profileLp);

                android.widget.LinearLayout profileOps =
                        new android.widget.LinearLayout(ctx);
                profileOps.setOrientation(
                        android.widget.LinearLayout.HORIZONTAL);
                TextView learnBtn = makeFxChip(ctx,
                        "Learn from selection", density);
                learnBtn.setAlpha(hasSelection ? 1f : 0.35f);
                learnBtn.setOnClickListener(v -> {
                    if (waveform == null || !waveform.hasSelection()) {
                        Toast.makeText(requireContext(),
                                "Select a noise-only region on the waveform first",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    learnNoiseProfile(slot, onSlotMutated);
                });
                profileOps.addView(learnBtn);
                if (slot.noiseProfile != null) {
                    TextView clearProfileBtn = makeFxChip(ctx,
                            "Clear profile", density);
                    clearProfileBtn.setOnClickListener(v -> {
                        slot.noiseProfile = null;
                        onSlotMutated.run();
                        rebuildFxRackList();
                    });
                    profileOps.addView(clearProfileBtn);
                }
                android.widget.LinearLayout.LayoutParams profileOpsLp =
                        new android.widget.LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                profileOpsLp.topMargin = Math.round(4 * density);
                body.addView(profileOps, profileOpsLp);
            }

            // Interactive curve editors for the Stage 3 EQ family: drag the
            // response directly on the canvas (bands, sliders or curve nodes).
            boolean hasEqView = slot.type == DspChain.EffectType.PARAMETRIC_EQ6
                    || slot.type == DspChain.EffectType.GRAPHIC_EQ
                    || slot.type == DspChain.EffectType.FILTER_CURVE_EQ;
            if (hasEqView) {
                EqCurveView eqView;
                if (slot.type == DspChain.EffectType.PARAMETRIC_EQ6) {
                    eqView = new ParametricEqView(ctx, slot, onSlotMutated);
                } else if (slot.type == DspChain.EffectType.GRAPHIC_EQ) {
                    eqView = new GraphicEqView(ctx, slot, onSlotMutated);
                } else {
                    eqView = new FilterCurveEqView(ctx, slot, onSlotMutated);
                }
                eqViewHolder[0] = eqView;
                android.widget.LinearLayout.LayoutParams eqLp =
                        new android.widget.LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                Math.round(132 * density));
                eqLp.topMargin = Math.round(8 * density);
                body.addView(eqView, eqLp);
            }

            // Stage 4 dynamics card: the transfer curve and the live gain
            // reduction meter explain what the compressor is doing.
            if (slot.type == DspChain.EffectType.DYNAMIC_COMPRESSOR
                    || slot.type == DspChain.EffectType.MULTIBAND_COMPRESSOR) {
                DynamicsGraphView dynView = new DynamicsGraphView(ctx, slot);
                android.widget.LinearLayout.LayoutParams dynLp =
                        new android.widget.LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                Math.round(140 * density));
                dynLp.topMargin = Math.round(8 * density);
                body.addView(dynView, dynLp);
            }

            checkBtn.setOnClickListener(v -> {
                slot.enabled = !slot.enabled;
                onSlotMutated.run();
                rebuildFxRackList();
            });

            titleCol.setOnClickListener(v -> {
                slot.expanded = !slot.expanded;
                body.setVisibility(slot.expanded ? View.VISIBLE : View.GONE);
                dspConfig.save(ctx);
                subTv.setText(formatSlotSubtitle(slot, dspConfig));
            });

            expandBtn.setOnClickListener(v -> showEffectDetailModal(slot));

            delBtn.setOnClickListener(v -> {
                if (dspSubRangeBaked) lastEditWasSubRangeBake = true;
                dspBakedToRaw = false;
                dspSubRangeBaked = false;
                fxAdvancedOpen.remove(slot);
                dspConfig.removeSlot(slot);
                dspConfig.save(ctx);
                if (livePreviewDsp != null) {
                    livePreviewDsp.updateConfig(dspConfig);
                }
                refreshFxPreviewPeaks();
                rebuildFxRackList();
                paintEditorStrip();
                paintFxPanelControls();
            });

            java.util.function.IntConsumer syncPrimaryLinks = (int val) -> {
                if (slot.type == DspChain.EffectType.NOTCH_FILTER) {
                    Integer pIdx = slot.extraParams.get("preset");
                    if (pIdx != null && ((pIdx == 1 && val != 50)
                            || (pIdx == 2 && val != 60))) {
                        slot.extraParams.put("preset", 0);
                    }
                } else if (slot.type == DspChain.EffectType.BASS_TREBLE) {
                    Integer linkRaw = slot.extraParams.get("link");
                    if (linkRaw != null && linkRaw != 0) {
                        slot.extraParams.put("treble", val);
                    }
                }
            };
            TeleValue.OnScrubListener scrub = new TeleValue.OnScrubListener() {
                @Override
                public void onScrubValueChanged(int value) {
                    slot.value = value;
                    syncPrimaryLinks.accept(value);
                    boolean engages = slot.type.isLegacyStage()
                            ? (value > 0) : (value != slot.type.defaultVal);
                    if (!slot.enabled && engages) slot.enabled = true;
                    onSlotMutated.run();
                }

                @Override
                public void onScrubFinished(int value) {
                    slot.value = value;
                    syncPrimaryLinks.accept(value);
                    boolean engages = slot.type.isLegacyStage()
                            ? (value > 0) : (value != slot.type.defaultVal);
                    if (!slot.enabled && engages) slot.enabled = true;
                    onSlotMutated.run();
                    rebuildFxRackList();
                }
            };
            ctl.setOnScrubListener(scrub);
            ctl.setOnValueChanged(val -> {
                slot.value = val;
                syncPrimaryLinks.accept(val);
                boolean engages = slot.type.isLegacyStage()
                        ? (val > 0) : (val != slot.type.defaultVal);
                if (!slot.enabled && engages) slot.enabled = true;
                onSlotMutated.run();
                rebuildFxRackList();
            });

            // ---- Inspector extras: ordering, blend, reset, extra params ----
            final int slotIdx = i;

            android.widget.LinearLayout ops =
                    new android.widget.LinearLayout(ctx);
            ops.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            android.widget.LinearLayout.LayoutParams opsLp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            opsLp.topMargin = Math.round(8 * density);

            TextView upBtn = makeFxChip(ctx, "↑ Move Up", density);
            TextView downBtn = makeFxChip(ctx, "↓ Move Down", density);
            TextView resetBtn = makeFxChip(ctx, "Reset", density);
            ops.addView(upBtn);
            ops.addView(downBtn);
            ops.addView(resetBtn);
            body.addView(ops, opsLp);

            boolean canUp = slotIdx > 0;
            boolean canDown = slotIdx < dspConfig.slots.size() - 1;
            upBtn.setEnabled(canUp);
            upBtn.setAlpha(canUp ? 1f : 0.35f);
            downBtn.setEnabled(canDown);
            downBtn.setAlpha(canDown ? 1f : 0.35f);
            upBtn.setOnClickListener(v -> {
                if (slotIdx <= 0) return;
                DspChain.EffectSlot moved =
                        dspConfig.slots.remove(slotIdx);
                dspConfig.slots.add(slotIdx - 1, moved);
                onSlotMutated.run();
                rebuildFxRackList();
            });
            downBtn.setOnClickListener(v -> {
                if (slotIdx >= dspConfig.slots.size() - 1) return;
                DspChain.EffectSlot moved =
                        dspConfig.slots.remove(slotIdx);
                dspConfig.slots.add(slotIdx + 1, moved);
                onSlotMutated.run();
                rebuildFxRackList();
            });
            resetBtn.setOnClickListener(v -> {
                slot.value = slot.type.defaultVal;
                slot.wetDry = 1f;
                slot.extraParams.clear();
                slot.noiseProfile = null;
                onSlotMutated.run();
                rebuildFxRackList();
            });

            // Wet/dry blend for all 1:1 stages and for Warmth / Presence when
            // only one tone slot is active in the rack.
            boolean showMixControl =
                    (slot.type != DspChain.EffectType.WARMTH_BODY
                            && slot.type != DspChain.EffectType.VOCAL_PRESENCE)
                    || !dspConfig.hasBothToneSlotsEnabled();
            if (showMixControl) {
                TextView mixCaption = new TextView(ctx);
                mixCaption.setText("Wet-Dry Mix");
                mixCaption.setTextSize(10.5f);
                mixCaption.setTextColor(0xFF8A93A0);
                body.addView(mixCaption);

                TeleValue mixCtl = new TeleValue(ctx);
                mixCtl.setCompact(true);
                mixCtl.configure(0, 100, 5,
                        Math.round(slot.wetDry * 100f), "%");
                android.widget.LinearLayout.LayoutParams mixLp =
                        new android.widget.LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                mixLp.topMargin = Math.round(4 * density);
                body.addView(mixCtl, mixLp);
                mixCtl.setOnScrubListener(new TeleValue.OnScrubListener() {
                    @Override
                    public void onScrubValueChanged(int value) {
                        slot.wetDry = value / 100f;
                        onSlotMutated.run();
                    }

                    @Override
                    public void onScrubFinished(int value) {
                        slot.wetDry = value / 100f;
                        onSlotMutated.run();
                    }
                });
                mixCtl.setOnValueChanged(val -> {
                    slot.wetDry = val / 100f;
                    onSlotMutated.run();
                });
            }

            // Secondary processor parameters, laid out two per row so the
            // dense Pro cards stay readable on narrow phones. The legacy
            // stages only surface their own extra knobs; their primary dial
            // stays above.
            java.util.List<View> secondaryViews = new java.util.ArrayList<>();
            AudioProcessor meta = FxRackChain.getPrototypeProcessor(slot.type.id);
            if (meta != null) {
                for (FxParameter param : meta.getParameters()) {
                    if (isPrimaryFxParam(slot.type, param.id)) continue;
                    // The warmth and presence cards share one tone processor;
                    // each card only edits its own corner frequency.
                    if (slot.type == DspChain.EffectType.WARMTH_BODY
                            && "presence_freq".equals(param.id)) continue;
                    if (slot.type == DspChain.EffectType.VOCAL_PRESENCE
                            && "warmth_freq".equals(param.id)) continue;
                    // Curve editors own these params: graphic bands and
                    // filter-curve nodes are dragged on the canvas instead.
                    if (slot.type == DspChain.EffectType.GRAPHIC_EQ
                            && param.id.matches("g\\d+")) continue;
                    if (slot.type == DspChain.EffectType.FILTER_CURVE_EQ
                            && (param.id.startsWith("pf")
                                    || param.id.startsWith("pg")
                                    || "npts".equals(param.id))) continue;
                    if (param.type == FxParameter.Type.BOOLEAN) {
                        TextView b = makeFxChip(ctx, param.name, density);
                        final int rawBool = slot.extraParams.containsKey(
                                param.id)
                                ? slot.extraParams.get(param.id)
                                : param.defaultRaw;
                        final boolean on = rawBool != 0;
                        b.setText(param.name + ": "
                                + (on ? "On" : "Off"));
                        b.setOnClickListener(v -> {
                            boolean nextOn = !on;
                            slot.extraParams.put(param.id, nextOn ? 1 : 0);
                            if (nextOn && slot.type == DspChain.EffectType.BASS_TREBLE
                                    && "link".equals(param.id)) {
                                slot.extraParams.put("treble", slot.value);
                            }
                            if (!slot.enabled) slot.enabled = true;
                            onSlotMutated.run();
                            rebuildFxRackList();
                        });
                        secondaryViews.add(b);
                        continue;
                    }
                    if (param.type == FxParameter.Type.ENUM
                            && param.enumLabels != null
                            && param.enumLabels.length > 0) {
                        TextView eChip = makeFxChip(ctx, param.name, density);
                        final int curIdx = param.clampRaw(
                                slot.extraParams.containsKey(param.id)
                                        ? slot.extraParams.get(param.id)
                                        : param.defaultRaw);
                        eChip.setText(param.name + ": "
                                + param.getEnumLabel(curIdx));
                        eChip.setOnClickListener(v -> {
                            int nextIdx = (curIdx + 1) % param.enumLabels.length;
                            slot.extraParams.put(param.id, nextIdx);
                            if (slot.type == DspChain.EffectType.NOTCH_FILTER
                                    && "preset".equals(param.id)) {
                                if (nextIdx == 1) slot.value = 50;
                                else if (nextIdx == 2) slot.value = 60;
                            }
                            if (!slot.enabled) slot.enabled = true;
                            onSlotMutated.run();
                            rebuildFxRackList();
                        });
                        secondaryViews.add(eChip);
                        continue;
                    }
                    if (param.type == FxParameter.Type.ACTION) {
                        TextView actChip = makeFxChip(ctx, param.name, density);
                        actChip.setOnClickListener(v -> {
                            int triggers = slot.extraParams.containsKey(param.id)
                                    ? slot.extraParams.get(param.id) + 1 : 1;
                            slot.extraParams.put(param.id, triggers);
                            if (!slot.enabled) slot.enabled = true;
                            onSlotMutated.run();
                            rebuildFxRackList();
                        });
                        secondaryViews.add(actChip);
                        continue;
                    }
                    if (param.type != FxParameter.Type.INT
                            && param.type != FxParameter.Type.FLOAT) {
                        continue;
                    }
                    TeleValue pc = new TeleValue(ctx);
                    pc.setCaption(param.name);
                    pc.setValueScale(Math.max(1, param.scaleDivisor));
                    pc.setValueFormatter(param::formatRaw);
                    pc.configure(param.minRaw, param.maxRaw,
                            param.interactionStepRaw(),
                            slot.extraParams.containsKey(param.id)
                                    ? slot.extraParams.get(param.id)
                                    : param.defaultRaw,
                            param.unit);
                    pc.setCompact(true);
                    extraControls.put(param.id, pc);
                    java.util.function.IntConsumer syncSecondaryLinks = (int value) -> {
                        slot.extraParams.put(param.id, value);
                        if (slot.type == DspChain.EffectType.BASS_TREBLE
                                && "treble".equals(param.id)) {
                            Integer linkRaw = slot.extraParams.get("link");
                            if (linkRaw != null && linkRaw != 0) {
                                slot.value = value;
                            }
                        }
                        if (!slot.enabled && value != param.defaultRaw) {
                            slot.enabled = true;
                        }
                    };
                    pc.setOnScrubListener(new TeleValue.OnScrubListener() {
                        @Override
                        public void onScrubValueChanged(int value) {
                            syncSecondaryLinks.accept(value);
                            onSlotMutated.run();
                        }

                        @Override
                        public void onScrubFinished(int value) {
                            syncSecondaryLinks.accept(value);
                            onSlotMutated.run();
                        }
                    });
                    pc.setOnValueChanged(val -> {
                        syncSecondaryLinks.accept(val);
                        onSlotMutated.run();
                    });
                    secondaryViews.add(pc);
                }
            }
            if (hasEqView && !secondaryViews.isEmpty()) {
                // The curve canvas is the primary EQ editor; its numeric
                // frequency/gain/Q parameters stay one tap away instead of
                // forming a wall of steppers under the graph.
                final android.widget.LinearLayout advancedBox =
                        new android.widget.LinearLayout(ctx);
                advancedBox.setOrientation(android.widget.LinearLayout.VERTICAL);
                final boolean advancedOpen = fxAdvancedOpen.contains(slot);
                advancedBox.setVisibility(advancedOpen
                        ? View.VISIBLE : View.GONE);
                addFxExtraGrid(advancedBox, secondaryViews, density);

                final TextView advancedToggle = makeFxChip(ctx,
                        advancedOpen ? "Hide advanced controls"
                                : "Show advanced controls ("
                                        + secondaryViews.size() + ")",
                        density);
                android.widget.LinearLayout.LayoutParams advLp =
                        new android.widget.LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                advLp.topMargin = Math.round(6 * density);
                body.addView(advancedToggle, advLp);
                body.addView(advancedBox);
                advancedToggle.setOnClickListener(v -> {
                    boolean show = advancedBox.getVisibility() != View.VISIBLE;
                    advancedBox.setVisibility(show ? View.VISIBLE : View.GONE);
                    if (show) {
                        fxAdvancedOpen.add(slot);
                    } else {
                        fxAdvancedOpen.remove(slot);
                    }
                    advancedToggle.setText(show
                            ? "Hide advanced controls"
                            : "Show advanced controls (" + secondaryViews.size() + ")");
                });
            } else {
                addFxExtraGrid(body, secondaryViews, density);
            }

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

    /**
     * Lays extra FX parameters out in two compact columns. Two-per-row cuts
     * the vertical run of a Pro card roughly in half on a phone, while a lone
     * control (or an odd tail) still gets the full width.
     */
    private void addFxExtraGrid(android.widget.LinearLayout body,
                                java.util.List<View> views, float density) {
        if (body == null || views == null || views.isEmpty()) return;
        Context ctx = requireContext();
        int gap = Math.round(5 * density);
        for (int i = 0; i < views.size(); i += 2) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            boolean pair = i + 1 < views.size();
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(views.get(i), lp);
            if (pair) {
                android.widget.LinearLayout.LayoutParams rp =
                        new android.widget.LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                rp.leftMargin = gap;
                row.addView(views.get(i + 1), rp);
            }
            android.widget.LinearLayout.LayoutParams rowLp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = Math.round(4 * density);
            body.addView(row, rowLp);
        }
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
                if (!isAdded() || dspConfig == null) return;
                if (dspSubRangeBaked) lastEditWasSubRangeBake = true;
                dspBakedToRaw = false;
                dspSubRangeBaked = false;
                dspConfig.applyPreset(pick);
                dspConfig.save(requireContext());
                if (livePreviewDsp != null) {
                    livePreviewDsp.updateConfig(dspConfig);
                }
                refreshFxPreviewPeaks();
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

        // Category filter chips: the catalog now spans eight families, so a
        // search box alone is not enough to find an effect quickly.
        final FxCategory[] categories = {
                null, FxCategory.EQUALIZER, FxCategory.DYNAMICS,
                FxCategory.RESTORATION, FxCategory.SPATIAL, FxCategory.TIME,
                FxCategory.CREATIVE, FxCategory.TOOLS, FxCategory.ANALYZER};
        final String[] categoryLabels = {
                "All", "EQ & Filters", "Dynamics", "Restoration",
                "Spatial", "Pitch & Time", "Creative", "Tools", "Analyzers"};
        final FxCategory[] selected = {null};
        final android.widget.HorizontalScrollView chipScroll =
                new android.widget.HorizontalScrollView(ctx);
        chipScroll.setHorizontalScrollBarEnabled(false);
        android.widget.LinearLayout chipRow =
                new android.widget.LinearLayout(ctx);
        chipRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        chipScroll.addView(chipRow);
        android.widget.LinearLayout.LayoutParams chipLp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.topMargin = Math.round(8 * density);
        root.addView(chipScroll, chipLp);
        final android.widget.TextView[] chipViews =
                new android.widget.TextView[categories.length];

        Runnable populateList = () -> {
            String q = searchBox.getText().toString().trim().toLowerCase(Locale.US);
            FxCategory filter = selected[0];
            listCol.removeAllViews();
            // The catalog is listed alphabetically so a long effect list stays
            // navigable.
            java.util.List<DspChain.EffectType> catalog =
                    new java.util.ArrayList<>(java.util.Arrays.asList(
                            DspChain.EffectType.values()));
            java.util.Collections.sort(catalog,
                    new java.util.Comparator<DspChain.EffectType>() {
                        @Override
                        public int compare(DspChain.EffectType a,
                                           DspChain.EffectType b) {
                            return a.title.compareToIgnoreCase(b.title);
                        }
                    });
            for (DspChain.EffectType t : catalog) {
                if (filter != null
                        && DspChain.categoryOf(t) != filter) {
                    continue;
                }
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
                    if (!isAdded() || dspConfig == null) return;
                    if (dspSubRangeBaked) lastEditWasSubRangeBake = true;
                    dspBakedToRaw = false;
                    dspSubRangeBaked = false;
                    dspConfig.addOrFocusSlot(t);
                    dspConfig.save(ctx);
                    if (livePreviewDsp != null) {
                        livePreviewDsp.updateConfig(dspConfig);
                    }
                    refreshFxPreviewPeaks();
                    rebuildFxRackList();
                    paintFxPanelControls();
                    paintEditorStrip();
                    d.dismiss();
                });
                listCol.addView(row, rLp);
            }
        };

        for (int i = 0; i < categories.length; i++) {
            final int index = i;
            TextView chip = makeFxChip(ctx, categoryLabels[i], density);
            chipViews[i] = chip;
            android.widget.LinearLayout.LayoutParams clp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = Math.round(5 * density);
            chipRow.addView(chip, clp);
            chip.setOnClickListener(v -> {
                selected[0] = categories[index];
                for (int k = 0; k < chipViews.length; k++) {
                    boolean on = k == index;
                    chipViews[k].setTextColor(on ? 0xFF0B0D10 : 0xFF9AA3AF);
                    chipViews[k].setBackgroundResource(on
                            ? R.drawable.bg_pill_option_selected
                            : R.drawable.bg_pill_option);
                }
                populateList.run();
            });
        }
        // "All" starts selected.
        chipViews[0].setTextColor(0xFF0B0D10);
        chipViews[0].setBackgroundResource(R.drawable.bg_pill_option_selected);
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
        OptionsSheet.styleAsCentered(d);
        d.show();
    }


    /**
     * Applies enabled effects from the FX rack to the selected audio region
     * when a selection is active, or to the entire take when no region is
     * selected. Works identically on both the normal recording and the
     * Select & Replace sub-editor recording.
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
        if (dspConfig == null) return false;
        dspConfig.syncLegacyFieldsFromSlots();
        if (!dspConfig.isActive()) {
            Toast.makeText(requireContext(),
                    "Enable at least one effect (checkmark) first",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        // Apply works on the selection when there is one and on the WHOLE TAKE
        // otherwise — the old forced "enable selection first" return aborted
        // silently and made the full-take branch below unreachable.
        if (waveform == null) return false;
        // In effects mode the target is the region that was selected before
        // the rack opened, so re-selecting a range (to learn a noise profile,
        // say) cannot silently change what Stop commits to.
        boolean hadSel = fxPanelOpen
                ? fxApplyTargetHasSelection
                : waveform.hasSelection();
        File raw = engine.rawFile;
        if (raw == null || !raw.exists()) return false;
        if (!hadSel && dspBakedToRaw && !dspSubRangeBaked) {
            Toast.makeText(requireContext(),
                    "Effects already applied to entire take (tap Undo or adjust a parameter first)",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        boolean peaksPushed = false;
        try {
            // Config.isActive() can disagree with the processor chain (e.g. a
            // Stage 3 slot whose parameters are neutral); probe the real chain
            // BEFORE any undo bookkeeping so an inaudible rack never pushes a
            // waveform peak snapshot without a matching audio snapshot.
            try {
                FormatConverter.WavInfo preInfo =
                        FormatConverter.readFloatWav(raw);
                DspChain probeChain = new DspChain(preInfo.channels, dspConfig);
                if (!probeChain.isActive()) {
                    Toast.makeText(requireContext(),
                            "Adjust an effect parameter away from 0 dB first",
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
            EditEngine.UndoJournal uj = activeUndoJournal();
            // If a sub-selection was just baked with this exact rack — even if
            // a knob tweak has since cleared dspSubRangeBaked — restore the
            // pre-sub-bake snapshot first so the selected region is not baked
            // twice!
            if (!hadSel && (dspSubRangeBaked || lastEditWasSubRangeBake)
                    && uj != null && uj.canUndo()) {
                if (uj.undo(raw)) {
                    waveform.popUndoPeaks();
                    engine.dataBytes = RecordingEngine.payloadBytesOf(raw);
                    dspBakedToRaw = false;
                    dspSubRangeBaked = false;
                    lastEditWasSubRangeBake = false;
                }
            }
            FormatConverter.WavInfo info = FormatConverter.readFloatWav(raw);
            long totalFrames = info.dataBytes / 4 / Math.max(1, info.channels);
            if (totalFrames <= 0) return false;
            long start = 0L;
            long end = totalFrames;
            if (hadSel) {
                float startFrac = fxPanelOpen
                        ? fxApplyTargetStart
                        : waveform.selectionStartFraction();
                float endFrac = fxPanelOpen
                        ? fxApplyTargetEnd
                        : waveform.selectionEndFraction();
                start = Math.round(startFrac * totalFrames);
                end = Math.round(endFrac * totalFrames);
                if (end <= start) {
                    Toast.makeText(requireContext(),
                            "Select a valid audio region first",
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
            // The file is about to change: stop the live region render and
            // drop its dry baseline so the reloaded bars become the new truth.
            fxPreviewGeneration++;
            handler.removeCallbacks(fxPreviewRefresh);
            waveform.clearFxRegionPeaks();
            waveform.recordUndoPeaks();
            peaksPushed = true;
            DspChain chain = new DspChain(info.channels, dspConfig);
            // Duration-changing tools (Change Tempo, Change Speed, Repeat,
            // Truncate Silence, ...) render the region offline and rewrite the
            // file, so they take a different path than the in-place chain.
            // They run first; the in-place chain then processes whatever
            // region came out of them.
            java.util.List<AudioProcessor> offline =
                    chain.offlineProcessors();
            if (!offline.isEmpty()) {
                long regionEnd = end;
                for (AudioProcessor proc : offline) {
                    long newTotal = EditEngine.applyLengthChangingEffectToRange(
                            raw, start, regionEnd, proc, uj);
                    if (newTotal < 0) {
                        waveform.popUndoPeaks();
                        peaksPushed = false;
                        Toast.makeText(requireContext(),
                                "This effect could not be applied here",
                                Toast.LENGTH_SHORT).show();
                        return false;
                    }
                    regionEnd = start + (newTotal - (totalFrames - regionEnd));
                }
                // The offline pass rewrote the region: run the remaining
                // in-place processors over the new boundaries.
                if (chain.hasInPlaceProcessors()) {
                    DspChain inPlace = new DspChain(info.channels, dspConfig);
                    inPlace.clearOfflineProcessors();
                    EditEngine.applyDspToRange(raw, start, regionEnd, inPlace, null);
                }
                engine.abortStreaming();
                engine.dataBytes = RecordingEngine.payloadBytesOf(raw);
                dspBakedToRaw = true;
                dspSubRangeBaked = hadSel;
                lastEditWasSubRangeBake = false;
                waveform.loadFromFloatWav(raw);
                resetFxRackAfterApply();
                syncFxBakeRequired();
                Toast.makeText(requireContext(),
                        "Effect applied (Undo available)",
                        Toast.LENGTH_SHORT).show();
                paint();
                paintFxPanelControls();
                return true;
            }
            boolean snapshotRecorded =
                    EditEngine.applyDspToRange(raw, start, end, chain, uj);
            if (!snapshotRecorded) {
                waveform.popUndoPeaks();
                peaksPushed = false;
            }
            lastEditWasSubRangeBake = false;
            // The raw now carries the chain for the applied range: the
            // incremental pump must not keep encoding unprocessed audio, and
            // neither preview nor save may process these samples a second time.
            engine.abortStreaming();
            engine.dataBytes = RecordingEngine.payloadBytesOf(raw);
            if (!hadSel) {
                // Whole take baked: mark it so preview (livePreviewDsp) and
                // save never process these samples a second time.
                dspBakedToRaw = true;
                dspSubRangeBaked = false;
                waveform.reloadSelectionPeaksFromWav(raw, 0f, 1f);
            } else {
                waveform.reloadSelectionPeaksFromWav(raw,
                        fxPanelOpen ? fxApplyTargetStart
                                : waveform.selectionStartFraction(),
                        fxPanelOpen ? fxApplyTargetEnd
                                : waveform.selectionEndFraction());
                // Only the selected region was baked. The raw file now carries
                // that bake, so preview/save must not process it again even
                // though the rest of the take remains dry.
                dspBakedToRaw = true;
                dspSubRangeBaked = true;
            }
            // Apply is the single commit point: the raw file and waveform now
            // carry the processed audio, so start a fresh, blank rack. Keeping
            // the old slots enabled would leave the UI implying the effects
            // are still pending and invite a second bake.
            resetFxRackAfterApply();
            syncFxBakeRequired();
            waveform.clearSelection();
            Toast.makeText(requireContext(),
                    hadSel ? "Effects applied to selection (Undo available)"
                            : "Effects applied to entire take (Undo available)",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            if (peaksPushed && waveform != null) waveform.popUndoPeaks();
            Toast.makeText(requireContext(), "FX failed",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        paint();
        paintFxPanelControls();
        return true;
    }

    /** Empties the FX editor after a successful bake; processed audio stays. */
    private void resetFxRackAfterApply() {
        if (dspConfig == null) return;
        dspConfig.clearRack();
        try {
            dspConfig.save(requireContext());
        } catch (Exception ignored) {}
        if (livePreviewDsp != null) {
            livePreviewDsp.updateConfig(dspConfig);
        }
        // The effect-applied waveform overlay follows every rack edit.
        refreshFxPreviewPeaks();
        // The baked audio is now the baseline: there is no "same rack" left to
        // protect from a second bake, so a later whole-take edit must not roll
        // this Apply back through the sub-range undo snapshot.
        dspBakedToRaw = false;
        dspSubRangeBaked = false;
        lastEditWasSubRangeBake = false;
        rebuildFxRackList();
    }

    private void paint() {
        if (getView() == null || !isAdded()) return;
        syncFxBakeRequired();
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
