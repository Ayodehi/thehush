package com.ayodehi.thehush.network;

import com.ayodehi.thehush.TheHushMod;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server to client: mute (or restore) ambient sound and music while the player is in the Quiet. */
public record HushSilencePayload(boolean on) implements CustomPacketPayload {
    public static final Type<HushSilencePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TheHushMod.MODID, "silence"));
    public static final StreamCodec<io.netty.buffer.ByteBuf, HushSilencePayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.BOOL, HushSilencePayload::on, HushSilencePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
