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

    @Test
    void shouldGenerateFlinkSqlPrompt() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec()
                .name("handover_failure_by_cell")
                .businessDefinition("按cell汇总最近1小时切换失败次数")
                .grain("(cell_id, hour)")
                .timeliness("streaming"));
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("signaling")
                        .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))
                        .confidence(0.9)
        ));
        spec.networkContext().kpiFamily("mobility");
        spec.engineDecision(new Spec.EngineDecision("flink_sql", "Kafka 流式源,需时间窗口"));

        var prompt = CodegenTool.buildCodegenPrompt(spec);
        assertThat(prompt).contains("Flink SQL");
        assertThat(prompt).contains("signaling_events");
        assertThat(prompt).contains("flink_sql");
    }

    @Test
    void shouldFallbackToHardcodedFlinkSqlWhenNoLlm() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec()
                .name("handover_test")
                .businessDefinition("切换失败统计")
                .grain("(cell_id, hour)"));
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("signaling")
                        .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))
        ));
        spec.engineDecision(new Spec.EngineDecision("flink_sql", "Kafka流式源"));

        var tool = new CodegenTool(null);
        var result = tool.run(spec);
        assertThat(result.success()).isTrue();
        var code = result.data().toString();
        assertThat(code).contains("flink_sql");
        assertThat(code).contains("handover");
    }

    @Test
    void shouldGenerateJavaFlinkStreamApiPrompt() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec()
                .name("stateful_sessionization")
                .businessDefinition("需要复杂状态机的Session窗口聚合")
                .grain("(cell_id, session)")
                .timeliness("streaming"));
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("signaling")
                        .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))
        ));
        spec.engineDecision(new Spec.EngineDecision("java_flink_streamapi",
                "复杂状态机SQL无法表达 → Java Flink Stream API"));

        var prompt = CodegenTool.buildCodegenPrompt(spec);
        assertThat(prompt).contains("Java Flink Stream API");
        assertThat(prompt).contains("java_flink_streamapi");
    }

    @Test
    void shouldFallbackToHardcodedJavaFlinkWhenNoLlm() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec()
                .name("kpi_stream")
                .businessDefinition("通用流式KPI加工")
                .grain("(cell_id, minute)"));
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("signaling")
                        .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))
        ));
        spec.engineDecision(new Spec.EngineDecision("java_flink_streamapi",
                "复杂窗口逻辑 → Java"));

        var tool = new CodegenTool(null);
        var result = tool.run(spec);
        assertThat(result.success()).isTrue();
        var code = result.data().toString();
        assertThat(code).contains("java_flink_streamapi");
        assertThat(code).contains("FlinkDataStream");
    }

    @Test
    void shouldGenerateReverseSyntheticPrompt() {
        var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
        spec.originalPipeline("""
                INSERT INTO handover_kpi
                SELECT cell_id, COUNT(*) AS failure_count
                FROM signaling_events
                WHERE event_type = 'handover' AND result = 'failure'
                GROUP BY cell_id;""");
        spec.target(new Spec.TargetSpec()
                .name("handover_kpi_data_gen")
                .businessDefinition("生成切换失败 KPI 测试数据")
                .grain("(cell_id, hour)"));
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("stream")
                        .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))
        ));
        spec.engineDecision(new Spec.EngineDecision("java_flink_streamapi",
                "反向合成数据生产 → Java Flink Stream API"));

        var prompt = CodegenTool.buildCodegenPrompt(spec);
        assertThat(prompt).contains("REVERSE_SYNTHETIC");
        assertThat(prompt).contains("数据生产");
        assertThat(prompt).contains("signaling_events");
        assertThat(prompt).contains("handover");
    }

    @Test
    void shouldFallbackToReverseSyntheticCodeWhenNoLlm() {
        var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
        spec.originalPipeline("INSERT INTO handover_kpi SELECT cell_id, COUNT(*) FROM signaling_events WHERE event_type='handover' GROUP BY cell_id;");
        spec.target(new Spec.TargetSpec()
                .name("handover_data_gen")
                .businessDefinition("生成切换失败测试数据"));
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("stream")
                        .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))
        ));
        spec.engineDecision(new Spec.EngineDecision("java_flink_streamapi",
                "反向合成 → Java"));

        var tool = new CodegenTool(null);
        var result = tool.run(spec);
        assertThat(result.success()).isTrue();
        var code = result.data().toString();
        assertThat(code).contains("DataGenerator");
        assertThat(code).contains("synthetic");
    }

    @Test
    void shouldSelectReversePromptWhenTaskDirectionIsReverse() {
        var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
        spec.engineDecision(new Spec.EngineDecision("flink_sql", "反向合成 Flink SQL"));
        spec.target(new Spec.TargetSpec().name("test"));
        spec.sources(List.of(
                new Spec.SourceBinding().role("s").binding(Map.of("table_or_topic", "t"))
        ));
        // Even with flink_sql engine, REVERSE_SYNTHETIC direction should trigger reverse prompt
        var prompt = CodegenTool.buildCodegenPrompt(spec);
        assertThat(prompt).contains("REVERSE_SYNTHETIC");
    }
}
