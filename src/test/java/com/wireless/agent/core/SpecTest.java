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
        assertThat(q.question()).isEqualTo("什么是活跃用户？");
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

    @Test
    void shouldSerializeOriginalPipeline() throws Exception {
        var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
        spec.originalPipeline("""
                INSERT INTO output_kpi
                SELECT cell_id, COUNT(*) AS failure_count
                FROM signaling_events
                WHERE event_type = 'handover'
                GROUP BY cell_id;""");

        var json = mapper.writeValueAsString(spec);
        assertThat(json).contains("original_pipeline");
        assertThat(json).contains("signaling_events");
    }

    @Test
    void shouldDefaultOriginalPipelineToNull() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        assertThat(spec.originalPipeline()).isNull();
    }

    @Test
    void shouldReturnNullWhenPipelineNotSet() {
        var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
        assertThat(spec.originalPipeline()).isNull();
    }

    @Test
    void shouldCreateQuestionWithAnswer() {
        var q = new Spec.Question("data_scale", "数据规模多大?", List.of("1k", "10k", "1M"));
        assertThat(q.fieldPath()).isEqualTo("data_scale");
        assertThat(q.question()).contains("数据规模");
        assertThat(q.candidates()).containsExactly("1k", "10k", "1M");
        assertThat(q.resolved()).isFalse();
        assertThat(q.answer()).isNull();

        var answered = q.withAnswer("10k");
        assertThat(answered.resolved()).isTrue();
        assertThat(answered.answer()).isEqualTo("10k");
    }

    @Test
    void shouldAddAndRetrieveUnansweredQuestions() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.addQuestion("target_name", "目标表名?", List.of());
        spec.addQuestion("time_grain", "时间粒度?", List.of("hour", "day"));

        var unanswered = spec.unansweredQuestions();
        assertThat(unanswered).hasSize(2);
        assertThat(unanswered.get(0).fieldPath()).isEqualTo("target_name");
    }

    @Test
    void shouldMarkQuestionAnswered() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.addQuestion("target_name", "目标表名?", List.of("handover_kpi", "coverage_kpi"));
        spec.addQuestion("time_grain", "时间粒度?", List.of("hour", "day"));

        spec.markAnswered("target_name", "handover_kpi");

        var unanswered = spec.unansweredQuestions();
        assertThat(unanswered).hasSize(1);
        assertThat(unanswered.get(0).fieldPath()).isEqualTo("time_grain");
        assertThat(spec.openQuestions().get(0).resolved()).isTrue();
    }

    @Test
    void shouldNotCrashWhenMarkingNonexistentField() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.markAnswered("nonexistent", "value");
        assertThat(spec.unansweredQuestions()).isEmpty();
    }
}
