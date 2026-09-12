package com.ayodehi.thehush;

import com.ayodehi.thehush.campaign.CampaignEvents;
import com.ayodehi.thehush.campaign.CampaignManager;
import com.ayodehi.thehush.campaign.CampaignRegistry;
import com.ayodehi.thehush.commands.TheHushCommands;
import com.ayodehi.thehush.conversation.ConversationManager;
import com.ayodehi.thehush.conversation.PlayerActivity;
import com.ayodehi.thehush.entity.AiVillagerEntity;
import com.ayodehi.thehush.entity.ReturnRegistry;
import com.ayodehi.thehush.llm.LlmService;
import com.ayodehi.thehush.memory.MemoryStore;
import com.ayodehi.thehush.persona.PersonaRegistry;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.LootTableLoadEvent;
import com.ayodehi.thehush.campaign.Clues;
import net.minecraft.world.entity.monster.Silverfish;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import com.ayodehi.thehush.network.HushSilencePayload;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

@Mod(TheHushMod.MODID)
public class TheHushMod {
    public static final String MODID = "thehush";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TheHushMod(IEventBus modEventBus, ModContainer modContainer) {
        Migration.migrateConfig();
        ModBlocks.BLOCKS.register(modEventBus);
        ModEntities.ENTITIES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        modEventBus.addListener(this::onAttributes);
        modEventBus.addListener(this::onCreativeTabs);
        modEventBus.addListener(this::onConfigReloaded);
        modEventBus.addListener(this::onRegisterPayloads);

        NeoForge.EVENT_BUS.addListener(this::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerChat);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onBlockBroken);
        NeoForge.EVENT_BUS.addListener(this::onBlockPlaced);
        NeoForge.EVENT_BUS.addListener(this::onAdvancement);
        NeoForge.EVENT_BUS.addListener(this::onChangeTarget);
        NeoForge.EVENT_BUS.addListener(this::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(this::onChangedDimension);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onPlayerRespawn);
        NeoForge.EVENT_BUS.addListener(this::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(Clues::onLootTableLoad);
    }

    // ---- campaign hooks ----

    /** The silverfish go around him. */
    private void onChangeTarget(LivingChangeTargetEvent event) {
        if (event.getEntity() instanceof Silverfish && event.getNewAboutToBeSetTarget() instanceof AiVillagerEntity v
                && CampaignManager.get().takesPart(v)) {
            event.setCanceled(true);
        }
    }

