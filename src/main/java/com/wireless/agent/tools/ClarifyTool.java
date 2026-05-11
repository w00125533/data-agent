package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;

import java.util.*;

/** Picks the single most important unanswered question and generates a clarifying question. */
public class ClarifyTool implements Tool {

    /** Priority order: target definition > business_definition > sources > grain > timeliness > other. */
    private static final List<String> PRIORITY_ORDER = List.of(
            "target_name", "business_definition", "identified_sources",
            "ne_grain", "time_grain", "rat", "timeliness");

    @Override
    public String name() { return "clarify"; }

    @Override
    public String description() { return "从 open_questions 中挑选最高优先级问题并生成反问"; }

    /** Pick the highest-priority unanswered question. */
    public Spec.Question pickNextQuestion(Spec spec) {
        var unanswered = spec.unansweredQuestions();
        if (unanswered.isEmpty()) return null;

        return unanswered.stream()
                .min(Comparator.comparingInt(q -> {
                    var idx = PRIORITY_ORDER.indexOf(q.fieldPath());
                    return idx >= 0 ? idx : PRIORITY_ORDER.size();
                }))
                .orElse(null);
    }

    /** Detect what's still missing from the spec. */
    public List<String> detectGaps(Spec spec) {
        var gaps = new ArrayList<String>();
        if (spec.target() == null || spec.target().name().isEmpty()) {
            gaps.add("target_name: 目标数据集名称缺失");
        }
        if (spec.target() == null || spec.target().businessDefinition().isEmpty()) {
            gaps.add("business_definition: 业务口径缺失");
        }
        if (spec.sources().isEmpty()) {
            gaps.add("identified_sources: 数据源未确认");
        }
        if (spec.networkContext().timeGrain().isEmpty()
                || spec.networkContext().timeGrain().equals("15min")) {
            gaps.add("time_grain: 时间粒度待确认");
        }
        return gaps;
    }

    @Override
    public ToolResult run(Spec spec) {
        var question = pickNextQuestion(spec);
        if (question == null) {
            return ToolResult.ok(Map.of(
                    "converged", true,
                    "message", "所有问题已回答,可以进入代码生成"));
        }

        var questionText = buildHardcodedQuestion(question, spec);
        return ToolResult.ok(Map.of(
                "converged", false,
                "field_path", question.fieldPath(),
                "question", questionText,
                "candidates", question.candidates()
        ));
    }

    /** Build a clarifying question without LLM (hardcoded templates). */
    private String buildHardcodedQuestion(Spec.Question q, Spec spec) {
        var field = q.fieldPath();
        var def = spec.target() != null ? spec.target().businessDefinition() : "";
        var candidates = q.candidates();

        if (!candidates.isEmpty()) {
            var options = String.join("/", candidates);
            return q.question() + " (" + options + ")";
        }

        return switch (field) {
            case "target_name" ->
                    "目标数据集应该叫什么名字? (如: handover_failure_by_cell)";
            case "business_definition" ->
                    "请一句话描述业务口径: \"" + (def.isEmpty() ? "?" : def) + "\" 具体指什么?";
            case "identified_sources" ->
                    "需要用到哪些数据源? (如: signaling_events, dw.mr_5g_15min)";
            case "time_grain" ->
                    "时间粒度是小时(hour)还是天(day)?";
            case "ne_grain" ->
                    "空间粒度是小区(cell)还是区县(district)?";
            default -> q.question();
        };
    }
}
