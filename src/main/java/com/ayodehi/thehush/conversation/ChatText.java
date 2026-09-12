package com.ayodehi.thehush.conversation;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns coordinates in villager speech into the same clickable links /locate prints. */
public final class ChatText {
    /** Matches "(x, y, z)" or "(x, ~, z)" with optional spaces, e.g. "(-7440, ~, 2992)". */
    private static final Pattern COORDS = Pattern.compile("\\(\\s*(-?\\d{1,7})\\s*,\\s*(~|-?\\d{1,4})\\s*,\\s*(-?\\d{1,7})\\s*\\)");

    private ChatText() {}

    /** Plain text with every coordinate triple replaced by a clickable [x, y, z]. */
    public static MutableComponent linkify(String text, ChatFormatting textColor) {
        MutableComponent out = Component.empty();
        Matcher m = COORDS.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                out.append(Component.literal(text.substring(last, m.start())).withStyle(textColor));
            }
            Integer y = m.group(2).equals("~") ? null : Integer.parseInt(m.group(2));
            out.append(coords(Integer.parseInt(m.group(1)), y, Integer.parseInt(m.group(3))));
            last = m.end();
        }
        if (last < text.length()) {
            out.append(Component.literal(text.substring(last)).withStyle(textColor));
        }
        return out;
    }

    public static boolean mentionsCoords(String text, int x, int z) {
        Matcher m = COORDS.matcher(text);
        while (m.find()) {
            if (Integer.parseInt(m.group(1)) == x && Integer.parseInt(m.group(3)) == z) return true;
        }
        return false;
    }

    /** Green [x, y, z] that suggests "/tp @s x y z" when clicked, like vanilla /locate. */
    public static MutableComponent coords(int x, @Nullable Integer y, int z) {
        String yText = y == null ? "~" : String.valueOf(y);
        return ComponentUtils.wrapInSquareBrackets(Component.translatable("chat.coordinates", x, yText, z))
                .withStyle(s -> s.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent.SuggestCommand("/tp @s " + x + " " + yText + " " + z))
                        .withHoverEvent(new HoverEvent.ShowText(Component.translatable("chat.coordinates.tooltip"))));
    }
}
