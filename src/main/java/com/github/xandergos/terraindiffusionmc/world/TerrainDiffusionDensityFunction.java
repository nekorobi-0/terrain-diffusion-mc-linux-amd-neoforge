package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

public final class TerrainDiffusionDensityFunction implements DensityFunction.SimpleFunction {
    public static final MapCodec<TerrainDiffusionDensityFunction> CODEC = MapCodec.unit(TerrainDiffusionDensityFunction::new);
    public static final KeyDispatchDataCodec<TerrainDiffusionDensityFunction> CODEC_HOLDER = KeyDispatchDataCodec.of(CODEC);

    @Override
    public double compute(FunctionContext context) {
        int x = context.blockX();
        int z = context.blockZ();
        int y = context.blockY();
        int tileShift = Integer.numberOfTrailingZeros(TerrainDiffusionConfig.tileSize());
        int startX = (x >> tileShift) << tileShift;
        int startZ = (z >> tileShift) << tileShift;
        LocalTerrainProvider.HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(startZ, startX, startZ + TerrainDiffusionConfig.tileSize(), startX + TerrainDiffusionConfig.tileSize());
        if (data == null || data.heightmap == null) return -y;
        int localX = Math.max(0, Math.min(data.width - 1, x - startX));
        int localZ = Math.max(0, Math.min(data.height - 1, z - startZ));
        return HeightConverter.convertToMinecraftHeight(data.heightmap[localZ][localX]) - y;
    }

    @Override public double minValue() { return -64; }
    @Override public double maxValue() { return 1024; }
    @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC_HOLDER; }
}
