package com.fdimo.golemsactuallyprotectvillagers.mixin.client;

import com.fdimo.golemsactuallyprotectvillagers.client.IronGolemEyesLayer;
import net.minecraft.client.model.IronGolemModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.IronGolemRenderer;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.world.entity.animal.IronGolem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IronGolemRenderer.class)
public abstract class MixinIronGolemRenderer extends MobRenderer<IronGolem, IronGolemModel<IronGolem>> {

    protected MixinIronGolemRenderer(EntityRendererProvider.Context context, IronGolemModel<IronGolem> model, float shadowRadius) {
        super(context, model, shadowRadius);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(EntityRendererProvider.Context context, CallbackInfo ci) {
        this.addLayer(new IronGolemEyesLayer(this));
    }
}
