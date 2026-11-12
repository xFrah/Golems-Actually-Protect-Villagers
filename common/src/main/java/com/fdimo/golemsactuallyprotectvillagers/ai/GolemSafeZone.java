package com.fdimo.golemsactuallyprotectvillagers.ai;

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
import net.minecraft.world.entity.animal.IronGolem;
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

/**
 * Maintains a rolling/sliding window of safe, recoverable terrain around an Iron Golem.
 * Computed entirely asynchronously on Constants.ASYNC_POOL using PathNavigationRegion snapshots.
 *
 * Safe blocks are defined by symmetric, reversible connectivity (|dy| &le; 1.0, no fluids, adequate clearance).
 * Any block outside the safe zone within the scanned radius is a confirmed hazard (hole, cliff, deep water).
 */
public class GolemSafeZone {

    private volatile LongSet safeBlocks = LongSets.EMPTY_SET;
    private volatile LongSet hazardSurfaceBlocks = LongSets.EMPTY_SET;
    private volatile boolean isComputing = false;
    private volatile BlockPos centerPos = BlockPos.ZERO;
    private long lastComputeTime = 0L;
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
     * 4. The golem is stationary and the idle refresh timer has elapsed (80 ticks / 4s).
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

        this.isComputing = true;
        this.lastComputeTime = gameTime;
        BlockPos snapshotCenter = currentPos.immutable();

        PathNavigationRegion region = new PathNavigationRegion(
            level,
            snapshotCenter.offset(-this.radius - 2, -36, -this.radius - 2),
            snapshotCenter.offset(this.radius + 2, 16, this.radius + 2)
        );

