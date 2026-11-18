package com.fdimo.golemsactuallyprotectvillagers.mixin;

import com.fdimo.golemsactuallyprotectvillagers.Config;
import com.fdimo.golemsactuallyprotectvillagers.Constants;
import com.fdimo.golemsactuallyprotectvillagers.ai.AsyncGolemPathfinder;
import com.fdimo.golemsactuallyprotectvillagers.ai.GolemClimbHandler;
import com.fdimo.golemsactuallyprotectvillagers.ai.GolemCombatGuard;
import com.fdimo.golemsactuallyprotectvillagers.ai.GolemDebugRenderer;
import com.fdimo.golemsactuallyprotectvillagers.ai.GolemSafeZone;
import com.fdimo.golemsactuallyprotectvillagers.ai.GolemSafeZoneHolder;
import com.fdimo.golemsactuallyprotectvillagers.ai.ProtectVillagersTargetGoal;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.AbstractGolem;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IronGolem.class)
public abstract class MixinIronGolem extends AbstractGolem implements GolemSafeZoneHolder {

    @Unique
    private static final Identifier PURSUIT_SPEED_MODIFIER_ID = Identifier
            .fromNamespaceAndPath(Constants.MOD_ID, "mob_pursuit_speed_boost");

    @Unique
    private static final Identifier RESCUE_FOLLOW_RANGE_MODIFIER_ID = Identifier
            .fromNamespaceAndPath(Constants.MOD_ID, "rescue_follow_range_boost");

    @Unique
    private static final EntityDataAccessor<Optional<BlockPos>> MANTLE_TARGET_POS = SynchedEntityData
            .defineId(IronGolem.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);

    @Unique
    private static final EntityDataAccessor<Integer> MANTLE_TICKS = SynchedEntityData
            .defineId(IronGolem.class, EntityDataSerializers.INT);

    @Unique
    private static final EntityDataAccessor<Float> MANTLE_YAW = SynchedEntityData
            .defineId(IronGolem.class, EntityDataSerializers.FLOAT);

    @Unique
    private static final EntityDataAccessor<Boolean> IS_PURSUING = SynchedEntityData
            .defineId(IronGolem.class, EntityDataSerializers.BOOLEAN);

    @Unique
    private final GolemSafeZone safeZone = new GolemSafeZone(32);

    @Unique
    private final GolemClimbHandler climbHandler = new GolemClimbHandler(
            (IronGolem) (Object) this,
            MANTLE_TARGET_POS,
            MANTLE_TICKS,
            MANTLE_YAW);

    @Unique
    private ProtectVillagersTargetGoal protectVillagersGoal;

    @Unique
    private boolean canEnterHazard = false;

