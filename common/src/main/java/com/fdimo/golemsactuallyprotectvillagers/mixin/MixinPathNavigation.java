package com.fdimo.golemsactuallyprotectvillagers.mixin;

import com.fdimo.golemsactuallyprotectvillagers.ai.GolemSafeZoneHolder;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PathNavigation.class)
public abstract class MixinPathNavigation {

    @Shadow @Final protected Mob mob;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void onNavigationTick(CallbackInfo ci) {
        if (this.mob instanceof GolemSafeZoneHolder holder && holder.golemsActuallyProtectVillagers$isMantling()) {
            ci.cancel();
        }
    }

    @Inject(method = "isStuck", at = @At("HEAD"), cancellable = true)
    private void onIsStuck(CallbackInfoReturnable<Boolean> cir) {
        if (this.mob instanceof GolemSafeZoneHolder holder && holder.golemsActuallyProtectVillagers$isMantling()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "stop", at = @At("HEAD"), cancellable = true)
    private void onStop(CallbackInfo ci) {
        if (this.mob instanceof GolemSafeZoneHolder holder && holder.golemsActuallyProtectVillagers$isMantling()) {
            ci.cancel();
        }
    }
}
