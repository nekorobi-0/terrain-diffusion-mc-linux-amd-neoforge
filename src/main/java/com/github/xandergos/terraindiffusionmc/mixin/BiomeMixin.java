package com.github.xandergos.terraindiffusionmc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Biome.class)
public abstract class BiomeMixin {
    @Shadow public abstract float getBaseTemperature();
    @Shadow public abstract boolean hasPrecipitation();

    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusionMc$preventHighAltitudeSnow(BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> callback) {
        if (!hasPrecipitation()) callback.setReturnValue(Biome.Precipitation.NONE);
        else if (getBaseTemperature() >= 0.15F) callback.setReturnValue(Biome.Precipitation.RAIN);
    }
}
