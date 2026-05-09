package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CodegenToolTest {

    @Test
    void shouldHaveCorrectToolName() {
        var tool = new CodegenTool(null);
        assertThat(tool.name()).isEqualTo("codegen");
    }

    @Test
    void shouldBuildPromptWithTargetAndSources() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec()
                .name("weak_cov_by_district")
                .businessDefinition("按区县统计弱覆盖小区数")
                .grain("(district, day)"));
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("mr_main")
                        .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))
                        .confidence(0.9),
                new Spec.SourceBinding()
                        .role("eng_param")
                        .binding(Map.of("catalog", "hive", "table_or_topic", "dim.engineering_param"))
                        .confidence(0.9)
        ));
        spec.engineDecision(new Spec.EngineDecision("spark_sql", "批源、join + 聚合"));

        var prompt = CodegenTool.buildCodegenPrompt(spec);
        assertThat(prompt).contains("弱覆盖小区");
        assertThat(prompt).contains("dw.mr_5g_15min");
        assertThat(prompt).contains("dim.engineering_param");
        assertThat(prompt).contains("spark_sql");
    }

    @Test
    void shouldFallbackToHardcodedSqlWhenNoLlmClient() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec()
                .name("weak_cov_test")
                .businessDefinition("弱覆盖统计")
                .grain("(cell_id, day)"));
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("main")
                        .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))
        ));
        spec.engineDecision(new Spec.EngineDecision("spark_sql", "单一Hive源"));

        var tool = new CodegenTool(null);
        var result = tool.run(spec);
        assertThat(result.success()).isTrue();
        assertThat(result.data().toString()).contains("弱覆盖");
        assertThat(result.data().toString()).contains("rsrp_avg");
    }

    @Test
    void shouldReturnGenericSqlForNonWeakCoverageTarget() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec()
                .name("test_table")
                .businessDefinition("测试数据")
                .grain("(cell_id)"));
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("main")
                        .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))
        ));
        spec.engineDecision(new Spec.EngineDecision("spark_sql", "test"));

        var tool = new CodegenTool(null);
        var result = tool.run(spec);
        assertThat(result.success()).isTrue();
        assertThat(result.data().toString()).contains("SELECT");
    }
}
