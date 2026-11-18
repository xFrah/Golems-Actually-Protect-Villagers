package com.fdimo.golemsactuallyprotectvillagers.mixin.client;

import com.fdimo.golemsactuallyprotectvillagers.ai.GolemSafeZoneHolder;
import com.fdimo.golemsactuallyprotectvillagers.client.GolemRenderStateHolder;
import com.fdimo.golemsactuallyprotectvillagers.client.IronGolemEyesLayer;
import net.minecraft.client.model.animal.golem.IronGolemModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.IronGolemRenderer;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.state.IronGolemRenderState;
import net.minecraft.world.entity.animal.golem.IronGolem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IronGolemRenderer.class)
public abstract class MixinIronGolemRenderer extends MobRenderer<IronGolem, IronGolemRenderState, IronGolemModel> {

    protected MixinIronGolemRenderer(EntityRendererProvider.Context context, IronGolemModel model, float shadowRadius) {
        super(context, model, shadowRadius);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(EntityRendererProvider.Context context, CallbackInfo ci) {
        this.addLayer(new IronGolemEyesLayer(this));
    }

    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/animal/golem/IronGolem;Lnet/minecraft/client/renderer/entity/state/IronGolemRenderState;F)V", at = @At("TAIL"))
    private void onExtractRenderState(IronGolem golem, IronGolemRenderState state, float partialTick, CallbackInfo ci) {
        if (state instanceof GolemRenderStateHolder stateHolder && golem instanceof GolemSafeZoneHolder golemHolder) {
            boolean mantling = golemHolder.golemsActuallyProtectVillagers$isMantling();
            stateHolder.golemsActuallyProtectVillagers$setMantling(mantling);
            stateHolder.golemsActuallyProtectVillagers$setMantleArmAngle(golemHolder.golemsActuallyProtectVillagers$getMantleArmAngle(partialTick));
            stateHolder.golemsActuallyProtectVillagers$setPursuingThreat(golemHolder.golemsActuallyProtectVillagers$isPursuingThreat());
            if (mantling) {
                state.walkAnimationSpeed = 0.0F;
                state.walkAnimationPos = 0.0F;
                state.bodyRot = golem.getYRot();
                state.yRot = 0.0F;
                state.xRot = 0.0F;
            }
        }
    }
}