        CompletableFuture.runAsync(() -> {
            try {
                Result result = computeSafeAndHazardBlocks(region, snapshotCenter);

                // Publish results atomically
                this.safeBlocks = LongSets.unmodifiable(result.safeBlocks);
                this.hazardSurfaceBlocks = LongSets.unmodifiable(result.hazardBlocks);
                this.centerPos = snapshotCenter;
            } catch (Exception e) {
                Constants.LOG.error("Failed to compute golem safe zone asynchronously", e);
            } finally {
                this.isComputing = false;
            }
        }, Constants.ASYNC_POOL);
    }

    /**
     * Runs 100% OFF-THREAD on ASYNC_POOL.
     * Flood-fills outward from the golem's position using reversible 1-block steps until convergence.
     * Any surface within the radius that was NOT reached by the BFS is confirmed unreachable and marked as unsafe.
     */
    private Result computeSafeAndHazardBlocks(PathNavigationRegion region, BlockPos newCenter) {
        LongOpenHashSet newSafeBlocks = new LongOpenHashSet();
        LongOpenHashSet newHazardBlocks = new LongOpenHashSet();

        // 1. Discover and flood-fill ALL safe reversible terrain via 8-way BFS from all ground seeds under the golem
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

        final int[][] HORIZONTAL_OFFSETS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, // Cardinal
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1} // Diagonal
        };

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

                // Check flat step first (dy = 0), then step up (+1), then step down (-1)
                for (int dy : new int[]{0, 1, -1}) {
                    int ny = current.getY() + dy;
                    if (Math.abs(ny - newCenter.getY()) > 32) {
                        continue;
                    }

                    if (isDiagonal) {
                        // Check intermediate corner clearance: the two orthogonal intermediate cells
                        // must not be solid blocks at movement height so the golem does not clip through block corners
                        int checkY = Math.max(current.getY(), ny);
                        targetPos.set(current.getX() + dx, checkY, current.getZ());
                        if (region.getBlockState(targetPos).blocksMotion()) {
                            continue;
                        }
                        targetPos.set(current.getX(), checkY, current.getZ() + dz);
                        if (region.getBlockState(targetPos).blocksMotion()) {
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

        // 2. Convergence complete: evaluate surface columns within the radius.
        // Any ground or water surface that the BFS could NOT reach is UNREACHABLE and marked as an unsafe hazard!
        int scanRadius = this.radius;
        int scanRadiusSqr = this.radiusSqr;
        BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();

        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            for (int dz = -scanRadius; dz <= scanRadius; dz++) {
                if (dx * dx + dz * dz > scanRadiusSqr) {
                    continue;
                }

                int sx = newCenter.getX() + dx;
                int sz = newCenter.getZ() + dz;

                // Deep scan: from 16 blocks above footing down 52 blocks (covers deep ravines down to Y-36)
                SurfaceInfo surface = findSurfaceInfoAt(region, scanPos, sx, referenceFootY + 16, sz, 52);
                if (surface != null) {
                    // Check if the floor foot position (or adjacent elevation step) was reached by the BFS
                    boolean isSafe = newSafeBlocks.contains(surface.footPos.asLong())
                        || newSafeBlocks.contains(surface.footPos.above().asLong())
                        || newSafeBlocks.contains(surface.footPos.below().asLong());
                    if (!isSafe) {
                        newHazardBlocks.add(surface.renderPos.asLong());
                    }
                }
            }
        }

        return new Result(newSafeBlocks, newHazardBlocks);
    }

    private static List<BlockPos> findWalkableGroundSeeds(BlockGetter level, BlockPos center) {
        List<BlockPos> seeds = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        final int[][] FOOTPRINT_OFFSETS = {
            {0, 0},
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };

        // Check feet elevation (dy = 0), slight elevation/slabs (dy = 1), and path blocks/step downs (dy = -1)
        for (int dy : new int[]{0, 1, -1}) {
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

        // If no recoverable ground was found within ±1 of feet across the entire footprint,
        // the golem is genuinely airborne / falling in mid-air. Only then probe straight down under center for landing surface.
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

    private static SurfaceInfo findSurfaceInfoAt(BlockGetter level, BlockPos.MutableBlockPos pos, int x, int startY, int z, int maxDepth) {
        BlockPos waterSurface = null;

        for (int y = startY; y >= startY - maxDepth; y--) {
            pos.set(x, y, z);
            FluidState fluid = level.getFluidState(pos);
            if (fluid.is(FluidTags.LAVA)) {
                return new SurfaceInfo(pos.immutable(), pos.immutable());
            }
            if (fluid.is(FluidTags.WATER) && waterSurface == null) {
                waterSurface = pos.immutable();
            }

            BlockState state = level.getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (!shape.isEmpty()) {
                BlockPos floorBlock = pos.immutable();
                BlockPos footPos = floorBlock.above();
                BlockPos renderPos = (waterSurface != null) ? waterSurface : floorBlock;
                return new SurfaceInfo(renderPos, footPos);
            }
        }
        return null;
    }

    public static boolean isRecoverableGround(BlockGetter level, BlockPos pos) {
        FluidState fluid = level.getFluidState(pos);
        if (fluid.is(FluidTags.LAVA)) {
            return false; // Lava is deadly to golems
        }

        BlockState footState = level.getBlockState(pos);
        if (footState.blocksMotion()) {
            return false;
        }

        // Headroom: Golem requires vertical clearance of ~3 blocks (can be air or water, but not solid blocks or lava)
        if (level.getFluidState(pos.above()).is(FluidTags.LAVA) || level.getFluidState(pos.above(2)).is(FluidTags.LAVA)) {
            return false;
        }
        if (level.getBlockState(pos.above()).blocksMotion() || level.getBlockState(pos.above(2)).blocksMotion()) {
            return false;
        }

        BlockPos belowPos = pos.below();
        BlockState belowState = level.getBlockState(belowPos);
        if (belowState.is(BlockTags.FENCES) || belowState.is(BlockTags.WALLS) || belowState.is(BlockTags.TRAPDOORS)) {
            return false; // Cannot stand on / jump over fences or walls, trapdoors can be traps
        }

        VoxelShape belowShape = belowState.getCollisionShape(level, belowPos);
        if (belowShape.isEmpty()) {
            return false;
        }

        // Allow full blocks (1.0) and blocks with height slightly less than 1 (path blocks 0.9375, farmland, mud, slabs)
        double topY = belowShape.max(Direction.Axis.Y);
        return topY >= 0.25 && topY <= 1.0;
    }

    /**
     * Checks if the position is within the indexed radius.
     */
    public boolean isWithinIndexedRadius(BlockPos pos) {
        if (this.centerPos == BlockPos.ZERO || this.safeBlocks.isEmpty()) {
            return false;
        }
        int dx = pos.getX() - this.centerPos.getX();
        int dz = pos.getZ() - this.centerPos.getZ();
        return (dx * dx + dz * dz) <= this.radiusSqr;
    }

    /**
     * Returns true if the position (or adjacent Y block if on path block/slab or airborne) is verified safe.
     */
    public boolean isSafe(BlockPos pos) {
        return this.safeBlocks.contains(pos.asLong())
            || this.safeBlocks.contains(pos.above().asLong())
            || this.safeBlocks.contains(pos.below().asLong());
    }

    /**
     * Fail-open query: only true if position is WITHIN indexed radius AND confirmed NOT safe.
     * Unindexed distant blocks return false so the golem is not artificially restricted.
     */
    public boolean isConfirmedHazard(BlockPos pos) {
        return isWithinIndexedRadius(pos) && !isSafe(pos);
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

        // Handle vehicles (boats, minecarts): evaluate vehicle position and ground state
        Entity rootVehicle = target.getRootVehicle();
        boolean isPassenger = (rootVehicle != target);
        BlockPos targetPos = isPassenger ? rootVehicle.blockPosition() : target.blockPosition();
        boolean onGround = isPassenger ? rootVehicle.onGround() : target.onGround();
        boolean inWater = isPassenger ? rootVehicle.isInWater() : target.isInWater();

        // 1. If airborne (not on ground and not in water), check the ground beneath it
        if (!onGround && !inWater) {
            BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
            for (int dy = 0; dy >= -5; dy--) {
                probe.set(targetPos.getX(), targetPos.getY() + dy, targetPos.getZ());
                if (this.isSafe(probe)) {
                    // Mob is airborne above verified safe ground (e.g. flung into the air by golem attack)
                    return false;
                }
            }
            // Mob is airborne and NOT above safe ground (falling or over an unrecoverable pit/hazard)
            return this.isConfirmedHazard(targetPos);
        }

        // 2. If in water, probe downward through the water column to see if the ground beneath is safe
        if (inWater) {
            BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
            for (int dy = 0; dy >= -6; dy--) {
                probe.set(targetPos.getX(), targetPos.getY() + dy, targetPos.getZ());
                if (this.isSafe(probe)) {
                    // The ground beneath the water column is safe and accessible
                    return false;
                }
            }
        }

        // 3. On ground or in water without safe ground beneath: evaluate whether the position is a confirmed hazard
        return this.isConfirmedHazard(targetPos);
    }

    public LongSet getHazardSurfaceBlocks() {
        return this.hazardSurfaceBlocks;
    }

    public BlockPos getCenterPos() {
        return this.centerPos;
    }

    /**
     * Resolves the actual walkable ground or fluid surface beneath an entity position.
     * Prevents false hazard detection when mobs jump or are flung airborne by golem attacks.
     */
    public BlockPos getGroundBelow(BlockGetter level, BlockPos pos) {
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy >= -6; dy--) {
            mpos.set(pos.getX(), pos.getY() + dy, pos.getZ());
            if (level.getFluidState(mpos).is(FluidTags.WATER) || level.getFluidState(mpos).is(FluidTags.LAVA)) {
                return mpos.immutable();
            }
            BlockState state = level.getBlockState(mpos);
            if (!state.isAir() && !state.getCollisionShape(level, mpos).isEmpty()) {
                return mpos.above().immutable();
            }
        }
        return pos;
    }

    private static class SurfaceInfo {
        final BlockPos renderPos;
        final BlockPos footPos;

        SurfaceInfo(BlockPos renderPos, BlockPos footPos) {
            this.renderPos = renderPos;
            this.footPos = footPos;
        }
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
