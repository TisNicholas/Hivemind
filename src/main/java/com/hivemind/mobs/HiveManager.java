package com.hivemind.mobs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityFlying;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.IRangedAttackMob;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.entity.ai.attributes.IAttribute;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.monster.EntityBlaze;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.pathfinding.PathNavigateFlying;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityBed;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.item.ItemBow;
import net.minecraft.util.DamageSource;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.entity.player.ArrowLooseEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Groups configured mobs into per-player squads, asks the Brain for an order for
 * every mob, converts game events into rewards, and trains the Brain.
 */
public class HiveManager {
    public static final HiveManager INSTANCE = new HiveManager();

    public Brain brain = new Brain();
    private final Map<Integer, MobState> states = new HashMap<>();
    private final Map<UUID, BaseInfo> bases = new HashMap<>();
    private final Map<UUID, Long> baseScanTime = new HashMap<>();
    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();
    private File brainFile;
    private File profileFile;
    private long lastSaveTick = 0;

    // ---------------- data types ----------------

    public static class BaseInfo {
        public final Vec3d center;
        public final double radius;
        public final List<BlockPos> anchors;

        BaseInfo(Vec3d center, double radius, List<BlockPos> anchors) {
            this.center = center; this.radius = radius; this.anchors = anchors;
        }

        public boolean contains(Entity e) {
            return e.getDistance(center.x, center.y, center.z) <= radius;
        }

        public boolean near(BlockPos p) {
            double r = radius + 4;
            return p.distanceSq(center.x, center.y, center.z) <= r * r;
        }

        public BlockPos nearestAnchor(Entity e) {
            BlockPos best = anchors.get(0);
            double bd = Double.MAX_VALUE;
            for (BlockPos a : anchors) {
                double d = e.getDistanceSq(a);
                if (d < bd) { bd = d; best = a; }
            }
            return best;
        }
    }

    public static class MobState {
        public final EntityLiving mob;
        public final String type;
        public EntityPlayer player;
        public BaseInfo base;
        public Role role = Role.RUSH;
        public long roleSince;
        public long lastThink;
        public int idx, size;
        public Vec3d centroid = Vec3d.ZERO;
        public double nearFrac;
        public float[] lastState;
        public int lastAction = -1;
        public float acc;          // reward accumulated since last decision
        public double lastDist;
        public final long born;

        MobState(EntityLiving mob) {
            this.mob = mob;
            this.type = MobMatcher.keyOf(mob);
            this.born = mob.world.getTotalWorldTime();
        }

        private static Vec3d ring(EntityPlayer p, double ang, double r) {
            return new Vec3d(p.posX + Math.cos(ang) * r, p.posY, p.posZ + Math.sin(ang) * r);
        }

        /** Where this mob should walk right now, or null to hold position. */
        public Vec3d goal(EntityLiving m) {
            EntityPlayer p = player;
            Vec3d pp = new Vec3d(p.posX, p.posY, p.posZ);
            long age = m.world.getTotalWorldTime() - roleSince;
            switch (role) {
                case FLANK: {
                    double ang = Math.atan2(centroid.z - p.posZ, centroid.x - p.posX) + ((idx & 1) == 0 ? 2.2 : -2.2);
                    Vec3d fp = ring(p, ang, 7);
                    if (m.getDistanceSq(fp.x, fp.y, fp.z) < 9 || m.getDistanceSq(p) < 64) return pp;
                    return fp;
                }
                case SURROUND: {
                    if (age > 120 || nearFrac >= 0.6) return pp;
                    return ring(p, 2 * Math.PI * idx / Math.max(1, size), 6);
                }
                case AMBUSH: {
                    if (age > 160 || nearFrac >= 0.5) return pp;
                    double d = m.getDistance(p);
                    if (d > 22 || d < 12) {
                        Vec3d dir = new Vec3d(m.posX - p.posX, 0, m.posZ - p.posZ).normalize();
                        if (dir.lengthSquared() < 0.01) dir = new Vec3d(1, 0, 0);
                        return new Vec3d(p.posX + dir.x * 17, p.posY, p.posZ + dir.z * 17);
                    }
                    return null;
                }
                case SIEGE: {
                    if (base == null) return pp;
                    if (p.getDistance(base.center.x, base.center.y, base.center.z) <= base.radius + 6) return pp;
                    BlockPos a = base.nearestAnchor(m);
                    return new Vec3d(a.getX() + 0.5, a.getY(), a.getZ() + 0.5);
                }
                case RETREAT: {
                    Vec3d away = new Vec3d(m.posX - p.posX, 0, m.posZ - p.posZ).normalize();
                    Vec3d toC = new Vec3d(centroid.x - m.posX, 0, centroid.z - m.posZ).normalize();
                    Vec3d v = away.scale(0.6).add(toC.scale(0.4));
                    return new Vec3d(m.posX + v.x * 12, m.posY, m.posZ + v.z * 12);
                }
                default:
                    return pp;
            }
        }
    }

