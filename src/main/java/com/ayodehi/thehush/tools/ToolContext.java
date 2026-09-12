package com.ayodehi.thehush.tools;

import com.ayodehi.thehush.entity.AiVillagerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** What a tool can see. Always handed over on the server thread. */
public record ToolContext(AiVillagerEntity npc, ServerPlayer player, ServerLevel level) {}
