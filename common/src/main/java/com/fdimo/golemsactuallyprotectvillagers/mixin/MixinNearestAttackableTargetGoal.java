package com.fdimo.golemsactuallyprotectvillagers.mixin;

import com.fdimo.golemsactuallyprotectvillagers.ai.GolemSafeZone;
import com.fdimo.golemsactuallyprotectvillagers.ai.GolemSafeZoneHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NearestAttackableTargetGoal.class)
public abstract class MixinNearestAttackableTargetGoal extends TargetGoal {

    @Shadow
    protected LivingEntity target;

    protected MixinNearestAttackableTargetGoal() {
        super(null, false);
    }

    @Inject(method = "canUse", at = @At("RETURN"), cancellable = true)
    private void golemsActuallyProtectVillagers$filterHazardTarget(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && this.mob instanceof IronGolem golem && golem instanceof GolemSafeZoneHolder holder) {
            GolemSafeZone safeZone = holder.golemsActuallyProtectVillagers$getSafeZone();
            if (safeZone != null && this.target != null) {
                // If the target is actively attacking the golem, allow self-defense
                if (this.target == golem.getLastHurtByMob() && golem.tickCount - golem.getLastHurtByMobTimestamp() <= 100) {
                    return;
                }
                // If the golem just spawned and safe zone hasn't finished its first scan yet, don't lock on until scan completes
                if (!safeZone.isInitialized()) {
                    if (golem.level() instanceof ServerLevel serverLevel) {
                        safeZone.requestAsyncUpdate(golem, serverLevel);
                    }
                    this.target = null;
                    cir.setReturnValue(false);
                    return;
                }
                if (safeZone.isTargetInHazard(this.target) && !golem.isWithinMeleeAttackRange(this.target)) {
                    this.target = null;
                    cir.setReturnValue(false);
                }
            }
        }
    }
}