    // ---------------- lifecycle ----------------

    public MobState get(Entity e) {
        return states.get(e.getEntityId());
    }

    public void onServerStart() {
        states.clear(); bases.clear(); baseScanTime.clear();
        brain = new Brain();
        File dir = DimensionManager.getCurrentSaveRootDirectory();
        brainFile = new File(dir != null ? dir : new File("."), "hivemind_brain.dat");
        profileFile = new File(dir != null ? dir : new File("."), "hivemind_players.dat");
        profiles.clear();
        profiles.putAll(PlayerProfile.loadAll(profileFile));
        if (brain.load(brainFile)) {
            HiveMindMod.LOG.info("HiveMind: loaded brain ({} decisions of experience)", brain.steps);
        } else {
            HiveMindMod.LOG.info("HiveMind: starting with a fresh brain");
        }
    }

    public void onServerStop() {
        saveBrain();
        states.clear();
    }

    public void saveBrain() {
        if (brainFile == null) return;
        try {
            brain.save(brainFile);
            if (profileFile != null) PlayerProfile.saveAll(profileFile, profiles);
        } catch (IOException e) {
            HiveMindMod.LOG.error("HiveMind: could not save brain", e);
        }
    }

    public void resetBrain() {
        brain.reset();
        profiles.clear();
        for (MobState s : states.values()) s.lastAction = -1;
    }

    // ---------------- events: AI injection ----------------

