package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxToolTest {

    @Test
    void shouldHaveCorrectToolName() {
        var runner = new DockerCommandRunner();
        var tool = new SandboxTool(runner, "da-spark-master");
        assertThat(tool.name()).isEqualTo("sandbox");
    }

    @Test
    void shouldExtractSqlFromMarkdownBlock() {
        var code = """
                ```sql
                SELECT cell_id, AVG(rsrp_avg) AS avg_rsrp
                FROM dw.mr_5g_15min
                WHERE rsrp_avg < -110
                GROUP BY cell_id
                LIMIT 10;
                ```""";
        var sql = SandboxTool.extractSql(code);
        assertThat(sql).contains("SELECT");
        assertThat(sql).contains("LIMIT 10");
        assertThat(sql).doesNotContain("```");
    }

    @Test
    void shouldAppendLimitIfMissing() {
        var sql = "SELECT * FROM dw.mr_5g_15min WHERE rsrp_avg < -110;";
        var result = SandboxTool.ensureLimit(sql, 100);
        assertThat(result).contains("LIMIT 100");
        assertThat(result).contains("_preview");
    }

    @Test
    void shouldWrapSqlWithSubqueryLimit() {
        var sql = "SELECT * FROM t LIMIT 50;";
        var result = SandboxTool.ensureLimit(sql, 100);
        assertThat(result).contains("SELECT * FROM (SELECT * FROM t LIMIT 50) _preview LIMIT 100");
    }

    @Test
    void shouldBuildDryRunPreviewMessage() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec().name("test_view").businessDefinition("weak_coverage"));
        spec.sources().add(new Spec.SourceBinding().role("mr")
                .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min")));

        var code = "```sql\nSELECT * FROM dw.mr_5g_15min LIMIT 10;\n```";
        var result = new SandboxTool(new DockerCommandRunner(), "da-spark-master")
                .dryRun(code, spec);

        // Without Docker running, should produce error but not crash
        assertThat(result).isNotNull();
        assertThat(result).containsKey("next_action");
    }

    @Test
    void shouldRewriteSqlForBaseline() {
        var runner = new DockerCommandRunner();
        var baseline = new BaselineService(runner, "da-spark-master");
        baseline.recordBaseline("dw.mr_5g_15min", "baseline.dw__mr_5g_15min",
                1, Map.of("row_count", 1000));
        var tool = new SandboxTool(runner, "da-spark-master", baseline);

        var spec = new com.wireless.agent.core.Spec(com.wireless.agent.core.Spec.TaskDirection.FORWARD_ETL);
        spec.sources(java.util.List.of(
            new com.wireless.agent.core.Spec.SourceBinding().role("mr")
                .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))
        ));

        var sql = "SELECT * FROM dw.mr_5g_15min WHERE rsrp_avg < -110;";
        var rewritten = tool.rewriteForBaseline(sql, spec);
        assertThat(rewritten).contains("baseline.dw__mr_5g_15min");
        assertThat(rewritten).doesNotContain("dw.mr_5g_15min");
    }

    @Test
    void shouldNotRewriteWhenNoBaseline() {
        var runner = new DockerCommandRunner();
        var baseline = new BaselineService(runner, "da-spark-master");
        var tool = new SandboxTool(runner, "da-spark-master", baseline);

        var spec = new com.wireless.agent.core.Spec(com.wireless.agent.core.Spec.TaskDirection.FORWARD_ETL);
        spec.sources(java.util.List.of(
            new com.wireless.agent.core.Spec.SourceBinding().role("mr")
                .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))
        ));

        var sql = "SELECT * FROM dw.mr_5g_15min;";
        var rewritten = tool.rewriteForBaseline(sql, spec);
        assertThat(rewritten).isEqualTo(sql); // No rewrite
    }

    @Test
    void shouldSelectSparkContainerForSparkSql() {
        var runner = new DockerCommandRunner();
        var sandbox = new SandboxTool(runner, "da-spark-master", "da-flink-jobmanager");
        assertThat(sandbox.sparkContainer()).isEqualTo("da-spark-master");
        assertThat(sandbox.flinkContainer()).isEqualTo("da-flink-jobmanager");
    }

    @Test
    void shouldSelectFlinkContainerForFlinkSql() {
        var runner = new DockerCommandRunner();
        var sandbox = new SandboxTool(runner, "da-spark-master", "da-flink-jobmanager");

        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.engineDecision(new Spec.EngineDecision("flink_sql", "流式"));

        var container = sandbox.selectContainer(spec);
        assertThat(container).isEqualTo("da-flink-jobmanager");
    }

    @Test
    void shouldDefaultToSparkForUnknownEngine() {
        var runner = new DockerCommandRunner();
        var sandbox = new SandboxTool(runner, "da-spark-master", "da-flink-jobmanager");

        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        // No engine decision → default to spark
        var container = sandbox.selectContainer(spec);
        assertThat(container).isEqualTo("da-spark-master");
    }

    @Test
    void shouldUseFlinkSqlClientForFlinkSqlExecution() {
        var runner = new DockerCommandRunner();
        var sandbox = new SandboxTool(runner, "da-spark-master", "da-flink-jobmanager");
        var cmd = sandbox.buildExecutionCommand("da-flink-jobmanager", "SELECT 1;");
        // Flink uses sql-client.sh, Spark uses spark-sql
        assertThat(cmd).anyMatch(s -> s.contains("sql-client.sh"));
    }

    @Test
    void shouldSupportDualDryRun() {
        var runner = new DockerCommandRunner();
        var sandbox = new SandboxTool(runner, "da-spark-master", "da-flink-jobmanager");

        var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
        spec.originalPipeline("SELECT cell_id, COUNT(*) FROM signaling_events WHERE event_type='handover' GROUP BY cell_id;");
        spec.engineDecision(new Spec.EngineDecision("flink_sql", "反向合成"));

        var generatedCode = """
                ```sql
                INSERT INTO signaling_events VALUES
                ('cell_A', 'handover', 'failure', 1000),
                ('cell_B', 'handover', 'success', 2000);
                ```""";

        var result = sandbox.dualDryRun(generatedCode, spec);
        // Without Docker, both steps should fail gracefully
        assertThat(result).isNotNull();
        assertThat(result).containsKey("step1_result");
        assertThat(result).containsKey("step2_result");
    }

    @Test
    void shouldStep1GenerateDataAndStep2Validate() {
        var runner = new DockerCommandRunner();
        var sandbox = new SandboxTool(runner, "da-spark-master", "da-flink-jobmanager");

        var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
        spec.originalPipeline("SELECT * FROM handover_output;");
        spec.engineDecision(new Spec.EngineDecision("spark_sql", "反向合成 Spark SQL"));

        var generatedCode = "```sql\nINSERT INTO handover_output SELECT 'cell_A', 10;\n```";

        var result = sandbox.dualDryRun(generatedCode, spec);
        assertThat(result).containsKey("step1_result");
        assertThat(result).containsKey("step2_result");
    }

    @Test
    void shouldBuildJavaExecutionCommandForFlink() {
        var runner = new DockerCommandRunner();
        var sandbox = new SandboxTool(runner, "da-spark-master", "da-flink-jobmanager");

        var cmd = sandbox.buildJavaExecutionCommand("da-flink-jobmanager", "SyntheticDataGenerator.java");
        assertThat(cmd).anyMatch(s -> s.contains("flink"));
        assertThat(cmd).anyMatch(s -> s.contains("run") || s.contains("sql-client"));
    }
}
