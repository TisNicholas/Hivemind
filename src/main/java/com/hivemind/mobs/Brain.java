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
import java.util.Random;

/**
 * The shared hive brain: a Deep Q-Network over tactical roles.
 *  - One network serves every mob, so squad-wide behaviour is learned jointly.
 *  - Each mob TYPE has a learned embedding vector (this is how it learns "what mobs do what").
 *  - Experience replay + target network for stable online learning.
 */
public class Brain {
    public static final int BASE = 22 + Role.COUNT + PlayerProfile.FEATURES;   // hand-built state features
    public static final int EMBED = 4;                // learned per-mob-type vector
    public static final int INPUT = BASE + EMBED;
    public static final int ACTIONS = Role.COUNT;

    private static final int MAGIC = 0x48495632; // "HIVE"
    private static final int CAPACITY = 30000;
    private static final int TARGET_SYNC = 200;

    private static final class Transition {
        final String type; final float[] s; final int a; final float r; final float[] next;
        Transition(String type, float[] s, int a, float r, float[] next) {
            this.type = type; this.s = s; this.a = a; this.r = r; this.next = next;
        }
    }

    private final Random rnd = new Random();
    private final int[] shape = {INPUT, 48, 24, ACTIONS};
    private NeuralNet net = new NeuralNet(shape, rnd);
    private NeuralNet target = new NeuralNet(shape, rnd);

    private final Map<String, float[]> embeddings = new HashMap<>();
    private final Map<String, long[]> roleUse = new HashMap<>();
    private final Map<String, double[]> roleReward = new HashMap<>();
    private final Map<String, MobObs> observed = new HashMap<>();
    private final List<Transition> buffer = new ArrayList<>();
    private int bufferPos = 0;

    public long steps = 0;
    public long trainSteps = 0;
    public long playerKills = 0;
    public double avgReward = 0;

    /** Everything the hive has watched mobs of one type do. */
    public static final class MobObs {
        public long deaths, byPlayer, byFire, byFall, byExplosion, byOther, blocksBroken, lifetimeTicks;
        public double dmgDealt, dmgTaken;
        public final long[] deathsByRole = new long[ACTIONS];
    }

    public static final int CAUSE_PLAYER = 0, CAUSE_FIRE = 1, CAUSE_FALL = 2, CAUSE_EXPLOSION = 3, CAUSE_OTHER = 4;

    public MobObs obs(String type) {
        return observed.computeIfAbsent(type, k -> new MobObs());
    }

    public void observeDeath(String type, int role, int cause, long lifetimeTicks) {
        MobObs o = obs(type);
        o.deaths++;
        o.lifetimeTicks += lifetimeTicks;
        if (role >= 0 && role < ACTIONS) o.deathsByRole[role]++;
        switch (cause) {
            case CAUSE_PLAYER: o.byPlayer++; break;
            case CAUSE_FIRE: o.byFire++; break;
            case CAUSE_FALL: o.byFall++; break;
            case CAUSE_EXPLOSION: o.byExplosion++; break;
            default: o.byOther++;
        }
    }

    private void writeObs(DataOutputStream o) throws IOException {
        o.writeInt(observed.size());
        for (Map.Entry<String, MobObs> e : observed.entrySet()) {
            MobObs m = e.getValue();
            o.writeUTF(e.getKey());
            o.writeLong(m.deaths); o.writeLong(m.byPlayer); o.writeLong(m.byFire); o.writeLong(m.byFall);
            o.writeLong(m.byExplosion); o.writeLong(m.byOther); o.writeLong(m.blocksBroken); o.writeLong(m.lifetimeTicks);
            o.writeDouble(m.dmgDealt); o.writeDouble(m.dmgTaken);
            for (long v : m.deathsByRole) o.writeLong(v);
        }
    }

