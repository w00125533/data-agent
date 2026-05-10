package com.wireless.agent.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/** In-memory wireless assessment domain knowledge base.
 *  Loaded from domain-kb.json classpath resource at startup.
 *  Provides multi-dimensional lookup: by KPI family, keyword search, category filter. */
public class DomainKnowledgeBase {

    private static final String RESOURCE_PATH = "com/wireless/agent/knowledge/domain-kb.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<KnowledgeEntry> entries;

    public DomainKnowledgeBase() {
        this.entries = loadFromResource();
    }

    /** Package-visible for testing with explicit entries. */
    DomainKnowledgeBase(List<KnowledgeEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** All entries in the KB. Returns an immutable copy. */
    public List<KnowledgeEntry> all() {
        return List.copyOf(entries);
    }

    /** Lookup entries by exact KPI family match. */
    public List<KnowledgeEntry> lookupByKpiFamily(String kpiFamily) {
        if (kpiFamily == null || kpiFamily.isBlank()) return List.of();
        return entries.stream()
                .filter(e -> kpiFamily.equals(e.kpiFamily()))
                .collect(Collectors.toList());
    }

    /** Lookup entries by exact category match. */
    public List<KnowledgeEntry> lookupByCategory(String category) {
        if (category == null || category.isBlank()) return List.of();
        return entries.stream()
                .filter(e -> category.equals(e.category()))
                .collect(Collectors.toList());
    }

    /** Full-text search across name, aliases, keywords, related tables, and definition.
     *  Returns results ranked by relevance using a 5-tier scoring model:
     *  name match (10) &gt; alias match (8) &gt; keyword match (6) &gt; related table match (4) &gt; definition match (3). */
    public List<KnowledgeEntry> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        var qLower = query.toLowerCase().trim();

        var scored = new ArrayList<Map.Entry<KnowledgeEntry, Integer>>();
        for (var entry : entries) {
            var score = 0;
            if (entry.name() != null && entry.name().toLowerCase().contains(qLower)) score += 10;
            if (entry.aliases() != null) {
                for (var alias : entry.aliases()) {
                    if (alias.toLowerCase().contains(qLower)) { score += 8; break; }
                }
            }
            if (entry.keywords() != null) {
                for (var kw : entry.keywords()) {
                    if (kw.toLowerCase().contains(qLower)) { score += 6; break; }
                }
            }
            if (entry.definition() != null && entry.definition().toLowerCase().contains(qLower)) score += 3;
            if (entry.relatedTables() != null) {
                for (var t : entry.relatedTables()) {
                    if (t.toLowerCase().contains(qLower)) { score += 4; break; }
                }
            }
            if (score > 0) {
                scored.add(Map.entry(entry, score));
            }
        }
        scored.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return scored.stream().map(Map.Entry::getKey).collect(Collectors.toList());
    }

    /** Return a compact summary list of all KB entries for LLM context injection. */
    public List<Map<String, Object>> summarizeForLlm() {
        return entries.stream()
                .map(KnowledgeEntry::toSummary)
                .collect(Collectors.toList());
    }

    /** Return a summary list filtered by KPI family, for targeted LLM context.
     *  When kpiFamily is null or blank, returns summaries for all entries. */
    public List<Map<String, Object>> summarizeForLlm(String kpiFamily) {
        if (kpiFamily == null || kpiFamily.isBlank()) {
            return summarizeForLlm();
        }
        return lookupByKpiFamily(kpiFamily).stream()
                .map(KnowledgeEntry::toSummary)
                .collect(Collectors.toList());
    }

    /** Build a compact markdown reference string for system prompt injection. */
    public String buildPromptContext(String kpiFamily) {
        var entries = (kpiFamily == null || kpiFamily.isBlank())
                ? this.entries
                : lookupByKpiFamily(kpiFamily);
        if (entries.isEmpty()) {
            System.err.println("[WARN] KB: no entries for kpi_family=" + kpiFamily + ", showing all entries");
            entries = this.entries;
        }

        var sb = new StringBuilder();
        sb.append("## 无线评估方法论字典 (Domain Knowledge Base)\n\n");

        var categories = entries.stream()
                .collect(Collectors.groupingBy(KnowledgeEntry::category));
        for (var cat : List.of("kpi_definition", "methodology", "source_desc", "join_pattern", "terminology")) {
            var catEntries = categories.get(cat);
            if (catEntries == null || catEntries.isEmpty()) continue;
            sb.append("### ").append(categoryLabel(cat)).append("\n\n");
            for (var e : catEntries) {
                sb.append("- **").append(e.name()).append("**: ").append(e.definition());
                if (e.formula() != null) sb.append(" `").append(e.formula()).append("`");
                if (e.threshold() != null) sb.append(" 阈值: ").append(e.threshold());
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String categoryLabel(String cat) {
        return switch (cat) {
            case "kpi_definition" -> "KPI 定义";
            case "methodology" -> "方法论流程";
            case "source_desc" -> "数据源说明";
            case "join_pattern" -> "联邦关联模式";
            case "terminology" -> "术语表";
            default -> cat;
        };
    }

    @SuppressWarnings("unchecked")
    private static List<KnowledgeEntry> loadFromResource() {
        try (InputStream in = DomainKnowledgeBase.class.getClassLoader()
                .getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                System.err.println("[WARN] domain-kb.json not found on classpath, KB is empty");
                return List.of();
            }
            return MAPPER.readValue(in, new TypeReference<List<KnowledgeEntry>>() {});
        } catch (Exception e) {
            System.err.println("[WARN] Failed to load domain-kb.json: " + e.getMessage());
            return List.of();
        }
    }
}
