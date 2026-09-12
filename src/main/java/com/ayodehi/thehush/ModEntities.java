package com.ayodehi.thehush;

import com.ayodehi.thehush.entity.AiVillagerEntity;
import com.ayodehi.thehush.entity.EchoEntity;
import com.ayodehi.thehush.entity.UnsaidEntity;
import com.ayodehi.thehush.entity.PilgrimEntity;
import com.ayodehi.thehush.entity.WickEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {
    public static final DeferredRegister.Entities ENTITIES = DeferredRegister.createEntities(TheHushMod.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<AiVillagerEntity>> AI_VILLAGER =
            ENTITIES.registerEntityType("ai_villager", AiVillagerEntity::new, MobCategory.MISC,
                    b -> b.sized(0.6F, 1.95F).eyeHeight(1.62F));

    public static final DeferredHolder<EntityType<?>, EntityType<PilgrimEntity>> PILGRIM =
            ENTITIES.registerEntityType("pilgrim", PilgrimEntity::new, MobCategory.MISC,
                    b -> b.sized(0.6F, 1.95F).eyeHeight(1.62F));

    public static final DeferredHolder<EntityType<?>, EntityType<WickEntity>> WICK =
            ENTITIES.registerEntityType("wick", WickEntity::new, MobCategory.MONSTER,
                    b -> b.sized(0.5F, 0.9F).eyeHeight(0.6F));

    public static final DeferredHolder<EntityType<?>, EntityType<EchoEntity>> ECHO =
            ENTITIES.registerEntityType("echo", EchoEntity::new, MobCategory.MONSTER,
                    b -> b.sized(0.7F, 2.6F).eyeHeight(2.3F));

    public static final DeferredHolder<EntityType<?>, EntityType<UnsaidEntity>> UNSAID =
            ENTITIES.registerEntityType("unsaid", UnsaidEntity::new, MobCategory.MONSTER,
                    b -> b.sized(0.6F, 1.95F).eyeHeight(1.62F).fireImmune());

    private ModEntities() {}
}
