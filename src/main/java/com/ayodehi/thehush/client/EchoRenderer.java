package com.ayodehi.thehush.client;

import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.entity.EchoEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.monster.enderman.EndermanModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.state.EndermanRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/** The Echo borrows the enderman's long body, in bone-pale ash, with no eyes at all. When it strikes it shakes. */
public class EchoRenderer extends MobRenderer<EchoEntity, EndermanRenderState, EndermanModel<EndermanRenderState>> {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(TheHushMod.MODID, "textures/entity/echo.png");
    private final RandomSource random = RandomSource.create();

    public EchoRenderer(EntityRendererProvider.Context context) {
        super(context, new EndermanModel<>(context.bakeLayer(ModelLayers.ENDERMAN)), 0.5F);
    }

    @Override
    public Identifier getTextureLocation(EndermanRenderState state) {
        return TEXTURE;
    }

    @Override
    public EndermanRenderState createRenderState() {
        return new EndermanRenderState();
    }

    @Override
    public void extractRenderState(EchoEntity entity, EndermanRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        HumanoidMobRenderer.extractHumanoidRenderState(entity, state, partialTicks, this.itemModelResolver);
        state.isCreepy = entity.isStriking();
        state.carriedBlock.clear();
    }

    @Override
    public Vec3 getRenderOffset(EndermanRenderState state) {
        Vec3 offset = super.getRenderOffset(state);
        if (state.isCreepy) {
            double d = 0.02 * state.scale;
            return offset.add(random.nextGaussian() * d, 0.0, random.nextGaussian() * d);
        }
        return offset;
    }

    @Override
    protected void scale(EndermanRenderState state, PoseStack poseStack) {
        poseStack.scale(0.9F, 0.9F, 0.9F);
    }

    /** Pale enough to be a shape in the dark, never a lamp. */
    @Override
    protected int getBlockLightLevel(EchoEntity entity, BlockPos pos) {
        return Math.max(3, super.getBlockLightLevel(entity, pos));
    }
}
