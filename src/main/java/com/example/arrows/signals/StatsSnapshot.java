package com.example.arrows.signals;

import java.util.List;

/**
 * Immutable snapshot of per-player statistics.
 * Published to a SharedValueSignal following the same pattern as
 * {@link CursorSnapshot}.
 */
public record StatsSnapshot(List<PlayerStats> players) {

    public record PlayerStats(
            String playerId,
            int correctMoves,
            int failedMoves,
            int levelsCleared
    ) {}
}
