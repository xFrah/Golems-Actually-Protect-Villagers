package com.fdimo.golemsactuallyprotectvillagers.mixin;

import com.fdimo.golemsactuallyprotectvillagers.ai.GolemSafeZoneHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MeleeAttackGoal.class)
public abstract class MixinMeleeAttackGoal extends Goal {

    @Shadow @Final protected PathfinderMob mob;
    @Shadow private Path path;
    @Shadow private int ticksUntilNextPathRecalculation;

    @Unique
    private static boolean golemsActuallyProtectVillagers$isPathTargetValid(Path path, LivingEntity target) {
        if (path == null || path.isDone() || target == null || !target.isAlive() || target.isRemoved()) {
            return false;
        }
        Node endNode = path.getEndNode();
        if (endNode == null) {
            return false;
        }
        // If target moved more than ~3.5 blocks away from the path endpoint, target is off-track!
        double distSqr = target.distanceToSqr(endNode.x + 0.5, endNode.y, endNode.z + 0.5);
        return distSqr <= 12.25;
    }

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void onCanUse(CallbackInfoReturnable<Boolean> cir) {
        if (this.mob instanceof GolemSafeZoneHolder holder && holder.golemsActuallyProtectVillagers$isProtectingVillager()) {
            LivingEntity target = this.mob.getTarget();
            Path activePath = this.mob.getNavigation().getPath();
            if (golemsActuallyProtectVillagers$isPathTargetValid(activePath, target)) {
                this.path = activePath;
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "start", at = @At("HEAD"), cancellable = true)
    private void onStart(CallbackInfo ci) {
        if (this.mob instanceof GolemSafeZoneHolder holder && holder.golemsActuallyProtectVillagers$isProtectingVillager()) {
            LivingEntity target = this.mob.getTarget();
            Path activePath = this.mob.getNavigation().getPath();
            if (golemsActuallyProtectVillagers$isPathTargetValid(activePath, target)) {
                this.mob.setAggressive(true);
                this.ticksUntilNextPathRecalculation = 40;
                ci.cancel();
            }
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        if (this.mob instanceof GolemSafeZoneHolder holder && holder.golemsActuallyProtectVillagers$isProtectingVillager()) {
            LivingEntity target = this.mob.getTarget();
            Path activePath = this.mob.getNavigation().getPath();
            if (golemsActuallyProtectVillagers$isPathTargetValid(activePath, target) && !this.mob.getNavigation().isStuck()) {
                // Keep the active validated path alive as long as target stays at destination
                if (this.ticksUntilNextPathRecalculation <= 1) {
                    this.ticksUntilNextPathRecalculation = 20;
                }
            } else {
                // When path finishes, target moves off-track, or golem gets stuck:
                // If our 20-tick buffer is active (> 10), drop it to 0 for a seamless transition.
                // Vanilla's own 4-10 tick recalculation timer (<= 10) is preserved so it never spams every tick!
                if (this.ticksUntilNextPathRecalculation > 10) {
                    this.ticksUntilNextPathRecalculation = 0;
                }
            }
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTickTail(CallbackInfo ci) {
        if (this.mob instanceof GolemSafeZoneHolder holder && holder.golemsActuallyProtectVillagers$isProtectingVillager()) {
            com.fdimo.golemsactuallyprotectvillagers.ai.ProtectVillagersTargetGoal protectGoal = holder.golemsActuallyProtectVillagers$getProtectGoal();
            if (protectGoal != null) {
                Path currentNavPath = this.mob.getNavigation().getPath();
                if (currentNavPath != null && currentNavPath != protectGoal.getPrecomputedPath()) {
                    protectGoal.setPrecomputedPath(currentNavPath);
                }
            }
        }
    }
}

