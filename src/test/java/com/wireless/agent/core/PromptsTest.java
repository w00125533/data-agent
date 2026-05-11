package com.wireless.agent.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class PromptsTest {

    @Test
    void systemPromptShouldContainWirelessDomainKnowledge() {
        assertThat(Prompts.SYSTEM_PROMPT).contains("无线网络");
        assertThat(Prompts.SYSTEM_PROMPT).contains("弱覆盖");
        assertThat(Prompts.SYSTEM_PROMPT).contains("RSRP");
    }

    @Test
    void extractSpecPromptShouldIncludeUserMessage() {
        var prompt = Prompts.buildExtractSpecPrompt(
            "给我30天弱覆盖小区",
            "{\"task_direction\": \"forward_etl\"}"
        );
        assertThat(prompt).contains("弱覆盖小区");
        assertThat(prompt).contains("forward_etl");
    }

    @Test
    void clarifyPromptShouldContainOpenQuestions() {
        var questions = List.of(
            new Spec.Question("a", "什么是活跃用户？", List.of())
        );
        var prompt = Prompts.buildClarifyPrompt(questions);
        assertThat(prompt).contains("活跃用户");
    }

    @Test
    void shouldBuildReverseExtractPromptWithPipelineCode() {
        var prompt = Prompts.buildReverseExtractPrompt(
                "INSERT INTO t SELECT * FROM src WHERE event='ho'",
                "{}");

        assertThat(prompt).contains("原始流水线");
        assertThat(prompt).contains("event='ho'");
        assertThat(prompt).contains("输入表");
    }

    @Test
    void shouldBuildReverseClarifyPrompt() {
        var prompt = Prompts.buildReverseClarifyPrompt("handover_failure",
                List.of("data_scale", "anomaly_ratio"));

        assertThat(prompt).contains("数据规模");
        assertThat(prompt).contains("异常比例");
        assertThat(prompt).contains("handover_failure");
    }

    @Test
    void shouldHaveReverseSystemPrompt() {
        var prompt = Prompts.REVERSE_SYNTHETIC_SYSTEM_PROMPT;
        assertThat(prompt).contains("反向合成");
        assertThat(prompt).contains("数据生产");
        assertThat(prompt).contains("分布");
    }

    @Test
    void shouldBuildReplyParsingPrompt() {
        var currentQuestion = new Spec.Question("time_grain", "时间粒度是小时还是天?",
                List.of("hour", "day"));
        var prompt = Prompts.buildReplyParsingPrompt("按天统计", currentQuestion, "{}");

        assertThat(prompt).contains("时间粒度");
        assertThat(prompt).contains("按天统计");
        assertThat(prompt).contains("hour");
        assertThat(prompt).contains("day");
        assertThat(prompt).contains("field_path");
    }

    @Test
    void shouldBuildClarifyPromptWithUnansweredQuestions() {
        var qs = List.of(
                new Spec.Question("target_name", "目标表名?", List.of()),
                new Spec.Question("time_grain", "时间粒度?", List.of("hour", "day")));

        var prompt = Prompts.buildClarifyPrompt(qs);
        assertThat(prompt).contains("目标表名");
        assertThat(prompt).contains("时间粒度");
        assertThat(prompt).contains("一次只问一个");
    }
}
