package com.hivemind.mobs;

import net.minecraftforge.common.config.Config;

/**
 * Generates config/hivemind.cfg. Only public static config fields live here
 * (Forge scans this class), all logic is in MobMatcher.
 */
@Config(modid = HiveMindMod.MODID, name = "hivemind")
public class HiveConfig {

    @Config.Comment({
            "Mobs controlled by the hive mind.",
            "Single mob:   minecraft:zombie",
            "Entire mod:   ModID:*      (e.g. twilightforest:*  or  minecraft:*)",
            "Everything:   *",
            "A bare name like 'zombie' is treated as minecraft:zombie."
    })
    public static String[] mobs = new String[]{
            "minecraft:zombie", "minecraft:skeleton", "minecraft:spider", "minecraft:creeper"
    };

    @Config.Comment({
            "Mobs that are NEVER controlled, even if matched by 'mobs'. Same syntax.",
            "Useful for things like: minecraft:villager, minecraft:enderman"
    })
    public static String[] excludedMobs = new String[]{};

    @Config.Comment("How far (blocks) from a player mobs get pulled into that player's squad.")
    @Config.RangeDouble(min = 16, max = 256)
    public static double aggroRange = 64;

    @Config.Comment("Ticks between hive decisions per mob (20 = once a second).")
    @Config.RangeInt(min = 5, max = 200)
    public static int thinkIntervalTicks = 20;

    @Config.Comment("Max mobs coordinated per player (closest ones are chosen).")
    @Config.RangeInt(min = 1, max = 200)
    public static int maxMobsPerPlayer = 40;

    @Config.Comment("Also hunt creative-mode players (handy for testing).")
    public static boolean targetCreativePlayers = false;

    @Config.Comment("Movement speed multiplier applied when the hive moves a mob.")
    @Config.RangeDouble(min = 0.5, max = 2.5)
    public static double speedMultiplier = 1.1;

    // ---------------- Learning ----------------

    @Config.Comment("Turn learning on/off. Off = the brain still acts, but never changes.")
    public static boolean learningEnabled = true;

    @Config.Comment("Neural network learning rate.")
    @Config.RangeDouble(min = 0.00001, max = 0.1)
    public static double learningRate = 0.001;

    @Config.Comment("Discount factor (how much future reward matters).")
    @Config.RangeDouble(min = 0.0, max = 0.999)
    public static double gamma = 0.9;

    @Config.Comment("Starting exploration rate (chance of trying something other than the brain's best guess).")
    @Config.RangeDouble(min = 0, max = 1)
    public static double explorationStart = 0.7;

    @Config.Comment("Lowest exploration rate once the brain is experienced.")
    @Config.RangeDouble(min = 0, max = 1)
    public static double explorationMin = 0.05;

    @Config.Comment("How many decisions it takes to go from explorationStart to explorationMin.")
    @Config.RangeInt(min = 100, max = 10000000)
    public static int explorationDecaySteps = 15000;

    @Config.Comment({
            "While exploring, this share of the 'random' picks come from a simple hand-written",
            "strategy instead of pure noise. Bootstraps learning much faster. 0 = pure random."
    })
    @Config.RangeDouble(min = 0, max = 1)
    public static double teacherShare = 0.6;

    @Config.Comment("Training batches per hive tick, and samples per batch.")
    @Config.RangeInt(min = 0, max = 100)
    public static int trainBatchesPerTick = 8;
    @Config.RangeInt(min = 4, max = 512)
    public static int trainBatchSize = 32;

    @Config.Comment("How often the brain autosaves (seconds).")
    @Config.RangeInt(min = 10, max = 3600)
    public static int saveIntervalSeconds = 300;

    // ---------------- Base assault ----------------

    @Config.Comment({
            "A 'base' is detected as a cluster of at least this many storage/furnace/bed blocks",
            "near the player. Lower = bases are detected more easily."
    })
    @Config.RangeInt(min = 1, max = 20)
    public static int minBaseAnchors = 2;

    @Config.Comment("Allow ground melee mobs in the SIEGE role to tunnel through blocks.")
    public static boolean mobsBreakBlocks = true;

    @Config.Comment("Highest block hardness mobs can break (stone=1.5, iron block=5, obsidian=50).")
    @Config.RangeDouble(min = 0, max = 100)
    public static double maxBlockHardness = 5.0;

    @Config.Comment("Block-breaking speed multiplier (higher = faster).")
    @Config.RangeDouble(min = 0.1, max = 20)
    public static double blockBreakSpeed = 1.0;

    @Config.Comment("Whether blocks broken by mobs drop items.")
    public static boolean blockDrops = true;

    @Config.Comment("Blocks mobs may never break. Supports ModID:* too.")
    public static String[] unbreakableBlocks = new String[]{
            "minecraft:bedrock", "minecraft:obsidian", "minecraft:end_portal_frame",
            "minecraft:command_block", "minecraft:barrier"
    };

    @Config.Comment("Creepers in SIEGE role ignite themselves when stuck against a wall.")
    public static boolean creepersBreachWalls = true;

    @Config.Comment("Log hive decisions to the console.")
    public static boolean debugLogging = false;
}
