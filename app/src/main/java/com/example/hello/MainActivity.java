package com.example.hello;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;

public class MainActivity extends AppCompatActivity {

    private View fabWrap;
    private View fabHalo;
    private ImageView fabIcon;
    private boolean inStudio = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fabWrap = findViewById(R.id.fabWrap);
        fabHalo = findViewById(R.id.fabHalo);
        fabIcon = findViewById(R.id.fabIcon);

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
        } else {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container, new LibraryFragment(), "LIB")
                    .commit();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("inStudio", inStudio);
    }

    public void openStudio() {
        inStudio = true;
        setFabStop(false);
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.fade_out,
                        R.anim.fade_in, R.anim.slide_out_right)
                .replace(R.id.container, new StudioFragment(), "STU")
                .commit();
    }

    public void exitStudio() {
        inStudio = false;
        setFabStop(false);
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.fade_in, R.anim.slide_out_right,
                        R.anim.slide_in_right, R.anim.fade_out)
                .replace(R.id.container, new LibraryFragment(), "LIB")
                .commit();
    }

    /** Swap the shared button between mic (idle) and square (stop) artwork. */
    public void setFabStop(boolean stop) {
        fabIcon.setImageResource(stop ? R.drawable.ic_stop : R.drawable.ic_mic);
        fabWrap.setContentDescription(getString(R.string.cd_mic));
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
