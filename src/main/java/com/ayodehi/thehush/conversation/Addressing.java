package com.ayodehi.thehush.conversation;

import java.util.Locale;

/** Does a chat line open by addressing a villager by name? Kept free of Minecraft types so it can be unit tested. */
public final class Addressing {
    private Addressing() {}

    /** "Traveller, ..." / "hey traveller" -> true. Tolerates misspellings by comparing the first five letters. */
    public static boolean addressedByName(String text, String name) {
        String[] words = text.toLowerCase(Locale.ROOT).split("[^a-z']+");
        int limit = Math.min(words.length, 5);
        for (String token : name.toLowerCase(Locale.ROOT).split("[^a-z']+")) {
            if (token.length() < 3 || token.equals("the")) continue;
            for (int i = 0; i < limit; i++) {
                String w = words[i];
                if (w.isEmpty()) continue;
                if (w.equals(token)) return true;
                if (w.length() >= 5 && token.length() >= 5 && w.regionMatches(0, token, 0, 5)) return true;
            }
        }
        return false;
    }
}