    private Map<String, MobObs> readObs(DataInputStream in) throws IOException {
        Map<String, MobObs> out = new HashMap<>();
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            String k = in.readUTF();
            MobObs m = new MobObs();
            m.deaths = in.readLong(); m.byPlayer = in.readLong(); m.byFire = in.readLong(); m.byFall = in.readLong();
            m.byExplosion = in.readLong(); m.byOther = in.readLong(); m.blocksBroken = in.readLong(); m.lifetimeTicks = in.readLong();
            m.dmgDealt = in.readDouble(); m.dmgTaken = in.readDouble();
            for (int a = 0; a < ACTIONS; a++) m.deathsByRole[a] = in.readLong();
            out.put(k, m);
        }
        return out;
    }

    public Brain() {
        target.copyFrom(net);
    }

    // ---------------- acting ----------------

    public double epsilon() {
        double f = Math.min(1.0, steps / (double) Math.max(1, HiveConfig.explorationDecaySteps));
        return HiveConfig.explorationStart + (HiveConfig.explorationMin - HiveConfig.explorationStart) * f;
    }

    private float[] embedding(String type) {
        float[] e = embeddings.get(type);
        if (e == null) {
            e = new float[EMBED];
            for (int i = 0; i < EMBED; i++) e[i] = (float) rnd.nextGaussian() * 0.3f;
            embeddings.put(type, e);
        }
        return e;
    }

    private static float[] concat(float[] base, float[] emb) {
        float[] x = new float[INPUT];
        System.arraycopy(base, 0, x, 0, BASE);
        System.arraycopy(emb, 0, x, BASE, EMBED);
        return x;
    }

    /** Epsilon-greedy choice; while exploring, a share of picks come from the hand-written teacher. */
    public int act(String type, float[] base, int teacher) {
        if (HiveConfig.learningEnabled && rnd.nextDouble() < epsilon()) {
            if (teacher >= 0 && rnd.nextDouble() < HiveConfig.teacherShare) return teacher;
            return rnd.nextInt(ACTIONS);
        }
        float[] q = net.predict(concat(base, embedding(type)));
        int best = 0;
        for (int i = 1; i < q.length; i++) if (q[i] > q[best]) best = i;
        return best;
    }

    // ---------------- learning ----------------

    /** next == null means the episode ended (mob died / player died). */
    public void record(String type, float[] s, int a, float r, float[] next) {
        Transition t = new Transition(type, s, a, r, next);
        if (buffer.size() < CAPACITY) buffer.add(t);
        else buffer.set(bufferPos, t);
        bufferPos = (bufferPos + 1) % CAPACITY;
        steps++;
        avgReward = avgReward * 0.999 + r * 0.001;

        long[] use = roleUse.computeIfAbsent(type, k -> new long[ACTIONS]);
        double[] rew = roleReward.computeIfAbsent(type, k -> new double[ACTIONS]);
        use[a]++;
        rew[a] += r;
    }

    public void train() {
        if (!HiveConfig.learningEnabled) return;
        int bs = HiveConfig.trainBatchSize;
        if (buffer.size() < bs * 2) return;
        float lr = (float) HiveConfig.learningRate;
        float gamma = (float) HiveConfig.gamma;

        for (int b = 0; b < HiveConfig.trainBatchesPerTick; b++) {
            for (int k = 0; k < bs; k++) {
                Transition t = buffer.get(rnd.nextInt(buffer.size()));
                float[] emb = embedding(t.type);
                float y = Math.max(-2f, Math.min(10f, t.r));
                if (t.next != null) {
                    float[] qn = target.predict(concat(t.next, emb));
                    float m = qn[0];
                    for (int i = 1; i < qn.length; i++) if (qn[i] > m) m = qn[i];
                    y += gamma * m;
                }
                float[] dIn = new float[INPUT];
                net.trainSample(concat(t.s, emb), t.a, y, lr, dIn);
                for (int e = 0; e < EMBED; e++) {
                    emb[e] = Math.max(-3f, Math.min(3f, emb[e] - lr * 2f * dIn[BASE + e]));
                }
            }
            trainSteps++;
            if (trainSteps % TARGET_SYNC == 0) target.copyFrom(net);
        }
    }

    public void reset() {
        net = new NeuralNet(shape, rnd);
        target = new NeuralNet(shape, rnd);
        target.copyFrom(net);
        embeddings.clear();
        roleUse.clear();
        roleReward.clear();
        observed.clear();
        buffer.clear();
        bufferPos = 0;
        steps = 0; trainSteps = 0; playerKills = 0; avgReward = 0;
    }

    // ---------------- stats ----------------

    public List<String> describe() {
        List<String> out = new ArrayList<>();
        out.add(String.format("HiveMind: %d decisions, %d train batches, replay %d, explore %.0f%%, avg reward %.3f, player kills %d",
                steps, trainSteps, buffer.size(), epsilon() * 100, avgReward, playerKills));

        List<Map.Entry<String, long[]>> types = new ArrayList<>(roleUse.entrySet());
        types.sort((x, y) -> Long.compare(sum(y.getValue()), sum(x.getValue())));
        int shown = 0;
        for (Map.Entry<String, long[]> en : types) {
            if (shown++ >= 8) break;
            long[] use = en.getValue();
            double[] rew = roleReward.get(en.getKey());
            long total = sum(use);
            Integer[] idx = new Integer[ACTIONS];
            for (int i = 0; i < ACTIONS; i++) idx[i] = i;
            java.util.Arrays.sort(idx, (p, q) -> Double.compare(avg(rew, use, q), avg(rew, use, p)));
            StringBuilder sb = new StringBuilder(en.getKey()).append(" (").append(total).append("): best roles ");
            int n = 0;
            for (int i : idx) {
                if (use[i] < 5) continue;
                if (n++ >= 3) break;
                sb.append(Role.VALUES[i]).append(String.format(" %+.2f  ", avg(rew, use, i)));
            }
            out.add(sb.toString());
        }
        return out;
    }

    private static final String[] ROLE_BLURB = {
            "charges straight at the player",
            "circles around to hit from the side/behind",
            "takes a slot in a ring, then closes in together",
            "waits out of reach until the squad gathers",
            "attacks the base, tunneling through walls",
            "falls back to the squad to regroup / bait"
    };

    /** Chat-ready summary of the hive's overall tactics. */
    public List<String> strategyReport() {
        List<String> out = new ArrayList<>();
        double f = steps / (double) Math.max(1, HiveConfig.explorationDecaySteps);
        String level = f < 0.1 ? "Rookie" : f < 0.6 ? "Learning" : f < 1.0 ? "Experienced" : "Veteran";
        out.add("\u00a76=== HiveMind Strategy ===");
        out.add(String.format("\u00a7eLevel: \u00a7f%s \u00a77(%d decisions, exploring %.0f%% of the time, %d player kills)",
                level, steps, epsilon() * 100, playerKills));

        long[] use = new long[ACTIONS];
        double[] rew = new double[ACTIONS];
        for (Map.Entry<String, long[]> e : roleUse.entrySet()) {
            double[] r = roleReward.get(e.getKey());
            for (int a = 0; a < ACTIONS; a++) { use[a] += e.getValue()[a]; rew[a] += r[a]; }
        }
        long total = sum(use);
        if (total < 20) {
            out.add("\u00a77Not enough experience yet. Let some mobs hunt you first.");
            return out;
        }

        Integer[] idx = new Integer[ACTIONS];
        for (int i = 0; i < ACTIONS; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (p, q) -> Double.compare(avg(rew, use, q), avg(rew, use, p)));

        out.add("\u00a7eTactics ranked by average reward:");
        for (int i : idx) {
            if (use[i] == 0) continue;
            out.add(String.format("\u00a7a%s \u00a7f%+.2f \u00a77| used %.0f%% | %s",
                    Role.VALUES[i], avg(rew, use, i), use[i] * 100.0 / total, ROLE_BLURB[i]));
        }
        int best = idx[0];
        out.add("\u00a7eCurrent best plan: \u00a7f" + Role.VALUES[best] + " \u00a77- the hive " + ROLE_BLURB[best] + ".");
        return out;
    }

    /** Chat-ready list of mob types the hive has learned, optionally filtered by name. */
    public List<String> mobReport(String filter) {
        List<String> out = new ArrayList<>();
        out.add("\u00a76=== Mobs learned by the HiveMind ===");
        List<Map.Entry<String, long[]>> types = new ArrayList<>(roleUse.entrySet());
        types.sort((x, y) -> Long.compare(sum(y.getValue()), sum(x.getValue())));
        int shown = 0;
        for (Map.Entry<String, long[]> en : types) {
            String key = en.getKey();
            if (filter != null && !key.contains(filter.toLowerCase())) continue;
            if (shown++ >= 12) { out.add("\u00a77...more hidden, use /hivemind mobs <name> to filter"); break; }
            long[] use = en.getValue();
            double[] rew = roleReward.get(key);
            long total = sum(use);
            double totalRew = 0;
            int fav = 0, best = -1;
            double bestAvg = -1e9;
            for (int a = 0; a < ACTIONS; a++) {
                totalRew += rew[a];
                if (use[a] > use[fav]) fav = a;
                if (use[a] >= 5 && rew[a] / use[a] > bestAvg) { bestAvg = rew[a] / use[a]; best = a; }
            }
            out.add(String.format("\u00a7a%s \u00a77(%d decisions, avg reward %+.2f)", key, total, totalRew / Math.max(1, total)));
            out.add(String.format("  \u00a7fMost used: \u00a7e%s \u00a7f| Works best as: \u00a7e%s",
                    Role.VALUES[fav], best < 0 ? "still testing" : Role.VALUES[best] + String.format(" (%+.2f)", bestAvg)));
            MobObs ob = observed.get(key);
            if (ob != null && (ob.deaths > 0 || ob.dmgDealt > 0 || ob.dmgTaken > 0 || ob.blocksBroken > 0)) {
                int worst = 0;
                for (int a = 1; a < ACTIONS; a++) if (ob.deathsByRole[a] > ob.deathsByRole[worst]) worst = a;
                out.add(String.format("  \u00a7fObserved: dealt %.0f dmg, took %.0f dmg, broke %d blocks",
                        ob.dmgDealt, ob.dmgTaken, ob.blocksBroken));
                if (ob.deaths > 0) {
                    out.add(String.format("  \u00a7fDeaths: %d (player %d, fire %d, fall %d, explosion %d, other %d), avg life %.0fs, dies most as %s",
                            ob.deaths, ob.byPlayer, ob.byFire, ob.byFall, ob.byExplosion, ob.byOther,
                            ob.lifetimeTicks / 20.0 / ob.deaths, Role.VALUES[worst]));
                }
            }
            float[] emb = embeddings.get(key);
            if (emb != null) {
                StringBuilder sb = new StringBuilder("  \u00a78learned profile: ");
                for (float v : emb) sb.append(String.format("%+.2f ", v));
                out.add(sb.toString());
            }
        }
        if (shown == 0) out.add("\u00a77Nothing learned yet" + (filter != null ? " for '" + filter + "'." : "."));
        return out;
    }

    private static long sum(long[] a) { long s = 0; for (long v : a) s += v; return s; }
    private static double avg(double[] rew, long[] use, int i) { return use[i] == 0 ? -999 : rew[i] / use[i]; }

    // ---------------- persistence ----------------

    public void save(File f) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            o.writeInt(MAGIC); o.writeInt(INPUT); o.writeInt(ACTIONS);
            net.write(o);
            o.writeLong(steps); o.writeLong(trainSteps); o.writeLong(playerKills); o.writeDouble(avgReward);
            o.writeInt(embeddings.size());
            for (Map.Entry<String, float[]> e : embeddings.entrySet()) {
                o.writeUTF(e.getKey());
                for (float v : e.getValue()) o.writeFloat(v);
            }
            o.writeInt(roleUse.size());
            for (Map.Entry<String, long[]> e : roleUse.entrySet()) {
                o.writeUTF(e.getKey());
                double[] rew = roleReward.get(e.getKey());
                for (int i = 0; i < ACTIONS; i++) { o.writeLong(e.getValue()[i]); o.writeDouble(rew[i]); }
            }
            writeObs(o);
        }
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    public boolean load(File f) {
        if (!f.exists()) return false;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != MAGIC || in.readInt() != INPUT || in.readInt() != ACTIONS) return false;
            NeuralNet loaded = new NeuralNet(shape, rnd);
            if (!loaded.read(in)) return false;
            long st = in.readLong(), ts = in.readLong(), pk = in.readLong();
            double ar = in.readDouble();
            Map<String, float[]> emb = new HashMap<>();
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                String k = in.readUTF();
                float[] v = new float[EMBED];
                for (int j = 0; j < EMBED; j++) v[j] = in.readFloat();
                emb.put(k, v);
            }
            Map<String, long[]> use = new HashMap<>();
            Map<String, double[]> rew = new HashMap<>();
            n = in.readInt();
            for (int i = 0; i < n; i++) {
                String k = in.readUTF();
                long[] u = new long[ACTIONS]; double[] r = new double[ACTIONS];
                for (int a = 0; a < ACTIONS; a++) { u[a] = in.readLong(); r[a] = in.readDouble(); }
                use.put(k, u); rew.put(k, r);
            }
            Map<String, MobObs> ob = readObs(in);
            net = loaded;
            observed.clear(); observed.putAll(ob);
            target.copyFrom(net);
            embeddings.clear(); embeddings.putAll(emb);
            roleUse.clear(); roleUse.putAll(use);
            roleReward.clear(); roleReward.putAll(rew);
            steps = st; trainSteps = ts; playerKills = pk; avgReward = ar;
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
