package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.concurrent.atomic.AtomicLong;

public class PipelineTest {

    private static long getProcessGpuMemMB() {
        long pid = ProcessHandle.current().pid();
        try {
            Process p = new ProcessBuilder(
                    "nvidia-smi",
                    "--query-compute-apps=pid,used_memory",
                    "--format=csv,noheader,nounits"
            ).redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            for (String line : output.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length >= 2 && parts[0].trim().equals(String.valueOf(pid))) {
                    return Long.parseLong(parts[1].trim());
                }
            }
            return 0;
        } catch (Exception e) {
            return -1;
        }
    }

    private static long getTotalGpuMemUsedMB() {
        try {
            Process p = new ProcessBuilder(
                    "nvidia-smi",
                    "--query-gpu=memory.used",
                    "--format=csv,noheader,nounits"
            ).redirectErrorStream(true).start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return Long.parseLong(output.split("\n")[0].trim());
        } catch (Exception e) {
            return -1;
        }
    }

    public static void main(String[] args) throws Exception {
        long seed = -5408366058459925370L;
        int scale = 2;
        int tileSize = Integer.getInteger("terrain_diffusion.benchmark_tile_size", 256);
        int benchmarkRegions = Integer.getInteger("terrain_diffusion.benchmark_regions", 1);
        if (tileSize <= 0 || (tileSize & (tileSize - 1)) != 0) {
            throw new IllegalArgumentException("terrain_diffusion.benchmark_tile_size must be a positive power of two");
        }
        if (benchmarkRegions <= 0) {
            throw new IllegalArgumentException("terrain_diffusion.benchmark_regions must be positive");
        }

        int blockX = -16160, blockZ = -59510;
        int blockStartX = Math.floorDiv(blockX, tileSize) * tileSize;
        int blockStartZ = Math.floorDiv(blockZ, tileSize) * tileSize;
        int blockEndX = blockStartX + tileSize;
        int blockEndZ = blockStartZ + tileSize;

        System.out.printf("blockStart: X=%d Z=%d  blockEnd: X=%d Z=%d%n",
                blockStartX, blockStartZ, blockEndX, blockEndZ);

        int i1n = Math.floorDiv(blockStartZ, scale);
        int j1n = Math.floorDiv(blockStartX, scale);
        int i2n = -Math.floorDiv(-blockEndZ, scale);
        int j2n = -Math.floorDiv(-blockEndX, scale);
        int i1p = i1n - 2, j1p = j1n - 2;
        int i2p = i2n + 2, j2p = j2n + 2;
        int nH = i2p - i1p, nW = j2p - j1p;

        System.out.printf("Native range: i=[%d,%d) j=[%d,%d) nH=%d nW=%d%n",
                i1p, i2p, j1p, j2p, nH, nW);

        long baselineTotal = getTotalGpuMemUsedMB();
        System.out.printf("Baseline total GPU memory: %d MB%n", baselineTotal);

        AtomicLong peakProcessMB = new AtomicLong(0);
        AtomicLong peakTotalMB = new AtomicLong(baselineTotal < 0 ? 0 : baselineTotal);
        Thread monitor = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                long proc = getProcessGpuMemMB();
                if (proc >= 0) peakProcessMB.updateAndGet(v -> Math.max(v, proc));
                long total = getTotalGpuMemUsedMB();
                if (total >= 0) peakTotalMB.updateAndGet(v -> Math.max(v, total));
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
        }, "vram-monitor");
        monitor.setDaemon(true);
        monitor.start();

        try (WorldPipeline pipeline = new WorldPipeline(seed)) {
            pipeline.resetModelRunTiming();
            long inferenceStart = System.nanoTime();
            float[] elevNative = pipeline.get(i1p, j1p, i2p, j2p, false)[0];
            long inferenceElapsedNanos = System.nanoTime() - inferenceStart;
            System.out.printf("Uncached pipeline generation: %.3f s%n", inferenceElapsedNanos / 1_000_000_000.0);
            System.out.println("ONNX stage timing: " + pipeline.getModelRunTimingSummary());

            long cachedStart = System.nanoTime();
            pipeline.get(i1p, j1p, i2p, j2p, false);
            long cachedElapsedNanos = System.nanoTime() - cachedStart;
            System.out.printf("Cached pipeline retrieval: %.3f s%n", cachedElapsedNanos / 1_000_000_000.0);

            for (int region = 1; region < benchmarkRegions; region++) {
                int shift = region * (tileSize / scale);
                long regionStart = System.nanoTime();
                pipeline.get(i1p, j1p + shift, i2p, j2p + shift, false);
                long regionElapsedNanos = System.nanoTime() - regionStart;
                System.out.printf("Adjacent uncached region %d: %.3f s%n",
                        region + 1, regionElapsedNanos / 1_000_000_000.0);
            }

            float[][] native2D = new float[nH][nW];
            for (int r = 0; r < nH; r++)
                System.arraycopy(elevNative, r * nW, native2D[r], 0, nW);
            float[][] elevUp = LaplacianUtils.bilinearResize(native2D, nH * scale, nW * scale);

            int padUp   = 2 * scale;
            int offsetI = blockStartZ - i1n * scale;
            int offsetJ = blockStartX - j1n * scale;
            int cropI1  = padUp + offsetI;
            int cropJ1  = padUp + offsetJ;

            int localZ = blockZ - blockStartZ;
            int localX = blockX - blockStartX;
            float elev = elevUp[cropI1 + localZ][cropJ1 + localX];

            System.out.printf("Pipeline elev at block (X=%d Z=%d): %.2f m%n", blockX, blockZ, elev);
            System.out.printf("Native pixel approx: i=%.1f j=%.1f%n",
                    i1p + (cropI1 + localZ) / (float)scale,
                    j1p + (cropJ1 + localX) / (float)scale);

            float RESOLUTION = WorldPipelineModelConfig.nativeResolution() / scale;
            float GAMMA = 1.0f, C = 30.0f;
            int SEA_LEVEL = 63;
            double transformed = Math.pow(elev + C, GAMMA) - Math.pow(C, GAMMA);
            int mcY = (int)(transformed / RESOLUTION) + SEA_LEVEL;

            System.out.printf("Expected Minecraft Y at (X=%d Z=%d): %d  (resolution=%.1f)%n",
                    blockX, blockZ, mcY, RESOLUTION);
            System.out.printf("User reports: ~420 blocks%n");
        }

        monitor.interrupt();
        monitor.join(2000);

        long peakProc  = peakProcessMB.get();
        long peakTotal = peakTotalMB.get();
        long delta     = baselineTotal >= 0 ? peakTotal - baselineTotal : -1;

        System.out.println("=== VRAM Report ===");
        System.out.printf("Baseline total GPU:     %d MB%n", baselineTotal);
        System.out.printf("Peak total GPU:         %d MB%n", peakTotal);
        System.out.printf("Pipeline delta (total): %d MB%n", delta);
        System.out.printf("Peak process-specific:  %d MB%n", peakProc);

        long reportedMB = peakProc > 0 ? peakProc : delta;
        if (reportedMB > 2500) {
            System.err.printf("FAIL: pipeline VRAM %d MB exceeds 2500 MB limit%n", reportedMB);
            System.exit(1);
        } else if (reportedMB >= 0) {
            System.out.printf("PASS: pipeline VRAM %d MB is within 2500 MB limit%n", reportedMB);
        } else {
            System.out.println("VRAM measurement unavailable (nvidia-smi not found)");
        }
    }
}