    private void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer sp) || !(event.getLevel() instanceof ServerLevel level)) return;
        var state = level.getBlockState(event.getPos());
        if (state.is(Blocks.BELL)) CampaignEvents.onBellRung(sp, level, event.getPos());
        else if (state.is(Blocks.NOTE_BLOCK)) CampaignEvents.onNoteBlock(level, event.getPos());
    }

    private void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) CampaignEvents.onPlayerChangedDimension(sp, event.getFrom(), event.getTo());
    }

    /** The dragon dying by any means (a sword, a bed, /kill) counts; the advancement alone would miss commands. */
    private void onLivingDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity() instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon
                && dragon.level().getServer() != null) {
            CampaignManager.get().setFlag(dragon.level().getServer(), "dragon_dead");
        }
        if (event.getEntity() instanceof ServerPlayer sp && event.getSource().getEntity() instanceof com.ayodehi.thehush.entity.EchoEntity echo) {
            com.ayodehi.thehush.entity.EchoHunts.onPreyKilled(sp, echo);
        }
    }

    private void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) CampaignEvents.onPlayerRespawn(sp);
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) CampaignEvents.onPlayerLoggedIn(sp);
    }

    /** The silence packet; its client handler is registered in TheHushClient. */
    private void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(HushSilencePayload.TYPE, HushSilencePayload.STREAM_CODEC);
        event.registrar("1").playToClient(com.ayodehi.thehush.network.HushVoicePayload.TYPE, com.ayodehi.thehush.network.HushVoicePayload.STREAM_CODEC);
        event.registrar("1").playToClient(com.ayodehi.thehush.network.HushDropPayload.TYPE, com.ayodehi.thehush.network.HushDropPayload.STREAM_CODEC);
    }

    private void onAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.AI_VILLAGER.get(), AiVillagerEntity.createAttributes().build());
        event.put(ModEntities.PILGRIM.get(), AiVillagerEntity.createAttributes().build());
        event.put(ModEntities.WICK.get(), com.ayodehi.thehush.entity.WickEntity.createAttributes().build());
        event.put(ModEntities.ECHO.get(), com.ayodehi.thehush.entity.EchoEntity.createAttributes().build());
        event.put(ModEntities.UNSAID.get(), com.ayodehi.thehush.entity.UnsaidEntity.createAttributes().build());
    }

    private void onCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ModItems.AI_VILLAGER_SPAWN_EGG);
            event.accept(ModItems.PILGRIM_SPAWN_EGG);
            event.accept(ModItems.WICK_SPAWN_EGG);
            event.accept(ModItems.ECHO_SPAWN_EGG);
            event.accept(ModItems.UNSAID_SPAWN_EGG);
        }
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.SNUFFED_TORCH);
        }
    }

    /** Fired when the config file changes on disk, including saves from the in-game config screen. */
    private void onConfigReloaded(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != Config.SPEC || ServerLifecycleHooks.getCurrentServer() == null) {
            return;
        }
        LlmService.get().configure();
        com.ayodehi.thehush.voice.VoiceService.get().configure();
        LOGGER.info("The Hush config reloaded: provider {}, voice {}", LlmService.get().describe(), com.ayodehi.thehush.voice.VoiceService.get().describe());
    }

    /** Before any chunk (and any villager in it) loads: the personas and campaigns must already be there. */
    private void onServerAboutToStart(net.neoforged.neoforge.event.server.ServerAboutToStartEvent event) {
        PersonaRegistry.get().reload();
        CampaignRegistry.get().reload();
    }

    private void onServerStarting(ServerStartingEvent event) {
        Migration.migrateWorld(event.getServer());
        com.ayodehi.thehush.llm.UsageMeter.get().prices(() -> Config.INPUT_PRICE.get(), () -> Config.OUTPUT_PRICE.get());
        com.ayodehi.thehush.llm.UsageMeter.get().open(event.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve(MODID).resolve("usage.json"));
        PersonaRegistry.get().reload();
        CampaignRegistry.get().reload();
        LlmService.get().configure();
        com.ayodehi.thehush.voice.VoiceService.get().configure();
        LOGGER.info("The Hush ready: {} persona(s), provider {}, voice {}",
                PersonaRegistry.get().ids().size(), LlmService.get().describe(), com.ayodehi.thehush.voice.VoiceService.get().describe());
        com.ayodehi.thehush.debug.DebugBridge.get().start(event.getServer());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        com.ayodehi.thehush.debug.DebugBridge.get().stop();
        ConversationManager.get().clear();
        MemoryStore.clear();
        ReturnRegistry.get().clear();
        CampaignManager.get().shutdown(event.getServer());
        CampaignManager.get().clear();
        LlmService.get().shutdown();
        com.ayodehi.thehush.voice.VoiceService.get().shutdown();
        com.ayodehi.thehush.campaign.NightSilence.clear();
        com.ayodehi.thehush.campaign.Unseen.clear();
        com.ayodehi.thehush.llm.UsageMeter.get().close();
    }

    private void onServerTick(ServerTickEvent.Post event) {
        ReturnRegistry.get().tick(event.getServer());
        CampaignManager.get().tick(event.getServer());
        com.ayodehi.thehush.entity.WickSpawner.tick(event.getServer());
        com.ayodehi.thehush.entity.EchoHunts.tick(event.getServer());
        com.ayodehi.thehush.entity.UnsaidSpawner.tick(event.getServer());
        com.ayodehi.thehush.voice.VoiceService.get().tick();
        com.ayodehi.thehush.campaign.Avoidance.tick(event.getServer());
        com.ayodehi.thehush.campaign.Unseen.tick(event.getServer());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        TheHushCommands.register(event.getDispatcher());
    }

    private void onServerChat(ServerChatEvent event) {
        com.ayodehi.thehush.debug.DebugBridge.get().record("chat", event.getPlayer().getName().getString(), event.getRawText());
        com.ayodehi.thehush.entity.UnsaidEntity.onSomeoneSpoke(event.getPlayer());
        CampaignManager.get().onChat(event.getPlayer(), event.getRawText());
        if (ConversationManager.get().handleChat(event.getPlayer(), event.getRawText())) {
            event.setCanceled(true);
        }
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        ConversationManager.get().endSession(event.getEntity().getUUID());
        PlayerActivity.forget(event.getEntity().getUUID());
    }

    // ---- what the player is up to (villagers may comment) ----

    /** Fires when a broken block drops items, i.e. survival mining; creative breaking is ignored on purpose. */
    private void onBlockBroken(BlockDropsEvent event) {
        if (event.getState().is(Blocks.BELL)) Clues.onBellBroken(event);
        if (event.getBreaker() instanceof ServerPlayer sp) {
            String what = PlayerActivity.describeBroken(event.getState());
            if (what != null) PlayerActivity.record(sp.getUUID(), sp.level().getGameTime(), what);
        }
    }

    private void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            String what = PlayerActivity.describePlaced(event.getPlacedBlock());
            if (what != null) PlayerActivity.record(sp.getUUID(), sp.level().getGameTime(), what);
        }
    }

    private void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            event.getAdvancement().value().display().ifPresent(display ->
                    PlayerActivity.recordAchievement(sp.getUUID(), display.getTitle().getString(),
                            display.getDescription().getString()));
        }
    }
}
