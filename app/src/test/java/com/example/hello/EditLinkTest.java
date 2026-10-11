package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EditLinkTest {

    @Test
    public void saveMatchClear() {
        Context ctx = ApplicationProvider.getApplicationContext();
        EditLink.clear(ctx);
        assertFalse(EditLink.matches(ctx, "/takes/a.wav"));
        EditLink.save(ctx, 42, "Song", "/takes/a.wav");
        assertTrue(EditLink.matches(ctx, "/takes/a.wav"));
        assertEquals(42, EditLink.id(ctx));
        assertEquals("Song", EditLink.title(ctx));
        // A different file must never inherit the linkage.
        assertFalse(EditLink.matches(ctx, "/takes/b.wav"));
        EditLink.clear(ctx);
        assertFalse(EditLink.matches(ctx, "/takes/a.wav"));
        assertEquals(-1, EditLink.id(ctx));
    }
}
