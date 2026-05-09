package com.wireless.agent.core;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class EngineSelector {

    private EngineSelector() {}

    private static final Spec.EngineDecision FALLBACK =
            new Spec.EngineDecision("spark_sql", "无法自动判定,默认 Spark SQL(人工确认)");

    record Rule(String recommended, String reasoning, Predicate<Spec> condition) {}

    private static final List<Rule> RULES = List.of(
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
                return new Spec.EngineDecision(rule.recommended(), rule.reasoning());
            }
        }
        return FALLBACK;
    }
}
