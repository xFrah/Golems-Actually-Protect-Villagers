package com.fdimo.golemsactuallyprotectvillagers.mixin;

import com.fdimo.golemsactuallyprotectvillagers.ai.VillageDistressSignal;
import com.fdimo.golemsactuallyprotectvillagers.ai.VillagerDistressEmitter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Villager.class)
public abstract class MixinVillager extends AbstractVillager implements VillagerDistressEmitter {

    @Unique
    private long golemsActuallyProtectVillagers$lastDistressTime = 0;
    @Unique
    private int golemsActuallyProtectVillagers$lastAttackerId = -1;

    protected MixinVillager(EntityType<? extends AbstractVillager> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    public long golemsActuallyProtectVillagers$getLastDistressTime() {
        return this.golemsActuallyProtectVillagers$lastDistressTime;
    }

    @Override
    public void golemsActuallyProtectVillagers$setLastDistressTime(long time) {
        this.golemsActuallyProtectVillagers$lastDistressTime = time;
    }

    @Override
    public int golemsActuallyProtectVillagers$getLastAttackerId() {
        return this.golemsActuallyProtectVillagers$lastAttackerId;
    }

    @Override
    public void golemsActuallyProtectVillagers$setLastAttackerId(int id) {
        this.golemsActuallyProtectVillagers$lastAttackerId = id;
    }

    @Inject(method = "setLastHurtByMob", at = @At("TAIL"))
    private void onSetLastHurtByMob(LivingEntity livingEntity, CallbackInfo ci) {
        if (!this.level().isClientSide() && this.level() instanceof ServerLevel serverLevel && livingEntity instanceof Mob mob) {
            VillageDistressSignal.broadcast(serverLevel, (Villager) (Object) this, mob);
        }
    }
}

