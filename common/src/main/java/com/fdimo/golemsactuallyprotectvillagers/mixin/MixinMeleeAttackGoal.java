package com.fdimo.golemsactuallyprotectvillagers.mixin;

import com.fdimo.golemsactuallyprotectvillagers.ai.GolemSafeZoneHolder;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MeleeAttackGoal.class)
public abstract class MixinMeleeAttackGoal extends Goal {

    @Shadow @Final protected PathfinderMob mob;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void onCanUse(CallbackInfoReturnable<Boolean> cir) {
        if (this.mob instanceof GolemSafeZoneHolder holder && holder.golemsActuallyProtectVillagers$isMantling()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "start", at = @At("HEAD"), cancellable = true)
    private void onStart(CallbackInfo ci) {
        if (this.mob instanceof GolemSafeZoneHolder holder && holder.golemsActuallyProtectVillagers$isMantling()) {
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void onTick(CallbackInfo ci) {
        if (this.mob instanceof GolemSafeZoneHolder holder && holder.golemsActuallyProtectVillagers$isMantling()) {
            ci.cancel();
        }
    }
}
