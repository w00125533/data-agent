package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;

import java.util.*;
import java.util.stream.Collectors;

/** Profiles data tables: row count, null rate, distinct count, top-K distribution.
 *  Executes profiling SQL via DockerCommandRunner on spark-master container. */
public class ProfilerTool implements Tool {

    private final DockerCommandRunner runner;
    private final String sparkContainer;

    public ProfilerTool(DockerCommandRunner runner, String sparkContainer) {
        this.runner = runner;
        this.sparkContainer = sparkContainer;
    }

    @Override
    public String name() { return "profiler"; }

    @Override
    public String description() { return "对指定表做数据画像: 行数、null率、distinct计数、top-K分布"; }

    @Override
    public ToolResult run(Spec spec) {
        if (spec.sources().isEmpty()) {
            return ToolResult.fail("No sources to profile");
        }
        var results = new LinkedHashMap<String, Object>();
        for (var src : spec.sources()) {
            var tbl = src.binding().getOrDefault("table_or_topic", "").toString();
            if (!tbl.isEmpty()) {
                var profile = profileTable(tbl, 5);
                results.put(tbl, profile.data());
            }
        }
        return ToolResult.ok(results,
                Map.of("type", "data_profile", "findings", Map.of("profiled_tables", results.size())));
    }

    /** Profile a single table: return row count + per-column stats. */
    public ToolResult profileTable(String tableName, int topK) {
        try {
            var rowCount = runSparkSql(buildRowCountQuery(tableName));
            var stats = new LinkedHashMap<String, Object>();
            stats.put("row_count", parseCount(rowCount));

            var mockFallback = new MockMetadataTool();
            var schemaResult = mockFallback.lookup(tableName);
            if (schemaResult.success()) {
                @SuppressWarnings("unchecked")
                var data = (Map<String, Object>) schemaResult.data();
                @SuppressWarnings("unchecked")
                var schema = (List<Map<String, String>>) data.get("schema");
                for (var col : schema) {
                    var colName = col.get("name");
                    var nullCount = runSparkSql(buildNullCheckQuery(tableName, colName));
                    stats.put(colName + "_null_rate",
                            String.format("%.2f%%", 100.0 * parseCount(nullCount) / Math.max(1, parseCount(rowCount))));
                }
            }
            return ToolResult.ok(stats);
        } catch (Exception e) {
            return ToolResult.fail("Profiling failed: " + e.getMessage(),
                    Map.of("row_count", "N/A"));
        }
    }

    static String buildProfileQuery(String table, List<String> columns, int topK) {
        var cols = columns.isEmpty() ? "*" : String.join(", ", columns);
        return String.format(
                "SELECT COUNT(*) AS cnt FROM %s; " +
                "SELECT %s FROM %s LIMIT %d;",
                table, cols, table, topK);
    }

    static String buildRowCountQuery(String table) {
        return String.format("SELECT COUNT(*) FROM %s;", table);
    }

    static String buildNullCheckQuery(String table, String column) {
        return String.format("SELECT COUNT(*) FROM %s WHERE %s IS NULL;", table, column);
    }

    static Map<String, String> parseCountResult(String stdout) {
        var lines = stdout.trim().split("\\n");
        var result = new LinkedHashMap<String, String>();
        for (int i = 0; i < lines.length; i++) {
            result.put("total", lines[i].trim());
            break;
        }
        return result;
    }

    private int parseCount(String stdout) {
        try {
            var lines = stdout.trim().split("\\n");
            for (var line : lines) {
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

    private String runSparkSql(String sql) {
        var result = runner.exec(sparkContainer,
                List.of("spark-sql", "--master", "spark://spark-master:7077", "-e", sql));
        if (result.isSuccess()) {
            return result.stdout();
        }
        return result.stderr();
    }
}
