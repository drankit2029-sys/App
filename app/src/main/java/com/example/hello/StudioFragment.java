package com.example.hello;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
import java.util.List;
import java.util.Locale;

/**
 * Name-first studio on top of the crash-safe 32-bit float engine.
 *
 * Flow: open -> name modal (no recording yet) -> tap mic to start (FAB morphs
 * into pause/play + split stop) -> stop asks save/discard -> convert to the
 * chosen format with a live progress bar -> back to the library.
 */
public class StudioFragment extends Fragment {

    private enum Fmt {
        RAW32("raw32"), M4A("m4a"), WAV16("wav16");
        final String id;
        Fmt(String id) { this.id = id; }
        static Fmt of(String id) {
            if ("raw32".equals(id)) return RAW32;
            if ("wav16".equals(id)) return WAV16;
            return M4A;
        }
    }

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
    private TextView fmtRaw32;
    private TextView fmtM4a;
    private TextView fmtWav16;

    private String pendingName;
    private Fmt format = Fmt.M4A;
    private boolean nameAsked;
    private boolean saving; // save/discard sequence running
    private RecordingEngine.State lastPaintedState;
    private String lastPaintedName = "\0";
    private boolean lastWaveLive;

    private Dialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressMessage;

    private AudioManager audioManager;
    private AudioManager.OnAudioFocusChangeListener focusListener;
    private boolean focusHeld;

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
        fmtRaw32 = v.findViewById(R.id.fmtRaw32);
        fmtM4a = v.findViewById(R.id.fmtM4a);
        fmtWav16 = v.findViewById(R.id.fmtWav16);

        format = Fmt.of(loadFormatId());
        audioManager = (AudioManager) requireContext()
                .getSystemService(Context.AUDIO_SERVICE);

        v.findViewById(R.id.backBtn).setOnClickListener(view -> onBackPressed());
        PressAnim.apply(v.findViewById(R.id.backBtn));

        attachRenameGestures(takeNameView);
        attachRenameGestures(statusText);

        fmtRaw32.setOnClickListener(view -> selectFormat(Fmt.RAW32));
        fmtM4a.setOnClickListener(view -> selectFormat(Fmt.M4A));
        fmtWav16.setOnClickListener(view -> selectFormat(Fmt.WAV16));
        PressAnim.apply(fmtRaw32);
        PressAnim.apply(fmtM4a);
        PressAnim.apply(fmtWav16);

        // Recovery (from MainActivity prompt) or a background session being
        // re-opened: attach, never show the name modal.
        Bundle args = getArguments();
        boolean attached = false;
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
                String title = intent.getStringExtra(RecordingService.EXTRA_TITLE);
                String kind = intent.getStringExtra(RecordingService.EXTRA_KIND);
                onBackgroundActionDone(ok, "cancel".equals(kind), title, msg);
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

    // ---- called by MainActivity's shared buttons ----

