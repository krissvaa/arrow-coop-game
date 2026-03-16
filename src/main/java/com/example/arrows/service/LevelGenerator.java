package com.example.arrows.service;

import com.example.arrows.model.Arrow;
import com.example.arrows.model.Level;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Generates levels from SVG shapes by rasterizing to a grid mask,
 * then filling with arrows using the same snake-like bent arrow
 * strategy as MazeGenerator (long arrows, turns, diverse directions).
 */
@Service
public class LevelGenerator {

    private final SvgRasterizer rasterizer;
    private final LevelSolver solver;
    private final MazeGenerator mazeGenerator;

    public LevelGenerator(SvgRasterizer rasterizer, LevelSolver solver,
                           MazeGenerator mazeGenerator) {
        this.rasterizer = rasterizer;
        this.solver = solver;
        this.mazeGenerator = mazeGenerator;
    }

    /**
     * Generate a level from an SVG string.
     * All filled cells in the rasterized shape are covered by arrows.
     */
    private static final int DEFAULT_RASTER_THRESHOLD = 128;

    public Level generate(String svgContent, int gridSize, int density, long seed) throws Exception {
        boolean[][] mask = rasterizer.rasterize(svgContent, gridSize, DEFAULT_RASTER_THRESHOLD);

        int filledCount = countFilled(mask, gridSize);

        if (filledCount < 2) {
            throw new IllegalArgumentException(
                "Shape too sparse for this grid size - try a smaller grid or denser fill.");
        }

        // Pick a base style for non-length parameters (bias, bent target, etc.)
        MazeGenerator.Style style;
        if (gridSize <= 8) style = MazeGenerator.Style.MEDIUM;
        else if (gridSize <= 20) style = MazeGenerator.Style.HEART;
        else style = MazeGenerator.Style.DENSE;

        for (int attempt = 0; attempt < 50; attempt++) {
            Random random = new Random(seed + attempt);
            Level level = tryGenerateLevel(mask, gridSize, style, density, random, attempt);
            if (level != null) {
                return level;
            }
        }

        if (gridSize > 4) {
            return generate(svgContent, gridSize - 1, density, seed);
        }

        return null;
    }

    private Level tryGenerateLevel(boolean[][] mask, int gridSize,
                                    MazeGenerator.Style style, int density,
                                    Random random, int attemptNum) {
        // Use MazeGenerator's filling strategy with density-controlled arrow lengths
        List<Arrow> arrows = mazeGenerator.fillWithArrows(mask, gridSize, style, density, random);
        if (arrows.size() < 2) return null;

        String levelId = "gen-" + UUID.randomUUID();
        Level level = new Level(levelId, "Custom Level", gridSize, arrows, null, mask);

        Optional<List<String>> solution = solver.solve(level);
        if (solution.isPresent()) {
            return new Level(levelId, "Custom Level", gridSize, arrows, solution.get(), mask);
        }
        return null;
    }

    private int countFilled(boolean[][] mask, int gridSize) {
        int count = 0;
        for (int r = 0; r < gridSize; r++)
            for (int c = 0; c < gridSize; c++)
                if (mask[r][c]) count++;
        return count;
    }

    /**
     * Get the rasterized mask for preview purposes.
     */
    public boolean[][] getMask(String svgContent, int gridSize) throws Exception {
        return rasterizer.rasterize(svgContent, gridSize, DEFAULT_RASTER_THRESHOLD);
    }
}
