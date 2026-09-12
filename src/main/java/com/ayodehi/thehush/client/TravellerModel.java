package com.ayodehi.thehush.client;

import com.ayodehi.thehush.TheHushMod;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * The Traveller's own body: a villager's head and nose under a hood, a cowl at the neck, a long robe over
 * the body, arms that hang at his sides in wide sleeves (not the villager's crossed arms), and boots
 * showing under the hem. Texture layout is 128x64; see MakeTravellerV2 in the image tooling for the map.
 */
public class TravellerModel extends EntityModel<VillagerRenderState> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(TheHushMod.MODID, "traveller"), "main");

    private final ModelPart head;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private final ModelPart rightLeg;
    private final ModelPart leftLeg;

    public TravellerModel(ModelPart root) {
        this(root, net.minecraft.client.renderer.rendertype.RenderTypes::entityCutout);
    }

    public TravellerModel(ModelPart root, java.util.function.Function<Identifier, net.minecraft.client.renderer.rendertype.RenderType> renderType) {
        super(root, renderType);
        this.head = root.getChild("head");
        this.rightArm = root.getChild("right_arm");
        this.leftArm = root.getChild("left_arm");
        this.rightLeg = root.getChild("right_leg");
        this.leftLeg = root.getChild("left_leg");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition head = root.addOrReplaceChild("head",
                CubeListBuilder.create().texOffs(0, 0).addBox(-4.0F, -10.0F, -4.0F, 8.0F, 10.0F, 8.0F), PartPose.ZERO);
        head.addOrReplaceChild("hood",
                CubeListBuilder.create().texOffs(32, 0).addBox(-4.0F, -10.0F, -4.0F, 8.0F, 10.0F, 8.0F, new CubeDeformation(0.5F)), PartPose.ZERO);
        // The nose ends at the chin (a villager's dips a pixel below it, which reads oddly against the cowl).
        head.addOrReplaceChild("nose",
                CubeListBuilder.create().texOffs(64, 0).addBox(-1.0F, -1.0F, -6.0F, 2.0F, 4.0F, 2.0F), PartPose.offset(0.0F, -3.0F, 0.0F));
        PartDefinition body = root.addOrReplaceChild("body",
                CubeListBuilder.create().texOffs(0, 18).addBox(-4.0F, 0.0F, -3.0F, 8.0F, 12.0F, 6.0F), PartPose.ZERO);
        body.addOrReplaceChild("robe",
                CubeListBuilder.create().texOffs(28, 18).addBox(-4.0F, 0.0F, -3.0F, 8.0F, 20.0F, 6.0F, new CubeDeformation(0.5F)), PartPose.ZERO);
        body.addOrReplaceChild("cowl",
                CubeListBuilder.create().texOffs(72, 0).addBox(-4.0F, 0.0F, -4.0F, 8.0F, 3.0F, 8.0F, new CubeDeformation(0.6F)), PartPose.ZERO);
        PartDefinition rightArm = root.addOrReplaceChild("right_arm",
                CubeListBuilder.create().texOffs(56, 18).addBox(-3.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F), PartPose.offset(-5.0F, 2.0F, 0.0F));
        rightArm.addOrReplaceChild("right_sleeve",
                CubeListBuilder.create().texOffs(88, 18).addBox(-3.0F, -2.0F, -2.0F, 4.0F, 9.0F, 4.0F, new CubeDeformation(0.5F)), PartPose.ZERO);
        PartDefinition leftArm = root.addOrReplaceChild("left_arm",
                CubeListBuilder.create().texOffs(72, 18).mirror().addBox(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F), PartPose.offset(5.0F, 2.0F, 0.0F));
        leftArm.addOrReplaceChild("left_sleeve",
                CubeListBuilder.create().texOffs(104, 18).mirror().addBox(-1.0F, -2.0F, -2.0F, 4.0F, 9.0F, 4.0F, new CubeDeformation(0.5F)), PartPose.ZERO);
        root.addOrReplaceChild("right_leg",
                CubeListBuilder.create().texOffs(56, 34).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F), PartPose.offset(-2.0F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg",
                CubeListBuilder.create().texOffs(72, 34).mirror().addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F), PartPose.offset(2.0F, 12.0F, 0.0F));
        return LayerDefinition.create(mesh, 128, 64);
    }

    /** How far the body drops when he sits: the legs' length, less a little so the robe rests on the ground. */
    public static final float SIT_DROP = 0.62F;

    @Override
    public void setupAnim(VillagerRenderState state) {
        super.setupAnim(state);
        if (state instanceof AiVillagerRenderState s && s.sitting) {
            setupSitting(state);
            return;
        }
        head.yRot = state.yRot * (float) (Math.PI / 180.0);
        head.xRot = state.xRot * (float) (Math.PI / 180.0);
        if (state.isUnhappy) {
            head.zRot = 0.3F * Mth.sin(0.45F * state.ageInTicks);
            head.xRot = 0.4F;
        } else {
            head.zRot = 0.0F;
        }
        float walk = state.walkAnimationPos;
        float speed = state.walkAnimationSpeed;
        rightLeg.xRot = Mth.cos(walk * 0.6662F) * 1.4F * speed * 0.5F;
        leftLeg.xRot = Mth.cos(walk * 0.6662F + (float) Math.PI) * 1.4F * speed * 0.5F;
        // Arms swing against the legs, gently; at rest they hang with the hands a little forward, as in the drawing.
        rightArm.xRot = Mth.cos(walk * 0.6662F + (float) Math.PI) * 1.2F * speed * 0.5F - 0.08F;
        leftArm.xRot = Mth.cos(walk * 0.6662F) * 1.2F * speed * 0.5F - 0.08F;
        float idle = Mth.cos(state.ageInTicks * 0.09F) * 0.03F;
        rightArm.zRot = 0.06F + idle;
        leftArm.zRot = -0.06F - idle;
    }

    /** Sat on the ground: legs out in front, a little apart; hands in his lap; the head still follows. */
    private void setupSitting(VillagerRenderState state) {
        head.yRot = state.yRot * (float) (Math.PI / 180.0);
        head.xRot = state.xRot * (float) (Math.PI / 180.0) + 0.08F;
        head.zRot = 0.0F;
        rightLeg.xRot = -1.41F;
        rightLeg.yRot = 0.26F;
        rightLeg.zRot = 0.0F;
        leftLeg.xRot = -1.41F;
        leftLeg.yRot = -0.26F;
        leftLeg.zRot = 0.0F;
        float breath = Mth.cos(state.ageInTicks * 0.07F) * 0.02F;
        rightArm.xRot = -0.55F + breath;
        leftArm.xRot = -0.55F + breath;
        rightArm.zRot = 0.10F;
        leftArm.zRot = -0.10F;
    }
}
