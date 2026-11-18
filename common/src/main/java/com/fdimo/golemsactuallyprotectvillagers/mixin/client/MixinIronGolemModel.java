package com.fdimo.golemsactuallyprotectvillagers.mixin.client;

import com.fdimo.golemsactuallyprotectvillagers.client.GolemRenderStateHolder;
import net.minecraft.client.model.animal.golem.IronGolemModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.IronGolemRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IronGolemModel.class)
public abstract class MixinIronGolemModel {

    @Shadow @Final private ModelPart head;
    @Shadow @Final private ModelPart rightArm;
    @Shadow @Final private ModelPart leftArm;
    @Shadow @Final private ModelPart rightLeg;
    @Shadow @Final private ModelPart leftLeg;

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/IronGolemRenderState;)V", at = @At("TAIL"))
    private void onSetupAnim(IronGolemRenderState state, CallbackInfo ci) {
        if (state instanceof GolemRenderStateHolder holder && holder.golemsActuallyProtectVillagers$isMantling()) {
            float armAngle = holder.golemsActuallyProtectVillagers$getMantleArmAngle();

            // Set both arms to the exact calculated angle locking hands to the ledge edge
            this.rightArm.xRot = armAngle;
            this.leftArm.xRot = armAngle;
            this.rightArm.yRot = 0.0F;
            this.leftArm.yRot = 0.0F;
            this.rightArm.zRot = 0.0F;
            this.leftArm.zRot = 0.0F;

            // Keep body and head straight:
            this.head.xRot = 0.0F;
            this.head.yRot = 0.0F;
            this.head.zRot = 0.0F;

            // Legs hang straight down while hoisted
            this.rightLeg.xRot = 0.0F;
            this.leftLeg.xRot = 0.0F;
            this.rightLeg.yRot = 0.0F;
            this.leftLeg.yRot = 0.0F;
        }
    }
}
