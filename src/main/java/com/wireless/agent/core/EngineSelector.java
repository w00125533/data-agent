package com.wireless.agent.core;

import com.wireless.agent.prefs.UserPreferencesStore;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class EngineSelector {

    private EngineSelector() {}

    private static final Spec.EngineDecision FALLBACK =
            new Spec.EngineDecision("spark_sql", "无法自动判定,默认 Spark SQL (需人工确认)",
                    null, Map.of("submission_mode", "one_shot"));

    private static final List<String> VALID_ENGINES = List.of(
            "spark_sql", "flink_sql", "java_flink_streamapi");

    record Rule(String recommended, String reasoning, Predicate<Spec> condition) {}

    private static final List<Rule> RULES = List.of(
        new Rule("java_flink_streamapi",
            "反向合成数据生产任务,需编程控制数据分布 → Java Flink Stream API",
            spec -> spec.taskDirection() == Spec.TaskDirection.REVERSE_SYNTHETIC
                    && spec.sources().stream().anyMatch(s -> {
                        var cat = s.binding().get("catalog");
                        return "kafka".equals(cat);
                    })),
        new Rule("java_flink_streamapi",
            "任务含复杂状态/递归/外部服务调用,SQL无法表达 → Java Flink Stream API",
            spec -> {
                var def = spec.target() != null ? spec.target().businessDefinition() : "";
                var lower = def.toLowerCase();
                return lower.contains("复杂状态") || lower.contains("状态机")
                    || lower.contains("递归") || lower.contains("外部服务")
                    || lower.contains("自定义算子") || lower.contains("custom operator");
            }),
        new Rule("flink_sql",
            "源含 Kafka/CDC 流式数据,需时间窗口或流式语义 → Flink SQL",
            spec -> spec.sources().stream().anyMatch(s -> {
                var cat = s.binding().get("catalog");
                return "kafka".equals(cat) || "cdc".equals(cat);
            })),
        new Rule("spark_sql",
            "多批源 join/聚合,无流式要求 → Spark SQL",
            spec -> spec.sources().size() >= 2
                    && spec.sources().stream().allMatch(s -> {
                        var cat = s.binding().get("catalog");
                        return cat == null || "hive".equals(cat) || "starrocks".equals(cat);
                    })),
        new Rule("spark_sql",
            "单一批源,简单查询或聚合 → Spark SQL",
            spec -> spec.sources().stream().allMatch(s -> {
                var cat = s.binding().get("catalog");
                return cat == null || "hive".equals(cat) || "starrocks".equals(cat);
            }))
    );

    public static Spec.EngineDecision select(Spec spec) {
        if (spec.sources().isEmpty()) return FALLBACK;
        for (var rule : RULES) {
            if (rule.condition().test(spec)) {
                return new Spec.EngineDecision(rule.recommended(), rule.reasoning(),
                        null, Map.of("submission_mode", "one_shot"));
            }
        }
        return FALLBACK;
    }

    /** Select engine with optional user preference override. */
    public static Spec.EngineDecision select(Spec spec,
            UserPreferencesStore prefs, String userId) {
        if (prefs != null && userId != null) {
            var preferred = prefs.get(userId, "engine_preference", "");
            if (!preferred.isEmpty() && VALID_ENGINES.contains(preferred)) {
                return new Spec.EngineDecision(preferred,
                        "用户偏好引擎: " + preferred, true,
                        Map.of("submission_mode", "one_shot"));
            }
        }
        return select(spec);
    }

    /** Select engine with a direct engine name override (for testing). */
    public static Spec.EngineDecision select(Spec spec, String directOverride) {
        if (directOverride != null && VALID_ENGINES.contains(directOverride)) {
            return new Spec.EngineDecision(directOverride,
                    "用户指定引擎: " + directOverride, true,
                    Map.of("submission_mode", "one_shot"));
        }
        return select(spec);
    }
}
