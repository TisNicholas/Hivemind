package com.hivemind.mobs;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Highest-priority AI task. Executes the hive's order for this mob.
 * RUSH (and close-range melee / ranged firing) is left to vanilla AI.
 */
public class EntityAIHiveCommand extends EntityAIBase {
    private final EntityLiving mob;
    private int repath, checkTimer, stuckTicks, breakTimer;
    private double lastX, lastZ;
    private BlockPos breakPos;
    private boolean ignited;

    public EntityAIHiveCommand(EntityLiving mob) {
        this.mob = mob;
        setMutexBits(1);
    }

    private HiveManager.MobState state() {
        return HiveManager.INSTANCE.get(mob);
    }

    @Override
    public boolean shouldExecute() {
        HiveManager.MobState s = state();
        if (s == null || s.player == null || s.player.isDead || s.player.world != mob.world || s.lastAction < 0) return false;
        Role r = s.role;
        if (r == Role.RUSH) return false;

        // if the player hit us, fight back using vanilla AI
        if (r != Role.RETREAT && mob.getRevengeTarget() != null && mob.ticksExisted - mob.getRevengeTimer() < 60) return false;

        double d = mob.getDistance(s.player);
        boolean ranged = HiveManager.isRanged(mob);
        if (r != Role.RETREAT) {
            if (ranged && d < 15 && mob.getEntitySenses().canSee(s.player)) return false; // let it shoot
            if (!ranged && d < 3.0) return false;                                        // let it melee
        }
        return true;
    }

    @Override
    public void startExecuting() {
        repath = 0; checkTimer = 0; stuckTicks = 0;
        lastX = mob.posX; lastZ = mob.posZ;
        ignited = false;
    }

    @Override
    public void resetTask() {
        mob.getNavigator().clearPath();
        clearBreak();
        stuckTicks = 0;
    }

    @Override
    public void updateTask() {
        HiveManager.MobState s = state();
        if (s == null || s.player == null) return;

        Vec3d goal = s.goal(mob);
        mob.getLookHelper().setLookPositionWithEntity(s.player, 30f, 30f);
        if (goal == null) {
            mob.getNavigator().clearPath();
            return;
        }

        if (--repath <= 0 || mob.getNavigator().noPath()) {
            repath = 10;
            double spd = HiveConfig.speedMultiplier * (s.role == Role.RETREAT ? 1.15 : 1.0);
            if (HiveManager.isFlyer(mob)) mob.getMoveHelper().setMoveTo(goal.x, goal.y, goal.z, spd);
            else mob.getNavigator().tryMoveToXYZ(goal.x, goal.y, goal.z, spd);
        }

        if (++checkTimer >= 10) {
            checkTimer = 0;
            double dx = mob.posX - lastX, dz = mob.posZ - lastZ;
            lastX = mob.posX; lastZ = mob.posZ;
            if (Math.sqrt(dx * dx + dz * dz) < 0.25 && mob.getDistanceSq(goal.x, goal.y, goal.z) > 4) {
                stuckTicks += 10;
            } else {
                stuckTicks = 0;
                if (breakTimer > 0) clearBreak();
            }
        }

        if (s.role == Role.SIEGE && stuckTicks >= 20) siegeObstacle(s, goal);
    }

    // ---------------- siege behaviour ----------------

    private void siegeObstacle(HiveManager.MobState s, Vec3d goal) {
        World w = mob.world;
        if (mob instanceof EntityCreeper) {
            if (HiveConfig.creepersBreachWalls && !ignited && findObstruction(w, goal) != null) {
                ignited = true;
                ((EntityCreeper) mob).ignite();
            }
            return;
        }
        if (!HiveConfig.mobsBreakBlocks || HiveManager.isRanged(mob) || HiveManager.isFlyer(mob)) return;

        BlockPos pos = findObstruction(w, goal);
        if (pos == null) { clearBreak(); return; }
        if (!pos.equals(breakPos)) { clearBreak(); breakPos = pos; }

        IBlockState state = w.getBlockState(pos);
        float hardness = state.getBlockHardness(w, pos);
        double need = Math.max(12.0, hardness * 30.0 / HiveConfig.blockBreakSpeed);
        breakTimer++;
        if (breakTimer % 8 == 0) mob.swingArm(EnumHand.MAIN_HAND);
        w.sendBlockBreakProgress(mob.getEntityId(), pos, Math.min(9, (int) (breakTimer / need * 10)));

        if (breakTimer >= need) {
            w.sendBlockBreakProgress(mob.getEntityId(), pos, -1);
            w.destroyBlock(pos, HiveConfig.blockDrops);
            boolean inBase = s.base != null && s.base.near(pos);
            HiveManager.INSTANCE.reward(mob, inBase ? 0.9f : 0.2f);
            HiveManager.INSTANCE.observeBlockBroken(mob);
            breakTimer = 0;
            breakPos = null;
            stuckTicks = 0;
        }
    }

    private void clearBreak() {
        if (breakPos != null) mob.world.sendBlockBreakProgress(mob.getEntityId(), breakPos, -1);
        breakPos = null;
        breakTimer = 0;
    }

    /** First breakable solid block between the mob and its goal. */
    private BlockPos findObstruction(World w, Vec3d goal) {
        double dx = goal.x - mob.posX, dz = goal.z - mob.posZ;
        EnumFacing fx = dx > 0 ? EnumFacing.EAST : EnumFacing.WEST;
        EnumFacing fz = dz > 0 ? EnumFacing.SOUTH : EnumFacing.NORTH;
        EnumFacing[] order = Math.abs(dx) > Math.abs(dz) ? new EnumFacing[]{fx, fz} : new EnumFacing[]{fz, fx};

        BlockPos here = new BlockPos(mob);
        for (EnumFacing f : order) {
            BlockPos feet = here.offset(f);
            if (breakable(w, feet)) return feet;
            BlockPos head = feet.up();
            if (breakable(w, head)) return head;
        }
        if (goal.y > mob.posY + 1.5 && breakable(w, here.up(2))) return here.up(2);
        if (goal.y < mob.posY - 1.5 && breakable(w, here.down())) return here.down();
        return null;
    }

    private boolean breakable(World w, BlockPos pos) {
        IBlockState st = w.getBlockState(pos);
        if (st.getBlock().isAir(st, w, pos)) return false;
        if (st.getMaterial().isLiquid()) return false;
        if (st.getCollisionBoundingBox(w, pos) == null) return false;
        float h = st.getBlockHardness(w, pos);
        if (h < 0 || h > HiveConfig.maxBlockHardness) return false;
        return !MobMatcher.isBlockProtected(st.getBlock());
    }
}
