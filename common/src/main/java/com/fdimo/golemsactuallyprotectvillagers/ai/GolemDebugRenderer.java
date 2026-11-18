package com.fdimo.golemsactuallyprotectvillagers.ai;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

/**
 * Utility class for rendering debug particle visualizations (hazards and active paths).
 */
public final class GolemDebugRenderer {

    private GolemDebugRenderer() {}

    private static final DustParticleOptions BLUE_PATH_DUST = new DustParticleOptions(
            new Vector3f(0.2F, 0.6F, 1.0F), 1.2F);
    private static final DustParticleOptions YELLOW_PATH_DUST = new DustParticleOptions(
            new Vector3f(1.0F, 0.9F, 0.1F), 1.2F);

    public static void renderPitFallDebug(IronGolem golem, ServerLevel serverLevel, GolemSafeZone safeZone) {
        if (safeZone == null) {
            return;
        }
        LongSet hazards = safeZone.getHazardSurfaceBlocks();
        if (hazards.isEmpty()) {
            return;
        }

        BlockPos golemPos = golem.blockPosition();
        int count = 0;
        int maxParticles = 2500;
        LongIterator it = hazards.iterator();
        while (it.hasNext() && count < maxParticles) {
            long packed = it.nextLong();
            int x = BlockPos.getX(packed);
            int y = BlockPos.getY(packed);
            int z = BlockPos.getZ(packed);

            int dx = x - golemPos.getX();
            int dz = z - golemPos.getZ();
            if (dx * dx + dz * dz <= 576) {
                BlockPos hazardPos = new BlockPos(x, y, z);
                boolean isWater = serverLevel.getFluidState(hazardPos).is(FluidTags.WATER);
                double py;
                if (isWater) {
                    py = y + 0.9;
                } else {
                    VoxelShape shape = serverLevel.getBlockState(hazardPos).getCollisionShape(serverLevel, hazardPos);
                    double top = shape.isEmpty() ? 1.0 : shape.max(net.minecraft.core.Direction.Axis.Y);
                    py = y + top + 0.05;
                }
                serverLevel.sendParticles(DustParticleOptions.REDSTONE, x + 0.5, py, z + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
                count++;
            }
        }
    }

    public static void renderPathDebug(IronGolem golem, ServerLevel serverLevel, ProtectVillagersTargetGoal protectGoal) {
        // 1. Original reachability validation path (Yellow) displayed for ~5 seconds when evaluated
        if (protectGoal != null) {
            Path validatedPath = protectGoal.getLastValidatedPath(serverLevel.getGameTime());
            if (validatedPath != null) {
                renderSpecificPath(serverLevel, validatedPath, YELLOW_PATH_DUST, 0.22, 0);
            }
        }

        // 2. Active vanilla navigation path (Blue)
        Path currentPath = golem.getNavigation().getPath();
        if (currentPath != null && !currentPath.isDone()) {
            renderSpecificPath(serverLevel, currentPath, BLUE_PATH_DUST, 0.10,
                    Math.max(0, currentPath.getNextNodeIndex() - 1));
        }
    }

    private static void renderSpecificPath(ServerLevel serverLevel, Path path, DustParticleOptions dust,
            double heightOffset, int startIndex) {
        int nodeCount = path.getNodeCount();
        for (int i = startIndex; i < nodeCount; i++) {
            Node node = path.getNode(i);
            int x = node.x;
            int y = node.y;
            int z = node.z;

            BlockPos nodePos = new BlockPos(x, y, z);
            boolean isWater = serverLevel.getFluidState(nodePos).is(FluidTags.WATER);
            double py;
            if (isWater) {
                py = y + 0.8 + heightOffset;
            } else {
                BlockPos groundPos = nodePos.below();
                VoxelShape shape = serverLevel.getBlockState(groundPos).getCollisionShape(serverLevel, groundPos);
                double top = shape.isEmpty() ? 0.0 : shape.max(net.minecraft.core.Direction.Axis.Y);
                py = groundPos.getY() + top + heightOffset;
            }

            serverLevel.sendParticles(dust, x + 0.5, py, z + 0.5, 1, 0.0, 0.0, 0.0, 0.0);

            // Interpolate a midpoint particle between consecutive nodes for a continuous trail
            if (i + 1 < nodeCount) {
                Node next = path.getNode(i + 1);
                double midX = (x + next.x) * 0.5 + 0.5;
                double midZ = (z + next.z) * 0.5 + 0.5;
                serverLevel.sendParticles(dust, midX, py, midZ, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    /**
     * Deep-scans columns within radius to find top surface coordinates of hazard/pitfall blocks
     * for red debug particle visualization.
     */
    public static LongOpenHashSet computeHazardSurfaceBlocks(
            BlockGetter level, BlockPos center, int referenceFootY, LongSet safeBlocks, int radius, int radiusSqr) {
        LongOpenHashSet hazardBlocks = new LongOpenHashSet();
        BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radiusSqr) {
                    continue;
                }

                int sx = center.getX() + dx;
                int sz = center.getZ() + dz;

                // Deep scan: from 16 blocks above footing down 52 blocks (covers deep ravines down to Y-36)
                SurfaceInfo surface = findSurfaceInfoAt(level, scanPos, sx, referenceFootY + 16, sz, 52);
                if (surface != null) {
                    // Check if the floor foot position (or adjacent elevation step) was reached by the BFS
                    boolean isSafe = safeBlocks.contains(surface.footPos.asLong())
                            || safeBlocks.contains(surface.footPos.above().asLong())
                            || safeBlocks.contains(surface.footPos.below().asLong());
                    if (!isSafe) {
                        hazardBlocks.add(surface.renderPos.asLong());
                    }
                }
            }
        }
        return hazardBlocks;
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

    private static class SurfaceInfo {
        final BlockPos renderPos;
        final BlockPos footPos;

        SurfaceInfo(BlockPos renderPos, BlockPos footPos) {
            this.renderPos = renderPos;
            this.footPos = footPos;
        }
    }
}
