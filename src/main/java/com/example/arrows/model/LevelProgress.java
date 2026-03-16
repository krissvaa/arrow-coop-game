package com.example.arrows.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class LevelProgress {
    private final String userId;
    private final String levelId;
    private final long completedAt;
    private final int moves;

    @JsonCreator
    public LevelProgress(
            @JsonProperty("userId") String userId,
            @JsonProperty("levelId") String levelId,
            @JsonProperty("completedAt") long completedAt,
            @JsonProperty("moves") int moves) {
        this.userId = userId;
        this.levelId = levelId;
        this.completedAt = completedAt;
        this.moves = moves;
    }

    public String getUserId() { return userId; }
    public String getLevelId() { return levelId; }
    public long getCompletedAt() { return completedAt; }
    public int getMoves() { return moves; }

    public static String key(String userId, String levelId) {
        return userId + "::" + levelId;
    }
}
