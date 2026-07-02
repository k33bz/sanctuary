package com.k33bz.sanctuary.siege;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.pathfinder.Path;
import com.k33bz.sanctuary.Sanctuary;
import com.k33bz.sanctuary.SanctuaryConfig;

/**
 * Wildlands siege: a door-breaker whose target is unreachable and who is standing at a
 * PLAYER-PLACED wooden door may smash one soft frame block at a time (the door's immediate
 * surroundings only). World-generated structures are never touched — only doors recorded in
 * {@link DoorRegistry} qualify. Respects the mobGriefing gamerule.
 */
public class SmashDoorFrameGoal extends Goal {
    private final Mob mob;
    private BlockPos targetBlock;
    private int progress;
    private int lastStage = -1;
    private int recheckCooldown;

    public SmashDoorFrameGoal(Mob mob) {
        this.mob = mob;
    }

    private static SanctuaryConfig.MobScaling cfg() {
        SanctuaryConfig c = Sanctuary.CONFIG;
        return c == null ? null : c.mobScaling;
    }

    @Override
    public boolean canUse() {
        if (--recheckCooldown > 0) {
            return false;
        }
        recheckCooldown = 40; // scan at most every 2s — pathfinding checks aren't free
        SanctuaryConfig.MobScaling ms = cfg();
        if (ms == null || !ms.enabled || !ms.frameSmashEnabled) {
            return false;
        }
        if (!(mob.level() instanceof ServerLevel level)
                || !level.getGameRules().get(GameRules.MOB_GRIEFING)) {
            return false;
        }
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        // Only rage at the walls when the normal route is actually blocked.
        Path path = mob.getNavigation().getPath();
        if (path != null && path.canReach()) {
            return false;
        }
        BlockPos door = DoorRegistry.get().nearestDoorWithin(level, mob.blockPosition(), ms.frameSearchRange);
        if (door == null) {
            return false;
        }
        targetBlock = pickFrameBlock(level, door, ms);
        return targetBlock != null;
    }

    /** The closest smashable block in the door's frame that the mob can actually reach. */
    private BlockPos pickFrameBlock(ServerLevel level, BlockPos door, SanctuaryConfig.MobScaling ms) {
        int r = Math.max(1, ms.frameRadius);
        BlockPos best = null;
        double bestSq = 3.0 * 3.0; // must be within arm's reach of the mob
        // Box around both door halves (door pos is the lower half).
        for (BlockPos pos : BlockPos.betweenClosed(door.offset(-r, -r, -r), door.offset(r, 1 + r, r))) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty() || state.is(BlockTags.DOORS)
                    || state.hasBlockEntity()) {
                continue;
            }
            float hardness = state.getDestroySpeed(level, pos);
            if (hardness < 0 || hardness > ms.frameMaxHardness) {
                continue; // unbreakable (bedrock = -1) or too tough for fists
            }
            double distSq = mob.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (distSq < bestSq) {
                best = pos.immutable();
                bestSq = distSq;
            }
        }
        return best;
    }

    @Override
    public boolean canContinueToUse() {
        SanctuaryConfig.MobScaling ms = cfg();
        if (ms == null || !ms.frameSmashEnabled || targetBlock == null) {
            return false;
        }
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive()
                && progress <= ms.frameSmashTimeTicks
                && mob.distanceToSqr(targetBlock.getX() + 0.5, targetBlock.getY() + 0.5, targetBlock.getZ() + 0.5) < 16.0
                && !mob.level().getBlockState(targetBlock).isAir();
    }

    @Override
    public void start() {
        progress = 0;
        lastStage = -1;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (!(mob.level() instanceof ServerLevel level) || targetBlock == null) {
            return;
        }
        SanctuaryConfig.MobScaling ms = cfg();
        if (ms == null) {
            return;
        }
        mob.getLookControl().setLookAt(targetBlock.getX() + 0.5, targetBlock.getY() + 0.5, targetBlock.getZ() + 0.5);
        if (progress % 20 == 0) {
            mob.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            level.playSound(null, targetBlock, SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, SoundSource.HOSTILE, 0.7f, 0.9f);
        }
        progress++;
        int stage = (int) (progress * 10.0f / Math.max(1, ms.frameSmashTimeTicks));
        if (stage != lastStage) {
            level.destroyBlockProgress(mob.getId(), targetBlock, Math.min(9, stage));
            lastStage = stage;
        }
        if (progress >= ms.frameSmashTimeTicks) {
            level.destroyBlockProgress(mob.getId(), targetBlock, -1);
            level.destroyBlock(targetBlock, true, mob);
            targetBlock = null;
        }
    }

    @Override
    public void stop() {
        if (targetBlock != null && mob.level() instanceof ServerLevel level) {
            level.destroyBlockProgress(mob.getId(), targetBlock, -1);
        }
        targetBlock = null;
        progress = 0;
        lastStage = -1;
    }
}
