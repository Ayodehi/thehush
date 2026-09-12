package com.ayodehi.thehush.client;

import com.ayodehi.thehush.entity.Skinned;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.npc.VillagerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.layers.VillagerProfessionLayer;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.ArrayList;
import java.util.List;

/**
 * The vanilla villager renderer, except a persona may supply its own texture. With a custom skin the
 * profession/biome clothing overlay is skipped so the skin shows as drawn (hood included).
 */
public class AiVillagerRenderer extends VillagerRenderer {

    public AiVillagerRenderer(EntityRendererProvider.Context context) {
        super(context);
        List<RenderLayer<VillagerRenderState, VillagerModel>> wrapped = new ArrayList<>();
        for (RenderLayer<VillagerRenderState, VillagerModel> layer : layers) {
            wrapped.add(layer instanceof VillagerProfessionLayer ? new VanillaOnlyLayer(this, layer) : layer);
        }
        layers.clear();
        layers.addAll(wrapped);
    }

    @Override
    public VillagerRenderState createRenderState() {
        return new AiVillagerRenderState();
    }

    @Override
    public void extractRenderState(Villager entity, VillagerRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        if (state instanceof AiVillagerRenderState s) {
            s.skin = entity instanceof Skinned sk ? sk.skin() : "";
            if (entity instanceof Skinned sk && sk.animationFrozen()) {
                state.walkAnimationSpeed = 0F;
            }
        }
    }

    @Override
    public Identifier getTextureLocation(VillagerRenderState state) {
        if (state instanceof AiVillagerRenderState s && !s.skin.isEmpty() && !state.isBaby) {
            Identifier id = Identifier.tryParse(s.skin);
            if (id != null) return id;
        }
        return super.getTextureLocation(state);
    }

    /** Runs the wrapped (profession/biome clothing) layer only for villagers without a custom skin. */
    private static final class VanillaOnlyLayer extends RenderLayer<VillagerRenderState, VillagerModel> {
        private final RenderLayer<VillagerRenderState, VillagerModel> inner;

        VanillaOnlyLayer(AiVillagerRenderer parent, RenderLayer<VillagerRenderState, VillagerModel> inner) {
            super(parent);
            this.inner = inner;
        }

        @Override
        public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, VillagerRenderState state,
                           float yRot, float xRot) {
            if (state instanceof AiVillagerRenderState s && !s.skin.isEmpty()) return;
            inner.submit(poseStack, collector, light, state, yRot, xRot);
        }
    }
}
