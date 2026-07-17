package com.github.xandergos.terraindiffusionmc.client;

import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleSelectionState;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Selects the terrain scale to persist when the new overworld first loads. */
public final class WorldScaleSettingsScreen extends Screen {
    private final Screen parent;
    private EditBox scale;
    private Component error = Component.empty();

    public WorldScaleSettingsScreen(Screen parent) {
        super(Component.translatable("terrain-diffusion-mc.world_settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int x = width / 2;
        scale = new EditBox(font, x - 40, height / 2 - 10, 80, 20, Component.literal("World Scale"));
        scale.setValue(String.valueOf(WorldScaleSelectionState.getPendingScaleOrDefault()));
        addRenderableWidget(scale);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> save()).bounds(x - 85, height / 2 + 20, 80, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose()).bounds(x + 5, height / 2 + 20, 80, 20).build());
        setInitialFocus(scale);
    }

    private void save() {
        try {
            int value = Integer.parseInt(scale.getValue().trim());
            if (value >= 1 && value <= WorldScaleManager.MAX_SCALE) {
                WorldScaleSelectionState.setPendingScale(value);
                onClose();
                return;
            }
        } catch (NumberFormatException ignored) {
        }
        error = Component.literal("Scale must be an integer between 1 and 6");
    }

    @Override public void onClose() { minecraft.setScreen(parent); }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 24, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.literal("Enter an integer value (1-6)"), width / 2, height / 2 - 34, 0xAAAAAA);
        graphics.drawCenteredString(font, error, width / 2, height / 2 + 48, 0xFF5555);
    }
}
