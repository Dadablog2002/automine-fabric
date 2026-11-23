package com.example.automine;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class AutoMineConfig {
    public static boolean stopOnLowDurability = true;
    public static int minDurability = 20;
    public static boolean stopOnLowHealth = true;
    public static int minHalfHearts = 6; // 6 = 3 hearts

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("automine.properties");
    }

    public static void load() {
        Path f = file();
        if (!Files.exists(f)) return;
        try (InputStream in = Files.newInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            stopOnLowDurability = Boolean.parseBoolean(p.getProperty("stopOnLowDurability", "true"));
            minDurability = Integer.parseInt(p.getProperty("minDurability", "20"));
            stopOnLowHealth = Boolean.parseBoolean(p.getProperty("stopOnLowHealth", "true"));
            minHalfHearts = Integer.parseInt(p.getProperty("minHalfHearts", "6"));
        } catch (IOException | NumberFormatException ignored) {
        }
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("stopOnLowDurability", String.valueOf(stopOnLowDurability));
        p.setProperty("minDurability", String.valueOf(minDurability));
        p.setProperty("stopOnLowHealth", String.valueOf(stopOnLowHealth));
        p.setProperty("minHalfHearts", String.valueOf(minHalfHearts));
        try (OutputStream out = Files.newOutputStream(file())) {
            p.store(out, "AutoMine settings");
        } catch (IOException ignored) {
        }
    }
}
