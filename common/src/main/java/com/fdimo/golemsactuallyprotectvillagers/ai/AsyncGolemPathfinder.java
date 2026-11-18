package com.fdimo.golemsactuallyprotectvillagers.ai;

import com.fdimo.golemsactuallyprotectvillagers.Config;
import com.fdimo.golemsactuallyprotectvillagers.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class AsyncGolemPathfinder {

    public static final ThreadLocal<Boolean> EVALUATING_PROTECT_PATH = ThreadLocal.withInitial(() -> false);
    public static final ThreadLocal<ThreatCandidate> CURRENT_EVALUATING_CANDIDATE = new ThreadLocal<>();
    public static final AtomicInteger executedPathfindings = new AtomicInteger(0);
    public static final AtomicInteger newPathfindings = new AtomicInteger(0);
    private static long lastPrintTime = 0;

    public static void printMetrics(ServerLevel serverLevel) {
        long time = serverLevel.getGameTime();
        if (time - lastPrintTime >= 100) {
            lastPrintTime = time;

            int executed = executedPathfindings.getAndSet(0);
            int newlyAdded = newPathfindings.getAndSet(0);
            int pending = Constants.ASYNC_POOL.getQueue().size();

            float executedPerSec = executed / 5.0f;
            float newlyAddedPerSec = newlyAdded / 5.0f;

            if (Config.DATA.enableQueueDebug) {
                String msg = String.format("§e[Golems Async] Queue: %d/%d | New/sec: %.1f | Executed/sec: %.1f",
                    pending, Config.DATA.asyncQueueCapacity, newlyAddedPerSec, executedPerSec);
                Component comp = Component.literal(msg);
                for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
                    player.sendSystemMessage(comp);
                }
            }
        }
    }

    /**
     * Safely constructs a PathNavigationRegion spanning two points with padding, clamped to world build bounds.
     * Returns null if chunks within the region are not loaded.
     */
    public static PathNavigationRegion createRegionSnapshot(
            ServerLevel level, BlockPos from, BlockPos to, int padding, int maxHorizontalRange, int verticalRange) {
        int minX = Math.min(from.getX(), to.getX());
        int maxX = Math.max(from.getX(), to.getX());
        int minY = Math.min(from.getY(), to.getY());
        int maxY = Math.max(from.getY(), to.getY());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxZ = Math.max(from.getZ(), to.getZ());

        int clampedMinX = Math.max(from.getX() - maxHorizontalRange, minX - padding);
        int clampedMaxX = Math.min(from.getX() + maxHorizontalRange, maxX + padding);
        int clampedMinY = Math.max(level.getMinY(), Math.max(from.getY() - verticalRange, minY - padding));
        int clampedMaxY = Math.min(level.getMaxY(), Math.min(from.getY() + verticalRange, maxY + padding));
        int clampedMinZ = Math.max(from.getZ() - maxHorizontalRange, minZ - padding);
        int clampedMaxZ = Math.min(from.getZ() + maxHorizontalRange, maxZ + padding);

        BlockPos minPos = new BlockPos(clampedMinX, clampedMinY, clampedMinZ);
        BlockPos maxPos = new BlockPos(clampedMaxX, clampedMaxY, clampedMaxZ);

        if (!level.hasChunksAt(minPos, maxPos)) {
            return null;
        }

        return new PathNavigationRegion(level, minPos, maxPos);
    }

    public static void evaluateThreatAsync(IronGolem golem, ServerLevel serverLevel, ThreatCandidate candidate, Consumer<ThreatCandidate> callback) {
        if (candidate == null || golem.isRemoved() || !golem.isAlive()) {
            callback.accept(null);
            return;
        }

        Mob threat = candidate.getMob();
        if (!threat.isAlive() || threat.isRemoved()) {
            callback.accept(null);
            return;
        }

        BlockPos golemPos = golem.blockPosition();
        BlockPos threatPos = threat.blockPosition();

        int maxRange = (int) Config.DATA.golemScanRadius;
        PathNavigationRegion region = createRegionSnapshot(serverLevel, golemPos, threatPos, 16, maxRange, 32);
        if (region == null) {
            callback.accept(null);
            return;
        }

        newPathfindings.incrementAndGet();

        CompletableFuture.runAsync(() -> {
            try {
                executedPathfindings.incrementAndGet();

                if (!threat.isAlive() || threat.isRemoved()) {
                    serverLevel.getServer().execute(() -> callback.accept(null));
                    return;
                }

                WalkNodeEvaluator nodeEvaluator = new WalkNodeEvaluator();
                nodeEvaluator.setCanPassDoors(true);
                PathFinder pathFinder = new PathFinder(nodeEvaluator, Config.DATA.maxPathfindingNodes);
                float maxPathDistance = (float) (Config.DATA.golemScanRadius * 2.0F);

                Path path;
                EVALUATING_PROTECT_PATH.set(true);
                CURRENT_EVALUATING_CANDIDATE.set(candidate);
                try {
                    path = pathFinder.findPath(region, golem, Set.of(threatPos), maxPathDistance, 1, 1.0F);
                } finally {
                    CURRENT_EVALUATING_CANDIDATE.remove();
                    EVALUATING_PROTECT_PATH.set(false);
                }

                boolean forwardReachable = (path != null && path.canReach());
                boolean shoreAttackReachable = false;
                if (path != null && !forwardReachable && path.getEndNode() != null) {
                    Node endNode = path.getEndNode();
                    BlockPos endPos = endNode.asBlockPos();
                    double distSqr = endPos.distSqr(threatPos);
                    // If path ends in melee attack range (~3.5 blocks)
                    if (distSqr <= 12.25) {
                        shoreAttackReachable = true;
                    }
                }

                boolean isReachable = (forwardReachable || shoreAttackReachable) && !isDeadlyLavaOrVoid(region, threatPos);
                int nodeCount = (path != null) ? path.getNodeCount() : 0;
                candidate.setPathResult(path, isReachable, nodeCount);

                if (Config.DATA.enableChatDebug) {
                    final boolean finalReachable = isReachable;
                    final double endDist = (path != null && path.getEndNode() != null)
                        ? Math.sqrt(path.getEndNode().asBlockPos().distSqr(threatPos)) : -1.0;
                    serverLevel.getServer().execute(() -> {
                        DebugChat.send(serverLevel, "§6[Golem #%d] §7Path eval for §c%s§7: reachable=%b (dist=%.1f)",
                            golem.getId(), threat.getName().getString(), finalReachable, endDist);
                    });
                }

                serverLevel.getServer().execute(() -> {
                    if (!golem.isRemoved() && golem.isAlive()) {
                        callback.accept(candidate);
                    } else {
                        callback.accept(null);
                    }
                });
            } catch (Exception e) {
                Constants.LOG.error("Async golem pathfinding error", e);
                serverLevel.getServer().execute(() -> callback.accept(null));
            }
        }, Constants.ASYNC_POOL);
    }

    private static boolean isDeadlyLavaOrVoid(PathNavigationRegion region, BlockPos pos) {
        if (pos.getY() <= region.getMinY()) {
            return true;
        }
        return region.getFluidState(pos).is(FluidTags.LAVA)
            || region.getFluidState(pos.below()).is(FluidTags.LAVA);
    }
}
