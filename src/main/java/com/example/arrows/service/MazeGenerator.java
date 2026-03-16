package com.example.arrows.service;

import com.example.arrows.model.Arrow;
import com.example.arrows.model.Direction;
import com.example.arrows.model.Level;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Generates arrow maze levels in three styles:
 *   HEART  — irregular heart boundary, long horizontal corridors, easy
 *   MEDIUM — rectangular, mixed directions, medium difficulty
 *   DENSE  — large fully-packed rectangle, short corridors, hard
 *
 * All arrows are multi-cell (length >= 2).
 * Solvability is guaranteed by the greedy solver.
 */
@Service
public class MazeGenerator {

    private static final String[] COLORS = {
        "#4fc3f7", "#e57373", "#81c784", "#ffb74d", "#ce93d8",
        "#f06292", "#a5d6a7", "#ffd54f", "#90caf9", "#ef9a9a",
        "#80cbc4", "#fff176", "#b39ddb", "#ffab91", "#80deea"
    };

    public enum Style { HEART, MEDIUM, DENSE }

    private final LevelSolver solver;

    public MazeGenerator(LevelSolver solver) {
        this.solver = solver;
    }

    public Level generate(Style style, int levelNum, long seed) {
        for (int attempt = 0; attempt < 80; attempt++) {
            Random rng = new Random(seed + attempt * 37L);
            Level level = tryGenerate(style, levelNum, rng);
            if (level != null) return level;
        }
        // Fallback: simple solvable level
        return generateFallback(levelNum);
    }

    private Level tryGenerate(Style style, int levelNum, Random rng) {
        int gs = gridSize(style);
        boolean[][] mask = generateMask(style, gs);

        List<Arrow> arrows = fillWithArrows(mask, gs, style, rng);
        if (arrows.size() < 3) return null;

        Level level = new Level("L" + levelNum, styleName(style), gs, arrows, null, mask);
        Optional<List<String>> solution = solver.solveGreedy(level);
        if (solution.isPresent()) {
            return new Level("L" + levelNum, styleName(style), gs, arrows, solution.get(), mask);
        }
        return null;
    }

    // ====== Grid sizes per style ======

    private int gridSize(Style style) {
        return switch (style) {
            case HEART  -> 18;
            case MEDIUM -> 16;
            case DENSE  -> 20;
        };
    }

    private String styleName(Style style) {
        return switch (style) {
            case HEART  -> "Heart Maze";
            case MEDIUM -> "Arrow Grid";
            case DENSE  -> "Dense Maze";
        };
    }

    // ====== Mask generation ======

    private boolean[][] generateMask(Style style, int gs) {
        return switch (style) {
            case HEART  -> heartMask(gs);
            case MEDIUM -> rectangularMask(gs, 0.85);
            case DENSE  -> rectangularMask(gs, 1.0);
        };
    }

    private boolean[][] heartMask(int gs) {
        boolean[][] mask = new boolean[gs][gs];
        double cx = gs / 2.0;
        double cy = gs / 2.0 - gs * 0.05;
        double scale = gs / 2.4;

        for (int r = 0; r < gs; r++) {
            for (int c = 0; c < gs; c++) {
                double x = (c + 0.5 - cx) / scale;
                double y = (cy - r - 0.5) / scale;
                double eq = Math.pow(x * x + y * y - 1, 3) - x * x * y * y * y;
                mask[r][c] = eq <= 0.0;
            }
        }
        return mask;
    }

    private boolean[][] rectangularMask(int gs, double fillRatio) {
        boolean[][] mask = new boolean[gs][gs];
        int margin = fillRatio >= 1.0 ? 0 : 1;
        for (int r = margin; r < gs - margin; r++) {
            for (int c = margin; c < gs - margin; c++) {
                mask[r][c] = true;
            }
        }
        return mask;
    }

    // ====== Arrow filling ======

