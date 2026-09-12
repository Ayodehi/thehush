package com.ayodehi.thehush.voice;

/** Why a line could not be voiced, sorted into the few kinds that call for different handling. */
public class VoiceException extends RuntimeException {
    public enum Kind {
        /** The account has no characters left this period. Stop asking until it resets. */
        QUOTA,
        /** The key is wrong, missing, or blocked. Stop asking until the config changes. */
        AUTH,
        /** Too many requests right now; try again shortly. */
        RATE_LIMIT,
        /** The voice or model does not exist for this account. */
        VOICE,
        /** Anything else: network, server errors, odd replies. */
        OTHER
    }

    private final Kind kind;
    /** When the quota resets (epoch seconds), or -1 if not known. */
    private final long resetEpochSeconds;

    public VoiceException(Kind kind, String message) {
        this(kind, message, -1);
    }

    public VoiceException(Kind kind, String message, long resetEpochSeconds) {
        super(message);
        this.kind = kind;
        this.resetEpochSeconds = resetEpochSeconds;
    }

    public Kind kind() {
        return kind;
    }

    public long resetEpochSeconds() {
        return resetEpochSeconds;
    }
}
