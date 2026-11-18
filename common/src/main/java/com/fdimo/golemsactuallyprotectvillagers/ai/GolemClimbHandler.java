package com.fdimo.golemsactuallyprotectvillagers.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Encapsulates the state machine, physics, timing, and synchronization
 * for Iron Golem ledge climbing and mantling.
 */
public class GolemClimbHandler {

    public static final byte EVENT_MANTLE_START = 68;

    private final IronGolem golem;
    private final EntityDataAccessor<Optional<BlockPos>> mantleTargetPosAccessor;
    private final EntityDataAccessor<Integer> mantleTicksAccessor;
    private final EntityDataAccessor<Float> mantleYawAccessor;

    private int mantleTicks = 0;
    private BlockPos mantleTargetPos = null;
    private Vec3 mantleStartPos = null;
    private int postMantleGraceTicks = 0;

    private BlockPos cachedClimbableLedge = null;
    private int lastClimbCheckTick = -1;

    private boolean cachedApproachingLedge = false;
    private int lastApproachCheckTick = -1;


    public GolemClimbHandler(
            IronGolem golem,
            EntityDataAccessor<Optional<BlockPos>> mantleTargetPosAccessor,
            EntityDataAccessor<Integer> mantleTicksAccessor,
            EntityDataAccessor<Float> mantleYawAccessor) {
        this.golem = golem;
        this.mantleTargetPosAccessor = mantleTargetPosAccessor;
        this.mantleTicksAccessor = mantleTicksAccessor;
        this.mantleYawAccessor = mantleYawAccessor;
    }

    public boolean handleEntityEvent(byte id) {
        if (id == EVENT_MANTLE_START) {
            this.mantleTicks = 1;
            this.mantleStartPos = this.golem.position();
            this.golem.playSound(SoundEvents.IRON_GOLEM_REPAIR, 0.8F, 1.3F);
            return true;
        }
        return false;
    }

    public boolean isMantling() {
        return this.mantleTicks > 0 || this.golem.getEntityData().get(this.mantleTicksAccessor) > 0;
    }

    public int getMantleTicks() {
        if (this.mantleTicks > 0) {
            return this.mantleTicks;
        }
        return this.golem.getEntityData().get(this.mantleTicksAccessor);
    }

    public float getMantleArmAngle(float partialTick) {
        int ticks = this.getMantleTicks();
        if (ticks <= 0) {
            return 0.0F;
        }
        double currentY = Mth.lerp((double) partialTick, this.golem.yo, this.golem.getY());
        BlockPos ledgePos = this.golem.getEntityData().get(this.mantleTargetPosAccessor).orElse(null);
        return GolemClimbController.calculateMantleArmAngle(
                MantleTiming.get(), ticks, partialTick, this.mantleStartPos, ledgePos, this.golem.level(), currentY);
    }

    public boolean isNearClimbableLedge() {
        return this.getOrFindClimbableLedge() != null || this.isApproachingClimbableLedge();
    }

    public BlockPos getOrFindClimbableLedge() {
        if (this.lastClimbCheckTick != this.golem.tickCount) {
            this.lastClimbCheckTick = this.golem.tickCount;
            this.cachedClimbableLedge = GolemClimbController.findNearbyClimbableLedge(this.golem);
        }
        return this.cachedClimbableLedge;
    }

    public boolean isApproachingClimbableLedge() {
        if (this.lastApproachCheckTick != this.golem.tickCount) {
            this.lastApproachCheckTick = this.golem.tickCount;
            this.cachedApproachingLedge = GolemClimbController.isApproachingClimbableLedge(this.golem);
        }
        return this.cachedApproachingLedge;
    }

    public boolean isInPostMantleGrace() {
        return this.postMantleGraceTicks > 0;
    }

    public void tickPostMantleGrace() {
        if (this.postMantleGraceTicks > 0) {
            this.postMantleGraceTicks--;
        }
    }

    /**
     * Ticks client-side mantling animation, positioning, and orientation.
     */
    public void tickClient() {
        if (this.mantleTicks > 0) {
            this.mantleTicks++;
            MantleTiming timing = MantleTiming.get();
            if (this.mantleTicks > timing.totalTicks) {
                this.mantleTicks = 0;
                this.mantleStartPos = null;
                return;
            }
            this.updateClientMantlePosition();
        }
    }

