package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provides terrain heightmap and biome data from the local WorldPipeline.
 *
 * <p>When scale=1 the pipeline is sampled at native model resolution directly.
 * When scale>1 the pipeline is sampled at native resolution and the result is
 * bilinearly upsampled, giving 1 block = nativeResolution/scale.
 */
public final class LocalTerrainProvider {
    private static final Logger LOG = LoggerFactory.getLogger(LocalTerrainProvider.class);

    private static final float NATIVE_RESOLUTION = WorldPipelineModelConfig.nativeResolution();

    private static final FastNoiseLite ELEV_NOISE_COARSE = makeFnl(99999, 1f/24f, 3, 2f, 0.5f);
    private static final FastNoiseLite ELEV_NOISE_FINE   = makeFnl(88888, 1f/6f,  2, 2f, 0.6f);

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    public static final class HeightmapData {
        public final short[][] heightmap;
        public final short[][] biomeIds;
        public final int width;
        public final int height;

        public HeightmapData(short[][] heightmap, short[][] biomeIds, int width, int height) {
            this.heightmap = heightmap;
            this.biomeIds  = biomeIds;
            this.width     = width;
            this.height    = height;
        }
    }

    private static final int MAX_CACHE_SIZE = 64;
    private static final Object CACHE_LOCK = new Object();
    private static final Map<String, HeightmapData> CACHE = new LinkedHashMap<>(16, 0.75f, true);
    private static final Map<String, Future<HeightmapData>> PENDING = new ConcurrentHashMap<>();
    /** Single thread for pipeline.get() so MemoryTileStore is not accessed concurrently. */
    private static final ThreadPoolExecutor INFERENCE_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "terrain-diffusion-inference");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicLong INFERENCE_TASK_SEQUENCE = new AtomicLong();

    private static volatile LocalTerrainProvider INSTANCE;
    private static long instanceSeed;

    private final WorldPipeline pipeline;

    private LocalTerrainProvider(long seed, PipelineModels models) {
        this.pipeline = new WorldPipeline(seed, models);
    }

    /** Seed is 64-bit world seed. Creates provider once; later worlds only update seed and clear caches (lightweight). */
    public static synchronized void init(long seed) {
        PipelineModels.awaitLoad();
        PipelineModels models = PipelineModels.getInstance();
        if (models == null) throw new IllegalStateException("PipelineModels failed to load");
        if (INSTANCE == null) {
            INSTANCE = new LocalTerrainProvider(seed, models);
            instanceSeed = seed;
        } else if (instanceSeed != seed) {
            INSTANCE.pipeline.setSeed(seed);
            instanceSeed = seed;
            synchronized (CACHE_LOCK) { CACHE.clear(); }
            PENDING.clear();
        }
    }

    public static synchronized LocalTerrainProvider getInstance() {
        if (INSTANCE == null) {
            PipelineModels.awaitLoad();
            PipelineModels models = PipelineModels.getInstance();
            if (models == null) throw new IllegalStateException("PipelineModels failed to load");
            INSTANCE = new LocalTerrainProvider(0L, models);
            instanceSeed = 0L;
        }
        return INSTANCE;
    }

    public static void clearCache() {
        synchronized (CACHE_LOCK) {
            CACHE.clear();
        }
        PENDING.clear();
    }

    // =========================================================================
    // Explorer API — all pipeline calls routed through INFERENCE_EXECUTOR
    // =========================================================================

    /** Returns the current world seed used by the pipeline. */
    public static long getSeed() {
        return instanceSeed;
    }

    /**
     * Run elevation and climate inference on the inference thread.
     *
     * @return float[2]: [0] = elev (H*W), [1] = climate (5*H*W, or null)
     */
    public static float[][] getPipelineData(int i1, int j1, int i2, int j2, boolean withClimate) throws Exception {
        return submitToInferenceThread(() -> getInstance().pipeline.get(i1, j1, i2, j2, withClimate));
    }

    /**
     * Fetch a coarse tensor slice on the inference thread.
     * Coordinates are in coarse index units (1 unit = 256 native pixels).
     *
     * @return FloatTensor with shape [7, ci1-ci0, cj1-cj0]
     */
    public static FloatTensor getPipelineCoarse(int ci0, int cj0, int ci1, int cj1) throws Exception {
        return submitToInferenceThread(() -> getInstance().pipeline.getCoarseSlice(ci0, cj0, ci1, cj1));
    }

    /**
     * Change the world seed used by the pipeline and clear all caches.
     * Note: this also affects terrain generation for new Minecraft chunks.
     */
    public static void changeSeedFromExplorer(long newSeed) throws Exception {
        submitToInferenceThread(() -> {
            LocalTerrainProvider provider = getInstance();
            provider.pipeline.setSeed(newSeed);
            instanceSeed = newSeed;
            synchronized (CACHE_LOCK) { CACHE.clear(); }
            PENDING.clear();
            return null;
        });
    }

    /** Change to a random new seed; returns the new seed value. */
    public static long generateRandomSeedFromExplorer() throws Exception {
        long newSeed = new Random().nextLong();
        changeSeedFromExplorer(newSeed);
        return newSeed;
    }

    private static <T> T submitToInferenceThread(Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        submitInferenceTask(future, 0);
        return future.get();
    }

    /**
     * Fetch heightmap for a block-coordinate region (i=Z, j=X).
     * Coordinates are in block space; scale from config determines blocks per native pixel.
     * Blocks the calling thread until the tile is ready (one tile can take 10–30+ seconds).
     * If the caller is the server or a chunk worker, the game will stall until this returns.
     */
    public HeightmapData fetchHeightmap(int i1, int j1, int i2, int j2) {
        String key = i1 + "," + j1 + "," + i2 + "," + j2;
        synchronized (CACHE_LOCK) {
            HeightmapData cached = CACHE.get(key);
            if (cached != null) return cached;
        }

        int scale = WorldScaleManager.getCurrentScale();
        FutureTask<HeightmapData> task = new FutureTask<>(() -> {
            synchronized (CACHE_LOCK) {
                HeightmapData cached = CACHE.get(key);
                if (cached != null) {
                    PENDING.remove(key);
                    return cached;
                }
            }

            long generationStartNanos = System.nanoTime();
            long computedWindowCountBefore = pipeline.getTotalComputedWindowCount();
            int requestedWidth = j2 - j1;
            int requestedHeight = i2 - i1;
            int tileSize = TerrainDiffusionConfig.tileSize();
            int prefetchTiles = TerrainDiffusionConfig.prefetchTiles();
            boolean prefetch = prefetchTiles > 1 && requestedWidth == tileSize && requestedHeight == tileSize;

            HeightmapData data = scale <= 1
                    ? handle1x(i1, j1, i2, j2)
                    : handleUpsampled(i1, j1, i2, j2, scale);
            long computedWindowCountAfter = pipeline.getTotalComputedWindowCount();
            long generationElapsedMillis = (System.nanoTime() - generationStartNanos) / 1_000_000L;

            long newlyComputedWindowCount = computedWindowCountAfter - computedWindowCountBefore;
            LOG.info(
                    "Terrain Diffusion ({}) finished request {}x{} in {} ms ({} newly computed windows)",
                    OnnxModel.getResolvedInferenceProvider(), requestedWidth, requestedHeight,
                    generationElapsedMillis, newlyComputedWindowCount);
            synchronized (CACHE_LOCK) {
                CACHE.put(key, data);
                evictLruTo(MAX_CACHE_SIZE);
            }
            PENDING.remove(key);
            if (prefetch) {
                schedulePrefetch(i1, j1, tileSize, prefetchTiles, scale);
            }
            return data;
        });
        Future<HeightmapData> existing = PENDING.putIfAbsent(key, task);
        FutureTask<HeightmapData> toRun = (existing == null) ? task : (FutureTask<HeightmapData>) existing;
        if (existing == null) {
            int regionWidth = j2 - j1;
            int regionHeight = i2 - i1;
            LOG.info(
                    "Terrain Diffusion ({}) uncached region requested: ({}, {})-({}, {}) size {}x{}",
                    OnnxModel.getResolvedInferenceProvider(), j1, i1, j2, i2, regionWidth, regionHeight);
            submitInferenceTask(toRun, 0);
        }
        try {
            return toRun.get();
        } catch (Exception e) {
            PENDING.remove(key);
            throw new RuntimeException("Terrain tile failed: " + key, e);
        }
    }

    private static void evictLruTo(int maxSize) {
        Iterator<Map.Entry<String, HeightmapData>> it = CACHE.entrySet().iterator();
        while (CACHE.size() > maxSize && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private void schedulePrefetch(int requestedI1, int requestedJ1, int tileSize, int tilesPerSide, int scale) {
        int batchSize = tileSize * tilesPerSide;
        int batchI1 = Math.floorDiv(requestedI1, batchSize) * batchSize;
        int batchJ1 = Math.floorDiv(requestedJ1, batchSize) * batchSize;
        for (int tileRow = 0; tileRow < tilesPerSide; tileRow++) {
            for (int tileColumn = 0; tileColumn < tilesPerSide; tileColumn++) {
                int tileI1 = batchI1 + tileRow * tileSize;
                int tileJ1 = batchJ1 + tileColumn * tileSize;
                if (tileI1 == requestedI1 && tileJ1 == requestedJ1) continue;
                submitInferenceTask(() -> prefetchTile(tileI1, tileJ1, tileSize, scale), 1);
            }
        }
    }

    private void prefetchTile(int i1, int j1, int tileSize, int scale) {
        String key = i1 + "," + j1 + "," + (i1 + tileSize) + "," + (j1 + tileSize);
        synchronized (CACHE_LOCK) {
            if (CACHE.containsKey(key)) return;
        }
        if (PENDING.containsKey(key)) return;

        HeightmapData data = scale <= 1
                ? handle1x(i1, j1, i1 + tileSize, j1 + tileSize)
                : handleUpsampled(i1, j1, i1 + tileSize, j1 + tileSize, scale);
        synchronized (CACHE_LOCK) {
            if (!CACHE.containsKey(key)) {
                CACHE.put(key, data);
                evictLruTo(MAX_CACHE_SIZE);
            }
        }
    }

    private static void submitInferenceTask(Runnable task, int priority) {
        INFERENCE_EXECUTOR.execute(new PrioritizedInferenceTask(task, priority, INFERENCE_TASK_SEQUENCE.getAndIncrement()));
    }

    private record PrioritizedInferenceTask(Runnable task, int priority, long sequence)
            implements Runnable, Comparable<PrioritizedInferenceTask> {
        @Override
        public void run() {
            task.run();
        }

        @Override
        public int compareTo(PrioritizedInferenceTask other) {
            int priorityComparison = Integer.compare(priority, other.priority);
            return priorityComparison != 0 ? priorityComparison : Long.compare(sequence, other.sequence);
        }
    }

    // =========================================================================
    // Scale == 1: block coords == native pixel coords
    // =========================================================================

    private HeightmapData handle1x(int i1, int j1, int i2, int j2) {
        int H = i2 - i1, W = j2 - j1;

        float[] elevPadded = pipeline.get(i1 - 1, j1 - 1, i2 + 1, j2 + 1, false)[0];
        float[][] out = pipeline.get(i1, j1, i2, j2, true);
        float[] elevFlat = out[0];
        float[] climate  = out[1];

        short[] biomeFlat = BiomeClassifier.classify(elevFlat, climate, i1, j1, elevPadded, H, W, NATIVE_RESOLUTION);
        return buildHeightmapData(elevFlat, biomeFlat, H, W);
    }

    // =========================================================================
    // Scale > 1: pipeline at native res → bilinear upsample to block res
    // =========================================================================

    private HeightmapData handleUpsampled(int i1, int j1, int i2, int j2, int scale) {
        int H = i2 - i1, W = j2 - j1;
        float pixelSizeM = NATIVE_RESOLUTION / scale;

        // Convert block coords to native pixel coords
        int i1n = Math.floorDiv(i1, scale);
        int j1n = Math.floorDiv(j1, scale);
        int i2n = -Math.floorDiv(-i2, scale);
        int j2n = -Math.floorDiv(-j2, scale);

        // 2-pixel native padding (1 for bilinear + 1 for slope)
        int i1p = i1n - 2, j1p = j1n - 2;
        int i2p = i2n + 2, j2p = j2n + 2;
        int nH = i2p - i1p, nW = j2p - j1p;

        float[][] out = pipeline.get(i1p, j1p, i2p, j2p, true);
        float[] elevNativeFlat    = out[0];
        float[] climateNativeFlat = out[1];

        // Bilinear upsample elevation: (nH, nW) → (nH*scale, nW*scale)
        float[][] elevNative2D = to2D(elevNativeFlat, nH, nW);
        float[][] elevUp = LaplacianUtils.bilinearResize(elevNative2D, nH * scale, nW * scale);

        // Crop offsets in the upsampled array
        int padUp   = 2 * scale;
        int offsetI = i1 - i1n * scale;
        int offsetJ = j1 - j1n * scale;
        int cropI1  = padUp + offsetI;
        int cropJ1  = padUp + offsetJ;

        float[] elevSmooth = cropFlat(elevUp, cropI1,     cropJ1,     H,   W,   nH * scale, nW * scale);
        float[] elevPadded = cropFlat(elevUp, cropI1 - 1, cropJ1 - 1, H+2, W+2, nH * scale, nW * scale);

        float[] elevOut = addElevationNoise(elevSmooth, elevPadded, i1, j1, H, W, pixelSizeM);

        // Classify at model resolution. Classifying a bilinearly interpolated climate
        // field at every Minecraft block turns threshold boundaries into tiny patches.
        float[] nativeElevPadded = pipeline.get(i1p - 1, j1p - 1, i2p + 1, j2p + 1, false)[0];
        short[] nativeBiomeFlat = BiomeClassifier.classify(
                elevNativeFlat, climateNativeFlat, i1p, j1p,
                nativeElevPadded, nH, nW, NATIVE_RESOLUTION);
        short[] biomeFlat = upsampleBiomeNearest(nativeBiomeFlat, nH, nW, cropI1, cropJ1, H, W, scale);
        return buildHeightmapData(elevOut, biomeFlat, H, W);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private float[] addElevationNoise(float[] elevSmooth, float[] elevPadded,
                                       int i1, int j1, int H, int W, float pixelSizeM) {
        float[] slopeGradient = sobelGradient(elevPadded, H + 2, W + 2, H, W);
        float[] elevOut = elevSmooth.clone();
        float normFactor = 40f * pixelSizeM / NATIVE_RESOLUTION;
        float ampC = 100f * pixelSizeM / NATIVE_RESOLUTION;
        float ampF = 70f  * pixelSizeM / NATIVE_RESOLUTION;

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float e = elevSmooth[idx];
                if (e < 0f) continue;

                float grad = slopeGradient[idx];
                float sf = Math.min(1f, grad / normFactor);
                sf = sf * sf * (float) Math.sqrt(sf);

                float nx = j1 + c, ny = i1 + r;
                elevOut[idx] = e
                        + ELEV_NOISE_COARSE.GetNoise(nx, ny) * ampC * sf
                        + ELEV_NOISE_FINE.GetNoise(nx, ny)   * ampF * sf;
            }
        }
        return elevOut;
    }

    private static float[] sobelGradient(float[] padded, int pH, int pW, int H, int W) {
        final float[] SOBEL_X = {-1,0,1, -2,0,2, -1,0,1};
        final float[] SOBEL_Y = {-1,-2,-1, 0,0,0, 1,2,1};
        float[] result = new float[H * W];
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float dx = 0, dy = 0;
                for (int k = 0; k < 9; k++) {
                    float v = padded[(r + k/3) * pW + (c + k%3)];
                    dx += v * SOBEL_X[k];
                    dy += v * SOBEL_Y[k];
                }
                dx /= 8f; dy /= 8f;
                result[r * W + c] = (float) Math.sqrt(dx * dx + dy * dy);
            }
        }
        return result;
    }

    private static short[] upsampleBiomeNearest(short[] nativeBiome, int nH, int nW,
                                                 int cropI1, int cropJ1, int H, int W, int scale) {
        short[] result = new short[H * W];
        for (int r = 0; r < H; r++) {
            int sourceRow = Math.max(0, Math.min(nH - 1, (cropI1 + r) / scale));
            for (int c = 0; c < W; c++) {
                int sourceColumn = Math.max(0, Math.min(nW - 1, (cropJ1 + c) / scale));
                result[r * W + c] = nativeBiome[sourceRow * nW + sourceColumn];
            }
        }
        return result;
    }

    private static float[] cropFlat(float[][] src, int r0, int c0, int H, int W, int srcH, int srcW) {
        float[] out = new float[H * W];
        for (int r = 0; r < H; r++) {
            int sr = Math.max(0, Math.min(srcH - 1, r0 + r));
            for (int c = 0; c < W; c++)
                out[r * W + c] = src[sr][Math.max(0, Math.min(srcW - 1, c0 + c))];
        }
        return out;
    }

    private static float[][] to2D(float[] flat, int H, int W) {
        float[][] a = new float[H][W];
        for (int r = 0; r < H; r++) System.arraycopy(flat, r * W, a[r], 0, W);
        return a;
    }

    private static HeightmapData buildHeightmapData(float[] elevFlat, short[] biomeFlat, int H, int W) {
        short[][] heightmap = new short[H][W];
        short[][] biomeIds  = new short[H][W];
        for (int r = 0; r < H; r++)
            for (int c = 0; c < W; c++) {
                float e = elevFlat[r * W + c];
                heightmap[r][c] = (short) Math.max(-32768, Math.min(32767, (int) Math.floor(e)));
                biomeIds[r][c]  = biomeFlat[r * W + c];
            }
        return new HeightmapData(heightmap, biomeIds, W, H);
    }
}
