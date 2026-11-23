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
 * Digs out a cuboid region in a fixed order (top layer first, row by row like a snake),
 * turns smoothly towards the next block, walks if it is out of reach and holds the vanilla
 * attack key. Mining/walking speed is entirely vanilla.
 */
public class AutoMineClient implements ClientModInitializer {
    public static KeyBinding menuKey;

    private static final double REACH = 4.0;

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
            msg(mc, "AutoMine: спочатку познач обидва кути");
            return false;
        }
        if (AutoMineConfig.volume() > AutoMineConfig.MAX_VOLUME) {
            msg(mc, "AutoMine: регіон завеликий (макс. " + AutoMineConfig.MAX_VOLUME + " блоків)");
            return false;
        }
        enabled = true;
        target = null;
        targetTicks = 0;
        skipped.clear();
        msg(mc, "AutoMine: УВІМК.");
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

    /**
     * Fixed order: top layer first; inside a layer row after row (z), direction alternates
     * like a snake, so the next block is always right next to the previous one.
     */
    private static BlockPos findTarget(MinecraftClient mc) {
        int minX = AutoMineConfig.minX(), maxX = AutoMineConfig.maxX();
        int minZ = AutoMineConfig.minZ(), maxZ = AutoMineConfig.maxZ();
        int layer = 0;
        for (int y = AutoMineConfig.maxY(); y >= AutoMineConfig.minY(); y--, layer++) {
            for (int zi = 0; zi <= maxZ - minZ; zi++) {
                int z = minZ + zi;
                boolean forward = ((layer + zi) % 2 == 0);
                for (int xi = 0; xi <= maxX - minX; xi++) {
                    int x = forward ? minX + xi : maxX - xi;
                    BlockPos p = new BlockPos(x, y, z);
                    if (!skipped.contains(p) && mineable(mc, p)) return p;
                }
            }
        }
        return null;
    }

    /** Smooth turning: fast when far from the goal, slow when close, never more than 10 degrees per tick. */
    private static float ease(float diff) {
        float abs = Math.abs(diff);
        if (abs <= 1.0F) return diff;
        float step = MathHelper.clamp(abs * 0.35F, 1.0F, 10.0F);
        return Math.signum(diff) * Math.min(step, abs);
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
            stop(client, "AutoMine: ВИМК. (кирка майже зламана)");
            return;
        }

        if (AutoMineConfig.stopOnFullInventory && client.player.inventory.getEmptySlot() == -1) {
            stop(client, "AutoMine: ВИМК. (інвентар повний)");
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
                stop(client, "AutoMine: куб викопано");
                return;
            }
        }
        if (++targetTicks > 200) { // could not break it for 10 s: try another one
            skipped.add(target);
            target = null;
            releaseKeys(client);
            return;
        }

        // smooth turn towards the target
        Vec3d eye = client.player.getCameraPosVec(1.0F);
        Vec3d tc = Vec3d.ofCenter(target);
        double dx = tc.x - eye.x, dy = tc.y - eye.y, dz = tc.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        float wantYaw = client.player.yaw;
        if (horiz > 0.2) {
            wantYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
            client.player.yaw += ease(MathHelper.wrapDegrees(wantYaw - client.player.yaw));
        }
        float wantPitch = (float) (-(Math.atan2(dy, horiz) * 180.0 / Math.PI));
        client.player.pitch = MathHelper.clamp(
                client.player.pitch + ease(wantPitch - client.player.pitch), -90.0F, 90.0F);

        float yawLeft = Math.abs(MathHelper.wrapDegrees(wantYaw - client.player.yaw));
        float pitchLeft = Math.abs(wantPitch - client.player.pitch);
        boolean aimed = yawLeft <= 3.0F && pitchLeft <= 3.0F;

        // walk only if the target is out of reach
        boolean far = eye.distanceTo(tc) > REACH;
        client.options.keyForward.setPressed(far && yawLeft < 30.0F);
        client.options.keyJump.setPressed(far && client.player.horizontalCollision && client.player.isOnGround());

        // mine only while the camera is on target, and only blocks inside the region
        boolean attack = false;
        HitResult hr = client.crosshairTarget;
        if (aimed && hr != null && hr.getType() == HitResult.Type.BLOCK) {
            BlockPos bp = ((BlockHitResult) hr).getBlockPos();
            attack = AutoMineConfig.inRegion(bp) && mineable(client, bp);
        }
        client.options.keyAttack.setPressed(attack);
    }
}
