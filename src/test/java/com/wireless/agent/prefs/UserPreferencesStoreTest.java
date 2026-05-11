package com.wireless.agent.prefs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UserPreferencesStoreTest {

    @Test
    void shouldReturnDefaultWhenNoPreferenceSaved(@TempDir Path tmpDir) {
        var store = new UserPreferencesStore(tmpDir);
        var engine = store.get("user1", "engine_preference", "spark_sql");
        assertThat(engine).isEqualTo("spark_sql");
    }

    @Test
    void shouldSaveAndRetrievePreference(@TempDir Path tmpDir) {
        var store = new UserPreferencesStore(tmpDir);
        store.set("user1", "engine_preference", "flink_sql");

        var engine = store.get("user1", "engine_preference", "spark_sql");
        assertThat(engine).isEqualTo("flink_sql");
    }

    @Test
    void shouldIsolateUsers(@TempDir Path tmpDir) {
        var store = new UserPreferencesStore(tmpDir);
        store.set("user1", "engine_preference", "flink_sql");
        store.set("user2", "engine_preference", "spark_sql");

        assertThat(store.get("user1", "engine_preference", "")).isEqualTo("flink_sql");
        assertThat(store.get("user2", "engine_preference", "")).isEqualTo("spark_sql");
    }

    @Test
    void shouldGetAllPreferences(@TempDir Path tmpDir) {
        var store = new UserPreferencesStore(tmpDir);
        store.set("user1", "engine_preference", "flink_sql");
        store.set("user1", "default_queue", "root.prod.wireless");

        var all = store.getAll("user1");
        assertThat(all).containsEntry("engine_preference", "flink_sql");
        assertThat(all).containsEntry("default_queue", "root.prod.wireless");
    }

    @Test
    void shouldOverwriteExistingPreference(@TempDir Path tmpDir) {
        var store = new UserPreferencesStore(tmpDir);
        store.set("user1", "engine_preference", "flink_sql");
        store.set("user1", "engine_preference", "java_flink_streamapi");

        assertThat(store.get("user1", "engine_preference", "")).isEqualTo("java_flink_streamapi");
    }

    @Test
    void shouldPersistAcrossStoreInstances(@TempDir Path tmpDir) {
        var store1 = new UserPreferencesStore(tmpDir);
        store1.set("user1", "nickname", "老王");

        var store2 = new UserPreferencesStore(tmpDir);
        assertThat(store2.get("user1", "nickname", "")).isEqualTo("老王");
    }

    @Test
    void shouldReturnDefaultForMissingUserFile(@TempDir Path tmpDir) {
        var store = new UserPreferencesStore(tmpDir);
        assertThat(store.get("nonexistent_user", "any_key", "default")).isEqualTo("default");
    }
}
