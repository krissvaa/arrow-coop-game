package com.example.arrows.signals;

import com.example.arrows.model.Direction;

import java.util.List;

/**
 * Immutable snapshot of the entire game state.
 * Published to a SharedValueSignal after every state change.
 * All sessions read from this snapshot via Signal.effect() or bindText().
 */
public record GameSnapshot(
        int currentLevelIndex,
        String levelId,
        String levelTitle,
        int gridSize,
        List<ArrowData> arrows,
        int moves,
        int hearts,
        boolean gameWon,
        boolean gameLost,
        int levelsCompleted,
        int totalLevels,
        MoveEvent lastMove,
        boolean[][] playableMask
) {
    /**
     * Immutable projection of an Arrow for the UI layer.
     */
    public record ArrowData(
            String id,
            String color,
            Direction headDirection,
            int[][] segments, // [segIndex][0=row, 1=col], tail to head
            boolean exited
    ) {}

    /**
     * Info about the last move, used for cross-session animation.
     */
    public record MoveEvent(
            String arrowId,
            MoveResult result,
            long timestamp,
            int collisionSteps
    ) {}

    public boolean isPlayable(int row, int col) {
        if (playableMask == null) return true;
        if (row < 0 || row >= gridSize || col < 0 || col >= gridSize) return false;
        return playableMask[row][col];
    }
}
