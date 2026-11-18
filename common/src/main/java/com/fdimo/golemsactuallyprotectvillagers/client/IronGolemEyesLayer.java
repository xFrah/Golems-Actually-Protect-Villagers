package com.fdimo.golemsactuallyprotectvillagers.client;

import com.fdimo.golemsactuallyprotectvillagers.Config;
import com.fdimo.golemsactuallyprotectvillagers.Constants;
import com.fdimo.golemsactuallyprotectvillagers.ai.GolemSafeZoneHolder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.IronGolemModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.IronGolem;

public class IronGolemEyesLayer extends RenderLayer<IronGolem, IronGolemModel<IronGolem>> {

    private static final RenderType GOLEM_EYES = RenderType.eyes(
            new ResourceLocation(Constants.MOD_ID, "textures/entity/iron_golem/iron_golem_eyes.png"));

    public IronGolemEyesLayer(RenderLayerParent<IronGolem, IronGolemModel<IronGolem>> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, IronGolem golem,
                       float limbSwing, float limbSwingAmount, float partialTick,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        if (Config.DATA != null && !Config.DATA.enableRedPursuitEyes) {
            return;
        }
        if (golem instanceof GolemSafeZoneHolder holder && holder.golemsActuallyProtectVillagers$isPursuingThreat()) {
            VertexConsumer vertexConsumer = buffer.getBuffer(GOLEM_EYES);
            this.getParentModel().renderToBuffer(poseStack, vertexConsumer, 15728640, OverlayTexture.NO_OVERLAY, 1.0F, 1.0F, 1.0F, 1.0F);
        }
    }
}
