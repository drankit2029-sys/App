package com.example.hello;

import android.content.Intent;
import android.os.Bundle;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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
            StudioFragment st = studio();
            if (st != null) st.onStopPressed();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
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
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
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
                    .commit();
        }

        handleOpenStudioIntent(getIntent());
        if (!recoveryChecked) {
            recoveryChecked = true;
            // Defer until after first frame so modals show over a live UI.
            fabWrap.post(this::checkUnfinishedTakes);
        }
        syncFabWithEngine();
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
        syncFabWithEngine();
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
                .commit();
    }

    public void openStudioWithRecovery(File rawFile) {
        inStudio = true;
        syncFabWithEngine();
        StudioFragment f = new StudioFragment();
        Bundle b = new Bundle();
        b.putString("recover_raw", rawFile.getAbsolutePath());
        f.setArguments(b);
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.fade_out,
                        R.anim.fade_in, R.anim.slide_out_right)
                .replace(R.id.container, f, "STU")
                .commit();
    }

    public void exitStudio() {
        inStudio = false;
        syncFabWithEngine();
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.fade_in, R.anim.slide_out_right,
                        R.anim.slide_in_right, R.anim.fade_out)
                .replace(R.id.container, new LibraryFragment(), "LIB")
                .commit();
    }

    // ---- split record controls ----

    /**
     * Main FAB artwork morph (mic / pause / play) with a smooth shrink-swap-pop.
     * The split stop button is shown separately while a take is active.
     */
    public void morphFabIcon(int resId) {
        if (fabIconRes == resId) return;
        fabIconRes = resId;
        fabIcon.animate().cancel();
        fabIcon.animate()
                .scaleX(0.35f).scaleY(0.35f)
                .rotationBy(70f)
                .setDuration(110)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .withEndAction(() -> {
                    try {
                        fabIcon.setImageResource(resId);
                    } catch (Exception ignored) {}
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
        java.util.List<OptionsSheet.Item> items = new java.util.ArrayList<>();
        items.add(OptionsSheet.Item.of(R.drawable.ic_play,
                getString(R.string.action_continue), () -> {
                    try {
                        RecordingEngine.get().attachExisting(
                                getApplicationContext(),
                                u.rawFile, u.name, u.channels,
                                RecordingEngine.payloadBytesOf(u.rawFile));
                    } catch (Exception e) {
                        OptionsSheet.showResult(this, false,
                                getString(R.string.convert_failed_title),
                                getString(R.string.couldnt_export));
                        promptRecovery(index + 1, all);
                        return;
                    }
                    openStudioWithRecovery(u.rawFile);
                }));
        items.add(OptionsSheet.Item.danger(R.drawable.ic_trash,
                getString(R.string.action_discard), () -> {
                    boolean ok = RecordingEngine.deleteUnfinished(u.rawFile);
                    OptionsSheet.showResult(this, ok,
                            ok ? getString(R.string.delete_success_title)
                                    : getString(R.string.delete_failed_title),
                            ok ? getString(R.string.delete_success_one, u.name)
                                    : getString(R.string.delete_failed_msg),
                            () -> promptRecovery(index + 1, all));
                }));
        try {
            OptionsSheet.showOptions(this,
                    getString(R.string.unfinished_title),
                    getString(R.string.unfinished_msg, u.name, dur),
                    items);
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
