package com.example.automine;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.text.LiteralText;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Set;

/**
 * Digs out a cuboid region: turns towards the next block, walks to it if needed and
 * holds the vanilla attack key. Mining/walking speed is entirely vanilla.
 */
public class AutoMineClient implements ClientModInitializer {
    public static KeyBinding menuKey;

    private static final double REACH = 4.0;
    private static final float TURN_STEP = 35.0F;

    private static boolean enabled = false;
    private static BlockPos target = null;
    private static int targetTicks = 0;
    private static final Set<BlockPos> skipped = new HashSet<>();

    public static boolean isEnabled() {
        return enabled;
    }

    private static void msg(MinecraftClient mc, String text) {
        if (mc.player != null) mc.player.sendMessage(new LiteralText(text), true);
    }

    public static boolean start(MinecraftClient mc) {
        if (!AutoMineConfig.hasRegion()) {
            msg(mc, "AutoMine: set both corners first");
            return false;
        }
        if (AutoMineConfig.volume() > AutoMineConfig.MAX_VOLUME) {
            msg(mc, "AutoMine: region too big (max " + AutoMineConfig.MAX_VOLUME + " blocks)");
            return false;
        }
        enabled = true;
        target = null;
        targetTicks = 0;
        skipped.clear();
        msg(mc, "AutoMine: ON");
        return true;
    }

    public static void stop(MinecraftClient mc, String text) {
        enabled = false;
        target = null;
        releaseKeys(mc);
        msg(mc, text);
    }

    private static void releaseKeys(MinecraftClient mc) {
        mc.options.keyAttack.setPressed(false);
        mc.options.keyForward.setPressed(false);
        mc.options.keyJump.setPressed(false);
    }

    @Override
    public void onInitializeClient() {
        AutoMineConfig.load();
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.automine.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.automine"));
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private static boolean mineable(MinecraftClient mc, BlockPos pos) {
        BlockState s = mc.world.getBlockState(pos);
        if (s.isAir()) return false;
        if (s.getMaterial().isLiquid()) return false;
        return s.getHardness(mc.world, pos) >= 0; // skips bedrock etc.
    }

    /** Top-most layer first; inside a layer the block closest to the player. */
    private static BlockPos findTarget(MinecraftClient mc) {
        Vec3d eye = mc.player.getCameraPosVec(1.0F);
        for (int y = AutoMineConfig.maxY(); y >= AutoMineConfig.minY(); y--) {
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;
            for (int x = AutoMineConfig.minX(); x <= AutoMineConfig.maxX(); x++) {
                for (int z = AutoMineConfig.minZ(); z <= AutoMineConfig.maxZ(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (skipped.contains(p) || !mineable(mc, p)) continue;
                    double d = eye.squaredDistanceTo(Vec3d.ofCenter(p));
                    if (d < bestDist) {
                        bestDist = d;
                        best = p;
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private void onTick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            enabled = false;
            return;
        }

        while (menuKey.wasPressed()) {
            if (client.currentScreen == null) {
                client.openScreen(new AutoMineScreen());
            }
        }

        if (!enabled) return;

        // Any screen (inventory, chat, menu): pause, stay enabled, continue when it closes.
        if (client.currentScreen != null) {
            releaseKeys(client);
            return;
        }

        ItemStack stack = client.player.getMainHandStack();
        if (AutoMineConfig.stopOnLowDurability && stack.isDamageable()
                && stack.getMaxDamage() - stack.getDamage() < AutoMineConfig.minDurability) {
            stop(client, "AutoMine: OFF (pickaxe almost broken)");
            return;
        }

        if (AutoMineConfig.stopOnFullInventory && client.player.inventory.getEmptySlot() == -1) {
            stop(client, "AutoMine: OFF (inventory full)");
            return;
        }

        // choose / refresh target
        if (target != null && client.world.getBlockState(target).isAir()) {
            skipped.clear();
            target = null;
        }
        if (target == null || !mineable(client, target)) {
            target = findTarget(client);
            targetTicks = 0;
            if (target == null) {
                stop(client, "AutoMine: cube finished");
                return;
            }
        }
        if (++targetTicks > 200) { // could not break it for 10 s: try another one
            skipped.add(target);
            target = null;
            releaseKeys(client);
            return;
        }

        // turn towards the target (limited degrees per tick)
        Vec3d eye = client.player.getCameraPosVec(1.0F);
        Vec3d tc = Vec3d.ofCenter(target);
        double dx = tc.x - eye.x, dy = tc.y - eye.y, dz = tc.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        float dyaw = 0F;
        if (horiz > 0.15) {
            float wantYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
            dyaw = MathHelper.wrapDegrees(wantYaw - client.player.yaw);
            client.player.yaw += MathHelper.clamp(dyaw, -TURN_STEP, TURN_STEP);
        }
        float wantPitch = (float) (-(Math.atan2(dy, horiz) * 180.0 / Math.PI));
        float dpitch = wantPitch - client.player.pitch;
        client.player.pitch = MathHelper.clamp(
                client.player.pitch + MathHelper.clamp(dpitch, -TURN_STEP, TURN_STEP), -90.0F, 90.0F);

        // walk if the target is too far to reach
        boolean far = eye.distanceTo(tc) > REACH;
        client.options.keyForward.setPressed(far && Math.abs(dyaw) < 25.0F);
        client.options.keyJump.setPressed(far && client.player.horizontalCollision && client.player.isOnGround());

        // mine only what is in the region (never anything outside it)
        boolean attack = false;
        HitResult hr = client.crosshairTarget;
        if (hr != null && hr.getType() == HitResult.Type.BLOCK) {
            BlockPos bp = ((BlockHitResult) hr).getBlockPos();
            attack = AutoMineConfig.inRegion(bp) && mineable(client, bp);
        }
        client.options.keyAttack.setPressed(attack);
    }
}
