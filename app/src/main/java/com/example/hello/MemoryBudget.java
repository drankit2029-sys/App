package com.example.hello;

/**
 * Adaptive chunk sizing for offline audio work.
 *
 * <p>The converters used to allocate their maximum (64K-frame float buffers,
 * up to ~650 KB per pass) regardless of how much headroom the process had.
 * On a low-RAM device, or while a recording, the FX preview and the library
 * are all live, those allocations were exactly the ones that pushed the heap
 * into repeated GC pauses. The budget below keeps the per-pass buffer inside
 * a fraction of the free heap, while never going below a useful chunk size.
 *
 * <p>This is deliberately framework-free so it behaves the same in
 * Robolectric and on device: the size is a hint, not a contract, and the
 * encoder loops already handle any positive chunk.
 */
final class MemoryBudget {

    /** Small enough to be safe on a 128 MB heap, large enough to amortise. */
    private static final long MIN_BUDGET_BYTES = 256L * 1024L;
    /** Never ask for bigger buffers than the original tuned constants. */
    private static final long MAX_BUDGET_BYTES = 4L * 1024L * 1024L;
    /** Use at most this fraction of the currently free heap per pass. */
    private static final long HEAP_DIVISOR = 16L;

    private MemoryBudget() {}

    /**
     * Number of frames to allocate this pass. {@code desired} is the tuned
     * constant; {@code channels} scales the float input buffer, which is the
     * dominant allocation.
     */
    static int chunkFrames(int desired, int channels) {
        int ch = Math.max(1, Math.min(2, channels));
        int wanted = Math.max(1, desired);
        try {
            Runtime rt = Runtime.getRuntime();
            long used = rt.totalMemory() - rt.freeMemory();
            long free = Math.max(0L, rt.maxMemory() - used);
            long budget = Math.max(MIN_BUDGET_BYTES,
                    Math.min(MAX_BUDGET_BYTES, free / HEAP_DIVISOR));
            int fits = (int) Math.min(Integer.MAX_VALUE,
                    budget / (4L * ch));
            return Math.max(2_048, Math.min(wanted, Math.max(1, fits)));
        } catch (Throwable ignored) {
            return wanted;
        }
    }
}
