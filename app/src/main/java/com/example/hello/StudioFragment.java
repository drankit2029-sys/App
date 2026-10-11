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
    private View presetRow;
    private TextView presetName;
    private TextView presetSub;

    private String pendingName;
    private RecordPreset preset = RecordPreset.STANDARD;
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
        presetRow = v.findViewById(R.id.presetRow);
        presetName = v.findViewById(R.id.presetName);
        presetSub = v.findViewById(R.id.presetSub);

        preset = RecordPreset.load(requireContext());
        audioManager = (AudioManager) requireContext()
                .getSystemService(Context.AUDIO_SERVICE);

        v.findViewById(R.id.backBtn).setOnClickListener(view -> onBackPressed());
        PressAnim.apply(v.findViewById(R.id.backBtn));

        attachRenameGestures(takeNameView);
        attachRenameGestures(statusText);

        presetRow.setOnClickListener(view -> showPresetPicker());
        PressAnim.apply(presetRow);

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
                nameAsked = true;
                attached = true;
            } catch (Exception e) {
                pendingName = null;
            }
            args.remove("recover_raw");
        }
        if (!attached && engine.hasSession()) {
            pendingName = engine.takeName;
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
            if (Build.VERSION.SDK_INT >= 33) {
                requireContext().registerReceiver(
                        doneReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                requireContext().registerReceiver(doneReceiver, f);
            }
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
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        stopDotPulse();
        dismissProgress();
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

    // ---- preset dropdown ----

    private void showPresetPicker() {
        if (!isAdded()) return;
        if (engine.state == RecordingEngine.State.RECORDING) {
            try {
                PressAnim.nudge(presetRow, 1.02f);
                Toast.makeText(requireContext(), R.string.preset_locked,
                        Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
            return;
        }
        List<OptionsSheet.Item> items = new ArrayList<>();
        for (RecordPreset p : RecordPreset.all()) {
            final RecordPreset pick = p;
            items.add(new OptionsSheet.Item(0, getString(p.titleRes)
                    + "  •  " + getString(p.subRes), false, p == preset,
                    () -> {
                        preset = pick;
                        preset.save(requireContext());
                        paint();
                    }));
        }
        OptionsSheet.showOptions(requireContext(),
                getString(R.string.preset_title), micCapabilityLine(), items);
    }

    private String micCapabilityLine() {
        int n = MicSupport.physicalMicCount(audioManager);
        if (n >= 2) return getString(R.string.mic_stereo_ok, n);
        if (n == 1) return getString(R.string.mic_mono_only);
        return getString(R.string.mic_unknown);
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
        final String title = engine.takeName != null ? engine.takeName : "";
        final long editId = editSourceId;
        final String editTitle = editSourceTitle;
        new Thread(() -> {
            boolean ok;
            String msg;
            String label;
            File fin = null;
            try {
                File raw = engine.finalizeRaw();
                if (raw == null || !raw.exists()) throw new IllegalStateException("empty");
                // Duration comes from the raw capture (M4A output has no WAV header).
                long dur = Math.max(durationOf(raw), 1000);
                final String convLabel = p.savesWav ? "WAV" : "M4A";
                if (p.savesWav) {
                    fin = uniqueFinal(raw, "wav");
                    label = "WAV";
                    FormatConverter.toWav16(raw, fin, pct -> postProgress(pct, convLabel));
                    if (fin.exists()) {
                        // noinspection ResultOfMethodCallIgnored
                        raw.delete();
                    }
                } else {
                    fin = uniqueFinal(raw, "m4a");
                    label = "M4A";
                    FormatConverter.toM4a(raw, fin, p.aacBitrate,
                            pct -> postProgress(pct, convLabel));
                    if (fin.exists()) {
                        // noinspection ResultOfMethodCallIgnored
                        raw.delete();
                    }
                }
                if (editId == -1) {
                    TakesRepository.addRecording(
                            fin, Math.max(dur, 1000), title, label);
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
                    handler.post(() -> {
                        saving = false;
                        dismissProgress();
                        if (!isAdded()) return;
                        RecordingService.stopService(requireContext());
                        activity().syncFabWithEngine();
                        askEditReplaceOrNew(editId, editTitle, title,
                                saved, savedDur, savedLabel);
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
        new Thread(() -> {
            String title = engine.takeName != null ? engine.takeName : "";
            boolean ok = engine.abortAndDelete();
            // Discarding an edit leaves the original library take untouched.
            editSourceId = -1;
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

        formatBadge.setText(preset.shortLabel);
        presetName.setText(preset.titleRes);
        presetSub.setText(preset.subRes);
        presetRow.setAlpha(rec ? 0.45f : 1f);

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

    private static File uniqueFinal(File raw, String ext) {
        File dir = raw.getParentFile();
        String base = raw.getName().replaceFirst("\\.[^.]+$", "");
        File c = new File(dir, base + "." + ext);
        if (!c.exists()) return c;
        int i = 2;
        while (true) {
            File n = new File(dir, base + " (" + i + ")." + ext);
            if (!n.exists()) return n;
            i++;
        }
    }

    private static long durationOf(File f) {
        try {
            FormatConverter.WavInfo info = FormatConverter.readFloatWav(f);
            long samples = info.dataBytes / 4 / Math.max(1, info.channels);
            return samples * 1000L / Math.max(1, info.sampleRate);
        } catch (Exception e) {
            return 1000;
        }
    }

    /** Centisecond timer (one more decimal place than before). */
    static String formatTimer(long ms) {
        long cs = (ms % 1000) / 10;
        long s = (ms / 1000) % 60;
        long m = (ms / 60000);
        return String.format(Locale.US, "%02d:%02d.%02d", m, s, cs);
    }

    private MainActivity activity() {
        return (MainActivity) requireActivity();
    }
}
