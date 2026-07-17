package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

public final class TerrainDiffusionDensityFunction implements DensityFunction.SimpleFunction {
    public static final MapCodec<TerrainDiffusionDensityFunction> CODEC = MapCodec.unit(TerrainDiffusionDensityFunction::new);
    public static final KeyDispatchDataCodec<TerrainDiffusionDensityFunction> CODEC_HOLDER = KeyDispatchDataCodec.of(CODEC);
    private static final ThreadLocal<TileCache> TILE_CACHE = new ThreadLocal<>();

    @Override
    public double compute(FunctionContext context) {
        int x = context.blockX();
        int z = context.blockZ();
        int y = context.blockY();
        int tileShift = Integer.numberOfTrailingZeros(TerrainDiffusionConfig.tileSize());
        int startX = (x >> tileShift) << tileShift;
        int startZ = (z >> tileShift) << tileShift;
        long cacheEpoch = LocalTerrainProvider.cacheEpoch();
        TileCache cached = TILE_CACHE.get();
        LocalTerrainProvider.HeightmapData data;
        if (cached != null && cached.epoch == cacheEpoch && cached.startX == startX && cached.startZ == startZ) {
            data = cached.data;
        } else {
            int tileSize = TerrainDiffusionConfig.tileSize();
            data = LocalTerrainProvider.getInstance().fetchHeightmap(startZ, startX, startZ + tileSize, startX + tileSize);
            TILE_CACHE.set(new TileCache(cacheEpoch, startX, startZ, data));
        }
        if (data == null || data.heightmap == null) return -y;
        int localX = Math.max(0, Math.min(data.width - 1, x - startX));
        int localZ = Math.max(0, Math.min(data.height - 1, z - startZ));
        return HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]) - y;
    }

    @Override
    public void fillArray(double[] values, ContextProvider contextProvider) {
        if (values.length == 0) return;

        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileShift = Integer.numberOfTrailingZeros(tileSize);
        long cacheEpoch = LocalTerrainProvider.cacheEpoch();
        TileCache cached = TILE_CACHE.get();
        boolean hasTile = cached != null && cached.epoch == cacheEpoch;
        int startX = hasTile ? cached.startX : 0;
        int startZ = hasTile ? cached.startZ : 0;
        LocalTerrainProvider.HeightmapData data = hasTile ? cached.data : null;
        LocalTerrainProvider provider = LocalTerrainProvider.getInstance();
        int[] heightLookup = HeightConverter.minecraftHeightLookup();

        for (int index = 0; index < values.length; index++) {
            FunctionContext context = contextProvider.forIndex(index);
            int x = context.blockX();
            int z = context.blockZ();
            int y = context.blockY();
            int requestedStartX = (x >> tileShift) << tileShift;
            int requestedStartZ = (z >> tileShift) << tileShift;

            if (!hasTile || startX != requestedStartX || startZ != requestedStartZ) {
                startX = requestedStartX;
                startZ = requestedStartZ;
                data = provider.fetchHeightmap(startZ, startX, startZ + tileSize, startX + tileSize);
                hasTile = true;
            }

            if (data == null || data.heightmap == null) {
                values[index] = -y;
                continue;
            }

            int localX = x - startX;
            int localZ = z - startZ;
            if (localX >= 0 && localX < data.width && localZ >= 0 && localZ < data.height) {
                values[index] = heightLookup[data.heightmap[localZ][localX] - Short.MIN_VALUE] - y;
            } else {
                localX = Math.max(0, Math.min(data.width - 1, localX));
                localZ = Math.max(0, Math.min(data.height - 1, localZ));
                values[index] = heightLookup[data.heightmap[localZ][localX] - Short.MIN_VALUE] - y;
            }
        }

        TILE_CACHE.set(new TileCache(cacheEpoch, startX, startZ, data));
    }

    @Override public double minValue() { return -64; }
    @Override public double maxValue() { return 1024; }
    @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC_HOLDER; }

    private record TileCache(long epoch, int startX, int startZ, LocalTerrainProvider.HeightmapData data) {
    }
}
