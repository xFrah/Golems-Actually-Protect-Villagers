package com.fdimo.golemsactuallyprotectvillagers.mixin;

import com.fdimo.golemsactuallyprotectvillagers.ai.VillageDistressSignal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.VillagerPanicTrigger;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VillagerPanicTrigger.class)
public abstract class MixinVillagerPanicTrigger {

    @Inject(method = "start(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/villager/Villager;J)V", at = @At("TAIL"))
    private void onStart(ServerLevel serverLevel, Villager villager, long gameTime, CallbackInfo ci) {
        VillageDistressSignal.onVillagerPanic(serverLevel, villager, gameTime);
    }

    @Inject(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/npc/villager/Villager;J)V", at = @At("TAIL"))
    private void onTick(ServerLevel serverLevel, Villager villager, long gameTime, CallbackInfo ci) {
        VillageDistressSignal.onVillagerPanic(serverLevel, villager, gameTime);
    }
}