    /**
     * Fills a mask with snake-like bent arrows (pass 1) and straight arrows (pass 2).
     * Public so LevelGenerator can reuse the same filling strategy.
     */
    public List<Arrow> fillWithArrows(boolean[][] mask, int gs, Style style, Random rng) {
        return fillWithArrows(mask, gs, style, -1, rng);
    }

    /**
     * Fills with arrows, using density (0-100) to control arrow length.
     * 0 = sparse (long arrows, easy), 100 = dense (short arrows, hard).
     * density < 0 means use style defaults.
     */
    public List<Arrow> fillWithArrows(boolean[][] mask, int gs, Style style, int density, Random rng) {
        boolean[][] used = new boolean[gs][gs];
        List<Arrow> arrows = new ArrayList<>();
        int id = 0;

        // Collect unfilled mask cells, shuffle for variety
        List<int[]> cells = new ArrayList<>();
        for (int r = 0; r < gs; r++)
            for (int c = 0; c < gs; c++)
                if (mask[r][c]) cells.add(new int[]{r, c});
        Collections.shuffle(cells, rng);

        // --- Pass 1: Place snake-like bent arrows across the grid ---
        // With snake/unravel mechanic, bent arrows are the main content.
        // Try to fill as much as possible with bent arrows before falling back to straight.
        int bentTarget = switch (style) {
            case HEART  -> 200;  // effectively unlimited — fill as much as possible
            case MEDIUM -> 200;
            case DENSE  -> 200;
        };
        List<int[]> edgeCells = new ArrayList<>(cells);
        edgeCells.sort(Comparator.comparingInt(cell -> {
            int distEdge = Math.min(Math.min(cell[0], gs - 1 - cell[0]),
                    Math.min(cell[1], gs - 1 - cell[1]));
            return distEdge;
        }));
        int bentPlaced = 0;
        for (int[] cell : edgeCells) {
            if (bentPlaced >= bentTarget) break;
            int r = cell[0], c = cell[1];
            if (used[r][c]) continue;

            int maxTurns = 3 + rng.nextInt(50); // many turns — snake-like shapes
            int maxLen = maxLength(style, density, rng);
            List<int[]> segs = growBentArrow(r, c, mask, used, gs, maxTurns, maxLen, rng);
            if (segs != null && segs.size() >= 3) {
                // Pick the end whose natural body direction has the shortest exit path
                // AND does not cross through the arrow's own segments.
                int n = segs.size();
                Direction dirLast = directionFrom(segs.get(n - 2), segs.get(n - 1));
                Direction dirFirst = directionFrom(segs.get(1), segs.get(0));
                int[] last = segs.get(n - 1);
                int[] first = segs.get(0);
                boolean lastSafe = !exitPathCrossesSelf(last, dirLast, segs, gs);
                boolean firstSafe = !exitPathCrossesSelf(first, dirFirst, segs, gs);

                Direction dir;
                if (lastSafe && firstSafe) {
                    // Both clear — prefer the end closer to the edge
                    int exitLast = edgeDistInDir(last[0], last[1], dirLast, gs);
                    int exitFirst = edgeDistInDir(first[0], first[1], dirFirst, gs);
                    if (exitFirst < exitLast) {
                        Collections.reverse(segs);
                        dir = dirFirst;
                    } else {
                        dir = dirLast;
                    }
                } else if (lastSafe) {
                    dir = dirLast;
                } else if (firstSafe) {
                    Collections.reverse(segs);
                    dir = dirFirst;
                } else {
                    continue; // both ends self-intersect — skip this arrow
                }

                for (int[] s : segs) used[s[0]][s[1]] = true;
                String color = COLORS[id % COLORS.length];
                arrows.add(new Arrow("a" + (++id), color, dir, segs));
                bentPlaced++;
            }
        }

        // --- Pass 2: Fill remaining cells with straight arrows ---
        for (int[] cell : cells) {
            int r = cell[0], c = cell[1];
            if (used[r][c]) continue;

            double hBias = switch (style) {
                case HEART  -> 0.7;
                case MEDIUM -> 0.55;
                case DENSE  -> 0.5;
            };
            boolean tryHFirst = rng.nextDouble() < hBias;

            List<int[]> segs;
            if (tryHFirst) {
                segs = growHorizontal(r, c, mask, used, gs, style, density, rng);
                if (segs == null) segs = growVertical(r, c, mask, used, gs, style, density, rng);
            } else {
                segs = growVertical(r, c, mask, used, gs, style, density, rng);
                if (segs == null) segs = growHorizontal(r, c, mask, used, gs, style, density, rng);
            }

            boolean isHorizontal;
            Direction dir;
            if (segs != null && segs.size() >= 2) {
                isHorizontal = segs.get(0)[0] == segs.get(1)[0];
            } else {
                segs = List.of(new int[]{r, c});
                isHorizontal = true;
            }

            if (isHorizontal && segs.size() >= 2) {
                dir = pickHorizontalDirection(segs, gs, rng);
            } else if (!isHorizontal && segs.size() >= 2) {
                dir = pickVerticalDirection(segs, gs, rng);
            } else {
                dir = nearestEdgeDirection(r, c, gs);
            }

            segs = orientSegments(new ArrayList<>(segs), dir);

            for (int[] s : segs) used[s[0]][s[1]] = true;

            String color = COLORS[id % COLORS.length];
            arrows.add(new Arrow("a" + (++id), color, dir, segs));
        }

        return arrows;
    }