    /** Main FAB: name -> start -> pause/resume. */
    public void onFabPressed() {
        if (saving) return;
        if (!engine.hasSession() && pendingName == null) {
            askForName();
            return;
        }
        if (engine.state == RecordingEngine.State.RECORDING) {
            pauseTake(false);
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
                getString(R.string.save_take_msg, name, formatLabel(format)),
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
        try {
            if (!engine.hasSession()) {
                String name = pendingName != null ? pendingName : nextTakeName();
                engine.startNewSession(requireContext(), name);
                pendingName = engine.takeName;
            }
            requestFocus();
            if (engine.state == RecordingEngine.State.PAUSED) {
                engine.resumeCapture(requireContext());
            } else {
                engine.startCapture(requireContext());
            }
            RecordingService.start(requireContext(), format.id);
        } catch (Exception e) {
            abandonFocus();
            Toast.makeText(requireContext(), "Could not start recording",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        handler.post(tick);
        paint();
        activity().syncFabWithEngine();
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
        try {
            requestFocus();
            engine.resumeCapture(requireContext());
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Could not resume",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        handler.post(tick);
        paint();
        activity().syncFabWithEngine();
    }

    // ---- stop: save (convert) or discard ----

    private void doSaveSequence() {
        if (saving || !engine.hasSession()) return;
        saving = true;
        showProgress(format);
        final Fmt fmt = format;
        final String title = engine.takeName != null ? engine.takeName : "";
        new Thread(() -> {
            boolean ok;
            String msg;
            try {
                File raw = engine.finalizeRaw();
                if (raw == null || !raw.exists()) throw new IllegalStateException("empty");
                // Duration comes from the raw capture (M4A output has no WAV header).
                long dur = Math.max(durationOf(raw), 1000);
                File fin;
                String label;
                if (fmt == Fmt.RAW32) {
                    fin = raw;
                    label = "WAV";
                    postProgress(100, label);
                } else if (fmt == Fmt.WAV16) {
                    fin = uniqueFinal(raw, "wav");
                    label = "WAV";
                    FormatConverter.toWav16(raw, fin, pct -> postProgress(pct, label));
                    if (fin.exists()) {
                        // noinspection ResultOfMethodCallIgnored
                        raw.delete();
                    }
                } else {
                    fin = uniqueFinal(raw, "m4a");
                    label = "M4A";
                    FormatConverter.toM4a(raw, fin, pct -> postProgress(pct, label));
                    if (fin.exists()) {
                        // noinspection ResultOfMethodCallIgnored
                        raw.delete();
                    }
                }
                TakesRepository.addRecording(
                        fin, Math.max(dur, 1000), title, label);
                engine.clearSession();
                abandonFocus();
                ok = true;
                msg = getString(R.string.convert_done_msg, title, label);
            } catch (Exception e) {
                ok = false;
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

    private void doDiscardSequence() {
        if (saving) return;
        saving = true;
        new Thread(() -> {
            String title = engine.takeName != null ? engine.takeName : "";
            boolean ok = engine.abortAndDelete();
            abandonFocus();
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
    private void onBackgroundActionDone(boolean ok, boolean wasCancel,
                                        String title, String msg) {
        if (!isAdded() || getView() == null) return;
        if (!engine.hasSession()) {
            abandonFocus();
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

    // ---- audio focus / interruptions: pause, never auto-resume ----

    private void requestFocus() {
        try {
            if (focusHeld || audioManager == null) return;
            focusListener = focus -> {
                if (focus == AudioManager.AUDIOFOCUS_LOSS
                        || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                        || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    handler.post(() -> pauseTake(true));
                }
            };
            int r = audioManager.requestAudioFocus(focusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            focusHeld = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Exception ignored) {}
    }

    private void abandonFocus() {
        try {
            if (audioManager != null && focusListener != null) {
                audioManager.abandonAudioFocus(focusListener);
            }
        } catch (Exception ignored) {}
        focusListener = null;
        focusHeld = false;
    }

    // ---- format options (before recording; locked while capturing) ----

    private void selectFormat(Fmt f) {
        if (engine.state == RecordingEngine.State.RECORDING) {
            try {
                // Locked mid-take: nudge instead of switching.
                View v = viewFor(f);
                if (v != null) PressAnim.nudge(v, 1.06f);
                Toast.makeText(requireContext(), R.string.studio_recording,
                        Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
            return;
        }
        format = f;
        saveFormatId(f.id);
        paint();
    }

    private View viewFor(Fmt f) {
        switch (f) {
            case RAW32: return fmtRaw32;
            case WAV16: return fmtWav16;
            default: return fmtM4a;
        }
    }

    private String loadFormatId() {
        try {
            SharedPreferences p = requireContext()
                    .getSharedPreferences("takeone", Context.MODE_PRIVATE);
            return p.getString("rec_format", "m4a");
        } catch (Exception e) {
            return "m4a";
        }
    }

    private void saveFormatId(String id) {
        try {
            requireContext().getSharedPreferences("takeone", Context.MODE_PRIVATE)
                    .edit().putString("rec_format", id).apply();
        } catch (Exception ignored) {}
    }

    private String formatLabel(Fmt f) {
        switch (f) {
            case RAW32: return getString(R.string.fmt_raw32);
            case WAV16: return getString(R.string.fmt_wav16);
            default: return getString(R.string.fmt_m4a);
        }
    }

    private String formatShort(Fmt f) {
        switch (f) {
            case RAW32: return "RAW • 32-bit float";
            case WAV16: return "WAV • 16-bit";
            default: return "M4A • AAC";
        }
    }

    // ---- progress dialog ----

    private void showProgress(Fmt fmt) {
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
                    getString(R.string.converting_msg, formatLabel(fmt), 0));
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
            boolean active = engine.hasSession()
                    && (engine.state == RecordingEngine.State.RECORDING
                    || engine.state == RecordingEngine.State.PAUSED);
            long ms = active ? engine.durationMs() : 0;
            try {
                timerText.setText(formatTimer(ms));
                boolean live = engine.state == RecordingEngine.State.RECORDING;
                if (live != lastWaveLive) {
                    lastWaveLive = live;
                    waveform.setLive(live);
                }
                if (live) waveform.setMicLevel(engine.level);
            } catch (Exception ignored) {}
            // Repaint on state/name transitions (FAB morph lives in paint).
            if (engine.state != lastPaintedState
                    || !String.valueOf(displayName()).equals(lastPaintedName)) {
                paint();
            }
            handler.postDelayed(this, 100);
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
            hintText.setText(R.string.studio_recovered);
        }

        // Unfinalised indicator: the pending marker means bytes are streamed
        // but the take is not finalised yet.
        boolean unfin = hasSession && isUnfinalised();
        draftBadge.setVisibility(unfin ? View.VISIBLE : View.GONE);

        waveform.setLive(rec);

        formatBadge.setText(formatShort(format));
        paintFormatChip(fmtRaw32, format == Fmt.RAW32);
        paintFormatChip(fmtM4a, format == Fmt.M4A);
        paintFormatChip(fmtWav16, format == Fmt.WAV16);
        boolean fmtLocked = rec;
        float alpha = fmtLocked ? 0.45f : 1f;
        fmtRaw32.setAlpha(alpha);
        fmtM4a.setAlpha(alpha);
        fmtWav16.setAlpha(alpha);

        statusDot.setBackgroundResource(rec ? R.drawable.dot_live : R.drawable.bg_round_btn);
        if (rec) startDotPulse(); else stopDotPulse();
        statusDot.setAlpha(rec ? 1f : 0.35f);

        try {
            activity().syncFabWithEngine();
        } catch (Exception ignored) {}
    }

    private void paintFormatChip(TextView chip, boolean active) {
        chip.setBackgroundResource(active
                ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
        chip.setTextColor(active ? 0xFFFFFFFF : 0xFF9AA3AF);
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

    private static String formatTimer(long ms) {
        long tenths = (ms / 100) % 10;
        long s = (ms / 1000) % 60;
        long m = (ms / 60000);
        return String.format(Locale.US, "%02d:%02d.%d", m, s, tenths);
    }

    private MainActivity activity() {
        return (MainActivity) requireActivity();
    }

    @Override
    public void onDestroy() {
        // Only release focus when no session survives; a background take
        // keeps its interruption listener alive via the service-owned engine.
        if (!engine.hasSession()) abandonFocus();
        super.onDestroy();
    }
}
