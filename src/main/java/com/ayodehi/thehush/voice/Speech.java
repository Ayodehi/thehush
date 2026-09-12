package com.ayodehi.thehush.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What of a chat line is worth saying aloud, and how. A line may carry delivery cues in square brackets
 * ("[whispers] Not here.", "[low, urgent] Behind you."): a few words of letters, commas and spaces. They
 * are never shown in chat; ElevenLabs v3 reads them as direction, older models get them stripped and a
 * setting nudged instead. Kept free of Minecraft types so it can be unit tested.
 */
public final class Speech {
    private static final Pattern CUE = Pattern.compile("\\[([a-zA-Z][a-zA-Z ,'-]{1,30})\\]\\s*");
    private static final Pattern STAGE_DIRECTION = Pattern.compile("\\*[^*]{0,80}\\*");
    private static final Pattern COORDS = Pattern.compile("\\(?\\s*-?\\d+\\s*,\\s*(?:-?\\d+|~)\\s*,\\s*-?\\d+\\s*\\)?");
    private static final Pattern MARKUP = Pattern.compile("[`_#>]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private Speech() {}

    /** How a line wants to be delivered, read from its cues. */
    public enum Manner { PLAIN, WHISPER, SHOUT, SOFT, SHARP }

    public static Manner manner(List<String> cues) {
        Manner m = Manner.PLAIN;
        for (String c : cues) {
            String s = c.toLowerCase(Locale.ROOT);
            if (s.contains("whisper") || s.contains("under his breath") || s.contains("hushed")) return Manner.WHISPER;
            if (s.contains("shout") || s.contains("calls out") || s.contains("yell") || s.contains("loud")) return Manner.SHOUT;
            if (s.contains("quiet") || s.contains("low") || s.contains("soft") || s.contains("gentl") || s.contains("tired") || s.contains("sigh")) m = Manner.SOFT;
            else if (s.contains("sharp") || s.contains("urgent") || s.contains("afraid") || s.contains("fast") || s.contains("alarm")) m = Manner.SHARP;
        }
        return m;
    }

    /** The bracketed cues in a line, in order, without the brackets. */
    public static List<String> cues(String line) {
        List<String> out = new ArrayList<>();
        if (line == null) return out;
        Matcher m = CUE.matcher(line);
        while (m.find()) out.add(m.group(1).trim());
        return out;
    }

    /** The line as the player should read it: cues removed, everything else as written. */
    public static String shown(String line) {
        if (line == null) return "";
        return SPACES.matcher(CUE.matcher(line).replaceAll("")).replaceAll(" ").trim();
    }

    /**
     * The line as it should be spoken: stage directions in asterisks and markdown gone, coordinate
     * triples said as "there", cues kept (for a model that reads them) or dropped. Empty when nothing is
     * left worth saying.
     */
    public static String spoken(String line, boolean keepCues) {
        if (line == null) return "";
        String s = keepCues ? line : CUE.matcher(line).replaceAll("");
        s = STAGE_DIRECTION.matcher(s).replaceAll(" ");
        s = COORDS.matcher(s).replaceAll("there");
        s = MARKUP.matcher(s).replaceAll("");
        s = SPACES.matcher(s).replaceAll(" ").trim();
        String bare = keepCues ? CUE.matcher(s).replaceAll("") : s;
        if (bare.replaceAll("[.\\u2026\\s]", "").isEmpty()) return "";
        return s;
    }

    /** Backwards-compatible: spoken form without cues. */
    public static String clean(String line) {
        return spoken(line, false);
    }

    /** Put a cue at the front unless the line already carries one. */
    public static String withCue(String line, String cue) {
        if (line == null) return "";
        if (CUE.matcher(line).find()) return line;
        return "[" + cue + "] " + line;
    }
}
