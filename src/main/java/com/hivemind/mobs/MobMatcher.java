package com.hivemind.mobs;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.util.ResourceLocation;

/** Turns the config lists ("minecraft:zombie", "modid:*", "*") into fast lookups. */
public final class MobMatcher {

    private static final class Spec {
        final Set<String> exact = new HashSet<>();
        final Set<String> mods = new HashSet<>();
        boolean all;

        Spec(String[] entries) {
            if (entries == null) return;
            for (String raw : entries) {
                if (raw == null) continue;
                String s = raw.trim().toLowerCase(Locale.ROOT);
                if (s.isEmpty()) continue;
                if (s.equals("*") || s.equals("*:*")) all = true;
                else if (s.endsWith(":*")) mods.add(s.substring(0, s.length() - 2));
                else if (s.indexOf(':') < 0) exact.add("minecraft:" + s);
                else exact.add(s);
            }
        }

        boolean matches(String full) {
            if (all || exact.contains(full)) return true;
            int i = full.indexOf(':');
            return i > 0 && mods.contains(full.substring(0, i));
        }
    }

    private static int signature = Integer.MIN_VALUE;
    private static Spec include = new Spec(null);
    private static Spec exclude = new Spec(null);
    private static Spec unbreakable = new Spec(null);
    private static final Map<String, Boolean> cache = new HashMap<>();

    private MobMatcher() {}

    /** Re-parses the config if it changed (works with the in-game config GUI too). */
    public static void refreshIfNeeded() {
        int sig = Objects.hash(Arrays.hashCode(HiveConfig.mobs),
                Arrays.hashCode(HiveConfig.excludedMobs),
                Arrays.hashCode(HiveConfig.unbreakableBlocks));
        if (sig == signature) return;
        signature = sig;
        include = new Spec(HiveConfig.mobs);
        exclude = new Spec(HiveConfig.excludedMobs);
        unbreakable = new Spec(HiveConfig.unbreakableBlocks);
        cache.clear();
    }

    /** Registry name such as "minecraft:zombie" (falls back to the class name). */
    public static String keyOf(Entity e) {
        ResourceLocation rl = EntityList.getKey(e);
        return rl != null ? rl.toString().toLowerCase(Locale.ROOT) : e.getClass().getSimpleName();
    }

    public static boolean isHiveMob(Entity e) {
        if (!(e instanceof EntityLiving)) return false;
        if (e instanceof EntityTameable && ((EntityTameable) e).isTamed()) return false;
        refreshIfNeeded();
        String key = keyOf(e);
        Boolean cached = cache.get(key);
        if (cached == null) {
            cached = include.matches(key) && !exclude.matches(key);
            cache.put(key, cached);
        }
        return cached;
    }

    public static boolean isBlockProtected(Block block) {
        refreshIfNeeded();
        ResourceLocation rl = block.getRegistryName();
        return rl != null && unbreakable.matches(rl.toString().toLowerCase(Locale.ROOT));
    }
}
