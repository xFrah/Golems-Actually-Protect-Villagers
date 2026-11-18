package com.fdimo.golemsactuallyprotectvillagers.client;

import com.fdimo.golemsactuallyprotectvillagers.Config;
import com.fdimo.golemsactuallyprotectvillagers.Constants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.animal.golem.IronGolemModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.client.renderer.entity.state.IronGolemRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

public class IronGolemEyesLayer extends EyesLayer<IronGolemRenderState, IronGolemModel> {

    private static final RenderType GOLEM_EYES = RenderTypes.eyes(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "textures/entity/iron_golem/iron_golem_eyes.png"));

    public IronGolemEyesLayer(RenderLayerParent<IronGolemRenderState, IronGolemModel> renderer) {
        super(renderer);
    }

    @Override
    public RenderType renderType() {
        return GOLEM_EYES;
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int packedLight,
                       IronGolemRenderState state, float yRot, float xRot) {
        if (Config.DATA != null && !Config.DATA.enableRedPursuitEyes) {
            return;
        }
        if (state instanceof GolemRenderStateHolder holder && holder.golemsActuallyProtectVillagers$isPursuingThreat()) {
            super.submit(poseStack, submitNodeCollector, packedLight, state, yRot, xRot);
        }
    }
}
