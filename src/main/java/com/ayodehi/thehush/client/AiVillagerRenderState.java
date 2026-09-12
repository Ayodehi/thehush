package com.ayodehi.thehush.client;

import net.minecraft.client.renderer.entity.state.VillagerRenderState;

public class AiVillagerRenderState extends VillagerRenderState {
    /** Custom texture id, or empty for the vanilla villager look. */
    public String skin = "";
    /** Sat down beside his seat: legs forward, lowered to the ground. */
    public boolean sitting;
}
