package com.example.hello;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ProgressBar;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private DbMeterView dbMeter;
    private View settingsRow;
    private TextView settingsName;
    private TextView settingsSub;
    private View telePanel;
    private android.widget.ScrollView teleScroll;
    private android.widget.EditText teleScript;
    private View teleToggle;
    private android.widget.ImageView teleToggleIcon;
    private View btnZoomIn;
    private View btnZoomOut;
    private TextView zoomLabel;

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

    private Dialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressMessage;

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
        teleScroll = v.findViewById(R.id.teleScroll);
        teleScript = v.findViewById(R.id.teleScript);
        teleToggle = v.findViewById(R.id.teleToggle);
        teleToggleIcon = v.findViewById(R.id.teleToggleIcon);
        btnZoomIn = v.findViewById(R.id.btnZoomIn);
        btnZoomOut = v.findViewById(R.id.btnZoomOut);
        zoomLabel = v.findViewById(R.id.zoomLabel);

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
        PressAnim.applySoft(teleToggle, 0.9f);
        btnZoomIn.setOnClickListener(view -> stepZoom(1));
        btnZoomOut.setOnClickListener(view -> stepZoom(-1));
        PressAnim.applySoft(btnZoomIn, 0.9f);
        PressAnim.applySoft(btnZoomOut, 0.9f);
        teleScript.setText(loadTeleScript());
        zoomLabel.setText(zoomText(waveform.getZoom()));

        // Recovery (crash prompt / notification tap) or an edit punch-in:
        // a session is already attached — never show the name modal.
        Bundle args = getArguments();
        boolean attached = false;
        boolean autostart = false;
        if (args != null && args.containsKey("recover_raw")) {
            try {
                File raw = new File(args.getString("recover_raw"));
                if (raw.exists() && !engine.hasSession()) {
                    engine.attachExisting(requireContext(), raw,
                            engine.takeName != null ? engine.takeName
                                    : prettyBase(raw),
                            2, RecordingEngine.payloadBytesOf(raw));
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
        dismissProgress();
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
        OptionsSheet.showInputWithCancel(requireContext(),
                getString(R.string.name_take_title),
                nextTakeName(),
                getString(R.string.name_take_save),
                text -> {
                    pendingName = RecordingEngine.sanitize(text);
                    paint();
                    activity().syncFabWithEngine();
                },
                () -> {
                    // Backed out of naming: nothing to record, leave.
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
                                if (engine.state
                                        == RecordingEngine.State.RECORDING) {
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

    private void toggleTeleprompter() {
        if (telePanel.getVisibility() == View.VISIBLE) {
            persistTeleScript();
            try {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) requireContext()
                                .getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(teleScript.getWindowToken(), 0);
                }
            } catch (Exception ignored) {}
            teleScript.clearFocus();
            telePanel.setVisibility(View.GONE);
            setTeleActive(false);
        } else {
            telePanel.setVisibility(View.VISIBLE);
            setTeleActive(true);
            teleScript.requestFocus();
        }
    }

    private void setTeleActive(boolean active) {
        teleToggle.setBackgroundResource(active
                ? R.drawable.bg_chip_selected : R.drawable.bg_round_btn);
        teleToggleIcon.setAlpha(active ? 1f : 0.6f);
    }

    private String loadTeleScript() {
        try {
            return requireContext().getSharedPreferences("takeone", Context.MODE_PRIVATE)
                    .getString("tele_script", "");
        } catch (Exception e) {
            return "";
        }
    }

    private void persistTeleScript() {
        try {
            String text = teleScript != null ? teleScript.getText().toString() : "";
            requireContext().getSharedPreferences("takeone", Context.MODE_PRIVATE)
                    .edit().putString("tele_script", text).apply();
        } catch (Exception ignored) {}
    }

    // ---- waveform zoom (time shown per unit length) ----

    private void stepZoom(int dir) {
        float[] steps = WaveformView.ZOOM_STEPS;
        float cur = waveform.getZoom();
        int idx = 0;
        for (int i = 0; i < steps.length; i++) {
            if (Math.abs(steps[i] - cur) < 0.01f) idx = i;
        }
        int next = Math.max(0, Math.min(steps.length - 1, idx + dir));
        waveform.setZoom(steps[next]);
        zoomLabel.setText(zoomText(steps[next]));
        PressAnim.nudge(dir > 0 ? btnZoomIn : btnZoomOut, 1.12f);
    }

    private static String zoomText(float z) {
        if (Math.abs(z - Math.round(z)) < 0.01f) return Math.round(z) + "x";
        return String.valueOf(z) + "x";
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
        if (!engine.hasSession()) {
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
        try {
            if (engine.state == RecordingEngine.State.PAUSED) {
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

    private void resumeTake() {
        if (engine.state != RecordingEngine.State.PAUSED || saving) return;
        startOrResumeCapture();
    }

    /** Service-owned focus loss funnels here (interruption: pause, no resume). */
    public void onInterruptionPause() {
        handler.post(() -> pauseTake(true));
    }

    // ---- stop: save (convert) or discard ----

    private void doSaveSequence() {
        if (saving || !engine.hasSession()) return;
        saving = true;
        showProgress(preset);
        final RecordPreset p = preset;
        final SaveFormat s = saveFormat;
        final String convLabel = TakeSaver.labelFor(p, s);
        final long editId = editSourceId;
        final String editTitle = editSourceTitle;
        new Thread(() -> {
            boolean ok;
            String msg;
            String label = convLabel;
            File fin = null;
            long dur = 1000;
            String title = engine.takeName != null ? engine.takeName : "";
            try {
                TakeSaver.Result r = TakeSaver.finalizeAndConvert(
                        requireContext().getApplicationContext(), p, s,
                        pct -> postProgress(pct, convLabel));
                fin = r.file;
                label = r.label;
                dur = r.durationMs;
                title = r.title;
                if (editId == -1) {
                    TakesRepository.addRecording(fin, dur, title, label);
                }
                // Edit punch-ins resolve replace-vs-new on the UI thread below,
                // where the new file + duration are in hand.
                engine.clearSession();
                ok = true;
                msg = getString(R.string.convert_done_msg, title, label);
                if (editId != -1) {
                    final File saved = fin;
                    final long savedDur = Math.max(dur, 1000);
                    final String savedLabel = label;
                    final String savedTitle = title;
                    handler.post(() -> {
                        try {
                            saving = false;
                            dismissProgress();
                            if (!isAdded()) return;
                            RecordingService.stopService(requireContext());
                            activity().syncFabWithEngine();
                            askEditReplaceOrNew(editId, editTitle, savedTitle,
                                    saved, savedDur, savedLabel);
                        } catch (Exception ex) {
                            try {
                                CrashLog.breadcrumb(getContext(),
                                        "completion-block FAILED: " + ex);
                            } catch (Exception ignored) {}
                        }
                    });
                    return;
                }
            } catch (Exception e) {
                ok = false;
                label = "";
                msg = getString(R.string.convert_failed_msg, title);
            }
            final boolean done = ok;
            final String text = msg;
            handler.post(() -> {
                try {
                    saving = false;
                    dismissProgress();
                    if (!isAdded()) return;
                    RecordingService.stopService(requireContext());
                    activity().syncFabWithEngine();
                    if (done) {
                        OptionsSheet.showResult(requireContext(), true,
                                getString(R.string.convert_done_title), text,
                                () -> {
                                    if (isAdded()) activity().exitStudio();
                                });
                    } else {
                        // Raw is kept so the user can retry or discard.
                        paint();
                        OptionsSheet.showResult(requireContext(), false,
                                getString(R.string.convert_failed_title), text);
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

    /** Edit punch-in done: replace the original or keep both. */
    private void askEditReplaceOrNew(long originalId, String originalTitle,
                                     String sessionTitle, File saved,
                                     long savedDur, String label) {
        String target = originalTitle != null && !originalTitle.isEmpty()
                ? originalTitle : sessionTitle;
        List<OptionsSheet.Item> items = new ArrayList<>();
        items.add(OptionsSheet.Item.of(R.drawable.ic_check,
                getString(R.string.edit_replace, target), () -> {
                    try {
                        Set<Long> ids = new HashSet<>();
                        ids.add(originalId);
                        TakesRepository.deleteMany(ids);
                        TakesRepository.addRecording(
                                saved, savedDur, target, label);
                        editSourceId = -1;
                        EditLink.clear(requireContext());
                        OptionsSheet.showResult(requireContext(), true,
                                getString(R.string.convert_done_title),
                                getString(R.string.edit_replaced_msg, target),
                                () -> {
                                    if (isAdded()) activity().exitStudio();
                                });
                    } catch (Exception e) {
                        OptionsSheet.showResult(requireContext(), false,
                                getString(R.string.convert_failed_title),
                                getString(R.string.couldnt_save));
                    }
                }));
        items.add(OptionsSheet.Item.of(R.drawable.ic_upload,
                getString(R.string.edit_save_new), () -> {
                    try {
                        String name = sessionTitle;
                        if (name.equals(target)) name = name + " (edit)";
                        TakesRepository.addRecording(
                                saved, savedDur, name, label);
                        editSourceId = -1;
                        EditLink.clear(requireContext());
                        OptionsSheet.showResult(requireContext(), true,
                                getString(R.string.convert_done_title),
                                getString(R.string.convert_done_msg, name, label),
                                () -> {
                                    if (isAdded()) activity().exitStudio();
                                });
                    } catch (Exception e) {
                        OptionsSheet.showResult(requireContext(), false,
                                getString(R.string.convert_failed_title),
                                getString(R.string.couldnt_save));
                    }
                }));
        OptionsSheet.showOptions(requireContext(),
                getString(R.string.edit_save_title), target, items);
    }

    private void doDiscardSequence() {
        if (saving) return;
        saving = true;
        final Context appCtx = requireContext().getApplicationContext();
        new Thread(() -> {
            String title = engine.takeName != null ? engine.takeName : "";
            boolean ok = engine.abortAndDelete();
            // Discarding an edit leaves the original library take untouched.
            editSourceId = -1;
            EditLink.clear(appCtx);
            handler.post(() -> {
                saving = false;
                if (!isAdded()) return;
                RecordingService.stopService(requireContext());
                activity().syncFabWithEngine();
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

    // ---- progress dialog ----

    private void showProgress(RecordPreset p) {
        showProgress(getString(p.titleRes));
    }

    private void showProgress(String label) {
        try {
            dismissProgress();
            progressDialog = new Dialog(requireContext(), R.style.TakeOneCenterDialog);
            progressDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            View root = LayoutInflater.from(requireContext())
                    .inflate(R.layout.dialog_progress, null);
            progressDialog.setContentView(root);
            progressBar = root.findViewById(R.id.progressBar);
            progressMessage = root.findViewById(R.id.progressMessage);
            ((TextView) root.findViewById(R.id.progressTitle))
                    .setText(getString(R.string.converting_title));
            progressMessage.setText(
                    getString(R.string.converting_msg, label, 0));
            progressDialog.setCancelable(false);
            Window w = progressDialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setDimAmount(0.62f);
            }
            progressDialog.show();
        } catch (Exception ignored) {}
    }

    private void postProgress(int pct, String label) {
        handler.post(() -> {
            try {
                if (progressBar != null) progressBar.setProgress(pct);
                if (progressMessage != null) {
                    progressMessage.setText(
                            getString(R.string.converting_msg, label, pct));
                }
            } catch (Exception ignored) {}
        });
    }

    private void dismissProgress() {
        try {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
        } catch (Exception ignored) {}
        progressDialog = null;
        progressBar = null;
        progressMessage = null;
    }

    // ---- ui ----

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (getView() == null || !isAdded()) return;
            // Always show the loaded duration once a session exists, so
            // recovery / edit punch-ins open with the correct timer.
            long ms = engine.hasSession() ? engine.durationMs() : 0;
            try {
                timerText.setText(formatTimer(ms));
                boolean live = engine.state == RecordingEngine.State.RECORDING;
                if (live != lastWaveLive) {
                    lastWaveLive = live;
                    waveform.setLive(live);
                }
                float lvl = live ? engine.level : 0f;
                if (live) waveform.setMicLevel(lvl);
                dbMeter.setLevel(lvl);
                // Teleprompter drifts upward hands-free while recording.
                if (live && telePanel.getVisibility() == View.VISIBLE) {
                    try {
                        teleScroll.smoothScrollBy(0, 2);
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            // Repaint on state/name/preset transitions (FAB morph lives in paint).
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

    private void paint() {
        if (getView() == null || !isAdded()) return;
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
        String saveName = saveFormat == SaveFormat.AUTO
                ? getString(R.string.save_auto) + " ("
                        + TakeSaver.labelFor(preset, SaveFormat.AUTO) + ")"
                : getString(saveFormat.titleRes);
        settingsSub.setText(getString(preset.subRes) + " • " + saveName);
        settingsRow.setAlpha(saving ? 0.45f : 1f);
        setTeleActive(telePanel.getVisibility() == View.VISIBLE);

        statusDot.setBackgroundResource(rec ? R.drawable.dot_live : R.drawable.bg_round_btn);
        if (rec) startDotPulse(); else stopDotPulse();
        statusDot.setAlpha(rec ? 1f : 0.35f);

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
        int n = TakesRepository.all().size() + 1;
        return "Take " + n;
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
