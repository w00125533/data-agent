package com.wireless.agent;

import com.wireless.agent.core.AgentCore;
import com.wireless.agent.core.EngineSelector;
import com.wireless.agent.core.Spec;
import com.wireless.agent.knowledge.DomainKnowledgeBase;
import com.wireless.agent.llm.DeepSeekClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationTest {

    /** Simulates a full agent conversation across multiple turns. */
    static class FakeLLMForIntegration extends DeepSeekClient {
        private int turn;

        FakeLLMForIntegration() {
            super("https://fake", "sk-fake", "fake-model");
        }

        @Override
        public String chat(List<Map<String, String>> messages) {
            turn++;
            if (turn == 1) {
                return """
                {
                  "intent_update": {
                    "target_name": "weak_cov_by_district",
                    "business_definition": "近30天5G弱覆盖小区按区县统计",
                    "kpi_family": "coverage",
                    "ne_grain": "district",
                    "time_grain": "day",
                    "rat": "5G_SA",
                    "timeliness": "batch_daily",
                    "identified_sources": ["dw.mr_5g_15min", "dim.engineering_param"],
                    "open_questions": []
                  },
                  "next_action": "ready_for_tools",
                  "clarifying_question": null
                }""";
            }
            // Turn 2+ is the codegen call: return a plausible Spark SQL block
            return """
            ```sql
            -- Weak coverage cells by district (Spark SQL)
            CREATE OR REPLACE TEMP VIEW weak_cov_by_district AS
            SELECT
                e.district,
                COUNT(DISTINCT m.cell_id) AS weak_cell_count,
                AVG(m.rsrp_avg) AS avg_rsrp,
                AVG(m.weak_cov_ratio) AS avg_weak_cov_ratio,
                SUM(m.sample_count) AS total_samples
            FROM dw.mr_5g_15min m
            JOIN dim.engineering_param e ON m.cell_id = e.cell_id
            WHERE m.rsrp_avg < -110
              AND m.weak_cov_ratio > 0.3
            GROUP BY e.district
            ORDER BY avg_weak_cov_ratio DESC;
            ```""";
        }

        @Override
        public String chat(okhttp3.OkHttpClient http, List<Map<String, String>> msgs) {
            return chat(msgs);
        }
    }

    @Test
    void shouldCompleteFullLoopForWeakCoverageScenario() {
        var agent = new AgentCore(
            new FakeLLMForIntegration(),
            Spec.TaskDirection.FORWARD_ETL
        );
        var result = agent.processMessage("给我近30天每个区县5G弱覆盖小区清单");

        assertThat(result.get("next_action")).isIn("code_done", "dry_run_ok", "sandbox_failed");
        var code = result.get("code").toString();
        assertThat(code.length()).isGreaterThan(50);
        assertThat(code.toLowerCase()).contains("select");
        assertThat(agent.spec().state()).isEqualTo(Spec.SpecState.CODEGEN_DONE);
    }

    @Test
    void shouldProduceCodeWithoutLlmClient() {
        var agent = new AgentCore(null);
        var result = agent.processMessage("弱覆盖小区");

        assertThat(result.get("next_action")).isIn("code_done", "dry_run_ok", "sandbox_failed");
        assertThat(result.get("code")).isNotNull();
        assertThat(result.get("code").toString()).contains("SELECT");
    }

    @Test
    void shouldReturnCodeForHandoverScenario() {
        var agent = new AgentCore(null);
        var result = agent.processMessage("按cell_id汇总切换失败次数");

        assertThat(result.get("next_action")).isIn("code_done", "dry_run_ok", "sandbox_failed");
        assertThat(result.get("code")).isNotNull();
    }

    @Test
    void shouldRunFullPipelineWithRealToolsAndFallback() {
        // Uses HMS fallback (mock schemas) + real Validator + Sandbox
        var agent = new AgentCore(
                null,  // no LLM, uses mock extraction
                Spec.TaskDirection.FORWARD_ETL,
                "thrift://nonexistent:9999",  // HMS fallback -> mock
                "da-spark-master"
        );
        var result = agent.processMessage("给我近30天每个区县5G弱覆盖小区清单");

        assertThat(result.get("next_action"))
                .isIn("code_done", "dry_run_ok", "sandbox_failed");
        assertThat(result.get("code")).isNotNull();
        assertThat(result.get("code").toString()).isNotEmpty();
        // Warnings and preview should be present
        assertThat(result).containsKey("warnings");
        assertThat(result).containsKey("preview");
    }

    @Test
    void shouldProcessCoverageTaskWithDomainKnowledge() {
        var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
                "thrift://nonexistent:9999", "da-spark-master");

        var result = agent.processMessage("近7天每个区县5G弱覆盖小区清单，RSRP<-110dBm占比>30%");

        assertThat(result.get("next_action"))
                .isIn("code_done", "dry_run_ok", "sandbox_failed");
        assertThat(result.get("code").toString()).isNotEmpty();

        var kbHits = agent.kb().search("弱覆盖");
        assertThat(kbHits).isNotEmpty();
        assertThat(kbHits.get(0).name()).contains("弱覆盖");
    }

    @Test
    void shouldProcessMobilityTaskWithDomainKnowledge() {
        var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
                "thrift://nonexistent:9999", "da-spark-master");

        var result = agent.processMessage("按cell汇总最近切换失败原因分布，包括早切晚切");

        assertThat(result.get("next_action"))
                .isIn("code_done", "dry_run_ok", "sandbox_failed");
        assertThat(result.get("code").toString()).isNotEmpty();

        var kbHits = agent.kb().search("早切");
        assertThat(kbHits).isNotEmpty();
    }

    @Test
    void shouldCreateBaselineAndPreferItForDryRun() {
        var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
                "thrift://nonexistent:9999", "da-spark-master");
        var baseline = agent.baselineService();

        baseline.recordBaseline("dw.mr_5g_15min", "baseline.dw__mr_5g_15min",
                1, Map.of("row_count", 1000));

        assertThat(baseline.hasBaseline("dw.mr_5g_15min")).isTrue();
        assertThat(baseline.resolveTable("dw.mr_5g_15min"))
                .isEqualTo("baseline.dw__mr_5g_15min");
    }

    @Test
    void shouldNotHaveBaselineForUnknownTable() {
        var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
                "thrift://nonexistent:9999", "da-spark-master");
        var baseline = agent.baselineService();

        assertThat(baseline.hasBaseline("unknown.table")).isFalse();
        assertThat(baseline.resolveTable("unknown.table")).isEqualTo("unknown.table");
    }

    @Test
    void shouldProcessFlinkSqlTaskWithKafkaSource() {
        var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
                "thrift://nonexistent:9999", "da-spark-master", "da-flink-jobmanager",
                new DomainKnowledgeBase());

        var result = agent.processMessage("实时监控最近1小时每个小区的切换失败次数");

        assertThat(result.get("next_action"))
                .isIn("code_done", "dry_run_ok", "sandbox_failed");
        var code = result.get("code").toString();
        assertThat(code).isNotEmpty();
    }

    @Test
    void shouldProcessSparkSqlTaskWithBatchSources() {
        var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
                "thrift://nonexistent:9999", "da-spark-master", "da-flink-jobmanager",
                new DomainKnowledgeBase());

        var result = agent.processMessage("按区县统计最近30天弱覆盖小区数量");

        assertThat(result.get("next_action"))
                .isIn("code_done", "dry_run_ok", "sandbox_failed");
        var code = result.get("code").toString();
        assertThat(code).isNotEmpty();
        // Spark SQL batch task
        var engine = result.get("engine").toString();
        assertThat(engine).isIn("spark_sql", "flink_sql");
    }

    @Test
    void shouldSupportAllThreeEngineTypes() {
        // Verify EngineSelector covers all 3 types
        var sparkSpec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        sparkSpec.sources(List.of(new Spec.SourceBinding().role("main")
                .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))));
        assertThat(EngineSelector.select(sparkSpec).recommended()).isEqualTo("spark_sql");

        var flinkSpec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        flinkSpec.sources(List.of(new Spec.SourceBinding().role("stream")
                .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))));
        assertThat(EngineSelector.select(flinkSpec).recommended()).isEqualTo("flink_sql");

        var javaFlinkSpec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        javaFlinkSpec.target(new Spec.TargetSpec()
                .name("stateful").businessDefinition("复杂状态机分析"));
        javaFlinkSpec.sources(List.of(new Spec.SourceBinding().role("stream")
                .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))));
        assertThat(EngineSelector.select(javaFlinkSpec).recommended())
                .isEqualTo("java_flink_streamapi");
    }

    @Test
    void shouldProcessReverseSyntheticTaskWithPastedPipeline() {
        var agent = new AgentCore(null, Spec.TaskDirection.REVERSE_SYNTHETIC,
                "thrift://nonexistent:9999", "da-spark-master", "da-flink-jobmanager",
                new DomainKnowledgeBase());

        var result = agent.processMessage(
                "INSERT INTO handover_kpi " +
                "SELECT cell_id, COUNT(*) AS failure_count " +
                "FROM signaling_events " +
                "WHERE event_type = 'handover' AND result = 'failure' " +
                "GROUP BY cell_id;");

        assertThat(result.get("next_action"))
                .isIn("ask_clarifying", "code_done", "dry_run_ok",
                      "dual_dry_run_ok", "step1_ok_step2_failed", "sandbox_failed");
        var code = result.get("code").toString();
        assertThat(code).isNotEmpty();
        // Reverse synthetic should generate data production code (INSERT or Java generator)
        assertThat(code.toLowerCase()).containsAnyOf("insert", "generator", "synthetic");
    }

    @Test
    void shouldStoreOriginalPipelineInSpec() {
        var agent = new AgentCore(null, Spec.TaskDirection.REVERSE_SYNTHETIC,
                "thrift://nonexistent:9999", "da-spark-master", "da-flink-jobmanager",
                new DomainKnowledgeBase());

        var pipelineCode = "SELECT * FROM signaling_events WHERE event_type='handover';";
        agent.processMessage(pipelineCode);

        assertThat(agent.spec().originalPipeline()).isEqualTo(pipelineCode);
    }

    @Test
    void shouldVerifyReverseEngineSelection() {
        // REVERSE_SYNTHETIC with Kafka source -> java_flink_streamapi
        var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
        spec.sources(List.of(new Spec.SourceBinding().role("stream")
                .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))));
        spec.target(new Spec.TargetSpec().name("test").businessDefinition("生成测试数据"));

        var decision = EngineSelector.select(spec);
        assertThat(decision.recommended()).isEqualTo("java_flink_streamapi");
        assertThat(decision.reasoning()).contains("反向合成");
    }
}
