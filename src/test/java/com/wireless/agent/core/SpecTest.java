package com.wireless.agent.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpecTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void shouldDefaultStateToGathering() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        assertThat(spec.state()).isEqualTo(Spec.SpecState.GATHERING);
    }

    @Test
    void shouldDefaultNetworkContextValues() {
        var ctx = new Spec.NetworkContext();
        assertThat(ctx.neGrain()).isEqualTo("cell");
        assertThat(ctx.rat()).isEqualTo("5G_SA");
    }

    @Test
    void shouldProgressToReadyWhenComplete() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec()
                .name("weak_cov_cells")
                .businessDefinition("弱覆盖小区")
                .grain("(cell_id, day)"));
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("mr_main")
                        .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))
                        .confidence(0.9)
        ));
        spec.engineDecision(new Spec.EngineDecision("spark_sql", "批源、简单聚合"));
        spec.advanceState();
        assertThat(spec.state()).isEqualTo(Spec.SpecState.READY_TO_CODEGEN);
    }

    @Test
    void shouldNotProgressWithoutTarget() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.advanceState();
        assertThat(spec.state()).isEqualTo(Spec.SpecState.GATHERING);
    }

    @Test
    void shouldReturnFirstOpenQuestion() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.addQuestion("field_a", "什么是活跃用户？", List.of("A", "B"));
        spec.addQuestion("field_b", "时间粒度？", null);
        var q = spec.nextQuestion();
        assertThat(q).isNotNull();
        assertThat(q.get("question")).isEqualTo("什么是活跃用户？");
    }

    @Test
    void shouldSerializeEnumsAsSnakeCase() throws JsonProcessingException {
        var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
        spec.state(Spec.SpecState.READY_TO_CODEGEN);
        var json = mapper.writeValueAsString(spec);
        assertThat(json).contains("\"task_direction\"");
        assertThat(json).contains("\"reverse_synthetic\"");
        assertThat(json).contains("\"ready_to_codegen\"");
    }
}
