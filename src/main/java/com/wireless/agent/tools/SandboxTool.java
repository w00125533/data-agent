package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;

import java.util.*;
import java.util.regex.Pattern;

/** Submits generated Spark SQL to local Docker spark-master for dry-run preview. */
public class SandboxTool implements Tool {

    private static final Pattern SQL_BLOCK = Pattern.compile(
            "```(?:sql|sparksql)\\s*\\n?(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern JAVA_BLOCK = Pattern.compile(
            "```java\\s*\\n?(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final DockerCommandRunner runner;
    private final String sparkContainer;
    private final String flinkContainer;
    private final BaselineService baselineService;

    public String sparkContainer() { return sparkContainer; }
    public String flinkContainer() { return flinkContainer; }

    public SandboxTool(DockerCommandRunner runner, String sparkContainer) {
        this(runner, sparkContainer, "da-flink-jobmanager", null);
    }

    public SandboxTool(DockerCommandRunner runner, String sparkContainer, String flinkContainer) {
        this(runner, sparkContainer, flinkContainer, null);
    }

    public SandboxTool(DockerCommandRunner runner, String sparkContainer, BaselineService baselineService) {
        this(runner, sparkContainer, "da-flink-jobmanager", baselineService);
    }

    public SandboxTool(DockerCommandRunner runner, String sparkContainer,
                       String flinkContainer, BaselineService baselineService) {
        this.runner = runner;
        this.sparkContainer = sparkContainer;
        this.flinkContainer = flinkContainer;
        this.baselineService = baselineService;
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
        Objects.requireNonNull(rawCode, "rawCode must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        var sql = extractSql(rawCode);
        if (sql.isEmpty()) {
            return Map.of(
                "next_action", "sandbox_failed",
                "error", "No executable SQL found in generated code",
                "preview", ""
            );
        }

        sql = ensureLimit(sql, 100);
        sql = rewriteForBaseline(sql, spec);

        try {
            var targetContainer = selectContainer(spec);
            var execCmd = buildExecutionCommand(targetContainer, sql);
            var result = runner.exec(targetContainer, execCmd);
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
                "error", Objects.toString(result.stderr(), ""),
                "preview", Objects.toString(result.stdout(), "")
            );
        } catch (Exception e) {
            return Map.of(
                "next_action", "sandbox_failed",
                "error", Objects.toString(e.getMessage(), ""),
                "preview", ""
            );
        }
    }

    /** Dual dry-run for reverse synthetic: Step 1 run generated code, Step 2 verify against original pipeline. */
    public Map<String, Object> dualDryRun(String generatedCode, Spec spec) {
        Objects.requireNonNull(generatedCode, "generatedCode must not be null");
        Objects.requireNonNull(spec, "spec must not be null");

        var result = new LinkedHashMap<String, Object>();

        // Step 1: Run the generated data production code
        var genSql = extractSql(generatedCode);
        if (genSql.isEmpty()) {
            genSql = extractJavaCode(generatedCode);
        }

        Map<String, Object> step1;
        if (!genSql.isEmpty()) {
            var targetContainer = selectContainer(spec);
            var execCmd = buildExecutionCommand(targetContainer, genSql);
            try {
                var execResult = runner.exec(targetContainer, execCmd);
                step1 = Map.of(
                    "success", execResult.isSuccess(),
                    "output", truncate(execResult.stdout(), 1000),
                    "error", Objects.toString(execResult.stderr(), "")
                );
            } catch (Exception e) {
                step1 = Map.of(
                    "success", false,
                    "output", "",
                    "error", Objects.toString(e.getMessage(), "")
                );
            }
        } else {
            step1 = Map.of(
                "success", false,
                "output", "",
                "error", "No executable code found in generated output"
            );
        }
        result.put("step1_result", step1);

        // Step 2: Feed into original pipeline for validation
        var originalPipeline = spec.originalPipeline();
        Map<String, Object> step2;
        if (originalPipeline != null && !originalPipeline.isEmpty()) {
            var origSql = extractSql(originalPipeline);
            if (!origSql.isEmpty()) {
                origSql = ensureLimit(origSql, 10);
                var targetContainer = selectContainer(spec);
                var execCmd = buildExecutionCommand(targetContainer, origSql);
                try {
                    var execResult = runner.exec(targetContainer, execCmd);
                    step2 = Map.of(
                        "success", execResult.isSuccess(),
                        "output", truncate(execResult.stdout(), 1000),
                        "error", Objects.toString(execResult.stderr(), "")
                    );
                } catch (Exception e) {
                    step2 = Map.of(
                        "success", false,
                        "output", "",
                        "error", Objects.toString(e.getMessage(), "")
                    );
                }
            } else {
                step2 = Map.of(
                    "success", false,
                    "output", "",
                    "error", "Original pipeline has no executable SQL"
                );
            }
        } else {
            step2 = Map.of(
                "success", false,
                "output", "",
                "error", "No original pipeline code to validate against"
            );
        }
        result.put("step2_result", step2);

        // Overall status
        var step1Ok = (boolean) step1.getOrDefault("success", false);
        var step2Ok = (boolean) step2.getOrDefault("success", false);
        if (step1Ok && step2Ok) {
            result.put("next_action", "dual_dry_run_ok");
        } else if (step1Ok) {
            result.put("next_action", "step1_ok_step2_failed");
        } else {
            result.put("next_action", "sandbox_failed");
        }

        return result;
    }

    /** Select the target container based on engine decision. */
    public String selectContainer(Spec spec) {
        var engine = spec.engineDecision();
        if (engine == null) return sparkContainer;
        return switch (engine.recommended()) {
            case "flink_sql" -> flinkContainer;
            case "java_flink_streamapi" -> flinkContainer;
            default -> sparkContainer;
        };
    }

    /** Build the execution command for a given container. */
    public List<String> buildExecutionCommand(String container, String sql) {
        if (container.equals(flinkContainer)) {
            // Flink SQL Client
            return List.of("bash", "-c",
                    "echo '" + sql.replace("'", "'\\''") + "' | /opt/flink/bin/sql-client.sh");
        }
        // Spark SQL
        return List.of("spark-sql", "--master", "spark://spark-master:7077", "-e", sql);
    }

    /** Extract Java code from markdown block (for Flink DataStream code). */
    public static String extractJavaCode(String rawCode) {
        var matcher = JAVA_BLOCK.matcher(rawCode);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    /** Build a Flink job submission command for Java code. */
    public List<String> buildJavaExecutionCommand(String container, String javaCode) {
        return List.of("bash", "-c",
                "echo '" + javaCode.replace("'", "'\\''")
                + "' | /opt/flink/bin/sql-client.sh");
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
        var trimmed = sql.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return "SELECT * FROM (" + trimmed + ") _preview LIMIT " + limit + ";";
    }

    /** Rewrite SQL to use baseline tables when available. */
    public String rewriteForBaseline(String sql, Spec spec) {
        if (baselineService == null) return sql;
        var rewritten = sql;
        for (var src : spec.sources()) {
            var tbl = src.binding().getOrDefault("table_or_topic", "").toString();
            if (!tbl.isEmpty() && baselineService.hasBaseline(tbl)) {
                var baselineTbl = baselineService.resolveTable(tbl);
                // Use word-boundary-aware regex to avoid partial matches
                rewritten = rewritten.replaceAll(
                        "(?<![\\w.])" + java.util.regex.Pattern.quote(tbl) + "(?![\\w.])",
                        java.util.regex.Matcher.quoteReplacement(baselineTbl));
            }
        }
        return rewritten;
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
