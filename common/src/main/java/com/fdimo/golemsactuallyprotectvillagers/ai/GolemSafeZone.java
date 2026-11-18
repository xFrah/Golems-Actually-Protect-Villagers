package com.fdimo.golemsactuallyprotectvillagers.ai;

import com.fdimo.golemsactuallyprotectvillagers.Config;
import com.fdimo.golemsactuallyprotectvillagers.Constants;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maintains a rolling/sliding window of safe, recoverable terrain around an
 * Iron Golem.
 * Computed entirely asynchronously on Constants.ASYNC_POOL using
 * PathNavigationRegion snapshots.
 *
 * Safe blocks are defined by symmetric, reversible connectivity (|dy| &le; 1.0,
 * no fluids, adequate clearance).
 * Any block outside the safe zone within the scanned radius is a confirmed
 * hazard (hole, cliff, deep water).
 */
public class GolemSafeZone {

    private static final int[][] HORIZONTAL_OFFSETS = {
            { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, // Cardinal
            { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 } // Diagonal
    };

    private static final int[][] FOOTPRINT_OFFSETS = {
            { 0, 0 },
            { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 },
            { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 }
    };

    private volatile LongSet safeBlocks = LongSets.EMPTY_SET;
    private volatile LongSet hazardSurfaceBlocks = LongSets.EMPTY_SET;
    private volatile boolean isComputing = false;
    private volatile BlockPos centerPos = BlockPos.ZERO;
    private long lastComputeTime = 0L;
    private final AtomicInteger computeVersion = new AtomicInteger(0);
    private final int radius;
    private final int radiusSqr;

    public GolemSafeZone(int radius) {
        this.radius = radius;
        this.radiusSqr = radius * radius;
    }

    public boolean isInitialized() {
        return this.centerPos != BlockPos.ZERO && !this.safeBlocks.isEmpty();
    }

    /**
     * Request an asynchronous update of the safe zone on Constants.ASYNC_POOL.
     * Triggers if:
     * 1. The safe zone has not been initialized yet, OR
     * 2. The golem moved >= 5 blocks from last center, OR
     * 3. The golem has a target and hasn't refreshed in 20 ticks (1s), OR
     * 4. The golem is stationary and the idle refresh timer has elapsed (80 ticks /
     * 4s).
     */
    public void requestAsyncUpdate(IronGolem golem, ServerLevel level) {
        if (this.isComputing || golem.isRemoved() || !golem.isAlive()) {
            return;
        }

        int footY = (int) Math.round(golem.getY());
        BlockPos currentPos = new BlockPos(golem.getBlockX(), footY, golem.getBlockZ());
        long gameTime = level.getGameTime();

        boolean notInitialized = (this.centerPos == BlockPos.ZERO || this.safeBlocks.isEmpty());
        boolean moved = (currentPos.distSqr(this.centerPos) >= 25.0);
        boolean combatRefresh = (golem.getTarget() != null && gameTime - this.lastComputeTime >= 20);
        boolean stationaryRefresh = (gameTime - this.lastComputeTime >= 80);

        if (!notInitialized && !moved && !combatRefresh && !stationaryRefresh) {
            return;
        }

        BlockPos snapshotCenter = currentPos.immutable();
        BlockPos minPos = snapshotCenter.offset(-this.radius - 2, -36, -this.radius - 2);
        BlockPos maxPos = snapshotCenter.offset(this.radius + 2, 16, this.radius + 2);

        if (!level.hasChunksAt(minPos, maxPos)) {
            return;
        }

        this.isComputing = true;
        this.lastComputeTime = gameTime;
        int version = this.computeVersion.incrementAndGet();

        PathNavigationRegion region = new PathNavigationRegion(level, minPos, maxPos);

        CompletableFuture.runAsync(() -> {
            try {
                Result result = computeSafeAndHazardBlocks(region, snapshotCenter);
                if (version != this.computeVersion.get()) {
                    return; // Superseded by a newer compute request
                }

                // Publish results atomically
                this.safeBlocks = LongSets.unmodifiable(result.safeBlocks);
                this.hazardSurfaceBlocks = LongSets.unmodifiable(result.hazardBlocks);
                this.centerPos = snapshotCenter;
            } catch (Exception e) {
                Constants.LOG.error("Failed to compute golem safe zone asynchronously", e);
            } finally {
                if (version == this.computeVersion.get()) {
                    this.isComputing = false;
                }
            }
        }, Constants.ASYNC_POOL);
    }

    /**
     * Forces an immediate asynchronous recalculation centered at the golem's
     * current position,
     * invalidating any in-flight task and temporarily failing open so forward
     * movement is unimpeded.
     * Used right when the golem finishes climbing out of a pit or hazard onto a
     * ledge.
     */
    public void requestForcedAsyncUpdate(IronGolem golem, ServerLevel level) {
        if (golem.isRemoved() || !golem.isAlive()) {
            return;
        }

        int footY = (int) Math.round(golem.getY());
        BlockPos currentPos = new BlockPos(golem.getBlockX(), footY, golem.getBlockZ());
        long gameTime = level.getGameTime();

        BlockPos snapshotCenter = currentPos.immutable();
        BlockPos minPos = snapshotCenter.offset(-this.radius - 2, -36, -this.radius - 2);
        BlockPos maxPos = snapshotCenter.offset(this.radius + 2, 16, this.radius + 2);

        if (!level.hasChunksAt(minPos, maxPos)) {
            return;
        }

        // Increment version to discard any previous in-flight computation from inside
        // the pit
        int version = this.computeVersion.incrementAndGet();
        this.isComputing = true;
        this.lastComputeTime = gameTime;

        // Temporarily clear safeBlocks to fail-open (isConfirmedHazard returns false)
        // while new terrain is scanned
        this.safeBlocks = LongSets.EMPTY_SET;
        this.hazardSurfaceBlocks = LongSets.EMPTY_SET;
        this.centerPos = snapshotCenter;

        PathNavigationRegion region = new PathNavigationRegion(level, minPos, maxPos);

        CompletableFuture.runAsync(() -> {
            try {
                Result result = computeSafeAndHazardBlocks(region, snapshotCenter);
                if (version != this.computeVersion.get()) {
                    return;
                }

                // Publish results atomically
                this.safeBlocks = LongSets.unmodifiable(result.safeBlocks);
                this.hazardSurfaceBlocks = LongSets.unmodifiable(result.hazardBlocks);
                this.centerPos = snapshotCenter;
            } catch (Exception e) {
                Constants.LOG.error("Failed to compute forced golem safe zone asynchronously", e);
            } finally {
                if (version == this.computeVersion.get()) {
                    this.isComputing = false;
                }
            }
        }, Constants.ASYNC_POOL);
    }

    /**
     * Runs 100% OFF-THREAD on ASYNC_POOL.
     * Flood-fills outward from the golem's position using reversible 1-block steps
     * until convergence.
     * Any surface within the radius that was NOT reached by the BFS is confirmed
     * unreachable and marked as unsafe.
     */
    private Result computeSafeAndHazardBlocks(PathNavigationRegion region, BlockPos newCenter) {
        LongOpenHashSet newSafeBlocks = new LongOpenHashSet();

        // 1. Discover and flood-fill ALL safe reversible terrain via 8-way BFS from all
        // ground seeds under the golem
        List<BlockPos> startSeeds = findWalkableGroundSeeds(region, newCenter);
        int referenceFootY = newCenter.getY();
        if (!startSeeds.isEmpty()) {
            referenceFootY = startSeeds.get(0).getY();
        }

        Queue<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos seed : startSeeds) {
            long packed = seed.asLong();
            if (!newSafeBlocks.contains(packed)) {
                newSafeBlocks.add(packed);
                queue.add(seed);
            }
        }

        BlockPos.MutableBlockPos targetPos = new BlockPos.MutableBlockPos();

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();

            for (int[] offset : HORIZONTAL_OFFSETS) {
                int dx = offset[0];
                int dz = offset[1];
                int nx = current.getX() + dx;
                int nz = current.getZ() + dz;

                int distCenterSq = (nx - newCenter.getX()) * (nx - newCenter.getX())
                        + (nz - newCenter.getZ()) * (nz - newCenter.getZ());
                if (distCenterSq > this.radiusSqr) {
                    continue;
                }

                boolean isDiagonal = (dx != 0 && dz != 0);
                boolean canClimb = (Config.DATA != null && Config.DATA.enableLedgeClimb);
                int[] dyOffsets = canClimb ? new int[] { 0, 1, -1, 2 } : new int[] { 0, 1, -1 };

                // Check flat step first (dy = 0), then step up (+1), then step down (-1), then
                // ledge mantle (+2)
                for (int dy : dyOffsets) {
                    if (dy == 2 && isDiagonal) {
                        continue; // 2-block ledge climbs are orthogonal
                    }

                    int ny = current.getY() + dy;
                    if (Math.abs(ny - newCenter.getY()) > 32) {
                        continue;
                    }

                    if (isDiagonal) {
                        // Check intermediate corner clearance: the two orthogonal intermediate cells
                        // must not be solid blocks at movement height so the golem does not clip
                        // through block corners
                        int checkY = Math.max(current.getY(), ny);
                        targetPos.set(current.getX() + dx, checkY, current.getZ());
                        if (!region.getBlockState(targetPos).getCollisionShape(region, targetPos).isEmpty()) {
                            continue;
                        }
                        targetPos.set(current.getX(), checkY, current.getZ() + dz);
                        if (!region.getBlockState(targetPos).getCollisionShape(region, targetPos).isEmpty()) {
                            continue;
                        }
                    }

                    targetPos.set(nx, ny, nz);
                    long packed = targetPos.asLong();

                    if (!newSafeBlocks.contains(packed) && isRecoverableGround(region, targetPos)) {
                        newSafeBlocks.add(packed);
                        queue.add(targetPos.immutable());
                    }
                }
            }
        }

