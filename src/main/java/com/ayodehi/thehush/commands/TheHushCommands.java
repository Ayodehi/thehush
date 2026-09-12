package com.ayodehi.thehush.commands;

import com.ayodehi.thehush.Config;
import com.ayodehi.thehush.campaign.CampaignDefinition;
import com.ayodehi.thehush.campaign.CampaignManager;
import com.ayodehi.thehush.campaign.CampaignRegistry;
import com.ayodehi.thehush.campaign.CampaignStage;
import com.ayodehi.thehush.campaign.CampaignState;
import com.ayodehi.thehush.ModEntities;
import com.ayodehi.thehush.conversation.ConversationManager;
import com.ayodehi.thehush.entity.AiVillagerEntity;
import com.ayodehi.thehush.llm.LlmService;
import com.ayodehi.thehush.persona.Persona;
import com.ayodehi.thehush.persona.PersonaRegistry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;

/**
 * /hush spawn [persona]  - spawn a talking villager in front of you
 * /hush persona <id>     - change the persona of the villager you're talking to
 * /hush bye              - end your conversation
 * /hush forget           - wipe the memory of the villager you're talking to
 * /hush status           - provider, personas, memory size
 * /hush reload           - reload persona files and re-read the API key
 * /hush usage [reset]    - API calls, tokens, and estimated cost for this session and the whole campaign
 * /hush campaign ...     - status | stage <id> | flag <name> | chosen <player> | reset | return | arrival | frame | pilgrim | hunt | snapshot | quiet | hush start|status|reset
 */
public final class TheHushCommands {
    private TheHushCommands() {}

