package com.wireless.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wireless.agent.core.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalCaseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldDeserializeEvalCaseFromJson() throws Exception {
        var json = """
        {
          "id": "eval-coverage-001",
          "category": "coverage",
          "direction": "forward_etl",
          "engine": "spark_sql",
          "nl_input": "给我近30天每个区县5G弱覆盖小区清单",
          "expected_spec": {
            "target_name": "weak_cov_by_district",
            "kpi_family": "coverage",
            "ne_grain": "district",
            "time_grain": "day",
            "rat": "5G_SA",
            "timeliness": "batch_daily",
            "sources": ["dw.mr_5g_15min", "dim.engineering_param"]
          },
          "expected_code_patterns": ["rsrp_avg", "weak_cov_ratio"],
          "max_turns": 5,
          "must_compile": true,
          "must_dry_run": true
        }""";
        var c = MAPPER.readValue(json, EvalCase.class);
        assertThat(c.id()).isEqualTo("eval-coverage-001");
        assertThat(c.category()).isEqualTo(EvalCategory.COVERAGE);
        assertThat(c.direction()).isEqualTo(Spec.TaskDirection.FORWARD_ETL);
        assertThat(c.engine()).isEqualTo("spark_sql");
        assertThat(c.nlInput()).contains("弱覆盖");
        assertThat(c.expectedSpec().get("target_name")).isEqualTo("weak_cov_by_district");
        assertThat(c.expectedSpec().get("kpi_family")).isEqualTo("coverage");
        assertThat(c.expectedCodePatterns()).containsExactly("rsrp_avg", "weak_cov_ratio");
        assertThat(c.maxTurns()).isEqualTo(5);
        assertThat(c.mustCompile()).isTrue();
    }

    @Test
    void shouldDeserializeEvalCaseWithFakeLlmResponses() throws Exception {
        var json = """
        {
          "id": "eval-reverse-001",
          "category": "reverse",
          "direction": "reverse_synthetic",
          "engine": "java_flink_streamapi",
          "nl_input": "这是切换失败分析 Flink SQL:\\nINSERT INTO ...",
          "fake_llm_responses": ["resp1", "resp2"],
          "expected_spec": {"target_name": "synthetic_test_data"},
          "expected_code_patterns": ["DataStream", "generator"],
          "max_turns": 3,
          "must_compile": false,
          "must_dry_run": false
        }""";
        var c = MAPPER.readValue(json, EvalCase.class);
        assertThat(c.fakeLlmResponses()).containsExactly("resp1", "resp2");
        assertThat(c.direction()).isEqualTo(Spec.TaskDirection.REVERSE_SYNTHETIC);
    }

    @Test
    void shouldSerializeBackToJson() throws Exception {
        var c = new EvalCase("test-001", EvalCategory.COVERAGE,
                Spec.TaskDirection.FORWARD_ETL, "spark_sql",
                "给我弱覆盖数据",
                Map.of("target_name", "test", "kpi_family", "coverage",
                        "ne_grain", "cell", "sources", List.of("dw.mr_5g_15min")),
                List.of("SELECT", "rsrp"),
                List.of(), 5, true, true);
        var json = MAPPER.writeValueAsString(c);
        assertThat(json).contains("test-001");
        assertThat(json).contains("coverage");
    }
}
