package com.example.hello;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The adaptive chunk size is a hint, so the important contracts are simple:
 * it never returns zero/negative, never exceeds the tuned request, and scales
 * with channel count without arithmetic overflow.
 */
public class MemoryBudgetTest {

    @Test
    public void requestedChunkIsUsedWhenHeapHasHeadroom() {
        int frames = MemoryBudget.chunkFrames(65_536, 2);
        assertTrue("must be a usable chunk", frames >= 2_048);
        assertTrue("must not exceed the tuned constant",
                frames <= 65_536);
    }

    @Test
    public void stereoNeverAllocatesMoreThanMonoForTheSameBudget() {
        int mono = MemoryBudget.chunkFrames(65_536, 1);
        int stereo = MemoryBudget.chunkFrames(65_536, 2);
        assertTrue("stereo chunks must not be larger than mono",
                stereo <= mono);
    }

    @Test
    public void hostileInputsStillYieldAUsableChunk() {
        assertEquals(2_048, MemoryBudget.chunkFrames(0, 0));
        assertTrue(MemoryBudget.chunkFrames(-5, -5) >= 2_048);
        assertTrue(MemoryBudget.chunkFrames(Integer.MAX_VALUE, 2) > 0);
    }
}
