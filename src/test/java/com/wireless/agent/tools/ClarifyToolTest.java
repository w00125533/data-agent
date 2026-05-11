package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClarifyToolTest {

    @Test
    void shouldPickHighestPriorityQuestion() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        // Lower priority (time_grain) first
        spec.addQuestion("time_grain", "时间粒度?", List.of("hour", "day"));
        // Higher priority (target_name) second — should be picked
        spec.addQuestion("target_name", "目标表名?", List.of("handover_kpi", "coverage_kpi"));

        var tool = new ClarifyTool();
        var picked = tool.pickNextQuestion(spec);

        assertThat(picked).isNotNull();
        assertThat(picked.fieldPath()).isEqualTo("target_name");
    }

    @Test
    void shouldReturnNullWhenAllQuestionsAnswered() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.addQuestion("target_name", "目标?", List.of("t1"));
        spec.markAnswered("target_name", "t1");

        var tool = new ClarifyTool();
        assertThat(tool.pickNextQuestion(spec)).isNull();
    }

    @Test
    void shouldGenerateClarifyingQuestionWithoutLlm() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.addQuestion("target_name", "目标表名是什么?", List.of("handover_kpi", "coverage_kpi"));
        spec.target(new Spec.TargetSpec().name("(未命名)").businessDefinition("切换失败统计"));

        var tool = new ClarifyTool();
        var result = tool.run(spec);

        assertThat(result.success()).isTrue();
        var data = result.data();
        assertThat(data).isNotNull();
        assertThat(data.toString()).containsAnyOf("目标表名", "handover_kpi", "coverage_kpi");
    }

    @Test
    void shouldScoreSpecGapsByPriority() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec().name("test").businessDefinition("test def"));
        // target exists but no sources → should detect source gap
        spec.addQuestion("identified_sources", "需要哪些数据源?", List.of("mr", "pm"));

        var tool = new ClarifyTool();
        var gaps = tool.detectGaps(spec);
        assertThat(gaps).isNotEmpty();
        assertThat(gaps).anyMatch(g -> g.contains("source") || g.contains("数据源"));
    }
}