    public void updateClientMantlePosition() {
        if (this.mantleStartPos == null) {
            this.mantleStartPos = this.golem.position();
        }
        Optional<BlockPos> opt = this.golem.getEntityData().get(this.mantleTargetPosAccessor);
        if (opt.isEmpty()) {
            return;
        }
        BlockPos ledgePos = opt.get();
        float targetYaw = this.golem.getEntityData().get(this.mantleYawAccessor);
        if (targetYaw == 0.0F) {
            targetYaw = GolemClimbController.calculateFacingYaw(this.mantleStartPos, ledgePos);
        }
        this.golem.setYRot(targetYaw);
        this.golem.setYBodyRot(targetYaw);
        this.golem.setYHeadRot(targetYaw);
        this.golem.yRotO = targetYaw;
        this.golem.yBodyRotO = targetYaw;
        this.golem.yHeadRotO = targetYaw;

        if (this.golem instanceof GolemSafeZoneHolder holder) {
            holder.golemsActuallyProtectVillagers$resetLerpSteps();
        }

        Vec3 pos = GolemClimbController.calculateMantlePosition(
                MantleTiming.get(), this.mantleTicks, this.mantleStartPos, ledgePos, this.golem.level(), targetYaw);
        this.golem.setPos(pos.x, pos.y, pos.z);
        this.golem.walkAnimation.setSpeed(0.0F);
    }

