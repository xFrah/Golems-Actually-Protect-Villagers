package com.fdimo.golemsactuallyprotectvillagers.mixin.client;

import com.fdimo.golemsactuallyprotectvillagers.ai.GolemSafeZoneHolder;
import net.minecraft.client.model.IronGolemModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.animal.IronGolem;
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
    @Shadow @Final private ModelPart root;

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/animal/IronGolem;FFFFF)V", at = @At("TAIL"))
    private void onSetupAnim(IronGolem golem, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (golem instanceof GolemSafeZoneHolder holder && holder.golemsActuallyProtectVillagers$isMantling()) {
            float partialTick = ageInTicks - (float) golem.tickCount;
            float armAngle = holder.golemsActuallyProtectVillagers$getMantleArmAngle(partialTick);

            // Set both arms to the exact calculated angle locking hands to the ledge edge
            this.rightArm.xRot = armAngle;
            this.leftArm.xRot = armAngle;
            this.rightArm.yRot = 0.0F;
            this.leftArm.yRot = 0.0F;
            this.rightArm.zRot = 0.0F;
            this.leftArm.zRot = 0.0F;

            // Keep body and head straight as requested by the user:
            // "no modification to head and body tilt. i want it to be straight for now, just move its arms"
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
