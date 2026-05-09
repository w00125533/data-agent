package com.wireless.agent;

import com.wireless.agent.core.AgentCore;
import com.wireless.agent.core.Spec;
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

        assertThat(result.get("next_action")).isEqualTo("code_done");
        var code = result.get("code").toString();
        assertThat(code.length()).isGreaterThan(50);
        assertThat(code.toLowerCase()).contains("select");
        assertThat(agent.spec().state()).isEqualTo(Spec.SpecState.CODEGEN_DONE);
    }

    @Test
    void shouldProduceCodeWithoutLlmClient() {
        var agent = new AgentCore(null);
        var result = agent.processMessage("弱覆盖小区");

        assertThat(result.get("next_action")).isEqualTo("code_done");
        assertThat(result.get("code")).isNotNull();
        assertThat(result.get("code").toString()).contains("SELECT");
    }

    @Test
    void shouldReturnCodeForHandoverScenario() {
        var agent = new AgentCore(null);
        var result = agent.processMessage("按cell_id汇总切换失败次数");

        assertThat(result.get("next_action")).isEqualTo("code_done");
        assertThat(result.get("code")).isNotNull();
    }
}
