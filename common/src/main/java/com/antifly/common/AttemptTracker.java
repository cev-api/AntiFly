package com.antifly.common;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AttemptTracker {
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();

    public int record(UUID uuid) {
        return attempts.merge(uuid, 1, Integer::sum);
    }

    public int get(UUID uuid) {
        return attempts.getOrDefault(uuid, 0);
    }

    public void reset(UUID uuid) {
        attempts.remove(uuid);
    }
}
