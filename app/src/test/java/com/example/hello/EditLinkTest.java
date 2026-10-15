package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * EditLink stash contract (NEW-06): both the studio pre-prompt and the
 * notification save path rely on save/match/id/title/clear.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class EditLinkTest {

    private android.content.Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test
    public void saveMatchAndClearBehave() {
        EditLink.clear(ctx());
        assertFalse(EditLink.matches(ctx(), "/takes/a.wav"));
        EditLink.save(ctx(), 42L, "Original", "/takes/a.wav");
        assertTrue(EditLink.matches(ctx(), "/takes/a.wav"));
        assertFalse(EditLink.matches(ctx(), "/takes/b.wav"));
        assertFalse(EditLink.matches(ctx(), null));
        assertEquals(42L, EditLink.id(ctx()));
        assertEquals("Original", EditLink.title(ctx()));
        EditLink.clear(ctx());
        assertFalse(EditLink.matches(ctx(), "/takes/a.wav"));
        assertEquals(-1L, EditLink.id(ctx()));
    }
}
