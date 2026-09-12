package com.ayodehi.thehush.commands;

import com.ayodehi.thehush.campaign.CampaignEvents;
import com.ayodehi.thehush.campaign.CampaignManager;
import com.ayodehi.thehush.campaign.Quiet;
import com.ayodehi.thehush.entity.PilgrimEntity;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/** Campaign subcommands for the set pieces: pilgrim, quiet, hush. */
final class CampaignCommands {
    private CampaignCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> pilgrim() {
        return Commands.literal("pilgrim").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            Vec3 look = player.getLookAngle();
            BlockPos at = BlockPos.containing(player.position().add(-look.x * 6, 0, -look.z * 6));
            PilgrimEntity p = PilgrimEntity.place((ServerLevel) player.level(), at);
            ctx.getSource().sendSuccess(() -> Component.literal(p == null ? "No room for it there." : "Something is standing behind you."), false);
            return p == null ? 0 : 1;
        });
    }

    /** Play one of the unseen sounds for the caller now: footsteps, heartbeat, drop, knock, below, bell. */
    static LiteralArgumentBuilder<CommandSourceStack> unseen() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("unseen").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        for (com.ayodehi.thehush.campaign.Unseen.Kind kind : com.ayodehi.thehush.campaign.Unseen.Kind.values()) {
            root.then(Commands.literal(kind.name().toLowerCase(java.util.Locale.ROOT)).executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                // Nothing is said in chat: the point is not to know what is coming. The log has it.
                String result = com.ayodehi.thehush.campaign.Unseen.playNow(ctx.getSource().getServer(), kind, player);
                com.ayodehi.thehush.TheHushMod.LOGGER.info("Unseen ({}): {}", kind.name().toLowerCase(java.util.Locale.ROOT), result);
                return 1;
            }));
        }
        return root;
    }

    /** Send an Echo after the caller now (binding them as the chosen if nobody is); with "status", describe the live one. */
    static LiteralArgumentBuilder<CommandSourceStack> hunt() {
        return Commands.literal("hunt").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    var server = ctx.getSource().getServer();
                    var cm = CampaignManager.get();
                    if (!cm.isChosen(server, player)) cm.bind(server, player);
                    String result = com.ayodehi.thehush.entity.EchoHunts.sendNow(server, player);
                    ctx.getSource().sendSuccess(() -> Component.literal(result), false);
                    return 1;
                })
                .then(Commands.literal("status").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(com.ayodehi.thehush.entity.EchoHunts.describe(ctx.getSource().getServer())), false);
                    return 1;
                }));
    }

    /** Bring back anyone waiting to return from death, right now. */
    static LiteralArgumentBuilder<CommandSourceStack> returnNow() {
        return Commands.literal("return").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            var registry = com.ayodehi.thehush.entity.ReturnRegistry.get();
            int pending = registry.pendingCount(ctx.getSource().getServer());
            int came = registry.forceReturn(ctx.getSource().getServer(), player);
            ctx.getSource().sendSuccess(() -> Component.literal(pending == 0 ? "Nobody is waiting to return."
                    : came + " of " + pending + " returned; see the log for any that could not."), false);
            return came;
        });
    }

    /** Re-run the frame search and pedestal build for the city the caller stands in. */
    static LiteralArgumentBuilder<CommandSourceStack> frame() {
        return Commands.literal("frame").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            var server = ctx.getSource().getServer();
            var cm = CampaignManager.get();
            if (!cm.isChosen(server, player)) cm.bind(server, player);
            String result = CampaignEvents.findFrameAndStockIt(server, cm, player, cm.state(server));
            ctx.getSource().sendSuccess(() -> Component.literal(result), false);
            return 1;
        });
    }

    /** Replay the opening storm and arrival. */
    static LiteralArgumentBuilder<CommandSourceStack> arrival() {
        return Commands.literal("arrival").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
            String result = com.ayodehi.thehush.campaign.Arrival.replay(ctx.getSource().getServer());
            ctx.getSource().sendSuccess(() -> Component.literal(result), false);
            return 1;
        });
    }

    static LiteralArgumentBuilder<CommandSourceStack> quiet() {
        return Commands.literal("quiet").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            var server = ctx.getSource().getServer();
            var cm = CampaignManager.get();
            var s = cm.state(server);
            if (!s.started()) {
                ctx.getSource().sendFailure(Component.literal("Start the campaign first (talk to the Traveller or use campaign stage)."));
                return 0;
            }
            if (!cm.isChosen(server, player)) cm.bind(server, player);
            Quiet.enter(server, player, cm.traveller(server, player), java.util.List.of(), s);
            ctx.getSource().sendSuccess(() -> Component.literal("Crossed."), false);
            return 1;
        });
    }

    static LiteralArgumentBuilder<CommandSourceStack> hush() {
        return Commands.literal("hush").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("start").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    var server = ctx.getSource().getServer();
                    var cm = CampaignManager.get();
                    var s = cm.state(server);
                    if (!cm.isChosen(server, player)) cm.bind(server, player);
                    ServerLevel quiet = Quiet.level(server);
                    if (quiet == null) {
                        ctx.getSource().sendFailure(Component.literal("The Quiet dimension is not loaded."));
                        return 0;
                    }
                    if (!Quiet.isQuiet(player.level())) Quiet.enter(server, player, cm.traveller(server, player), java.util.List.of(), s);
                    BlockPos in = new BlockPos(0, Quiet.THROAT.minY(), 2);
                    player.teleportTo(quiet, in.getX() + 0.5, in.getY(), in.getZ() + 0.5, Set.<Relative>of(), 0F, 0F, true);
                    var t = cm.traveller(server, player);
                    if (t != null) t.teleportTo(quiet, in.getX() + 1.5, in.getY(), in.getZ() + 0.5, Set.<Relative>of(), 0F, 0F, false);
                    CampaignEvents.hush().start(server, s);
                    ctx.getSource().sendSuccess(() -> Component.literal("The Hush is listening."), false);
                    return 1;
                }))
                .then(Commands.literal("status").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("Hush: " + CampaignEvents.hush().describe()), false);
                    return 1;
                }))
                .then(Commands.literal("reset").executes(ctx -> {
                    CampaignEvents.hush().reset(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(() -> Component.literal("The chamber is quiet again."), false);
                    return 1;
                }));
    }
}
