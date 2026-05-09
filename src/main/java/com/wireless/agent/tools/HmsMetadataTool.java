package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;

import java.util.*;
import java.util.stream.Collectors;

/** Real HMS MetadataTool using Hive Metastore Thrift client.
 *  Falls back to MockMetadataTool when HMS is unreachable. */
public class HmsMetadataTool implements Tool {

    private final String hmsUri;
    private final MockMetadataTool fallback;
    private HiveMetaStoreClient client;

    public HmsMetadataTool(String hmsUri) {
        this.hmsUri = hmsUri;
        this.fallback = new MockMetadataTool();
    }

    @Override
    public String name() { return "metadata"; }

    @Override
    public String description() { return "通过 HMS Thrift 查询真实表 schema、字段语义、责任人"; }

    @Override
    public ToolResult run(Spec spec) {
        return ToolResult.fail("Use lookup(tableName) instead of run()");
    }

    /** Lookup a table by exact name or fuzzy search. Tries HMS first, falls back to mock. */
    public ToolResult lookup(String search) {
        if (search == null || search.isBlank()) {
            return ToolResult.fail("No search term provided");
        }

        // Try real HMS client
        var result = tryHmsLookup(search);
        if (result != null) return result;

        // Fallback to mock
        return fallback.lookup(search);
    }

    private ToolResult tryHmsLookup(String search) {
        try {
            var c = getClient();
            // Parse catalog.schema.table or schema.table
            var parts = search.split("\\.");
            if (parts.length >= 2) {
                var dbName = parts[parts.length - 2];  // e.g., "dw"
                var tblName = parts[parts.length - 1]; // e.g., "mr_5g_15min"
                var table = c.getTable(dbName, tblName);
                var fields = c.getFields(dbName, tblName);

                var schema = fields.stream()
                        .map(f -> Map.of(
                            "name", f.getName(),
                            "type", f.getType(),
                            "semantic", f.getComment() != null ? f.getComment() : ""
                        ))
                        .collect(Collectors.toList());

                var data = Map.of(
                    "catalog", "hive",
                    "schema", schema,
                    "owner", table.getOwner() != null ? table.getOwner() : "unknown",
                    "description", table.getParameters() != null
                            ? table.getParameters().getOrDefault("comment", "") : "",
                    "grain", "(from HMS)"
                );
                return new ToolResult(true, data, "",
                    Map.of("type", "schema_lookup", "source", search,
                           "findings", Map.of("found", true, "via", "hms")));
            }
        } catch (Throwable e) {
            // HMS unreachable or table not found — fall through to fallback
            client = null;  // Reset failed client
        }
        return null;
    }

    private HiveMetaStoreClient getClient() throws Exception {
        if (client != null) return client;

        var conf = new HiveConf();
        conf.set("hive.metastore.uris", hmsUri);
        conf.set("hive.metastore.client.capability.check", "false");
        client = new HiveMetaStoreClient(conf);
        return client;
    }

    /** Direct search with keyword matching against known tables via fallback. */
    public ToolResult search(String keyword) {
        return fallback.lookup(keyword);
    }
}
