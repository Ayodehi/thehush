package com.ayodehi.thehush.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Long-term notes one villager keeps: things players told it and events it chose to remember.
 * Stored as JSON next to the world save so it travels with the world and can be read or edited by hand.
 * Unlike the chat history (which is trimmed), these persist until the villager forgets them on purpose.
 */
public final class VillagerMemory {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final int MAX_NOTES_PER_PLAYER = 60;
    public static final int MAX_GENERAL_NOTES = 40;
    public static final int MAX_NOTE_LENGTH = 240;

    /** Serialized form. Field names are the JSON keys. */
    static final class Data {
        String villager = "";
        String persona = "";
        int nextId = 1;
        Map<String, PlayerNotes> players = new LinkedHashMap<>();
        List<Note> notes = new ArrayList<>();
        /** Notes stolen by a Pilgrim's touch; restored at the ending. */
        List<Note> taken = new ArrayList<>();
        /** Notes about players set aside at the ending, for a later playthrough to find. */
        Map<String, PlayerNotes> archive = new LinkedHashMap<>();
    }

    /** What the prompt may see. Defaults show everything. */
    public record View(boolean omitName, long hideNewerThanDay, boolean hidePlayerNotes) {
        public static final View ALL = new View(false, Long.MAX_VALUE, false);
    }

    static final class PlayerNotes {
        String name = "";
        List<Note> notes = new ArrayList<>();
    }

    public static final class Note {
        public int id;
        public long day;
        public String text = "";
    }

    private final Path file;
    private final Data data;
    private boolean dirty;

    private VillagerMemory(Path file, Data data) {
        this.file = file;
        this.data = data;
    }

