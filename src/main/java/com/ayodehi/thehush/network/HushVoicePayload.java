package com.ayodehi.thehush.network;

import com.ayodehi.thehush.TheHushMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server to client: one chunk of a spoken line. utterance ties chunks together; seq/total order them;
 * entityId is who is speaking (the sound follows them); gain and range shape how it is heard.
 */
public record HushVoicePayload(int utterance, int entityId, int sampleRate, int seq, int total, float gain, float range, byte[] data)
        implements CustomPacketPayload {
    public static final Type<HushVoicePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(TheHushMod.MODID, "voice"));
    public static final StreamCodec<ByteBuf, HushVoicePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, HushVoicePayload::utterance,
            ByteBufCodecs.VAR_INT, HushVoicePayload::entityId,
            ByteBufCodecs.VAR_INT, HushVoicePayload::sampleRate,
            ByteBufCodecs.VAR_INT, HushVoicePayload::seq,
            ByteBufCodecs.VAR_INT, HushVoicePayload::total,
            ByteBufCodecs.FLOAT, HushVoicePayload::gain,
            ByteBufCodecs.FLOAT, HushVoicePayload::range,
            ByteBufCodecs.BYTE_ARRAY, HushVoicePayload::data,
            HushVoicePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
