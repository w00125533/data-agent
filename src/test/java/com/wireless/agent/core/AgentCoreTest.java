package com.wireless.agent.core;

import com.wireless.agent.llm.DeepSeekClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCoreTest {

    /** Fake LLM client that returns controlled JSON responses. */
    static class FakeLLMClient extends DeepSeekClient {
        private final List<String> responses;
        private int callCount;

        FakeLLMClient(List<String> responses) {
            super("https://fake", "sk-fake", "fake-model");
            this.responses = responses;
        }

        @Override
        public String chat(List<Map<String, String>> messages) {
            if (callCount < responses.size()) {
                return responses.get(callCount++);
            }
            return "[ERROR] no more fake responses";
        }

        @Override
        public String chat(okhttp3.OkHttpClient http, List<Map<String, String>> msgs) {
            return chat(msgs);
        }
    }

    static String extractResp(String targetName, String kpiFamily) {
        return """
        {
          "intent_update": {
            "target_name": "%s",
            "business_definition": "近30天5G弱覆盖小区按区县统计",
            "kpi_family": "%s",
            "ne_grain": "district",
            "time_grain": "day",
            "rat": "5G_SA",
            "timeliness": "batch_daily",
            "identified_sources": ["dw.mr_5g_15min", "dim.engineering_param"],
            "open_questions": []
          },
          "next_action": "ready_for_tools",
          "clarifying_question": null
        }""".formatted(targetName, kpiFamily);
    }

    static String askResp(String question) {
        return """
        {
          "intent_update": {"open_questions": []},
          "next_action": "ask_clarifying",
          "clarifying_question": "%s"
        }""".formatted(question);
    }

    @Test
    void shouldInitWithEmptySpec() {
        var agent = new AgentCore(new FakeLLMClient(List.of()),
            Spec.TaskDirection.FORWARD_ETL,
            "thrift://nonexistent:9999", "da-spark-master");
        assertThat(agent.spec()).isNotNull();
        assertThat(agent.spec().taskDirection()).isEqualTo(Spec.TaskDirection.FORWARD_ETL);
    }

    @Test
    void shouldExtractIntentFromFirstMessage() {
        var agent = new AgentCore(new FakeLLMClient(List.of(
            extractResp("weak_cov_by_district", "coverage")
        )), Spec.TaskDirection.FORWARD_ETL,
            "thrift://nonexistent:9999", "da-spark-master");
        var result = agent.processMessage("给我近30天5G弱覆盖小区");
        assertThat(agent.spec().target()).isNotNull();
        assertThat(agent.spec().target().businessDefinition()).isNotEmpty();
        assertThat(result).containsKey("next_action");
    }

    @Test
    void shouldAskClarifyingWhenNeeded() {
        var agent = new AgentCore(new FakeLLMClient(List.of(
            askResp("按什么时间粒度？"),
            extractResp("weak_cov", "coverage")
        )), Spec.TaskDirection.FORWARD_ETL,
            "thrift://nonexistent:9999", "da-spark-master");
        var r1 = agent.processMessage("给我弱覆盖数据");
        assertThat(r1.get("next_action")).isEqualTo("ask_clarifying");
        assertThat(r1.get("clarifying_question")).isNotNull();

        var r2 = agent.processMessage("按天汇总");
        assertThat(r2.get("next_action")).isIn("code_done", "dry_run_ok", "sandbox_failed");
    }

    @Test
    void shouldWorkWithoutLlmClient() {
        var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
            "thrift://nonexistent:9999", "da-spark-master");
        var result = agent.processMessage("弱覆盖小区统计");
        assertThat(result.get("next_action")).isIn("code_done", "dry_run_ok", "sandbox_failed");
        assertThat(result.get("code").toString()).contains("SELECT");
    }

    @Test
    void shouldAccumulateSpecAcrossTurns() {
        var agent = new AgentCore(new FakeLLMClient(List.of(
            askResp("什么RAT? 4G or 5G?"),
            extractResp("weak_cov", "coverage")
        )), Spec.TaskDirection.FORWARD_ETL,
            "thrift://nonexistent:9999", "da-spark-master");
        agent.processMessage("给我弱覆盖数据");
        agent.processMessage("5G_SA");
        assertThat(agent.spec().networkContext().rat()).isEqualTo("5G_SA");
    }

    @Test
    void shouldHaveDomainKnowledgeBaseInjected() {
        var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
                "thrift://nonexistent:9999", "da-spark-master");
        assertThat(agent.kb()).isNotNull();
        assertThat(agent.kb().all()).isNotEmpty();
    }

    @Test
    void shouldHaveBaselineServiceInjected() {
        var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
                "thrift://nonexistent:9999", "da-spark-master");
        assertThat(agent.baselineService()).isNotNull();
        assertThat(agent.baselineService().name()).isEqualTo("baseline");
    }
}