    private List<int[]> growHorizontal(int r, int c, boolean[][] mask, boolean[][] used,
                                        int gs, Style style, int density, Random rng) {
        int maxLen = maxLength(style, density, rng);
        List<int[]> segs = new ArrayList<>();
        segs.add(new int[]{r, c});

        // Grow right
        for (int cc = c + 1; cc < gs && segs.size() < maxLen; cc++) {
            if (mask[r][cc] && !used[r][cc]) segs.add(new int[]{r, cc});
            else break;
        }
        // Grow left
        for (int cc = c - 1; cc >= 0 && segs.size() < maxLen; cc--) {
            if (mask[r][cc] && !used[r][cc]) segs.add(0, new int[]{r, cc});
            else break;
        }

        if (segs.size() < 2) return null;
        segs.sort(Comparator.comparingInt(s -> s[1]));
        return segs;
    }

    private List<int[]> growVertical(int r, int c, boolean[][] mask, boolean[][] used,
                                      int gs, Style style, int density, Random rng) {
        int maxLen = maxLength(style, density, rng);
        List<int[]> segs = new ArrayList<>();
        segs.add(new int[]{r, c});

        // Grow down
        for (int rr = r + 1; rr < gs && segs.size() < maxLen; rr++) {
            if (mask[rr][c] && !used[rr][c]) segs.add(new int[]{rr, c});
            else break;
        }
        // Grow up
        for (int rr = r - 1; rr >= 0 && segs.size() < maxLen; rr--) {
            if (mask[rr][c] && !used[rr][c]) segs.add(0, new int[]{rr, c});
            else break;
        }

        if (segs.size() < 2) return null;
        segs.sort(Comparator.comparingInt(s -> s[0]));
        return segs;
    }