    @SubscribeEvent
    public void onJoin(EntityJoinWorldEvent e) {
        if (e.getWorld().isRemote || !(e.getEntity() instanceof EntityLiving)) return;
        if (!MobMatcher.isHiveMob(e.getEntity())) return;
        EntityLiving m = (EntityLiving) e.getEntity();
        for (EntityAITasks.EntityAITaskEntry en : m.tasks.taskEntries) {
            if (en.action instanceof EntityAIHiveCommand) return;
        }
        m.tasks.addTask(0, new EntityAIHiveCommand(m));
        IAttributeInstance fr = m.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE);
        if (fr != null && fr.getBaseValue() < HiveConfig.aggroRange) fr.setBaseValue(HiveConfig.aggroRange);
    }

    // ---------------- events: rewards ----------------

    public void reward(EntityLiving m, float r) {
        MobState s = states.get(m.getEntityId());
        if (s != null) s.acc += r;
    }

    @SubscribeEvent
    public void onHurt(LivingHurtEvent e) {
        float amt = e.getAmount();
        if (e.getEntityLiving() instanceof EntityPlayer) {
            EntityPlayer victim = (EntityPlayer) e.getEntityLiving();
            Entity src = e.getSource().getTrueSource();
            MobState attacker = src != null ? states.get(src.getEntityId()) : null;
            profile(victim).damageTaken += amt;
            if (attacker != null) {
                attacker.acc += amt * 0.5f;
                brain.obs(attacker.type).dmgDealt += amt;
            }
            // team credit: the squad shares in every hit one of them lands
            for (MobState o : states.values()) {
                if (o != attacker && o.player == victim) o.acc += amt * 0.1f;
            }
        } else {
            MobState s = states.get(e.getEntityLiving().getEntityId());
            if (s != null) {
                brain.obs(s.type).dmgTaken += amt;
                Entity src = e.getSource().getTrueSource();
                if (src instanceof EntityPlayer) {
                    s.acc -= amt * 0.1f;
                    profile((EntityPlayer) src).damageDealt += amt;
                }
            }
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent e) {
        if (e.getEntityLiving() instanceof EntityPlayer) {
            EntityPlayer victim = (EntityPlayer) e.getEntityLiving();
            Entity killer = e.getSource().getTrueSource();
            int involved = 0;
            for (MobState s : new ArrayList<>(states.values())) {
                if (s.player != victim) continue;
                involved++;
                if (s.lastAction >= 0 && s.lastState != null) {
                    float r = s.acc + 10f + (s.mob == killer ? 5f : 0f);
                    brain.record(s.type, s.lastState, s.lastAction, r, null);
                }
                s.lastAction = -1;
                s.acc = 0;
            }
            if (involved > 0) {
                brain.playerKills++;
                if (HiveConfig.debugLogging) HiveMindMod.LOG.info("HiveMind: squad of {} killed {}", involved, victim.getName());
            }
        } else {
            MobState s = states.remove(e.getEntityLiving().getEntityId());
            if (s != null && s.lastAction >= 0 && s.lastState != null) {
                brain.record(s.type, s.lastState, s.lastAction, s.acc - 1f, null);
            }
            if (s != null) {
                int cause = causeOf(e.getSource());
                brain.observeDeath(s.type, s.lastAction >= 0 ? s.role.ordinal() : -1, cause,
                        s.mob.world.getTotalWorldTime() - s.born);
                if (cause == Brain.CAUSE_PLAYER) profile((EntityPlayer) e.getSource().getTrueSource()).mobKills++;
            }
        }
    }

    private static int causeOf(DamageSource src) {
        if (src.getTrueSource() instanceof EntityPlayer) return Brain.CAUSE_PLAYER;
        if (src.isFireDamage()) return Brain.CAUSE_FIRE;
        if (src == DamageSource.FALL) return Brain.CAUSE_FALL;
        if (src.isExplosion()) return Brain.CAUSE_EXPLOSION;
        return Brain.CAUSE_OTHER;
    }

    // ---------------- observing the player ----------------

    public PlayerProfile profile(EntityPlayer p) {
        PlayerProfile pf = profiles.computeIfAbsent(p.getUniqueID(), k -> new PlayerProfile(k, p.getName()));
        pf.name = p.getName();
        return pf;
    }

    public void observeBlockBroken(EntityLiving m) {
        brain.obs(MobMatcher.keyOf(m)).blocksBroken++;
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END || ev.player.world.isRemote) return;
        EntityPlayer p = ev.player;
        if (p.ticksExisted % 5 != 0 || p.isSpectator()) return;
        PlayerProfile pf = profile(p);
        long t = p.world.getTotalWorldTime();

        double speed = 0;
        if (pf.hasLast) {
            double dx = p.posX - pf.lastX, dz = p.posZ - pf.lastZ;
            speed = Math.sqrt(dx * dx + dz * dz) / 5.0;
            if (speed > 3) speed = 0; // teleport / respawn
        }
        pf.lastX = p.posX; pf.lastZ = p.posZ; pf.hasLast = true;

        double nearest = Double.MAX_VALUE;
        for (MobState ms : states.values()) {
            if (ms.player == p && !ms.mob.isDead) nearest = Math.min(nearest, ms.mob.getDistance(p));
        }
        boolean fleeing = nearest < 16 && pf.prevNearest < Double.MAX_VALUE
                && nearest > pf.prevNearest + 0.3 && speed > 0.1;
        pf.prevNearest = nearest;

        BaseInfo base = bases.get(p.getUniqueID());
        boolean inBase = base != null && base.contains(p);
        boolean bow = p.getHeldItemMainhand().getItem() instanceof ItemBow || t - pf.lastBowTick < 100;
        pf.sample(speed, p.isSprinting(), p.isSneaking(), p.isActiveItemStackBlocking(),
                t - pf.lastAttackTick < 40, bow, inBase,
                t - pf.lastPlaceTick < 100, t - pf.lastBreakTick < 100, fleeing, nearest < 24);
    }

    @SubscribeEvent
    public void onPlayerAttack(AttackEntityEvent e) {
        if (e.getEntityPlayer().world.isRemote) return;
        PlayerProfile pf = profile(e.getEntityPlayer());
        pf.lastAttackTick = e.getEntityPlayer().world.getTotalWorldTime();
        if (MobMatcher.isHiveMob(e.getTarget())) pf.mobHits++;
    }

    @SubscribeEvent
    public void onPlayerPlace(BlockEvent.PlaceEvent e) {
        if (e.getWorld().isRemote || e.getPlayer() == null) return;
        PlayerProfile pf = profile(e.getPlayer());
        pf.placed++;
        pf.lastPlaceTick = e.getWorld().getTotalWorldTime();
    }

    @SubscribeEvent
    public void onPlayerBreak(BlockEvent.BreakEvent e) {
        if (e.getWorld().isRemote || e.getPlayer() == null) return;
        PlayerProfile pf = profile(e.getPlayer());
        pf.broken++;
        pf.lastBreakTick = e.getWorld().getTotalWorldTime();
    }

    @SubscribeEvent
    public void onArrowLoose(ArrowLooseEvent e) {
        if (e.getEntityPlayer().world.isRemote) return;
        PlayerProfile pf = profile(e.getEntityPlayer());
        pf.arrowsShot++;
        pf.lastBowTick = e.getEntityPlayer().world.getTotalWorldTime();
    }

    // ---------------- chat reports ----------------

    public List<String> playerReport(String filter) {
        List<String> out = new ArrayList<>();
        int n = 0;
        for (PlayerProfile pf : profiles.values()) {
            if (filter != null && !pf.name.toLowerCase().contains(filter.toLowerCase())) continue;
            out.addAll(pf.report());
            n++;
        }
        if (n == 0) out.add("\u00a77No player observed yet" + (filter != null ? " matching '" + filter + "'." : "."));
        return out;
    }

    public List<String> liveReport(EntityPlayer p) {
        List<String> out = new ArrayList<>();
        PlayerProfile pf = profile(p);
        out.add("\u00a76=== Live view ===");
        out.add("\u00a7eYou right now: \u00a7f" + pf.activity);
        BaseInfo base = bases.get(p.getUniqueID());
        out.add("\u00a7eBase: \u00a7f" + (base == null ? "none detected"
                : String.format("detected, radius %.0f, %d anchor blocks", base.radius, base.anchors.size())));

        List<MobState> mine = new ArrayList<>();
        int[] counts = new int[Role.COUNT];
        for (MobState ms : states.values()) {
            if (ms.player == p && ms.lastAction >= 0 && !ms.mob.isDead) {
                mine.add(ms);
                counts[ms.role.ordinal()]++;
            }
        }
        if (mine.isEmpty()) {
            out.add("\u00a77No hive mobs are hunting you right now.");
            return out;
        }
        StringBuilder sb = new StringBuilder("\u00a7eSquad of " + mine.size() + ": \u00a7f");
        for (int r = 0; r < Role.COUNT; r++) if (counts[r] > 0) sb.append(Role.VALUES[r]).append(' ').append(counts[r]).append("  ");
        out.add(sb.toString());
        mine.sort(Comparator.comparingDouble(ms -> ms.mob.getDistanceSq(p)));
        for (int i = 0; i < Math.min(8, mine.size()); i++) {
            MobState ms = mine.get(i);
            out.add(String.format("\u00a77 %s \u00a7f%s \u00a77%.0f blocks, %.0f%% hp",
                    ms.type, ms.role, ms.mob.getDistance(p), 100f * ms.mob.getHealth() / Math.max(1f, ms.mob.getMaxHealth())));
        }
        return out;
    }

    // ---------------- main loop ----------------

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END || ev.world.isRemote) return;
        World w = ev.world;
        long t = w.getTotalWorldTime();

        if (w.provider.getDimension() == 0 && t - lastSaveTick >= HiveConfig.saveIntervalSeconds * 20L) {
            lastSaveTick = t;
            saveBrain();
        }
        if (t % Math.max(1, HiveConfig.thinkIntervalTicks) != 0) return;
        MobMatcher.refreshIfNeeded();
        think(w, t);
        brain.train();
    }

    private void think(World w, long t) {
        List<EntityPlayer> players = new ArrayList<>();
        for (EntityPlayer p : w.playerEntities) {
            if (p.isDead || p.isSpectator()) continue;
            if (!HiveConfig.targetCreativePlayers && p.isCreative()) continue;
            players.add(p);
        }

        // prune stale states belonging to this world
        long stale = 4L * Math.max(1, HiveConfig.thinkIntervalTicks);
        Iterator<MobState> it = states.values().iterator();
        while (it.hasNext()) {
            MobState s = it.next();
            if (s.mob.world == w && (s.mob.isDead || t - s.lastThink > stale)) it.remove();
        }
        if (players.isEmpty()) return;

        // assign every hive mob to its nearest player within range
        double r2 = HiveConfig.aggroRange * HiveConfig.aggroRange;
        Map<EntityPlayer, List<EntityLiving>> squads = new LinkedHashMap<>();
        for (Entity e : w.loadedEntityList) {
            if (!(e instanceof EntityLiving) || e.isDead || !MobMatcher.isHiveMob(e)) continue;
            EntityPlayer best = null;
            double bd = r2;
            for (EntityPlayer p : players) {
                double d = e.getDistanceSq(p);
                if (d < bd) { bd = d; best = p; }
            }
            if (best != null) squads.computeIfAbsent(best, k -> new ArrayList<>()).add((EntityLiving) e);
        }

        for (Map.Entry<EntityPlayer, List<EntityLiving>> en : squads.entrySet()) {
            final EntityPlayer p = en.getKey();
            List<EntityLiving> mobs = en.getValue();
            mobs.sort(Comparator.comparingDouble(m -> m.getDistanceSq(p)));
            if (mobs.size() > HiveConfig.maxMobsPerPlayer) mobs = new ArrayList<>(mobs.subList(0, HiveConfig.maxMobsPerPlayer));
            mobs.sort(Comparator.comparingInt(Entity::getEntityId));
            processSquad(w, p, mobs, t);
        }
    }

    private void processSquad(World w, EntityPlayer p, List<EntityLiving> mobs, long t) {
        BaseInfo base = baseFor(p, t);
        int n = mobs.size();
        double cx = 0, cy = 0, cz = 0, sumD = 0;
        int near = 0;
        int[] counts = new int[Role.COUNT];
        for (EntityLiving m : mobs) {
            cx += m.posX; cy += m.posY; cz += m.posZ;
            double d = m.getDistance(p);
            sumD += d;
            if (d < 10) near++;
            MobState s = states.get(m.getEntityId());
            if (s != null && s.lastAction >= 0) counts[s.role.ordinal()]++;
        }
        Vec3d centroid = new Vec3d(cx / n, cy / n, cz / n);
        double meanDist = sumD / n;
        double nearFrac = near / (double) n;

        for (int i = 0; i < n; i++) {
            EntityLiving m = mobs.get(i);
            MobState s = states.get(m.getEntityId());
            if (s == null) {
                s = new MobState(m);
                states.put(m.getEntityId(), s);
            }
            s.player = p; s.base = base; s.idx = i; s.size = n;
            s.centroid = centroid; s.nearFrac = nearFrac; s.lastThink = t;

            if (s.lastAction >= 0) counts[s.role.ordinal()]--; // features exclude self

            float[] st = features(m, p, base, s, counts, meanDist, nearFrac, w);
            double dist = m.getDistance(p);
            if (s.lastAction >= 0 && s.lastState != null) {
                float shaping = (float) MathHelper.clamp((s.lastDist - dist) * 0.02, -0.1, 0.1);
                brain.record(s.type, s.lastState, s.lastAction, s.acc + shaping - 0.01f, st);
            }
            s.acc = 0;

            int a = brain.act(s.type, st, teacher(m, s, base, p));
            if (HiveConfig.debugLogging) HiveMindMod.LOG.info("HiveMind: {} -> {}", s.type, Role.VALUES[a]);
            s.role = Role.VALUES[a];
            s.roleSince = t;
            s.lastState = st;
            s.lastAction = a;
            s.lastDist = dist;
            counts[a]++;
            m.setAttackTarget(p);
        }
    }

    // ---------------- state features ----------------

    private float[] features(EntityLiving m, EntityPlayer p, BaseInfo base, MobState s,
                             int[] counts, double meanDist, double nearFrac, World w) {
        float[] f = new float[Brain.BASE];
        int i = 0;
        double range = HiveConfig.aggroRange;
        f[i++] = m.getHealth() / Math.max(1f, m.getMaxHealth());
        f[i++] = (float) (m.getDistance(p) / range);
        f[i++] = base == null ? 1f : (float) Math.min(1.0, Math.sqrt(m.getDistanceSq(new BlockPos(base.center))) / range);
        f[i++] = p.getHealth() / Math.max(1f, p.getMaxHealth());
        f[i++] = p.getTotalArmorValue() / 20f;
        f[i++] = m.getEntitySenses().canSee(p) ? 1f : 0f;
        f[i++] = isRanged(m) ? 1f : 0f;
        f[i++] = m instanceof EntityCreeper ? 1f : 0f;
        f[i++] = isFlyer(m) ? 1f : 0f;
        f[i++] = attr(m, SharedMonsterAttributes.ATTACK_DAMAGE) / 10f;
        f[i++] = attr(m, SharedMonsterAttributes.MOVEMENT_SPEED) / 0.5f;
        f[i++] = m.getMaxHealth() / 40f;
        f[i++] = s.size / 16f;
        for (int r = 0; r < Role.COUNT; r++) f[i++] = counts[r] / (float) Math.max(1, s.size);
        f[i++] = (float) (meanDist / range);
        f[i++] = (float) nearFrac;
        f[i++] = base != null && base.contains(p) ? 1f : 0f;
        f[i++] = behindness(m, p);
        double ang = (w.getWorldTime() % 24000L) / 24000.0 * 2 * Math.PI;
        f[i++] = (float) Math.sin(ang);
        f[i++] = (float) Math.cos(ang);
        f[i++] = w.canSeeSky(p.getPosition()) ? 1f : 0f;
        f[i++] = (float) Math.min(1.0, Math.sqrt(p.motionX * p.motionX + p.motionZ * p.motionZ) * 5);
        for (float v : profile(p).features()) f[i++] = v;
        f[i++] = 1f;
        if (i != Brain.BASE) throw new IllegalStateException("feature count " + i + " != " + Brain.BASE);
        return f;
    }

    /** +1 = mob is in front of the player's view, -1 = directly behind. */
    private static float behindness(EntityLiving m, EntityPlayer p) {
        Vec3d look = p.getLookVec();
        double dx = m.posX - p.posX, dz = m.posZ - p.posZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        double ll = Math.sqrt(look.x * look.x + look.z * look.z);
        if (len < 1e-3 || ll < 1e-3) return 0f;
        return (float) ((look.x * dx + look.z * dz) / (len * ll));
    }

    private static float attr(EntityLiving m, IAttribute a) {
        IAttributeInstance inst = m.getEntityAttribute(a);
        return inst == null ? 0f : (float) inst.getAttributeValue();
    }

    public static boolean isRanged(EntityLiving m) {
        return m instanceof IRangedAttackMob || m instanceof EntityBlaze
                || m instanceof net.minecraft.entity.monster.EntityGhast;
    }

    public static boolean isFlyer(EntityLiving m) {
        return m instanceof EntityFlying || m instanceof EntityBlaze || m.getNavigator() instanceof PathNavigateFlying;
    }

    /** Simple hand-written strategy used to guide early exploration. */
    private int teacher(EntityLiving m, MobState s, BaseInfo base, EntityPlayer p) {
        boolean inBase = base != null && base.contains(p);
        if (m.getHealth() / Math.max(1f, m.getMaxHealth()) < 0.25f) return Role.RETREAT.ordinal();
        PlayerProfile pf = profile(p);
        if (pf.combatSamples > 20 && !(m instanceof EntityCreeper)) {
            if (pf.ranged > 0.4f && !isRanged(m)) return Role.RUSH.ordinal();            // close on archers
            if (pf.blocking > 0.3f && s.size >= 2) return Role.FLANK.ordinal();           // go around the shield
            if (pf.flee > 0.35f && s.size >= 3) return ((s.idx & 1) == 0 ? Role.FLANK : Role.AMBUSH).ordinal(); // cut off runners
            if (pf.turtle > 0.5f && !isFlyer(m) && !isRanged(m)) return Role.SIEGE.ordinal(); // crack the turtle
        }
        if (m instanceof EntityCreeper) {
            if (inBase) return Role.SIEGE.ordinal();
            return (s.size >= 3 ? Role.FLANK : Role.RUSH).ordinal();
        }
        if (isRanged(m)) return (s.size >= 3 ? Role.SURROUND : Role.RUSH).ordinal();
        if (inBase && !isFlyer(m)) return Role.SIEGE.ordinal();
        if (s.size >= 4) {
            int k = s.idx % 3;
            return (k == 0 ? Role.FLANK : k == 1 ? Role.SURROUND : Role.RUSH).ordinal();
        }
        return Role.RUSH.ordinal();
    }

    // ---------------- base detection ----------------

    private BaseInfo baseFor(EntityPlayer p, long t) {
        UUID id = p.getUniqueID();
        Long last = baseScanTime.get(id);
        if (last == null || t < last || t - last >= 100) {
            bases.put(id, scanBase(p));
            baseScanTime.put(id, t);
        }
        return bases.get(id);
    }

    /** A "base" = a cluster of inventories / furnaces / beds near the player. */
    private BaseInfo scanBase(EntityPlayer p) {
        List<BlockPos> all = new ArrayList<>();
        for (TileEntity te : p.world.loadedTileEntityList) {
            if (te == null || te.isInvalid()) continue;
            if (!(te instanceof IInventory) && !(te instanceof TileEntityBed)) continue;
            BlockPos pos = te.getPos();
            if (p.getDistanceSq(pos) <= 96.0 * 96.0) all.add(pos);
        }
        if (all.size() < HiveConfig.minBaseAnchors) return null;

        BlockPos seed = null;
        double best = Double.MAX_VALUE;
        for (BlockPos pos : all) {
            double d = p.getDistanceSq(pos);
            if (d < best) { best = d; seed = pos; }
        }
        List<BlockPos> cluster = new ArrayList<>();
        for (BlockPos pos : all) if (pos.distanceSq(seed) <= 24.0 * 24.0) cluster.add(pos);
        if (cluster.size() < HiveConfig.minBaseAnchors) return null;

        double ax = 0, ay = 0, az = 0;
        for (BlockPos c : cluster) { ax += c.getX() + 0.5; ay += c.getY(); az += c.getZ() + 0.5; }
        Vec3d center = new Vec3d(ax / cluster.size(), ay / cluster.size(), az / cluster.size());
        double radius = 0;
        for (BlockPos c : cluster) radius = Math.max(radius, Math.sqrt(c.distanceSq(center.x, center.y, center.z)));
        return new BaseInfo(center, Math.max(12, radius + 10), cluster);
    }
}
