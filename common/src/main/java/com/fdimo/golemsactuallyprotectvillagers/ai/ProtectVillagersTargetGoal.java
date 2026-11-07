package com.fdimo.golemsactuallyprotectvillagers.ai;

import com.fdimo.golemsactuallyprotectvillagers.Config;
import com.fdimo.golemsactuallyprotectvillagers.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

public class ProtectVillagersTargetGoal extends TargetGoal {

    private final IronGolem golem;
    private long lastScanTime = 0;

    private volatile boolean isEvaluatingAsync = false;
    private long asyncStartTime = 0;
    private long lastDisengageTime = 0;
    private Mob pendingTarget;
    private Path pendingPath;
    private Path precomputedPath;
    private String lastTargetName;

    public ProtectVillagersTargetGoal(IronGolem golem) {
        super(golem, false, false); // mustSee = false to prevent door/wall blindness
        this.golem = golem;
        this.setFlags(EnumSet.of(Goal.Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (!(this.golem.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        long gameTime = serverLevel.getGameTime();

        // Debounce: cooldown of 20 ticks (1.0s) after disengaging before evaluating or acquiring a new target
        if (gameTime - this.lastDisengageTime < 20) {
            return false;
        }

        // 1. If an async evaluation just finished and provided a valid target, adopt it immediately
        if (this.pendingTarget != null) {
            if (this.pendingTarget.isAlive() && !this.pendingTarget.isRemoved() && this.golem.canAttack(this.pendingTarget)) {
                if (this.pendingTarget.getType() == EntityType.DROWNED && this.pendingTarget.isInWater()) {
                    this.pendingTarget = null;
                    this.pendingPath = null;
                } else {
                    this.targetMob = this.pendingTarget;
                    this.precomputedPath = this.pendingPath;
                    this.pendingTarget = null;
                    this.pendingPath = null;
                    return true;
                }
            } else {
                this.pendingTarget = null;
                this.pendingPath = null;
            }
        }

        // 2. Vanilla persistence: if the golem already has an active living target, stay committed to it
        LivingEntity currentTarget = this.golem.getTarget();
        if (currentTarget != null && currentTarget.isAlive() && !currentTarget.isRemoved()) {
            return false;
        }

        // If an async pathfinding job is already running on ASYNC_POOL, wait for it
        // Safety: if the async job has been pending for more than 5 seconds (100 ticks),
        // assume the task was discarded by DiscardOldestPolicy and reset
        if (this.isEvaluatingAsync) {
            if (serverLevel.getGameTime() - this.asyncStartTime > 100) {
                this.isEvaluatingAsync = false;
            } else {
                return false;
            }
        }

        // Throttle scans when idle (default: every 100 ticks / 5 seconds)
        int scanInterval = Config.DATA != null ? Math.max(1, Config.DATA.idleScanIntervalTicks) : 100;
        if (gameTime - this.lastScanTime < scanInterval) {
            return false;
        }
        this.lastScanTime = gameTime;

        // Collect candidates and trigger async pathfinding
        this.startAsyncEvaluation(serverLevel);
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.targetMob != null && this.targetMob.getType() == EntityType.DROWNED && this.targetMob.isInWater()) {
            return false;
        }
        return super.canContinueToUse();
    }

    @Override
    protected double getFollowDistance() {
        return Math.max(super.getFollowDistance(), Config.DATA.golemScanRadius + 10.0);
    }

    @Override
    public void start() {
        this.golem.setTarget(this.targetMob);

        if (this.targetMob != null) {
            this.lastTargetName = this.targetMob.getName().getString();
        }

        boolean movingWithPrecomputed = false;
        // If we found a valid reachable path during async evaluation, kick off movement immediately
        if (this.precomputedPath != null && this.precomputedPath.canReach()) {
            this.golem.getNavigation().moveTo(this.precomputedPath, 1.25);
            movingWithPrecomputed = true;
        }

        if (this.golem.level() instanceof ServerLevel serverLevel && this.targetMob != null) {
            sendChatDebug(serverLevel, String.format("§6[Golem #%d] §cEngaging §4%s§c! (Moving on pre-computed path: %b)",
                this.golem.getId(), this.lastTargetName, movingWithPrecomputed));
        }

        if (Config.DATA.enableDebugLogs && this.targetMob != null) {
            Constants.LOG.info("[Golem Protection] Golem {} engaged threat {}",
                this.golem.getId(), this.targetMob.getName().getString());
        }
        super.start();
    }

    @Override
    public void stop() {
        if (this.golem.level() instanceof ServerLevel serverLevel) {
            this.lastDisengageTime = serverLevel.getGameTime();
            if (this.lastTargetName != null) {
                sendChatDebug(serverLevel, String.format("§6[Golem #%d] §bDisengaged from %s (target defeated or lost).",
                    this.golem.getId(), this.lastTargetName));
                this.lastTargetName = null;
            }
        }
        this.precomputedPath = null;
        this.pendingTarget = null;
        this.pendingPath = null;
        super.stop();
    }

    private void sendChatDebug(ServerLevel serverLevel, String message) {
        if (!Config.DATA.enableChatDebug) {
            return;
        }
        Component comp = Component.literal(message);
        for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
            player.sendSystemMessage(comp);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (Config.DATA.enableQueueDebug && this.golem.level() instanceof ServerLevel serverLevel && this.golem.tickCount % 20 == 0) {
            AsyncGolemPathfinder.printMetrics(serverLevel);
        }
    }

    private void startAsyncEvaluation(ServerLevel level) {
        double scanRadius = Config.DATA.golemScanRadius;
        AABB scanArea = this.golem.getBoundingBox().inflate(scanRadius);

        List<Villager> nearbyVillagers = level.getEntitiesOfClass(Villager.class, scanArea, Villager::isAlive);
        if (nearbyVillagers.isEmpty()) {
            return;
        }

        List<Mob> nearbyEnemies = level.getEntitiesOfClass(Mob.class, scanArea, mob -> {
            if (!mob.isAlive() || mob.isRemoved()) return false;
            if (mob.getType() == EntityType.CREEPER) return false;
            if (!((mob instanceof Enemy) || mob.getType().is(EntityTypeTags.RAIDERS))) return false;
            return this.golem.canAttack(mob);
        });

        if (nearbyEnemies.isEmpty()) {
            return;
        }

        List<ThreatCandidate> candidates = new ArrayList<>();
        for (Mob enemy : nearbyEnemies) {
            double baseUrgency = this.calculateBaseUrgency(enemy, nearbyVillagers);
            if (baseUrgency > 0.0) {
                double euclideanDist = this.golem.distanceTo(enemy);
                candidates.add(new ThreatCandidate(enemy, baseUrgency, euclideanDist));
            }
        }

        if (candidates.isEmpty()) {
            return;
        }

        // Bounded queue limit: sort by Euclidean distance and take the closest candidates
        candidates.sort(Comparator.comparingDouble(ThreatCandidate::getEuclideanDist));
        if (candidates.size() > Config.DATA.singleGolemQueueLimit) {
            candidates = new ArrayList<>(candidates.subList(0, Config.DATA.singleGolemQueueLimit));
        }

        sendChatDebug(level, String.format("§6[Golem #%d] §eFound %d threat(s) near villagers. Calculating A* paths off-thread...",
            this.golem.getId(), candidates.size()));

        this.isEvaluatingAsync = true;
        this.asyncStartTime = level.getGameTime();

        AsyncGolemPathfinder.evaluateThreatsAsync(this.golem, level, candidates, evaluated -> {
            this.isEvaluatingAsync = false;

            // If the golem was assigned another target in the meantime, ignore results
            LivingEntity current = this.golem.getTarget();
            if (current != null && current.isAlive() && !current.isRemoved()) {
                return;
            }

            ThreatCandidate best = null;
            double highestScore = 0.0;
            for (ThreatCandidate candidate : evaluated) {
                if (!candidate.getMob().isAlive() || candidate.getMob().isRemoved()) {
                    continue;
                }
                if (!candidate.isReachable()) {
                    continue;
                }
                if (this.isDrownedPathEndingInWater(level, candidate)) {
                    continue;
                }
                if (candidate.getFinalScore() > highestScore) {
                    highestScore = candidate.getFinalScore();
                    best = candidate;
                }
            }

            if (best != null && highestScore > 0.0) {
                sendChatDebug(level, String.format("§6[Golem #%d] §aSelected threat: §c%s§a (Urgency: %.1f | Path: %d nodes -> Score: %.1f)",
                    this.golem.getId(),
                    best.getMob().getName().getString(),
                    best.getBaseUrgency(),
                    best.getPathDistance(),
                    best.getFinalScore()));

                // Store for canUse() to pick up on the next tick — the goal lifecycle
                // will call start() which sets the target and kicks off navigation cleanly
                this.pendingTarget = best.getMob();
                this.pendingPath = best.getPath();
            } else if (!evaluated.isEmpty()) {
                sendChatDebug(level, String.format("§6[Golem #%d] §7Evaluated %d candidate(s), but none qualify as an active danger.",
                    this.golem.getId(), evaluated.size()));
            }
        });
    }

    private boolean isDrownedPathEndingInWater(ServerLevel level, ThreatCandidate candidate) {
        if (candidate.getMob().getType() != EntityType.DROWNED) {
            return false;
        }

        if (candidate.getMob().isInWater()) {
            return true;
        }

        Path path = candidate.getPath();
        if (path != null && path.getEndNode() != null) {
            Node endNode = path.getEndNode();
            BlockPos endPos = new BlockPos(endNode.x, endNode.y, endNode.z);
            if (level.getFluidState(endPos).is(FluidTags.WATER) || level.getFluidState(endPos.above()).is(FluidTags.WATER)) {
                return true;
            }
        }

        return false;
    }

    private boolean isInnateVillagerThreat(Mob mob) {
        return (mob instanceof Zombie)
            || mob.getType().is(EntityTypeTags.RAIDERS)
            || mob.getType() == EntityType.VEX
            || mob.getType() == EntityType.ZOGLIN;
    }

    private double calculateBaseUrgency(Mob enemy, List<Villager> villagers) {
        // 1. Is the enemy actively targeting a living villager?
        LivingEntity enemyTarget = enemy.getTarget();
        boolean isTargetingVillager = (enemyTarget instanceof Villager targetVillager && targetVillager.isAlive() && !targetVillager.isRemoved());

        // 2. Did this enemy damage any nearby villager recently (within 10s / 200 ticks)?
        boolean hurtRecent = false;
        double closestVillagerDist = Double.MAX_VALUE;
        boolean villagerPanicking = false;

        for (Villager villager : villagers) {
            double d = villager.distanceTo(enemy);
            if (d < closestVillagerDist) {
                closestVillagerDist = d;
                villagerPanicking = villager.getBrain().hasMemoryValue(MemoryModuleType.IS_PANICKING);
            }

            if (!hurtRecent && villager.getLastHurtByMob() == enemy && (villager.tickCount - villager.getLastHurtByMobTimestamp() <= 200)) {
                hurtRecent = true;
            }
        }

        boolean isCloseToVillager = (closestVillagerDist <= Config.DATA.villagerProximityThreatRadius);
        boolean isInnateThreat = this.isInnateVillagerThreat(enemy);

        // Menace check: Only engage if actively targeting/damaging a villager,
        // OR if it is an innate villager-hunter actually close to a villager.
        // Non-menace mobs (like spiders or neutral passers-by) will never be targeted.
        if (!isTargetingVillager && !hurtRecent && !(isCloseToVillager && isInnateThreat)) {
            return 0.0;
        }

        double urgency = 0.0;

        if (isTargetingVillager) {
            urgency += 100.0;
        }

        if (hurtRecent) {
            urgency += 80.0;
        }

        // Proximity to nearest villager (up to +50.0 points when within 10 blocks)
        if (isCloseToVillager) {
            urgency += Math.max(0.0, (Config.DATA.villagerProximityThreatRadius - closestVillagerDist) * 5.0);
        }

        // Zombie breaking a wooden door near any villager (+40.0)
        if (enemy instanceof Zombie zombie && zombie.canBreakDoors() && isCloseToVillager) {
            urgency += 40.0;
        }

        // Nearest villager is panicking (+25.0)
        if (villagerPanicking && isCloseToVillager) {
            urgency += 25.0;
        }

        return urgency;
    }
}
