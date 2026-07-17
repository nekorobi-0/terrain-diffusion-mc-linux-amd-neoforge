package com.github.xandergos.terraindiffusionmc;

import com.github.xandergos.terraindiffusionmc.explorer.ExplorerServer;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.ModelAssetManager;
import com.github.xandergos.terraindiffusionmc.pipeline.PipelineModels;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionDensityFunction;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.mojang.brigadier.context.CommandContext;
import java.net.URI;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(TerrainDiffusionMc.MOD_ID)
public final class TerrainDiffusionMc {
    public static final String MOD_ID = "terrain_diffusion_mc";
    public static final String RESOURCE_NAMESPACE = "terrain-diffusion-mc";
    private static final Logger LOG = LoggerFactory.getLogger(TerrainDiffusionMc.class);

    public TerrainDiffusionMc(IEventBus modBus) {
        LOG.info("Initializing terrain-diffusion-mc");
        modBus.addListener(TerrainDiffusionMc::registerWorldgenCodecs);
        ModelAssetManager.ensureAssetsReady();
        PipelineModels.load();
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) -> LocalTerrainProvider.clearCache());
        NeoForge.EVENT_BUS.addListener((LevelEvent.Load event) -> {
            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level && level.dimension() == Level.OVERWORLD) {
                WorldScaleManager.initializeForWorld(level);
                LocalTerrainProvider.init(level.getSeed());
            }
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> ExplorerServer.stop());
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> event.getDispatcher().register(Commands.literal("td-explore").executes(TerrainDiffusionMc::executeExplore)));
    }

    private static void registerWorldgenCodecs(RegisterEvent event) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(RESOURCE_NAMESPACE, "terrain_diffusion");
        event.register(Registries.BIOME_SOURCE, id, () -> TerrainDiffusionBiomeSource.CODEC);
        event.register(Registries.DENSITY_FUNCTION_TYPE, id, () -> TerrainDiffusionDensityFunction.CODEC);
    }

    private static int executeExplore(CommandContext<CommandSourceStack> context) {
        try {
            String url = "http://localhost:" + ExplorerServer.startIfNotRunning();
            Component link = Component.literal(url).withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, URI.create(url).toString())).withUnderlined(true));
            context.getSource().sendSuccess(() -> Component.literal("Terrain Explorer: ").append(link), false);
        } catch (Exception exception) {
            LOG.error("Failed to start terrain explorer", exception);
            context.getSource().sendFailure(Component.literal("Failed to start terrain explorer: " + exception.getMessage()));
        }
        return 1;
    }
}
