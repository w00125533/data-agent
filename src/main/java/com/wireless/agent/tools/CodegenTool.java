package com.wireless.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.wireless.agent.core.Spec;
import com.wireless.agent.llm.DeepSeekClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CodegenTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    public static final String CODGEN_SYSTEM_PROMPT = """
            You are a Spark SQL code generator for wireless network perception tasks.

            Rules:
            1. Output ONLY the SQL code, no explanation before or after.
            2. Use Hive-style Spark SQL (Spark 3.5, Hive 4.0 Metastore).
            3. Include CREATE TABLE AS or INSERT OVERWRITE as the target table.
            4. Join columns must match their semantic meaning (e.g., cell_id joins with cell_id).
            5. Aggregations use GROUP BY at the grain specified in the spec.
            6. Filter conditions must reflect the business definition (e.g., weak coverage = RSRP < -110).
            7. Use COALESCE for null handling in key columns.
            8. Wrap the final SQL in a ```sql code block.
            """;

    public static final String FLINK_SQL_SYSTEM_PROMPT = """
            You are a Flink SQL code generator for wireless network perception tasks.

            Rules:
            1. Output ONLY the Flink SQL code, no explanation before or after.
            2. Use Flink SQL syntax (Flink 1.18+ with Hive catalog integration).
            3. Kafka sources must declare watermark: WATERMARK FOR ts AS ts - INTERVAL '5' SECOND.
            4. Time-windowed aggregations use TUMBLE/HOP/SESSION window functions.
            5. Include CREATE TABLE AS or INSERT INTO for target table.
            6. Join columns must match their semantic meaning.
            7. Filter conditions must reflect the business definition.
            8. For lookup join with Hive dim table, use FOR SYSTEM_TIME AS OF syntax.
            9. Wrap the final SQL in a ```sql code block.
            """;

    // Placeholder — will be replaced by Task 3
    public static final String JAVA_FLINK_SYSTEM_PROMPT = """
            You are a Java Flink Stream API code generator.
            """;

    private final DeepSeekClient llmClient;

    public CodegenTool(DeepSeekClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public String name() { return "codegen"; }

    @Override
    public String description() {
        return "Generate Spark SQL / Flink SQL / Java code from a completed Spec";
    }

    @Override
    public ToolResult run(Spec spec) {
        try {
            var prompt = buildCodegenPrompt(spec);

            String code;
            if (llmClient != null) {
                var messages = List.of(
                    Map.of("role", "system", "content", selectSystemPrompt(spec)),
                    Map.of("role", "user", "content", prompt)
                );
                code = llmClient.chat(messages);
                if (code.startsWith("[ERROR]")) {
                    code = hardcodedCode(spec);
                }
            } else {
                code = hardcodedCode(spec);
            }
            return ToolResult.ok(Map.of(
                "code", code,
                "engine", spec.engineDecision().recommended()
            ));
        } catch (Exception e) {
            return ToolResult.fail("Codegen failed: " + e.getMessage());
        }
    }

    public static String buildCodegenPrompt(Spec spec) {
        var target = spec.target();
        var engine = spec.engineDecision();
        var schemaLines = new ArrayList<String>();

        for (var src : spec.sources()) {
            var tbl = src.binding().getOrDefault("table_or_topic", "unknown").toString();
            schemaLines.add("- " + src.role() + ": " + tbl);
            if (src.schema_() != null) {
                for (var col : src.schema_()) {
                    schemaLines.add("    " + col.get("name") + " (" + col.get("type")
                            + "): " + col.getOrDefault("semantic", ""));
                }
            }
        }
        var schemaBlock = schemaLines.isEmpty()
                ? "(no schema bound yet)"
                : String.join("\n", schemaLines);

        try {
            var ncJson = MAPPER.writeValueAsString(spec.networkContext());
            var transforms = spec.transformations().stream()
                    .map(Spec.TransformStep::description)
                    .collect(Collectors.joining(", "));
            if (transforms.isEmpty()) transforms = "(auto-infer joins and aggregation from source roles)";

            return String.format("""
                    Generate %s for the following wireless network perception spec:

                    Task: %s
                    Target table: %s
                    Business definition: %s
                    Output grain: %s
                    Network context: %s

                    Sources:
                    %s

                    Requirements:
                    - Output grain: %s
                    - Engine: %s (rationale: %s)
                    - Transformations: %s
                    """,
                    displayEngineName(engine.recommended()),
                    spec.taskDirection().value(),
                    target != null ? target.name() : "(unspecified)",
                    target != null ? target.businessDefinition() : "(unspecified)",
                    target != null ? target.grain() : "(unspecified)",
                    ncJson,
                    schemaBlock,
                    target != null ? target.grain() : "cell × day",
                    engine.recommended(),
                    engine.reasoning(),
                    transforms
            );
        } catch (Exception e) {
            return "Generate Spark SQL for: "
                    + (target != null ? target.businessDefinition() : "unknown task");
        }
    }

    /** Fallback hardcoded SQL for the 2 demo M0b scenarios. */
    static String hardcodedSparkSql(Spec spec) {
        var target = spec.target();
        var def = target != null ? target.businessDefinition() : "";

        if (def.contains("弱覆盖") || def.toLowerCase().contains("weak")) {
            return """
                    ```sql
                    -- 弱覆盖小区按区县统计 (Spark SQL)
                    CREATE OR REPLACE TEMP VIEW weak_cov_cells AS
                    SELECT
                        m.cell_id,
                        e.district,
                        e.rat,
                        AVG(m.rsrp_avg) AS avg_rsrp,
                        AVG(m.weak_cov_ratio) AS avg_weak_cov_ratio,
                        SUM(m.sample_count) AS total_samples
                    FROM dw.mr_5g_15min m
                    JOIN dim.engineering_param e ON m.cell_id = e.cell_id
                    WHERE m.rsrp_avg < -110
                      AND m.weak_cov_ratio > 0.3
                    GROUP BY m.cell_id, e.district, e.rat
                    ORDER BY e.district, avg_weak_cov_ratio DESC;
                    ```""";
        }
        return String.format("""
                ```sql
                -- %s (Spark SQL)
                SELECT * FROM dw.mr_5g_15min LIMIT 100;
                ```""",
                target != null ? target.name() : "output");
    }

    private static String selectSystemPrompt(Spec spec) {
        var engine = spec.engineDecision();
        if (engine == null) return CODGEN_SYSTEM_PROMPT;
        return switch (engine.recommended()) {
            case "flink_sql" -> FLINK_SQL_SYSTEM_PROMPT;
            case "java_flink_streamapi" -> JAVA_FLINK_SYSTEM_PROMPT;
            default -> CODGEN_SYSTEM_PROMPT;
        };
    }

    private static String displayEngineName(String engineId) {
        return switch (engineId) {
            case "flink_sql" -> "Flink SQL";
            case "java_flink_streamapi" -> "Java Flink Stream API";
            case "spark_sql" -> "Spark SQL";
            default -> engineId;
        };
    }

    /** Fallback hardcoded code for all engines when LLM is unavailable. */
    static String hardcodedCode(Spec spec) {
        var engine = spec.engineDecision();
        if (engine == null) return hardcodedSparkSql(spec);
        return switch (engine.recommended()) {
            case "flink_sql" -> hardcodedFlinkSql(spec);
            case "java_flink_streamapi" -> hardcodedJavaFlink(spec);
            default -> hardcodedSparkSql(spec);
        };
    }

    /** Fallback hardcoded Flink SQL for demo scenarios. */
    static String hardcodedFlinkSql(Spec spec) {
        var target = spec.target();
        var def = target != null ? target.businessDefinition() : "";

        if (def.contains("切换失败") || def.toLowerCase().contains("handover failure")) {
            return """
                    ```sql
                    -- 切换失败按小区统计 (Flink SQL, Kafka source + time window)
                    CREATE TABLE IF NOT EXISTS handover_failure_cell_hour (
                        cell_id STRING,
                        window_start TIMESTAMP(3),
                        window_end TIMESTAMP(3),
                        failure_count BIGINT,
                        success_count BIGINT,
                        ho_succ_rate DOUBLE
                    ) WITH (
                        'connector' = 'filesystem',
                        'path' = '/output/handover_failure_cell_hour',
                        'format' = 'parquet'
                    );

                    INSERT INTO handover_failure_cell_hour
                    SELECT
                        src_cell AS cell_id,
                        TUMBLE_START(ts, INTERVAL '1' HOUR) AS window_start,
                        TUMBLE_END(ts, INTERVAL '1' HOUR) AS window_end,
                        SUM(CASE WHEN result = 'failure' THEN 1 ELSE 0 END) AS failure_count,
                        SUM(CASE WHEN result = 'success' THEN 1 ELSE 0 END) AS success_count,
                        CAST(SUM(CASE WHEN result = 'success' THEN 1 ELSE 0 END) AS DOUBLE)
                            / CAST(COUNT(*) AS DOUBLE) * 100 AS ho_succ_rate
                    FROM signaling_events
                    WHERE event_type = 'handover'
                    GROUP BY src_cell, TUMBLE(ts, INTERVAL '1' HOUR);
                    ```""";
        }
        return String.format("""
                ```sql
                -- %s (Flink SQL)
                SELECT * FROM signaling_events WHERE event_type = 'handover' LIMIT 100;
                ```""",
                target != null ? target.name() : "output");
    }

    static String hardcodedJavaFlink(Spec spec) {
        return "```java\n// Java Flink placeholder\n```";
    }
}
