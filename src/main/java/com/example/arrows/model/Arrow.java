package com.example.arrows.model;

import java.util.ArrayList;
import java.util.List;

/**
 * An arrow occupying one or more cells on the grid.
 * Segments are ordered from tail to head.
 * The head (last segment) determines the exit direction.
 * Adjacent segments must be orthogonally adjacent, with at most
 * a 90-degree turn between consecutive segments.
 * When launched, the arrow "unravels" like a snake: the head leads in
 * headDirection, the body follows behind. Only the head's forward path
 * is checked for collisions (1 cell wide).
 */
public class Arrow {
    private final String id;
    private final String color;
    private final Direction headDirection;
    private List<int[]> segments;           // [row, col] from tail to head
    private final List<int[]> originalSegments;
    private boolean exited;

    /**
     * Multi-cell constructor.
     * @param segments list of [row,col] from tail to head
     */
    public Arrow(String id, String color, Direction headDirection, List<int[]> segments) {
        this.id = id;
        this.color = color;
        this.headDirection = headDirection;
        this.segments = copySegments(segments);
        this.originalSegments = copySegments(segments);
        this.exited = false;
    }

    /** Single-cell convenience constructor. */
    public Arrow(String id, String color, Direction headDirection, int row, int col) {
        this(id, color, headDirection, List.of(new int[]{row, col}));
    }

    public Arrow deepCopy() {
        Arrow copy = new Arrow(id, color, headDirection, originalSegments);
        copy.segments = copySegments(this.segments);
        copy.exited = this.exited;
        return copy;
    }

    // --- Segment accessors ---

    public List<int[]> getSegments() { return segments; }

    public int[] getHead() { return segments.get(segments.size() - 1); }
    public int getHeadRow() { return getHead()[0]; }
    public int getHeadCol() { return getHead()[1]; }

    public boolean isHead(int row, int col) {
        int[] h = getHead();
        return h[0] == row && h[1] == col;
    }

    public boolean occupies(int row, int col) {
        for (int[] seg : segments) {
            if (seg[0] == row && seg[1] == col) return true;
        }
        return false;
    }

    public int size() { return segments.size(); }

    // --- Backward-compatible accessors (head position) ---

    public int getRow() { return getHeadRow(); }
    public int getCol() { return getHeadCol(); }
    public Direction getDirection() { return headDirection; }

    // --- State ---

    public String getId() { return id; }
    public String getColor() { return color; }
    public Direction getHeadDirection() { return headDirection; }
    public boolean isExited() { return exited; }
    public void setExited(boolean exited) { this.exited = exited; }

    public void resetPosition() {
        this.segments = copySegments(originalSegments);
        this.exited = false;
    }

    private static List<int[]> copySegments(List<int[]> segs) {
        List<int[]> copy = new ArrayList<>(segs.size());
        for (int[] s : segs) {
            copy.add(new int[]{s[0], s[1]});
        }
        return copy;
    }
}
