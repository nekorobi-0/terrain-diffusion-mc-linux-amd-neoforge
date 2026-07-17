package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.WorldPipelineModelConfig;

public class HeightConverter {
    private static final int SEA_LEVEL = 63;
    private static final short MAX_PIPELINE_METERS = 10_000;
    private static volatile HeightLookup heightLookup;

    private static float getResolutionForScale(int configuredScale) {
        return WorldPipelineModelConfig.nativeResolution() / WorldScaleManager.clampScale(configuredScale);
    }

    public static int convertToMinecraftHeight(short meters) {
        int scale = WorldScaleManager.getCurrentScale();
        HeightLookup lookup = heightLookup;
        if (lookup == null || lookup.scale != scale) {
            lookup = getOrBuildLookup(scale);
        }
        return lookup.minecraftHeights[meters - Short.MIN_VALUE];
    }

    public static int convertToMinecraftHeight(short meters, int configuredScale) {
        int baseY;
        float resolution = getResolutionForScale(configuredScale);

        if (meters >= 0) {
            baseY = (int) (meters / resolution);
        } else {
            baseY = (int) (-Math.sqrt(Math.abs(meters) + 10) + Math.sqrt(10.0)) - 1;
        }

        return baseY + SEA_LEVEL;
    }

    /**
     * Returns the highest generated block Y expected from pipeline output for a given scale.
     */
    public static int getMaxGeneratedYForScale(int configuredScale) {
        return convertToMinecraftHeight(MAX_PIPELINE_METERS, configuredScale);
    }

    private static HeightLookup getOrBuildLookup(int scale) {
        synchronized (HeightConverter.class) {
            HeightLookup existing = heightLookup;
            if (existing != null && existing.scale == scale) {
                return existing;
            }
            int[] minecraftHeights = new int[1 << 16];
            for (int value = Short.MIN_VALUE; value <= Short.MAX_VALUE; value++) {
                minecraftHeights[value - Short.MIN_VALUE] = convertToMinecraftHeight((short) value, scale);
            }
            HeightLookup created = new HeightLookup(scale, minecraftHeights);
            heightLookup = created;
            return created;
        }
    }

    private record HeightLookup(int scale, int[] minecraftHeights) {
    }
}
