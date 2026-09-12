package com.ayodehi.thehush.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Reads a RIFF/WAVE file into 16-bit mono PCM: any channel count, 8/16/24/32-bit integer or 32-bit float. */
public final class Wav {
    private Wav() {}

    public static boolean isWav(byte[] data) {
        return data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E';
    }

    public static VoiceProvider.Audio decode(byte[] data) {
        if (!isWav(data)) throw new IllegalArgumentException("not a WAV file");
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        b.position(12);
        int format = 1, channels = 1, rate = 24000, bits = 16;
        int dataStart = -1, dataLen = 0;
        while (b.remaining() >= 8) {
            int id = b.getInt();
            int len = b.getInt();
            int start = b.position();
            if (id == 0x20746d66) { // "fmt "
                format = b.getShort() & 0xffff;
                channels = b.getShort() & 0xffff;
                rate = b.getInt();
                b.getInt(); // byte rate
                b.getShort(); // block align
                bits = b.getShort() & 0xffff;
                if (format == 0xFFFE && len >= 26) { // WAVE_FORMAT_EXTENSIBLE: the real format is in the sub-format GUID
                    b.position(start + 24);
                    format = b.getShort() & 0xffff;
                }
            } else if (id == 0x61746164) { // "data"
                dataStart = start;
                dataLen = Math.min(len < 0 ? Integer.MAX_VALUE : len, data.length - start);
                break;
            }
            int next = start + len + (len & 1);
            if (next > data.length || next < start) break;
            b.position(next);
        }
        if (dataStart < 0) throw new IllegalArgumentException("WAV has no data chunk");
        if (channels < 1) channels = 1;
        int bytesPerSample = Math.max(1, bits / 8);
        int frames = dataLen / (bytesPerSample * channels);
        byte[] out = new byte[frames * 2];
        ByteBuffer in = ByteBuffer.wrap(data, dataStart, dataLen).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer o = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (int f = 0; f < frames; f++) {
            double sum = 0;
            for (int c = 0; c < channels; c++) sum += sample(in, format, bits);
            long v = Math.round(sum / channels * 32768.0); // 16-bit input comes back exactly; floats and 8/24/32-bit are scaled
            o.putShort((short) Math.max(-32768, Math.min(32767, v)));
        }
        return new VoiceProvider.Audio(out, rate);
    }

    /** One sample as -1..1. */
    private static double sample(ByteBuffer in, int format, int bits) {
        if (format == 3) { // IEEE float
            return bits == 64 ? in.getDouble() : in.getFloat();
        }
        return switch (bits) {
            case 8 -> ((in.get() & 0xff) - 128) / 128.0;
            case 16 -> in.getShort() / 32768.0;
            case 24 -> {
                int v = (in.get() & 0xff) | ((in.get() & 0xff) << 8) | (in.get() << 16);
                yield v / 8388608.0;
            }
            case 32 -> in.getInt() / 2147483648.0;
            default -> throw new IllegalArgumentException("unsupported WAV bit depth " + bits);
        };
    }
}
