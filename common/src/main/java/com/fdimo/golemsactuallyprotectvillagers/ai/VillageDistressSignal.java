package com.fdimo.golemsactuallyprotectvillagers.ai;

import com.fdimo.golemsactuallyprotectvillagers.Config;
import com.fdimo.golemsactuallyprotectvillagers.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class VillageDistressSignal {

    public static void onVillagerPanic(ServerLevel level, Villager villager, long gameTime) {
        if (Config.DATA != null && !Config.DATA.enableVillagerDistressSignals) {
            return;
        }
        if (villager == null || !villager.isAlive() || villager.isRemoved()) {
            return;
        }

        // Early throttle check: do not perform any entity lookups or brain queries if within interval
        int interval = (Config.DATA != null) ? Config.DATA.villagerDistressBroadcastIntervalTicks : 40;
        if (villager instanceof VillagerDistressEmitter emitter) {
            long lastTime = emitter.golemsActuallyProtectVillagers$getLastDistressTime();
            if (gameTime - lastTime < interval) {
                return;
            }
            // Mark time immediately so we don't query every tick while async evaluation is in flight
            emitter.golemsActuallyProtectVillagers$setLastDistressTime(gameTime);
        }

        Mob threat = findThreat(villager);
        if (threat == null) {
            return;
        }

        // 1. Direct imminent threat: if already in melee reach, damaged recently, or flying mob, broadcast immediately!
        if (isDirectImminentThreat(villager, threat)) {
            broadcast(level, villager, threat, true);
            return;
        }

        // 2. Off-thread Reachability Verification: verify the threat can actually pathfind to the villager
        // before alerting the village golems. Prevents false alarms from mobs trapped in pits, caves, or behind walls.
        BlockPos vPos = villager.blockPosition();
        BlockPos aPos = threat.blockPosition();

        int minX = Math.min(vPos.getX(), aPos.getX()) - 8;
        int maxX = Math.max(vPos.getX(), aPos.getX()) + 8;
        int minY = Math.min(vPos.getY(), aPos.getY()) - 8;
        int maxY = Math.max(vPos.getY(), aPos.getY()) + 8;
        int minZ = Math.min(vPos.getZ(), aPos.getZ()) - 8;
        int maxZ = Math.max(vPos.getZ(), aPos.getZ()) + 8;

        PathNavigationRegion region = new PathNavigationRegion(
            level,
            new BlockPos(minX, minY, minZ),
            new BlockPos(maxX, maxY, maxZ)
        );

        CompletableFuture.runAsync(() -> {
            try {
                WalkNodeEvaluator evaluator = new WalkNodeEvaluator();
                evaluator.setCanPassDoors(true);
                evaluator.setCanOpenDoors(true);
                PathFinder pathFinder = new PathFinder(evaluator, 150);

                Path path = pathFinder.findPath(region, threat, Set.of(vPos), 24.0F, 2, 1.0F);
                boolean canReach = false;
                if (path != null) {
                    if (path.canReach()) {
                        canReach = true;
                    } else if (path.getEndNode() != null) {
                        Node endNode = path.getEndNode();
                        BlockPos endPos = endNode.asBlockPos();
                        int dy = Math.abs(endPos.getY() - vPos.getY());
                        double distSqr = endPos.distSqr(vPos);
                        // Threat can only attack if on adjacent ground (dy <= 1) within melee reach (~2.5 blocks)
                        if (dy <= 1 && distSqr <= 6.25) {
                            canReach = true;
                        }
                    }
                }

                final boolean reachable = canReach;
                level.getServer().execute(() -> {
                    if (!villager.isAlive() || villager.isRemoved() || !threat.isAlive() || threat.isRemoved()) {
                        return;
                    }
                    if (reachable) {
                        broadcast(level, villager, threat, true);
                    } else {
                        if (villager instanceof VillagerDistressEmitter emitter) {
                            emitter.golemsActuallyProtectVillagers$setLastDistressTime(level.getGameTime());
                            emitter.golemsActuallyProtectVillagers$setLastAttackerId(threat.getId());
                        }
                        sendChatDebug(level, String.format(
                            "§a[Villager #%d] §7Suppressed SOS: threat §c%s§7 at [%d, %d, %d] cannot pathfind to me (trapped/obstructed).",
                            villager.getId(), threat.getName().getString(), aPos.getX(), aPos.getY(), aPos.getZ()
                        ));
                    }
                });
            } catch (Exception e) {
                Constants.LOG.error("Failed to evaluate threat path to villager", e);
            }
        }, Constants.ASYNC_POOL);
    }

    private static boolean isDirectImminentThreat(Villager villager, Mob attacker) {
        if (villager.isPassenger() && attacker.isPassenger() && villager.getRootVehicle() == attacker.getRootVehicle()) {
            return true;
        }
        if (attacker.isWithinMeleeAttackRange(villager)) {
            return true;
        }
        if (villager.getLastHurtByMob() == attacker && (villager.tickCount - villager.getLastHurtByMobTimestamp() <= 200)) {
            return true;
        }
        if (villager.getBrain().getMemory(MemoryModuleType.HURT_BY_ENTITY).orElse(null) == attacker) {
            return true;
        }
        return attacker instanceof Vex || attacker instanceof Phantom || attacker.isNoGravity();
    }

    public static void broadcast(ServerLevel level, Villager villager, Mob attacker) {
        broadcast(level, villager, attacker, false);
    }

    public static void broadcast(ServerLevel level, Villager villager, Mob attacker, boolean bypassThrottle) {
        if (Config.DATA != null && !Config.DATA.enableVillagerDistressSignals) {
            return;
        }
        if (level == null || villager == null || !villager.isAlive() || villager.isRemoved()) {
            return;
        }
        if (attacker == null || !attacker.isAlive() || attacker.isRemoved()) {
            return;
        }
        if (attacker instanceof IronGolem || attacker instanceof Creeper) {
            return;
        }

        long gameTime = level.getGameTime();
        int interval = (Config.DATA != null) ? Config.DATA.villagerDistressBroadcastIntervalTicks : 40;

        // Rate limiting per villager for the same threat
        if (!bypassThrottle && villager instanceof VillagerDistressEmitter emitter) {
            long lastTime = emitter.golemsActuallyProtectVillagers$getLastDistressTime();
            int lastAttacker = emitter.golemsActuallyProtectVillagers$getLastAttackerId();

            if (attacker.getId() == lastAttacker && (gameTime - lastTime < interval)) {
                return;
            }
        }

        if (villager instanceof VillagerDistressEmitter emitter) {
            emitter.golemsActuallyProtectVillagers$setLastDistressTime(gameTime);
            emitter.golemsActuallyProtectVillagers$setLastAttackerId(attacker.getId());
        }

        double radius = (Config.DATA != null) ? Config.DATA.golemScanRadius : 48.0;
        AABB scanArea = villager.getBoundingBox().inflate(radius);
        List<IronGolem> golems = level.getEntitiesOfClass(IronGolem.class, scanArea, IronGolem::isAlive);

        BlockPos pos = villager.blockPosition();
        if (golems.isEmpty()) {
            sendChatDebug(level, String.format(
                "§a[Villager #%d] §eSent SOS for §c%s§e at [%d, %d, %d] §7(no golems in range).",
                villager.getId(), attacker.getName().getString(), pos.getX(), pos.getY(), pos.getZ()
            ));
            return;
        }

        sendChatDebug(level, String.format(
            "§a[Villager #%d] §eSent SOS for §c%s§e at [%d, %d, %d] §7(%d golem(s) in range).",
            villager.getId(), attacker.getName().getString(), pos.getX(), pos.getY(), pos.getZ(), golems.size()
        ));

        for (IronGolem golem : golems) {
            if (golem instanceof GolemSafeZoneHolder holder) {
                ProtectVillagersTargetGoal goal = holder.golemsActuallyProtectVillagers$getProtectGoal();
                if (goal != null) {
                    goal.onDistressSignal(level, villager, attacker);
                }
            }
        }
    }

    private static void sendChatDebug(ServerLevel serverLevel, String message) {
        if (Config.DATA == null || !Config.DATA.enableChatDebug) {
            return;
        }
        Component comp = Component.literal(message);
        for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
            player.sendSystemMessage(comp);
        }
    }

    public static Mob findThreat(Villager villager) {
        if (villager == null || !villager.isAlive() || villager.isRemoved()) {
            return null;
        }

        // 1. Direct attacker that hurt the villager (Brain memory HURT_BY_ENTITY)
        LivingEntity hurtBy = villager.getBrain().getMemory(MemoryModuleType.HURT_BY_ENTITY).orElse(null);
        if (isValidThreat(hurtBy)) {
            return (Mob) hurtBy;
        }

        // 2. Direct attacker that hurt the villager (Combat tracker, within 10s / 200 ticks)
        LivingEntity lastHurt = villager.getLastHurtByMob();
        if (isValidThreat(lastHurt) && (villager.tickCount - villager.getLastHurtByMobTimestamp() <= 200)) {
            return (Mob) lastHurt;
        }

        // 3. Nearest hostile detected by VillagerHostilesSensor
        LivingEntity nearestHostile = villager.getBrain().getMemory(MemoryModuleType.NEAREST_HOSTILE).orElse(null);
        if (isValidThreat(nearestHostile)) {
            return (Mob) nearestHostile;
        }

        // 4. Proximity fallback: enemy within 10 blocks actively targeting this villager
        Level level = villager.level();
        AABB checkArea = villager.getBoundingBox().inflate(10.0);
        List<Mob> nearbyEnemies = level.getEntitiesOfClass(Mob.class, checkArea, mob ->
            isValidThreat(mob) && mob.getTarget() == villager
        );
        if (!nearbyEnemies.isEmpty()) {
            return nearbyEnemies.get(0);
        }

        return null;
    }

    private static boolean isValidThreat(LivingEntity entity) {
        return entity instanceof Mob mob
            && mob.isAlive()
            && !mob.isRemoved()
            && !(mob instanceof IronGolem)
            && !(mob instanceof Creeper);
    }
}

