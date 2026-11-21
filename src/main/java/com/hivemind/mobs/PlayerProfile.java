package com.hivemind.mobs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the hive has observed about one player. Values are running averages (0..1)
 * sampled every 5 ticks. The first FEATURES of them are fed into the neural network,
 * so the brain can learn counter-tactics to a specific player's playstyle.
 */
public class PlayerProfile {
    public static final int FEATURES = 8;
    private static final int MAGIC = 0x504C5952;
    private static final float A = 0.006f; // smoothing: roughly a minute of memory

    public final UUID id;
    public String name;

    // running averages (0..1)
    public float aggression, ranged, mobility, sprint, sneak, turtle, builder, digger, flee, blocking;

    // raw counters
    public long samples, combatSamples, placed, broken, mobHits, mobKills, arrowsShot;
    public double damageTaken, damageDealt;

    // transient (not saved)
    public double lastX, lastZ, prevNearest = Double.MAX_VALUE;
    public boolean hasLast;
    public long lastAttackTick = -1000, lastBowTick = -1000, lastPlaceTick = -1000, lastBreakTick = -1000;
    public String activity = "idle";

    public PlayerProfile(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    private static float ema(float old, boolean v) {
        return old + ((v ? 1f : 0f) - old) * A;
    }

    private static float ema2(float old, boolean v) {
        return old + ((v ? 1f : 0f) - old) * A * 2f;
    }

    public void sample(double speed, boolean sprinting, boolean sneaking, boolean blockingNow,
                       boolean attackingNow, boolean bowNow, boolean inBase, boolean placing,
                       boolean breaking, boolean fleeing, boolean inCombat) {
        samples++;
        mobility += ((float) Math.min(1.0, speed / 0.28) - mobility) * A;
        sprint = ema(sprint, sprinting);
        sneak = ema(sneak, sneaking);
        turtle = ema(turtle, inBase);
        builder = ema(builder, placing);
        digger = ema(digger, breaking);
        if (inCombat) {
            combatSamples++;
            aggression = ema2(aggression, attackingNow);
            ranged = ema2(ranged, bowNow);
            blocking = ema2(blocking, blockingNow);
            flee = ema2(flee, fleeing);
        }

        List<String> now = new ArrayList<>();
        if (attackingNow) now.add("attacking");
        if (bowNow) now.add("using a bow");
        if (blockingNow) now.add("blocking");
        if (fleeing) now.add("running away");
        if (placing) now.add("building");
        if (breaking) now.add("digging");
        if (inBase) now.add("inside your base");
        if (sprinting) now.add("sprinting");
        if (sneaking) now.add("sneaking");
        activity = now.isEmpty() ? "idle" : String.join(", ", now);
    }

    /** Inputs for the neural network. Must contain exactly FEATURES values. */
    public float[] features() {
        return new float[]{aggression, ranged, mobility, sprint, turtle, builder, flee, blocking};
    }

    public String style() {
        if (combatSamples < 20) return "unknown (not enough fights observed yet)";
        List<String> s = new ArrayList<>();
        if (ranged > 0.4f) s.add("ranged fighter");
        if (blocking > 0.3f) s.add("shield user");
        if (aggression > 0.5f) s.add("melee brawler");
        if (flee > 0.35f) s.add("runner / kiter");
        if (turtle > 0.5f) s.add("base turtle");
        if (builder > 0.25f) s.add("builds walls & fortifications");
        if (mobility > 0.7f) s.add("highly mobile");
        return s.isEmpty() ? "balanced" : String.join(", ", s);
    }

    /** How the hive reacts to what it has seen. */
    public List<String> counterPlan() {
        List<String> c = new ArrayList<>();
        if (combatSamples < 20) {
            c.add("Still watching you. No counter-plan yet.");
            return c;
        }
        if (ranged > 0.4f) c.add("You shoot a lot -> hive closes distance fast and spreads out so arrows hit few.");
        if (blocking > 0.3f) c.add("You block with a shield -> hive flanks to hit from the sides and behind.");
        if (flee > 0.35f) c.add("You run when pressed -> hive sends flankers and ambushers to cut off your escape.");
        if (turtle > 0.5f) c.add("You hole up in your base -> hive sieges it and tunnels through walls.");
        if (builder > 0.25f) c.add("You build walls/pillars -> hive keeps siege mobs and creepers ready to breach.");
        if (aggression > 0.5f) c.add("You fight head-on -> hive surrounds you so you cannot take mobs one at a time.");
        if (c.isEmpty()) c.add("No strong habits detected. Hive relies on what its brain has learned.");
        return c;
    }

    private static String pct(float v) {
        return Math.round(v * 100) + "%";
    }

    public List<String> report() {
        List<String> out = new ArrayList<>();
        out.add("\u00a76=== Player: " + name + " ===");
        out.add("\u00a7eStyle: \u00a7f" + style());
        out.add("\u00a77Aggression " + pct(aggression) + " | Ranged " + pct(ranged) + " | Blocking " + pct(blocking)
                + " | Fleeing " + pct(flee) + " | Turtling " + pct(turtle));
        out.add("\u00a77Building " + pct(builder) + " | Digging " + pct(digger) + " | Mobility " + pct(mobility)
                + " | Sprint " + pct(sprint) + " | Sneak " + pct(sneak));
        out.add(String.format("\u00a77Totals: %d hits on mobs, %d kills, %.0f damage dealt, %.0f damage taken, %d blocks placed, %d broken, %d arrows",
                mobHits, mobKills, damageDealt, damageTaken, placed, broken, arrowsShot));
        out.add("\u00a7eHive counter-plan:");
        for (String l : counterPlan()) out.add("\u00a7f - " + l);
        return out;
    }

    // ---------------- persistence ----------------

    private void write(DataOutputStream o) throws IOException {
        o.writeLong(id.getMostSignificantBits());
        o.writeLong(id.getLeastSignificantBits());
        o.writeUTF(name);
        float[] f = {aggression, ranged, mobility, sprint, sneak, turtle, builder, digger, flee, blocking};
        for (float v : f) o.writeFloat(v);
        o.writeLong(samples); o.writeLong(combatSamples); o.writeLong(placed); o.writeLong(broken);
        o.writeLong(mobHits); o.writeLong(mobKills); o.writeLong(arrowsShot);
        o.writeDouble(damageTaken); o.writeDouble(damageDealt);
    }

    private static PlayerProfile read(DataInputStream in) throws IOException {
        UUID id = new UUID(in.readLong(), in.readLong());
        PlayerProfile p = new PlayerProfile(id, in.readUTF());
        p.aggression = in.readFloat(); p.ranged = in.readFloat(); p.mobility = in.readFloat();
        p.sprint = in.readFloat(); p.sneak = in.readFloat(); p.turtle = in.readFloat();
        p.builder = in.readFloat(); p.digger = in.readFloat(); p.flee = in.readFloat(); p.blocking = in.readFloat();
        p.samples = in.readLong(); p.combatSamples = in.readLong(); p.placed = in.readLong(); p.broken = in.readLong();
        p.mobHits = in.readLong(); p.mobKills = in.readLong(); p.arrowsShot = in.readLong();
        p.damageTaken = in.readDouble(); p.damageDealt = in.readDouble();
        return p;
    }

    public static void saveAll(File f, Map<UUID, PlayerProfile> profiles) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            o.writeInt(MAGIC);
            o.writeInt(profiles.size());
            for (PlayerProfile p : profiles.values()) p.write(o);
        }
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    public static Map<UUID, PlayerProfile> loadAll(File f) {
        Map<UUID, PlayerProfile> out = new HashMap<>();
        if (!f.exists()) return out;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != MAGIC) return out;
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                PlayerProfile p = read(in);
                out.put(p.id, p);
            }
        } catch (IOException e) {
            return new HashMap<>();
        }
        return out;
    }
}