    /**
     * Ticks server-side mantling state machine.
     *
     * @return true if the golem is actively executing a mantle and AI travel should
     *         be bypassed.
     */
    public boolean tickServer(ServerLevel serverLevel, GolemSafeZone safeZone) {
        // 1. If currently mantling, advance progress and update position
        if (this.mantleTicks > 0) {
            this.mantleTicks++;
            MantleTiming timing = MantleTiming.get();

            if (this.mantleTargetPos != null && this.mantleStartPos != null) {
                BlockPos ledgePos = this.mantleTargetPos;
                float targetYaw = this.golem.getEntityData().get(this.mantleYawAccessor);
                if (targetYaw == 0.0F) {
                    targetYaw = GolemClimbController.calculateFacingYaw(this.mantleStartPos, ledgePos);
                }
                this.golem.setYRot(targetYaw);
                this.golem.setYBodyRot(targetYaw);
                this.golem.setYHeadRot(targetYaw);
                this.golem.yRotO = targetYaw;
                this.golem.yBodyRotO = targetYaw;
                this.golem.yHeadRotO = targetYaw;

                Vec3 targetPos = GolemClimbController.calculateMantlePosition(
                        timing, this.mantleTicks, this.mantleStartPos, ledgePos, serverLevel, targetYaw);

                // Lock look control to the obstacle to prevent vanilla goals from pulling yaw
                // towards off-axis mobs
                this.golem.getLookControl().setLookAt(targetPos.x, targetPos.y + 1.5, targetPos.z, 360.0F, 360.0F);
                this.golem.walkAnimation.setSpeed(0.0F);

                if (this.mantleTicks <= timing.t1End) {
                    // Stage 1: Step smoothly flush against the wall face
                    this.golem.setPos(targetPos.x, targetPos.y, targetPos.z);
                    this.golem.setDeltaMovement(Vec3.ZERO);
                    this.golem.setJumping(false);
                    this.golem.getEntityData().set(this.mantleTicksAccessor, this.mantleTicks);
                } else if (this.mantleTicks <= timing.t2End) {
                    // Stage 2: Arm Wind-up behind back -> overhead -> slam onto wall top
                    this.golem.setPos(targetPos.x, targetPos.y, targetPos.z);
                    this.golem.setDeltaMovement(Vec3.ZERO);
                    this.golem.setJumping(false);
                    this.golem.resetFallDistance();
                    this.golem.getEntityData().set(this.mantleTicksAccessor, this.mantleTicks);

                    // At tick t2End, arms slam onto the top of the wall
                    if (this.mantleTicks == timing.t2End) {
                        this.golem.playSound(SoundEvents.IRON_GOLEM_REPAIR, 0.9F, 1.2F);
                        this.golem.playSound(SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, 0.4F, 1.5F);
                        float rad = targetYaw * (float) (Math.PI / 180.0);
                        double fx = -Mth.sin(rad);
                        double fz = Mth.cos(rad);
                        double hitX = (ledgePos.getX() + 0.5 - fx * 0.5) + fx * 0.1;
                        double hitZ = (ledgePos.getZ() + 0.5 - fz * 0.5) + fz * 0.1;
                        serverLevel.sendParticles(
                                new BlockParticleOption(ParticleTypes.BLOCK, serverLevel.getBlockState(ledgePos)),
                                hitX, targetPos.y + 0.05, hitZ,
                                12, 0.2, 0.05, 0.2, 0.05);
                    }
                } else if (this.mantleTicks <= timing.totalTicks) {
                    // Stage 3 & Settle: Smooth circular vault and settle onto the obstacle top
                    this.golem.setPos(targetPos.x, targetPos.y, targetPos.z);
                    this.golem.setDeltaMovement(Vec3.ZERO);
                    this.golem.resetFallDistance();
                    this.golem.getEntityData().set(this.mantleTicksAccessor, this.mantleTicks);
                } else {
                    // Mantle complete: placed securely on top of the block
                    this.golem.setPos(targetPos.x, targetPos.y, targetPos.z);
                    this.golem.resetFallDistance();

                    this.resetMantleState();
                    this.golem.playSound(SoundEvents.IRON_GOLEM_STEP, 1.0F, 0.8F);

                    this.postMantleGraceTicks = 40;
                    this.cachedClimbableLedge = null;
                    this.lastClimbCheckTick = -1;
                    this.cachedApproachingLedge = false;
                    this.lastApproachCheckTick = -1;
                    safeZone.requestForcedAsyncUpdate(this.golem, serverLevel);

                    Path activePath = this.golem.getNavigation().getPath();

                    Vec3 nextWaypoint = null;
                    if (activePath != null && !activePath.isDone()) {
                        int ledgeIndex = GolemClimbController.findLedgeNodeIndex(activePath, ledgePos);
                        if (ledgeIndex >= 0) {
                            int targetIndex = Math.max(activePath.getNextNodeIndex(), ledgeIndex + 1);
                            activePath.setNextNodeIndex(Math.min(activePath.getNodeCount(), targetIndex));
                        } else {
                            activePath.advance();
                        }
                        this.golem.getNavigation().moveTo(activePath, 1.25);
                        if (!activePath.isDone()) {
                            nextWaypoint = activePath.getNextEntityPos(this.golem);
                        }
                    } else {
                        LivingEntity currentTarget = this.golem.getTarget();
                        if (currentTarget != null && currentTarget.isAlive() && !currentTarget.isRemoved()) {
                            this.golem.getNavigation().moveTo(currentTarget, 1.25);
                            nextWaypoint = currentTarget.position();
                        }
                    }

                    // Seamless stride transition: immediately prime MoveControl and impart forward
                    // momentum
                    // towards the next waypoint so the golem does not freeze into a dead standstill
                    double moveX;
                    double moveZ;
                    if (nextWaypoint != null) {
                        this.golem.getMoveControl().setWantedPosition(nextWaypoint.x, nextWaypoint.y, nextWaypoint.z,
                                1.25);
                        double dx = nextWaypoint.x - targetPos.x;
                        double dz = nextWaypoint.z - targetPos.z;
                        double distSq = dx * dx + dz * dz;
                        if (distSq > 0.001) {
                            double invDist = 1.0 / Math.sqrt(distSq);
                            moveX = dx * invDist;
                            moveZ = dz * invDist;
                        } else {
                            float rad = targetYaw * (float) (Math.PI / 180.0);
                            moveX = -Mth.sin(rad);
                            moveZ = Mth.cos(rad);
                        }
                    } else {
                        float rad = targetYaw * (float) (Math.PI / 180.0);
                        moveX = -Mth.sin(rad);
                        moveZ = Mth.cos(rad);
                    }

                    double forwardSpeed = 0.25;
                    this.golem.setDeltaMovement(moveX * forwardSpeed, this.golem.getDeltaMovement().y,
                            moveZ * forwardSpeed);
                    return false; // Mantle finished! Allow normal AI and navigation steps to run immediately on
                                  // this tick
                }
            } else {
                this.resetMantleState();
            }
            return true;
        }

        // 2. Check if golem should start mantling a ledge
        BlockPos targetLedge = this.getOrFindClimbableLedge();
        if (targetLedge != null) {
            this.mantleTargetPos = targetLedge;
            this.mantleStartPos = this.golem.position();
            this.mantleTicks = 1;
            this.golem.getEntityData().set(this.mantleTargetPosAccessor, Optional.of(targetLedge));
            this.golem.getEntityData().set(this.mantleTicksAccessor, 1);
            this.golem.setJumping(false);
            this.golem.setDeltaMovement(Vec3.ZERO);

            float targetYaw = GolemClimbController.calculateFacingYaw(this.mantleStartPos, targetLedge);
            this.golem.getEntityData().set(this.mantleYawAccessor, targetYaw);

            this.golem.setYRot(targetYaw);
            this.golem.setYBodyRot(targetYaw);
            this.golem.setYHeadRot(targetYaw);
            this.golem.yRotO = targetYaw;
            this.golem.yBodyRotO = targetYaw;
            this.golem.yHeadRotO = targetYaw;

            serverLevel.broadcastEntityEvent(this.golem, EVENT_MANTLE_START);
            this.golem.playSound(SoundEvents.IRON_GOLEM_STEP, 0.8F, 1.0F);

            return true;
        }

        return false;
    }

    private void resetMantleState() {
        this.mantleTicks = 0;
        this.mantleTargetPos = null;
        this.mantleStartPos = null;
        this.golem.getEntityData().set(this.mantleTargetPosAccessor, Optional.empty());
        this.golem.getEntityData().set(this.mantleTicksAccessor, 0);
        this.golem.getEntityData().set(this.mantleYawAccessor, 0.0F);
    }
}