    protected MixinIronGolem(EntityType<? extends AbstractGolem> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(method = "handleEntityEvent", at = @At("HEAD"), cancellable = true)
    private void onHandleEntityEvent(byte id, CallbackInfo ci) {
        if (this.climbHandler.handleEntityEvent(id)) {
            ci.cancel();
        }
    }

    @Override
    public void setJumping(boolean jumping) {
        if (jumping && (this.climbHandler.isMantling() || this.climbHandler.isNearClimbableLedge())) {
            super.setJumping(false);
            return;
        }
        super.setJumping(jumping);
    }

    @Override
    public void jumpFromGround() {
        if (this.climbHandler.isMantling() || this.climbHandler.isNearClimbableLedge()) {
            return;
        }
        super.jumpFromGround();
    }

    @Override
    public void travel(Vec3 travelVector) {
        if (this.climbHandler.isMantling()) {
            this.setDeltaMovement(Vec3.ZERO);
            return;
        }
        super.travel(travelVector);
    }

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void onDefineSynchedData(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(MANTLE_TARGET_POS, Optional.empty());
        builder.define(MANTLE_TICKS, 0);
        builder.define(MANTLE_YAW, 0.0F);
        builder.define(IS_PURSUING, false);
    }

    @Inject(method = "registerGoals", at = @At("TAIL"))
    private void onRegisterGoals(CallbackInfo ci) {
        IronGolem golem = (IronGolem) (Object) this;
        // Priority 2: Protect villagers from active threats (evaluated statelessly on-demand)
        this.protectVillagersGoal = new ProtectVillagersTargetGoal(golem);
        this.targetSelector.addGoal(2, this.protectVillagersGoal);

        // Ensure follow range base value is vanilla (16.0); rescue follow range is applied dynamically
        AttributeInstance followRange = golem.getAttribute(Attributes.FOLLOW_RANGE);
        if (followRange != null && followRange.getBaseValue() > 16.0) {
            followRange.setBaseValue(16.0);
        }
    }

    @Override
    public ProtectVillagersTargetGoal golemsActuallyProtectVillagers$getProtectGoal() {
        return this.protectVillagersGoal;
    }

    @Override
    public GolemSafeZone golemsActuallyProtectVillagers$getSafeZone() {
        return this.safeZone;
    }

    @Override
    public boolean golemsActuallyProtectVillagers$isProtectingVillager() {
        return this.protectVillagersGoal != null && this.protectVillagersGoal.isRunning();
    }

    @Override
    public void golemsActuallyProtectVillagers$setRescueFollowRangeBoost(boolean enabled) {
        AttributeInstance followRangeAttr = this.getAttribute(Attributes.FOLLOW_RANGE);
        if (followRangeAttr == null) {
            return;
        }
        if (enabled) {
            double targetRange = (Config.DATA != null ? Config.DATA.golemScanRadius : 48.0) + 16.0;
            double boost = Math.max(0.0, targetRange - followRangeAttr.getBaseValue());
            AttributeModifier modifier = followRangeAttr.getModifier(RESCUE_FOLLOW_RANGE_MODIFIER_ID);
            if (modifier == null || modifier.amount() != boost) {
                followRangeAttr.addOrUpdateTransientModifier(new AttributeModifier(
                        RESCUE_FOLLOW_RANGE_MODIFIER_ID,
                        boost,
                        AttributeModifier.Operation.ADD_VALUE));
            }
        } else if (followRangeAttr.hasModifier(RESCUE_FOLLOW_RANGE_MODIFIER_ID)) {
            followRangeAttr.removeModifier(RESCUE_FOLLOW_RANGE_MODIFIER_ID);
        }
    }

    @Override
    public boolean golemsActuallyProtectVillagers$canEnterHazard() {
        return this.canEnterHazard;
    }

    @Override
    public int getMaxFallDistance() {
        if (this.getTarget() != null
                || this.golemsActuallyProtectVillagers$isProtectingVillager()
                || AsyncGolemPathfinder.EVALUATING_PROTECT_PATH.get()) {
            return (Config.DATA != null && Config.DATA.maxFallDistance > 0)
                    ? Config.DATA.maxFallDistance
                    : 32;
        }
        return super.getMaxFallDistance();
    }

    @Override
    public boolean golemsActuallyProtectVillagers$canClimbLedge() {
        if (Config.DATA != null && !Config.DATA.enableLedgeClimb) {
            return false;
        }
        return this.golemsActuallyProtectVillagers$isProtectingVillager()
                || AsyncGolemPathfinder.EVALUATING_PROTECT_PATH.get();
    }

    @Override
    public boolean golemsActuallyProtectVillagers$isMantling() {
        return this.climbHandler.isMantling();
    }

    @Override
    public boolean golemsActuallyProtectVillagers$isPursuingThreat() {
        return this.getEntityData().get(IS_PURSUING);
    }

    @Override
    public float golemsActuallyProtectVillagers$getMantleArmAngle(float partialTick) {
        return this.climbHandler.getMantleArmAngle(partialTick);
    }

    @Override
    public void golemsActuallyProtectVillagers$resetLerpSteps() {
        if (this.getInterpolation() != null) {
            this.getInterpolation().cancel();
        }
        this.lerpHeadSteps = 0;
    }

    @Override
    protected AABB getAttackBoundingBox(double range) {
        AABB aabb = super.getAttackBoundingBox(range);
        // Reach downwards 0.25 blocks below feet and +0.25 blocks horizontally beyond vanilla reach
        return new AABB(
                aabb.minX - 0.25,
                aabb.minY - 0.25,
                aabb.minZ - 0.25,
                aabb.maxX + 0.25,
                aabb.maxY,
                aabb.maxZ + 0.25);
    }

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void onGolemAiStepHead(CallbackInfo ci) {
        if (this.level().isClientSide()) {
            this.climbHandler.tickClient();
        }
    }

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void onGolemAiStep(CallbackInfo ci) {
        if (this.level().isClientSide()) {
            return;
        }

        if (this.level() instanceof ServerLevel serverLevel) {
            IronGolem golem = (IronGolem) (Object) this;

            // 0. Ledge mantling / climbing update (smooth arc translation)
            if (this.climbHandler.tickServer(serverLevel, this.safeZone)) {
                return;
            }

            this.climbHandler.tickPostMantleGrace();

            // 1. Periodic async safe zone update (staggered across ticks by golem ID)
            if ((golem.tickCount + Math.floorMod(golem.getId(), 10)) % 10 == 0) {
                this.safeZone.requestAsyncUpdate(golem, serverLevel);
            }

            // 2. Debug visualizations (pitfall particles & active path)
            if (Config.DATA.showPitFallDebug && (golem.tickCount + Math.floorMod(golem.getId(), 10)) % 10 == 0) {
                GolemDebugRenderer.renderPitFallDebug(golem, serverLevel, this.safeZone);
            }
            if (Config.DATA.showPathDebug && golem.tickCount % 2 == 0) {
                GolemDebugRenderer.renderPathDebug(golem, serverLevel, this.protectVillagersGoal);
            }

            // 3. Trapped villager evaluation
            boolean isProtecting = this.golemsActuallyProtectVillagers$isProtectingVillager();
            LivingEntity target = golem.getTarget();
            this.canEnterHazard = isProtecting && GolemCombatGuard.isVillagerTrappedWithTarget(
                    this.safeZone, this.protectVillagersGoal, target);

            // 4. Combat safety guards (emergency lava guard, hazard disengage, rim attack anchor)
            GolemCombatGuard.tickSafetyGuards(golem, serverLevel, this.safeZone, this.climbHandler, this.canEnterHazard);

            // 5. Pursuit speed modifier: apply speed boost when pursuing hostile mobs (not players)
            AttributeInstance speedAttr = golem.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speedAttr != null) {
                if (target instanceof Mob && !(target instanceof Player) && target.isAlive()
                        && !target.isRemoved() && Config.DATA.mobPursuitSpeedModifier > 0.0) {
                    AttributeModifier modifier = speedAttr.getModifier(PURSUIT_SPEED_MODIFIER_ID);
                    if (modifier == null || modifier.amount() != Config.DATA.mobPursuitSpeedModifier) {
                        speedAttr.addOrUpdateTransientModifier(new AttributeModifier(
                                PURSUIT_SPEED_MODIFIER_ID,
                                Config.DATA.mobPursuitSpeedModifier,
                                AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
                    }
                } else if (speedAttr.hasModifier(PURSUIT_SPEED_MODIFIER_ID)) {
                    speedAttr.removeModifier(PURSUIT_SPEED_MODIFIER_ID);
                }
            }

            // 6. Villager rescue follow range modifier: ensure follow range is boosted ONLY while actively rescuing
            this.golemsActuallyProtectVillagers$setRescueFollowRangeBoost(isProtecting);

            // 7. Update pursuit status for client-side emissive red eyes rendering
            boolean isPursuing = isProtecting || (target != null && target.isAlive() && !target.isRemoved());
            if (this.getEntityData().get(IS_PURSUING) != isPursuing) {
                this.getEntityData().set(IS_PURSUING, isPursuing);
            }
        }
    }
}