    static VillagerMemory load(Path file, UUID villagerId, String personaId) {
        Data data = null;
        if (Files.exists(file)) {
            try {
                data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            } catch (IOException | JsonSyntaxException e) {
                data = null; // unreadable: start fresh but never overwrite the bad file silently
                try {
                    Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
        if (data == null) data = new Data();
        if (data.players == null) data.players = new LinkedHashMap<>();
        if (data.notes == null) data.notes = new ArrayList<>();
        if (data.taken == null) data.taken = new ArrayList<>();
        if (data.archive == null) data.archive = new LinkedHashMap<>();
        data.villager = villagerId.toString();
        data.persona = personaId;
        return new VillagerMemory(file, data);
    }

    // ---- writing ----

    public synchronized Note rememberAboutPlayer(UUID playerId, String playerName, String text, long day) {
        PlayerNotes pn = data.players.computeIfAbsent(playerId.toString(), k -> new PlayerNotes());
        pn.name = playerName;
        Note n = newNote(text, day);
        pn.notes.add(n);
        while (pn.notes.size() > MAX_NOTES_PER_PLAYER) pn.notes.remove(0);
        save();
        return n;
    }

    public synchronized Note rememberGeneral(String text, long day) {
        Note n = newNote(text, day);
        data.notes.add(n);
        while (data.notes.size() > MAX_GENERAL_NOTES) data.notes.remove(0);
        save();
        return n;
    }

    public synchronized boolean forget(int id) {
        boolean removed = data.notes.removeIf(n -> n.id == id);
        for (PlayerNotes pn : data.players.values()) {
            removed |= pn.notes.removeIf(n -> n.id == id);
        }
        if (removed) save();
        return removed;
    }

    public synchronized void forgetEverything() {
        data.players.clear();
        data.notes.clear();
        save();
    }

    /** A Pilgrim's touch: one random note about the player is taken (kept aside, not destroyed). */
    public synchronized @Nullable Note takeRandomNoteAbout(UUID playerId, java.util.function.IntUnaryOperator randomBelow) {
        PlayerNotes pn = data.players.get(playerId.toString());
        if (pn == null || pn.notes.isEmpty()) return null;
        Note n = pn.notes.remove(randomBelow.applyAsInt(pn.notes.size()));
        n.text = playerId + "|" + n.text;
        data.taken.add(n);
        save();
        return n;
    }

    /** Give back everything the Pilgrims took. */
    public synchronized int restoreTaken() {
        int restored = 0;
        for (Note n : new ArrayList<>(data.taken)) {
            int bar = n.text.indexOf('|');
            if (bar < 0) continue;
            PlayerNotes pn = data.players.computeIfAbsent(n.text.substring(0, bar), k -> new PlayerNotes());
            n.text = n.text.substring(bar + 1);
            pn.notes.add(n);
            restored++;
        }
        data.taken.clear();
        if (restored > 0) save();
        return restored;
    }

    /** The ending: set aside everything about one player so he does not know them, without losing it. */
    public synchronized void archivePlayer(UUID playerId) {
        PlayerNotes pn = data.players.remove(playerId.toString());
        if (pn == null) return;
        PlayerNotes old = data.archive.get(playerId.toString());
        if (old == null) data.archive.put(playerId.toString(), pn);
        else old.notes.addAll(pn.notes);
        save();
    }

    public synchronized int archivedCount() {
        int n = 0;
        for (PlayerNotes pn : data.archive.values()) n += pn.notes.size();
        return n;
    }

    public synchronized int takenCount() {
        return data.taken.size();
    }

    private Note newNote(String text, long day) {
        Note n = new Note();
        n.id = data.nextId++;
        n.day = day;
        n.text = text.strip().length() > MAX_NOTE_LENGTH ? text.strip().substring(0, MAX_NOTE_LENGTH) : text.strip();
        return n;
    }

    // ---- reading ----

    public synchronized List<Note> notesAbout(UUID playerId) {
        PlayerNotes pn = data.players.get(playerId.toString());
        return pn == null ? List.of() : List.copyOf(pn.notes);
    }

    public synchronized List<Note> generalNotes() {
        return List.copyOf(data.notes);
    }

    public synchronized int size() {
        int n = data.notes.size();
        for (PlayerNotes pn : data.players.values()) n += pn.notes.size();
        return n;
    }

    /** The block appended to the system prompt. Empty string when there is nothing to recall. */
    public synchronized String promptSection(@Nullable UUID playerId, @Nullable String playerName) {
        return promptSection(playerId, playerName, View.ALL);
    }

    public synchronized String promptSection(@Nullable UUID playerId, @Nullable String playerName, View view) {
        StringBuilder sb = new StringBuilder();
        if (playerId != null && !view.hidePlayerNotes()) {
            List<Note> mine = new ArrayList<>();
            for (Note n : notesAbout(playerId)) {
                if (n.day > view.hideNewerThanDay()) continue;
                if (view.omitName() && NAME_NOTE.matcher(n.text).find()) continue; // a note about what to call them
                mine.add(n);
            }
            if (!mine.isEmpty()) {
                sb.append("\nWhat you remember about ").append(playerName == null || view.omitName() ? "this player" : playerName)
                  .append(" (from earlier meetings; note ids in brackets):\n");
                String known = data.players.containsKey(playerId.toString()) ? data.players.get(playerId.toString()).name : playerName;
                for (Note n : mine) {
                    String text = n.text;
                    if (view.omitName()) text = withoutName(text, known, playerName);
                    sb.append("- [").append(n.id).append("] day ").append(n.day).append(": ").append(text).append('\n');
                }
            }
        }
        if (!data.notes.isEmpty()) {
            sb.append("\nOther things you remember:\n");
            for (Note n : data.notes) sb.append("- [").append(n.id).append("] day ").append(n.day).append(": ").append(n.text).append('\n');
        }
        // Other players you have met, by name only, so you can mention them.
        List<String> others = new ArrayList<>();
        for (Map.Entry<String, PlayerNotes> e : data.players.entrySet()) {
            if (playerId != null && e.getKey().equals(playerId.toString())) continue;
            if (!e.getValue().name.isBlank()) others.add(e.getValue().name);
        }
        if (view.hidePlayerNotes()) others.clear();
        if (!others.isEmpty()) sb.append("\nOther travellers you have met: ").append(String.join(", ", others)).append('\n');
        return sb.toString();
    }

    /** A note that records what the player is called; hidden entirely while the name is forgotten. */
    private static final java.util.regex.Pattern NAME_NOTE =
            java.util.regex.Pattern.compile("(?i)\\b(called|call (him|her|them)|name[sd]?|goes by|known as)\\b");

    /** The player's known names replaced by "the player" in a note, as whole words. */
    static String withoutName(String text, @Nullable String... names) {
        String out = text;
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            out = out.replaceAll("(?i)\\b" + java.util.regex.Pattern.quote(name) + "('s)?\\b", "the player$1");
        }
        return out;
    }

    // ---- persistence ----

    private void save() {
        dirty = true;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException e) {
            com.ayodehi.thehush.TheHushMod.LOGGER.error("Could not save villager memory {}", file, e);
        }
    }

    public Path file() {
        return file;
    }
}
