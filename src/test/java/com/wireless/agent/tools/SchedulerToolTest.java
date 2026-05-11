package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerToolTest {

    private Spec buildReadySpec() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.state(Spec.SpecState.CODEGEN_DONE);
        spec.target(new Spec.TargetSpec()
                .name("weak_cov_by_district")
                .businessDefinition("近30天5G弱覆盖按区县统计")
                .timeliness("batch_daily"));
        spec.sources(List.of(
                new Spec.SourceBinding().role("main")
                        .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))
                        .confidence(0.9),
                new Spec.SourceBinding().role("dim")
                        .binding(Map.of("catalog", "hive", "table_or_topic", "dim.engineering_param"))
                        .confidence(0.9)));
        spec.engineDecision(new Spec.EngineDecision("spark_sql", "全批源 join → Spark SQL",
                null, Map.of("submission_mode", "one_shot")));
        spec.owners().put("upstream", List.of("data-team"));
        spec.owners().put("downstream", List.of("coverage-team"));
        spec.testCases().add(Map.of("name", "正常样例", "expected_rows", 50));
        return spec;
    }

    @Test
    void shouldNameScheduler() {
        var tool = new SchedulerTool();
        assertThat(tool.name()).isEqualTo("scheduler");
        assertThat(tool.description()).contains("PR");
    }

    @Test
    void shouldGenerateSparkSubmitScript() {
        var tool = new SchedulerTool();
        var spec = buildReadySpec();
        var result = tool.run(spec, "SELECT * FROM test;");
        assertThat(result.success()).isTrue();

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.data();
        var submitScript = data.get("submit_script").toString();
        assertThat(submitScript).contains("spark-submit");
        assertThat(submitScript).contains("weak_cov_by_district");
    }

    @Test
    void shouldGenerateFlinkRunScript() {
        var tool = new SchedulerTool();
        var spec = buildReadySpec();
        spec.engineDecision(new Spec.EngineDecision("flink_sql",
                "Kafka source → Flink SQL", null,
                Map.of("submission_mode", "one_shot")));

        var result = tool.run(spec, "INSERT INTO kpi_output SELECT ...");
        assertThat(result.success()).isTrue();

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.data();
        var submitScript = data.get("submit_script").toString();
        assertThat(submitScript).contains("sql-client.sh");
    }

    @Test
    void shouldGeneratePrTemplate() {
        var tool = new SchedulerTool();
        var spec = buildReadySpec();
        var result = tool.run(spec, "CREATE TABLE test ...");
        assertThat(result.success()).isTrue();

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.data();
        var pr = data.get("pr_template").toString();
        assertThat(pr).contains("weak_cov_by_district");
        assertThat(pr).contains("## 变更内容");
        assertThat(pr).contains("coverage-team");
    }

    @Test
    void shouldGenerateTicketTemplate() {
        var tool = new SchedulerTool();
        var spec = buildReadySpec();
        var result = tool.run(spec, "SELECT * FROM test;");
        assertThat(result.success()).isTrue();

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.data();
        var ticket = data.get("ticket_template").toString();
        assertThat(ticket).contains("上线工单");
        assertThat(ticket).contains("weak_cov_by_district");
        assertThat(ticket).contains("test_case");
    }

    @Test
    void shouldFailWhenSpecNotReady() {
        var tool = new SchedulerTool();
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.state(Spec.SpecState.CLARIFYING); // not ready

        var result = tool.run(spec, "SELECT * FROM test;");
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not ready");
    }

    @Test
    void shouldFailWhenCodeIsEmpty() {
        var tool = new SchedulerTool();
        var spec = buildReadySpec();

        var result = tool.run(spec, "");
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("code");
    }

    @Test
    void shouldGenerateJavaFlinkSubmitCommand() {
        var tool = new SchedulerTool();
        var spec = buildReadySpec();
        spec.engineDecision(new Spec.EngineDecision("java_flink_streamapi",
                "复杂状态机 → Java Flink", null,
                Map.of("submission_mode", "one_shot")));

        var result = tool.run(spec, "// Java Flink code...");
        assertThat(result.success()).isTrue();

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.data();
        var submitScript = data.get("submit_script").toString();
        assertThat(submitScript).contains("flink run");
        assertThat(submitScript).contains(".jar");
    }

    @Test
    void shouldIncludeOwnersInPrTemplate() {
        var tool = new SchedulerTool();
        var spec = buildReadySpec();
        spec.owners().put("upstream", List.of("data-eng-team", "platform-team"));
        spec.owners().put("downstream", List.of("wireless-alert-system"));

        var result = tool.run(spec, "SELECT 1;");
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.data();
        var pr = data.get("pr_template").toString();
        assertThat(pr).contains("@data-eng-team");
        assertThat(pr).contains("@platform-team");
        assertThat(pr).contains("wireless-alert-system");
    }
}
