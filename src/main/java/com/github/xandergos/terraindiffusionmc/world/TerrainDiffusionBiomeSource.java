package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

public final class TerrainDiffusionBiomeSource extends BiomeSource {
    public static final MapCodec<TerrainDiffusionBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(RegistryOps.retrieveGetter(Registries.BIOME)).apply(instance, TerrainDiffusionBiomeSource::new));
    private final HolderGetter<Biome> biomes;
    private Map<Short, Holder<Biome>> idMap;

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomes) { this.biomes = biomes; }
    @Override protected MapCodec<? extends BiomeSource> codec() { return CODEC; }
    private Map<Short, Holder<Biome>> ids() {
        if (idMap == null) idMap = Map.ofEntries(
                Map.entry((short) 1, biomes.getOrThrow(Biomes.PLAINS)), Map.entry((short) 3, biomes.getOrThrow(Biomes.SNOWY_PLAINS)),
                Map.entry((short) 5, biomes.getOrThrow(Biomes.DESERT)), Map.entry((short) 6, biomes.getOrThrow(Biomes.SWAMP)),
                Map.entry((short) 8, biomes.getOrThrow(Biomes.FOREST)), Map.entry((short) 15, biomes.getOrThrow(Biomes.TAIGA)),
                Map.entry((short) 16, biomes.getOrThrow(Biomes.SNOWY_TAIGA)), Map.entry((short) 17, biomes.getOrThrow(Biomes.SAVANNA)),
                Map.entry((short) 23, biomes.getOrThrow(Biomes.JUNGLE)), Map.entry((short) 26, biomes.getOrThrow(Biomes.BADLANDS)),
                Map.entry((short) 29, biomes.getOrThrow(Biomes.MEADOW)), Map.entry((short) 31, biomes.getOrThrow(Biomes.GROVE)),
                Map.entry((short) 108, biomes.getOrThrow(Biomes.FOREST)), Map.entry((short) 115, biomes.getOrThrow(Biomes.TAIGA)), Map.entry((short) 116, biomes.getOrThrow(Biomes.SNOWY_TAIGA)));
        return idMap;
    }
    @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return ids().values().stream(); }
    @Override public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        int blockX = QuartPos.toBlock(x), blockZ = QuartPos.toBlock(z), size = TerrainDiffusionConfig.tileSize(), shift = Integer.numberOfTrailingZeros(size);
        int startX = (blockX >> shift) << shift, startZ = (blockZ >> shift) << shift;
        LocalTerrainProvider.HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(startZ, startX, startZ + size, startX + size);
        if (data != null && data.biomeIds != null) {
            int localX = Math.max(0, Math.min(data.width - 1, blockX - startX)), localZ = Math.max(0, Math.min(data.height - 1, blockZ - startZ));
            Holder<Biome> result = ids().get(data.biomeIds[localZ][localX]);
            if (result != null) return result;
        }
        return ids().get((short) 1);
    }
}
