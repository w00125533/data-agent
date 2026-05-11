package com.wireless.agent.prefs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** JSON-file backed user preferences store. Thread-safe. */
public class UserPreferencesStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USER_ID_PATTERN = "[a-zA-Z0-9][a-zA-Z0-9._@-]*";

    private final Path storeDir;
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();
    private volatile boolean dirsCreated;

    public UserPreferencesStore(Path storeDir) {
        this.storeDir = Objects.requireNonNull(storeDir, "storeDir must not be null");
    }

    /** Get a preference value, returning defaultValue if not set. */
    public String get(String userId, String key, String defaultValue) {
        if (userId == null) return defaultValue;
        var prefs = load(userId);
        return prefs.getOrDefault(key, defaultValue);
    }

    /** Set a preference value and persist immediately. */
    public synchronized void set(String userId, String key, String value) {
        if (userId == null) return;
        var prefs = new LinkedHashMap<>(load(userId));
        prefs.put(key, value);
        save(userId, prefs);
        cache.put(userId, Collections.unmodifiableMap(prefs));
    }

    /** Return all preferences for a user. */
    public Map<String, String> getAll(String userId) {
        if (userId == null) return Map.of();
        return Collections.unmodifiableMap(load(userId));
    }

    private Map<String, String> load(String userId) {
        var cached = cache.get(userId);
        if (cached != null) return cached;

        try {
            var file = prefsFile(userId);
            if (!Files.exists(file)) {
                var empty = Collections.<String, String>emptyMap();
                cache.put(userId, empty);
                return empty;
            }
            var content = Files.readString(file);
            if (content.isBlank()) {
                var empty = Collections.<String, String>emptyMap();
                cache.put(userId, empty);
                return empty;
            }
            Map<String, String> prefs = MAPPER.readValue(content,
                    new TypeReference<Map<String, String>>() {});
            var unmodifiable = Collections.unmodifiableMap(prefs);
            cache.put(userId, unmodifiable);
            return unmodifiable;
        } catch (IOException e) {
            return Map.of();
        }
    }

    private void save(String userId, Map<String, String> prefs) {
        try {
            ensureDirectories();
            var file = prefsFile(userId);
            MAPPER.writeValue(file.toFile(), prefs);
        } catch (IOException e) {
            System.err.println("[UserPreferencesStore] Failed to save prefs for " + userId + ": " + e.getMessage());
        }
    }

    private void ensureDirectories() {
        if (dirsCreated) return;
        synchronized (this) {
            if (!dirsCreated) {
                try {
                    Files.createDirectories(storeDir);
                    dirsCreated = true;
                } catch (IOException e) {
                    System.err.println("[UserPreferencesStore] Failed to create store dir: " + e.getMessage());
                }
            }
        }
    }

    private Path prefsFile(String userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (!userId.matches(USER_ID_PATTERN)) {
            throw new IllegalArgumentException("Invalid userId: " + userId);
        }
        Path resolved = storeDir.resolve(userId + ".json").toAbsolutePath().normalize();
        Path normalizedDir = storeDir.toAbsolutePath().normalize();
        if (!resolved.startsWith(normalizedDir)) {
            throw new IllegalArgumentException("Invalid userId (path traversal): " + userId);
        }
        return resolved;
    }
}
