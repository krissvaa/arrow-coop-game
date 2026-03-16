package com.example.arrows;

import com.example.arrows.model.Level;
import com.example.arrows.service.LevelSolver;
import com.example.arrows.service.MazeGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LevelSolvabilityTest {

    private final LevelSolver solver = new LevelSolver();

    @Test
    void mazeGeneratorProducesSolvableLevels() {
        MazeGenerator generator = new MazeGenerator(solver);

        for (MazeGenerator.Style style : MazeGenerator.Style.values()) {
            for (int i = 0; i < 3; i++) {
                long seed = 12345L + i * 97L;
                Level level = generator.generate(style, 100 + i, seed);
                assertNotNull(level, "Generator returned null for style " + style + " seed " + seed);
                assertTrue(level.getArrows().size() >= 3,
                        "Level should have at least 3 arrows, got " + level.getArrows().size()
                                + " for style " + style);

                // Verify it's actually solvable
                Optional<List<String>> solution = solver.solve(level);
                assertTrue(solution.isPresent(),
                        "Generated level for style " + style + " (seed " + seed + ") is NOT solvable!");
                System.out.println("Generated " + style + " #" + i + ": " + level.getArrows().size()
                        + " arrows, solvable in " + solution.get().size() + " moves");
            }
        }
    }
}
