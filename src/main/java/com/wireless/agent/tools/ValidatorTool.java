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

    @Override
    public String name() { return "validator"; }

    @Override
    public String description() { return "校验生成的 SQL 语法与 schema 兼容性"; }

    @Override
    public ToolResult run(Spec spec) {
        return ToolResult.fail("Use validate(code, spec) instead of run()");
    }

    public ToolResult validate(String rawCode, Spec spec) {
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

        // 3. Check table references against spec sources
        var referencedTables = new ArrayList<String>();
        var tableMatcher = TABLE_REF.matcher(sql);
        while (tableMatcher.find()) {
            referencedTables.add(tableMatcher.group(1).toLowerCase());
        }

        var knownTables = new ArrayList<String>();
        for (var src : spec.sources()) {
            var tbl = src.binding().getOrDefault("table_or_topic", "").toString();
            if (!tbl.isEmpty()) knownTables.add(tbl.toLowerCase());
        }

        for (var ref : referencedTables) {
            var found = knownTables.stream().anyMatch(t -> ref.contains(t) || t.contains(ref));
            if (!found) {
                warnings.add("Table " + ref + " not in spec sources: " + knownTables);
            }
        }

        // 4. Basic syntax checks
        if (!sql.toUpperCase().contains("SELECT")) {
            warnings.add("SQL has no SELECT clause");
        }

        return new ToolResult(true,
                Map.of("sql", sql, "warnings", warnings, "referenced_tables", referencedTables),
                warnings.isEmpty() ? "" : String.join("; ", warnings));
    }
}
