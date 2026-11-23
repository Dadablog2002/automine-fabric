package com.example.automine;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class AutoMineConfig {
    public static BlockPos pos1 = null;
    public static BlockPos pos2 = null;
    public static boolean stopOnLowDurability = true;
    public static int minDurability = 20;
    public static boolean stopOnFullInventory = true;

    public static final int MAX_VOLUME = 100000;

    public static boolean hasRegion() {
        return pos1 != null && pos2 != null;
    }

    public static int minX() { return Math.min(pos1.getX(), pos2.getX()); }
    public static int minY() { return Math.min(pos1.getY(), pos2.getY()); }
    public static int minZ() { return Math.min(pos1.getZ(), pos2.getZ()); }
    public static int maxX() { return Math.max(pos1.getX(), pos2.getX()); }
    public static int maxY() { return Math.max(pos1.getY(), pos2.getY()); }
    public static int maxZ() { return Math.max(pos1.getZ(), pos2.getZ()); }

    public static long volume() {
        if (!hasRegion()) return 0;
        return (long) (maxX() - minX() + 1) * (maxY() - minY() + 1) * (maxZ() - minZ() + 1);
    }

    public static boolean inRegion(BlockPos p) {
        return hasRegion()
                && p.getX() >= minX() && p.getX() <= maxX()
                && p.getY() >= minY() && p.getY() <= maxY()
                && p.getZ() >= minZ() && p.getZ() <= maxZ();
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("automine.properties");
    }

    private static int i(Properties p, String k, int d) {
        try {
            return Integer.parseInt(p.getProperty(k, String.valueOf(d)));
        } catch (NumberFormatException e) {
            return d;
        }
    }

    public static void load() {
        Path f = file();
        if (!Files.exists(f)) return;
        try (InputStream in = Files.newInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            stopOnLowDurability = Boolean.parseBoolean(p.getProperty("stopOnLowDurability", "true"));
            minDurability = i(p, "minDurability", 20);
            stopOnFullInventory = Boolean.parseBoolean(p.getProperty("stopOnFullInventory", "true"));
            if (p.containsKey("p1x") && p.containsKey("p2x")) {
                pos1 = new BlockPos(i(p, "p1x", 0), i(p, "p1y", 0), i(p, "p1z", 0));
                pos2 = new BlockPos(i(p, "p2x", 0), i(p, "p2y", 0), i(p, "p2z", 0));
            }
        } catch (IOException ignored) {
        }
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("stopOnLowDurability", String.valueOf(stopOnLowDurability));
        p.setProperty("minDurability", String.valueOf(minDurability));
        p.setProperty("stopOnFullInventory", String.valueOf(stopOnFullInventory));
        if (hasRegion()) {
            p.setProperty("p1x", String.valueOf(pos1.getX()));
            p.setProperty("p1y", String.valueOf(pos1.getY()));
            p.setProperty("p1z", String.valueOf(pos1.getZ()));
            p.setProperty("p2x", String.valueOf(pos2.getX()));
            p.setProperty("p2y", String.valueOf(pos2.getY()));
            p.setProperty("p2z", String.valueOf(pos2.getZ()));
        }
        try (OutputStream out = Files.newOutputStream(file())) {
            p.store(out, "AutoMine settings");
        } catch (IOException ignored) {
        }
    }
}
