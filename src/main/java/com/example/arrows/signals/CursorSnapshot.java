package com.example.arrows.signals;

import java.util.List;

/**
 * Immutable snapshot of all player presence and cursor positions.
 * Published to a SharedValueSignal — separate from the game state signal
 * so cursor updates don't trigger board re-renders.
 */
public record CursorSnapshot(List<PlayerPresence> players) {

    public record PlayerPresence(
            String playerId,
            String name,
            String color,
            String initials,
            int cursorRow,   // -1 if mouse is off the board
            int cursorCol    // -1 if mouse is off the board
    ) {
        public boolean isOnBoard() {
            return cursorRow >= 0 && cursorCol >= 0;
        }
    }
}
