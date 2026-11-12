package com.fdimo.golemsactuallyprotectvillagers.mixin;

import com.fdimo.golemsactuallyprotectvillagers.Config;
import com.fdimo.golemsactuallyprotectvillagers.Constants;
import com.fdimo.golemsactuallyprotectvillagers.ai.AsyncGolemPathfinder;
import com.fdimo.golemsactuallyprotectvillagers.ai.GolemSafeZone;
import com.fdimo.golemsactuallyprotectvillagers.ai.GolemSafeZoneHolder;
import com.fdimo.golemsactuallyprotectvillagers.ai.ProtectVillagersTargetGoal;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import org.joml.Vector3f;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.AbstractGolem;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
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
    private static final ResourceLocation PURSUIT_SPEED_MODIFIER_ID = ResourceLocation
            .fromNamespaceAndPath(Constants.MOD_ID, "mob_pursuit_speed_boost");

    @Unique
    private static final ResourceLocation RESCUE_FOLLOW_RANGE_MODIFIER_ID = ResourceLocation
            .fromNamespaceAndPath(Constants.MOD_ID, "rescue_follow_range_boost");

    @Unique
    private static final DustParticleOptions BLUE_PATH_DUST = new DustParticleOptions(
            new Vector3f(0.0F, 0.65F, 1.0F), 1.2F);

    @Unique
    private static final DustParticleOptions YELLOW_PATH_DUST = new DustParticleOptions(
            new Vector3f(1.0F, 0.9F, 0.0F), 1.2F);

    @Unique
    private final GolemSafeZone safeZone = new GolemSafeZone(32);

    @Unique
    private ProtectVillagersTargetGoal protectVillagersGoal;

    protected MixinIronGolem(EntityType<? extends AbstractGolem> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(method = "registerGoals", at = @At("TAIL"))
    private void onRegisterGoals(CallbackInfo ci) {
        IronGolem golem = (IronGolem) (Object) this;
        // Priority 2: Protect villagers from active threats (evaluated statelessly on-demand)
        this.protectVillagersGoal = new ProtectVillagersTargetGoal(golem);
        this.targetSelector.addGoal(2, this.protectVillagersGoal);

        // Ensure follow range base value is vanilla (16.0); rescue follow range is applied dynamically as a transient modifier
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
        if (followRangeAttr == null) return;
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
    public int getMaxFallDistance() {
        if (this.getTarget() != null
            || this.golemsActuallyProtectVillagers$isProtectingVillager()
            || AsyncGolemPathfinder.EVALUATING_PROTECT_PATH.get()) {
            return (Config.DATA != null && Config.DATA.maxFallDistance > 0)
                ? Config.DATA.maxFallDistance : 32;
        }
        return super.getMaxFallDistance();
    }

    @Override
    protected AABB getAttackBoundingBox() {
        AABB aabb = super.getAttackBoundingBox();
        // Slight reach buffer:
        // - Reach downwards 0.25 blocks below feet (vanilla is 0.0)
        // - Slight horizontal buffer of +0.25 blocks beyond vanilla reach
        return new AABB(
            aabb.minX - 0.25,
            aabb.minY - 0.25,
            aabb.minZ - 0.25,
            aabb.maxX + 0.25,
            aabb.maxY,
            aabb.maxZ + 0.25
        );
    }

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void onGolemAiStep(CallbackInfo ci) {
        if (!this.level().isClientSide && this.level() instanceof ServerLevel serverLevel) {
            IronGolem golem = (IronGolem) (Object) this;

            // 1. Periodic async safe zone update (sliding window, checks movement and area block changes)
            if (golem.tickCount % 10 == 0) {
                this.safeZone.requestAsyncUpdate(golem, serverLevel);
            }

            // 2. Pitfall / unrecoverable hazard debug particles (showPitFallDebug)
            if (Config.DATA.showPitFallDebug && golem.tickCount % 10 == 0) {
                this.renderPitFallDebug(golem, serverLevel);
            }

            // 3. Active path debug particles (showPathDebug)
            if (Config.DATA.showPathDebug && golem.tickCount % 2 == 0) {
                this.renderPathDebug(golem, serverLevel);
            }

            LivingEntity target = golem.getTarget();
            BlockPos targetPos = (target != null) ? target.blockPosition() : null;

            // 3. Emergency Lava Guard: abort navigation if current path steps into dynamic lava or target is in lava
            Path currentPath = golem.getNavigation().getPath();
            if (currentPath != null && !currentPath.isDone()) {
                int nextIndex = currentPath.getNextNodeIndex();
                if (nextIndex < currentPath.getNodeCount()) {
                    Node nextNode = currentPath.getNode(nextIndex);
                    BlockPos nextPos = nextNode.asBlockPos();
                    if (serverLevel.getFluidState(nextPos).is(FluidTags.LAVA)) {
                        golem.getNavigation().stop();
                        golem.setTarget(null);
                    }
                }
            }

            if (target != null && targetPos != null) {
                if (serverLevel.getFluidState(targetPos).is(FluidTags.LAVA) || targetPos.getY() <= serverLevel.getMinBuildHeight()) {
                    golem.setTarget(null);
                    golem.getNavigation().stop();
                }
            }

            // 4. Combat pursuit guard: if target is in an unrecoverable hazard (pit, ravine, deep water)
            // and golem is NOT actively protecting a villager:
            boolean isProtecting = this.golemsActuallyProtectVillagers$isProtectingVillager();
            if (target != null && !isProtecting) {
                boolean isAttacker = (target == golem.getLastHurtByMob()
                        && golem.tickCount - golem.getLastHurtByMobTimestamp() <= 100);
                if (!isAttacker && this.safeZone.isTargetInHazard(target)) {
                    boolean pathDone = (currentPath == null || currentPath.isDone() || golem.getNavigation().isStuck());
                    boolean canHit = golem.isWithinMeleeAttackRange(target);

                    // If reached end of safe path and cannot hit target: disengage!
                    if (pathDone && !canHit) {
                        golem.setTarget(null);
                        golem.getNavigation().stop();
                    }
                }
            }

            // 5. Rim attack anchor: if target is in a hazard and golem can hit it from safe ground,
            // STOP forward navigation and clear horizontal velocity so it hits from the rim without falling in!
            if (target != null && !isProtecting && this.safeZone.isTargetInHazard(target)) {
                if (golem.isWithinMeleeAttackRange(target)) {
                    golem.getNavigation().stop();
                    Vec3 delta = golem.getDeltaMovement();
                    if (delta.x * delta.x + delta.z * delta.z > 0.001) {
                        golem.setDeltaMovement(0.0, delta.y, 0.0);
                    }
                }
            }

            // 6. Pursuit speed modifier: apply speed boost when pursuing hostile mobs (not
            // players)
            AttributeInstance speedAttr = golem.getAttribute(Attributes.MOVEMENT_SPEED);
            if (speedAttr != null) {
                LivingEntity currentTarget = golem.getTarget();
                if (currentTarget instanceof Mob && !(currentTarget instanceof Player) && currentTarget.isAlive()
                        && !currentTarget.isRemoved() && Config.DATA.mobPursuitSpeedModifier > 0.0) {
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

            // 7. Villager rescue follow range modifier: ensure follow range is boosted ONLY while actively rescuing
            this.golemsActuallyProtectVillagers$setRescueFollowRangeBoost(isProtecting);
        }
    }

    @Unique
    private void renderPitFallDebug(IronGolem golem, ServerLevel serverLevel) {
        LongSet hazards = this.safeZone.getHazardSurfaceBlocks();
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
                    net.minecraft.world.phys.shapes.VoxelShape shape = serverLevel.getBlockState(hazardPos)
                            .getCollisionShape(serverLevel, hazardPos);
                    double top = shape.isEmpty() ? 1.0 : shape.max(net.minecraft.core.Direction.Axis.Y);
                    py = y + top + 0.05;
                }
                serverLevel.sendParticles(DustParticleOptions.REDSTONE, x + 0.5, py, z + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
                count++;
            }
        }
    }

    @Unique
    private void renderPathDebug(IronGolem golem, ServerLevel serverLevel) {
        ProtectVillagersTargetGoal protectGoal = this.protectVillagersGoal;
        Path initialPath = (protectGoal != null) ? protectGoal.getPrecomputedPath() : null;
        if (initialPath != null && !initialPath.isDone()) {
            this.renderSpecificPath(serverLevel, initialPath, YELLOW_PATH_DUST, 0.22, 0);
        }

        Path currentPath = golem.getNavigation().getPath();
        if (currentPath != null && !currentPath.isDone()) {
            this.renderSpecificPath(serverLevel, currentPath, BLUE_PATH_DUST, 0.10, Math.max(0, currentPath.getNextNodeIndex() - 1));
        }
    }

    @Unique
    private void renderSpecificPath(ServerLevel serverLevel, Path path, DustParticleOptions dust, double heightOffset, int startIndex) {
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
                net.minecraft.world.phys.shapes.VoxelShape shape = serverLevel.getBlockState(groundPos)
                        .getCollisionShape(serverLevel, groundPos);
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
}
