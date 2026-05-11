# M2 域知识与基线 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建 Domain Knowledge Base（无线评估方法论字典）+ Sample Baseline Service（1% 采样基线落到 HDFS `/baseline/` 路径），使 agent 能引用方法论口径、dry-run 优先走基线数据。

**Architecture:** Domain Knowledge Base 是纯 Java 内存知识库，内部数据以 JSON 资源文件加载，提供按 KPI 族/关键词/源表的多维度查询。Sample Baseline Service 通过 Docker exec spark-sql 执行 `CREATE TABLE AS ... WHERE rand() < 0.01` 创建基线快照，存储元数据到本地 JSON 文件。HmsMetadataTool 集成 KB 查询以返回方法论口径，ProfilerTool 和 SandboxTool 优先使用基线路径。

**Tech Stack:** Java 17, Maven, Jackson, JUnit 5, AssertJ

---

## 文件结构（M2 完成后的新增/变更目录形态）

```
data-agent/
├── pom.xml                                           # 不变
├── src/main/resources/
│   ├── agent.properties                              # 修改: +baseline 配置
│   └── com/wireless/agent/knowledge/
│       └── domain-kb.json                            # 新增: 无线评估方法论字典
├── src/main/java/com/wireless/agent/
│   ├── knowledge/
│   │   ├── KnowledgeEntry.java                       # 新增: KB 条目数据模型
│   │   └── DomainKnowledgeBase.java                  # 新增: 知识库引擎
│   ├── tools/
│   │   ├── BaselineService.java                      # 新增: 基线采样与管理
│   │   ├── HmsMetadataTool.java                      # 修改: 集成 KB 查询
│   │   ├── ProfilerTool.java                         # 修改: 优先走 baseline
│   │   ├── SandboxTool.java                          # 修改: 优先走 baseline
│   │   └── ...
│   ├── core/
│   │   └── AgentCore.java                            # 修改: 注入 KB + BaselineService
│   └── Main.java                                     # 修改: 加载 KB + BaselineService
└── src/test/java/com/wireless/agent/
    ├── knowledge/
    │   └── DomainKnowledgeBaseTest.java              # 新增
    ├── tools/
    │   ├── BaselineServiceTest.java                  # 新增
    │   ├── HmsMetadataToolTest.java                  # 修改: 验证 KB 集成
    │   ├── ProfilerToolTest.java                     # 修改: 验证 baseline 优先
    │   └── SandboxToolTest.java                      # 修改: 验证 baseline 优先
    └── IntegrationTest.java                          # 修改: M2 端到端
```

---

