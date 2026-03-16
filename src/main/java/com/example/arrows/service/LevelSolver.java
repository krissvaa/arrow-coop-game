package com.example.arrows.service;

import com.example.arrows.model.Arrow;
import com.example.arrows.model.Direction;
import com.example.arrows.model.Level;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.List;

@Service
public class LevelSolver {

    /**
     * Solve using BFS for small puzzles (<=12 arrows), greedy for larger ones.
     */
    public Optional<List<String>> solve(Level level) {
        if (level.getArrows().size() <= 12) {
            return solveBfs(level);
        }
        return solveGreedy(level);
    }

    /**
     * Greedy topological solver — works for any number of arrows.
     * At each step, removes any arrow that can exit freely.
     * Correct because removing a free arrow never blocks other arrows.
     */
    public Optional<List<String>> solveGreedy(Level level) {
        List<Arrow> remaining = new ArrayList<>();
        for (Arrow a : level.getArrows()) remaining.add(a.deepCopy());

        List<String> solution = new ArrayList<>();
        int gridSize = level.getGridSize();

        while (!remaining.isEmpty()) {
            boolean found = false;
            for (int i = 0; i < remaining.size(); i++) {
                if (canExitInList(remaining, i, gridSize)) {
                    solution.add(remaining.get(i).getId());
                    remaining.remove(i);
                    found = true;
                    break;
                }
            }
            if (!found) return Optional.empty();
        }
        return Optional.of(solution);
    }

    /**
     * Snake/unravel collision: the HEAD's forward path (1 cell wide)
     * is checked against other arrows' segments AND the arrow's own
     * segments (to prevent arrows that need to pass through themselves).
     */
    private boolean canExitInList(List<Arrow> arrows, int index, int gridSize) {
        Arrow arrow = arrows.get(index);
        Direction dir = arrow.getHeadDirection();
        int[] head = arrow.getHead();

        Set<Long> occupied = new HashSet<>();
        // Other arrows' segments
        for (int j = 0; j < arrows.size(); j++) {
            if (j == index) continue;
            for (int[] seg : arrows.get(j).getSegments()) {
                occupied.add(cellKey(seg[0], seg[1]));
            }
        }
        // Own segments (except the head itself)
        for (int[] seg : arrow.getSegments()) {
            if (seg[0] == head[0] && seg[1] == head[1]) continue;
            occupied.add(cellKey(seg[0], seg[1]));
        }

        int r = head[0], c = head[1];
        while (true) {
            r += dir.dRow();
            c += dir.dCol();
            if (r < 0 || r >= gridSize || c < 0 || c >= gridSize) return true;
            if (occupied.contains(cellKey(r, c))) return false;
        }
    }

    /**
     * BFS solver for small puzzles — finds optimal solution.
     */
    public Optional<List<String>> solveBfs(Level level) {
        List<Arrow> arrows = level.getArrows();
        int n = arrows.size();
        if (n > 12) return Optional.empty();

        int gridSize = level.getGridSize();
        int fullMask = (1 << n) - 1;

        Map<Integer, Integer> parent = new HashMap<>();
        Map<Integer, Integer> parentMove = new HashMap<>();
        Queue<Integer> queue = new LinkedList<>();

        parent.put(fullMask, -1);
        queue.add(fullMask);

        while (!queue.isEmpty()) {
            int state = queue.poll();
            if (state == 0) {
                return Optional.of(reconstructSolution(arrows, parent, parentMove));
            }
            for (int i = 0; i < n; i++) {
                if ((state & (1 << i)) == 0) continue;
                if (canExit(arrows, i, state, gridSize)) {
                    int nextState = state & ~(1 << i);
                    if (!parent.containsKey(nextState)) {
                        parent.put(nextState, state);
                        parentMove.put(nextState, i);
                        queue.add(nextState);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Snake/unravel collision for BFS solver: head path checked against
     * other arrows AND the arrow's own segments (no self-intersection).
     */
    public boolean canExit(List<Arrow> arrows, int arrowIndex, int stateMask, int gridSize) {
        Arrow arrow = arrows.get(arrowIndex);
        Direction dir = arrow.getHeadDirection();
        int[] head = arrow.getHead();

        Set<Long> occupied = new HashSet<>();
        // Other arrows still on the board
        for (int j = 0; j < arrows.size(); j++) {
            if (j == arrowIndex) continue;
            if ((stateMask & (1 << j)) == 0) continue;
            for (int[] seg : arrows.get(j).getSegments()) {
                occupied.add(cellKey(seg[0], seg[1]));
            }
        }
        // Own segments (except head)
        for (int[] seg : arrow.getSegments()) {
            if (seg[0] == head[0] && seg[1] == head[1]) continue;
            occupied.add(cellKey(seg[0], seg[1]));
        }

        int r = head[0], c = head[1];
        while (true) {
            r += dir.dRow();
            c += dir.dCol();
            if (r < 0 || r >= gridSize || c < 0 || c >= gridSize) return true;
            if (occupied.contains(cellKey(r, c))) return false;
        }
    }

    private static long cellKey(int r, int c) {
        return (long) r * 1000 + c;
    }

    private List<String> reconstructSolution(List<Arrow> arrows,
                                              Map<Integer, Integer> parent,
                                              Map<Integer, Integer> parentMove) {
        List<String> solution = new ArrayList<>();
        int state = 0;
        while (parent.get(state) != null && parent.get(state) != -1) {
            int prevState = parent.get(state);
            int movedIndex = parentMove.get(state);
            solution.add(0, arrows.get(movedIndex).getId());
            state = prevState;
        }
        return solution;
    }
}
