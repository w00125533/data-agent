package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;

import java.util.*;
import java.util.regex.Pattern;

/** Validates generated SQL: extracts SQL block, checks table references,
 *  detects common issues. No external SQL parser dependency. */
public class ValidatorTool implements Tool {

    private static final Pattern SQL_BLOCK = Pattern.compile(
            "```sql\\s*\\n?(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_REF = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+([a-zA-Z_][a-zA-Z0-9_.]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMA_TABLE = Pattern.compile(
            ",\\s*([a-zA-Z_][a-zA-Z0-9_.]*)");
    private static final Pattern CTE_NAME = Pattern.compile(
            "\\bWITH\\s+(\\w+)\\s+AS\\s*\\(", Pattern.CASE_INSENSITIVE);

    @Override
    public String name() { return "validator"; }

    @Override
    public String description() { return "校验生成的 SQL 语法与 schema 兼容性"; }

    @Override
    public ToolResult run(Spec spec) {
        return ToolResult.fail("Use validate(code, spec) instead of run()");
    }

    public ToolResult validate(String rawCode, Spec spec) {
        java.util.Objects.requireNonNull(rawCode, "rawCode must not be null");
        java.util.Objects.requireNonNull(spec, "spec must not be null");

        var warnings = new ArrayList<String>();

        // 1. Extract SQL from markdown code block
        var matcher = SQL_BLOCK.matcher(rawCode);
        if (!matcher.find()) {
            return ToolResult.fail("No SQL code block found in generated output");
        }
        var sql = matcher.group(1).trim();
        if (sql.isEmpty()) {
            return ToolResult.fail("SQL code block is empty");
        }

        // 2. Check for FROM clause
        if (!sql.toUpperCase().contains("FROM")) {
            warnings.add("SQL has no FROM clause — may not reference any table");
        }

        // 3. Collect CTE names to avoid false warnings
        var cteNames = new HashSet<String>();
        var cteMatcher = CTE_NAME.matcher(sql);
        while (cteMatcher.find()) {
            cteNames.add(cteMatcher.group(1).toLowerCase());
        }

        // 4. Extract table references from FROM/JOIN clauses
        var referencedTables = new ArrayList<String>();
        var tableMatcher = TABLE_REF.matcher(sql);
        while (tableMatcher.find()) {
            var tableName = tableMatcher.group(1).toLowerCase();
            referencedTables.add(tableName);
            // Also check for comma-separated tables after this match
            var afterFrom = sql.substring(tableMatcher.end());
            var commaMatcher = COMMA_TABLE.matcher(afterFrom);
            while (commaMatcher.find()) {
                referencedTables.add(commaMatcher.group(1).toLowerCase());
            }
        }

        // 5. Check table references against spec sources
        var knownTables = new ArrayList<String>();
        for (var src : spec.sources()) {
            var tbl = src.binding().getOrDefault("table_or_topic", "").toString();
            if (!tbl.isEmpty()) knownTables.add(tbl.toLowerCase());
        }

        for (var ref : referencedTables) {
            // Skip CTE names
            if (cteNames.contains(ref)) continue;

            var found = knownTables.stream().anyMatch(t ->
                t.equals(ref) || ref.endsWith("." + t) || t.endsWith("." + ref));
            if (!found) {
                warnings.add("Table " + ref + " not in spec sources: " + knownTables);
            }
        }

        // 6. Basic syntax checks
        if (!sql.toUpperCase().contains("SELECT")) {
            warnings.add("SQL has no SELECT clause");
        }

        return new ToolResult(true,
                Map.of("sql", sql, "warnings", warnings, "referenced_tables", referencedTables),
                warnings.isEmpty() ? "" : String.join("; ", warnings));
    }
}
