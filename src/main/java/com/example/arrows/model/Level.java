package com.example.arrows.model;

import java.util.List;
import java.util.stream.Collectors;

public class Level {
    private final String id;
    private final String title;
    private final int gridSize;
    private final List<Arrow> arrows;
    private final List<String> solutionOrder;
    private final boolean[][] playableMask; // null = all cells playable

    public Level(String id, String title, int gridSize, List<Arrow> arrows,
                 List<String> solutionOrder, boolean[][] playableMask) {
        this.id = id;
        this.title = title;
        this.gridSize = gridSize;
        this.arrows = arrows;
        this.solutionOrder = solutionOrder;
        this.playableMask = playableMask;
    }

    public Level(String id, String title, int gridSize, List<Arrow> arrows,
                 List<String> solutionOrder) {
        this(id, title, gridSize, arrows, solutionOrder, null);
    }

    public Level deepCopy() {
        boolean[][] maskCopy = null;
        if (playableMask != null) {
            maskCopy = new boolean[gridSize][gridSize];
            for (int r = 0; r < gridSize; r++) {
                System.arraycopy(playableMask[r], 0, maskCopy[r], 0, gridSize);
            }
        }
        return new Level(
            id, title, gridSize,
            arrows.stream().map(Arrow::deepCopy).collect(Collectors.toList()),
            solutionOrder != null ? List.copyOf(solutionOrder) : null,
            maskCopy
        );
    }

    public boolean isPlayable(int row, int col) {
        if (playableMask == null)
            return row >= 0 && row < gridSize && col >= 0 && col < gridSize;
        return row >= 0 && row < gridSize && col >= 0 && col < gridSize
                && playableMask[row][col];
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public int getGridSize() { return gridSize; }
    public List<Arrow> getArrows() { return arrows; }
    public List<String> getSolutionOrder() { return solutionOrder; }
    public boolean[][] getPlayableMask() { return playableMask; }
}
