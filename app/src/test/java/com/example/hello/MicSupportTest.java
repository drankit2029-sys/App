package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Microphone capability rules. All null-manager paths are pure Java and must
 * degrade to "try stereo, fall back silently" instead of failing.
 */
public class MicSupportTest {

    @Test
    public void nullManagerIsUnknown() {
        assertEquals(-1, MicSupport.physicalMicCount(null));
    }

    @Test
    public void unknownMeansTryStereo() {
        assertTrue(MicSupport.stereoLikely(null));
    }

    @Test
    public void unknownIsNeverDualAbsent() {
        assertFalse(MicSupport.dualAbsent(null));
    }
}
