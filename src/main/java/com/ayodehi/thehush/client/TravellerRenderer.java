package com.ayodehi.thehush.client;

import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.entity.AiVillagerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/**
 * The Traveller in his own body (see TravellerModel), with a second pass that makes his eyes and the
 * small lights on his robe glow in the dark. A persona may still supply its own texture, drawn on the
 * same 128x64 layout; only the bundled skin gets the glow.
 */
public class TravellerRenderer extends MobRenderer<AiVillagerEntity, AiVillagerRenderState, TravellerModel> {
    private static final Identifier DEFAULT = Identifier.fromNamespaceAndPath(TheHushMod.MODID, "textures/entity/traveller.png");
    private static final RenderType GLOW = RenderTypes.eyes(Identifier.fromNamespaceAndPath(TheHushMod.MODID, "textures/entity/traveller_glow.png"));
    private static final RenderType GLOW_EYES_DARK = RenderTypes.eyes(Identifier.fromNamespaceAndPath(TheHushMod.MODID, "textures/entity/traveller_glow_dark.png"));

    public TravellerRenderer(EntityRendererProvider.Context context) {
        super(context, new TravellerModel(context.bakeLayer(TravellerModel.LAYER)), 0.5F);
        addLayer(new GlowLayer(this));
    }

    @Override
    public AiVillagerRenderState createRenderState() {
        return new AiVillagerRenderState();
    }

    @Override
    public void extractRenderState(AiVillagerEntity entity, AiVillagerRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.isUnhappy = entity.getUnhappyCounter() > 0;
        state.villagerData = entity.getVillagerData();
        state.skin = entity.skin();
        state.sitting = entity.sitting();
        if (entity.animationFrozen() || state.sitting) state.walkAnimationSpeed = 0F;
    }

    /** The villager body is 34 pixels tall and clips into two-block ceilings; he is drawn a little smaller so he fits under them. */
    @Override
    protected void scale(AiVillagerRenderState state, com.mojang.blaze3d.vertex.PoseStack poseStack) {
        // Sitting: the hips come down to the ground (y is already flipped here, so down is +y).
        if (state.sitting) poseStack.translate(0.0F, TravellerModel.SIT_DROP, 0.0F);
        poseStack.scale(0.92F, 0.92F, 0.92F);
    }

    @Override
    public Identifier getTextureLocation(AiVillagerRenderState state) {
        if (!state.skin.isEmpty()) {
            Identifier id = Identifier.tryParse(state.skin);
            if (id != null) return id;
        }
        return DEFAULT;
    }

    /** His eyes, and the little lights sewn into the robe, lit from within. With his eyes gone dark only the lights remain. */
    private static final class GlowLayer extends RenderLayer<AiVillagerRenderState, TravellerModel> {
        GlowLayer(TravellerRenderer parent) {
            super(parent);
        }

        @Override
        public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AiVillagerRenderState state, float yRot, float xRot) {
            if (!state.skin.isEmpty() && !state.skin.contains("traveller")) return;
            RenderType type = state.skin.endsWith("_dark.png") ? GLOW_EYES_DARK : GLOW;
            collector.order(1).submitModel(getParentModel(), state, poseStack, type, light, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        }
    }
}
