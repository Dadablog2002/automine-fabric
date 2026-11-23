package com.example.automine;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.text.LiteralText;
import org.lwjgl.glfw.GLFW;

/**
 * Only holds the vanilla "attack" key down. It does not change mining speed,
 * reach, rotation or anything else: speed is decided by the game/server as usual.
 */
public class AutoMineClient implements ClientModInitializer {
    private static KeyBinding toggleKey;
    private static KeyBinding menuKey;
    private static boolean enabled = false;

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!value) mc.options.keyAttack.setPressed(false);
        if (mc.player != null) {
            mc.player.sendMessage(new LiteralText("AutoMine: " + (value ? "ON" : "OFF")), true);
        }
    }

    @Override
    public void onInitializeClient() {
        AutoMineConfig.load();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.automine.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.automine"));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.automine.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.automine"));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(MinecraftClient client) {
        if (client.player == null) {
            enabled = false;
            return;
        }

        while (menuKey.wasPressed()) {
            if (client.currentScreen == null) {
                if (enabled) setEnabled(false);
                client.openScreen(new AutoMineScreen());
            }
        }

        while (toggleKey.wasPressed()) {
            setEnabled(!enabled);
        }

        if (!enabled) return;

        if (client.currentScreen != null) {
            stop(client, "AutoMine: OFF (menu)");
            return;
        }

        ItemStack stack = client.player.getMainHandStack();
        if (AutoMineConfig.stopOnLowDurability && stack.isDamageable()
                && stack.getMaxDamage() - stack.getDamage() < AutoMineConfig.minDurability) {
            stop(client, "AutoMine: OFF (tool almost broken)");
            return;
        }

        if (AutoMineConfig.stopOnLowHealth && client.player.getHealth() <= AutoMineConfig.minHalfHearts) {
            stop(client, "AutoMine: OFF (low health)");
            return;
        }

        client.options.keyAttack.setPressed(true);
    }

    private void stop(MinecraftClient client, String msg) {
        enabled = false;
        client.options.keyAttack.setPressed(false);
        if (client.player != null) {
            client.player.sendMessage(new LiteralText(msg), true);
        }
    }
}
