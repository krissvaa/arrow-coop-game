package com.example.arrows.signals;

import com.vaadin.flow.signals.shared.SharedValueSignal;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Singleton holding shared cursor/presence state for all players.
 * Follows the same pattern as {@link GameService}: mutable internal state
 * guarded by {@code synchronized}, published as an immutable snapshot
 * to a {@link SharedValueSignal}.
 */
@Component
public class CursorService {

    private static final String[] ADJECTIVES = {
            "Jungle", "Cosmic", "Turbo", "Sneaky", "Fuzzy", "Mighty",
            "Dizzy", "Wacky", "Jolly", "Zippy", "Groovy", "Bouncy"
    };
    private static final String[] NOUNS = {
            "Jim", "Rex", "Pip", "Ace", "Dot", "Max",
            "Panda", "Fox", "Otter", "Gecko", "Moose", "Koala"
    };
    private static final String[] PALETTE = {
            "#ff6b6b", "#4ecdc4", "#ffe66d", "#a29bfe",
            "#fd79a8", "#00cec9", "#ffeaa7", "#6c5ce7"
    };
    private static final AtomicInteger colorCounter = new AtomicInteger();

    private final SharedValueSignal<CursorSnapshot> cursorState =
            new SharedValueSignal<>(CursorSnapshot.class);

    // Mutable player entries, guarded by synchronized methods
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private record Entry(String playerId, String name, String color, String initials,
                          int cursorRow, int cursorCol) {}

    public CursorService() {
        publishSnapshot();
    }

    public SharedValueSignal<CursorSnapshot> cursorState() {
        return cursorState;
    }

    // --- Static utilities ---

    public static String nextColor() {
        return PALETTE[Math.floorMod(colorCounter.getAndIncrement(), PALETTE.length)];
    }

    public static String generateName() {
        var rng = ThreadLocalRandom.current();
        return ADJECTIVES[rng.nextInt(ADJECTIVES.length)] + " " + NOUNS[rng.nextInt(NOUNS.length)];
    }

    private static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.split(" ")) {
            if (!part.isEmpty()) sb.append(Character.toUpperCase(part.charAt(0)));
        }
        return sb.toString();
    }

    // --- Mutations ---

    public synchronized void addPlayer(String playerId, String name, String color) {
        entries.put(playerId, new Entry(playerId, name, color, initials(name), -1, -1));
        publishSnapshot();
    }

    public synchronized void removePlayer(String playerId) {
        entries.remove(playerId);
        publishSnapshot();
    }

    public synchronized void updateCursor(String playerId, int row, int col) {
        Entry e = entries.get(playerId);
        if (e == null) return;
        entries.put(playerId, new Entry(e.playerId, e.name, e.color, e.initials, row, col));
        publishSnapshot();
    }

    public synchronized void removeCursor(String playerId) {
        Entry e = entries.get(playerId);
        if (e == null) return;
        if (e.cursorRow < 0) return; // already off board
        entries.put(playerId, new Entry(e.playerId, e.name, e.color, e.initials, -1, -1));
        publishSnapshot();
    }

    private void publishSnapshot() {
        var players = entries.values().stream()
                .map(e -> new CursorSnapshot.PlayerPresence(
                        e.playerId, e.name, e.color, e.initials, e.cursorRow, e.cursorCol))
                .toList();
        cursorState.set(new CursorSnapshot(players));
    }
}
