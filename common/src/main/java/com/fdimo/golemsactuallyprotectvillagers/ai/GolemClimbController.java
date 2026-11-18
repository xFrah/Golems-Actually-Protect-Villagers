package com.fdimo.golemsactuallyprotectvillagers.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class GolemClimbController {

    private GolemClimbController() {
    }

    public static final float MAX_APPROACH_ANGLE_DEGREES = 40.0F;

    /**
     * Calculates the natural facing yaw toward the target ledge, clamped to at most
     * MAX_APPROACH_ANGLE_DEGREES (40 deg) from the obstacle face normal.
     */
    public static float calculateFacingYaw(Vec3 startPos, BlockPos ledgePos) {
        double centerX = ledgePos.getX() + 0.5;
        double centerZ = ledgePos.getZ() + 0.5;
        double dx = centerX - startPos.x;
        double dz = centerZ - startPos.z;

        Direction primaryDir;
        if (Math.abs(dx) > Math.abs(dz)) {
            primaryDir = (dx > 0) ? Direction.EAST : Direction.WEST;
        } else {
            primaryDir = (dz > 0) ? Direction.SOUTH : Direction.NORTH;
        }

        float baseCardinalYaw = primaryDir.toYRot();
        float naturalYaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float diff = Mth.wrapDegrees(naturalYaw - baseCardinalYaw);
        float clampedDiff = Mth.clamp(diff, -MAX_APPROACH_ANGLE_DEGREES, MAX_APPROACH_ANGLE_DEGREES);

        return Mth.wrapDegrees(baseCardinalYaw + clampedDiff);
    }

    /**
     * Calculates the horizontal wall-standing position flush outside the obstacle face along the approach angle.
     */
    public static Vec3 getWallStandPosition(Vec3 startPos, BlockPos ledgePos) {
        return getWallStandPosition(startPos, ledgePos, calculateFacingYaw(startPos, ledgePos));
    }

    public static Vec3 getWallStandPosition(Vec3 startPos, BlockPos ledgePos, float targetYaw) {
        double centerX = ledgePos.getX() + 0.5;
        double centerZ = ledgePos.getZ() + 0.5;

        float rad = targetYaw * (float) (Math.PI / 180.0);
        double fx = -Mth.sin(rad);
        double fz = Mth.cos(rad);

        double standDist = 0.5 + 0.72; // 1.22 blocks from obstacle center

        // Check if golem is already closer (e.g. at fence): don't pull backwards
        double currentDist = (centerX - startPos.x) * fx + (centerZ - startPos.z) * fz;
        double actualStandDist = (currentDist > 0.0 && currentDist < standDist)
                ? Math.max(0.65, currentDist)
                : standDist;

        double standX = centerX - fx * actualStandDist;
        double standZ = centerZ - fz * actualStandDist;

        return new Vec3(standX, startPos.y, standZ);
    }

    /**
     * Computes the interpolated 3D position of the golem at the current mantle tick.
     * Shared by both client rendering and server physics.
     */
    public static Vec3 calculateMantlePosition(MantleTiming timing, int mantleTicks, Vec3 startPos, BlockPos ledgePos,
            Level level) {
        return calculateMantlePosition(timing, mantleTicks, startPos, ledgePos, level, calculateFacingYaw(startPos, ledgePos));
    }

    public static Vec3 calculateMantlePosition(MantleTiming timing, int mantleTicks, Vec3 startPos, BlockPos ledgePos,
            Level level, float targetYaw) {
        double centerX = ledgePos.getX() + 0.5;
        double centerZ = ledgePos.getZ() + 0.5;

        VoxelShape shape = level.getBlockState(ledgePos).getCollisionShape(level, ledgePos);
        double topOffset = shape.isEmpty() ? 1.0 : shape.max(Direction.Axis.Y);
        double targetY = ledgePos.getY() + topOffset;

        Vec3 wallStand = getWallStandPosition(startPos, ledgePos, targetYaw);

        if (mantleTicks <= timing.t1End) {
            // Stage 1: Step smoothly flush against the wall face along the natural approach angle
            float t = (float) mantleTicks / (float) timing.t1End;
            double curX = Mth.lerp(t, startPos.x, wallStand.x);
            double curZ = Mth.lerp(t, startPos.z, wallStand.z);
            return new Vec3(curX, startPos.y, curZ);
        } else if (mantleTicks <= timing.t2End) {
            // Stage 2: Wind-up behind back -> overhead -> slam onto wall top (stand flush at wall)
            return new Vec3(wallStand.x, startPos.y, wallStand.z);
        } else if (mantleTicks <= timing.t3End) {
            // Stage 3: Smooth circular arc vault onto obstacle top along the natural approach angle
            float vaultT = (float) (mantleTicks - timing.t2End) / (float) (timing.t3End - timing.t2End);
            vaultT = Mth.clamp(vaultT, 0.0F, 1.0F);

            // Smooth vertical progression over the vault
            float ySmooth = vaultT * vaultT * (3.0F - 2.0F * vaultT);
            double curY = Mth.lerp(ySmooth, startPos.y, targetY);

            // Circular arc for horizontal vault along the approach angle
            float hT = Mth.clamp((vaultT - 0.25F) / 0.75F, 0.0F, 1.0F);
            float angle = hT * (float) (Math.PI / 2.0);
            float hArc = 1.0F - Mth.cos(angle);
            double curX = Mth.lerp(hArc, wallStand.x, centerX);
            double curZ = Mth.lerp(hArc, wallStand.z, centerZ);

            // Arc crest: gives the final part an organic circular up-and-over vault curve
            float crest = 0.12F * Mth.sin(hT * (float) Math.PI);
            curY += crest;

            return new Vec3(curX, curY, curZ);
        } else {
            // Settle / completed on top of the block
            return new Vec3(centerX, targetY, centerZ);
        }
    }

    /**
     * Calculates the arm rotation angle (in radians) for the mantling animation.
     */
    public static float calculateMantleArmAngle(MantleTiming timing, int ticks, float partialTick, Vec3 startPos,
            BlockPos ledgePos, Level level, double currentY) {
        if (ticks <= 0) {
            return 0.0F;
        }

        float currentTick = (float) ticks - 1.0F + partialTick;

        // Stage 1: arms at rest at sides while stepping flush to wall
        if (currentTick < (float) timing.t1End) {
            return 0.0F;
        }

        double targetY;
        if (ledgePos != null) {
            VoxelShape shape = level.getBlockState(ledgePos).getCollisionShape(level, ledgePos);
            double topOffset = shape.isEmpty() ? 1.0 : shape.max(Direction.Axis.Y);
            targetY = ledgePos.getY() + topOffset;
        } else {
            targetY = currentY + 2.0;
        }

        double startY = (startPos != null) ? startPos.y : (targetY - 2.0);
        double effectiveTargetY = targetY + 0.1875;

        // Angle where bottom face of the arm hits the block top when standing on the
        // ground
        double groundShoulderY = startY + 2.1;
        double dyStart = groundShoulderY - effectiveTargetY;
        float cosStart = Mth.clamp((float) (dyStart / 1.875), -1.0F, 1.0F);
        float startSurfaceRot = (float) (Math.PI * 2.0) - (float) Math.acos(cosStart);

        // Stage 2: Arm Wind-up behind back -> overhead -> slam onto wall top
        if (currentTick < (float) timing.t2End) {
            float u = (currentTick - (float) timing.t1End) / (float) (timing.t2End - timing.t1End);
            u = Mth.clamp(u, 0.0F, 1.0F);
            if (u < 0.35F) {
                // Smooth sinusoidal backswing behind the back (0.0 to +1.2 rad / ~70 deg)
                float sub = u / 0.35F;
                float ease = Mth.sin(sub * (float) (Math.PI / 2.0));
                return Mth.lerp(ease, 0.0F, 1.2F);
            } else {
                // Smoothly accelerate overhead and land precisely on the top surface of the
                // obstacle
                float sub = (u - 0.35F) / 0.65F;
                float subEased = sub * sub * (3.0F - 2.0F * sub);
                return Mth.lerp(subEased, 1.2F, startSurfaceRot);
            }
        }

        // Stage 3: Golem vaults in a circular arc onto the wall; arms smoothly complete
        // the 360 rotation
        if (currentTick < (float) timing.t3End) {
            float vaultT = (currentTick - (float) timing.t2End) / (float) (timing.t3End - timing.t2End);
            vaultT = Mth.clamp(vaultT, 0.0F, 1.0F);
            float armEased = vaultT * vaultT * (3.0F - 2.0F * vaultT);
            float armAngle = Mth.lerp(armEased, startSurfaceRot, (float) (Math.PI * 2.0));

            // When close to completing the 360-degree rotation, smoothly settle to 0
            if (armAngle >= (float) (Math.PI * 2.0) - 0.01F) {
                return 0.0F;
            }
            return armAngle;
        }

        return 0.0F;
    }

    /**
     * Checks if a candidate block position represents a viable climbable ledge
     * obstacle within a specified horizontal distance squared.
     * Criteria: valid surface, within reach, and elevation between 1.25 and 2.35 blocks.
     */
    public static boolean isViableLedge(IronGolem golem, Level level, BlockPos candidate, double maxDistSq) {
        if (!isValidLedge(level, candidate)) {
            return false;
        }
        VoxelShape shape = level.getBlockState(candidate).getCollisionShape(level, candidate);
        double topOffset = shape.isEmpty() ? 1.0 : shape.max(Direction.Axis.Y);
        double dy = (candidate.getY() + topOffset) - golem.getY();
        if (dy > 1.25 && dy <= 2.35) {
            double dx = (candidate.getX() + 0.5) - golem.getX();
            double dz = (candidate.getZ() + 0.5) - golem.getZ();
            return (dx * dx + dz * dz <= maxDistSq);
        }
        return false;
    }

    /**
     * Checks if a candidate block position represents a viable climbable ledge
     * obstacle within immediate mantling reach (&le; 2.0 blocks / distSq &le; 4.0).
     */
    public static boolean isViableLedgeObstacle(IronGolem golem, Level level, BlockPos candidate) {
        return isViableLedge(golem, level, candidate, 4.0);
    }

    /**
     * Checks if a candidate block position represents a viable climbable obstacle
     * within lookahead reach (up to 3.2 blocks / distSq &le; 10.24).
     */
    public static boolean isViableLedgeApproach(IronGolem golem, Level level, BlockPos candidate) {
        return isViableLedge(golem, level, candidate, 10.24);
    }

    /**
     * Checks if the golem is currently approaching a 2-block climbable ledge
     * obstacle (within ~3.0 blocks),
     * either via an elevated node in its active path or directly ahead along its
     * facing direction.
     * Used exclusively to suppress pre-climb jump attempts so the approach remains
     * ground-level and smooth.
     */
    public static boolean isApproachingClimbableLedge(IronGolem golem) {
        if (golem instanceof GolemSafeZoneHolder holder && !holder.golemsActuallyProtectVillagers$canClimbLedge()) {
            return false;
        }

        LivingEntity target = golem.getTarget();
        if (target != null && golem.isWithinMeleeAttackRange(target)) {
            if (target.getY() <= golem.getY() + 0.5) {
                return false;
            }
        }

        Level level = golem.level();
        Path currentPath = golem.getNavigation().getPath();

        // Path-driven lookahead: check the next 2-3 nodes in the active path for an
        // elevation step
        if (currentPath != null && !currentPath.isDone()) {
            int nextIndex = currentPath.getNextNodeIndex();
            int endIndex = Math.min(nextIndex + 3, currentPath.getNodeCount());

            for (int i = nextIndex; i < endIndex; i++) {
                Node node = currentPath.getNode(i);
                BlockPos candidateLedge = new BlockPos(node.x, node.y - 1, node.z);
                if (isViableLedgeApproach(golem, level, candidateLedge)) {
                    return true;
                }
                BlockPos candidateSameY = new BlockPos(node.x, node.y, node.z);
                if (isViableLedgeApproach(golem, level, candidateSameY)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Probes for nearby climbable ledges based exclusively on active path
     * navigation.
     */
    public static BlockPos findNearbyClimbableLedge(IronGolem golem) {
        if (golem instanceof GolemSafeZoneHolder holder && !holder.golemsActuallyProtectVillagers$canClimbLedge()) {
            return null;
        }

        // Suppress climbing if already within melee strike range of the target on the
        // same level
        LivingEntity target = golem.getTarget();
        if (target != null && golem.isWithinMeleeAttackRange(target)) {
            if (target.getY() <= golem.getY() + 0.5) {
                return null;
            }
        }

        Level level = golem.level();
        Path currentPath = golem.getNavigation().getPath();

        // Must have an active path that explicitly steps onto an elevated obstacle
        if (currentPath != null && !currentPath.isDone()) {
            int nextIndex = currentPath.getNextNodeIndex();
            int endIndex = Math.min(nextIndex + 2, currentPath.getNodeCount());
            for (int i = nextIndex; i < endIndex; i++) {
                Node nextNode = currentPath.getNode(i);
                // 1. Check candidate ledge at node.y - 1 (2-block ledge or fence)
                BlockPos candidateLedge = new BlockPos(nextNode.x, nextNode.y - 1, nextNode.z);
                if (isViableLedgeObstacle(golem, level, candidateLedge)) {
                    return candidateLedge;
                }
                // 2. Check candidate ledge directly at node.y
                BlockPos candidateSameY = new BlockPos(nextNode.x, nextNode.y, nextNode.z);
                if (isViableLedgeObstacle(golem, level, candidateSameY)) {
                    return candidateSameY;
                }
            }
        }

        return null;
    }

    /**
     * Checks if a ledge position has solid surface, no lava/fire, and 3-block
     * vertical clearance for the golem.
     */
    public static boolean isValidLedge(Level level, BlockPos ledgePos) {
        net.minecraft.world.level.block.state.BlockState ledgeState = level.getBlockState(ledgePos);
        if (ledgeState.isAir() || level.getFluidState(ledgePos).is(FluidTags.LAVA)) {
            return false;
        }
        if (ledgeState.getCollisionShape(level, ledgePos).isEmpty()) {
            return false;
        }
        if (level.getBlockState(ledgePos.above()).is(net.minecraft.world.level.block.Blocks.FIRE)) {
            return false;
        }

        // Check vertical clearance for the golem above the target ledge (Y+1, Y+2, Y+3)
        BlockPos above1 = ledgePos.above();
        BlockPos above2 = ledgePos.above(2);
        BlockPos above3 = ledgePos.above(3);
        if (!level.getBlockState(above1).getCollisionShape(level, above1).isEmpty())
            return false;
        if (!level.getBlockState(above2).getCollisionShape(level, above2).isEmpty())
            return false;
        if (!level.getBlockState(above3).getCollisionShape(level, above3).isEmpty())
            return false;

        return true;
    }

    /**
     * Locates the index of the node on or closest to targetLedge in the saved path.
     */
    public static int findLedgeNodeIndex(Path path, BlockPos targetLedge) {
        if (path == null) {
            return -1;
        }
        int count = path.getNodeCount();
        int startIndex = Math.max(0, path.getNextNodeIndex() - 1);

        // 1. Exact match at targetLedge or targetLedge.above()
        for (int i = startIndex; i < count; i++) {
            Node node = path.getNode(i);
            if (node.x == targetLedge.getX() && node.z == targetLedge.getZ()
                    && (node.y == targetLedge.getY() + 1 || node.y == targetLedge.getY())) {
                return i;
            }
        }

        // 2. Closest node horizontally with y >= targetLedge.getY()
        int bestIdx = -1;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = startIndex; i < count; i++) {
            Node node = path.getNode(i);
            if (node.y >= targetLedge.getY()) {
                double dx = (node.x + 0.5) - (targetLedge.getX() + 0.5);
                double dz = (node.z + 0.5) - (targetLedge.getZ() + 0.5);
                double distSq = dx * dx + dz * dz;
                if (distSq < bestDistSq && distSq <= 2.25) {
                    bestDistSq = distSq;
                    bestIdx = i;
                }
            }
        }

        // 3. Fallback: closest node to targetLedge among all remaining nodes
        if (bestIdx == -1 && startIndex < count) {
            for (int i = startIndex; i < count; i++) {
                Node node = path.getNode(i);
                double dx = (node.x + 0.5) - (targetLedge.getX() + 0.5);
                double dy = node.y - (targetLedge.getY() + 1.0);
                double dz = (node.z + 0.5) - (targetLedge.getZ() + 0.5);
                double distSq = dx * dx + dy * dy * 2.0 + dz * dz;
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestIdx = i;
                }
            }
        }
        return bestIdx;
    }
}
