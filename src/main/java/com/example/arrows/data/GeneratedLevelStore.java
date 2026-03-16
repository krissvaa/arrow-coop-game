package com.example.arrows.data;

import com.example.arrows.model.Level;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stores generated levels in memory, shared across all sessions.
 * Uses a thread-safe list. When any user generates and saves a level,
 * it becomes available for all sessions.
 */
@Component
public class GeneratedLevelStore {

    private final List<Level> generatedLevels = new CopyOnWriteArrayList<>();

    public void save(Level level) {
        generatedLevels.add(0, level); // newest first
    }

    public List<Level> getLevels() {
        return List.copyOf(generatedLevels);
    }

    public Level getById(String id) {
        return generatedLevels.stream()
                .filter(l -> l.getId().equals(id))
                .findFirst()
                .orElse(null);
    }
}
