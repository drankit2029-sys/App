package com.example.hello;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class StudioFragment extends Fragment {

    private enum State { IDLE, RECORDING, PAUSED }

    private State state = State.IDLE;
    private MediaRecorder recorder;
    private File outFile;
    private long startedAt;
    private long accumulated;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ObjectAnimator dotPulse;

    private TextView timerText;
    private TextView statusText;
    private TextView stateLabel;
    private TextView takeName;
    private TextView hintText;
    private TextView pauseLabel;
    private View statusDot;
    private WaveformView waveform;
    private FrameLayout pauseBtn;
    private ImageView pauseIcon;
    private FrameLayout discardBtn;

    private ActivityResultLauncher<String> micPermission;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        micPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        startRecording();
                    } else {
                        Toast.makeText(requireContext(), R.string.msg_need_mic,
                                Toast.LENGTH_LONG).show();
                    }
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
        takeName = v.findViewById(R.id.takeName);
        hintText = v.findViewById(R.id.hintText);
        pauseLabel = v.findViewById(R.id.pauseLabel);
        statusDot = v.findViewById(R.id.statusDot);
        waveform = v.findViewById(R.id.waveform);
        pauseBtn = v.findViewById(R.id.pauseBtn);
        pauseIcon = v.findViewById(R.id.pauseIcon);
        discardBtn = v.findViewById(R.id.discardBtn);

        takeName.setText(nextTakeName());
        waveform.setLive(false);

        v.findViewById(R.id.backBtn).setOnClickListener(view -> onBackPressed());
        pauseBtn.setOnClickListener(view -> togglePause());
        discardBtn.setOnClickListener(view -> confirmDiscard());
        paint();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        stopDotPulse();
        if (state != State.IDLE) {
            // Fragment going away mid-take: keep file only if user kept it; otherwise clean up.
            abortRecording();
        }
        super.onDestroyView();
    }

    // ---- called by MainActivity's shared mic button ----

    public void onFabPressed() {
        if (state == State.IDLE) {
            ensurePermissionAndRecord();
        } else {
            keepAndExit();
        }
    }

    /** @return true if the press was consumed (dialog shown / handled). */
    public boolean onBackPressed() {
        if (state == State.IDLE) {
            activity().exitStudio();
            return true;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("End this take?")
                .setMessage("Keep the recording in your Library, or discard it?")
                .setPositiveButton(R.string.action_keep, (d, w) -> keepAndExit())
                .setNegativeButton(R.string.action_discard, (d, w) -> {
                    abortRecording();
                    Toast.makeText(requireContext(), R.string.msg_discarded,
                            Toast.LENGTH_SHORT).show();
                    activity().exitStudio();
                })
                .setNeutralButton("Cancel", null)
                .show();
        return true;
    }

    // ---- recording ----

    private void ensurePermissionAndRecord() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void startRecording() {
        try {
            outFile = newTakeFile();
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(48000);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioChannels(2);
            recorder.setOutputFile(outFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
        } catch (Exception e) {
            cleanupRecorder();
            Toast.makeText(requireContext(), "Could not start recording",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        state = State.RECORDING;
        startedAt = SystemClock.elapsedRealtime();
        accumulated = 0;
        takeName.setText(prettyName(outFile));
        handler.post(tick);
        paint();
        activity().setFabStop(true);
    }

    private void togglePause() {
        if (state == State.RECORDING) {
            try {
                recorder.pause();
            } catch (Exception e) {
                Toast.makeText(requireContext(), "Pause not supported on this device",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            accumulated += SystemClock.elapsedRealtime() - startedAt;
            state = State.PAUSED;
            paint();
        } else if (state == State.PAUSED) {
            try {
                recorder.resume();
            } catch (Exception e) {
                Toast.makeText(requireContext(), "Could not resume",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            startedAt = SystemClock.elapsedRealtime();
            state = State.RECORDING;
            paint();
        }
    }

    private void keepAndExit() {
        long duration = currentDuration();
        stopRecorderOk();
        if (outFile != null && outFile.exists()) {
            TakesRepository.addRecording(outFile,
                    Math.max(duration, 1000), new Date().toString());
            Toast.makeText(requireContext(), R.string.msg_saved, Toast.LENGTH_SHORT).show();
        }
        resetUi();
        activity().exitStudio();
    }

    private void confirmDiscard() {
        if (state == State.IDLE) return;
        new AlertDialog.Builder(requireContext())
                .setTitle("Discard take?")
                .setMessage("This recording will be deleted.")
                .setPositiveButton(R.string.action_discard, (d, w) -> {
                    abortRecording();
                    resetUi();
                    Toast.makeText(requireContext(), R.string.msg_discarded,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void abortRecording() {
        try {
            if (recorder != null) recorder.stop();
        } catch (Exception ignored) {}
        cleanupRecorder();
        if (outFile != null) {
            // noinspection ResultOfMethodCallIgnored
            outFile.delete();
            outFile = null;
        }
        handler.removeCallbacks(tick);
    }

    private void stopRecorderOk() {
        handler.removeCallbacks(tick);
        try {
            if (recorder != null) recorder.stop();
        } catch (Exception ignored) {}
        cleanupRecorder();
    }

    private void cleanupRecorder() {
        if (recorder != null) {
            try {
                recorder.release();
            } catch (Exception ignored) {}
            recorder = null;
        }
    }

    private void resetUi() {
        state = State.IDLE;
        outFile = null;
        accumulated = 0;
        timerText.setText("00:00.0");
        waveform.setLive(false);
        waveform.setMicLevel(0f);
        takeName.setText(nextTakeName());
        paint();
        activity().setFabStop(false);
    }

    private long currentDuration() {
        if (state == State.RECORDING) {
            return accumulated + (SystemClock.elapsedRealtime() - startedAt);
        }
        return accumulated;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (state == State.IDLE) return;
            long ms = currentDuration();
            timerText.setText(formatTimer(ms));
            if (recorder != null && state == State.RECORDING) {
                try {
                    int amp = recorder.getMaxAmplitude();
                    waveform.setMicLevel(amp / 32768f);
                } catch (Exception ignored) {}
            }
            handler.postDelayed(this, 100);
        }
    };

    private static String formatTimer(long ms) {
        long tenths = (ms / 100) % 10;
        long s = (ms / 1000) % 60;
        long m = (ms / 60000);
        return String.format(Locale.US, "%02d:%02d.%d", m, s, tenths);
    }

    // ---- ui state ----

    private void paint() {
        boolean active = state != State.IDLE;
        boolean rec = state == State.RECORDING;
        waveform.setLive(rec);
        statusText.setText(state == State.IDLE ? getString(R.string.studio_ready)
                : rec ? getString(R.string.studio_recording) : getString(R.string.studio_paused));
        stateLabel.setText(state == State.IDLE ? getString(R.string.studio_ready)
                : rec ? getString(R.string.studio_recording) : getString(R.string.studio_paused));
        hintText.setText(state == State.IDLE ? getString(R.string.studio_hint)
                : rec ? "Tap the mic to keep this take" : "Paused — resume or keep");
        pauseBtn.setAlpha(active ? 1f : 0.4f);
        pauseBtn.setClickable(active);
        discardBtn.setAlpha(active ? 1f : 0.4f);
        discardBtn.setClickable(active);
        pauseIcon.setImageResource(state == State.PAUSED ? R.drawable.ic_play : R.drawable.ic_pause);
        pauseLabel.setText(state == State.PAUSED ? getString(R.string.action_resume)
                : getString(R.string.action_pause));
        statusDot.setBackgroundResource(rec ? R.drawable.dot_live : R.drawable.bg_round_btn);
        if (rec) startDotPulse(); else stopDotPulse();
        statusDot.setAlpha(rec ? 1f : (state == State.PAUSED ? 1f : 0.35f));
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

    private File takesDir() {
        File base = requireContext().getExternalFilesDir(null);
        if (base == null) base = requireContext().getFilesDir();
        File dir = new File(base, "takes");
        // noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private File newTakeFile() {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return new File(takesDir(), "Take_" + stamp + ".m4a");
    }

    private String nextTakeName() {
        int n = TakesRepository.all().size() + 1;
        return "Take " + n;
    }

    private static String prettyName(File f) {
        return f.getName().replaceFirst("\\.[^.]+$", "").replace('_', ' ');
    }

    private MainActivity activity() {
        return (MainActivity) requireActivity();
    }
}
