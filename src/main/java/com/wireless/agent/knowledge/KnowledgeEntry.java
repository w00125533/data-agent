package com.wireless.agent.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A single domain knowledge entry in the wireless assessment methodology dictionary. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class KnowledgeEntry {

    @JsonProperty("id")           private String id;
    @JsonProperty("category")     private String category;      // kpi_definition | methodology | source_desc | join_pattern | terminology
    @JsonProperty("kpi_family")   private String kpiFamily;     // coverage | mobility | accessibility | retainability | qoe
    @JsonProperty("name")         private String name;          // 中文名称, e.g. "弱覆盖小区"
    @JsonProperty("aliases")      private List<String> aliases; // 别名, e.g. ["弱覆盖", "poor coverage", "weak cov"]
    @JsonProperty("definition")   private String definition;    // 方法论口径定义
    @JsonProperty("formula")      private String formula;       // 计算公式/伪代码
    @JsonProperty("threshold")    private String threshold;     // 阈值, e.g. "RSRP < -110 dBm 占比 > 30%"
    @JsonProperty("related_tables") private List<String> relatedTables; // 关联的源表
    @JsonProperty("related_kpis") private List<String> relatedKpis;     // 关联的 KPI 条目 ID
    @JsonProperty("notes")        private String notes;         // 备注/注意事项
    @JsonProperty("keywords")     private List<String> keywords; // 搜索关键词

    public KnowledgeEntry() {}

    // ─── Getters ───

    public String id() { return id; }
    public String category() { return category; }
    public String kpiFamily() { return kpiFamily; }
    public String name() { return name; }
    public List<String> aliases() { return aliases; }
    public String definition() { return definition; }
    public String formula() { return formula; }
    public String threshold() { return threshold; }
    public List<String> relatedTables() { return relatedTables; }
    public List<String> relatedKpis() { return relatedKpis; }
    public String notes() { return notes; }
    public List<String> keywords() { return keywords; }

    /**
     * Returns a compact summary map for tool consumption.
     * Keys use snake_case names matching the JSON field names
     * (e.g. {@code related_tables}, not {@code relatedTables}).
     */
    public Map<String, Object> toSummary() {
        var m = new LinkedHashMap<String, Object>();
        if (id != null) m.put("id", id);
        if (category != null) m.put("category", category);
        if (name != null) m.put("name", name);
        if (definition != null) m.put("definition", definition);
        if (formula != null) m.put("formula", formula);
        if (threshold != null) m.put("threshold", threshold);
        if (relatedTables != null) m.put("related_tables", relatedTables);
        return m;
    }
}
