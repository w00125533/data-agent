package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Creates and manages 1% sample baselines in HDFS at /baseline/ paths.
 *  Uses spark-sql via DockerCommandRunner to run CREATE TABLE AS SELECT ... WHERE rand() < 0.01.
 *  Baseline metadata is tracked in-memory (v1; future: persist to file). */
public class BaselineService implements Tool {

    private static final double DEFAULT_SAMPLE_PCT = 1.0;

    private final DockerCommandRunner runner;
    private final String sparkContainer;
    private final Map<String, Map<String, Object>> baselineMeta = new ConcurrentHashMap<>();

    public BaselineService(DockerCommandRunner runner, String sparkContainer) {
        this.runner = runner;
        this.sparkContainer = sparkContainer;
    }

    @Override
    public String name() { return "baseline"; }

    @Override
    public String description() { return "创建 1% 采样基线到 HDFS /baseline/ 路径，管理基线元数据"; }

    @Override
    public ToolResult run(Spec spec) {
        if (spec.sources().isEmpty()) {
            return ToolResult.fail("No sources to baseline");
        }
        var results = new LinkedHashMap<String, Object>();
        var failed = new ArrayList<String>();
        for (var src : spec.sources()) {
            var binding = src.binding();
            if (binding == null) continue;
            var tbl = binding.getOrDefault("table_or_topic", "").toString();
            if (!tbl.isEmpty()) {
                var result = createBaseline(tbl, DEFAULT_SAMPLE_PCT);
                if (result.success()) {
                    results.put(tbl, result.data());
                } else {
                    failed.add(tbl);
                }
            }
        }
        if (results.isEmpty() && !failed.isEmpty()) {
            return ToolResult.fail("All baseline creations failed: " + String.join(", ", failed));
        }
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("type", "baseline_created");
        var findings = new LinkedHashMap<String, Object>();
        findings.put("baseline_count", results.size());
        if (!failed.isEmpty()) {
            findings.put("failed", String.join(", ", failed));
        }
        evidence.put("findings", findings);
        return ToolResult.ok(results, evidence);
    }

    /** Create a baseline sample for a single source table. */
    public ToolResult createBaseline(String sourceTable, double samplePercent) {
        var baselineTbl = baselineTableName(sourceTable);
        var sql = buildCreateBaselineSql(sourceTable, baselineTbl, samplePercent);

        try {
            var result = runner.exec(sparkContainer,
                    List.of("spark-sql", "--master", "spark://spark-master:7077",
                            "-e", sql));
            if (!result.isSuccess()) {
                return ToolResult.fail("Baseline creation failed: " + result.stderr(),
                        Map.of("source_table", sourceTable));
            }

            // Count the baseline rows
            var countSql = buildCountSql(baselineTbl);
            var countResult = runner.exec(sparkContainer,
                    List.of("spark-sql", "--master", "spark://spark-master:7077",
                            "-e", countSql));
            var rowCount = countResult.isSuccess() ? parseCount(countResult.stdout()) : 0;

            var stats = Map.of("row_count", (Object) rowCount);
            recordBaseline(sourceTable, baselineTbl, (long) samplePercent, stats);

            return ToolResult.ok(Map.of(
                    "source_table", sourceTable,
                    "baseline_table", baselineTbl,
                    "baseline_rows", rowCount,
                    "sample_pct", samplePercent
            ));
        } catch (Exception e) {
            return ToolResult.fail("Baseline creation error: " + e.getMessage(),
                    Map.of("source_table", sourceTable));
        }
    }

    /** Record baseline metadata in-memory. */
    public void recordBaseline(String sourceTable, String baselineTable,
                                long sampleNumerator, Map<String, Object> stats) {
        var meta = new LinkedHashMap<String, Object>();
        meta.put("source_table", sourceTable);
        meta.put("baseline_table", baselineTable);
        meta.put("sample_rate", sampleNumerator + "%");
        meta.put("created_at", java.time.Instant.now().toString());
        meta.put("stats", stats);
        baselineMeta.put(sourceTable, meta);
    }

    /** Get baseline metadata for a source table. */
    public Optional<Map<String, Object>> getBaselineMeta(String sourceTable) {
        return Optional.ofNullable(baselineMeta.get(sourceTable));
    }

    /** Check if a baseline exists for the given source table. */
    public boolean hasBaseline(String sourceTable) {
        return baselineMeta.containsKey(sourceTable);
    }

    /** Get the baseline table name if one exists, otherwise return the original table. */
    public String resolveTable(String sourceTable) {
        var meta = baselineMeta.get(sourceTable);
        if (meta != null) {
            return meta.get("baseline_table").toString();
        }
        return sourceTable;
    }

    /** Convert a source table name to a baseline table name.
     *  e.g., "dw.mr_5g_15min" → "baseline.dw__mr_5g_15min" */
    public static String baselineTableName(String sourceTable) {
        Objects.requireNonNull(sourceTable, "sourceTable must not be null");
        return "baseline." + sourceTable.replace(".", "__").replace("-", "_");
    }

    /** Build the CREATE TABLE AS SELECT SQL for baseline sampling. */
    public static String buildCreateBaselineSql(String sourceTable, String baselineTable,
                                                  double samplePercent) {
        var pct = samplePercent / 100.0;
        return String.format(
                "CREATE OR REPLACE TABLE %s AS SELECT * FROM %s WHERE rand() < %.4f;",
                baselineTable, sourceTable, pct);
    }

    /** Build a COUNT(*) query for the baseline table. */
    public static String buildCountSql(String baselineTable) {
        return String.format("SELECT COUNT(*) FROM %s;", baselineTable);
    }

    private int parseCount(String stdout) {
        try {
            for (var line : stdout.trim().split("\\n")) {
                var trimmed = line.trim();
                if (trimmed.matches("\\d+")) {
                    return Integer.parseInt(trimmed);
                }
            }
        } catch (NumberFormatException e) {
            // ignore
        }
        return 0;
    }
}
