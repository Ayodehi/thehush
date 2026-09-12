package com.ayodehi.thehush.conversation;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A plain reading of what the player asked for, used as a safety net when the model says "I'll stay"
 * and forgets to call the tool that makes it true. Deliberately narrow: only wording that can hardly mean
 * anything else. Kept free of Minecraft types so it can be unit tested.
 */
public final class Intent {
    public enum Travel { NONE, STAY, COME }

    private static final Pattern STAY = Pattern.compile(
            "\\b(stay|wait|remain|stop)\\s+(here|there|put|back|behind|where you are)\\b"
            + "|\\bstop following\\b|\\bdon'?t follow\\b|\\bwait for me\\b|\\bstay (?:right )?here\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COME = Pattern.compile(
            "\\b(come|follow|travel|walk|go)\\s+(with me|along|on|inside|in|closer|over here|here|with us)\\b"
            + "|\\bfollow me\\b|\\bstay (close|near|with me|by me)\\b|\\bkeep up\\b|\\bcome\\b\\s*[.!,]?\\s*$",
            Pattern.CASE_INSENSITIVE);
    /** The player talking about themselves ("I'll wait here") is not an order. */
    private static final Pattern FIRST_PERSON = Pattern.compile(
            "\\b(i'?ll|i will|i'?m going to|i am going to|i'?m gonna|let me|i can|we'?ll|we will)\\s+(just\\s+)?(stay|wait|remain|stop)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATED_COME = Pattern.compile(
            "\\b(don'?t|do not|no need to|stop)\\s+(come|follow)", Pattern.CASE_INSENSITIVE);

    private static final Pattern COORDINATES = Pattern.compile(
            "\\bco-?ord(inate)?s?\\b|\\bxyz\\b|\\bx,? ?y,? ?(and )?z\\b|\\bexact (location|position|spot)\\b|\\bthe numbers\\b|\\bposition numbers\\b",
            Pattern.CASE_INSENSITIVE);

    private Intent() {}

    /** Did the player ask for coordinates in so many words? Only then do numbers go into chat. */
    public static boolean wantsCoordinates(String text) {
        return text != null && COORDINATES.matcher(text).find();
    }

    public static Travel travel(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
        if (t.isEmpty()) return Travel.NONE;
        if (NEGATED_COME.matcher(t).find()) return Travel.STAY;
        boolean stay = STAY.matcher(t).find() && !FIRST_PERSON.matcher(t).find();
        boolean come = COME.matcher(t).find();
        if (stay && !come) return Travel.STAY;
        if (come && !stay) return Travel.COME;
        return Travel.NONE;
    }
}