        // 2. Surface column evaluation for debug visualization: delegated to
        // GolemDebugRenderer
        LongOpenHashSet newHazardBlocks = (Config.DATA != null && Config.DATA.showPitFallDebug)
                ? GolemDebugRenderer.computeHazardSurfaceBlocks(region, newCenter, referenceFootY, newSafeBlocks,
                        this.radius, this.radiusSqr)
                : new LongOpenHashSet();

        return new Result(newSafeBlocks, newHazardBlocks);
    }

    private static List<BlockPos> findWalkableGroundSeeds(BlockGetter level, BlockPos center) {
        List<BlockPos> seeds = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // Check feet elevation (dy = 0), slight elevation/slabs (dy = 1), and path
        // blocks/step downs (dy = -1)
        for (int dy : new int[] { 0, 1, -1 }) {
            for (int[] offset : FOOTPRINT_OFFSETS) {
                pos.set(center.getX() + offset[0], center.getY() + dy, center.getZ() + offset[1]);
                if (isRecoverableGround(level, pos)) {
                    BlockPos imm = pos.immutable();
                    if (!seeds.contains(imm)) {
                        seeds.add(imm);
                    }
                }
            }
        }

        // If no recoverable ground was found within ±1 of feet across the entire
        // footprint,
        // the golem is genuinely airborne / falling in mid-air. Only then probe
        // straight down under center for landing surface.
        if (seeds.isEmpty()) {
            for (int dy = -2; dy >= -8; dy--) {
                pos.set(center.getX(), center.getY() + dy, center.getZ());
                if (isRecoverableGround(level, pos)) {
                    seeds.add(pos.immutable());
                    break;
                }
            }
        }

        if (seeds.isEmpty()) {
            seeds.add(center);
        }
        return seeds;
    }

    public static boolean isRecoverableGround(BlockGetter level, BlockPos pos) {
        FluidState fluid = level.getFluidState(pos);
        if (fluid.is(FluidTags.LAVA)) {
            return false; // Lava is deadly to golems
        }

        BlockState footState = level.getBlockState(pos);
        if (!footState.getCollisionShape(level, pos).isEmpty()) {
            return false;
        }

        // Headroom: Golem requires vertical clearance of ~3 blocks (can be air or
        // water, but not solid blocks or lava)
        if (level.getFluidState(pos.above()).is(FluidTags.LAVA)
                || level.getFluidState(pos.above(2)).is(FluidTags.LAVA)) {
            return false;
        }
        if (!level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty() || !level.getBlockState(pos.above(2)).getCollisionShape(level, pos.above(2)).isEmpty()) {
            return false;
        }

        BlockPos belowPos = pos.below();
        BlockState belowState = level.getBlockState(belowPos);
        if (belowState.is(BlockTags.TRAPDOORS)) {
            return false; // Trapdoors can open and drop golems into pits
        }

        boolean canClimb = (Config.DATA != null && Config.DATA.enableLedgeClimb);
        if (!canClimb && (belowState.is(BlockTags.FENCES) || belowState.is(BlockTags.WALLS))) {
            return false; // Cannot stand on / jump over fences or walls when climbing is disabled
        }

        VoxelShape belowShape = belowState.getCollisionShape(level, belowPos);
        if (belowShape.isEmpty()) {
            return false;
        }

        // Allow full blocks (1.0), slightly less than 1 (path blocks 0.9375, farmland,
        // mud, slabs),
        // and fences/walls (1.5) when climbing is enabled
        double topY = belowShape.max(Direction.Axis.Y);
        double maxAllowedTopY = canClimb ? 1.5 : 1.0;
        return topY >= 0.25 && topY <= maxAllowedTopY;
    }

    /**
     * Checks if the position is within the indexed radius.
     */
    public boolean isWithinIndexedRadius(int x, int z) {
        if (this.centerPos == BlockPos.ZERO || this.safeBlocks.isEmpty()) {
            return false;
        }
        int dx = x - this.centerPos.getX();
        int dz = z - this.centerPos.getZ();
        return (dx * dx + dz * dz) <= this.radiusSqr;
    }

    /**
     * Returns true if the position (or adjacent Y block if on path block/slab or
     * airborne) is verified safe.
     * Uses zero-allocation bit packing.
     */
    public boolean isSafe(int x, int y, int z) {
        return this.safeBlocks.contains(BlockPos.asLong(x, y, z))
                || this.safeBlocks.contains(BlockPos.asLong(x, y + 1, z))
                || this.safeBlocks.contains(BlockPos.asLong(x, y - 1, z));
    }

    public boolean isSafe(BlockPos pos) {
        return this.isSafe(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Fail-open query: only true if position is WITHIN indexed radius AND confirmed
     * NOT safe.
     * Unindexed distant blocks return false so the golem is not artificially
     * restricted.
     * Uses zero-allocation bit packing.
     */
    public boolean isConfirmedHazard(int x, int y, int z) {
        return this.isWithinIndexedRadius(x, z) && !this.isSafe(x, y, z);
    }

    public boolean isConfirmedHazard(BlockPos pos) {
        return this.isConfirmedHazard(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Checks if any cardinal neighbor of (x, y, z) is a confirmed hazard.
     * Used to penalize edge nodes near pits/cliffs so pathfinding favors center
     * ground.
     */
    public boolean isAdjacentToHazard(int x, int y, int z) {
        return this.isConfirmedHazard(x, y, z - 1)
                || this.isConfirmedHazard(x, y, z + 1)
                || this.isConfirmedHazard(x + 1, y, z)
                || this.isConfirmedHazard(x - 1, y, z);
    }

    /**
     * Checks if a target entity is located in an unrecoverable hazard.
     * Prevents false positives when a target is flung into the air by an attack
     * or is jumping over safe ground.
     */
    public boolean isTargetInHazard(LivingEntity target) {
        if (target == null || target.isRemoved() || !target.isAlive()) {
            return false;
        }

        // Handle vehicles (boats, minecarts): evaluate vehicle position and ground
        // state
        Entity rootVehicle = target.getRootVehicle();
        boolean isPassenger = (rootVehicle != target);
        BlockPos targetPos = isPassenger ? rootVehicle.blockPosition() : target.blockPosition();
        boolean onGround = isPassenger ? rootVehicle.onGround() : target.onGround();
        boolean inWater = isPassenger ? rootVehicle.isInWater() : target.isInWater();
        int tx = targetPos.getX();
        int ty = targetPos.getY();
        int tz = targetPos.getZ();

        // 1. If airborne (not on ground and not in water), check the ground beneath it
        if (!onGround && !inWater) {
            for (int dy = 0; dy >= -5; dy--) {
                if (this.isSafe(tx, ty + dy, tz)) {
                    // Mob is airborne above verified safe ground (e.g. flung into the air by golem
                    // attack)
                    return false;
                }
            }
            // Mob is airborne and NOT above safe ground (falling or over an unrecoverable
            // pit/hazard)
            return this.isConfirmedHazard(tx, ty, tz);
        }

        // 2. If in water, probe downward through the water column to see if the ground
        // beneath is safe
        if (inWater) {
            for (int dy = 0; dy >= -6; dy--) {
                if (this.isSafe(tx, ty + dy, tz)) {
                    // The ground beneath the water column is safe and accessible
                    return false;
                }
            }
        }

        // 3. On ground or in water without safe ground beneath: evaluate whether the
        // position is a confirmed hazard
        return this.isConfirmedHazard(tx, ty, tz);
    }

    public LongSet getHazardSurfaceBlocks() {
        return this.hazardSurfaceBlocks;
    }

    private static class Result {
        final LongOpenHashSet safeBlocks;
        final LongOpenHashSet hazardBlocks;

        Result(LongOpenHashSet safeBlocks, LongOpenHashSet hazardBlocks) {
            this.safeBlocks = safeBlocks;
            this.hazardBlocks = hazardBlocks;
        }
    }
}
