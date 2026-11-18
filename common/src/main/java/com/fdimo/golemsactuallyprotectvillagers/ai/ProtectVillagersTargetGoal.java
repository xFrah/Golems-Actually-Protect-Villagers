package com.fdimo.golemsactuallyprotectvillagers.ai;

import com.fdimo.golemsactuallyprotectvillagers.Config;
import com.fdimo.golemsactuallyprotectvillagers.Constants;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;

public class ProtectVillagersTargetGoal extends TargetGoal {

    public static class RescueMission {
        private final Mob threat;
        private final Villager villager;

        public RescueMission(Mob threat, Villager villager) {
            this.threat = threat;
            this.villager = villager;
        }

        public Mob getThreat() {
            return this.threat;
        }

        public Villager getVillager() {
            return this.villager;
        }

        public boolean isValid() {
            return this.threat != null && this.threat.isAlive() && !this.threat.isRemoved();
        }
    }

    private final IronGolem golem;

    private RescueMission activeMission = null;

    private int evaluatingEnemyId = -1;
    private long asyncStartTime = 0;
    private long lastDisengageTime = 0;
    private String lastTargetName = null;
    private int lastUnreachableEnemyId = -1;
    private long lastUnreachableTime = 0;
    private Path lastValidatedPath = null;
    private long lastValidatedPathTime = 0;

    public ProtectVillagersTargetGoal(IronGolem golem) {
        super(golem, false, false); // mustSee = false to prevent door/wall blindness
        this.golem = golem;
        this.setFlags(EnumSet.of(Goal.Flag.TARGET));
    }

    public RescueMission getActiveMission() {
        return this.activeMission;
    }

    @Override
    public boolean canUse() {
        return this.activeMission != null && this.activeMission.isValid();
    }

    @Override
    public boolean canContinueToUse() {
        if (this.activeMission == null || !this.activeMission.isValid()) {
            return false;
        }

        LivingEntity currentTarget = this.golem.getTarget();
        if (currentTarget == null) {
            currentTarget = this.targetMob;
            if (currentTarget != null && currentTarget.isAlive() && !currentTarget.isRemoved()) {
                this.golem.setTarget(currentTarget);
            }
        }

        if (currentTarget == null || !currentTarget.isAlive() || currentTarget.isRemoved()) {
            return false;
        }

        return super.canContinueToUse();
    }

    @Override
    protected double getFollowDistance() {
        return Math.max(super.getFollowDistance(), Config.DATA.golemScanRadius + 10.0);
    }

    public boolean isRunning() {
        return this.activeMission != null && this.activeMission.isValid();
    }

    public Path getLastValidatedPath(long currentGameTime) {
        if (this.lastValidatedPath != null && (currentGameTime - this.lastValidatedPathTime <= 100)) {
            return this.lastValidatedPath;
        }
        return null;
    }

    public Villager getImperiledVillager() {
        return this.activeMission != null ? this.activeMission.getVillager() : null;
    }

    public void engage(RescueMission mission) {
        if (mission == null || !mission.isValid()) {
            return;
        }
        if (this.activeMission == mission) {
            return;
        }

        this.activeMission = mission;
        this.targetMob = mission.getThreat();
        this.golem.setTarget(this.targetMob);

        if (this.golem instanceof GolemSafeZoneHolder holder) {
            holder.golemsActuallyProtectVillagers$setRescueFollowRangeBoost(true);
        }

        if (this.targetMob != null) {
            this.lastTargetName = this.targetMob.getName().getString();
        }

        this.golem.getNavigation().moveTo(this.targetMob, 1.25);

        if (this.golem.level() instanceof ServerLevel serverLevel && this.targetMob != null) {
            DebugChat.send(serverLevel, "§6[Golem #%d] §cEngaging §4%s§c to protect %s!",
                    this.golem.getId(), this.lastTargetName, mission.getVillager().getName().getString());
        }

        if (Config.DATA.enableDebugLogs && this.targetMob != null) {
            Constants.LOG.info("[Golem Protection] Golem {} engaged threat {}",
                    this.golem.getId(), this.lastTargetName);
        }
    }

    @Override
    public void start() {
        if (this.activeMission != null && this.activeMission.isValid()) {
            this.targetMob = this.activeMission.getThreat();
            this.golem.setTarget(this.targetMob);
            if (this.golem instanceof GolemSafeZoneHolder holder) {
                holder.golemsActuallyProtectVillagers$setRescueFollowRangeBoost(true);
            }
            if (!this.golem.getNavigation().isInProgress()) {
                this.golem.getNavigation().moveTo(this.targetMob, 1.25);
            }
        }
        super.start();
    }