### Task 1: KnowledgeEntry 数据模型

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/knowledge/KnowledgeEntry.java`

- [ ] **Step 1: 写入 KnowledgeEntry.java**

```java
package com.wireless.agent.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** A single domain knowledge entry in the wireless assessment methodology dictionary. */
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

    /** Returns a compact summary map for tool consumption. */
    public Map<String, Object> toSummary() {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("category", category);
        m.put("name", name);
        m.put("definition", definition);
        if (formula != null) m.put("formula", formula);
        if (threshold != null) m.put("threshold", threshold);
        if (relatedTables != null) m.put("related_tables", relatedTables);
        return m;
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd D:/agent-code/data-agent
mvn compile -pl .
```

期望: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wireless/agent/knowledge/KnowledgeEntry.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m2): knowledge entry data model for wireless domain dictionary"
```

---

### Task 2: Domain Knowledge Base JSON 资源文件

**Files:**
- Create: `D:/agent-code/data-agent/src/main/resources/com/wireless/agent/knowledge/domain-kb.json`

- [ ] **Step 1: 写入 domain-kb.json（无线评估方法论字典全文）**

```json
[
  {
    "id": "kpi-001",
    "category": "kpi_definition",
    "kpi_family": "coverage",
    "name": "弱覆盖小区",
    "aliases": ["弱覆盖", "poor coverage", "weak coverage", "弱覆盖比例"],
    "definition": "在指定时间窗口内，RSRP < -110 dBm 的采样点占比超过 30% 的小区",
    "formula": "weak_cov_ratio = COUNT(rsrp_avg < -110) / COUNT(*) * 100",
    "threshold": "weak_cov_ratio > 30%",
    "related_tables": ["dw.mr_5g_15min", "dw.mr_4g_15min"],
    "keywords": ["RSRP", "弱覆盖", "-110", "覆盖"]
  },
  {
    "id": "kpi-002",
    "category": "kpi_definition",
    "kpi_family": "coverage",
    "name": "深度覆盖差小区",
    "aliases": ["深度覆盖", "deep coverage", "deep poor coverage"],
    "definition": "室内深度覆盖不足的小区，表现为 SINR < -3 dB 且 RSRP 在 [-115, -105] dBm 区间采样占比高",
    "formula": "deep_poor_ratio = COUNT(sinr_avg < -3 AND rsrp_avg BETWEEN -115 AND -105) / COUNT(*) * 100",
    "threshold": "deep_poor_ratio > 20%",
    "related_tables": ["dw.mr_5g_15min"],
    "keywords": ["SINR", "深度覆盖", "室内", "穿透"]
  },
  {
    "id": "kpi-003",
    "category": "kpi_definition",
    "kpi_family": "coverage",
    "name": "过覆盖小区",
    "aliases": ["过覆盖", "over coverage", "over shoot"],
    "definition": "小区信号覆盖范围异常过大，表现为 TA (Timing Advance) 分布超出预期的采样占比高",
    "formula": "over_shoot_ratio = COUNT(ta > 15) / COUNT(*) * 100",
    "threshold": "over_shoot_ratio > 15%",
    "related_tables": ["dw.mr_5g_15min", "dw.mr_4g_15min", "dim.engineering_param"],
    "keywords": ["TA", "过覆盖", "覆盖范围", "越区"]
  },
  {
    "id": "kpi-004",
    "category": "kpi_definition",
    "kpi_family": "mobility",
    "name": "切换成功率",
    "aliases": ["切换成功率", "HO success rate", "handover success"],
    "definition": "切换成功次数 / 切换请求次数，按 cell × 时间窗口聚合",
    "formula": "ho_succ_rate = COUNT(result='success') / COUNT(*) * 100",
    "threshold": "ho_succ_rate < 99% 为异常",
    "related_tables": ["dw.kpi_pm_cell_hour", "kafka.signaling_events"],
    "keywords": ["切换", "handover", "HO", "成功率"]
  },
  {
    "id": "kpi-005",
    "category": "kpi_definition",
    "kpi_family": "mobility",
    "name": "切换失败原因分布",
    "aliases": ["切换失败", "HO failure", "早切", "晚切", "too early", "too late"],
    "definition": "切换失败原因分类统计：正常失败(normal)、过早切换(too_early)、过晚切换(too_late)、乒乓切换(pingpong)",
    "formula": "按 cause 字段分组统计: GROUP BY cause",
    "threshold": "早切+晚切占比 > 30% 需优化邻区关系/CIO",
    "related_tables": ["kafka.signaling_events"],
    "keywords": ["早切", "晚切", "乒乓", "cause", "失败原因"]
  },
  {
    "id": "kpi-006",
    "category": "kpi_definition",
    "kpi_family": "accessibility",
    "name": "RRC 建立成功率",
    "aliases": ["RRC建立", "RRC setup", "接入成功率"],
    "definition": "RRC 连接建立成功次数 / RRC 连接请求次数",
    "formula": "rrc_succ_rate = COUNT(rrc_result='success') / COUNT(*) * 100",
    "threshold": "rrc_succ_rate < 99.5% 为异常",
    "related_tables": ["dw.kpi_pm_cell_hour"],
    "keywords": ["RRC", "接入", "连接建立"]
  },
  {
    "id": "kpi-007",
    "category": "kpi_definition",
    "kpi_family": "retainability",
    "name": "掉话率",
    "aliases": ["掉话率", "drop rate", "E-RAB drop", "呼叫保持"],
    "definition": "异常释放的 E-RAB 数 / 成功建立的 E-RAB 总数",
    "formula": "erab_drop_rate = COUNT(erab_result='abnormal_release') / COUNT(erab_result='established') * 100",
    "threshold": "erab_drop_rate > 0.5% 为异常",
    "related_tables": ["dw.kpi_pm_cell_hour"],
    "keywords": ["掉话", "E-RAB", "保持", "drop"]
  },
  {
    "id": "kpi-008",
    "category": "kpi_definition",
    "kpi_family": "qoe",
    "name": "感知差小区",
    "aliases": ["感知差", "QoE poor", "用户体验差", "quality of experience"],
    "definition": "用户体验质量差的小区，综合评估视频卡顿率、网页时延、TCP 重传率等指标",
    "formula": "qoe_score = w1*video_stall_rate + w2*web_rtt + w3*tcp_retrans_rate",
    "threshold": "qoe_score > 阈值(按省分/地市差异化配置)",
    "related_tables": ["dw.qoe_user_cell", "dw.kpi_pm_cell_hour"],
    "keywords": ["QoE", "感知", "视频", "卡顿", "TCP", "网页"]
  },

  {
    "id": "method-001",
    "category": "methodology",
    "kpi_family": "coverage",
    "name": "弱覆盖小区识别标准流程 (v2口径)",
    "definition": "1) 取最近 7 天 MR 数据; 2) 按 cell_id × day 聚合; 3) 计算每日 RSRP<-110 占比; 4) 任一天超 30% 标记为弱覆盖小区",
    "formula": "WITH daily AS (SELECT cell_id, dt, AVG(CASE WHEN rsrp_avg < -110 THEN 1.0 ELSE 0.0 END) AS weak_ratio FROM dw.mr_5g_15min WHERE dt >= date_sub(current_date, 7) GROUP BY cell_id, dt) SELECT DISTINCT cell_id FROM daily WHERE weak_ratio > 0.3",
    "related_tables": ["dw.mr_5g_15min"],
    "related_kpis": ["kpi-001"],
    "notes": "v2 口径与 v1 差异: v2 改为 7 天窗口(v1 为 30 天),且使用日粒度而非 15min 粒度"
  },
  {
    "id": "method-002",
    "category": "methodology",
    "kpi_family": "mobility",
    "name": "切换失败根因定位流程",
    "definition": "1) 从信令流中提取 event_type='handover' 事件; 2) 按 cause 分类(too_early/too_late/normal); 3) 按 src_cell × hour 汇总; 4) 关联工参表获取邻区关系与 CIO 配置",
    "formula": "SELECT src_cell, cause, COUNT(*) AS cnt FROM kafka.signaling_events WHERE event_type='handover' GROUP BY src_cell, cause",
    "related_tables": ["kafka.signaling_events", "dim.engineering_param"],
    "related_kpis": ["kpi-005"],
    "notes": "早切: UE 在新小区未稳定即触发切换; 晚切: 源小区信号已严重衰减仍未切换"
  },
  {
    "id": "method-003",
    "category": "methodology",
    "kpi_family": "coverage",
    "name": "MR 数据与工参数据联邦分析流程",
    "definition": "1) 以 cell_id 为 join key 将 MR 表与工参维表关联; 2) 附加 site_id/district/经纬度 维度; 3) 按区县/站址做聚合透视",
    "formula": "SELECT e.district, COUNT(DISTINCT m.cell_id) AS weak_cell_cnt, AVG(m.weak_cov_ratio) AS avg_weak_ratio FROM dw.mr_5g_15min m JOIN dim.engineering_param e ON m.cell_id = e.cell_id WHERE m.weak_cov_ratio > 0.3 GROUP BY e.district",
    "related_tables": ["dw.mr_5g_15min", "dim.engineering_param"],
    "related_kpis": ["kpi-001"],
    "notes": "工参维表通常按天快照，与 MR 的 15min 粒度存在时间对齐问题; 建议使用 latest 维表快照"
  },

  {
    "id": "src-001",
    "category": "source_desc",
    "kpi_family": "coverage",
    "name": "5G MR 15分钟 KPI 表",
    "aliases": ["MR表", "MR 5G", "mr_5g"],
    "definition": "5G 测量报告 (Measurement Report) 15 分钟小区级聚合 KPI，包含 RSRP/RSRQ/SINR 等无线测量指标",
    "related_tables": ["dw.mr_5g_15min"],
    "notes": "数据量极大(1000+ 小区 × 96 个15min窗口/天), 采样查询时必须加 LIMIT 或时间范围过滤"
  },
  {
    "id": "src-002",
    "category": "source_desc",
    "kpi_family": "coverage",
    "name": "小区工参维表",
    "aliases": ["工参", "工程参数", "engineering param"],
    "definition": "小区工程参数配置维表，包含 site_id/district/经纬度/方位角/下倾角等",
    "related_tables": ["dim.engineering_param"],
    "notes": "维表数据量小(~10k 行),可直接全量 join"
  },
  {
    "id": "src-003",
    "category": "source_desc",
    "kpi_family": "mobility",
    "name": "切换信令事件流",
    "aliases": ["信令", "signaling", "事件流", "handover events"],
    "definition": "Kafka 实时信令事件流，JSONL 格式，包含 event_type/handover/access 等事件类型及其结果",
    "related_tables": ["kafka.signaling_events"],
    "notes": "Kafka 流数据，需指定消费窗口(start_offset/end_offset 或时间范围), v1 使用 spark-sql 读对应 topic 的 Hive 外表映射"
  },

  {
    "id": "pattern-001",
    "category": "join_pattern",
    "kpi_family": "coverage",
    "name": "MR + 工参联邦 (cell_id)",
    "definition": "用 cell_id 将 MR KPI 表与工参维表关联，附加上 site_id/district/经纬度等维表字段，用于区县级聚合分析",
    "formula": "JOIN dim.engineering_param ON m.cell_id = e.cell_id",
    "related_tables": ["dw.mr_5g_15min", "dim.engineering_param"],
    "related_kpis": ["kpi-001", "kpi-002", "kpi-003"]
  },
  {
    "id": "pattern-002",
    "category": "join_pattern",
    "kpi_family": "mobility",
    "name": "信令 + 工参联邦 (cell_id)",
    "definition": "切换信令事件的 src_cell / dst_cell 分别 join 工参维表获取源/目标小区地理信息",
    "formula": "JOIN dim.engineering_param src ON s.src_cell = src.cell_id JOIN dim.engineering_param dst ON s.dst_cell = dst.cell_id",
    "related_tables": ["kafka.signaling_events", "dim.engineering_param"],
    "related_kpis": ["kpi-005"]
  },

  {
    "id": "term-001",
    "category": "terminology",
    "kpi_family": null,
    "name": "RAT 制式术语",
    "aliases": ["RAT", "制式", "网络制式"],
    "definition": "4G = LTE, 5G_SA = 5G 独立组网, 5G_NSA = 5G 非独立组网(EN-DC方式, 锚点LTE+NR SCG), mixed = 多制式混合",
    "keywords": ["4G", "5G", "SA", "NSA", "LTE", "NR", "EN-DC"]
  },
  {
    "id": "term-002",
    "category": "terminology",
    "kpi_family": null,
    "name": "网络粒度术语",
    "aliases": ["粒度", "grain", "聚合粒度"],
    "definition": "cell = 小区, site = 站址(物理站点, 含多个小区), sector = 扇区, tracking_area = TA跟踪区, district = 区县, city = 地市",
    "keywords": ["cell", "site", "sector", "TA", "district", "区县", "地市"]
  },
  {
    "id": "term-003",
    "category": "terminology",
    "kpi_family": null,
    "name": "时间粒度术语",
    "aliases": ["时间粒度", "time grain", "聚合窗口"],
    "definition": "15min = 15分钟(96窗口/天), hour = 小时(24窗口/天), day = 天, week = 周",
    "keywords": ["15min", "小时", "天", "周", "窗口"]
  }
]
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/com/wireless/agent/knowledge/domain-kb.json
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m2): wireless assessment methodology dictionary json resource"
```

---

### Task 3: DomainKnowledgeBase 引擎

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/knowledge/DomainKnowledgeBase.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/knowledge/DomainKnowledgeBaseTest.java`

- [ ] **Step 1: 在 DomainKnowledgeBaseTest.java 写失败测试**

```java
package com.wireless.agent.knowledge;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DomainKnowledgeBaseTest {

    private static DomainKnowledgeBase kb;

    @BeforeAll
    static void setUp() {
        kb = new DomainKnowledgeBase();
    }

    @Test
    void shouldLoadFromClasspathResource() {
        var all = kb.all();
        assertThat(all).isNotEmpty();
        assertThat(all.size()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void shouldLookupByKpiFamily() {
        var coverageKpis = kb.lookupByKpiFamily("coverage");
        assertThat(coverageKpis).isNotEmpty();
        assertThat(coverageKpis.stream().map(KnowledgeEntry::name))
                .contains("弱覆盖小区");
    }

    @Test
    void shouldLookupByKeyword() {
        var results = kb.search("弱覆盖");
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).name()).contains("弱覆盖");
    }

    @Test
    void shouldMatchAliasesForSearch() {
        var results = kb.search("poor coverage");
        assertThat(results).isNotEmpty();
        assertThat(results.stream().anyMatch(e -> e.name().equals("弱覆盖小区"))).isTrue();
    }

    @Test
    void shouldLookupByCategory() {
        var methods = kb.lookupByCategory("methodology");
        assertThat(methods).isNotEmpty();
        assertThat(methods.stream().map(KnowledgeEntry::name))
                .contains("弱覆盖小区识别标准流程 (v2口径)");
    }

    @Test
    void shouldFindRelatedTables() {
        var entries = kb.search("cell_id");
        assertThat(entries).isNotEmpty();
    }

    @Test
    void shouldReturnEmptyForNoMatch() {
        var results = kb.search("zzz_no_such_term_xyz");
        assertThat(results).isEmpty();
    }

    @Test
    void shouldGetTerminologyDefinitions() {
        var terms = kb.lookupByCategory("terminology");
        assertThat(terms).isNotEmpty();
        assertThat(terms.stream().map(KnowledgeEntry::name))
                .contains("RAT 制式术语", "网络粒度术语", "时间粒度术语");
    }

    @Test
    void shouldGetJoinPatterns() {
        var patterns = kb.lookupByCategory("join_pattern");
        assertThat(patterns).isNotEmpty();
        assertThat(patterns.get(0).formula()).isNotNull();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=DomainKnowledgeBaseTest
```

- [ ] **Step 3: 在 DomainKnowledgeBase.java 写入实现**

```java
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

    /** All entries in the KB. */
    public List<KnowledgeEntry> all() {
        return entries;
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

    /** Full-text search across name, aliases, keywords, definition, and related tables.
     *  Returns results ranked by relevance (exact name match > alias match > keyword match > content match). */
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

    /** Return a summary list filtered by KPI family, for targeted LLM context. */
    public List<Map<String, Object>> summarizeForLlm(String kpiFamily) {
        return lookupByKpiFamily(kpiFamily).stream()
                .map(KnowledgeEntry::toSummary)
                .collect(Collectors.toList());
    }

    /** Build a compact markdown reference string for system prompt injection. */
    public String buildPromptContext(String kpiFamily) {
        var entries = (kpiFamily == null || kpiFamily.isBlank())
                ? this.entries
                : lookupByKpiFamily(kpiFamily);
        if (entries.isEmpty()) entries = this.entries;

        var sb = new StringBuilder();
        sb.append("## 无线评估方法论字典 (Domain Knowledge Base)\n\n");

        var categories = entries.stream()
                .collect(Collectors.groupingBy(KnowledgeEntry::category));
        for (var cat : List.of("kpi_definition", "methodology", "join_pattern", "terminology")) {
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
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -Dtest=DomainKnowledgeBaseTest
```

期望: 9 passed

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/knowledge/DomainKnowledgeBase.java \
        src/test/java/com/wireless/agent/knowledge/DomainKnowledgeBaseTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m2): domain knowledge base engine with multi-dimensional lookup"
```

---

### Task 4: BaselineService — 基线采样与管理

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/BaselineService.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/tools/BaselineServiceTest.java`

BaselineService 通过 Docker exec spark-sql 在 HDFS 上创建 1% 采样快照，存储到 `/baseline/<db>/<table>/` 路径，并记录元数据。

- [ ] **Step 1: 在 BaselineServiceTest.java 写失败测试**

```java
package com.wireless.agent.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineServiceTest {

    @Test
    void shouldHaveCorrectToolName() {
        var svc = new BaselineService(new DockerCommandRunner(), "da-spark-master");
        assertThat(svc.name()).isEqualTo("baseline");
    }

    @Test
    void shouldBuildBaselineTableName() {
        var baselineName = BaselineService.baselineTableName("dw.mr_5g_15min");
        assertThat(baselineName).isEqualTo("baseline.dw__mr_5g_15min");
    }

    @Test
    void shouldBuildBaselineTableNameReplaceDots() {
        assertThat(BaselineService.baselineTableName("dim.engineering_param"))
                .isEqualTo("baseline.dim__engineering_param");
    }

    @Test
    void shouldBuildCreateBaselineSql() {
        var sql = BaselineService.buildCreateBaselineSql(
                "dw.mr_5g_15min", "baseline.dw__mr_5g_15min", 1.0);
        assertThat(sql).contains("CREATE OR REPLACE TABLE");
        assertThat(sql).contains("baseline.dw__mr_5g_15min");
        assertThat(sql).contains("rand() < 0.01");
        assertThat(sql).contains("dw.mr_5g_15min");
    }

    @Test
    void shouldBuildCreateBaselineSqlWithCustomSampleRate() {
        var sql = BaselineService.buildCreateBaselineSql(
                "src.tbl", "baseline.src__tbl", 5.0);
        assertThat(sql).contains("rand() < 0.05");
    }

    @Test
    void shouldBuildCountBaselineSql() {
        var sql = BaselineService.buildCountSql("baseline.dw__mr_5g_15min");
        assertThat(sql).isEqualTo("SELECT COUNT(*) FROM baseline.dw__mr_5g_15min;");
    }

    @Test
    void shouldTrackBaselineMetadata() {
        var svc = new BaselineService(new DockerCommandRunner(), "da-spark-master");
        svc.recordBaseline("dw.mr_5g_15min", "baseline.dw__mr_5g_15min", 100000,
                Map.of("row_count", 1000));
        var meta = svc.getBaselineMeta("dw.mr_5g_15min");
        assertThat(meta).isPresent();
        assertThat(meta.get()).containsEntry("source_table", "dw.mr_5g_15min");
        assertThat(meta.get()).containsEntry("baseline_table", "baseline.dw__mr_5g_15min");
    }

    @Test
    void shouldReturnEmptyForUnknownBaseline() {
        var svc = new BaselineService(new DockerCommandRunner(), "da-spark-master");
        var meta = svc.getBaselineMeta("no_such_table");
        assertThat(meta).isEmpty();
    }

    @Test
    void shouldImplementToolInterface() {
        var svc = new BaselineService(new DockerCommandRunner(), "da-spark-master");
        assertThat(svc).isInstanceOf(Tool.class);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=BaselineServiceTest
```

- [ ] **Step 3: 在 BaselineService.java 写入实现**

```java
package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Creates and manages 1% sample baselines in HDFS at /baseline/ paths.
 *  Uses spark-sql via DockerCommandRunner to run CREATE TABLE AS SELECT ... WHERE rand() < 0.01.
 *  Baseline metadata is tracked in-memory (v1; future: persist to file). */
public class BaselineService implements Tool {

    private static final double DEFAULT_SAMPLE_PCT = 1.0;

    private final DockerCommandRunner runner;
    private final String sparkContainer;
    private final Map<String, Map<String, Object>> baselineMeta = new ConcurrentHashMap<>();

    public BaselineService(DockerCommandRunner runner, String sparkContainer) {
        this.runner = runner;
        this.sparkContainer = sparkContainer;
    }

    @Override
    public String name() { return "baseline"; }

    @Override
    public String description() { return "创建 1% 采样基线到 HDFS /baseline/ 路径，管理基线元数据"; }

    @Override
    public ToolResult run(Spec spec) {
        if (spec.sources().isEmpty()) {
            return ToolResult.fail("No sources to baseline");
        }
        var results = new LinkedHashMap<String, Object>();
        for (var src : spec.sources()) {
            var tbl = src.binding().getOrDefault("table_or_topic", "").toString();
            if (!tbl.isEmpty()) {
                var result = createBaseline(tbl, DEFAULT_SAMPLE_PCT);
                results.put(tbl, result.data());
            }
        }
        return ToolResult.ok(results,
                Map.of("type", "baseline_created", "findings", Map.of("baseline_count", results.size())));
    }

    /** Create a baseline sample for a single source table. */
    public ToolResult createBaseline(String sourceTable, double samplePercent) {
        var baselineTbl = baselineTableName(sourceTable);
        var sql = buildCreateBaselineSql(sourceTable, baselineTbl, samplePercent);

        try {
            var result = runner.exec(sparkContainer,
                    List.of("spark-sql", "--master", "spark://spark-master:7077",
                            "-e", sql));
            if (!result.isSuccess()) {
                return ToolResult.fail("Baseline creation failed: " + result.stderr(),
                        Map.of("source_table", sourceTable));
            }

            // Count the baseline rows
            var countSql = buildCountSql(baselineTbl);
            var countResult = runner.exec(sparkContainer,
                    List.of("spark-sql", "--master", "spark://spark-master:7077",
                            "-e", countSql));
            var rowCount = parseCount(countResult.stdout());

            var stats = Map.of("row_count", (Object) rowCount);
            recordBaseline(sourceTable, baselineTbl, (long) (samplePercent * 100), stats);

            return ToolResult.ok(Map.of(
                    "source_table", sourceTable,
                    "baseline_table", baselineTbl,
                    "baseline_rows", rowCount,
                    "sample_pct", samplePercent
            ));
        } catch (Exception e) {
            return ToolResult.fail("Baseline creation error: " + e.getMessage(),
                    Map.of("source_table", sourceTable));
        }
    }

    /** Record baseline metadata in-memory. */
    public void recordBaseline(String sourceTable, String baselineTable,
                                long sampleNumerator, Map<String, Object> stats) {
        var meta = new LinkedHashMap<String, Object>();
        meta.put("source_table", sourceTable);
        meta.put("baseline_table", baselineTable);
        meta.put("sample_rate", sampleNumerator + "%");
        meta.put("created_at", java.time.Instant.now().toString());
        meta.put("stats", stats);
        baselineMeta.put(sourceTable, meta);
    }

    /** Get baseline metadata for a source table. */
    public Optional<Map<String, Object>> getBaselineMeta(String sourceTable) {
        return Optional.ofNullable(baselineMeta.get(sourceTable));
    }

    /** Check if a baseline exists for the given source table. */
    public boolean hasBaseline(String sourceTable) {
        return baselineMeta.containsKey(sourceTable);
    }

    /** Get the baseline table name if one exists, otherwise return the original table. */
    public String resolveTable(String sourceTable) {
        var meta = baselineMeta.get(sourceTable);
        if (meta != null) {
            return meta.get("baseline_table").toString();
        }
        return sourceTable;
    }

    /** Convert a source table name to a baseline table name.
     *  e.g., "dw.mr_5g_15min" → "baseline.dw__mr_5g_15min" */
    public static String baselineTableName(String sourceTable) {
        return "baseline." + sourceTable.replace('.', '_').replace('-', '_');
    }

    /** Build the CREATE TABLE AS SELECT SQL for baseline sampling. */
    public static String buildCreateBaselineSql(String sourceTable, String baselineTable,
                                                  double samplePercent) {
        var pct = samplePercent / 100.0;
        return String.format(
                "CREATE OR REPLACE TABLE %s AS SELECT * FROM %s WHERE rand() < %.4f;",
                baselineTable, sourceTable, pct);
    }

    /** Build a COUNT(*) query for the baseline table. */
    public static String buildCountSql(String baselineTable) {
        return String.format("SELECT COUNT(*) FROM %s;", baselineTable);
    }

    private int parseCount(String stdout) {
        try {
            for (var line : stdout.trim().split("\\n")) {
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
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -Dtest=BaselineServiceTest
```

期望: 9 passed

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/tools/BaselineService.java \
        src/test/java/com/wireless/agent/tools/BaselineServiceTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m2): baseline service for 1% sample creation on hdfs /baseline/ path"
```

---

### Task 5: HmsMetadataTool 集成 Domain Knowledge Base

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/HmsMetadataTool.java`
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/tools/HmsMetadataToolTest.java`

- [ ] **Step 1: 修改 HmsMetadataTool.java — 注入 DomainKnowledgeBase 并在 lookup 结果中附加 KB 信息**

**修改构造函数和字段：**

```java
import com.wireless.agent.knowledge.DomainKnowledgeBase;

// 在类顶部字段区, MockMetadataTool fallback 之后新增:
private final DomainKnowledgeBase kb;

// 修改现有构造函数（新增 kb 参数）:
public HmsMetadataTool(String hmsUri, DomainKnowledgeBase kb) {
    // ... 现有 HMS conf 初始化 ...
    this.kb = kb;
}
```

**在 `lookup` 方法返回前，附加 KB 信息到 result evidence:**

在 `tryHmsLookup` 成功返回前（以及 `fallback.lookup(search)` 成功后），将 KB 的匹配条目附加到返回的 `ToolResult` 中。

在 `lookup` 方法的最后做统一处理。将现有的 `return result` 和 `return fallback.lookup(search)` 替换为通过 `enrichWithKb()` 处理：

```java
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

/** Attach relevant domain knowledge entries to the lookup result. */
private ToolResult enrichWithKb(ToolResult result, String search) {
    if (kb == null) return result;
    var kbMatches = kb.search(search);
    if (kbMatches.isEmpty()) return result;

    var summaries = kbMatches.stream()
            .map(com.wireless.agent.knowledge.KnowledgeEntry::toSummary)
            .collect(java.util.stream.Collectors.toList());

    // Merge into result data
    Map<String, Object> data;
    if (result.data() instanceof Map<?, ?> d) {
        @SuppressWarnings("unchecked")
        var typed = (Map<String, Object>) d;
        data = new java.util.LinkedHashMap<>(typed);
    } else {
        data = new java.util.LinkedHashMap<>();
    }
    data.put("domain_knowledge", summaries);

    // Merge into evidence
    var evidence = new java.util.LinkedHashMap<>(result.evidence());
    evidence.put("kb_matches", kbMatches.size());

    return new ToolResult(result.success(), data, result.error(), evidence);
}
```

**添加显式 KB 查询方法：**

```java
/** Search the Domain Knowledge Base directly. */
public ToolResult searchKb(String query) {
    if (kb == null) return ToolResult.fail("Domain Knowledge Base not available");
    var matches = kb.search(query);
    if (matches.isEmpty()) {
        return ToolResult.fail("No KB entries found for: " + query);
    }
    return ToolResult.ok(
            Map.of("query", query, "matches", matches.stream()
                    .map(com.wireless.agent.knowledge.KnowledgeEntry::toSummary)
                    .collect(java.util.stream.Collectors.toList())),
            Map.of("type", "kb_search", "findings", Map.of("hit_count", matches.size())));
}

/** Get KB prompt context for a given KPI family. */
public String kbPromptContext(String kpiFamily) {
    if (kb == null) return "";
    return kb.buildPromptContext(kpiFamily);
}
```

- [ ] **Step 2: 修改 HmsMetadataTool 现有构造函数（保留兼容性，kb 可为 null）**

```java
// 在现有单参数构造函数中:
public HmsMetadataTool(String hmsUri) {
    this(hmsUri, null);
}

public HmsMetadataTool(String hmsUri, DomainKnowledgeBase kb) {
    // ... 现有 HMS conf 初始化 ...
    this.kb = kb;
}
```

- [ ] **Step 3: 更新 HmsMetadataToolTest.java 添加 KB 集成测试**

在现有测试类中追加：

```java
@Test
void shouldEnrichLookupWithDomainKnowledge() {
    var kb = new com.wireless.agent.knowledge.DomainKnowledgeBase();
    var tool = new HmsMetadataTool("thrift://nonexistent:9999", kb);
    var result = tool.lookup("dw.mr_5g_15min");

    assertThat(result.success()).isTrue();
    @SuppressWarnings("unchecked")
    var data = (Map<String, Object>) result.data();
    var kbEntries = data.get("domain_knowledge");
    assertThat(kbEntries).isNotNull();
    assertThat(((List<?>) kbEntries)).isNotEmpty();
}

@Test
void shouldSearchKbDirectly() {
    var kb = new com.wireless.agent.knowledge.DomainKnowledgeBase();
    var tool = new HmsMetadataTool("thrift://nonexistent:9999", kb);
    var result = tool.searchKb("弱覆盖");

    assertThat(result.success()).isTrue();
    @SuppressWarnings("unchecked")
    var data = (Map<String, Object>) result.data();
    var matches = (List<?>) data.get("matches");
    assertThat(matches).isNotEmpty();
}

@Test
void shouldReturnKbPromptContext() {
    var kb = new com.wireless.agent.knowledge.DomainKnowledgeBase();
    var tool = new HmsMetadataTool("thrift://nonexistent:9999", kb);
    var context = tool.kbPromptContext("coverage");
    assertThat(context).contains("弱覆盖");
    assertThat(context).contains("## 无线评估方法论字典");
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -Dtest=HmsMetadataToolTest
```

期望: 7 passed (原有 4 + 新增 3)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/tools/HmsMetadataTool.java \
        src/test/java/com/wireless/agent/tools/HmsMetadataToolTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m2): integrate domain knowledge base into hms metadata tool"
```

---

### Task 6: ProfilerTool 和 SandboxTool 优先走 Baseline

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/ProfilerTool.java`
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/SandboxTool.java`
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/tools/ProfilerToolTest.java`
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/tools/SandboxToolTest.java`

- [ ] **Step 1: 修改 ProfilerTool — 注入 BaselineService，优先 query baseline**

在 ProfilerTool 字段区追加：

```java
private final BaselineService baselineService;
```

修改构造函数：

```java
public ProfilerTool(DockerCommandRunner runner, String sparkContainer, BaselineService baselineService) {
    this.runner = runner;
    this.sparkContainer = sparkContainer;
    this.baselineService = baselineService;
}
```

修改 `run(Spec)` 方法中的表名解析，在 `profileTable` 调用前将表名替换为 baseline（如果存在）：

```java
@Override
public ToolResult run(Spec spec) {
    if (spec.sources().isEmpty()) {
        return ToolResult.fail("No sources to profile");
    }
    var results = new LinkedHashMap<String, Object>();
    for (var src : spec.sources()) {
        var binding = src.binding();
        if (binding == null) continue;
        var tblObj = binding.get("table_or_topic");
        var tbl = tblObj != null ? tblObj.toString() : "";
        if (!tbl.isEmpty()) {
            // Prefer baseline if available
            var queryTbl = baselineService != null ? baselineService.resolveTable(tbl) : tbl;
            var schema = src.schema_();
            var profile = profileTable(queryTbl, 5, schema);
            results.put(tbl, profile.data());
        }
    }
    return ToolResult.ok(results,
            Map.of("type", "data_profile", "findings", Map.of("profiled_tables", results.size())));
}
```

- [ ] **Step 2: 修改 SandboxTool — 注入 BaselineService，提供 SQL 重写方法**

在 SandboxTool 字段区追加：

```java
private final BaselineService baselineService;
```

修改构造函数：

```java
public SandboxTool(DockerCommandRunner runner, String sparkContainer, BaselineService baselineService) {
    this.runner = runner;
    this.sparkContainer = sparkContainer;
    this.baselineService = baselineService;
}
```

添加 SQL 重写方法，将源表名替换为 baseline 表名：

```java
/** Rewrite SQL to use baseline tables when available. */
public String rewriteForBaseline(String sql, Spec spec) {
    if (baselineService == null) return sql;
    var rewritten = sql;
    for (var src : spec.sources()) {
        var tbl = src.binding().getOrDefault("table_or_topic", "").toString();
        if (!tbl.isEmpty() && baselineService.hasBaseline(tbl)) {
            var baselineTbl = baselineService.resolveTable(tbl);
            rewritten = rewritten.replace(tbl, baselineTbl);
        }
    }
    return rewritten;
}
```

在 `dryRun` 方法中，在 `ensureLimit` 之后添加 baseline 重写：

```java
public Map<String, Object> dryRun(String rawCode, Spec spec) {
    // ... 现有 extractSql + isEmpty check ...

    sql = ensureLimit(sql, 100);
    sql = rewriteForBaseline(sql, spec);   // ← 新增这行

    // ... 现有 exec ...
}
```

- [ ] **Step 3: 更新 ProfilerToolTest 和 SandboxToolTest**

在 ProfilerToolTest 中追加：

```java
@Test
void shouldPreferBaselineTableWhenAvailable() {
    var runner = new DockerCommandRunner();
    var baseline = new BaselineService(runner, "da-spark-master");
    baseline.recordBaseline("dw.mr_5g_15min", "baseline.dw__mr_5g_15min",
            1, Map.of("row_count", 1000));
    var tool = new ProfilerTool(runner, "da-spark-master", baseline);

    // Verify that resolveTable returns baseline when available
    var resolved = baseline.resolveTable("dw.mr_5g_15min");
    assertThat(resolved).isEqualTo("baseline.dw__mr_5g_15min");
}

@Test
void shouldFallbackToOriginalTableWhenNoBaseline() {
    var runner = new DockerCommandRunner();
    var baseline = new BaselineService(runner, "da-spark-master");
    var tool = new ProfilerTool(runner, "da-spark-master", baseline);

    var resolved = baseline.resolveTable("unknown.table");
    assertThat(resolved).isEqualTo("unknown.table");
}
```

在 SandboxToolTest 中追加：

```java
@Test
void shouldRewriteSqlForBaseline() {
    var runner = new DockerCommandRunner();
    var baseline = new BaselineService(runner, "da-spark-master");
    baseline.recordBaseline("dw.mr_5g_15min", "baseline.dw__mr_5g_15min",
            1, Map.of("row_count", 1000));
    var tool = new SandboxTool(runner, "da-spark-master", baseline);

    var spec = new com.wireless.agent.core.Spec(com.wireless.agent.core.Spec.TaskDirection.FORWARD_ETL);
    spec.sources(java.util.List.of(
        new com.wireless.agent.core.Spec.SourceBinding().role("mr")
            .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))
    ));

    var sql = "SELECT * FROM dw.mr_5g_15min WHERE rsrp_avg < -110;";
    var rewritten = tool.rewriteForBaseline(sql, spec);
    assertThat(rewritten).contains("baseline.dw__mr_5g_15min");
    assertThat(rewritten).doesNotContain("dw.mr_5g_15min");
}

@Test
void shouldNotRewriteWhenNoBaseline() {
    var runner = new DockerCommandRunner();
    var baseline = new BaselineService(runner, "da-spark-master");
    var tool = new SandboxTool(runner, "da-spark-master", baseline);

    var spec = new com.wireless.agent.core.Spec(com.wireless.agent.core.Spec.TaskDirection.FORWARD_ETL);
    spec.sources(java.util.List.of(
        new com.wireless.agent.core.Spec.SourceBinding().role("mr")
            .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))
    ));

    var sql = "SELECT * FROM dw.mr_5g_15min;";
    var rewritten = tool.rewriteForBaseline(sql, spec);
    assertThat(rewritten).isEqualTo(sql); // No rewrite
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -Dtest=ProfilerToolTest,SandboxToolTest,BaselineServiceTest
```

期望: 全部通过

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/tools/ProfilerTool.java \
        src/main/java/com/wireless/agent/tools/SandboxTool.java \
        src/test/java/com/wireless/agent/tools/ProfilerToolTest.java \
        src/test/java/com/wireless/agent/tools/SandboxToolTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m2): profiler and sandbox prefer baseline tables when available"
```

---

### Task 7: AgentCore 接入 KB + BaselineService

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/core/AgentCore.java`
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/Main.java`

- [ ] **Step 1: 修改 AgentCore.java — 新增 KB 和 BaselineService 字段，注入到工具**

在字段区新增：

```java
private final DomainKnowledgeBase kb;
private final BaselineService baselineService;
```

修改构造函数链：

```java
public AgentCore(DeepSeekClient llmClient) {
    this(llmClient, Spec.TaskDirection.FORWARD_ETL,
         "thrift://hive-metastore:9083", "da-spark-master",
         new com.wireless.agent.knowledge.DomainKnowledgeBase());
}

public AgentCore(DeepSeekClient llmClient, Spec.TaskDirection taskDirection) {
    this(llmClient, taskDirection,
         "thrift://hive-metastore:9083", "da-spark-master",
         new com.wireless.agent.knowledge.DomainKnowledgeBase());
}

public AgentCore(DeepSeekClient llmClient, Spec.TaskDirection taskDirection,
                 String hmsUri, String sparkContainer) {
    this(llmClient, taskDirection, hmsUri, sparkContainer,
         new com.wireless.agent.knowledge.DomainKnowledgeBase());
}

public AgentCore(DeepSeekClient llmClient, Spec.TaskDirection taskDirection,
                 String hmsUri, String sparkContainer,
                 com.wireless.agent.knowledge.DomainKnowledgeBase kb) {
    this.llmClient = llmClient;
    this.spec = new Spec(taskDirection);
    this.cmdRunner = new DockerCommandRunner();
    this.kb = kb;
    this.baselineService = new BaselineService(cmdRunner, sparkContainer);
    this.metadataTool = new HmsMetadataTool(hmsUri, kb);
    this.profilerTool = new ProfilerTool(cmdRunner, sparkContainer, baselineService);
    this.codegenTool = new CodegenTool(llmClient);
    this.validatorTool = new ValidatorTool();
    this.sandboxTool = new SandboxTool(cmdRunner, sparkContainer, baselineService);
}
```

在 `callLlmExtract` 方法中，追加 KB context 到 system prompt（仅在实际有 LLM 时）。修改 `callLlmExtract` 方法，在构建 messages 时将 KB context 注入：

在构建 prompt 的 messages 时, 将 KB context 追加到 system prompt:

```java
private Map<String, Object> callLlmExtract(String userMessage) {
    if (llmClient == null) {
        return mockExtract(userMessage);
    }
    try {
        var currentJson = MAPPER.writeValueAsString(spec);
        var prompt = Prompts.buildExtractSpecPrompt(userMessage, currentJson);
        var systemPrompt = Prompts.SYSTEM_PROMPT;
        // Inject KB context
        var kbContext = metadataTool.kbPromptContext(spec.networkContext().kpiFamily());
        if (!kbContext.isEmpty()) {
            systemPrompt = systemPrompt + "\n\n" + kbContext;
        }
        var messages = List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", prompt)
        );
        var response = llmClient.chat(messages);
        return MAPPER.readValue(response, Map.class);
    } catch (JsonProcessingException e) {
        return Map.of(
            "intent_update", Map.of(),
            "next_action", "ask_clarifying",
            "clarifying_question", "抱歉,我没理解,能换个说法吗？"
        );
    }
}
```

添加暴露 KB 和 BaselineService 的公共方法：

```java
public DomainKnowledgeBase kb() { return kb; }
public BaselineService baselineService() { return baselineService; }
```

- [ ] **Step 2: 修改 Main.java — 无变更（新构造函数签名兼容）**

Main.java 中使用 `new AgentCore(llmClient, Spec.TaskDirection.FORWARD_ETL, hmsUri, sparkContainer)` 保持不变，这个 4 参数构造函数会自动创建 KB。

- [ ] **Step 3: 运行测试确保旧测试未回归**

```bash
mvn test
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/wireless/agent/core/AgentCore.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m2): wire domain kb and baseline service into agent core pipeline"
```

---

### Task 8: 集成测试 — M2 端到端验证

**Files:**
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/IntegrationTest.java`
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/core/AgentCoreTest.java`

- [ ] **Step 1: 更新 AgentCoreTest.java 确保 KB 注入工作**

```java
@Test
void shouldHaveDomainKnowledgeBaseInjected() {
    var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
            "thrift://nonexistent:9999", "da-spark-master");
    assertThat(agent.kb()).isNotNull();
    assertThat(agent.kb().all()).isNotEmpty();
}

@Test
void shouldHaveBaselineServiceInjected() {
    var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
            "thrift://nonexistent:9999", "da-spark-master");
    assertThat(agent.baselineService()).isNotNull();
    assertThat(agent.baselineService().name()).isEqualTo("baseline");
}
```

- [ ] **Step 2: 在 IntegrationTest.java 追加 M2 端到端测试**

```java
@Test
void shouldProcessCoverageTaskWithDomainKnowledge() {
    var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
            "thrift://nonexistent:9999", "da-spark-master");

    // Weak coverage task — should match KB entries
    var result = agent.processMessage("近7天每个区县5G弱覆盖小区清单，RSRP<-110dBm占比>30%");

    assertThat(result.get("next_action"))
            .isIn("code_done", "dry_run_ok", "sandbox_failed");
    assertThat(result.get("code").toString()).isNotEmpty();

    // Verify KB hits
    var kbHits = agent.kb().search("弱覆盖");
    assertThat(kbHits).isNotEmpty();
    assertThat(kbHits.get(0).name()).contains("弱覆盖");
}

@Test
void shouldProcessMobilityTaskWithDomainKnowledge() {
    var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
            "thrift://nonexistent:9999", "da-spark-master");

    var result = agent.processMessage("按cell汇总最近切换失败原因分布，包括早切晚切");

    assertThat(result.get("next_action"))
            .isIn("code_done", "dry_run_ok", "sandbox_failed");
    assertThat(result.get("code").toString()).isNotEmpty();

    var kbHits = agent.kb().search("早切");
    assertThat(kbHits).isNotEmpty();
}

@Test
void shouldCreateBaselineAndPreferItForDryRun() {
    var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
            "thrift://nonexistent:9999", "da-spark-master");
    var baseline = agent.baselineService();

    // Simulate baseline creation (in-memory metadata only, no Docker needed)
    baseline.recordBaseline("dw.mr_5g_15min", "baseline.dw__mr_5g_15min",
            1, Map.of("row_count", 1000));

    assertThat(baseline.hasBaseline("dw.mr_5g_15min")).isTrue();
    assertThat(baseline.resolveTable("dw.mr_5g_15min"))
            .isEqualTo("baseline.dw__mr_5g_15min");
}

@Test
void shouldNotHaveBaselineForUnknownTable() {
    var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
            "thrift://nonexistent:9999", "da-spark-master");
    var baseline = agent.baselineService();

    assertThat(baseline.hasBaseline("unknown.table")).isFalse();
    assertThat(baseline.resolveTable("unknown.table")).isEqualTo("unknown.table");
}
```

- [ ] **Step 3: 运行全套测试**

```bash
mvn test
```

期望: 全部通过，无回归

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/wireless/agent/IntegrationTest.java \
        src/test/java/com/wireless/agent/core/AgentCoreTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "test(m2): integration tests for domain kb and baseline service"
```

---

### Task 9: 配置文件与 Demo 验证

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/resources/agent.properties`
- Modify: `D:/agent-code/data-agent/README.md`

- [ ] **Step 1: 更新 agent.properties 追加 M2 配置**

```properties
# M2 — Domain Knowledge Base + Baseline
kb.resource.path=com/wireless/agent/knowledge/domain-kb.json
baseline.sample.percent=1.0
baseline.hdfs.prefix=baseline
baseline.auto_create=false
```

- [ ] **Step 2: 验证编译 + 测试**

```bash
mvn compile && mvn test
```

- [ ] **Step 3: 运行 M2 demo**

```bash
mvn exec:java -Dexec.mainClass="com.wireless.agent.Main" -Dexec.args="--demo --no-llm"
```

期望输出应包含:
- 能正常处理弱覆盖和切换相关的 NL 输入
- 代码中包含从 KB 获取的领域口径
- 若无 Docker 运行，sandbox 降级输出 error 但不崩溃

- [ ] **Step 4: 更新 README.md — 追加 M2 段落**

```markdown
## M2 — Domain Knowledge Base + Sample Baseline

**Domain Knowledge Base:** 内置无线评估方法论字典，包含 5 大 KPI 族 (coverage/mobility/accessibility/retainability/qoe) 的定义、计算公式、阈值、关联源表和 join 模式。

```bash
# KB 目前为 classpath JSON 资源，启动时自动加载
# 可通过 MetadataTool.searchKb() API 以编程方式查询
```

**Sample Baseline Service:** 为源表创建 1% 采样快照，存储到 HDFS `/baseline/` 路径，ProfilerTool 和 SandboxTool 优先使用基线数据。

```bash
# 为当前 spec 的源表创建基线 (需要 Docker 栈运行)
# 基线自动在 ProfilerTool.run() 和 SandboxTool.dryRun() 中优先生效
```

**KB 查询示例:**

| 查询方式 | 说明 |
|----------|------|
| `kb.search("弱覆盖")` | 全文搜索: 匹配名称/别名/关键词/定义 |
| `kb.lookupByKpiFamily("coverage")` | 按 KPI 族查询 |
| `kb.lookupByCategory("methodology")` | 按类别查询(方法论/术语/join模式) |
| `metadataTool.searchKb("RSRP")` | 通过 MetadataTool 间接查询 |
```

- [ ] **Step 5: 最终 commit**

```bash
git add src/main/resources/agent.properties README.md
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "docs(m2): add domain kb and baseline config, update readme"
```

---

## M2 完成标准（DoD）

- [ ] `mvn test` 全部测试通过，无回归
- [ ] DomainKnowledgeBase 从 classpath JSON 加载，包含 ≥15 条覆盖/移动性/接入/保持/QoE 的 KPI 定义 + 方法论 + 术语 + join 模式
- [ ] `kb.search("弱覆盖")` 返回匹配条目，包含 `kpi-001` (弱覆盖小区)
- [ ] `kb.lookupByKpiFamily("mobility")` 返回切换相关条目
- [ ] `kb.lookupByCategory("terminology")` 返回 RAT/粒度/时间术语定义
- [ ] HmsMetadataTool.lookup() 返回结果中附加了 `domain_knowledge` 字段
- [ ] BaselineService 能创建基线（`buildCreateBaselineSql` 生成正确的 CTAS SQL）
- [ ] BaselineService 元数据跟踪正确（`hasBaseline` / `resolveTable` / `getBaselineMeta`）
- [ ] ProfilerTool 在 baseline 存在时优先 query baseline 表
- [ ] SandboxTool 在 baseline 存在时重写 SQL 中的源表名为 baseline 表名
- [ ] AgentCore 自动注入 KB 和 BaselineService，LLM 路径注入 KB context 到 system prompt
- [ ] `mvn exec:java -Dexec.mainClass="com.wireless.agent.Main" -Dexec.args="--demo --no-llm"` 端到端跑通

---

## 后续

M2 完成后 → M3（引擎扩展: Flink SQL + Java Flink Stream API）。
