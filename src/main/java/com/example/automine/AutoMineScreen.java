package com.example.automine;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

public class AutoMineScreen extends Screen {
    private ButtonWidget startBtn, dur, durMinus, durPlus, inv;
    private int infoY;

    public AutoMineScreen() {
        super(new LiteralText("AutoMine"));
    }

    private static String onOff(boolean v) {
        return v ? "УВІМК." : "ВИМК.";
    }

    /** Блок, на який дивишся; якщо такого немає, то блок під ногами. */
    private BlockPos pickPos() {
        MinecraftClient mc = MinecraftClient.getInstance();
        HitResult hr = mc.crosshairTarget;
        if (hr != null && hr.getType() == HitResult.Type.BLOCK) {
            return ((BlockHitResult) hr).getBlockPos();
        }
        return mc.player.getBlockPos();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int L = cx - 155, R = cx + 5, W = 150;
        int y = this.height / 2 - 84;

        startBtn = this.addButton(new ButtonWidget(L, y, 310, 20, Text.of(""), b -> {
            if (AutoMineClient.isEnabled()) {
                AutoMineClient.stop(MinecraftClient.getInstance(), "AutoMine: ВИМК.");
                refresh();
            } else if (AutoMineClient.start(MinecraftClient.getInstance())) {
                this.onClose();
            }
        }));

        y += 24;
        this.addButton(new ButtonWidget(L, y, W, 20, new LiteralText("Поставити кут 1"), b -> {
            AutoMineConfig.pos1 = pickPos();
            AutoMineConfig.save();
        }));
        this.addButton(new ButtonWidget(R, y, W, 20, new LiteralText("Поставити кут 2"), b -> {
            AutoMineConfig.pos2 = pickPos();
            AutoMineConfig.save();
        }));

        y += 24;
        infoY = y + 2;
        y += 14;
        this.addButton(new ButtonWidget(L, y, 310, 20, new LiteralText("Скинути регіон"), b -> {
            AutoMineConfig.pos1 = null;
            AutoMineConfig.pos2 = null;
            AutoMineConfig.save();
        }));

        y += 24;
        dur = this.addButton(new ButtonWidget(L, y, 310, 20, Text.of(""), b -> {
            AutoMineConfig.stopOnLowDurability = !AutoMineConfig.stopOnLowDurability;
            refresh();
        }));

        y += 24;
        durMinus = this.addButton(new ButtonWidget(L, y, 153, 20, new LiteralText("- 5"), b -> {
            AutoMineConfig.minDurability = Math.max(1, AutoMineConfig.minDurability - 5);
            refresh();
        }));
        durPlus = this.addButton(new ButtonWidget(L + 157, y, 153, 20, new LiteralText("+ 5"), b -> {
            AutoMineConfig.minDurability = Math.min(500, AutoMineConfig.minDurability + 5);
            refresh();
        }));

        y += 24;
        inv = this.addButton(new ButtonWidget(L, y, 310, 20, Text.of(""), b -> {
            AutoMineConfig.stopOnFullInventory = !AutoMineConfig.stopOnFullInventory;
            refresh();
        }));

        y += 28;
        this.addButton(new ButtonWidget(L, y, 310, 20, new LiteralText("Готово"), b -> this.onClose()));
        refresh();
    }

    private void refresh() {
        startBtn.setMessage(new LiteralText(AutoMineClient.isEnabled() ? "Зупинити копання" : "Почати копання"));
        dur.setMessage(new LiteralText("Стоп, якщо міцність кирки менша за " + AutoMineConfig.minDurability
                + ": " + onOff(AutoMineConfig.stopOnLowDurability)));
        inv.setMessage(new LiteralText("Стоп, якщо інвентар повний: " + onOff(AutoMineConfig.stopOnFullInventory)));
        durMinus.active = durPlus.active = AutoMineConfig.stopOnLowDurability;
    }

    private static String fmt(BlockPos p) {
        return p == null ? "-" : p.getX() + ", " + p.getY() + ", " + p.getZ();
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        drawCenteredText(matrices, this.textRenderer, this.title, this.width / 2, this.height / 2 - 100, 0xFFFFFF);
        super.render(matrices, mouseX, mouseY, delta);
        String info = "Кут 1: " + fmt(AutoMineConfig.pos1) + "   Кут 2: " + fmt(AutoMineConfig.pos2);
        if (AutoMineConfig.hasRegion()) {
            info += "   (" + AutoMineConfig.volume() + " блоків)";
        }
        drawCenteredText(matrices, this.textRenderer, new LiteralText(info), this.width / 2, infoY, 0xAAAAAA);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (AutoMineClient.menuKey.matchesKey(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
