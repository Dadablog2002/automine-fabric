package com.example.automine;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
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

    private static final double REACH = 4.8; // distance to block centre (vanilla reach is 4.5 to the block surface)
    private static final int SLAB = 3;

    private static boolean enabled = false;
    private static BlockPos target = null;
    private static BlockPos lastAim = null;
    private static int aimTicks = 0;
    private static int idleTicks = 0;
    private static int slabTop = 0;
    private static Vec3d aim = null;
    private static int noHitTicks = 0;
    private static boolean leftoverMode = false;
    private static boolean drillOn = true;
    private static int toggleStep = -1;

    // camera: the tick computes where to look, every rendered frame turns the camera smoothly
    private static boolean haveDesired = false;
    private static float desiredYaw, desiredPitch;
    private static float yawVel, pitchVel;
    private static long lastFrameNs = 0;
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
        noHitTicks = 0;
        drillOn = AutoMineConfig.drillStartOn;
        toggleStep = -1;
        haveDesired = false;
        yawVel = 0F;
        pitchVel = 0F;
        slabTop = AutoMineConfig.maxY();
        skipped.clear();
        msg(mc, "AutoMine: УВІМК.");
        return true;
    }

    public static void stop(MinecraftClient mc, String text) {
        enabled = false;
        target = null;
        haveDesired = false;
        releaseKeys(mc);
        msg(mc, text);
    }

    private static void releaseKeys(MinecraftClient mc) {
        mc.options.keyAttack.setPressed(false);
        mc.options.keyForward.setPressed(false);
        mc.options.keyJump.setPressed(false);
        if (toggleStep >= 0) { // interrupted while switching the drill
            mc.options.keySneak.setPressed(false);
            mc.options.keyUse.setPressed(false);
            toggleStep = -1;
        }
    }

    /** Shift + right click with the pickaxe switches the drill on/off. Takes 6 ticks. */
    private static void runDrillToggle(MinecraftClient mc) {
        mc.options.keyAttack.setPressed(false);
        mc.options.keyForward.setPressed(false);
        mc.options.keyJump.setPressed(false);
        if (toggleStep < 0) toggleStep = 0;
        switch (toggleStep) {
            case 0:
            case 1:
                mc.options.keySneak.setPressed(true);
                break;
            case 2:
            case 3:
                mc.options.keySneak.setPressed(true);
                mc.options.keyUse.setPressed(true);
                break;
            case 4:
                mc.options.keyUse.setPressed(false);
                break;
            default:
                mc.options.keySneak.setPressed(false);
                drillOn = !drillOn;
                toggleStep = -1;
                return;
        }
        toggleStep++;
    }

    @Override
    public void onInitializeClient() {
        AutoMineConfig.load();
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.automine.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.automine"));
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        HudRenderCallback.EVENT.register((matrices, tickDelta) -> onFrame());
    }

    /** Runs every rendered frame: smooth, speed-limited camera turn (no stepping at 20 ticks/s). */
    private void onFrame() {
        long now = System.nanoTime();
        float dt = lastFrameNs == 0 ? 0.016F : (now - lastFrameNs) / 1.0e9F;
        lastFrameNs = now;
        dt = MathHelper.clamp(dt, 0.001F, 0.1F);

        MinecraftClient c = MinecraftClient.getInstance();
        if (!enabled || !haveDesired || c.player == null || c.currentScreen != null) {
            yawVel = 0F;
            pitchVel = 0F;
            return;
        }
        float ey = MathHelper.wrapDegrees(desiredYaw - c.player.yaw);
        float ep = desiredPitch - c.player.pitch;
        float k = 1.0F - (float) Math.exp(-dt * 10.0F);
        yawVel += (MathHelper.clamp(ey * 5.0F, -140.0F, 140.0F) - yawVel) * k;
        pitchVel += (MathHelper.clamp(ep * 5.0F, -140.0F, 140.0F) - pitchVel) * k;

        float ny = c.player.yaw + yawVel * dt;
        float np = MathHelper.clamp(c.player.pitch + pitchVel * dt, -90.0F, 90.0F);
        c.player.yaw = ny;
        c.player.prevYaw = ny;
        c.player.pitch = np;
        c.player.prevPitch = np;
    }

    private static boolean mineable(MinecraftClient mc, BlockPos pos) {
        BlockState s = mc.world.getBlockState(pos);
        if (s.isAir()) return false;
        if (s.getMaterial().isLiquid()) return false;
        return s.getHardness(mc.world, pos) >= 0; // skips bedrock etc.
    }

    private static int slabBottom() {
        return slabTop - SLAB + 1;
    }

    private static int safe(int v, int min, int max) {
        int lo = (max - min >= 2) ? min + 1 : min;
        int hi = (max - min >= 2) ? max - 1 : max;
        return MathHelper.clamp(v, lo, hi);
    }

    private static boolean inColumn(BlockPos p) {
        return p.getX() >= AutoMineConfig.minX() && p.getX() <= AutoMineConfig.maxX()
                && p.getZ() >= AutoMineConfig.minZ() && p.getZ() <= AutoMineConfig.maxZ();
    }

    /**
     * Drill 3x3: the slab is always 3 layers thick and we hit only its MIDDLE layer (the layer of
     * the eyes), on rows that are 3 apart (1 block inside the region edge), every block along the
     * row. The drill then clears the layer above, the layer below and both sides, and never touches
     * anything under the slab. The last slab is shifted up so the drill cannot reach below the region.
     * Leftovers (only if something survived) are hit afterwards in a fixed snake order.
     */
    private static BlockPos findTarget(MinecraftClient mc) {
        int minX = AutoMineConfig.minX(), maxX = AutoMineConfig.maxX();
        int minZ = AutoMineConfig.minZ(), maxZ = AutoMineConfig.maxZ();
        int minY = AutoMineConfig.minY();
        int depthZ = maxZ - minZ + 1;
        while (true) {
            int bottom = slabBottom();
            int mid = bottom + 1;

            java.util.List<Integer> rows = new java.util.ArrayList<>();
            if (depthZ < 3) {
                rows.add(minZ);
            } else {
                for (int z = minZ + 1; z <= maxZ - 1; z += 3) rows.add(z);
                int lastRow = maxZ - 1;
                if (rows.get(rows.size() - 1) != lastRow) rows.add(lastRow);
            }
            leftoverMode = false;
            for (int ri = 0; ri < rows.size(); ri++) {
                int z = rows.get(ri);
                boolean forward = (ri % 2 == 0);
                for (int xi = 0; xi <= maxX - minX; xi++) {
                    int x = forward ? minX + xi : maxX - xi;
                    BlockPos p = new BlockPos(x, mid, z);
                    if (!skipped.contains(p) && mineable(mc, p)) return p;
                }
            }

            leftoverMode = true;
            int col = 0;
            for (int zi = 0; zi <= maxZ - minZ; zi++) {
                int z = minZ + zi;
                boolean forward = (zi % 2 == 0);
                for (int xi = 0; xi <= maxX - minX; xi++) {
                    int x = forward ? minX + xi : maxX - xi;
                    boolean down = (col++ % 2 == 0);
                    for (int k = 0; k < SLAB; k++) {
                        int y = down ? slabTop - k : bottom + k;
                        if (y < minY || y > AutoMineConfig.maxY()) continue;
                        BlockPos p = new BlockPos(x, y, z);
                        if (!skipped.contains(p) && mineable(mc, p)) return p;
                    }
                }
            }

            int next = bottom - 1;
            if (next < minY) return null; // the whole cube is done
            if (next - SLAB + 1 < minY) next = minY + SLAB - 1;
            slabTop = next;
            skipped.clear();
        }
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
            haveDesired = false;
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

        Vec3d eye = client.player.getCameraPosVec(1.0F);
        BlockPos feet = client.player.getBlockPos();
        BlockPos aimBlock = target;
        Vec3d lateral = null;
        boolean descending = false;

        // above the current slab: go to a safe column (not on the edge) and dig 3 blocks down
        if (feet.getY() > slabBottom() && inColumn(feet)) {
            int cx = safe(feet.getX(), AutoMineConfig.minX(), AutoMineConfig.maxX());
            int cz = safe(feet.getZ(), AutoMineConfig.minZ(), AutoMineConfig.maxZ());
            if (cx != feet.getX() || cz != feet.getZ()) {
                lateral = new Vec3d(cx + 0.5, eye.y, cz + 0.5);
                aimBlock = null;
            } else {
                BlockPos below = feet.down();
                aimBlock = (AutoMineConfig.inRegion(below) && mineable(client, below)) ? below : null;
                descending = true;
            }
        }

        if (aimBlock == null && lateral == null) { // falling / waiting
            releaseKeys(client);
            if (++idleTicks > 80) {
                stop(client, "AutoMine: ВИМК. (не можу спуститись)");
            }
            return;
        }
        idleTicks = 0;

        // digging down: switch the drill OFF (1 block at a time); mining the slab: switch it ON
        if (AutoMineConfig.autoDrillToggle && lateral == null) {
            boolean wantOn = !descending;
            if (toggleStep >= 0 || (drillOn != wantOn && client.player.isOnGround())) {
                runDrillToggle(client);
                return;
            }
        }

        // could not break the same block for 10 seconds
        if (aimBlock != null) {
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
        }

        // aim point glides smoothly towards the goal, camera follows the aim point
        Vec3d tc = (lateral != null) ? lateral : Vec3d.ofCenter(aimBlock);
        if (aim == null) {
            aim = eye.add(client.player.getRotationVec(1.0F).multiply(3.0));
        }
        Vec3d dv = tc.subtract(aim);
        double len = dv.length();
        double speed = Math.min(1.5, Math.max(0.3, len * 0.15)); // blocks per tick
        aim = (len <= speed) ? tc : aim.add(dv.multiply(speed / len));

        double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        if (horiz > 0.15) {
            desiredYaw = client.player.yaw + MathHelper.wrapDegrees(
                    (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F - client.player.yaw);
        } else {
            desiredYaw = client.player.yaw;
        }
        desiredPitch = MathHelper.clamp((float) (-(Math.atan2(dy, horiz) * 180.0 / Math.PI)), -90.0F, 90.0F);
        haveDesired = true;

        float yawErr = MathHelper.wrapDegrees(desiredYaw - client.player.yaw);
        float pitchErr = desiredPitch - client.player.pitch;
        boolean aimed = Math.abs(yawErr) < 4.0F && Math.abs(pitchErr) < 4.0F;

        // walk if the block is out of reach (or we are moving to the safe column)
        boolean far = lateral != null || eye.distanceTo(tc) > REACH;
        boolean stepCloser = noHitTicks > 20;
        client.options.keyForward.setPressed((far || stepCloser) && Math.abs(yawErr) < 30.0F);
        client.options.keyJump.setPressed(far && client.player.horizontalCollision && client.player.isOnGround());

        // Hold attack only while the crosshair is on a region block. While working the slab the
        // drill must hit the MIDDLE layer, so blocks of the other layers are never hit directly.
        boolean attack = false;
        HitResult hr = client.crosshairTarget;
        if (lateral == null && hr != null && hr.getType() == HitResult.Type.BLOCK) {
            BlockPos bp = ((BlockHitResult) hr).getBlockPos();
            boolean midOnly = aimBlock.equals(target) && !leftoverMode;
            attack = AutoMineConfig.inRegion(bp) && mineable(client, bp)
                    && (!midOnly || bp.getY() == slabBottom() + 1);
        }
        noHitTicks = (attack || !aimed) ? 0 : noHitTicks + 1;
        client.options.keyAttack.setPressed(attack);
    }
}
