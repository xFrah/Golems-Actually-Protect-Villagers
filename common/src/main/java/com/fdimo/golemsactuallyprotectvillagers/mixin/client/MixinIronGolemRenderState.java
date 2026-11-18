package com.fdimo.golemsactuallyprotectvillagers.mixin.client;

import com.fdimo.golemsactuallyprotectvillagers.client.GolemRenderStateHolder;
import net.minecraft.client.renderer.entity.state.IronGolemRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(IronGolemRenderState.class)
public abstract class MixinIronGolemRenderState implements GolemRenderStateHolder {

    @Unique
    private boolean golemsActuallyProtectVillagers$mantling;

    @Unique
    private float golemsActuallyProtectVillagers$mantleArmAngle;

    @Unique
    private boolean golemsActuallyProtectVillagers$pursuingThreat;

    @Override
    public boolean golemsActuallyProtectVillagers$isMantling() {
        return this.golemsActuallyProtectVillagers$mantling;
    }

    @Override
    public void golemsActuallyProtectVillagers$setMantling(boolean mantling) {
        this.golemsActuallyProtectVillagers$mantling = mantling;
    }

    @Override
    public float golemsActuallyProtectVillagers$getMantleArmAngle() {
        return this.golemsActuallyProtectVillagers$mantleArmAngle;
    }

    @Override
    public void golemsActuallyProtectVillagers$setMantleArmAngle(float angle) {
        this.golemsActuallyProtectVillagers$mantleArmAngle = angle;
    }

    @Override
    public boolean golemsActuallyProtectVillagers$isPursuingThreat() {
        return this.golemsActuallyProtectVillagers$pursuingThreat;
    }

    @Override
    public void golemsActuallyProtectVillagers$setPursuingThreat(boolean pursuing) {
        this.golemsActuallyProtectVillagers$pursuingThreat = pursuing;
    }
}
