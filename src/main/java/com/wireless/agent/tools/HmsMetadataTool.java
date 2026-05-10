package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;

import java.util.*;
import java.util.stream.Collectors;

/** Real HMS MetadataTool using Hive Metastore Thrift client.
 *  Falls back to MockMetadataTool when HMS is unreachable. */
public class HmsMetadataTool implements Tool {

    private final HiveConf conf;
    private final MockMetadataTool fallback;

    public HmsMetadataTool(String hmsUri) {
        // Ensure hadoop.home.dir is set to prevent Shell init failure on Windows
        if (System.getProperty("hadoop.home.dir") == null) {
            System.setProperty("hadoop.home.dir", "/");
        }
        HiveConf c = null;
        try {
            c = new HiveConf();
            c.set("hive.metastore.uris", hmsUri);
            c.set("hive.metastore.client.capability.check", "false");
            c.set("hive.metastore.client.socket.timeout", "3000");
            c.set("hive.metastore.connect.retries", "1");
            c.set("hive.metastore.failure.retries", "0");
        } catch (Throwable e) {
            // HiveConf init may fail on Windows without HADOOP_HOME;
            // fallback to MockMetadataTool below.
        }
        this.conf = c;
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

        var result = tryHmsLookup(search);
        if (result != null) return result;

        return fallback.lookup(search);
    }

    private ToolResult tryHmsLookup(String search) {
        if (conf == null) return null;
        try (var client = new HiveMetaStoreClient(conf)) {
            var parts = search.split("\\.");
            if (parts.length >= 2) {
                var dbName = parts[parts.length - 2];
                var tblName = parts[parts.length - 1];
                var table = client.getTable(dbName, tblName);
                var fields = client.getFields(dbName, tblName);

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
        } catch (NoClassDefFoundError | NoSuchMethodError e) {
            // HMS jars not available — fall through to fallback
        } catch (org.apache.thrift.TException e) {
            // HMS connection failure (MetaException extends TException) — fall through to fallback
        } catch (Exception e) {
            // Other unexpected exceptions — fall through to fallback
        }
        return null;
    }

    /** Direct search with keyword matching against known tables via fallback. */
    public ToolResult search(String keyword) {
        return fallback.lookup(keyword);
    }
}
