package com.fdimo.golemsactuallyprotectvillagers.ai;

import com.fdimo.golemsactuallyprotectvillagers.Config;
import com.fdimo.golemsactuallyprotectvillagers.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class AsyncGolemPathfinder {

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
                String msg = String.format("[Golems Async] Queue: %d/%d | New/sec: %.1f | Executed/sec: %.1f",
                    pending, Config.DATA.asyncQueueCapacity, newlyAddedPerSec, executedPerSec);
                net.minecraft.network.chat.Component comp = net.minecraft.network.chat.Component.literal("§e" + msg);
                for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
                    player.sendSystemMessage(comp);
                }
            }
        }
    }

    public static void evaluateThreatsAsync(IronGolem golem, ServerLevel serverLevel, List<ThreatCandidate> candidates, Consumer<List<ThreatCandidate>> callback) {
        if (candidates.isEmpty() || golem.isRemoved() || !golem.isAlive()) {
            callback.accept(List.of());
            return;
        }

        BlockPos golemPos = golem.blockPosition();
        int range = (int) (Config.DATA.golemScanRadius * 1.5);
        PathNavigationRegion region = new PathNavigationRegion(
            serverLevel,
            golemPos.offset(-range, -range, -range),
            golemPos.offset(range, range, range)
        );

        newPathfindings.addAndGet(candidates.size());

        CompletableFuture.runAsync(() -> {
            try {
                WalkNodeEvaluator nodeEvaluator = new WalkNodeEvaluator();
                nodeEvaluator.setCanPassDoors(true);
                nodeEvaluator.setCanOpenDoors(true);
                PathFinder pathFinder = new PathFinder(nodeEvaluator, Config.DATA.maxPathfindingNodes);
                float maxPathDistance = (float) (Config.DATA.golemScanRadius * 2.0F);

                for (ThreatCandidate candidate : candidates) {
                    executedPathfindings.incrementAndGet();

                    if (!candidate.getMob().isAlive() || candidate.getMob().isRemoved()) {
                        continue;
                    }

                    BlockPos targetPos = candidate.getMob().blockPosition();
                    Path path = pathFinder.findPath(region, golem, Set.of(targetPos), maxPathDistance, 1, 1.0F);

                    if (path != null && path.canReach()) {
                        int nodeCount = Math.max(1, path.getNodeCount());
                        candidate.setPathResult(path, true, nodeCount);
                    } else if (path != null) {
                        // Incomplete / partially obstructed path
                        int nodeCount = path.getNodeCount() + 25;
                        candidate.setPathResult(path, false, nodeCount);
                    } else {
                        // Completely unpathable
                        int nodeCount = (int) (candidate.getEuclideanDist() * 2.0) + 40;
                        candidate.setPathResult(null, false, nodeCount);
                    }
                }

                serverLevel.getServer().execute(() -> {
                    if (!golem.isRemoved() && golem.isAlive()) {
                        callback.accept(candidates);
                    } else {
                        callback.accept(List.of());
                    }
                });
            } catch (Exception e) {
                Constants.LOG.error("Async golem pathfinding error", e);
                serverLevel.getServer().execute(() -> callback.accept(List.of()));
            }
        }, Constants.ASYNC_POOL);
    }
}

