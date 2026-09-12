package com.ayodehi.thehush.tools;

import com.ayodehi.thehush.llm.ToolSpec;
import com.google.gson.JsonObject;

/** A capability the villager can invoke. run() executes on the server thread and returns plain text for the model. */
public interface NpcTool {
    ToolSpec spec();

    String run(ToolContext ctx, JsonObject input);
}
