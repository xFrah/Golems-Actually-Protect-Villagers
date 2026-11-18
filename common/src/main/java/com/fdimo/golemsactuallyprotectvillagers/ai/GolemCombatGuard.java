package com.fdimo.golemsactuallyprotectvillagers.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Enforces environmental safety and navigation guards for Iron Golems during combat.
 */
public final class GolemCombatGuard {

    private GolemCombatGuard() {}

    /**
     * Evaluates if the imperiled villager is trapped in an unsafe hazard with the threat,
     * justifying lowering the golem's hazard avoidance boundaries to perform a rescue.
     */
    public static boolean isVillagerTrappedWithTarget(GolemSafeZone safeZone, ProtectVillagersTargetGoal protectGoal, LivingEntity target) {
        if (target == null || safeZone == null || !safeZone.isTargetInHazard(target)) {
            return false;
        }
        Villager villager = (protectGoal != null) ? protectGoal.getImperiledVillager() : null;
        if (villager != null && villager.isAlive() && !villager.isRemoved()) {
            if (!safeZone.isSafe(villager.blockPosition()) && target.hasLineOfSight(villager)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Executes per-tick combat guards:
     * 1. Emergency lava guard (stops navigation if path steps into lava or target falls into lava).
     * 2. Combat pursuit guard (prevents following threats into unrecoverable pits/deep water).
     * 3. Rim attack anchor (anchors golem to the rim of pits when hitting airborne/trapped targets).
     */
    public static void tickSafetyGuards(
            IronGolem golem, ServerLevel serverLevel, GolemSafeZone safeZone, GolemClimbHandler climbHandler, boolean canEnterHazard) {
        LivingEntity target = golem.getTarget();
        BlockPos targetPos = (target != null) ? target.blockPosition() : null;
        Path currentPath = golem.getNavigation().getPath();

        // 1. Emergency Lava Guard
        if (currentPath != null && !currentPath.isDone()) {
            int nextIndex = currentPath.getNextNodeIndex();
            if (nextIndex < currentPath.getNodeCount()) {
                Node nextNode = currentPath.getNode(nextIndex);
                BlockPos nextPos = nextNode.asBlockPos();
                if (serverLevel.getFluidState(nextPos).is(FluidTags.LAVA)) {
                    golem.getNavigation().stop();
                    golem.setTarget(null);
                    return;
                }
            }
        }

        if (target != null && targetPos != null) {
            if (serverLevel.getFluidState(targetPos).is(FluidTags.LAVA)
                    || targetPos.getY() <= serverLevel.getMinBuildHeight()) {
                golem.setTarget(null);
                golem.getNavigation().stop();
                return;
            }
        }

        boolean postMantleGrace = (climbHandler != null && climbHandler.isInPostMantleGrace());

        // 2. Combat pursuit guard: disengage if threat is in an unrecoverable hazard and cannot be reached
        if (target != null && !canEnterHazard && !postMantleGrace) {
            boolean isAttacker = (target == golem.getLastHurtByMob()
                    && golem.tickCount - golem.getLastHurtByMobTimestamp() <= 100);
            if (!isAttacker && safeZone.isTargetInHazard(target)) {
                boolean pathDone = (currentPath == null || currentPath.isDone() || golem.getNavigation().isStuck());
                boolean canHit = golem.isWithinMeleeAttackRange(target);

                if (pathDone && !canHit) {
                    golem.setTarget(null);
                    golem.getNavigation().stop();
                    return;
                }
            }
        }

        // 3. Rim attack anchor: if target is in a hazard and golem can hit it from safe ground,
        // stop forward navigation and clear horizontal velocity to avoid sliding in
        if (target != null && !canEnterHazard && safeZone.isTargetInHazard(target)) {
            if (golem.isWithinMeleeAttackRange(target)) {
                golem.getNavigation().stop();
                Vec3 delta = golem.getDeltaMovement();
                if (delta.x * delta.x + delta.z * delta.z > 0.001) {
                    golem.setDeltaMovement(0.0, delta.y, 0.0);
                }
            }
        }
    }
}
