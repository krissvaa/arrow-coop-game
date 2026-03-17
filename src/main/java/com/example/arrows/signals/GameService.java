package com.example.arrows.signals;

import com.example.arrows.data.LevelsRepository;
import com.example.arrows.model.Arrow;
import com.example.arrows.model.Direction;
import com.example.arrows.model.Level;
import com.example.arrows.service.LevelGenerator;
import com.example.arrows.service.MazeGenerator;
import com.vaadin.flow.signals.shared.SharedValueSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

/**
 * Singleton holding the ONE shared game state visible across all sessions.
 * All players play the same board at the same time.
 *
 * State is published to a {@link SharedValueSignal} after every mutation.
 * Views subscribe via {@code Signal.effect()} — no manual listeners or
 * {@code ui.access()} needed.
 */
@Component
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    private final LevelsRepository levelsRepository;
    private final MazeGenerator mazeGenerator;
    private final LevelGenerator levelGenerator;

    // SVG presets used for shaped levels (cycle through these)
    private static final String[] SHAPE_PRESETS = {
        "heart.svg", "star.svg", "diamond.svg", "lightning.svg",
        "cat.svg", "moon.svg", "skull.svg", "tree.svg",
        "smiley.svg", "cross.svg", "puzzle.svg", "arrow-up.svg"
    };

    // Shared signal — the single reactive source of truth for all UIs
    private final SharedValueSignal<GameSnapshot> gameState =
            new SharedValueSignal<>(GameSnapshot.class);

    // Internal mutable game state (guarded by synchronized)
    private Level currentLevel;
    private List<Arrow> activeArrows;
    private int moves;
    private int hearts;
    private boolean gameWon;
    private boolean gameLost;
    private int currentLevelIndex;
    private int levelsCompleted;

    // Last move event — for animation data
    private String lastArrowId;
    private MoveResult lastResult;
    private long lastMoveTimestamp;
    private int lastCollisionSteps;

    public GameService(LevelsRepository levelsRepository,
                              MazeGenerator mazeGenerator,
                              LevelGenerator levelGenerator) {
        this.levelsRepository = levelsRepository;
        this.mazeGenerator = mazeGenerator;
        this.levelGenerator = levelGenerator;
        this.currentLevelIndex = 0;
        // Generate the first level using heart SVG
        generateAndAdd(0);
        loadLevel(0);
    }

    // --- Shared signal accessor ---

    /**
     * The reactive signal containing the full game state snapshot.
     * Views should bind effects to this signal.
     */
    public SharedValueSignal<GameSnapshot> gameState() {
        return gameState;
    }

    // --- Game actions ---

    /**
     * Attempt to move an arrow. Called by any player's click.
     * Returns a result indicating what happened.
     */
    public synchronized MoveResult moveArrow(String arrowId) {
        if (gameWon || gameLost) return MoveResult.GAME_OVER;

        Arrow arrow = activeArrows.stream()
                .filter(a -> a.getId().equals(arrowId) && !a.isExited())
                .findFirst()
                .orElse(null);
        if (arrow == null) return MoveResult.ALREADY_EXITED;

        lastArrowId = arrowId;
        lastMoveTimestamp = System.currentTimeMillis();

        int exitSteps = computeExitSteps(arrow);
        if (exitSteps == -1) {
            lastCollisionSteps = 0;
            arrow.setExited(true);
            moves++;
            lastResult = MoveResult.SUCCESS;

            boolean allExited = activeArrows.stream().allMatch(Arrow::isExited);
            if (allExited) {
                gameWon = true;
                levelsCompleted++;
                lastResult = MoveResult.WIN;
                publishSnapshot();
                return MoveResult.WIN;
            }

            publishSnapshot();
            return MoveResult.SUCCESS;
        } else {
            lastCollisionSteps = exitSteps;
            hearts--;
            lastResult = MoveResult.COLLISION;
            if (hearts <= 0) {
                gameLost = true;
                lastResult = MoveResult.LOST;
                publishSnapshot();
                return MoveResult.LOST;
            }
            publishSnapshot();
            return MoveResult.COLLISION;
        }
    }

    /**
     * Restart the current level. Any player can trigger this.
     */
    public synchronized void restartLevel() {
        loadLevel(currentLevelIndex);
    }

    /**
     * Advance to the next level. Always generates a fresh level.
     * Style cycles: HEART -> MEDIUM -> DENSE -> HEART -> ...
     */
    public synchronized void nextLevel() {
        int nextIndex = currentLevelIndex + 1;
        if (nextIndex >= levelsRepository.getAllLevels().size()) {
            generateAndAdd(nextIndex);
        }
        loadLevel(nextIndex);
    }

    private void generateAndAdd(int index) {
        long seed = System.currentTimeMillis() + index * 97L;
        Level generated = null;

        // Alternate: even levels = SVG shape, odd levels = maze style
        if (index % 2 == 0) {
            // SVG shape level
            String preset = SHAPE_PRESETS[(index / 2) % SHAPE_PRESETS.length];
            generated = tryGenerateFromPreset(preset, index, seed);
        }

        if (generated == null) {
            // Fallback or odd levels: use MazeGenerator
            MazeGenerator.Style[] styles = MazeGenerator.Style.values();
            MazeGenerator.Style style = styles[index % styles.length];
            generated = mazeGenerator.generate(style, index + 1, seed);
        }

        levelsRepository.addLevel(generated);
    }

    private Level tryGenerateFromPreset(String presetFile, int index, long seed) {
        try {
            InputStream is = getClass().getResourceAsStream("/presets/" + presetFile);
            if (is == null) return null;
            String svg = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            int gridSize = 16; // good default for shape levels
            int density = 25;  // medium-easy
            Level level = levelGenerator.generate(svg, gridSize, density, seed);
            if (level != null) {
                // Re-create with nicer title based on preset name
                String title = presetFile.replace(".svg", "");
                title = title.substring(0, 1).toUpperCase() + title.substring(1);
                title = title.replace("-", " ");
                return new Level("L" + (index + 1), title, level.getGridSize(),
                        level.getArrows(), level.getSolutionOrder(), level.getPlayableMask());
            }
        } catch (Exception e) {
            log.warn("Failed to generate level from preset {}: {}", presetFile, e.getMessage());
        }
        return null;
    }

    /**
     * Load a specific level by its index in the repository.
     */
    public synchronized void loadLevel(int index) {
        List<Level> all = levelsRepository.getAllLevels();
        if (index < 0 || index >= all.size()) index = 0;
        this.currentLevelIndex = index;
        this.currentLevel = all.get(index);
        this.activeArrows = new ArrayList<>();
        for (Arrow a : currentLevel.getArrows()) {
            activeArrows.add(a.deepCopy());
        }
        this.moves = 0;
        this.hearts = 3;
        this.gameWon = false;
        this.gameLost = false;
        this.lastArrowId = null;
        this.lastResult = null;
        this.lastMoveTimestamp = 0;
        this.lastCollisionSteps = 0;
        publishSnapshot();
    }

    /**
     * Load a generated/custom level.
     */
    public synchronized void loadCustomLevel(Level level) {
        this.currentLevel = level;
        this.currentLevelIndex = -1;
        this.activeArrows = new ArrayList<>();
        for (Arrow a : level.getArrows()) {
            activeArrows.add(a.deepCopy());
        }
        this.moves = 0;
        this.hearts = 3;
        this.gameWon = false;
        this.gameLost = false;
        publishSnapshot();
    }

    // --- Snapshot publishing ---

    private void publishSnapshot() {
        List<GameSnapshot.ArrowData> arrowData = activeArrows.stream()
                .map(a -> new GameSnapshot.ArrowData(
                        a.getId(),
                        a.getColor(),
                        a.getHeadDirection(),
                        a.getSegments().toArray(new int[0][]),
                        a.isExited()
                )).toList();

        GameSnapshot.MoveEvent moveEvent = lastArrowId != null
                ? new GameSnapshot.MoveEvent(lastArrowId, lastResult,
                        lastMoveTimestamp, lastCollisionSteps)
                : null;

        gameState.set(new GameSnapshot(
                currentLevelIndex,
                currentLevel.getId(),
                currentLevel.getTitle(),
                currentLevel.getGridSize(),
                arrowData,
                moves,
                hearts,
                gameWon,
                gameLost,
                levelsCompleted,
                levelsRepository.getAllLevels().size(),
                moveEvent,
                currentLevel.getPlayableMask()
        ));
    }

    // --- Collision detection (snake/unravel — head-only) ---
    //
    // When an arrow moves, it "unravels" like a snake: the head leads in
    // headDirection, the body follows behind in a straight 1-cell-wide line.
    // Only the HEAD's forward path is checked for collisions with other arrows.

    private int computeExitSteps(Arrow arrow) {
        Direction dir = arrow.getHeadDirection();
        int[] head = arrow.getHead();
        int gridSize = currentLevel.getGridSize();

        Set<Long> occupied = new HashSet<>();
        // Other arrows still on the board
        for (Arrow other : activeArrows) {
            if (other == arrow || other.isExited()) continue;
            for (int[] seg : other.getSegments()) {
                occupied.add(cellKey(seg[0], seg[1]));
            }
        }
        // Own segments (except head) — arrow can't pass through itself
        for (int[] seg : arrow.getSegments()) {
            if (seg[0] == head[0] && seg[1] == head[1]) continue;
            occupied.add(cellKey(seg[0], seg[1]));
        }

        int r = head[0], c = head[1];
        int steps = 0;
        while (true) {
            r += dir.dRow();
            c += dir.dCol();
            if (r < 0 || r >= gridSize || c < 0 || c >= gridSize) {
                return -1; // head reached grid edge — success
            }
            if (occupied.contains(cellKey(r, c))) {
                return steps; // head hit another arrow or own body
            }
            steps++;
        }
    }

    private static long cellKey(int r, int c) {
        return (long) r * 1000 + c;
    }
}
