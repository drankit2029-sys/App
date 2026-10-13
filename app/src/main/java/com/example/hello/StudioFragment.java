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
import android.os.SystemClock;
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
    private boolean telePaused;
    private TeleSettings tele;
    /** Manual scroll override: auto-scroll stays off briefly after a drag. */
    private long teleManualUntil;
    private long teleCountdownUntil;
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
        // Touching the script lets the performer scrub it by hand. While the
        // prompter is paused this is the primary way to reposition the text;
        // auto-scroll resumes on lift.
        teleView.setOnTouchListener((v2, ev) -> {
            if (ev == null) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    noteTeleManualOverride();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // Bounded either way: an up event can be swallowed if a
                    // parent steals the gesture, and an unbounded value here
                    // would freeze auto-scroll permanently.
                    noteTeleManualOverride();
                    break;
                default:
                    break;
            }
            return false;
        });
        initTeleprompter();
        initTeleValueControls();

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

    private void initTeleprompter() {
        tele = TeleSettings.load(requireContext());
        teleView.setScript(loadTeleScript());
        applyTeleSettings();
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
            teleState.setText(active ? R.string.tele_show : R.string.tele_hide_short);
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
     * Drives the prompter: auto-scroll only while actually recording, at the
     * performer's chosen reading speed, and always yieldable to a manual
     * drag for a few seconds afterwards.
     */
    private void advanceTeleprompter() {
        if (teleView == null || tele == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!tele.autoScroll) {
            lastTeleAdvanceMs = 0L;
            return;
        }
        // A pause is sticky: it outranks recording, so the performer can hold
        // a position mid-take without the script running away from them.
        if (telePaused) {
            lastTeleAdvanceMs = 0L;
            return;
        }
        if (telePanel == null || telePanel.getVisibility() != View.VISIBLE) {
            lastTeleAdvanceMs = 0L;
            return;
        }
        if (now < teleManualUntil) {
            // Keep the clock current while yielding so auto-scroll resumes
            // without a catch-up jump when the override expires.
            lastTeleAdvanceMs = now;
            return;
        }
        if (engine.state != RecordingEngine.State.RECORDING) {
            teleCountdownUntil = 0L;
            lastTeleAdvanceMs = 0L;
            return;
        }
        float dt = lastTeleAdvanceMs > 0
                ? Math.max(0f, (now - lastTeleAdvanceMs) / 1000f)
                : 0.05f;
        // A long UI stall must not turn into one large jump when the tick
        // resumes. Cap one frame at a quarter-second of reading.
        dt = Math.min(dt, 0.25f);
        lastTeleAdvanceMs = now;
        float px = TeleMath.scrollPxPerSecond(tele.wordsPerMinute,
                teleView.effectiveWordsPerLine(),
                teleView.renderedTextSizePx());
        if (px <= 0f) return;
        teleView.scrollBy(px * dt);
    }

    private void noteTeleManualOverride() {
        long now = SystemClock.elapsedRealtime();
        teleManualUntil = now + 1500L;
        lastTeleAdvanceMs = now;
    }

    private void toggleTelePaused() {
        telePaused = !telePaused;
        lastTeleAdvanceMs = 0L;
        if (telePauseBtn != null) {
            telePauseBtn.setText(telePaused
                    ? R.string.tele_resume_short : R.string.tele_pause_short);
            telePauseBtn.setSelected(telePaused);
            PressAnim.nudge(telePauseBtn, 1.12f);
        }
        if (teleState != null) {
            teleState.setText(telePaused
                    ? R.string.tele_paused_label : R.string.tele_scrolling_label);
        }
        // Resuming hands control back to the auto-scroll clock immediately.
        if (!telePaused) teleManualUntil = 0L;
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
        teleSizeValue.configure(12, 56, 1, tele.textSizeSp, "");
        teleSpeedValue.configure(40, 400, 5, tele.wordsPerMinute, "");
        teleWplValue.configure(TeleMath.MIN_WORDS_PER_LINE,
                TeleMath.MAX_WORDS_PER_LINE, 1, tele.wordsPerLine, "");

        teleSizeValue.setOnScrubListener(new TeleValue.OnScrubListener() {
            @Override
            public void onScrubValueChanged(int val) {
                previewTeleSize(val);
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
                // Words-per-line changes the segmentation itself, so it is
                // committed only when the scrub ends.
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

    private void previewTeleSize(int textSizeSp) {
        if (teleView == null) return;
        float density = getResources().getDisplayMetrics().density;
        teleView.previewTextSizePx(textSizeSp * density);
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

    private void applyZoomValue(int percent) {
        waveform.setZoom(percent / 100f);
        paintZoomLabel();
    }

    private void refreshZoomValue() {
        float z = waveform.getZoom();
        int pct = Math.max(1, Math.round(z * 100f));
        zoomValue.configure(Math.max(1, Math.round(
                        waveform.minimumZoom() * 100f)), 400, 5, pct, "%");
        paintZoomLabel();
    }

    private void paintZoomLabel() {
        // The zoom control renders its own percentage; nothing to mirror
        // elsewhere. Kept as a hook so the gesture path stays symmetric.
    }

    /** Called when capture starts so the script begins from the top. */
    private void teleprompterArmForTake() {
        if (teleView != null) teleView.rewind();
        teleManualUntil = 0L;
        lastTeleAdvanceMs = 0L;
        // The prompter starts with the recording. A countdown is a head start
        // for the performer, not a delay before the script moves — holding
        // the text still for N seconds desynchronised the take.
        teleCountdownUntil = 0L;
    }

    // ---- waveform zoom (time shown per unit length) ----

    private void stepZoom(int dir) {
        // Gesture zoom works on the same percentage scale as the control, so
        // the two never disagree about where the zoom currently sits.
        int pct = Math.max(1, Math.round(waveform.getZoom() * 100f));
        int step = pct < 100 ? 5 : (pct < 200 ? 20 : 50);
        int next = Math.max(1, Math.min(400, pct + dir * step));
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
            // Fresh capture: rewind the prompter and start its countdown so
            // the performer is not thrown straight into mid-script.
            if (engine.state == RecordingEngine.State.RECORDING) {
                teleprompterArmForTake();
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
                // Prompter advances at the performer's reading speed while
                // recording, and stops the moment capture stops.
                advanceTeleprompter();
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
        settingsSub.setText(preset.shortLabel + " → "
                + TakeSaver.labelFor(preset, saveFormat));
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