    @Override
    public void stop() {
        if (this.golem instanceof GolemSafeZoneHolder holder) {
            holder.golemsActuallyProtectVillagers$setRescueFollowRangeBoost(false);
        }
        if (this.golem.level() instanceof ServerLevel serverLevel) {
            this.lastDisengageTime = serverLevel.getGameTime();
            if (this.lastTargetName != null) {
                DebugChat.send(serverLevel, "§6[Golem #%d] §bDisengaged from %s (target defeated or lost).",
                        this.golem.getId(), this.lastTargetName);
                this.lastTargetName = null;
            }
        }
        this.activeMission = null;
        super.stop();
    }

    @Override
    public void tick() {
        super.tick();

        if (Config.DATA.enableQueueDebug && this.golem.level() instanceof ServerLevel serverLevel && this.golem.tickCount % 20 == 0) {
            AsyncGolemPathfinder.printMetrics(serverLevel);
        }
    }

    public void onDistressSignal(ServerLevel level, Villager villager, Mob enemy) {
        if (!this.golem.isAlive() || this.golem.isRemoved()) return;
        if (this.golem.level() != level) return;

        // 1. If already on an active rescue mission:
        if (this.activeMission != null && this.activeMission.isValid()) {
            Mob currentThreat = this.activeMission.getThreat();
            if (currentThreat == enemy) {
                DebugChat.send(level, "§6[Golem #%d] §7Ignoring SOS for §c%s§7: already actively pursuing this threat.",
                        this.golem.getId(), enemy.getName().getString());
                return;
            } else {
                DebugChat.send(level, "§6[Golem #%d] §7Ignoring SOS for §c%s§7: already on active rescue for %s.",
                        this.golem.getId(), enemy.getName().getString(),
                        currentThreat != null ? currentThreat.getName().getString() : "threat");
                return;
            }
        }

        // 2. If golem is targeting something from vanilla:
        LivingEntity currentTarget = this.golem.getTarget();
        if (currentTarget != null && currentTarget.isAlive() && !currentTarget.isRemoved()) {
            boolean inMeleeRange = this.golem.isWithinMeleeAttackRange(currentTarget);
            boolean movingOnValidPath = this.golem.getNavigation().isInProgress() && !this.golem.getNavigation().isStuck();
            if (inMeleeRange || movingOnValidPath) {
                DebugChat.send(level, "§6[Golem #%d] §7Ignoring SOS for §c%s§7: actively engaging nearby %s.",
                        this.golem.getId(), enemy.getName().getString(), currentTarget.getName().getString());
                return;
            }

            DebugChat.send(level, "§6[Golem #%d] §eOverriding idle/stuck target %s to respond to villager SOS for §c%s§e!",
                    this.golem.getId(), currentTarget.getName().getString(), enemy.getName().getString());
            this.golem.setTarget(null);
            this.golem.getNavigation().stop();
        }

        long gameTime = level.getGameTime();

        // 3. Debounce after disengaging (20 ticks / 1.0s)
        if (gameTime - this.lastDisengageTime < 20) {
            long remaining = 20 - (gameTime - this.lastDisengageTime);
            DebugChat.send(level, "§6[Golem #%d] §7Ignoring SOS for §c%s§7: recently disengaged (%d ticks cooldown).",
                    this.golem.getId(), enemy.getName().getString(), remaining);
            return;
        }

        // 4. Async evaluation in-flight check (timeout watchdog: 100 ticks / 5s)
        if (this.evaluatingEnemyId != -1) {
            if (gameTime - this.asyncStartTime > 100) {
                this.evaluatingEnemyId = -1;
            } else {
                DebugChat.send(level, "§6[Golem #%d] §7Ignoring SOS for §c%s§7: already calculating a path off-thread.",
                        this.golem.getId(), enemy.getName().getString());
                return;
            }
        }

        // 5. Unreachable cooldown (60 ticks / 3.0s)
        if (enemy.getId() == this.lastUnreachableEnemyId && (gameTime - this.lastUnreachableTime < 60)) {
            long remaining = 60 - (gameTime - this.lastUnreachableTime);
            DebugChat.send(level, "§6[Golem #%d] §7Ignoring SOS for §c%s§7: recently confirmed unreachable (%d ticks cooldown).",
                    this.golem.getId(), enemy.getName().getString(), remaining);
            return;
        }

        // 6. Can attack & range check
        if (!this.golem.canAttack(enemy)) {
            DebugChat.send(level, "§6[Golem #%d] §7Ignoring SOS for §c%s§7: cannot attack target entity.",
                    this.golem.getId(), enemy.getName().getString());
            return;
        }

        double maxRadius = (Config.DATA != null) ? Config.DATA.golemScanRadius : 48.0;
        if (this.golem.distanceTo(enemy) > maxRadius + 10.0) {
            DebugChat.send(level, "§6[Golem #%d] §7Ignoring SOS for §c%s§7: threat is out of range (%.1f > %.1f blocks).",
                    this.golem.getId(), enemy.getName().getString(), this.golem.distanceTo(enemy), maxRadius + 10.0);
            return;
        }

        // 7. Immediate engagement if already in melee attack range
        if (this.golem.isWithinMeleeAttackRange(enemy)) {
            RescueMission immediateMission = new RescueMission(enemy, villager);
            this.engage(immediateMission);
            DebugChat.send(level, "§6[Golem #%d] §aEngaging threat §c%s§a immediately without pathfinding (already in melee range).",
                    this.golem.getId(), enemy.getName().getString());
            return;
        }

        // 8. Legitimate threat check
        if (!this.isLegitimateThreat(enemy, villager)) {
            DebugChat.send(level, "§6[Golem #%d] §7Ignoring SOS for §c%s§7: threat is not an active danger to villagers.",
                    this.golem.getId(), enemy.getName().getString());
            return;
        }

        // 9. Off-thread reachability validation
        ThreatCandidate candidate = new ThreatCandidate(enemy, villager);
        this.evaluatingEnemyId = enemy.getId();
        this.asyncStartTime = gameTime;

        if (Config.DATA != null && Config.DATA.enableChatDebug) {
            DebugChat.send(level, "§6[Golem #%d] §eValidating threat §c%s§e reachability off-thread...",
                    this.golem.getId(), enemy.getName().getString());
        }

        AsyncGolemPathfinder.evaluateThreatAsync(this.golem, level, candidate, evaluated -> {
            this.evaluatingEnemyId = -1;

            if (evaluated != null && evaluated.getPath() != null) {
                this.lastValidatedPath = evaluated.getPath();
                this.lastValidatedPathTime = level.getGameTime();
            }

            if (this.activeMission != null && this.activeMission.isValid()) {
                DebugChat.send(level, "§6[Golem #%d] §7Discarded path result for §c%s§7: already adopted another mission.",
                        this.golem.getId(), enemy.getName().getString());
                return;
            }

            LivingEntity current = this.golem.getTarget();
            if (current != null && current.isAlive() && !current.isRemoved() && current != enemy) {
                DebugChat.send(level, "§6[Golem #%d] §7Discarded path result for §c%s§7: became engaged with %s while evaluating.",
                        this.golem.getId(), enemy.getName().getString(), current.getName().getString());
                return;
            }

            if (evaluated != null && evaluated.isReachable()) {
                DebugChat.send(level, "§6[Golem #%d] §aConfirmed reachable: §c%s§a (Path: %d nodes)",
                        this.golem.getId(),
                        evaluated.getMob().getName().getString(),
                        evaluated.getPathDistance());

                RescueMission evaluatedMission = new RescueMission(evaluated.getMob(), evaluated.getImperiledVillager());
                this.lastUnreachableEnemyId = -1;
                this.engage(evaluatedMission);
            } else {
                this.lastUnreachableEnemyId = enemy.getId();
                this.lastUnreachableTime = level.getGameTime();
                DebugChat.send(level, "§6[Golem #%d] §7Cannot reach threat §c%s§7 (unreachable / no valid path from current position).",
                        this.golem.getId(), enemy.getName().getString());
            }
        });
    }

    private boolean isInnateVillagerThreat(Mob mob) {
        return (mob instanceof Zombie)
                || mob.getType().is(EntityTypeTags.RAIDERS)
                || (mob instanceof Vex)
                || (mob instanceof Zoglin);
    }

    private boolean isLegitimateThreat(Mob enemy, Villager villager) {
        // 1. Is the enemy actively targeting a living villager?
        LivingEntity enemyTarget = enemy.getTarget();
        if (enemyTarget instanceof Villager targetVillager && targetVillager.isAlive() && !targetVillager.isRemoved()) {
            return true;
        }

        // 2. Did this enemy damage this villager recently (within 10s / 200 ticks)?
        if (villager.getLastHurtByMob() == enemy && (villager.tickCount - villager.getLastHurtByMobTimestamp() <= 200)) {
            return true;
        }

        // 3. Innate villager threat (zombies, raiders, vexes, zoglins)
        if (this.isInnateVillagerThreat(enemy)) {
            return true;
        }

        // 4. Any enemy close to the villager
        if (villager.distanceTo(enemy) <= Config.DATA.villagerProximityThreatRadius) {
            return true;
        }

        return false;
    }
}
