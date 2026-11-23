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
 * Digs out a cuboid region slab by slab (3 layers thick). Inside a slab the order is fixed:
 * row after row, column after column, each column up/down like a snake. When the slab is empty
 * it digs down below its feet to the next slab. The camera follows a smoothly gliding aim point.
 * Mining/walking speed is entirely vanilla (the mod only holds the usual keys).
 */
public class AutoMineClient implements ClientModInitializer {
    public static KeyBinding menuKey;

    private static final double REACH = 4.0;
    private static final int SLAB = 3;
    private static final float MAX_TURN = 12.0F; // degrees per tick

    private static boolean enabled = false;
    private static BlockPos target = null;
    private static BlockPos lastAim = null;
    private static int aimTicks = 0;
    private static int idleTicks = 0;
    private static int slabTop = 0;
    private static Vec3d aim = null;
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
        lastAim = null;
        aim = null;
        aimTicks = 0;
        idleTicks = 0;
        slabTop = AutoMineConfig.maxY();
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

    private static int slabBottom() {
        return Math.max(AutoMineConfig.minY(), slabTop - SLAB + 1);
    }

    private static boolean inColumn(BlockPos p) {
        return p.getX() >= AutoMineConfig.minX() && p.getX() <= AutoMineConfig.maxX()
                && p.getZ() >= AutoMineConfig.minZ() && p.getZ() <= AutoMineConfig.maxZ();
    }

    /** Fixed order inside the current slab; moves to the next (lower) slab when it is empty. */
    private static BlockPos findTarget(MinecraftClient mc) {
        int minX = AutoMineConfig.minX(), maxX = AutoMineConfig.maxX();
        int minZ = AutoMineConfig.minZ(), maxZ = AutoMineConfig.maxZ();
        while (slabTop >= AutoMineConfig.minY()) {
            int bottom = slabBottom();
            int col = 0;
            for (int zi = 0; zi <= maxZ - minZ; zi++) {
                int z = minZ + zi;
                boolean forward = (zi % 2 == 0);
                for (int xi = 0; xi <= maxX - minX; xi++) {
                    int x = forward ? minX + xi : maxX - xi;
                    boolean down = (col++ % 2 == 0);
                    for (int k = 0; k <= slabTop - bottom; k++) {
                        int y = down ? slabTop - k : bottom + k;
                        BlockPos p = new BlockPos(x, y, z);
                        if (!skipped.contains(p) && mineable(mc, p)) return p;
                    }
                }
            }
            slabTop = bottom - 1;
            skipped.clear();
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
            stop(client, "AutoMine: ВИМК. (кирка майже зламана)");
            return;
        }

        if (AutoMineConfig.stopOnFullInventory && client.player.inventory.getEmptySlot() == -1) {
            stop(client, "AutoMine: ВИМК. (інвентар повний)");
            return;
        }

        // next block of the slab
        if (target != null && client.world.getBlockState(target).isAir()) {
            skipped.clear();
            target = null;
        }
        if (target == null || !mineable(client, target)) {
            target = findTarget(client);
            if (target == null) {
                stop(client, "AutoMine: куб викопано");
                return;
            }
        }

        // if we are above the current slab, dig down below our feet first
        BlockPos feet = client.player.getBlockPos();
        BlockPos aimBlock = target;
        if (feet.getY() > slabBottom() && inColumn(feet)) {
            BlockPos below = feet.down();
            aimBlock = (AutoMineConfig.inRegion(below) && mineable(client, below)) ? below : null;
        }

        if (aimBlock == null) { // falling / waiting
            releaseKeys(client);
            if (++idleTicks > 80) {
                stop(client, "AutoMine: ВИМК. (не можу спуститись)");
            }
            return;
        }
        idleTicks = 0;

        // could not break the same block for 10 seconds
        if (aimBlock.equals(lastAim)) {
            aimTicks++;
        } else {
            lastAim = aimBlock;
            aimTicks = 0;
        }
        if (aimTicks > 200) {
            if (aimBlock.equals(target)) {
                skipped.add(target);
                target = null;
                aimTicks = 0;
                releaseKeys(client);
                return;
            }
            stop(client, "AutoMine: ВИМК. (не можу спуститись)");
            return;
        }

        // aim point glides smoothly towards the block, camera follows the aim point
        Vec3d eye = client.player.getCameraPosVec(1.0F);
        Vec3d tc = Vec3d.ofCenter(aimBlock);
        if (aim == null) {
            aim = eye.add(client.player.getRotationVec(1.0F).multiply(3.0));
        }
        Vec3d dv = tc.subtract(aim);
        double len = dv.length();
        double speed = Math.min(1.5, Math.max(0.25, len * 0.15)); // blocks per tick
        aim = (len <= speed) ? tc : aim.add(dv.multiply(speed / len));

        double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        float yawErr = 0F;
        if (horiz > 0.15) {
            float wantYaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
            yawErr = MathHelper.wrapDegrees(wantYaw - client.player.yaw);
            client.player.yaw += MathHelper.clamp(yawErr, -MAX_TURN, MAX_TURN);
        }
        float wantPitch = (float) (-(Math.atan2(dy, horiz) * 180.0 / Math.PI));
        client.player.pitch = MathHelper.clamp(client.player.pitch
                + MathHelper.clamp(wantPitch - client.player.pitch, -MAX_TURN, MAX_TURN), -90.0F, 90.0F);

        // walk only if the block is out of reach
        boolean far = eye.distanceTo(tc) > REACH;
        client.options.keyForward.setPressed(far && Math.abs(yawErr) < 30.0F);
        client.options.keyJump.setPressed(far && client.player.horizontalCollision && client.player.isOnGround());

        // hold the attack key while the crosshair is on a block that belongs to the region
        boolean attack = false;
        HitResult hr = client.crosshairTarget;
        if (hr != null && hr.getType() == HitResult.Type.BLOCK) {
            BlockPos bp = ((BlockHitResult) hr).getBlockPos();
            attack = AutoMineConfig.inRegion(bp) && mineable(client, bp);
        }
        client.options.keyAttack.setPressed(attack);
    }
}
