package com.hivemind.mobs;

/** The tactical orders the hive brain can hand to a mob. */
public enum Role {
    /** Charge the player directly (vanilla AI does the fighting). */
    RUSH,
    /** Circle around to the opposite side of the squad and hit from behind. */
    FLANK,
    /** Take a slot on a ring around the player, then close in together. */
    SURROUND,
    /** Hold back out of reach until the squad has gathered, then charge. */
    AMBUSH,
    /** Go for the player's base: tunnel through walls, creepers breach. */
    SIEGE,
    /** Fall back toward the squad (bait / regroup). */
    RETREAT;

    public static final Role[] VALUES = values();
    public static final int COUNT = VALUES.length;
}
