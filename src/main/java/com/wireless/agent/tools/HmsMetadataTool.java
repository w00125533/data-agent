package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;
import com.wireless.agent.knowledge.DomainKnowledgeBase;
import com.wireless.agent.knowledge.KnowledgeEntry;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;

import java.util.*;
import java.util.stream.Collectors;

/** Real HMS MetadataTool using Hive Metastore Thrift client.
 *  Falls back to MockMetadataTool when HMS is unreachable. */
public class HmsMetadataTool implements Tool {

    private final HiveConf conf;
    private final MockMetadataTool fallback;
    private final DomainKnowledgeBase kb;

    public HmsMetadataTool(String hmsUri) {
        this(hmsUri, null);
    }

    public HmsMetadataTool(String hmsUri, DomainKnowledgeBase kb) {
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
        this.kb = kb;
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
        if (result == null) {
            result = fallback.lookup(search);
        }
        return enrichWithKb(result, search);
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

    /** Attach relevant domain knowledge entries to the lookup result. */
    private ToolResult enrichWithKb(ToolResult result, String search) {
        if (kb == null) return result;
        var kbMatches = kb.search(search);
        if (kbMatches.isEmpty()) return result;

        var summaries = kbMatches.stream()
                .map(KnowledgeEntry::toSummary)
                .collect(Collectors.toList());

        // Merge into result data
        Map<String, Object> data;
        if (result.data() instanceof Map<?, ?> d) {
            @SuppressWarnings("unchecked")
            var typed = (Map<String, Object>) d;
            data = new LinkedHashMap<>(typed);
        } else {
            data = new LinkedHashMap<>();
        }
        data.put("domain_knowledge", summaries);

        // Merge into evidence
        var evidence = new LinkedHashMap<>(result.evidence());
        evidence.put("kb_matches", kbMatches.size());

        return new ToolResult(result.success(), data, result.error(), evidence);
    }

    /** Search the Domain Knowledge Base directly. */
    public ToolResult searchKb(String query) {
        if (kb == null) return ToolResult.fail("Domain Knowledge Base not available");
        var matches = kb.search(query);
        if (matches.isEmpty()) {
            return ToolResult.fail("No KB entries found for: " + query);
        }
        return ToolResult.ok(
                Map.of("query", query, "matches", matches.stream()
                        .map(KnowledgeEntry::toSummary)
                        .collect(Collectors.toList())),
                Map.of("type", "kb_search", "findings", Map.of("hit_count", matches.size())));
    }

    /** Get KB prompt context for a given KPI family. */
    public String kbPromptContext(String kpiFamily) {
        if (kb == null) return "";
        return kb.buildPromptContext(kpiFamily);
    }
}
