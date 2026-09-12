package com.ayodehi.thehush.llm;

import com.google.gson.JsonObject;

/** Declares a tool the model may call. inputSchema is a JSON Schema object. */
public record ToolSpec(String name, String description, JsonObject inputSchema) {

    /** Convenience for tools that take no arguments. */
    public static ToolSpec noArgs(String name, String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return new ToolSpec(name, description, schema);
    }
}
