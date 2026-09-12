package com.ayodehi.thehush.tools;

import com.ayodehi.thehush.llm.ContentPart;
import com.ayodehi.thehush.llm.ToolExecutor;
import com.ayodehi.thehush.entity.AiVillagerEntity;
import com.ayodehi.thehush.llm.ToolSpec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** All tools offered to every villager, plus the bridge that runs them on the server thread. */
public final class ToolRegistry {
    private static final ToolRegistry INSTANCE = new ToolRegistry();

    private final Map<String, NpcTool> tools = new LinkedHashMap<>();

    private ToolRegistry() {
        for (NpcTool t : WorldTools.all()) {
            tools.put(t.spec().name(), t);
        }
    }

    public static ToolRegistry get() {
        return INSTANCE;
    }

    public List<ToolSpec> specs() {
        return tools.values().stream().map(NpcTool::spec).toList();
    }

    /**
     * Builds an executor bound to one villager. Tool calls arrive on an LLM worker thread; each one is
     * hopped onto the server thread, run against whoever the villager is talking to right now, and the
     * result handed back as a future.
     */
    public ToolExecutor executorFor(AiVillagerEntity npc) {
        MinecraftServer server = npc.level().getServer();
        return call -> {
            CompletableFuture<ContentPart.ToolResult> future = new CompletableFuture<>();
            server.execute(() -> {
                NpcTool tool = tools.get(call.name());
                if (tool == null) {
                    future.complete(ToolExecutor.error(call, "Unknown tool: " + call.name()));
                    return;
                }
                ServerPlayer player = npc.talkingTo();
                if (!npc.isAlive() || player == null || player.isRemoved()) {
                    future.complete(ToolExecutor.error(call, "The conversation is no longer possible."));
                    return;
                }
                try {
                    ToolContext ctx = new ToolContext(npc, player, (ServerLevel) npc.level());
                    future.complete(ToolExecutor.ok(call, tool.run(ctx, ToolExecutor.inputOf(call))));
                } catch (Exception e) {
                    future.complete(ToolExecutor.error(call, "Tool failed: " + e.getMessage()));
                }
            });
            return future;
        };
    }
}
