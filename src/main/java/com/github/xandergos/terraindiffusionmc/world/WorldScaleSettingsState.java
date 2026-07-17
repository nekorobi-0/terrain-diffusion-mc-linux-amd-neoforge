package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

public final class WorldScaleSettingsState extends SavedData {
    private int scale;
    private boolean explicitScale;

    private WorldScaleSettingsState(int scale, boolean explicitScale) {
        this.scale = WorldScaleManager.clampScale(scale);
        this.explicitScale = explicitScale;
    }

    public static WorldScaleSettingsState createDefault() { return new WorldScaleSettingsState(WorldScaleManager.DEFAULT_SCALE, false); }
    public static WorldScaleSettingsState load(CompoundTag tag, HolderLookup.Provider provider) {
        return new WorldScaleSettingsState(tag.getInt("scale"), tag.getBoolean("explicit_scale"));
    }
    public static final SavedData.Factory<WorldScaleSettingsState> TYPE = new SavedData.Factory<>(WorldScaleSettingsState::createDefault, WorldScaleSettingsState::load);
    public int getScale() { return scale; }
    public boolean hasExplicitScale() { return explicitScale; }
    public void setScale(int scale) { this.scale = WorldScaleManager.clampScale(scale); this.explicitScale = true; setDirty(); }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) { tag.putInt("scale", scale); tag.putBoolean("explicit_scale", explicitScale); return tag; }
}
