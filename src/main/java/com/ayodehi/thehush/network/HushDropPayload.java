package com.ayodehi.thehush.network;

import com.ayodehi.thehush.TheHushMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server to client: every sound drops away for this many ticks, then comes back. The Hush, passing close. */
public record HushDropPayload(int ticks) implements CustomPacketPayload {
    public static final Type<HushDropPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TheHushMod.MODID, "drop"));
    public static final StreamCodec<ByteBuf, HushDropPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, HushDropPayload::ticks, HushDropPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
