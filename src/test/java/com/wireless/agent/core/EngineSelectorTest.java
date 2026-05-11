package com.wireless.agent.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class EngineSelectorTest {

    @Test
    void shouldRecommendSparkForHiveOnlySources() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.sources(List.of(new Spec.SourceBinding().role("main")
                .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))));
        assertThat(EngineSelector.select(spec).recommended()).isEqualTo("spark_sql");
    }

    @Test
    void shouldRecommendFlinkForKafkaSource() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.sources(List.of(new Spec.SourceBinding().role("stream")
                .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))));
        assertThat(EngineSelector.select(spec).recommended()).isEqualTo("flink_sql");
    }

    @Test
    void shouldRecommendFlinkForMixedSources() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.sources(List.of(
                new Spec.SourceBinding().role("stream")
                        .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events")),
                new Spec.SourceBinding().role("dim")
                        .binding(Map.of("catalog", "hive", "table_or_topic", "dim.engineering_param"))));
        assertThat(EngineSelector.select(spec).recommended()).isEqualTo("flink_sql");
    }

    @Test
    void shouldProvideReasoning() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.sources(List.of(new Spec.SourceBinding().role("main")
                .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))));
        assertThat(EngineSelector.select(spec).reasoning()).isNotEmpty();
    }

    @Test
    void shouldReturnFallbackForEmptySources() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        assertThat(EngineSelector.select(spec).recommended()).isEqualTo("spark_sql");
    }

    @Test
    void shouldRecommendFlinkSqlForKafkaPlusTimeWindow() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec().name("test").timeliness("streaming"));
        spec.sources(List.of(new Spec.SourceBinding().role("stream")
                .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))));
        assertThat(EngineSelector.select(spec).recommended()).isEqualTo("flink_sql");
    }

    @Test
    void shouldRecommendJavaFlinkStreamApiForComplexState() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec().name("stateful_test").businessDefinition("需要复杂状态机处理"));
        spec.sources(List.of(new Spec.SourceBinding().role("stream")
                .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))));
        assertThat(EngineSelector.select(spec).recommended()).isEqualTo("java_flink_streamapi");
    }

    @Test
    void shouldStillRecommendFlinkSqlForSimpleStreamTask() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec().name("simple_agg").businessDefinition("切换失败次数统计"));
        spec.sources(List.of(new Spec.SourceBinding().role("stream")
                .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))));
        assertThat(EngineSelector.select(spec).recommended()).isEqualTo("flink_sql");
    }

    @Test
    void shouldRecommendJavaFlinkForReverseSyntheticTask() {
        var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
        spec.target(new Spec.TargetSpec().name("synth_data").businessDefinition("生成模拟信令数据"));
        spec.sources(List.of(new Spec.SourceBinding().role("stream")
                .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))));
        assertThat(EngineSelector.select(spec).recommended()).isEqualTo("java_flink_streamapi");
    }

    @Test
    void shouldRespectUserEnginePreference() throws Exception {
        var tmpDir = java.nio.file.Files.createTempDirectory("prefs-test");
        try {
            var prefs = new com.wireless.agent.prefs.UserPreferencesStore(tmpDir);
            prefs.set("test-user", "engine_preference", "flink_sql");

            var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
            spec.sources(List.of(new Spec.SourceBinding().role("main")
                    .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))));

            // Without user pref → would be spark_sql, but pref overrides to flink_sql
            var decision = EngineSelector.select(spec, prefs, "test-user");
            assertThat(decision.recommended()).isEqualTo("flink_sql");
            assertThat(decision.userOverridden()).isTrue();
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    @Test
    void shouldFallbackWhenNoUserPreference() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.sources(List.of(new Spec.SourceBinding().role("main")
                .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))));

        var decision = EngineSelector.select(spec, null, null);
        assertThat(decision.recommended()).isEqualTo("spark_sql");
        assertThat(decision.userOverridden()).isNull();
    }

    @Test
    void shouldNotOverrideWhenUserPrefIsUnknownEngine() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.sources(List.of(new Spec.SourceBinding().role("main")
                .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))));

        // User pref with invalid engine name should be ignored
        var decision = EngineSelector.select(spec, "invalid_engine");
        assertThat(decision.recommended()).isEqualTo("spark_sql");
        assertThat(decision.userOverridden()).isNull();
    }

    private static void deleteRecursively(java.nio.file.Path dir) throws Exception {
        if (dir == null || !java.nio.file.Files.exists(dir)) return;
        try (var s = java.nio.file.Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { java.nio.file.Files.delete(p); } catch (Exception ignored) {}
            });
        }
    }
}