    private static final SuggestionProvider<CommandSourceStack> PERSONA_IDS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(PersonaRegistry.get().ids(), builder);
    private static final SuggestionProvider<CommandSourceStack> STAGE_IDS = (ctx, builder) -> {
        CampaignDefinition d = CampaignManager.get().definition(ctx.getSource().getServer());
        if (d == null) d = CampaignRegistry.get().find(Config.DEFAULT_CAMPAIGN_FALLBACK);
        return SharedSuggestionProvider.suggest(d == null ? java.util.List.of() : d.stages.stream().map(st -> st.id).toList(), builder);
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = dispatcher.register(Commands.literal("hush")
                .then(Commands.literal("spawn")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> spawn(ctx, Config.DEFAULT_PERSONA.get()))
                        .then(Commands.argument("persona", StringArgumentType.word())
                                .suggests(PERSONA_IDS)
                                .executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "persona")))))
                .then(Commands.literal("persona")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("persona", StringArgumentType.word())
                                .suggests(PERSONA_IDS)
                                .executes(ctx -> setPersona(ctx, StringArgumentType.getString(ctx, "persona")))))
                .then(Commands.literal("bye").executes(TheHushCommands::bye))
                .then(Commands.literal("forget").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(TheHushCommands::forget))
                .then(Commands.literal("memories").executes(TheHushCommands::memories))
                .then(Commands.literal("status").executes(TheHushCommands::status))
                .then(Commands.literal("reload").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(TheHushCommands::reload))
                .then(Commands.literal("usage").executes(TheHushCommands::usage)
                        .then(Commands.literal("reset").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
                            com.ayodehi.thehush.llm.UsageMeter.get().resetCampaign();
                            ctx.getSource().sendSuccess(() -> Component.literal("Campaign usage ledger cleared."), false);
                            return 1;
                        })))
                .then(Commands.literal("campaign")
                        .then(Commands.literal("status").executes(TheHushCommands::campaignStatus))
                        .then(Commands.literal("stage")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.argument("stage", StringArgumentType.word())
                                        .suggests(STAGE_IDS)
                                        .executes(ctx -> campaignStage(ctx, StringArgumentType.getString(ctx, "stage")))))
                        .then(Commands.literal("flag")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.argument("flag", StringArgumentType.word())
                                        .executes(ctx -> campaignFlag(ctx, StringArgumentType.getString(ctx, "flag")))))
                        .then(Commands.literal("chosen")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> campaignChosen(ctx, EntityArgument.getPlayer(ctx, "player")))))
                        .then(Commands.literal("reset")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(TheHushCommands::campaignReset))
                        .then(CampaignCommands.returnNow())
                        .then(CampaignCommands.arrival())
                        .then(CampaignCommands.frame())
                        .then(CampaignCommands.pilgrim())
                        .then(CampaignCommands.hunt())
                        .then(CampaignCommands.unseen())
                        .then(Commands.literal("snapshot").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
                            String where = Snapshot.write(ctx.getSource().getPlayerOrException());
                            ctx.getSource().sendSuccess(() -> Component.literal("Snapshot written to " + where), false);
                            return 1;
                        }))
                        .then(CampaignCommands.quiet())
                        .then(CampaignCommands.hush())));
        dispatcher.register(Commands.literal("thehush").redirect(root)); // the old name still works

    }

    // ---- campaign ----

    private static int campaignStatus(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        CampaignManager cm = CampaignManager.get();
        CampaignState s = cm.state(server);
        StringBuilder sb = new StringBuilder();
        if (!s.started()) {
            sb.append("No campaign running yet. Talk to a campaign persona (the Traveller) to begin. Known: ")
              .append(String.join(", ", CampaignRegistry.get().ids()));
            if (s.stormUntilTick > 0 && !s.arrivalDone) sb.append("\nThe opening storm is blowing; he arrives when it passes.");
            else if (s.arrivalDone) sb.append("\nHe has arrived; find him and speak to him.");
        } else {
            CampaignDefinition d = cm.definition(server);
            CampaignStage st = cm.stage(server);
            sb.append("Campaign: ").append(d == null ? s.campaignId : d.title)
              .append("\nStage: ").append(s.stage).append(st == null ? "" : " (" + st.title + ")")
              .append(", entered day ").append(s.stageEnteredDay)
              .append("\nChosen: ").append(s.chosenName.isBlank() ? "nobody" : s.chosenName)
              .append("\nReturns from death: ").append(s.deaths)
              .append(s.forgotten.isEmpty() ? "" : ", forgotten: " + String.join(", ", s.forgotten))
              .append("\nFlags: ").append(s.flags.isEmpty() ? "none" : String.join(", ", s.flags));
            if (s.touches > 0) sb.append("\nPilgrim touches: ").append(s.touches);
            if (s.failedAttempts > 0) sb.append("\nFailed answers: ").append(s.failedAttempts);
            if (s.frame != null) sb.append("\nFrame: (").append(s.frame[0]).append(", ").append(s.frame[1]).append(", ").append(s.frame[2]).append(")");
            if (s.pedestal != null) sb.append("\nPedestal (bell goes here): (").append(s.pedestal[0]).append(", ").append(s.pedestal[1]).append(", ").append(s.pedestal[2]).append(")");
            if (s.strangerToPlayer) sb.append("\nHe does not know the chosen (the road has ended once).");
            sb.append("\nRoad so far: ");
            for (int i = 0; i < s.history.size(); i++) {
                if (i > 0) sb.append(" > ");
                sb.append(s.history.get(i).stage).append("@").append(s.history.get(i).day);
            }
            sb.append("\nFile: ").append(s.file() == null ? "(unsaved)" : s.file().toAbsolutePath().normalize());
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int campaignStage(CommandContext<CommandSourceStack> ctx, String stageId) {
        MinecraftServer server = ctx.getSource().getServer();
        CampaignManager cm = CampaignManager.get();
        CampaignState s = cm.state(server);
        CampaignDefinition d = cm.definition(server);
        if (d == null) {
            // Not started: start the bundled campaign with the caller as the chosen.
            d = CampaignRegistry.get().find(Config.DEFAULT_CAMPAIGN_FALLBACK);
            if (d == null || !(ctx.getSource().getEntity() instanceof ServerPlayer caller)) {
                ctx.getSource().sendFailure(Component.literal("No campaign is running; talk to the Traveller first."));
                return 0;
            }
            s.campaignId = d.id;
            cm.bind(server, caller);
        }
        CampaignStage st = d.stage(stageId);
        if (st == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown stage '" + stageId + "'. Stages: "
                    + String.join(", ", d.stages.stream().map(x -> x.id).toList())));
            return 0;
        }
        cm.enterStage(server, st, "command");
        ctx.getSource().sendSuccess(() -> Component.literal("Stage is now " + st.id + " (" + st.title + "). The entry beat will play when he is near the chosen."), false);
        return 1;
    }

    private static int campaignFlag(CommandContext<CommandSourceStack> ctx, String flag) {
        MinecraftServer server = ctx.getSource().getServer();
        boolean added = CampaignManager.get().setFlag(server, flag);
        ctx.getSource().sendSuccess(() -> Component.literal(added ? "Flag set: " + flag : "Flag already set: " + flag), false);
        return 1;
    }

    private static int campaignChosen(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        CampaignManager.get().bind(ctx.getSource().getServer(), player);
        ctx.getSource().sendSuccess(() -> Component.literal(player.getName().getString() + " is now the chosen."), false);
        return 1;
    }

    private static int campaignReset(CommandContext<CommandSourceStack> ctx) {
        CampaignManager.get().reset(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("Campaign reset. It begins again the next time someone talks to the Traveller."), false);
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, String personaId) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Persona persona = PersonaRegistry.get().find(personaId);
        if (persona == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown persona '" + personaId + "'. Known: "
                    + String.join(", ", PersonaRegistry.get().ids())));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        AiVillagerEntity villager = ModEntities.AI_VILLAGER.get().create(level, EntitySpawnReason.COMMAND);
        if (villager == null) {
            ctx.getSource().sendFailure(Component.literal("Could not create the villager."));
            return 0;
        }
        Vec3 look = player.getLookAngle();
        Vec3 pos = player.position().add(look.x * 2, 0, look.z * 2);
        villager.snapTo(pos.x, pos.y, pos.z, player.getYRot() + 180F, 0F);
        villager.setPersona(persona.id());
        level.addFreshEntity(villager);
        ctx.getSource().sendSuccess(() -> Component.literal("Spawned " + persona.name() + ". Speak to him in chat."), false);
        return 1;
    }

    private static int setPersona(CommandContext<CommandSourceStack> ctx, String personaId) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        AiVillagerEntity villager = ConversationManager.get().villagerFor(player);
        if (villager == null) {
            ctx.getSource().sendFailure(Component.literal("Right-click a villager to start talking to it first."));
            return 0;
        }
        if (PersonaRegistry.get().find(personaId) == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown persona '" + personaId + "'."));
            return 0;
        }
        villager.setPersona(personaId);
        ctx.getSource().sendSuccess(() -> Component.literal("They are now " + villager.speakerName() + "."), false);
        return 1;
    }

    private static int bye(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ConversationManager.get().endSession(ctx.getSource().getPlayerOrException());
        return 1;
    }

    private static int forget(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        AiVillagerEntity villager = ConversationManager.get().villagerFor(player);
        if (villager == null) {
            ctx.getSource().sendFailure(Component.literal("You're not talking to anyone."));
            return 0;
        }
        villager.forgetEverything();
        villager.memory().forgetEverything();
        ctx.getSource().sendSuccess(() -> Component.literal(villager.speakerName() + " has forgotten everything, notes included."), false);
        return 1;
    }

    private static int memories(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        AiVillagerEntity villager = ConversationManager.get().villagerFor(player);
        if (villager == null) {
            ctx.getSource().sendFailure(Component.literal("You're not talking to anyone."));
            return 0;
        }
        var mine = villager.memory().notesAbout(player.getUUID());
        var general = villager.memory().generalNotes();
        var server = ctx.getSource().getServer();
        var cm = CampaignManager.get();
        long hideAfter = cm.takesPart(villager) && cm.isChosen(server, player) && cm.forgotten(server).contains("recent_days")
                ? villager.currentDay() - 1 : Long.MAX_VALUE;
        StringBuilder sb = new StringBuilder(villager.speakerName() + " remembers about you:");
        if (mine.isEmpty()) sb.append(" nothing yet.");
        for (var n : mine) sb.append("\n [").append(n.id).append("] day ").append(n.day).append(": ").append(n.text).append(n.day > hideAfter ? " (forgotten)" : "");
        if (villager.memory().takenCount() > 0) sb.append("\n (").append(villager.memory().takenCount()).append(" note(s) taken by the Pilgrims)");
        if (villager.memory().archivedCount() > 0) sb.append("\n (").append(villager.memory().archivedCount()).append(" note(s) from a road already walked)");
        if (!general.isEmpty()) {
            sb.append("\nOther notes:");
            for (var n : general) sb.append("\n [").append(n.id).append("] day ").append(n.day).append(": ").append(n.text);
        }
        sb.append("\nFile: ").append(villager.memory().file().toAbsolutePath().normalize());
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Provider: ").append(LlmService.get().describe()).append('\n');
        sb.append("Voice: ").append(com.ayodehi.thehush.voice.VoiceService.get().describe()).append('\n');
        sb.append("Personas: ").append(String.join(", ", PersonaRegistry.get().ids()))
          .append(" (").append(PersonaRegistry.get().directory()).append(")");
        if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
            AiVillagerEntity v = ConversationManager.get().villagerFor(player);
            if (v != null) {
                sb.append("\nTalking to: ").append(v.speakerName()).append(", remembers ")
                  .append(v.rememberedMessages()).append(" messages and ").append(v.memory().size()).append(" notes");
            }
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int usage(CommandContext<CommandSourceStack> ctx) {
        var meter = com.ayodehi.thehush.llm.UsageMeter.get();
        net.minecraft.network.chat.MutableComponent out = Component.literal("AI usage").withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD)
                .append(Component.literal("  " + LlmService.get().describe()).withStyle(net.minecraft.ChatFormatting.GRAY));
        out.append(usageBlock("This session", meter.session()));
        out.append(usageBlock("Whole campaign", meter.campaign()));
        String credits = com.ayodehi.thehush.voice.VoiceService.get().creditsLine();
        if (!credits.isEmpty()) out.append(Component.literal("\n" + credits).withStyle(net.minecraft.ChatFormatting.AQUA));
        out.append(Component.literal("\nEstimated from list prices; set llm.inputPricePerMTok / outputPricePerMTok if yours differ.")
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY, net.minecraft.ChatFormatting.ITALIC));
        ctx.getSource().sendSuccess(() -> out, false);
        return 1;
    }

    private static Component usageBlock(String title, com.ayodehi.thehush.llm.UsageMeter.Ledger l) {
        var gray = net.minecraft.ChatFormatting.GRAY;
        var white = net.minecraft.ChatFormatting.WHITE;
        net.minecraft.network.chat.MutableComponent b = Component.literal("\n" + title).withStyle(net.minecraft.ChatFormatting.YELLOW);
        b.append(Component.literal("\n  Calls ").withStyle(gray)).append(Component.literal(Long.toString(l.calls)).withStyle(white))
         .append(Component.literal("   Cost ").withStyle(gray))
         .append(Component.literal(com.ayodehi.thehush.llm.Pricing.dollars(l.cost)).withStyle(net.minecraft.ChatFormatting.GREEN));
        b.append(Component.literal("\n  Prompt ").withStyle(gray)).append(Component.literal(tokens(l.promptTokens())).withStyle(white))
         .append(Component.literal("   Output ").withStyle(gray)).append(Component.literal(tokens(l.output)).withStyle(white));
        b.append(Component.literal("\n  Cache read ").withStyle(gray)).append(Component.literal(tokens(l.cacheRead)).withStyle(white))
         .append(Component.literal("   Cache write ").withStyle(gray)).append(Component.literal(tokens(l.cacheWrite)).withStyle(white));
        if (l.voiceLines > 0) {
            b.append(Component.literal("\n  Voice ").withStyle(gray)).append(Component.literal(l.voiceLines + " lines, " + tokens(l.voiceChars) + " chars").withStyle(white))
             .append(Component.literal("   Cost ").withStyle(gray))
             .append(Component.literal(com.ayodehi.thehush.llm.Pricing.dollars(l.voiceCost)).withStyle(net.minecraft.ChatFormatting.GREEN));
        }
        if (l.byModel != null && l.byModel.size() > 1) {
            for (var e : l.byModel.entrySet()) {
                b.append(Component.literal("\n  " + e.getKey() + ": ").withStyle(net.minecraft.ChatFormatting.DARK_GRAY))
                 .append(Component.literal(e.getValue().calls + " calls, " + com.ayodehi.thehush.llm.Pricing.dollars(e.getValue().cost)).withStyle(gray));
            }
        }
        return b;
    }

    private static String tokens(long n) {
        if (n >= 1_000_000) return String.format("%.2fM", n / 1_000_000.0);
        if (n >= 10_000) return String.format("%.1fk", n / 1000.0);
        return String.format("%,d", n);
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        PersonaRegistry.get().reload();
        CampaignRegistry.get().reload();
        LlmService.get().configure();
        ctx.getSource().sendSuccess(() -> Component.literal("Reloaded. Provider: " + LlmService.get().describe()), false);
        return 1;
    }
}
