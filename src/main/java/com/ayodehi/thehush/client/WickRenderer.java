package com.ayodehi.thehush.client;

import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.entity.WickEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.monster.vex.VexModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.state.VexRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

/** The Wick borrows the vex's small drifting body, in charcoal, with embers for a mouth. */
public class WickRenderer extends MobRenderer<WickEntity, VexRenderState, VexModel> {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(TheHushMod.MODID, "textures/entity/wick.png");

    public WickRenderer(EntityRendererProvider.Context context) {
        super(context, new VexModel(context.bakeLayer(ModelLayers.VEX)), 0.4F);
    }

    @Override
    public Identifier getTextureLocation(VexRenderState state) {
        return TEXTURE;
    }

    @Override
    public VexRenderState createRenderState() {
        return new VexRenderState();
    }

    @Override
    public void extractRenderState(WickEntity entity, VexRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.isCharging = entity.isCornered();
    }

    @Override
    protected void scale(VexRenderState state, PoseStack poseStack) {
        poseStack.scale(1.6F, 1.6F, 1.6F);
    }

    /** The embers give it a little light of its own, so it reads as a shape in the dark rather than nothing. */
    @Override
    protected int getBlockLightLevel(WickEntity entity, BlockPos pos) {
        return Math.max(6, super.getBlockLightLevel(entity, pos));
    }
}
