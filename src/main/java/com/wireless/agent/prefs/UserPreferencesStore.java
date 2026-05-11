package com.wireless.agent.prefs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** JSON-file backed user preferences store. Thread-safe. */
public class UserPreferencesStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path storeDir;
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    public UserPreferencesStore(Path storeDir) {
        this.storeDir = storeDir;
    }

    /** Get a preference value, returning defaultValue if not set. */
    public String get(String userId, String key, String defaultValue) {
        var prefs = load(userId);
        return prefs.getOrDefault(key, defaultValue);
    }

    /** Set a preference value and persist immediately. */
    public void set(String userId, String key, String value) {
        var prefs = new LinkedHashMap<>(load(userId));
        prefs.put(key, value);
        save(userId, prefs);
        cache.put(userId, Collections.unmodifiableMap(prefs));
    }

    /** Return all preferences for a user. */
    public Map<String, String> getAll(String userId) {
        return Collections.unmodifiableMap(load(userId));
    }

    private Map<String, String> load(String userId) {
        var cached = cache.get(userId);
        if (cached != null) return cached;

        try {
            var file = prefsFile(userId);
            if (!Files.exists(file)) return Map.of();
            var content = Files.readString(file);
            if (content.isBlank()) return Map.of();
            Map<String, String> prefs = MAPPER.readValue(content,
                    new TypeReference<Map<String, String>>() {});
            cache.put(userId, prefs);
            return prefs;
        } catch (IOException e) {
            return Map.of();
        }
    }

    private void save(String userId, Map<String, String> prefs) {
        try {
            Files.createDirectories(storeDir);
            var file = prefsFile(userId);
            MAPPER.writeValue(file.toFile(), prefs);
        } catch (IOException e) {
            System.err.println("[UserPreferencesStore] Failed to save prefs for " + userId + ": " + e.getMessage());
        }
    }

    private Path prefsFile(String userId) {
        return storeDir.resolve(userId + ".json");
    }
}
