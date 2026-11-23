package com.example.automine;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;

public class AutoMineScreen extends Screen {
    private ButtonWidget durToggle, durMinus, durPlus, hpToggle, hpMinus, hpPlus;

    public AutoMineScreen() {
        super(new LiteralText("AutoMine"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 2 - 60;

        this.addButton(new ButtonWidget(cx - 100, y, 200, 20,
                new LiteralText(AutoMineClient.isEnabled() ? "Stop mining" : "Start mining"), b -> {
            AutoMineClient.setEnabled(!AutoMineClient.isEnabled());
            this.onClose();
        }));

        y += 30;
        durToggle = this.addButton(new ButtonWidget(cx - 100, y, 200, 20, Text.of(""), b -> {
            AutoMineConfig.stopOnLowDurability = !AutoMineConfig.stopOnLowDurability;
            refresh();
        }));
        y += 22;
        durMinus = this.addButton(new ButtonWidget(cx - 100, y, 98, 20, new LiteralText("- 5"), b -> {
            AutoMineConfig.minDurability = Math.max(1, AutoMineConfig.minDurability - 5);
            refresh();
        }));
        durPlus = this.addButton(new ButtonWidget(cx + 2, y, 98, 20, new LiteralText("+ 5"), b -> {
            AutoMineConfig.minDurability = Math.min(500, AutoMineConfig.minDurability + 5);
            refresh();
        }));

        y += 30;
        hpToggle = this.addButton(new ButtonWidget(cx - 100, y, 200, 20, Text.of(""), b -> {
            AutoMineConfig.stopOnLowHealth = !AutoMineConfig.stopOnLowHealth;
            refresh();
        }));
        y += 22;
        hpMinus = this.addButton(new ButtonWidget(cx - 100, y, 98, 20, new LiteralText("- 0.5"), b -> {
            AutoMineConfig.minHalfHearts = Math.max(1, AutoMineConfig.minHalfHearts - 1);
            refresh();
        }));
        hpPlus = this.addButton(new ButtonWidget(cx + 2, y, 98, 20, new LiteralText("+ 0.5"), b -> {
            AutoMineConfig.minHalfHearts = Math.min(19, AutoMineConfig.minHalfHearts + 1);
            refresh();
        }));

        y += 30;
        this.addButton(new ButtonWidget(cx - 100, y, 200, 20, new LiteralText("Done"), b -> this.onClose()));
        refresh();
    }

    private void refresh() {
        durToggle.setMessage(new LiteralText("Stop when tool durability low: "
                + (AutoMineConfig.stopOnLowDurability ? "ON" : "OFF")));
        durMinus.setMessage(new LiteralText("- 5"));
        durPlus.setMessage(new LiteralText("+ 5 (now " + AutoMineConfig.minDurability + ")"));
        durMinus.active = durPlus.active = AutoMineConfig.stopOnLowDurability;

        hpToggle.setMessage(new LiteralText("Stop when health low: "
                + (AutoMineConfig.stopOnLowHealth ? "ON" : "OFF")));
        hpMinus.setMessage(new LiteralText("- 0.5"));
        hpPlus.setMessage(new LiteralText("+ 0.5 (now " + (AutoMineConfig.minHalfHearts / 2.0) + " hearts)"));
        hpMinus.active = hpPlus.active = AutoMineConfig.stopOnLowHealth;
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        drawCenteredText(matrices, this.textRenderer, this.title, this.width / 2, this.height / 2 - 85, 0xFFFFFF);
        super.render(matrices, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        AutoMineConfig.save();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