    /**
     * Grows an arrow with 1-3 turns, producing L, U, or S shapes.
     * Each leg grows in a random direction, then turns 90° for the next leg.
     */
    private List<int[]> growBentArrow(int r, int c, boolean[][] mask, boolean[][] used,
                                       int gs, int maxTurns, int maxLen, Random rng) {
        if (maxLen < 3) return null;

        // Try several random starting directions
        Direction[] allDirs = Direction.values();
        List<Direction> shuffled = new ArrayList<>(Arrays.asList(allDirs));
        Collections.shuffle(shuffled, rng);

        for (Direction startDir : shuffled) {
            List<int[]> segs = new ArrayList<>();
            Set<Long> own = new HashSet<>(); // track own cells to prevent self-crossing
            segs.add(new int[]{r, c});
            own.add(cellKey(r, c));
            Direction dir = startDir;
            int cr = r, cc = c;
            int remaining = maxLen - 1;
            int turnsLeft = maxTurns;

            while (remaining > 0) {
                // Grow current leg: 1-3 cells typically, longer if few turns remain
                int legMax = turnsLeft > 0
                        ? 1 + rng.nextInt(Math.max(1, Math.min(4, remaining / Math.min(turnsLeft + 1, 8))) + 1)
                        : remaining;
                int grew = 0;
                for (int i = 0; i < legMax && remaining > 0; i++) {
                    int nr = cr + dir.dRow(), nc = cc + dir.dCol();
                    if (nr < 0 || nr >= gs || nc < 0 || nc >= gs) break;
                    if (!mask[nr][nc] || used[nr][nc] || own.contains(cellKey(nr, nc))) break;
                    segs.add(new int[]{nr, nc});
                    own.add(cellKey(nr, nc));
                    cr = nr; cc = nc;
                    remaining--;
                    grew++;
                }

                if (turnsLeft <= 0 || remaining <= 0 || grew == 0) break;

                // Try to turn 90°
                Direction[] perps = perpendiculars(dir);
                Direction newDir = perps[rng.nextInt(2)];
                int nr = cr + newDir.dRow(), nc = cc + newDir.dCol();
                if (nr < 0 || nr >= gs || nc < 0 || nc >= gs
                        || !mask[nr][nc] || used[nr][nc] || own.contains(cellKey(nr, nc))) {
                    // Try other perpendicular
                    newDir = perps[0] == newDir ? perps[1] : perps[0];
                    nr = cr + newDir.dRow(); nc = cc + newDir.dCol();
                    if (nr < 0 || nr >= gs || nc < 0 || nc >= gs
                            || !mask[nr][nc] || used[nr][nc] || own.contains(cellKey(nr, nc))) {
                        break; // Can't turn — stop here
                    }
                }
                dir = newDir;
                turnsLeft--;
            }

            if (segs.size() >= 3) return segs;
        }
        return null;
    }

    private Direction[] perpendiculars(Direction d) {
        return switch (d) {
            case UP, DOWN -> new Direction[]{Direction.LEFT, Direction.RIGHT};
            case LEFT, RIGHT -> new Direction[]{Direction.UP, Direction.DOWN};
        };
    }

    private Direction directionFrom(int[] from, int[] to) {
        int dr = to[0] - from[0], dc = to[1] - from[1];
        if (dc > 0) return Direction.RIGHT;
        if (dc < 0) return Direction.LEFT;
        if (dr > 0) return Direction.DOWN;
        return Direction.UP;
    }

    private int maxLength(Style style, int density, Random rng) {
        if (density >= 0) {
            // density 0 (sparse/easy) = very long arrows (12-30)
            // density 100 (dense/expert) = very short arrows (2-4)
            double t = density / 100.0; // 0.0 = sparse, 1.0 = dense
            int minLen = (int) Math.round(12 - 10 * t);  // 12 → 2
            int range  = (int) Math.round(18 - 16 * t);  // 18 → 2
            minLen = Math.max(2, minLen);
            range = Math.max(2, range);
            return minLen + rng.nextInt(range);
        }
        return switch (style) {
            case HEART  -> 6 + rng.nextInt(15);   // 6-20
            case MEDIUM -> 5 + rng.nextInt(12);   // 5-16
            case DENSE  -> 4 + rng.nextInt(10);   // 4-13
        };
    }

    // ====== Direction assignment ======

    private Direction pickHorizontalDirection(List<int[]> segs, int gs, Random rng) {
        int leftCol = segs.get(0)[1];
        int rightCol = segs.get(segs.size() - 1)[1];
        int distLeft = leftCol;
        int distRight = gs - 1 - rightCol;

        // Prefer direction toward nearer edge (75% chance)
        if (distRight <= distLeft) {
            return rng.nextDouble() < 0.75 ? Direction.RIGHT : Direction.LEFT;
        } else {
            return rng.nextDouble() < 0.75 ? Direction.LEFT : Direction.RIGHT;
        }
    }

