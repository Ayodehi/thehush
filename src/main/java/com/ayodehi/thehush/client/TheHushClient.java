package com.ayodehi.thehush.client;

import com.ayodehi.thehush.ModEntities;
import com.ayodehi.thehush.TheHushMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import com.ayodehi.thehush.network.HushSilencePayload;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only wiring: villager renderer with per-persona skins, plus the in-game config screen (Mods > Villager AI > Config). */
@Mod(value = TheHushMod.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = TheHushMod.MODID, value = Dist.CLIENT)
public class TheHushClient {

    public TheHushClient(ModContainer container) {
        // NeoForge generates a settings screen from Config.SPEC; the API key and model can be set there.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(HushSilencePayload.TYPE, (payload, context) -> SilenceClient.apply(payload.on()));
        event.register(com.ayodehi.thehush.network.HushVoicePayload.TYPE, (payload, context) -> VoiceClient.receive(payload));
        event.register(com.ayodehi.thehush.network.HushDropPayload.TYPE, (payload, context) -> SilenceClient.drop(payload.ticks()));
    }

    @SubscribeEvent
    static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        VoiceClient.tick();
        SilenceClient.tick();
    }

    @SubscribeEvent
    static void onLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        VoiceClient.clear();
    }

    /** The compass clue: vanilla compass and recovery compass definitions are overridden to read this needle. */
    @SubscribeEvent
    static void onRegisterNeedles(net.neoforged.neoforge.client.event.RegisterRangeSelectItemModelPropertyEvent event) {
        event.register(net.minecraft.resources.Identifier.fromNamespaceAndPath(TheHushMod.MODID, "compass_angle"), CompassTell.MAP_CODEC);
    }

    @SubscribeEvent
    static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(TravellerModel.LAYER, TravellerModel::createBodyLayer);
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.AI_VILLAGER.get(), TravellerRenderer::new);
        event.registerEntityRenderer(ModEntities.PILGRIM.get(), AiVillagerRenderer::new);
        event.registerEntityRenderer(ModEntities.WICK.get(), WickRenderer::new);
        event.registerEntityRenderer(ModEntities.ECHO.get(), EchoRenderer::new);
        event.registerEntityRenderer(ModEntities.UNSAID.get(), UnsaidRenderer::new);
    }
}
