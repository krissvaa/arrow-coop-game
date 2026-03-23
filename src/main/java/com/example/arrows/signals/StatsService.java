package com.example.arrows.signals;

import com.vaadin.flow.signals.shared.SharedValueSignal;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Singleton holding per-player statistics (correct moves, failed moves,
 * levels cleared). Follows the same pattern as {@link CursorService}:
 * mutable internal state guarded by {@code synchronized}, published as
 * an immutable snapshot to a {@link SharedValueSignal}.
 */
@Component
public class StatsService {

    private final SharedValueSignal<StatsSnapshot> statsState =
            new SharedValueSignal<>(StatsSnapshot.class);

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private static class Entry {
        final String playerId;
        int correctMoves;
        int failedMoves;
        int levelsCleared;

        Entry(String playerId) {
            this.playerId = playerId;
        }
    }

    public StatsService() {
        publishSnapshot();
    }

    public SharedValueSignal<StatsSnapshot> statsState() {
        return statsState;
    }

    public synchronized void addPlayer(String playerId) {
        entries.putIfAbsent(playerId, new Entry(playerId));
        publishSnapshot();
    }

    public synchronized void removePlayer(String playerId) {
        entries.remove(playerId);
        publishSnapshot();
    }

    public synchronized void recordCorrectMove(String playerId) {
        Entry e = entries.get(playerId);
        if (e == null) return;
        e.correctMoves++;
        publishSnapshot();
    }

    public synchronized void recordFailedMove(String playerId) {
        Entry e = entries.get(playerId);
        if (e == null) return;
        e.failedMoves++;
        publishSnapshot();
    }

    public synchronized void recordLevelCleared(String playerId) {
        Entry e = entries.get(playerId);
        if (e == null) return;
        e.levelsCleared++;
        publishSnapshot();
    }

    private void publishSnapshot() {
        var players = entries.values().stream()
                .map(e -> new StatsSnapshot.PlayerStats(
                        e.playerId, e.correctMoves, e.failedMoves, e.levelsCleared))
                .toList();
        statsState.set(new StatsSnapshot(players));
    }
}
