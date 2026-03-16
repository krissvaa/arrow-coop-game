package com.example.arrows.data;

import com.example.arrows.model.Level;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LevelsRepository {

    private final List<Level> levels = new ArrayList<>();

    public List<Level> getAllLevels() {
        return levels;
    }

    public synchronized void addLevel(Level level) {
        levels.add(level);
    }

    public int size() {
        return levels.size();
    }

    public Level getById(String id) {
        return levels.stream()
                .filter(l -> l.getId().equals(id))
                .findFirst()
                .orElse(null);
    }
}
