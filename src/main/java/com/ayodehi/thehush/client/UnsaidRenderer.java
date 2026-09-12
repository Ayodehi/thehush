package com.ayodehi.thehush.client;

import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.entity.UnsaidEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** The Unsaid wear the Traveller's shape, translucent and grey, drifting a little up and down, with two pale lights for eyes. */
public class UnsaidRenderer extends MobRenderer<UnsaidEntity, AiVillagerRenderState, TravellerModel> {
    private static final Identifier[] TEXTURES = new Identifier[UnsaidEntity.VARIANTS];
    private static final RenderType GLOW = RenderTypes.eyes(Identifier.fromNamespaceAndPath(TheHushMod.MODID, "textures/entity/unsaid_glow.png"));

    static {
        for (int i = 0; i < TEXTURES.length; i++) {
            TEXTURES[i] = Identifier.fromNamespaceAndPath(TheHushMod.MODID, "textures/entity/unsaid_" + i + ".png");
        }
    }

    public UnsaidRenderer(EntityRendererProvider.Context context) {
        super(context, new TravellerModel(context.bakeLayer(TravellerModel.LAYER), RenderTypes::entityTranslucent), 0.0F);
        addLayer(new GlowLayer(this));
    }

    @Override
    public AiVillagerRenderState createRenderState() {
        return new AiVillagerRenderState();
    }

    @Override
    public void extractRenderState(UnsaidEntity entity, AiVillagerRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.villagerData = null;
        state.isUnhappy = false;
        state.walkAnimationSpeed = 0F; // it does not walk
        state.skin = "unsaid:" + entity.variant();
    }

    @Override
    public Identifier getTextureLocation(AiVillagerRenderState state) {
        int v = 0;
        try {
            v = Integer.parseInt(state.skin.substring(state.skin.indexOf(':') + 1));
        } catch (RuntimeException ignored) {
            // default variant
        }
        return TEXTURES[Math.floorMod(v, TEXTURES.length)];
    }

    @Override
    public Vec3 getRenderOffset(AiVillagerRenderState state) {
        return new Vec3(0.0, 0.15 + Mth.sin(state.ageInTicks * 0.07F) * 0.12, 0.0);
    }

    @Override
    protected void scale(AiVillagerRenderState state, PoseStack poseStack) {
        poseStack.scale(0.92F, 0.92F, 0.92F);
    }

    private static final class GlowLayer extends RenderLayer<AiVillagerRenderState, TravellerModel> {
        GlowLayer(UnsaidRenderer parent) {
            super(parent);
        }

        @Override
        public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AiVillagerRenderState state, float yRot, float xRot) {
            collector.order(1).submitModel(getParentModel(), state, poseStack, GLOW, light, OverlayTexture.NO_OVERLAY, state.outlineColor, null);
        }
    }
}
