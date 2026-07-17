package com.github.xandergos.terraindiffusionmc.mixin.client;

import com.github.xandergos.terraindiffusionmc.client.WorldScaleSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Makes the vanilla Customize control available for the Terrain Diffusion preset. */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
public abstract class CreateWorldScreenWorldTabMixin {
    private static final ResourceKey<WorldPreset> TERRAIN_DIFFUSION = ResourceKey.create(
            net.minecraft.core.registries.Registries.WORLD_PRESET,
            ResourceLocation.fromNamespaceAndPath("terrain-diffusion-mc", "terrain_diffusion"));

    @Shadow @Final private CreateWorldScreen this$0;
    @Shadow @Final private Button customizeTypeButton;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void terrainDiffusionMc$enableCustomizeForPreset(CallbackInfo callback) {
        this$0.getUiState().addListener(state -> {
            if (isTerrainDiffusionPreset()) customizeTypeButton.active = true;
        });
    }

    @Inject(method = "openPresetEditor", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusionMc$openScaleScreen(CallbackInfo callback) {
        if (isTerrainDiffusionPreset()) {
            Minecraft.getInstance().setScreen(new WorldScaleSettingsScreen(this$0));
            callback.cancel();
        }
    }

    private boolean isTerrainDiffusionPreset() {
        return this$0.getUiState().getWorldType().preset() != null
                && this$0.getUiState().getWorldType().preset().unwrapKey().filter(TERRAIN_DIFFUSION::equals).isPresent();
    }
}
