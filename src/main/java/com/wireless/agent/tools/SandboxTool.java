package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;

import java.util.*;
import java.util.regex.Pattern;

/** Submits generated Spark SQL to local Docker spark-master for dry-run preview. */
public class SandboxTool implements Tool {

    private static final Pattern SQL_BLOCK = Pattern.compile(
            "```sql\\s*\\n?(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern HAS_LIMIT = Pattern.compile(
            "\\bLIMIT\\s+\\d+", Pattern.CASE_INSENSITIVE);

    private final DockerCommandRunner runner;
    private final String sparkContainer;

    public SandboxTool(DockerCommandRunner runner, String sparkContainer) {
        this.runner = runner;
        this.sparkContainer = sparkContainer;
    }

    @Override
    public String name() { return "sandbox"; }

    @Override
    public String description() { return "提交 Spark SQL 到本地 Docker spark-master dry-run 预览"; }

    @Override
    public ToolResult run(Spec spec) {
        return ToolResult.fail("Use dryRun(code, spec) instead of run()");
    }

    /** Execute dry-run: extract SQL, add LIMIT if needed, submit to Spark, return preview. */
    public Map<String, Object> dryRun(String rawCode, Spec spec) {
        var sql = extractSql(rawCode);
        if (sql.isEmpty()) {
            return Map.of(
                "next_action", "sandbox_failed",
                "error", "No executable SQL found in generated code",
                "preview", ""
            );
        }

        sql = ensureLimit(sql, 100);

        try {
            var result = runner.exec(sparkContainer,
                    List.of("spark-sql", "--master", "spark://spark-master:7077", "-e", sql));
            if (result.isSuccess()) {
                var preview = truncate(result.stdout(), 2000);
                return Map.of(
                    "next_action", "dry_run_ok",
                    "preview", preview,
                    "rows", countLines(preview),
                    "spec_summary", specSummaryBrief(spec)
                );
            }
            return Map.of(
                "next_action", "sandbox_failed",
                "error", result.stderr(),
                "preview", result.stdout()
            );
        } catch (Exception e) {
            return Map.of(
                "next_action", "sandbox_failed",
                "error", e.getMessage(),
                "preview", ""
            );
        }
    }

    public static String extractSql(String rawCode) {
        var matcher = SQL_BLOCK.matcher(rawCode);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        // Fallback: treat entire content as SQL
        return rawCode.trim();
    }

    public static String ensureLimit(String sql, int limit) {
        if (HAS_LIMIT.matcher(sql).find()) {
            return sql;
        }
        var trimmed = sql.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + " LIMIT " + limit + ";";
    }

    private String specSummaryBrief(Spec spec) {
        var t = spec.target();
        return "目标: " + (t != null ? t.name() : "?")
                + " | 引擎: " + (spec.engineDecision() != null
                        ? spec.engineDecision().recommended() : "?");
    }

    private String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "\n... (truncated, " + s.length() + " chars total)";
    }

    private int countLines(String s) {
        return (int) s.lines().count();
    }
}