    private Direction pickVerticalDirection(List<int[]> segs, int gs, Random rng) {
        int topRow = segs.get(0)[0];
        int botRow = segs.get(segs.size() - 1)[0];
        int distTop = topRow;
        int distBot = gs - 1 - botRow;

        if (distBot <= distTop) {
            return rng.nextDouble() < 0.75 ? Direction.DOWN : Direction.UP;
        } else {
            return rng.nextDouble() < 0.75 ? Direction.UP : Direction.DOWN;
        }
    }

    private int edgeDist(int r, int c, int gs) {
        return Math.min(Math.min(r, gs - 1 - r), Math.min(c, gs - 1 - c));
    }

    private int edgeDistInDir(int r, int c, Direction dir, int gs) {
        return switch (dir) {
            case UP -> r;
            case DOWN -> gs - 1 - r;
            case LEFT -> c;
            case RIGHT -> gs - 1 - c;
        };
    }

    private Direction nearestEdgeDirection(int r, int c, int gs) {
        int dUp = r, dDown = gs - 1 - r, dLeft = c, dRight = gs - 1 - c;
        int min = Math.min(Math.min(dUp, dDown), Math.min(dLeft, dRight));
        if (min == dUp) return Direction.UP;
        if (min == dDown) return Direction.DOWN;
        if (min == dLeft) return Direction.LEFT;
        return Direction.RIGHT;
    }

    private List<int[]> orientSegments(List<int[]> segs, Direction dir) {
        // For the head to be last in the list:
        // RIGHT → head is rightmost (ascending col order) — already sorted
        // LEFT  → head is leftmost (descending col order) — reverse
        // DOWN  → head is bottommost (ascending row order) — already sorted
        // UP    → head is topmost (descending row order) — reverse
        if (dir == Direction.LEFT || dir == Direction.UP) {
            Collections.reverse(segs);
        }
        return segs;
    }

    // ====== Fallback ======

    private Level generateFallback(int levelNum) {
        // Simple 6x6 with 4 multi-cell arrows, always solvable
        List<Arrow> arrows = List.of(
            new Arrow("a1", COLORS[0], Direction.RIGHT,
                    List.of(new int[]{2, 0}, new int[]{2, 1}, new int[]{2, 2})),
            new Arrow("a2", COLORS[1], Direction.DOWN,
                    List.of(new int[]{0, 4}, new int[]{1, 4})),
            new Arrow("a3", COLORS[2], Direction.LEFT,
                    List.of(new int[]{4, 5}, new int[]{4, 4}, new int[]{4, 3})),
            new Arrow("a4", COLORS[3], Direction.UP,
                    List.of(new int[]{5, 1}, new int[]{4, 1}))
        );
        return new Level("L" + levelNum, "Escape", 6, arrows,
                List.of("a1", "a2", "a3", "a4"), null);
    }

    /**
     * Returns true if the head's exit path in the given direction passes
     * through any of the arrow's own segments (excluding the head itself).
     */
    static boolean exitPathCrossesSelf(int[] head, Direction dir,
                                        List<int[]> segments, int gridSize) {
        Set<Long> own = new HashSet<>();
        for (int[] seg : segments) {
            own.add(cellKey(seg[0], seg[1]));
        }
        own.remove(cellKey(head[0], head[1]));

        int r = head[0], c = head[1];
        while (true) {
            r += dir.dRow();
            c += dir.dCol();
            if (r < 0 || r >= gridSize || c < 0 || c >= gridSize) return false;
            if (own.contains(cellKey(r, c))) return true;
        }
    }

    private static long cellKey(int r, int c) {
        return (long) r * 10000 + c;
    }
}
