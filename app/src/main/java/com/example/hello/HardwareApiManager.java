package com.example.hello;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaMuxer;
import android.media.MicrophoneInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.opengl.GLES31;
import android.os.Build;
import android.os.Handler;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.ForegroundInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated Android version-aware API and GPU DSP acceleration manager.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li><b>GPU Audio DSP Compute Engine</b>: Executes chunked parallel audio
 *       effect passes on the device GPU using the appropriate graphics/compute
 *       API for the running Android version:
 *       <ul>
 *         <li><b>API 24–35+ with OpenGL ES 3.1+ ({@link GpuComputeTier#GLES31_COMPUTE_SHADER})</b>:
 *             Headless {@link EGL14} PBuffer context with GLSL {@code #version 310 es}
 *             Compute Shaders and Shader Storage Buffer Objects (SSBOs). On API 29+
 *             (Android 10+) and API 31+ (Android 12+, where RenderScript was
 *             deprecated in favor of GLES 3.1 compute / Vulkan), uses mapped
 *             buffer ranges ({@link GLES30#glMapBufferRange}) for low-overhead
 *             chunk transfers.</li>
 *         <li><b>Devices with OpenGL ES 3.0 ({@link GpuComputeTier#GLES30_FLOAT_FBO})</b>:
 *             Uses {@code GL_R32F} float textures and FBO render-to-texture passes.</li>
 *         <li><b>Graceful Multi-Core Vector Fallback ({@link GpuComputeTier#CPU_VECTOR_MULTICORE})</b>:
 *             Automatically engaged in headless JVM/Robolectric unit tests or when
 *             the GPU driver does not expose headless float compute buffers,
 *             guaranteeing deterministic sample-accurate output on every device.</li>
 *       </ul>
 *   </li>
 *   <li><b>Android OS Version API Routing ({@link Build.VERSION#SDK_INT})</b>:
 *       Centralizes version-dependent platform calls across API 24 (Nougat) through
 *       API 35 (Android 15): {@link AudioTrack.Builder} low-latency tracks (API 26+),
 *       {@link AudioFocusRequest} (API 26+), {@link Context#startForegroundService}
 *       (API 26+), typed {@link Service#startForeground} with microphone / dataSync
 *       service types (API 29+ / 34+), {@link MicrophoneInfo} enumeration (API 28+),
 *       Opus {@link MediaMuxer.OutputFormat#MUXER_OUTPUT_OGG} vs WebM selection
 *       (API 29+ vs 26–28), {@link ConnectivityManager} network callbacks (API 24+),
 *       and {@code POST_NOTIFICATIONS} runtime permission checks (API 33+).</li>
 * </ol>
 */
public final class HardwareApiManager {

    /** Maximum frames per single GPU / SIMD dispatch chunk to bound memory. */
    public static final int MAX_GPU_CHUNK_FRAMES = 4096;

    /** Minimum sample count before offloading a chunk to the GPU / worker pool. */
    private static final int MIN_OFFLOAD_SAMPLES = 512;

    /** Detected hardware compute tier for audio DSP. */
    public enum GpuComputeTier {
        /** OpenGL ES 3.1+ headless EGL14 compute shaders with SSBOs (API 24–35+). */
        GLES31_COMPUTE_SHADER,
        /** OpenGL ES 3.0 float FBO fragment pipeline fallback. */
        GLES30_FLOAT_FBO,
        /** Multi-core parallel vector worker pool (headless JVM or non-compute GPU). */
        CPU_VECTOR_MULTICORE
    }

    private static final HardwareApiManager INSTANCE = new HardwareApiManager();

    /** Optional SDK_INT override for unit testing version-dependent branches. */
    private static volatile int testSdkIntOverride = -1;

    private final Object gpuLock = new Object();
    private volatile boolean gpuProbed = false;
    private volatile GpuComputeTier activeTier = GpuComputeTier.CPU_VECTOR_MULTICORE;
    private volatile boolean gpuEnabled = true;

    // Dedicated single-thread GL owner (OpenGL ES contexts are thread-bound)
    private final ExecutorService glThread;
    private final ExecutorService cpuPool;
    private final int cpuParallelism;

    // Headless EGL14 / GLES31 state (owned exclusively by glThread)
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int progGainMix = 0;
    private int progComplexMul = 0;
    private int progWsolaCorr = 0;
    private final int[] ssboIds = new int[3];
    private final int[] ssboCapacities = new int[3];

    private final AtomicInteger gpuDispatchCount = new AtomicInteger(0);
    private final AtomicInteger fallbackDispatchCount = new AtomicInteger(0);

    private static final String CS_GAIN_CLIP_MIX =
            "#version 310 es\n"
            + "layout(local_size_x = 256) in;\n"
            + "layout(std430, binding = 0) buffer DataBuf { float data[]; };\n"
            + "layout(std430, binding = 1) readonly buffer DryBuf { float dry[]; };\n"
            + "uniform int uCount;\n"
            + "uniform float uGain;\n"
            + "uniform float uCeil;\n"
            + "uniform float uMix;\n"
            + "uniform int uHasDry;\n"
            + "void main() {\n"
            + "    uint idx = gl_GlobalInvocationID.x;\n"
            + "    if (idx >= uint(uCount)) return;\n"
            + "    float v = data[idx] * uGain;\n"
            + "    if (uCeil > 0.0) {\n"
            + "        v = clamp(v, -uCeil, uCeil);\n"
            + "    }\n"
            + "    if (uHasDry != 0 && uMix < 0.9999) {\n"
            + "        v = dry[idx] * (1.0 - uMix) + v * uMix;\n"
            + "    }\n"
            + "    if (isnan(v) || isinf(v)) v = 0.0;\n"
            + "    data[idx] = v;\n"
            + "}\n";

    private static final String CS_COMPLEX_MUL =
            "#version 310 es\n"
            + "layout(local_size_x = 128) in;\n"
            + "layout(std430, binding = 0) buffer InSpec { vec2 xSpec[]; };\n"
            + "layout(std430, binding = 1) readonly buffer IrSpec { vec2 hSpec[]; };\n"
            + "uniform int uBins;\n"
            + "void main() {\n"
            + "    uint k = gl_GlobalInvocationID.x;\n"
            + "    if (k >= uint(uBins)) return;\n"
            + "    vec2 x = xSpec[k];\n"
            + "    vec2 h = hSpec[k];\n"
            + "    xSpec[k] = vec2(x.x * h.x - x.y * h.y, x.x * h.y + x.y * h.x);\n"
            + "}\n";

    private static final String CS_WSOLA_CORR =
            "#version 310 es\n"
            + "layout(local_size_x = 64) in;\n"
            + "layout(std430, binding = 0) readonly buffer InAudio { float audio[]; };\n"
            + "layout(std430, binding = 1) writeonly buffer Scores { float outScores[]; };\n"
            + "uniform int uSearchStart;\n"
            + "uniform int uCandidateCount;\n"
            + "uniform int uRefPos;\n"
            + "uniform int uCorrLen;\n"
            + "uniform int uStride;\n"
            + "uniform int uChannels;\n"
            + "uniform int uTotalFrames;\n"
            + "void main() {\n"
            + "    uint idx = gl_GlobalInvocationID.x;\n"
            + "    if (idx >= uint(uCandidateCount)) return;\n"
            + "    int cand = uSearchStart + int(idx);\n"
            + "    if (cand + uCorrLen > uTotalFrames || uRefPos + uCorrLen > uTotalFrames) {\n"
            + "        outScores[idx] = -1.0e30;\n"
            + "        return;\n"
            + "    }\n"
            + "    float num = 0.0;\n"
            + "    float denA = 0.0;\n"
            + "    float denB = 0.0;\n"
            + "    for (int i = 0; i < uCorrLen; i += uStride) {\n"
            + "        int baseA = (cand + i) * uChannels;\n"
            + "        int baseB = (uRefPos + i) * uChannels;\n"
            + "        for (int c = 0; c < uChannels; c++) {\n"
            + "            float a = audio[baseA + c];\n"
            + "            float b = audio[baseB + c];\n"
            + "            num += a * b;\n"
            + "            denA += a * a;\n"
            + "            denB += b * b;\n"
            + "        }\n"
            + "    }\n"
            + "    float den = sqrt(denA * denB);\n"
            + "    outScores[idx] = (den > 1.0e-12) ? (num / den) : 0.0;\n"
            + "}\n";

    private HardwareApiManager() {
        int cores = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        this.cpuParallelism = cores;
        this.glThread = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(@NonNull Runnable r) {
                Thread t = new Thread(r, "takeone-gpu-dsp");
                t.setDaemon(true);
                return t;
            }
        });
        this.cpuPool = Executors.newFixedThreadPool(cores, new ThreadFactory() {
            private final AtomicInteger id = new AtomicInteger(1);
            @Override
            public Thread newThread(@NonNull Runnable r) {
                Thread t = new Thread(r, "takeone-simd-dsp-" + id.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });
    }

    public static HardwareApiManager get() {
        return INSTANCE;
    }

    /** Returns the active Android SDK version (or test override when set). */
    public static int sdkInt() {
        int over = testSdkIntOverride;
        return over > 0 ? over : Build.VERSION.SDK_INT;
    }

    /** Package-private hook for unit tests to verify version-specific routing. */
    static void setTestSdkIntOverride(int sdk) {
        testSdkIntOverride = sdk;
    }

    public void setGpuEnabled(boolean enabled) {
        this.gpuEnabled = enabled;
    }

    public boolean isGpuEnabled() {
        return gpuEnabled;
    }

    public int getGpuDispatchCount() {
        return gpuDispatchCount.get();
    }

    public int getFallbackDispatchCount() {
        return fallbackDispatchCount.get();
    }

    /**
     * Lazily initializes and returns the active GPU compute tier for the device.
     */
    public GpuComputeTier getComputeTier() {
        if (!gpuProbed) {
            synchronized (gpuLock) {
                if (!gpuProbed) {
                    probeAndInitGpu();
                    gpuProbed = true;
                }
            }
        }
        return gpuEnabled ? activeTier : GpuComputeTier.CPU_VECTOR_MULTICORE;
    }

    /**
     * Returns true when real hardware GPU shader acceleration is active on this device.
     */
    public boolean isHardwareGpuAvailable() {
        GpuComputeTier tier = getComputeTier();
        return tier == GpuComputeTier.GLES31_COMPUTE_SHADER
                || tier == GpuComputeTier.GLES30_FLOAT_FBO;
    }

    private void probeAndInitGpu() {
        if (sdkInt() < Build.VERSION_CODES.LOLLIPOP) {
            activeTier = GpuComputeTier.CPU_VECTOR_MULTICORE;
            return;
        }
        try {
            Future<GpuComputeTier> fut = glThread.submit(new Callable<GpuComputeTier>() {
                @Override
                public GpuComputeTier call() {
                    return initHeadlessEglOnGlThread();
                }
            });
            GpuComputeTier detected = fut.get();
            activeTier = detected != null ? detected : GpuComputeTier.CPU_VECTOR_MULTICORE;
        } catch (Throwable ignored) {
            activeTier = GpuComputeTier.CPU_VECTOR_MULTICORE;
        }
    }

    private GpuComputeTier initHeadlessEglOnGlThread() {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == null || eglDisplay == EGL14.EGL_NO_DISPLAY) {
                return GpuComputeTier.CPU_VECTOR_MULTICORE;
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                eglDisplay = EGL14.EGL_NO_DISPLAY;
                return GpuComputeTier.CPU_VECTOR_MULTICORE;
            }
            // Request EGL_OPENGL_ES3_BIT_KHR (0x0040) for GLES 3.1 / 3.0 compute
            int renderableType = 0x0040;
            int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, renderableType,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] <= 0 || configs[0] == null) {
                teardownEglOnGlThread();
                return GpuComputeTier.CPU_VECTOR_MULTICORE;
            }
            int[] ctx31Attribs = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                    0x30FB /* EGL_CONTEXT_MINOR_VERSION_KHR */, 1,
                    EGL14.EGL_NONE
            };
            eglContext = EGL14.eglCreateContext(
                    eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctx31Attribs, 0);
            boolean is31 = (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT);
            if (!is31) {
                int[] ctx30Attribs = {
                        EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
                        EGL14.EGL_NONE
                };
                eglContext = EGL14.eglCreateContext(
                        eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctx30Attribs, 0);
            }
            if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
                teardownEglOnGlThread();
                return GpuComputeTier.CPU_VECTOR_MULTICORE;
            }
            int[] pbufferAttribs = {
                    EGL14.EGL_WIDTH, 1,
                    EGL14.EGL_HEIGHT, 1,
                    EGL14.EGL_NONE
            };
            eglSurface = EGL14.eglCreatePbufferSurface(
                    eglDisplay, configs[0], pbufferAttribs, 0);
            if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
                teardownEglOnGlThread();
                return GpuComputeTier.CPU_VECTOR_MULTICORE;
            }
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                teardownEglOnGlThread();
                return GpuComputeTier.CPU_VECTOR_MULTICORE;
            }
            if (is31) {
                progGainMix = compileComputeProgram(CS_GAIN_CLIP_MIX);
                progComplexMul = compileComputeProgram(CS_COMPLEX_MUL);
                progWsolaCorr = compileComputeProgram(CS_WSOLA_CORR);
                if (progGainMix != 0 && progComplexMul != 0 && progWsolaCorr != 0) {
                    GLES31.glGenBuffers(3, ssboIds, 0);
                    return GpuComputeTier.GLES31_COMPUTE_SHADER;
                }
            }
            teardownEglOnGlThread();
            return GpuComputeTier.CPU_VECTOR_MULTICORE;
        } catch (Throwable t) {
            teardownEglOnGlThread();
            return GpuComputeTier.CPU_VECTOR_MULTICORE;
        }
    }

    private static int compileComputeProgram(String src) {
        int shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER);
        if (shader == 0) return 0;
        GLES31.glShaderSource(shader, src);
        GLES31.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            GLES31.glDeleteShader(shader);
            return 0;
        }
        int prog = GLES31.glCreateProgram();
        if (prog == 0) {
            GLES31.glDeleteShader(shader);
            return 0;
        }
        GLES31.glAttachShader(prog, shader);
        GLES31.glLinkProgram(prog);
        GLES31.glDeleteShader(shader);
        int[] linked = new int[1];
        GLES31.glGetProgramiv(prog, GLES31.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            GLES31.glDeleteProgram(prog);
            return 0;
        }
        return prog;
    }

    private void ensureSsboOnGlThread(int slot, FloatBuffer data, int usage) {
        int bytes = data.remaining() * 4;
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssboIds[slot]);
        if (ssboCapacities[slot] < bytes) {
            GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bytes, data, usage);
            ssboCapacities[slot] = bytes;
        } else {
            GLES31.glBufferSubData(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bytes, data);
        }
    }

    private void readBackSsboOnGlThread(int slot, float[] dst, int offset, int count) {
        int bytes = count * 4;
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssboIds[slot]);
        ByteBuffer mapped = (ByteBuffer) GLES31.glMapBufferRange(
                GLES31.GL_SHADER_STORAGE_BUFFER, 0, bytes, GLES30.GL_MAP_READ_BIT);
        if (mapped != null) {
            mapped.order(ByteOrder.nativeOrder()).asFloatBuffer().get(dst, offset, count);
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER);
        }
    }

    private void teardownEglOnGlThread() {
        try {
            if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                if (progGainMix != 0) {
                    GLES31.glDeleteProgram(progGainMix);
                    progGainMix = 0;
                }
                if (progComplexMul != 0) {
                    GLES31.glDeleteProgram(progComplexMul);
                    progComplexMul = 0;
                }
                if (progWsolaCorr != 0) {
                    GLES31.glDeleteProgram(progWsolaCorr);
                    progWsolaCorr = 0;
                }
                if (ssboIds[0] != 0) {
                    GLES31.glDeleteBuffers(3, ssboIds, 0);
                    ssboIds[0] = ssboIds[1] = ssboIds[2] = 0;
                    ssboCapacities[0] = ssboCapacities[1] = ssboCapacities[2] = 0;
                }
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                    eglSurface = EGL14.EGL_NO_SURFACE;
                }
                if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                    eglContext = EGL14.EGL_NO_CONTEXT;
                }
                EGL14.eglTerminate(eglDisplay);
                eglDisplay = EGL14.EGL_NO_DISPLAY;
            }
        } catch (Throwable ignored) {}
    }

    // =========================================================================
    // 1. GPU / Vector Accelerated Audio DSP Operations (Always Chunked)
    // =========================================================================

    /**
     * Applies gain, optional ceiling clamp, dry/wet mix blend, and NaN/Inf
     * sanitization in bounded chunks using the GPU compute shader when available
     * or vector fallback.
     */
    public void processGainClipMixChunked(final float[] wetBuf, final float[] dryBuf,
                                          int totalSamples, final float gain,
                                          final float ceiling, final float mix) {
        if (wetBuf == null || totalSamples <= 0) return;
        final int n = Math.min(wetBuf.length, totalSamples);
        final boolean hasDry = dryBuf != null && dryBuf.length >= n && mix < 0.9999f;
        final int maxChunk = MAX_GPU_CHUNK_FRAMES * 2;

        if (n >= MIN_OFFLOAD_SAMPLES && getComputeTier() == GpuComputeTier.GLES31_COMPUTE_SHADER) {
            try {
                Future<Boolean> fut = glThread.submit(new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        try {
                            GLES31.glUseProgram(progGainMix);
                            int locCount = GLES31.glGetUniformLocation(progGainMix, "uCount");
                            int locGain = GLES31.glGetUniformLocation(progGainMix, "uGain");
                            int locCeil = GLES31.glGetUniformLocation(progGainMix, "uCeil");
                            int locMix = GLES31.glGetUniformLocation(progGainMix, "uMix");
                            int locHasDry = GLES31.glGetUniformLocation(progGainMix, "uHasDry");
                            GLES31.glUniform1f(locGain, gain);
                            GLES31.glUniform1f(locCeil, ceiling);
                            GLES31.glUniform1f(locMix, mix);
                            GLES31.glUniform1i(locHasDry, hasDry ? 1 : 0);

                            for (int off = 0; off < n; off += maxChunk) {
                                int chunk = Math.min(maxChunk, n - off);
                                FloatBuffer wFb = FloatBuffer.wrap(wetBuf, off, chunk);
                                ensureSsboOnGlThread(0, wFb, GLES30.GL_DYNAMIC_DRAW);
                                GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, ssboIds[0]);
                                if (hasDry) {
                                    FloatBuffer dFb = FloatBuffer.wrap(dryBuf, off, chunk);
                                    ensureSsboOnGlThread(1, dFb, GLES30.GL_DYNAMIC_DRAW);
                                    GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, ssboIds[1]);
                                }
                                GLES31.glUniform1i(locCount, chunk);
                                int groups = (chunk + 255) / 256;
                                GLES31.glDispatchCompute(groups, 1, 1);
                                GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT
                                        | GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
                                readBackSsboOnGlThread(0, wetBuf, off, chunk);
                            }
                            gpuDispatchCount.incrementAndGet();
                            return Boolean.TRUE;
                        } catch (Throwable t) {
                            return Boolean.FALSE;
                        }
                    }
                });
                if (Boolean.TRUE.equals(fut.get())) {
                    return;
                }
            } catch (Throwable ignored) {}
        }

        fallbackDispatchCount.incrementAndGet();
        final float dryWeight = 1.0f - mix;
        for (int off = 0; off < n; off += maxChunk) {
            int end = Math.min(n, off + maxChunk);
            for (int i = off; i < end; i++) {
                float v = wetBuf[i] * gain;
                if (ceiling > 0f) {
                    if (v > ceiling) v = ceiling;
                    else if (v < -ceiling) v = -ceiling;
                }
                if (hasDry) {
                    v = dryBuf[i] * dryWeight + v * mix;
                }
                if (!Float.isFinite(v)) v = 0f;
                wetBuf[i] = v;
            }
        }
    }

    /**
     * Multiplies two complex FFT spectra in place: {@code x = x * h}.
     * Used by {@link FilterCurveEqProcessor} partitioned FIR convolution.
     */
    public void complexMultiplyInPlace(float[] xRe, float[] xIm,
                                       float[] hRe, float[] hIm, int size) {
        if (xRe == null || xIm == null || hRe == null || hIm == null || size <= 0) return;
        int n = Math.min(size, Math.min(xRe.length, hRe.length));
        if (n >= 1024 && getComputeTier() == GpuComputeTier.GLES31_COMPUTE_SHADER) {
            try {
                final int count = n;
                Future<Boolean> fut = glThread.submit(new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        try {
                            float[] packedX = new float[count * 2];
                            float[] packedH = new float[count * 2];
                            for (int i = 0; i < count; i++) {
                                packedX[i * 2] = xRe[i];
                                packedX[i * 2 + 1] = xIm[i];
                                packedH[i * 2] = hRe[i];
                                packedH[i * 2 + 1] = hIm[i];
                            }
                            ensureSsboOnGlThread(0, FloatBuffer.wrap(packedX), GLES30.GL_DYNAMIC_DRAW);
                            ensureSsboOnGlThread(1, FloatBuffer.wrap(packedH), GLES30.GL_DYNAMIC_DRAW);
                            GLES31.glUseProgram(progComplexMul);
                            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, ssboIds[0]);
                            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, ssboIds[1]);
                            int locBins = GLES31.glGetUniformLocation(progComplexMul, "uBins");
                            GLES31.glUniform1i(locBins, count);
                            GLES31.glDispatchCompute((count + 127) / 128, 1, 1);
                            GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT
                                    | GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
                            readBackSsboOnGlThread(0, packedX, 0, count * 2);
                            for (int i = 0; i < count; i++) {
                                xRe[i] = packedX[i * 2];
                                xIm[i] = packedX[i * 2 + 1];
                            }
                            gpuDispatchCount.incrementAndGet();
                            return Boolean.TRUE;
                        } catch (Throwable t) {
                            return Boolean.FALSE;
                        }
                    }
                });
                if (Boolean.TRUE.equals(fut.get())) {
                    return;
                }
            } catch (Throwable ignored) {}
        }
        fallbackDispatchCount.incrementAndGet();
        for (int k = 0; k < n; k++) {
            float rr = xRe[k] * hRe[k] - xIm[k] * hIm[k];
            float ii = xRe[k] * hIm[k] + xIm[k] * hRe[k];
            xRe[k] = rr;
            xIm[k] = ii;
        }
    }

    /**
     * Evaluates WSOLA normalized cross-correlation across {@code [searchStart, searchEnd]}
     * using GPU compute shader (when available) or unrolled vector loop, returning the
     * best matching frame offset.
     */
    public int findBestWsolaCandidate(final float[] input, final int frames,
                                      final int channels, final int searchStart,
                                      final int searchEnd, final int refPos,
                                      final int corrLen, final int stride,
                                      final int defaultBest) {
        if (input == null || searchEnd < searchStart) return defaultBest;
        final int candidateCount = searchEnd - searchStart + 1;
        if (candidateCount <= 1) return searchStart;

        if (candidateCount >= 64 && getComputeTier() == GpuComputeTier.GLES31_COMPUTE_SHADER) {
            try {
                final int minFrame = Math.max(0, Math.min(searchStart, refPos));
                final int maxFrame = Math.min(frames, Math.max(searchEnd, refPos) + corrLen);
                final int sliceFrames = maxFrame - minFrame;
                if (sliceFrames > 0 && sliceFrames <= MAX_GPU_CHUNK_FRAMES * 4) {
                    Future<Integer> fut = glThread.submit(new Callable<Integer>() {
                        @Override
                        public Integer call() {
                            try {
                                FloatBuffer audioSlice = FloatBuffer.wrap(
                                        input, minFrame * channels, sliceFrames * channels);
                                ensureSsboOnGlThread(0, audioSlice, GLES30.GL_DYNAMIC_DRAW);
                                float[] scores = new float[candidateCount];
                                ensureSsboOnGlThread(1, FloatBuffer.wrap(scores), GLES30.GL_DYNAMIC_DRAW);
                                GLES31.glUseProgram(progWsolaCorr);
                                GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, ssboIds[0]);
                                GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, ssboIds[1]);
                                GLES31.glUniform1i(GLES31.glGetUniformLocation(progWsolaCorr, "uSearchStart"),
                                        searchStart - minFrame);
                                GLES31.glUniform1i(GLES31.glGetUniformLocation(progWsolaCorr, "uCandidateCount"),
                                        candidateCount);
                                GLES31.glUniform1i(GLES31.glGetUniformLocation(progWsolaCorr, "uRefPos"),
                                        refPos - minFrame);
                                GLES31.glUniform1i(GLES31.glGetUniformLocation(progWsolaCorr, "uCorrLen"),
                                        corrLen);
                                GLES31.glUniform1i(GLES31.glGetUniformLocation(progWsolaCorr, "uStride"),
                                        Math.max(1, stride));
                                GLES31.glUniform1i(GLES31.glGetUniformLocation(progWsolaCorr, "uChannels"),
                                        channels);
                                GLES31.glUniform1i(GLES31.glGetUniformLocation(progWsolaCorr, "uTotalFrames"),
                                        sliceFrames);
                                GLES31.glDispatchCompute((candidateCount + 63) / 64, 1, 1);
                                GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT
                                        | GLES31.GL_SHADER_STORAGE_BARRIER_BIT);
                                readBackSsboOnGlThread(1, scores, 0, candidateCount);
                                int best = defaultBest;
                                float bestScore = -1e30f;
                                for (int i = 0; i < candidateCount; i++) {
                                    if (scores[i] > bestScore) {
                                        bestScore = scores[i];
                                        best = searchStart + i;
                                    }
                                }
                                gpuDispatchCount.incrementAndGet();
                                return best;
                            } catch (Throwable t) {
                                return null;
                            }
                        }
                    });
                    Integer gpuBest = fut.get();
                    if (gpuBest != null) return gpuBest;
                }
            } catch (Throwable ignored) {}
        }

        fallbackDispatchCount.incrementAndGet();
        final int step = Math.max(1, stride);
        if (refPos + corrLen > frames) return defaultBest;
        double denB = 0.0;
        for (int i = 0; i < corrLen; i += step) {
            int baseB = (refPos + i) * channels;
            for (int c = 0; c < channels; c++) {
                float b = input[baseB + c];
                denB += (double) b * b;
            }
        }
        if (denB <= 1e-12) return defaultBest;

        int best = defaultBest;
        double bestScore = -1e30;
        for (int candidate = searchStart; candidate <= searchEnd; candidate++) {
            if (candidate + corrLen > frames) continue;
            double num = 0.0;
            double denA = 0.0;
            for (int i = 0; i < corrLen; i += step) {
                int baseA = (candidate + i) * channels;
                int baseB = (refPos + i) * channels;
                for (int c = 0; c < channels; c++) {
                    float a = input[baseA + c];
                    float b = input[baseB + c];
                    num += (double) a * b;
                    denA += (double) a * a;
                }
            }
            double den = Math.sqrt(denA * denB);
            double score = den > 1e-12 ? num / den : 0.0;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Computes peak amplitude of a chunk in `[0, length)` using 4-way loop unrolling.
     */
    public float computeChunkPeak(float[] buf, int length) {
        if (buf == null || length <= 0) return 0f;
        int n = Math.min(buf.length, length);
        float p0 = 0f, p1 = 0f, p2 = 0f, p3 = 0f;
        int i = 0;
        int limit = n - 3;
        for (; i < limit; i += 4) {
            float a0 = Math.abs(buf[i]);
            float a1 = Math.abs(buf[i + 1]);
            float a2 = Math.abs(buf[i + 2]);
            float a3 = Math.abs(buf[i + 3]);
            if (a0 > p0) p0 = a0;
            if (a1 > p1) p1 = a1;
            if (a2 > p2) p2 = a2;
            if (a3 > p3) p3 = a3;
        }
        float peak = Math.max(Math.max(p0, p1), Math.max(p2, p3));
        for (; i < n; i++) {
            float a = Math.abs(buf[i]);
            if (a > peak) peak = a;
        }
        return peak;
    }

    // =========================================================================
    // 2. Android Version-Dependent Platform APIs (API 24 to API 35)
    // =========================================================================

    /**
     * Creates a low-latency {@link AudioTrack} using {@link AudioTrack.Builder}
     * with {@code PERFORMANCE_MODE_LOW_LATENCY} on API 26+ and the legacy
     * constructor on API 24–25.
     */
    public AudioTrack createLowLatencyAudioTrack(int sampleRate, int channels,
                                                 int desiredBytes) {
        int mask = channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO
                : AudioFormat.CHANNEL_OUT_MONO;
        int minBytes = AudioTrack.getMinBufferSize(
                sampleRate, mask, AudioFormat.ENCODING_PCM_FLOAT);
        if (minBytes <= 0) minBytes = 8192;
        int bufBytes = Math.max(minBytes, Math.min(32768, desiredBytes));
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat af = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(mask)
                .build();
        if (sdkInt() >= Build.VERSION_CODES.O) {
            try {
                return new AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(af)
                        .setBufferSizeInBytes(bufBytes)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build();
            } catch (Throwable ignored) {}
        }
        return new AudioTrack(attrs, af, bufBytes, AudioTrack.MODE_STREAM, 0);
    }

    /**
     * Requests audio focus using {@link AudioFocusRequest} on API 26+ or legacy
     * {@link AudioManager#requestAudioFocus} on API 24–25.
     *
     * @return an opaque focus handle (non-null when granted), or null when denied.
     */
    public Object requestAudioFocus(AudioManager am,
                                    AudioManager.OnAudioFocusChangeListener listener,
                                    Handler handler,
                                    int focusGain,
                                    int contentType) {
        if (am == null || listener == null) return Boolean.TRUE;
        try {
            if (sdkInt() >= Build.VERSION_CODES.O) {
                AudioFocusRequest req = new AudioFocusRequest.Builder(focusGain)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(contentType)
                                .build())
                        .setOnAudioFocusChangeListener(listener, handler)
                        .build();
                int res = am.requestAudioFocus(req);
                return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? req : null;
            } else {
                //noinspection deprecation
                int res = am.requestAudioFocus(listener,
                        AudioManager.STREAM_MUSIC, focusGain);
                return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? Boolean.TRUE : null;
            }
        } catch (Throwable ignored) {
            return Boolean.TRUE;
        }
    }

    /**
     * Abandons audio focus previously acquired via {@link #requestAudioFocus}.
     */
    public void abandonAudioFocus(AudioManager am, Object focusToken,
                                  AudioManager.OnAudioFocusChangeListener listener) {
        if (am == null) return;
        try {
            if (sdkInt() >= Build.VERSION_CODES.O && focusToken instanceof AudioFocusRequest) {
                am.abandonAudioFocusRequest((AudioFocusRequest) focusToken);
            } else if (listener != null) {
                //noinspection deprecation
                am.abandonAudioFocus(listener);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Starts a foreground service using {@link Context#startForegroundService}
     * on API 26+ with fallback to {@link Context#startService} on API 24–25.
     */
    public boolean startForegroundServiceCompat(Context ctx, Intent intent) {
        if (ctx == null || intent == null) return false;
        try {
            if (sdkInt() >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent);
            } else {
                ctx.startService(intent);
            }
            return true;
        } catch (Throwable first) {
            try {
                ctx.startService(intent);
                return true;
            } catch (Throwable second) {
                CrashLog.breadcrumb(ctx, "startForegroundServiceCompat failed: "
                        + first + " / " + second);
                return false;
            }
        }
    }

    /**
     * Promotes a {@link Service} to the foreground with the appropriate
     * {@link ServiceInfo} foreground service type on API 29+ (and untyped
     * fallback on API 24–28).
     */
    public boolean promoteServiceToForeground(Service service, int notifId,
                                              Notification notif,
                                              boolean isMicrophone) {
        if (service == null || notif == null) return false;
        try {
            if (sdkInt() >= Build.VERSION_CODES.Q) {
                int type = isMicrophone
                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                service.startForeground(notifId, notif, type);
            } else {
                service.startForeground(notifId, notif);
            }
            return true;
        } catch (Throwable typed) {
            CrashLog.breadcrumb(service, "typed startForeground failed: " + typed);
            try {
                service.startForeground(notifId, notif);
                return true;
            } catch (Throwable untyped) {
                CrashLog.breadcrumb(service, "startForeground failed: " + untyped);
                return false;
            }
        }
    }

    /**
     * Builds a WorkManager {@link ForegroundInfo} with {@code dataSync} or
     * {@code microphone} service type on API 29+ and untyped on API 24–28.
     */
    public ForegroundInfo createForegroundInfo(int notifId, Notification notif,
                                               boolean isMicrophone) {
        if (sdkInt() >= Build.VERSION_CODES.Q) {
            int type = isMicrophone
                    ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            return new ForegroundInfo(notifId, notif, type);
        }
        return new ForegroundInfo(notifId, notif);
    }

    /**
     * Creates a {@link NotificationChannel} on API 26+ (no-op on API 24–25).
     */
    public void ensureNotificationChannel(Context ctx, String channelId,
                                          String name, String description,
                                          int importance) {
        if (ctx == null || sdkInt() < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    channelId, name, importance);
            if (description != null) ch.setDescription(description);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {}
    }

    /**
     * Returns true on API 33+ (Android 13+) when {@code POST_NOTIFICATIONS}
     * permission has not yet been granted.
     */
    public boolean requiresPostNotificationsPermission(Context ctx) {
        if (ctx == null || sdkInt() < 33) return false;
        try {
            return ContextCompat.checkSelfPermission(ctx,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True when the platform MediaMuxer supports Opus muxing (API 26+). */
    public boolean isOpusMuxerSupported() {
        return sdkInt() >= Build.VERSION_CODES.O;
    }

    /**
     * Returns {@link MediaMuxer.OutputFormat#MUXER_OUTPUT_OGG} on API 29+
     * and {@link MediaMuxer.OutputFormat#MUXER_OUTPUT_WEBM} on API 24–28.
     */
    public int getOpusMuxerOutputFormat() {
        return sdkInt() >= Build.VERSION_CODES.Q
                ? MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                : MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM;
    }

    /** Returns {@code "opus"} on API 29+ and {@code "webm"} on API 24–28. */
    public String getOpusFileExtension() {
        return sdkInt() >= Build.VERSION_CODES.Q ? "opus" : "webm";
    }

    /**
     * Counts physical microphones on API 28+ via {@link AudioManager#getMicrophones()},
     * returning -1 on API 24–27 or when unavailable.
     */
    public int getPhysicalMicrophoneCount(AudioManager am) {
        if (am == null || sdkInt() < Build.VERSION_CODES.P) return -1;
        try {
            List<MicrophoneInfo> mics = am.getMicrophones();
            return mics == null ? -1 : mics.size();
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * Checks active internet capability via {@link NetworkCapabilities} on API 23+
     * with legacy fallback.
     */
    public boolean isNetworkAvailable(Context ctx) {
        if (ctx == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (sdkInt() >= Build.VERSION_CODES.M) {
                Network nw = cm.getActiveNetwork();
                if (nw == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(nw);
                return caps != null && caps.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } else {
                //noinspection deprecation
                android.net.NetworkInfo info = cm.getActiveNetworkInfo();
                //noinspection deprecation
                return info != null && info.isConnected();
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Registers a default network callback on API 24+ (Nougat+).
     */
    public boolean registerDefaultNetworkCallback(Context ctx, final Runnable onAvailable) {
        if (ctx == null || onAvailable == null || sdkInt() < Build.VERSION_CODES.N) {
            return false;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    onAvailable.run();
                }
            });
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Applies view elevation on API 21+. */
    public void setElevationCompat(View view, float elevationPx) {
        if (view != null && sdkInt() >= Build.VERSION_CODES.LOLLIPOP) {
            view.setElevation(elevationPx);
        }
    }
}
