package com.example.hello;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private View fabWrap;
    private View fabHalo;
    private ImageView fabIcon;
    private View fabStopWrap;
    private boolean inStudio = false;
    private int fabIconRes = R.drawable.ic_mic;
    private boolean recoveryChecked;
    private long lastFabPress;
    private long lastStopPress;
    private OnBackPressedCallback backCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CrashLog.install(this);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fabWrap = findViewById(R.id.fabWrap);
        fabHalo = findViewById(R.id.fabHalo);
        fabIcon = findViewById(R.id.fabIcon);
        fabStopWrap = findViewById(R.id.fabStopWrap);

        // Smooth press animation: shrink on touch-down, spring back on release.
        fabWrap.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(120).start();
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
            }
            return false;
        });
        fabWrap.setOnClickListener(v -> {
            // Debounced: one tap, one transition. Guards against double
            // delivery that used to start-then-instantly-pause takes.
            long now = SystemClock.elapsedRealtime();
            if (now - lastFabPress < 400) return;
            lastFabPress = now;
            if (!inStudio) {
                pulseHalo();
                openStudio();
            } else {
                StudioFragment st = studio();
                if (st != null) st.onFabPressed();
            }
        });
        PressAnim.apply(fabStopWrap);
        fabStopWrap.setOnClickListener(v -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastStopPress < 400) return;
            lastStopPress = now;
            StudioFragment st = studio();
            if (st != null) st.onStopPressed();
        });

        getOnBackPressedDispatcher().addCallback(this, backCallback =
                new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (inStudio) {
                    StudioFragment st = studio();
                    if (st != null) {
                        st.onBackPressed();
                    } else {
                        exitStudio();
                    }
                } else {
                    Fragment f = getSupportFragmentManager().findFragmentByTag("LIB");
                    boolean consumed = f instanceof LibraryFragment
                            && ((LibraryFragment) f).onBackPressed();
                    if (!consumed) {
                        finish();
                    }
                }
            }
        });

        if (savedInstanceState != null) {
            inStudio = savedInstanceState.getBoolean("inStudio", false);
            recoveryChecked = savedInstanceState.getBoolean("recoveryChecked", false);
        } else {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container, new LibraryFragment(), "LIB")
                    .commitAllowingStateLoss();
        }

        handleOpenStudioIntent(getIntent());
        // Library loads async (Room + disk reconcile). Recovery + first paint
        // wait for it so prompts see adopted orphans and pruned rows.
        TakesRepository.init(this);
        TrialAndBillingManager.get(this).connectAndSync();
        TakesRepository.onReady(() -> {
            if (!recoveryChecked) {
                recoveryChecked = true;
                fabWrap.post(this::checkUnfinishedTakes);
            } else {
                fabWrap.post(this::refreshLibraryIfShown);
            }
            maybeAnnounceRecovered();
            maybeHealCrashedEncoder();
            maybeShowCrashReport();
        });
        syncFabWithEngine();
    }

    /**
     * Self-healing after an encoder kills a save: the breadcrumbs name the
     * exact format that never finished. Step one rung down the reliability
     * ladder (exotic -> AAC -> pure-Java WAV) so the next recording can
     * actually save on this device.
     */
    private void maybeHealCrashedEncoder() {
        String stage;
        try {
            stage = CrashLog.unterminatedStage(this);
        } catch (Exception e) {
            return;
        }
        if (stage == null || !stage.startsWith("convert ") || inStudio) return;
        String crashedId = stage.substring("convert ".length()).trim();
        SaveFormat crashed = SaveFormat.of(crashedId);
        SaveFormat current = SaveFormat.load(this);
        SaveFormat currentEff = current.effective(RecordPreset.load(this));
        // Only act while the user still has the killer selected: a manual
        // switch since the crash is respected, not overridden.
        if (currentEff != crashed) return;
        SaveFormat fallback = crashed.fallbackAfterCrash();
        if (fallback == null) return;
        try {
            fallback.save(this);
            CrashLog.breadcrumb(this,
                    "auto-switch " + crashed.id + "->" + fallback.id);
        } catch (Exception ignored) {}
        // A Java crash file already tells the story (breadcrumbs included):
        // don't stack a second dialog on top of its share prompt.
        boolean crashFile;
        try {
            crashFile = CrashLog.recentCrash(this) != null;
        } catch (Exception e) {
            crashFile = false;
        }
        if (crashFile) return;
        final String from = crashed.badge;
        final String to = fallback.badge;
        fabWrap.post(() -> {
            if (inStudio) return;
            try {
                OptionsSheet.showResult(this, false,
                        getString(R.string.convert_crash_title),
                        getString(R.string.convert_crash_msg, from, to),
                        () -> { });
            } catch (Exception ignored) {}
        });
    }

    /** One surfacing of the previous run's crash log, with share + dismiss. */
    private void maybeShowCrashReport() {
        File crash = CrashLog.recentCrash(this);
        if (crash == null || inStudio) return;
        // Stale reports auto-expire: only a fresh crash nags, and sharing
        // clears it. Cancelling keeps it for the next launch (bounded by
        // the same freshness window).
        if (System.currentTimeMillis() - crash.lastModified()
                > 7L * 24 * 3600_000L) {
            CrashLog.clearCrash(this);
            return;
        }
        fabWrap.post(() -> {
            if (inStudio) return;
            try {
                OptionsSheet.showConfirm(this,
                        getString(R.string.crash_title),
                        getString(R.string.crash_msg),
                        getString(R.string.crash_share),
                        R.drawable.ic_close, false,
                        () -> {
                            CrashLog.share(this, crash);
                            CrashLog.clearCrash(this);
                        });
            } catch (Exception ignored) {}
        });
    }

    /** One summary popup when the startup sweep adopted orphaned files. */
    private void maybeAnnounceRecovered() {
        TakesRepository.ReconcileResult rr = TakesRepository.lastReconcile();
        if (rr == null || rr.adopted <= 0 || inStudio) return;
        // Posted: dialogs need a resumed activity, and onReady can fire
        // synchronously inside onCreate after a rotation.
        fabWrap.post(() -> {
            if (inStudio) return;
            try {
                OptionsSheet.showTopPopup(this, "Takes recovered");
            } catch (Exception ignored) {}
        });
    }

    private void refreshLibraryIfShown() {
        try {
            Fragment f = getSupportFragmentManager().findFragmentByTag("LIB");
            if (f instanceof LibraryFragment && f.isVisible()) {
                ((LibraryFragment) f).refreshExternal();
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenStudioIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (backCallback != null) backCallback.setEnabled(true);
        syncFabWithEngine();
        for (ConversionJob j : ConversionJobs.active()) {
            if (ConversionJob.QUEUED.equals(j.state)) {
                ConversionService.startJob(this, j.jobId);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("inStudio", inStudio);
        outState.putBoolean("recoveryChecked", recoveryChecked);
    }

    private void handleOpenStudioIntent(Intent intent) {
        if (intent == null) return;
        boolean open = intent.getBooleanExtra("open_studio", false)
                || "com.example.hello.OPEN_STUDIO".equals(intent.getAction());
        if (open && RecordingEngine.get().hasSession() && !inStudio) {
            openStudio();
        }
        try {
            intent.removeExtra("open_studio");
            intent.setAction(null);
        } catch (Exception ignored) {}
    }

    public void openStudio() {
        inStudio = true;
        syncFabWithEngine();
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.fade_out,
                        R.anim.fade_in, R.anim.slide_out_right)
                .replace(R.id.container, new StudioFragment(), "STU")
                .commitAllowingStateLoss();
    }

    public void openStudioWithRecovery(File rawFile) {
        // Crash recovery reopens directly in the recording state.
        openStudioWithSession(rawFile.getAbsolutePath(), true, -1, null);
    }

    /** Edit punch-in: session pre-loaded, timer correct, waits for mic tap. */
    public void openStudioWithEdit(File rawFile, long sourceId, String sourceTitle) {
        openStudioWithSession(rawFile.getAbsolutePath(), false, sourceId, sourceTitle);
    }

    private void openStudioWithSession(String rawPath, boolean autostart,
                                       long editSourceId, String editSourceTitle) {
        inStudio = true;
        syncFabWithEngine();
        StudioFragment f = new StudioFragment();
        Bundle b = new Bundle();
        b.putString("recover_raw", rawPath);
        b.putBoolean("autostart", autostart);
        if (editSourceId != -1) {
            b.putLong("edit_source_id", editSourceId);
            b.putString("edit_source_title", editSourceTitle);
        }
        f.setArguments(b);
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.fade_out,
                        R.anim.fade_in, R.anim.slide_out_right)
                .replace(R.id.container, f, "STU")
                .commitAllowingStateLoss();
    }

    public void exitStudio() {
        inStudio = false;
        syncFabWithEngine();
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.fade_in, R.anim.slide_out_right,
                        R.anim.slide_in_right, R.anim.fade_out)
                .replace(R.id.container, new LibraryFragment(), "LIB")
                .commitAllowingStateLoss();
    }

    // ---- split record controls ----

    /**
     * Main FAB artwork morph (mic / pause / play) with a smooth shrink-swap-pop.
     * Scale-only: the old rotation accumulated 70° on every tap, visibly
     * twisting the icon over time. The split stop button is shown separately
     * while a take is active.
     */
    public void morphFabIcon(int resId) {
        if (fabIconRes == resId) return;
        fabIconRes = resId;
        fabIcon.animate().cancel();
        // Snap rotation back first so repeated morphs can never accumulate.
        fabIcon.setRotation(0f);
        fabIcon.animate()
                .scaleX(0.35f).scaleY(0.35f)
                .setDuration(110)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .withEndAction(() -> {
                    try {
                        fabIcon.setImageResource(resId);
                    } catch (Exception ignored) {}
                    fabIcon.setRotation(0f);
                    fabIcon.animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(230)
                            .setInterpolator(new OvershootInterpolator(2f))
                            .start();
                })
                .start();
    }

    /** Show/hide the split stop button with a pop. */
    public void setStopVisible(boolean visible) {
        if (visible && fabStopWrap.getVisibility() != View.VISIBLE) {
            fabStopWrap.setVisibility(View.VISIBLE);
            PressAnim.pop(fabStopWrap);
        } else if (!visible && fabStopWrap.getVisibility() == View.VISIBLE) {
            fabStopWrap.animate().cancel();
            fabStopWrap.animate()
                    .scaleX(0.4f).scaleY(0.4f)
                    .alpha(0f)
                    .setDuration(150)
                    .withEndAction(() -> {
                        fabStopWrap.setVisibility(View.GONE);
                        fabStopWrap.setScaleX(1f);
                        fabStopWrap.setScaleY(1f);
                        fabStopWrap.setAlpha(1f);
                    })
                    .start();
        }
    }

    /**
     * Drive both FABs from the engine state. Called on studio transitions and
     * on resume so a background recording re-attaches its controls.
     */
    public void syncFabWithEngine() {
        RecordingEngine e = RecordingEngine.get();
        boolean active = inStudio && e.hasSession()
                && (e.state == RecordingEngine.State.RECORDING
                || e.state == RecordingEngine.State.PAUSED);
        boolean paused = e.state == RecordingEngine.State.PAUSED;
        if (active) {
            morphFabIcon(paused ? R.drawable.ic_play : R.drawable.ic_pause);
            setStopVisible(true);
            fabWrap.setContentDescription(
                    getString(paused ? R.string.action_resume : R.string.action_pause));
        } else {
            morphFabIcon(inStudio ? R.drawable.ic_mic : R.drawable.ic_mic);
            setStopVisible(false);
            fabWrap.setContentDescription(getString(R.string.cd_mic));
        }
    }

    /** Legacy hook: true once meant square-stop artwork; now drives split UI. */
    public void setFabStop(boolean stop) {
        syncFabWithEngine();
    }

    // ---- unfinished-take recovery (every app start) ----

    private void checkUnfinishedTakes() {
        // Never interrupt an attached studio session with its own UI.
        if (inStudio) return;
        // If the engine already holds a session (background recording), the
        // notification owns it — just offer to reopen the studio.
        if (RecordingEngine.get().hasSession()) {
            return;
        }
        List<RecordingEngine.Unfinished> found;
        try {
            found = RecordingEngine.scanUnfinished(this);
        } catch (Exception e) {
            return;
        }
        if (found == null || found.isEmpty()) return;
        promptRecovery(0, found);
    }

    private void promptRecovery(int index, List<RecordingEngine.Unfinished> all) {
        if (index >= all.size()) return;
        RecordingEngine.Unfinished u = all.get(index);
        String dur = String.format(Locale.US, "%02d:%02d",
                (u.durationMs / 60000), (u.durationMs / 1000) % 60);
        try {
            OptionsSheet.showTwoChoiceModal(this,
                    getString(R.string.unfinished_title),
                    getString(R.string.unfinished_msg, u.name, dur),
                    OptionsSheet.Item.danger(R.drawable.ic_trash,
                            "Discard", () -> {
                                boolean ok = RecordingEngine.deleteUnfinished(u.rawFile);
                                OptionsSheet.showResult(this, ok,
                                        ok ? "Take discarded" : "Delete failed",
                                        null,
                                        () -> promptRecovery(index + 1, all));
                            }),
                    OptionsSheet.Item.of(R.drawable.ic_play,
                            "Continue", () -> {
                                try {
                                    RecordingEngine.get().attachExisting(
                                            getApplicationContext(),
                                            u.rawFile, u.name, u.channels,
                                            RecordingEngine.payloadBytesOf(u.rawFile));
                                } catch (Exception e) {
                                    OptionsSheet.showResult(this, false,
                                            "Open failed", null);
                                    promptRecovery(index + 1, all);
                                    return;
                                }
                                openStudioWithRecovery(u.rawFile);
                            }));
        } catch (Exception ignored) {}
    }

    private StudioFragment studio() {
        Fragment f = getSupportFragmentManager().findFragmentByTag("STU");
        return (f instanceof StudioFragment && f.isVisible()) ? (StudioFragment) f : null;
    }

    private void pulseHalo() {
        fabHalo.animate().scaleX(1.22f).scaleY(1.22f).setDuration(150)
                .withEndAction(() -> fabHalo.animate().scaleX(1f).scaleY(1f)
                        .setDuration(220).start())
                .start();
    }
}
