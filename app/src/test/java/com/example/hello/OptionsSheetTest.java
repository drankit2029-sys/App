package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/**
 * Every dialog builder opens, labels its controls, fires its actions and
 * dismisses — driven through the shown dialog like a user would.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OptionsSheetTest {

    private Activity activity() {
        return Robolectric.buildActivity(Activity.class).setup().get();
    }

    private static Dialog latest() {
        Dialog d = ShadowDialog.getLatestDialog();
        assertTrue("no dialog shown", d != null && d.isShowing());
        return d;
    }

    private static void idle() {
        ShadowLooper shadow = Shadows.shadowOf(Looper.getMainLooper());
        shadow.idle();
    }

    @Test
    public void confirmFiresAndDismisses() {
        Activity a = activity();
        final boolean[] fired = {false};
        OptionsSheet.showConfirm(a, "Title?", "Are you sure?", "Do it",
                R.drawable.ic_check, false, () -> fired[0] = true);
        Dialog d = latest();
        assertEquals("Title?",
                ((TextView) d.findViewById(R.id.dialogTitle)).getText()
                        .toString());
        d.findViewById(R.id.dialogConfirm).performClick();
        idle();
        assertTrue("confirm action never fired", fired[0]);
        assertTrue("dialog did not dismiss", !d.isShowing());
    }

    @Test
    public void confirmCancelDismissesWithoutFiring() {
        Activity a = activity();
        final boolean[] fired = {false};
        OptionsSheet.showConfirm(a, "Title?", "Are you sure?", "Do it",
                R.drawable.ic_trash, true, () -> fired[0] = true);
        Dialog d = latest();
        d.findViewById(R.id.dialogCancel).performClick();
        idle();
        assertTrue(!fired[0]);
        assertTrue(!d.isShowing());
    }

    @Test
    public void resultShowsMessageAndOk() {
        Activity a = activity();
        final boolean[] ok = {false};
        OptionsSheet.showResult(a, true, "Saved", "All good", () -> ok[0] = true);
        Dialog d = latest();
        assertEquals("Saved",
                ((TextView) d.findViewById(R.id.resultTitle)).getText()
                        .toString());
        View okBtn = d.findViewById(R.id.resultOk);
        assertTrue(okBtn.getVisibility() == View.VISIBLE);
        okBtn.performClick();
        idle();
        assertTrue(ok[0]);
        assertTrue(!d.isShowing());
    }

    @Test
    public void resultWithoutCallbackHidesOk() {
        Activity a = activity();
        OptionsSheet.showResult(a, false, "Failed", "Bad news");
        Dialog d = latest();
        assertTrue(d.findViewById(R.id.resultOk).getVisibility() == View.GONE);
        d.dismiss();
    }

    @Test
    public void optionsListFiresChosenItem() {
        Activity a = activity();
        final String[] picked = {null};
        List<OptionsSheet.Item> items = new ArrayList<>();
        items.add(OptionsSheet.Item.of(R.drawable.ic_check, "First",
                () -> picked[0] = "first"));
        items.add(OptionsSheet.Item.of(R.drawable.ic_share, "Second",
                () -> picked[0] = "second"));
        OptionsSheet.showOptions(a, "Pick one", "subtitle", items);
        Dialog d = latest();
        assertEquals("Pick one",
                ((TextView) d.findViewById(R.id.dialogTitle)).getText()
                        .toString());
        // Option rows live wrapped in a FrameLayout per pill; the clickable
        // row is the wrapper's first child.
        android.view.ViewGroup box = d.findViewById(R.id.dialogOptions);
        assertTrue("no option rows", box != null && box.getChildCount() >= 2);
        android.view.ViewGroup wrap =
                (android.view.ViewGroup) box.getChildAt(1);
        wrap.getChildAt(0).performClick();
        idle();
        assertEquals("second", picked[0]);
    }

    @Test
    public void inputSavesText() {
        Activity a = activity();
        final String[] saved = {null};
        OptionsSheet.showInput(a, "Name it", "Take 1", "Save",
                text -> saved[0] = text);
        Dialog d = latest();
        android.widget.EditText input =
                d.findViewById(R.id.dialogInput);
        assertTrue(input != null);
        input.setText("Renamed");
        d.findViewById(R.id.dialogSave).performClick();
        idle();
        assertEquals("Renamed", saved[0]);
    }

    @Test
    public void inputRejectsBlank() {
        Activity a = activity();
        final boolean[] saved = {false};
        OptionsSheet.showInput(a, "Name it", "Take 1", "Save",
                text -> saved[0] = true);
        Dialog d = latest();
        android.widget.EditText input =
                d.findViewById(R.id.dialogInput);
        input.setText("   ");
        d.findViewById(R.id.dialogSave).performClick();
        idle();
        assertTrue(!saved[0]);
        assertTrue("dialog should stay open on blank", d.isShowing());
        d.dismiss();
    }

    @Test
    public void applicationContextInflationSanity() {
        // Builders must also survive a bare application context.
        OptionsSheet.showResult(
                ApplicationProvider.getApplicationContext(),
                true, "T", "M");
        assertTrue(ShadowDialog.getLatestDialog() != null);
    }
}
