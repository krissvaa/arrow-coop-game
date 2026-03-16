package com.example.arrows.model;

public enum Direction {
    UP(-1, 0, 270),
    DOWN(1, 0, 90),
    LEFT(0, -1, 180),
    RIGHT(0, 1, 0);

    private final int dRow;
    private final int dCol;
    private final int rotationDeg;

    Direction(int dRow, int dCol, int rotationDeg) {
        this.dRow = dRow;
        this.dCol = dCol;
        this.rotationDeg = rotationDeg;
    }

    public int dRow() { return dRow; }
    public int dCol() { return dCol; }
    public int rotationDeg() { return rotationDeg; }
}
