package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ValidatorToolTest {

    @Test
    void shouldHaveCorrectToolName() {
        var tool = new ValidatorTool();
        assertThat(tool.name()).isEqualTo("validator");
    }

    @Test
    void shouldPassValidSparkSql() {
        var code = """
                ```sql
                CREATE OR REPLACE TEMP VIEW test AS
                SELECT m.cell_id, e.district
                FROM dw.mr_5g_15min m
                JOIN dim.engineering_param e ON m.cell_id = e.cell_id
                WHERE m.rsrp_avg < -110;
                ```""";

        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec().name("test_view").businessDefinition("weak_coverage"));
        spec.sources().add(new Spec.SourceBinding().role("mr_main")
                .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min")));
        spec.sources().add(new Spec.SourceBinding().role("eng_param")
                .binding(Map.of("catalog", "hive", "table_or_topic", "dim.engineering_param")));

        var result = new ValidatorTool().validate(code, spec);
        assertThat(result.success()).isTrue();
    }

    @Test
    void shouldFailOnMissingSqlBlock() {
        var code = "just some text, no sql code block";
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        var result = new ValidatorTool().validate(code, spec);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("SQL code block");
    }

    @Test
    void shouldWarnOnMissingTableReference() {
        var code = """
                ```sql
                SELECT * FROM some_unknown_table LIMIT 10;
                ```""";
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.sources().add(new Spec.SourceBinding().role("mr_main")
                .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min")));

        var result = new ValidatorTool().validate(code, spec);
        // Should still pass but with warnings
        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        var warnings = (List<String>) ((Map<?, ?>) result.data()).get("warnings");
        assertThat(warnings).isNotEmpty();
    }

    @Test
    void shouldDetectMissingFROMClause() {
        var code = """
                ```sql
                SELECT 1;
                ```""";
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        var result = new ValidatorTool().validate(code, spec);
        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        var warnings = (List<String>) ((Map<?, ?>) result.data()).get("warnings");
        assertThat(warnings).isNotEmpty();
    }

    @Test
    void shouldValidateReverseSyntheticSchemaCompatibility() {
        var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
        spec.originalPipeline("""
                SELECT cell_id, COUNT(*) AS failure_count
                FROM signaling_events
                WHERE event_type = 'handover'
                GROUP BY cell_id;""");
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("stream")
                        .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))
        ));
        spec.engineDecision(new Spec.EngineDecision("flink_sql", "反向合成"));

        var generatedCode = """
                ```sql
                INSERT INTO signaling_events (cell_id, event_type, result, ts)
                VALUES ('cell_A', 'handover', 'failure', 1000);
                ```""";

        var tool = new ValidatorTool();
        var result = tool.validate(generatedCode, spec);
        assertThat(result.success()).isTrue();
    }

    @Test
    void shouldCheckSchemaCompatibilityForReverseSynthetic() {
        var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
        spec.originalPipeline("SELECT cell_id, COUNT(*) FROM signaling_events WHERE event_type='handover' GROUP BY cell_id;");
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("stream")
                        .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))
        ));

        var generatedCode = """
                ```sql
                INSERT INTO signaling_events VALUES ('cell_A', 'handover', 'failure', 1000);
                ```""";

        var tool = new ValidatorTool();
        var result = tool.validate(generatedCode, spec);
        // The generated SQL inserts into signaling_events which matches spec source
        @SuppressWarnings("unchecked")
        var warnings = (List<String>) ((Map<?, ?>) result.data()).get("warnings");
        assertThat(warnings.stream().noneMatch(w -> w.contains("not in spec"))).isTrue();
    }
}
