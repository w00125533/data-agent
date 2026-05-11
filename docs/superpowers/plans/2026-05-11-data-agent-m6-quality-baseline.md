# M6 Quality Baseline 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 E2E 评估体系：30-35 个无线评估典型任务样本作为回归基线，支持多维度评分和 CI 门禁。

**Architecture:** 新增 `com.wireless.agent.eval` 包，包含 EvalCase（评估案例数据模型）、EvalResult（单案例评分）、EvalReport（聚合报告）、EvalRunner（评估引擎）。评估案例以 JSON 文件存储在 `src/test/resources/eval/` 下，按 7 个域内类别组织。EvalRunner 复用 AgentCore 的无 LLM 模式（null LLM），通过 FakeLLMForEval 提供可控的 LLM 响应序列，按 Spec 字段准确率、代码可编译、轮次收敛三个维度打分。回归门禁通过 Maven profile + shell 脚本实现：新分数 vs 基线对比，跌幅 >= 5% 阻塞 CI。

**Tech Stack:** Java 17, Maven, JUnit 5 (@ParameterizedTest), AssertJ, Jackson JSON

---

## 文件结构（M6 完成后的新增/变更）

```
data-agent/
├── src/main/java/com/wireless/agent/eval/
│   ├── EvalCategory.java          # 新增: 评估类别枚举
│   ├── EvalCase.java              # 新增: 单个评估案例数据模型
│   ├── EvalResult.java            # 新增: 单案例评分结果
│   ├── EvalReport.java            # 新增: 聚合报告
│   └── EvalRunner.java            # 新增: 评估引擎
├── src/test/java/com/wireless/agent/eval/
│   ├── EvalCaseTest.java          # 新增: 数据模型序列化测试
│   ├── EvalResultTest.java        # 新增: 评分逻辑测试
│   ├── EvalRunnerTest.java        # 新增: 评估引擎测试
│   └── E2EEvalSetTest.java        # 新增: 参数化端到端评估
├── src/test/resources/eval/
│   ├── coverage-cases.json        # 新增: 覆盖类 (5 cases)
│   ├── mobility-cases.json        # 新增: 移动性类 (5 cases)
│   ├── accessibility-cases.json   # 新增: 接入/保持类 (5 cases)
│   ├── qoe-cases.json             # 新增: 感知KPI类 (5 cases)
│   ├── multi-source-cases.json    # 新增: 多源联邦 (5 cases)
│   ├── stream-batch-cases.json    # 新增: 流批混合 (5 cases)
│   └── reverse-cases.json         # 新增: 反向合成专题 (5 cases)
├── scripts/
│   └── eval-regression-check.sh   # 新增: CI 回归门禁脚本
├── pom.xml                        # 修改: +eval profile
└── README.md                      # 修改: +M6 章节
```

---

### Task 1: EvalCategory + EvalCase 数据模型

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/eval/EvalCategory.java`
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/eval/EvalCase.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/eval/EvalCaseTest.java`

- [ ] **Step 1: 创建 EvalCaseTest.java**

```java
package com.wireless.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wireless.agent.core.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalCaseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldDeserializeEvalCaseFromJson() throws Exception {
        var json = """
        {
          "id": "eval-coverage-001",
          "category": "coverage",
          "direction": "forward_etl",
          "engine": "spark_sql",
          "nl_input": "给我近30天每个区县5G弱覆盖小区清单",
          "expected_spec": {
            "target_name": "weak_cov_by_district",
            "kpi_family": "coverage",
            "ne_grain": "district",
            "time_grain": "day",
            "rat": "5G_SA",
            "timeliness": "batch_daily",
            "sources": ["dw.mr_5g_15min", "dim.engineering_param"]
          },
          "expected_code_patterns": ["rsrp_avg", "weak_cov_ratio"],
          "max_turns": 5,
          "must_compile": true,
          "must_dry_run": true
        }""";
        var c = MAPPER.readValue(json, EvalCase.class);
        assertThat(c.id()).isEqualTo("eval-coverage-001");
        assertThat(c.category()).isEqualTo(EvalCategory.COVERAGE);
        assertThat(c.direction()).isEqualTo(Spec.TaskDirection.FORWARD_ETL);
        assertThat(c.engine()).isEqualTo("spark_sql");
        assertThat(c.nlInput()).contains("弱覆盖");
        assertThat(c.expectedSpec().get("target_name")).isEqualTo("weak_cov_by_district");
        assertThat(c.expectedSpec().get("kpi_family")).isEqualTo("coverage");
        assertThat(c.expectedCodePatterns()).containsExactly("rsrp_avg", "weak_cov_ratio");
        assertThat(c.maxTurns()).isEqualTo(5);
        assertThat(c.mustCompile()).isTrue();
    }

    @Test
    void shouldDeserializeEvalCaseWithFakeLlmResponses() throws Exception {
        var json = """
        {
          "id": "eval-reverse-001",
          "category": "reverse",
          "direction": "reverse_synthetic",
          "engine": "java_flink_streamapi",
          "nl_input": "这是切换失败分析 Flink SQL:\\nINSERT INTO ...",
          "fake_llm_responses": ["resp1", "resp2"],
          "expected_spec": {"target_name": "synthetic_test_data"},
          "expected_code_patterns": ["DataStream", "generator"],
          "max_turns": 3,
          "must_compile": false,
          "must_dry_run": false
        }""";
        var c = MAPPER.readValue(json, EvalCase.class);
        assertThat(c.fakeLlmResponses()).containsExactly("resp1", "resp2");
        assertThat(c.direction()).isEqualTo(Spec.TaskDirection.REVERSE_SYNTHETIC);
    }

    @Test
    void shouldSerializeBackToJson() throws Exception {
        var c = new EvalCase("test-001", EvalCategory.COVERAGE,
                Spec.TaskDirection.FORWARD_ETL, "spark_sql",
                "给我弱覆盖数据",
                Map.of("target_name", "test", "kpi_family", "coverage",
                        "ne_grain", "cell", "sources", List.of("dw.mr_5g_15min")),
                List.of("SELECT", "rsrp"),
                List.of(), 5, true, true);
        var json = MAPPER.writeValueAsString(c);
        assertThat(json).contains("test-001");
        assertThat(json).contains("coverage");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=EvalCaseTest
```

期望: 编译失败（EvalCategory、EvalCase 类不存在）。

- [ ] **Step 3: 创建 EvalCategory.java**

```java
package com.wireless.agent.eval;

import com.fasterxml.jackson.annotation.JsonValue;

/** Seven domain categories for wireless network perception evaluation. */
public enum EvalCategory {
    COVERAGE("coverage"),
    MOBILITY("mobility"),
    ACCESSIBILITY("accessibility"),
    RETAINABILITY("retainability"),
    QOE("qoe"),
    MULTI_SOURCE("multi_source"),
    STREAM_BATCH("stream_batch"),
    REVERSE("reverse");

    private final String value;
    EvalCategory(String v) { value = v; }

    @JsonValue
    public String value() { return value; }
}
```

- [ ] **Step 4: 创建 EvalCase.java**

```java
package com.wireless.agent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wireless.agent.core.Spec;

import java.util.List;
import java.util.Map;

/** A single evaluation case: NL input → expected spec + code. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalCase(
        @JsonProperty("id")                    String id,
        @JsonProperty("category")              EvalCategory category,
        @JsonProperty("direction")             Spec.TaskDirection direction,
        @JsonProperty("engine")                String engine,
        @JsonProperty("nl_input")              String nlInput,
        @JsonProperty("expected_spec")         Map<String, Object> expectedSpec,
        @JsonProperty("expected_code_patterns") List<String> expectedCodePatterns,
        @JsonProperty("fake_llm_responses")    List<String> fakeLlmResponses,
        @JsonProperty("max_turns")             int maxTurns,
        @JsonProperty("must_compile")          boolean mustCompile,
        @JsonProperty("must_dry_run")          boolean mustDryRun) {

    public EvalCase {
        if (fakeLlmResponses == null) fakeLlmResponses = List.of();
        if (expectedCodePatterns == null) expectedCodePatterns = List.of();
    }
}
```

- [ ] **Step 5: 运行 EvalCaseTest**

```bash
mvn test -Dtest=EvalCaseTest
```

期望: 3 passed。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/wireless/agent/eval/EvalCategory.java src/main/java/com/wireless/agent/eval/EvalCase.java src/test/java/com/wireless/agent/eval/EvalCaseTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m6): add EvalCategory and EvalCase data models for evaluation cases"
```

---

### Task 2: EvalResult + EvalReport 评分模型

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/eval/EvalResult.java`
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/eval/EvalReport.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/eval/EvalResultTest.java`

- [ ] **Step 1: 创建 EvalResultTest.java**

```java
package com.wireless.agent.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalResultTest {

    @Test
    void shouldComputeOverallScore() {
        var r = new EvalResult("eval-001", EvalCategory.COVERAGE,
                0.9, true, true, 3, true);
        // overall = avg of: spec(0.9) + code(1.0 if compile) + dryRun(0.0 if not required) + turn(0.66 if 3/5)
        // score = (0.9 + 1.0 + 0.0 + 0.67) / 4 = 0.64
        assertThat(r.overallScore()).isGreaterThan(0.6);
        assertThat(r.passed()).isTrue();
    }

    @Test
    void shouldFailWhenSpecBelowThreshold() {
        var r = new EvalResult("eval-002", EvalCategory.MOBILITY,
                0.4, true, true, 3, true);
        assertThat(r.passed()).isFalse();
    }

    @Test
    void shouldComputeRegressionDelta() {
        var baseline = new EvalResult("eval-001", EvalCategory.COVERAGE,
                0.9, true, false, 3, false);
        var current = new EvalResult("eval-001", EvalCategory.COVERAGE,
                0.7, true, false, 3, false);
        var delta = current.overallScore() - baseline.overallScore();
        assertThat(delta).isNegative(); // regression
    }

    @Test
    void shouldBuildReportFromResults() {
        var results = List.of(
                new EvalResult("eval-001", EvalCategory.COVERAGE, 0.9, true, false, 3, false),
                new EvalResult("eval-002", EvalCategory.MOBILITY, 0.5, false, false, 5, false),
                new EvalResult("eval-003", EvalCategory.QOE, 0.7, true, false, 4, false));

        var report = new EvalReport(results, "spark_sql");
        assertThat(report.totalCases()).isEqualTo(3);
        assertThat(report.passedCases()).isEqualTo(2);
        assertThat(report.failedCases()).isEqualTo(1);
        assertThat(report.passRate()).isGreaterThan(0.6);
        assertThat(report.byCategory()).containsKeys(EvalCategory.COVERAGE, EvalCategory.MOBILITY, EvalCategory.QOE);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=EvalResultTest
```

期望: 编译失败（类不存在）。

- [ ] **Step 3: 创建 EvalResult.java**

```java
package com.wireless.agent.eval;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Scoring result for a single eval case. */
public record EvalResult(
        @JsonProperty("case_id")          String caseId,
        @JsonProperty("category")         EvalCategory category,
        @JsonProperty("spec_accuracy")    double specAccuracy,
        @JsonProperty("code_compiles")    boolean codeCompiles,
        @JsonProperty("dry_run_passed")   boolean dryRunPassed,
        @JsonProperty("turns_used")       int turnsUsed,
        @JsonProperty("must_dry_run")     boolean mustDryRun) {

    private static final double SPEC_THRESHOLD = 0.5;

    /** Overall score: weighted average of spec/code/dryRun/turn dimensions. */
    public double overallScore() {
        double codeScore = codeCompiles ? 1.0 : 0.0;
        double dryRunScore;
        if (!mustDryRun) {
            dryRunScore = 1.0; // not required, don't penalize
        } else {
            dryRunScore = dryRunPassed ? 1.0 : 0.0;
        }
        double turnScore = 1.0 - Math.min((double) turnsUsed / 5.0, 1.0);
        return (specAccuracy + codeScore + dryRunScore + turnScore) / 4.0;
    }

    /** Whether this case passes the quality gate. */
    public boolean passed() {
        return specAccuracy >= SPEC_THRESHOLD && codeCompiles && (dryRunPassed || !mustDryRun);
    }
}
```

- [ ] **Step 4: 创建 EvalReport.java**

```java
package com.wireless.agent.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/** Aggregated evaluation report across all cases. */
public record EvalReport(
        @JsonProperty("total_cases")      int totalCases,
        @JsonProperty("passed_cases")     int passedCases,
        @JsonProperty("failed_cases")     int failedCases,
        @JsonProperty("pass_rate")        double passRate,
        @JsonProperty("average_score")    double averageScore,
        @JsonProperty("engine")           String engine,
        @JsonProperty("by_category")      Map<EvalCategory, Double> byCategory,
        @JsonProperty("results")          List<EvalResult> results,
        @JsonProperty("generated_at")     String generatedAt) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    public EvalReport(List<EvalResult> results, String engine) {
        this(results.size(),
             (int) results.stream().filter(EvalResult::passed).count(),
             (int) results.stream().filter(r -> !r.passed()).count(),
             results.isEmpty() ? 0.0 : (double) results.stream().filter(EvalResult::passed).count() / results.size(),
             results.isEmpty() ? 0.0 : results.stream().mapToDouble(EvalResult::overallScore).average().orElse(0.0),
             engine,
             results.stream().collect(Collectors.groupingBy(
                     EvalResult::category,
                     Collectors.averagingDouble(EvalResult::overallScore))),
             results,
             java.time.LocalDateTime.now().toString());
    }

    /** Write report as JSON to file. */
    public void writeTo(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), this);
    }

    /** Load a previous baseline report for comparison. */
    public static EvalReport loadFrom(Path file) throws IOException {
        return MAPPER.readValue(file.toFile(), EvalReport.class);
    }

    /** Compute regression delta vs a baseline report. */
    public double regressionDelta(EvalReport baseline) {
        return this.averageScore - baseline.averageScore;
    }
}
```

- [ ] **Step 5: 运行 EvalResultTest**

```bash
mvn test -Dtest=EvalResultTest
```

期望: 4 passed。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/wireless/agent/eval/EvalResult.java src/main/java/com/wireless/agent/eval/EvalReport.java src/test/java/com/wireless/agent/eval/EvalResultTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m6): add EvalResult scoring model and EvalReport aggregation"
```

---

### Task 3: FakeLLMForEval + EvalRunner 评估引擎

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/eval/EvalRunner.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/eval/EvalRunnerTest.java`

- [ ] **Step 1: 创建 EvalRunnerTest.java**

```java
package com.wireless.agent.eval;

import com.wireless.agent.core.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalRunnerTest {

    @Test
    void shouldScoreSingleEvalCase() {
        var evalCase = new EvalCase("test-001", EvalCategory.COVERAGE,
                Spec.TaskDirection.FORWARD_ETL, "spark_sql",
                "给我弱覆盖小区统计",
                Map.of("target_name", "weak_cov", "kpi_family", "coverage",
                        "ne_grain", "district", "sources", List.of("dw.mr_5g_15min")),
                List.of("SELECT", "rsrp"),
                List.of(), 5, true, false);

        var runner = new EvalRunner(null);
        var result = runner.evaluate(evalCase);

        assertThat(result.caseId()).isEqualTo("test-001");
        assertThat(result.category()).isEqualTo(EvalCategory.COVERAGE);
        assertThat(result.specAccuracy()).isGreaterThan(0.0);
        assertThat(result.overallScore()).isGreaterThan(0.0);
    }

    @Test
    void shouldRunFullSuite() {
        var cases = List.of(
                new EvalCase("eval-cov-001", EvalCategory.COVERAGE,
                        Spec.TaskDirection.FORWARD_ETL, "spark_sql",
                        "给我近30天每个区县5G弱覆盖小区清单",
                        Map.of("target_name", "weak_cov_by_district", "kpi_family", "coverage",
                                "ne_grain", "district", "time_grain", "day",
                                "sources", List.of("dw.mr_5g_15min")),
                        List.of("SELECT", "rsrp"),
                        List.of(), 5, true, false),
                new EvalCase("eval-mob-001", EvalCategory.MOBILITY,
                        Spec.TaskDirection.FORWARD_ETL, "flink_sql",
                        "实时监控最近1小时切换失败次数",
                        Map.of("target_name", "ho_failure", "kpi_family", "mobility",
                                "ne_grain", "cell", "time_grain", "hour",
                                "sources", List.of("signaling_events")),
                        List.of("SELECT", "handover"),
                        List.of(), 3, true, false));

        var runner = new EvalRunner(null);
        var report = runner.runSuite(cases, "spark_sql");

        assertThat(report.totalCases()).isEqualTo(2);
        assertThat(report.passedCases()).isGreaterThanOrEqualTo(0);
        assertThat(report.averageScore()).isGreaterThan(0.0);
    }

    @Test
    void shouldLoadCasesFromJsonFile() throws Exception {
        // Create temp eval cases file
        var tmpFile = java.nio.file.Files.createTempFile("eval-cases", ".json");
        try {
            java.nio.file.Files.writeString(tmpFile, """
            [
              {
                "id": "eval-001",
                "category": "coverage",
                "direction": "forward_etl",
                "engine": "spark_sql",
                "nl_input": "给我弱覆盖数据",
                "expected_spec": {
                  "target_name": "weak_cov",
                  "kpi_family": "coverage",
                  "ne_grain": "district",
                  "sources": ["dw.mr_5g_15min"]
                },
                "expected_code_patterns": ["SELECT"],
                "max_turns": 5,
                "must_compile": true,
                "must_dry_run": false
              }
            ]""");
            var cases = EvalRunner.loadCases(tmpFile);
            assertThat(cases).hasSize(1);
            assertThat(cases.get(0).id()).isEqualTo("eval-001");
        } finally {
            java.nio.file.Files.deleteIfExists(tmpFile);
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=EvalRunnerTest
```

期望: 编译失败（EvalRunner 不存在）。

- [ ] **Step 3: 创建 EvalRunner.java**

```java
package com.wireless.agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wireless.agent.core.AgentCore;
import com.wireless.agent.core.Spec;
import com.wireless.agent.llm.DeepSeekClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Runs eval cases against AgentCore and scores results. */
public class EvalRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String hmsUri;
    private final String sparkContainer;
    private final String flinkContainer;

    public EvalRunner(String hmsUri) {
        this(hmsUri, "da-spark-master", "da-flink-jobmanager");
    }

    public EvalRunner(String hmsUri, String sparkContainer, String flinkContainer) {
        this.hmsUri = hmsUri;
        this.sparkContainer = sparkContainer;
        this.flinkContainer = flinkContainer;
    }

    /** Load eval cases from a JSON file. */
    public static List<EvalCase> loadCases(Path file) throws IOException {
        var content = Files.readString(file);
        return MAPPER.readValue(content, new TypeReference<List<EvalCase>>() {});
    }

    /** Evaluate a single case. */
    public EvalResult evaluate(EvalCase evalCase) {
        var fakeLlm = evalCase.fakeLlmResponses().isEmpty()
                ? null
                : new FakeLLMForEval(evalCase.fakeLlmResponses());
        var agent = new AgentCore(fakeLlm, evalCase.direction(),
                hmsUri, sparkContainer, flinkContainer, new com.wireless.agent.knowledge.DomainKnowledgeBase());

        // Run the conversation
        Map<String, Object> result = null;
        int turns = 0;
        for (int i = 0; i < evalCase.maxTurns(); i++) {
            result = agent.processMessage(evalCase.nlInput());
            turns = i + 1;
            var nextAction = result.get("next_action").toString();
            if ("code_done".equals(nextAction) || "dry_run_ok".equals(nextAction)
                    || "sandbox_failed".equals(nextAction) || "dual_dry_run_ok".equals(nextAction)
                    || "step1_ok_step2_failed".equals(nextAction)) {
                break;
            }
        }

        var spec = agent.spec();
        var specAccuracy = computeSpecAccuracy(spec, evalCase.expectedSpec());
        var codeCompiles = result != null && result.get("code") != null
                && !result.get("code").toString().isBlank();
        var dryRunPassed = result != null && "dry_run_ok".equals(result.get("next_action").toString());
        var codeOk = result != null && evalCase.expectedCodePatterns().stream()
                .allMatch(p -> result.get("code").toString().toLowerCase().contains(p.toLowerCase()));

        // Code compiles only if patterns match AND non-empty
        codeCompiles = codeCompiles && codeOk;

        return new EvalResult(evalCase.id(), evalCase.category(),
                specAccuracy, codeCompiles, dryRunPassed, turns, evalCase.mustDryRun());
    }

    /** Run a full suite and produce a report. */
    public EvalReport runSuite(List<EvalCase> cases, String engine) {
        var results = new ArrayList<EvalResult>();
        for (var c : cases) {
            try {
                results.add(evaluate(c));
            } catch (Exception e) {
                results.add(new EvalResult(c.id(), c.category(), 0.0, false, false, c.maxTurns(), false));
                System.err.println("[EvalRunner] Failed case " + c.id() + ": " + e.getMessage());
            }
        }
        return new EvalReport(results, engine);
    }

    /** Compute spec accuracy by comparing expected fields to actual spec state. */
    double computeSpecAccuracy(Spec spec, Map<String, Object> expected) {
        if (expected.isEmpty()) return 1.0;
        double hits = 0;
        var total = expected.size();

        for (var e : expected.entrySet()) {
            var key = e.getKey();
            var expectedVal = e.getValue();
            switch (key) {
                case "target_name" -> {
                    var target = spec.target();
                    if (target != null && expectedVal.toString().equals(target.name())) hits++;
                }
                case "kpi_family" -> {
                    if (expectedVal.toString().equals(spec.networkContext().kpiFamily())) hits++;
                }
                case "ne_grain" -> {
                    if (expectedVal.toString().equals(spec.networkContext().neGrain())) hits++;
                }
                case "time_grain" -> {
                    if (expectedVal.toString().equals(spec.networkContext().timeGrain())) hits++;
                }
                case "rat" -> {
                    if (expectedVal.toString().equals(spec.networkContext().rat())) hits++;
                }
                case "timeliness" -> {
                    var target = spec.target();
                    if (target != null && expectedVal.toString().equals(target.timeliness())) hits++;
                }
                case "business_definition" -> {
                    var target = spec.target();
                    if (target != null && !target.businessDefinition().isEmpty()) hits += 0.5;
                    if (target != null && target.businessDefinition().contains(expectedVal.toString())) hits += 0.5;
                }
                case "sources" -> {
                    @SuppressWarnings("unchecked")
                    var expectedSources = (List<String>) expectedVal;
                    if (expectedSources != null) {
                        for (var src : expectedSources) {
                            var found = spec.sources().stream()
                                    .anyMatch(s -> s.binding().getOrDefault("table_or_topic", "")
                                            .toString().contains(src));
                            if (found) hits++;
                        }
                        hits = hits / expectedSources.size(); // normalize
                    }
                }
            }
        }
        return hits / total;
    }

    /** Fake LLM for eval: returns pre-canned responses in sequence. */
    static class FakeLLMForEval extends DeepSeekClient {
        private final List<String> responses;
        private int callCount;

        FakeLLMForEval(List<String> responses) {
            super("https://fake", "sk-fake", "fake-model");
            this.responses = responses;
        }

        @Override
        public String chat(List<Map<String, String>> messages) {
            if (callCount < responses.size()) return responses.get(callCount++);
            return """
            {
              "intent_update": {"open_questions": []},
              "next_action": "ready_for_tools",
              "clarifying_question": null
            }""";
        }

        @Override
        public String chat(okhttp3.OkHttpClient http, List<Map<String, String>> msgs) {
            return chat(msgs);
        }
    }
}
```

- [ ] **Step 4: 运行 EvalRunnerTest**

```bash
mvn test -Dtest=EvalRunnerTest
```

期望: 3 passed。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/eval/EvalRunner.java src/test/java/com/wireless/agent/eval/EvalRunnerTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m6): add EvalRunner with FakeLLMForEval and spec accuracy scoring"
```

---

### Task 4: 评估案例 — 覆盖类 (5 cases)

**Files:**
- Create: `D:/agent-code/data-agent/src/test/resources/eval/coverage-cases.json`

- [ ] **Step 1: 创建 coverage-cases.json**

```json
[
  {
    "id": "eval-cov-001",
    "category": "coverage",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "给我近30天每个区县5G弱覆盖小区清单",
    "expected_spec": {
      "target_name": "弱覆盖小区统计",
      "kpi_family": "coverage",
      "ne_grain": "district",
      "time_grain": "day",
      "rat": "5G_SA",
      "timeliness": "batch_daily",
      "sources": ["dw.mr_5g_15min"]
    },
    "expected_code_patterns": ["SELECT", "rsrp"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-cov-002",
    "category": "coverage",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "按cell_id统计5G弱覆盖小区数量，RSRP < -110 dBm占比 > 30%",
    "expected_spec": {
      "target_name": "弱覆盖小区统计",
      "kpi_family": "coverage",
      "ne_grain": "cell",
      "time_grain": "15min",
      "rat": "5G_SA",
      "sources": ["dw.mr_5g_15min"]
    },
    "expected_code_patterns": ["rsrp_avg", "weak_cov_ratio", "30"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-cov-003",
    "category": "coverage",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "近7天各小区4G弱覆盖情况，按district汇总",
    "expected_spec": {
      "kpi_family": "coverage",
      "ne_grain": "district",
      "time_grain": "day",
      "rat": "4G",
      "sources": ["dw.mr_4g_15min"]
    },
    "expected_code_patterns": ["SELECT", "rsrp"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-cov-004",
    "category": "coverage",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "统计每个区县过覆盖（重叠覆盖）小区数量，关联工参表取district和site_name",
    "expected_spec": {
      "kpi_family": "coverage",
      "ne_grain": "district",
      "sources": ["dw.mr_5g_15min", "dim.engineering_param"]
    },
    "expected_code_patterns": ["SELECT", "JOIN", "district"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-cov-005",
    "category": "coverage",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "深度覆盖差小区TOP100，按RSRP排序升序，输出cell_id、district、平均RSRP、采样数",
    "expected_spec": {
      "kpi_family": "coverage",
      "ne_grain": "cell",
      "sources": ["dw.mr_5g_15min"]
    },
    "expected_code_patterns": ["ORDER BY", "LIMIT 100", "rsrp"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  }
]
```

- [ ] **Step 2: 验证 JSON 可解析**

写一个快速验证（在 Task 5 的 E2EEvalSetTest 中会完整运行）：

```bash
mvn test -Dtest=EvalRunnerTest#shouldLoadCasesFromJsonFile
```

期望: PASS（验证 JSON 加载机制正常）。

- [ ] **Step 3: Commit**

```bash
git add src/test/resources/eval/coverage-cases.json
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m6): add 5 coverage category eval cases (weak/over/deep coverage)"
```

---

### Task 5: 评估案例 — 移动性类 (5 cases)

**Files:**
- Create: `D:/agent-code/data-agent/src/test/resources/eval/mobility-cases.json`

- [ ] **Step 1: 创建 mobility-cases.json**

```json
[
  {
    "id": "eval-mob-001",
    "category": "mobility",
    "direction": "forward_etl",
    "engine": "flink_sql",
    "nl_input": "实时监控最近1小时每个小区的切换失败次数",
    "expected_spec": {
      "target_name": "切换失败统计",
      "kpi_family": "mobility",
      "ne_grain": "cell",
      "time_grain": "hour",
      "rat": "5G_SA",
      "sources": ["signaling_events"]
    },
    "expected_code_patterns": ["SELECT", "handover", "failure", "COUNT"],
    "max_turns": 3,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-mob-002",
    "category": "mobility",
    "direction": "forward_etl",
    "engine": "flink_sql",
    "nl_input": "按cell_id汇总切换失败原因分布，区分早切和晚切场景",
    "expected_spec": {
      "kpi_family": "mobility",
      "ne_grain": "cell",
      "time_grain": "15min",
      "sources": ["signaling_events"]
    },
    "expected_code_patterns": ["SELECT", "early", "late", "handover"],
    "max_turns": 4,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-mob-003",
    "category": "mobility",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "统计各小区过去7天的切换成功率，关联工参表的district字段",
    "expected_spec": {
      "kpi_family": "mobility",
      "ne_grain": "cell",
      "time_grain": "day",
      "sources": ["kpi_pm_cell_hour", "dim.engineering_param"]
    },
    "expected_code_patterns": ["SELECT", "JOIN", "handover"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-mob-004",
    "category": "mobility",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "切换失败率TOP50小区清单，输出cell_id、district、失败次数、失败率",
    "expected_spec": {
      "kpi_family": "mobility",
      "ne_grain": "cell",
      "sources": ["kpi_pm_cell_hour"]
    },
    "expected_code_patterns": ["ORDER BY", "LIMIT 50", "failure", "RATE"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-mob-005",
    "category": "mobility",
    "direction": "forward_etl",
    "engine": "flink_sql",
    "nl_input": "滑动窗口5分钟，统计各小区切换请求数和切换成功数，输出成功率",
    "expected_spec": {
      "kpi_family": "mobility",
      "ne_grain": "cell",
      "time_grain": "15min",
      "sources": ["signaling_events"]
    },
    "expected_code_patterns": ["HOP", "TUMBLE", "window", "handover", "success"],
    "max_turns": 3,
    "must_compile": true,
    "must_dry_run": false
  }
]
```

- [ ] **Step 2: Commit**

```bash
git add src/test/resources/eval/mobility-cases.json
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m6): add 5 mobility category eval cases (handover success/failure)"
```

---

### Task 6: 评估案例 — 接入/保持类 (5 cases)

**Files:**
- Create: `D:/agent-code/data-agent/src/test/resources/eval/accessibility-cases.json`

- [ ] **Step 1: 创建 accessibility-cases.json**

```json
[
  {
    "id": "eval-acc-001",
    "category": "accessibility",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "统计各小区RRC建立成功率，输出cell_id、请求数、成功数、成功率",
    "expected_spec": {
      "kpi_family": "accessibility",
      "ne_grain": "cell",
      "time_grain": "15min",
      "rat": "5G_SA",
      "sources": ["kpi_pm_cell_hour"]
    },
    "expected_code_patterns": ["SELECT", "RRC", "success", "COUNT"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-acc-002",
    "category": "accessibility",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "E-RAB建立成功率按区县汇总，过滤掉成功率低于90%的小区",
    "expected_spec": {
      "kpi_family": "accessibility",
      "ne_grain": "district",
      "time_grain": "day",
      "sources": ["kpi_pm_cell_hour"]
    },
    "expected_code_patterns": ["E-RAB", "success", "90"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-acc-003",
    "category": "retainability",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "各小区掉话率统计，过去30天，输出cell_id、掉话次数、总通话次数、掉话率",
    "expected_spec": {
      "kpi_family": "retainability",
      "ne_grain": "cell",
      "time_grain": "day",
      "sources": ["kpi_pm_cell_hour"]
    },
    "expected_code_patterns": ["SELECT", "drop", "RATE"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-acc-004",
    "category": "retainability",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "E-RAB掉线率超过5%的高风险小区清单，按掉线率降序排列",
    "expected_spec": {
      "kpi_family": "retainability",
      "ne_grain": "cell",
      "sources": ["kpi_pm_cell_hour"]
    },
    "expected_code_patterns": ["ORDER BY", "DESC", "drop", "5"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-acc-005",
    "category": "accessibility",
    "direction": "forward_etl",
    "engine": "flink_sql",
    "nl_input": "实时统计最近30分钟每个小区新接入请求数，超过100条则输出告警",
    "expected_spec": {
      "kpi_family": "accessibility",
      "ne_grain": "cell",
      "time_grain": "15min",
      "sources": ["signaling_events"]
    },
    "expected_code_patterns": ["SELECT", "COUNT", "100", "window"],
    "max_turns": 3,
    "must_compile": true,
    "must_dry_run": false
  }
]
```

- [ ] **Step 2: Commit**

```bash
git add src/test/resources/eval/accessibility-cases.json
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m6): add 5 accessibility/retainability category eval cases (RRC/E-RAB/drop)"
```

---

### Task 7: 评估案例 — 感知KPI类 (5 cases)

**Files:**
- Create: `D:/agent-code/data-agent/src/test/resources/eval/qoe-cases.json`

- [ ] **Step 1: 创建 qoe-cases.json**

```json
[
  {
    "id": "eval-qoe-001",
    "category": "qoe",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "感知差小区统计：TCP重传率高于15%且网页时延大于3秒的小区",
    "expected_spec": {
      "kpi_family": "qoe",
      "ne_grain": "cell",
      "time_grain": "15min",
      "rat": "5G_SA",
      "sources": ["dw.qoe_user_cell"]
    },
    "expected_code_patterns": ["SELECT", "tcp", "retrans", "15", "latency", "3"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-qoe-002",
    "category": "qoe",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "每个区县视频卡顿率统计，过去7天，输出district、总视频会话数、卡顿会话数、卡顿率",
    "expected_spec": {
      "kpi_family": "qoe",
      "ne_grain": "district",
      "time_grain": "day",
      "sources": ["dw.qoe_user_cell"]
    },
    "expected_code_patterns": ["video", "stall", "RATE"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-qoe-003",
    "category": "qoe",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "用户感知评分低于3分的小区清单，输出cell_id、平均评分、采样数、主要劣化因子",
    "expected_spec": {
      "kpi_family": "qoe",
      "ne_grain": "cell",
      "sources": ["dw.qoe_user_cell"]
    },
    "expected_code_patterns": ["SELECT", "score", "3"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-qoe-004",
    "category": "qoe",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "网页首屏时延P99统计，按区县汇总，过滤时延>5秒的异常样本",
    "expected_spec": {
      "kpi_family": "qoe",
      "ne_grain": "district",
      "time_grain": "day",
      "sources": ["dw.qoe_user_cell"]
    },
    "expected_code_patterns": ["SELECT", "latency", "PERCENTILE", "5"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-qoe-005",
    "category": "qoe",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "感知差小区关联覆盖指标：弱覆盖且视频卡顿率高的小区TOP20",
    "expected_spec": {
      "kpi_family": "qoe",
      "ne_grain": "cell",
      "sources": ["dw.qoe_user_cell", "dw.mr_5g_15min"]
    },
    "expected_code_patterns": ["SELECT", "JOIN", "rsrp", "video", "stall", "LIMIT 20"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  }
]
```

- [ ] **Step 2: Commit**

```bash
git add src/test/resources/eval/qoe-cases.json
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m6): add 5 QoE category eval cases (TCP/video/web latency/user score)"
```

---

### Task 8: 评估案例 — 多源联邦 + 流批混合 (10 cases)

**Files:**
- Create: `D:/agent-code/data-agent/src/test/resources/eval/multi-source-cases.json`
- Create: `D:/agent-code/data-agent/src/test/resources/eval/stream-batch-cases.json`

- [ ] **Step 1: 创建 multi-source-cases.json**

```json
[
  {
    "id": "eval-multi-001",
    "category": "multi_source",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "关联5G MR和工参表，按区县统计弱覆盖小区，同时输出site经纬度信息",
    "expected_spec": {
      "kpi_family": "coverage",
      "ne_grain": "district",
      "time_grain": "day",
      "rat": "5G_SA",
      "sources": ["dw.mr_5g_15min", "dim.engineering_param"]
    },
    "expected_code_patterns": ["JOIN", "longitude", "latitude", "cell_id"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-multi-002",
    "category": "multi_source",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "关联MR和KPI表，按同cell_id+同15min粒度，分析弱覆盖与切换失败的相关性",
    "expected_spec": {
      "kpi_family": "coverage",
      "ne_grain": "cell",
      "time_grain": "15min",
      "sources": ["dw.mr_5g_15min", "kpi_pm_cell_hour"]
    },
    "expected_code_patterns": ["JOIN", "cell_id", "rsrp", "handover"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-multi-003",
    "category": "multi_source",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "联合信令+MR+工参三表，分析切换失败时的RSRP分布，输出district、失败次数、平均RSRP",
    "expected_spec": {
      "kpi_family": "mobility",
      "ne_grain": "district",
      "sources": ["signaling_events", "dw.mr_5g_15min", "dim.engineering_param"]
    },
    "expected_code_patterns": ["JOIN", "cell_id", "rsrp", "handover", "failure"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-multi-004",
    "category": "multi_source",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "关联QoE用户感知与MR覆盖指标，分析感知差与弱覆盖的重合度，输出重合小区比例",
    "expected_spec": {
      "kpi_family": "qoe",
      "ne_grain": "cell",
      "sources": ["dw.qoe_user_cell", "dw.mr_5g_15min"]
    },
    "expected_code_patterns": ["JOIN", "cell_id", "rsrp", "score"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-multi-005",
    "category": "multi_source",
    "direction": "forward_etl",
    "engine": "spark_sql",
    "nl_input": "关联维表dim.engineering_param，按区县和厂家(vendor)分组统计掉话率",
    "expected_spec": {
      "kpi_family": "retainability",
      "ne_grain": "district",
      "sources": ["kpi_pm_cell_hour", "dim.engineering_param"]
    },
    "expected_code_patterns": ["JOIN", "vendor", "district", "drop"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  }
]
```

- [ ] **Step 2: 创建 stream-batch-cases.json**

```json
[
  {
    "id": "eval-sb-001",
    "category": "stream_batch",
    "direction": "forward_etl",
    "engine": "flink_sql",
    "nl_input": "Kafka信令流join Hive工参维表，实时输出每个小区切换失败次数及district信息",
    "expected_spec": {
      "kpi_family": "mobility",
      "ne_grain": "cell",
      "time_grain": "15min",
      "sources": ["signaling_events", "dim.engineering_param"]
    },
    "expected_code_patterns": ["JOIN", "lookup", "cell_id", "handover"],
    "max_turns": 4,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-sb-002",
    "category": "stream_batch",
    "direction": "forward_etl",
    "engine": "flink_sql",
    "nl_input": "实时消费信令流，按5分钟滚动窗口统计每小区RRC请求数和接入成功率，lookup join工参获取district和site_name",
    "expected_spec": {
      "kpi_family": "accessibility",
      "ne_grain": "cell",
      "time_grain": "15min",
      "sources": ["signaling_events", "dim.engineering_param"]
    },
    "expected_code_patterns": ["TUMBLE", "lookup", "RRC", "join"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-sb-003",
    "category": "stream_batch",
    "direction": "forward_etl",
    "engine": "flink_sql",
    "nl_input": "Kafka signaling_events实时接入，过滤handover事件，按1小时滑动窗口每15分钟触发，统计切换失败数",
    "expected_spec": {
      "kpi_family": "mobility",
      "ne_grain": "cell",
      "time_grain": "15min",
      "sources": ["signaling_events"]
    },
    "expected_code_patterns": ["HOP", "window", "handover", "WHERE event"],
    "max_turns": 3,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-sb-004",
    "category": "stream_batch",
    "direction": "forward_etl",
    "engine": "flink_sql",
    "nl_input": "实时流处理：信令流过滤handover事件，JOIN Hive维表dim.engineering_param获取geolocation，按district聚合切换成功率",
    "expected_spec": {
      "kpi_family": "mobility",
      "ne_grain": "district",
      "time_grain": "15min",
      "sources": ["signaling_events", "dim.engineering_param"]
    },
    "expected_code_patterns": ["JOIN", "handover", "district", "success"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-sb-005",
    "category": "stream_batch",
    "direction": "forward_etl",
    "engine": "java_flink_streamapi",
    "nl_input": "流式切换失败根因分析：需复杂状态机判断早切/晚切/乒乓切换三种类型，输出每种类型的计数和占比",
    "expected_spec": {
      "kpi_family": "mobility",
      "ne_grain": "cell",
      "sources": ["signaling_events"]
    },
    "expected_code_patterns": ["DataStream", "state", "early", "late", "pingpong"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  }
]
```

- [ ] **Step 3: Commit**

```bash
git add src/test/resources/eval/multi-source-cases.json src/test/resources/eval/stream-batch-cases.json
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m6): add 10 multi-source federation and stream-batch hybrid eval cases"
```

---

### Task 9: 评估案例 — 反向合成专题 (5 cases)

**Files:**
- Create: `D:/agent-code/data-agent/src/test/resources/eval/reverse-cases.json`

- [ ] **Step 1: 创建 reverse-cases.json**

```json
[
  {
    "id": "eval-rev-001",
    "category": "reverse",
    "direction": "reverse_synthetic",
    "engine": "java_flink_streamapi",
    "nl_input": "INSERT INTO handover_kpi SELECT cell_id, COUNT(*) AS failure_count FROM signaling_events WHERE event_type = 'handover' AND result = 'failure' GROUP BY cell_id;",
    "expected_spec": {
      "target_name": "synthetic_test_data",
      "kpi_family": "synthetic",
      "sources": ["signaling_events"]
    },
    "expected_code_patterns": ["INSERT", "generator", "synthetic"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-rev-002",
    "category": "reverse",
    "direction": "reverse_synthetic",
    "engine": "java_flink_streamapi",
    "nl_input": "DROP TABLE IF EXISTS coverage_kpi; CREATE TABLE coverage_kpi AS SELECT e.district, COUNT(DISTINCT m.cell_id) AS weak_count FROM dw.mr_5g_15min m JOIN dim.engineering_param e ON m.cell_id = e.cell_id WHERE m.rsrp_avg < -110 GROUP BY e.district;",
    "expected_spec": {
      "target_name": "synthetic_test_data",
      "kpi_family": "synthetic",
      "sources": ["dw.mr_5g_15min", "dim.engineering_param"]
    },
    "expected_code_patterns": ["generator", "INSERT", "synthetic", "rsrp"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-rev-003",
    "category": "reverse",
    "direction": "reverse_synthetic",
    "engine": "java_flink_streamapi",
    "nl_input": "INSERT INTO qoe_kpi SELECT cell_id, AVG(tcp_retrans_rate) AS avg_retrans FROM dw.qoe_user_cell WHERE timestamp > now() - INTERVAL 7 DAY GROUP BY cell_id HAVING AVG(tcp_retrans_rate) > 0.15;",
    "expected_spec": {
      "target_name": "synthetic_test_data",
      "kpi_family": "synthetic",
      "sources": ["dw.qoe_user_cell"]
    },
    "expected_code_patterns": ["generator", "INSERT", "tcp"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-rev-004",
    "category": "reverse",
    "direction": "reverse_synthetic",
    "engine": "java_flink_streamapi",
    "nl_input": "需要覆盖正常切换/早切/晚切三种场景的测试数据，数据量1万行，异常率10%",
    "expected_spec": {
      "target_name": "synthetic_test_data",
      "kpi_family": "synthetic",
      "sources": ["signaling_events"]
    },
    "expected_code_patterns": ["generator", "early", "late", "NUM_ROWS"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  },
  {
    "id": "eval-rev-005",
    "category": "reverse",
    "direction": "reverse_synthetic",
    "engine": "java_flink_streamapi",
    "nl_input": "造一份含cell_id偏斜分布（20% cell占80%数据）的MR测试数据，共5万行",
    "expected_spec": {
      "target_name": "synthetic_test_data",
      "kpi_family": "synthetic",
      "sources": ["dw.mr_5g_15min"]
    },
    "expected_code_patterns": ["generator", "skew", "INSERT"],
    "max_turns": 5,
    "must_compile": true,
    "must_dry_run": false
  }
]
```

- [ ] **Step 2: Commit**

```bash
git add src/test/resources/eval/reverse-cases.json
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m6): add 5 reverse synthetic eval cases (data generation/skew/anomaly)"
```

---

### Task 10: E2EEvalSetTest — 参数化端到端评估测试

**Files:**
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/eval/E2EEvalSetTest.java`

- [ ] **Step 1: 创建 E2EEvalSetTest.java**

```java
package com.wireless.agent.eval;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the full E2E eval set and generates a report. */
class E2EEvalSetTest {

    private static List<EvalCase> allCases;
    private static EvalReport report;

    @BeforeAll
    static void runAllEvals(@TempDir Path tmpDir) throws Exception {
        var runner = new EvalRunner("thrift://nonexistent:9999");
        allCases = new ArrayList<>();

        // Load all eval case files
        var evalDir = Path.of("src/test/resources/eval");
        if (Files.exists(evalDir)) {
            try (var files = Files.list(evalDir)) {
                for (var file : files.sorted().toList()) {
                    if (file.toString().endsWith(".json")) {
                        try {
                            allCases.addAll(EvalRunner.loadCases(file));
                        } catch (Exception e) {
                            System.err.println("Failed to load " + file + ": " + e.getMessage());
                        }
                    }
                }
            }
        }

        report = runner.runSuite(allCases, "spark_sql");

        // Write report
        var reportFile = tmpDir.resolve("eval-report.json");
        report.writeTo(reportFile);
        System.out.println("[E2E Eval] Report written to: " + reportFile);
        System.out.println("[E2E Eval] Cases: " + report.totalCases()
                + " | Passed: " + report.passedCases()
                + " | Failed: " + report.failedCases()
                + " | Avg Score: " + String.format("%.2f", report.averageScore() * 100) + "%");
    }

    @Test
    void shouldLoadAllEvalCases() {
        assertThat(allCases).isNotEmpty();
        assertThat(allCases.size()).isBetween(30, 50);
    }

    @Test
    void shouldAchieveMinimumPassRate() {
        assertThat(report.passRate())
                .withFailMessage("Pass rate %.1f%% is below 30%% minimum",
                        report.passRate() * 100)
                .isGreaterThan(0.3);
    }

    @Test
    void shouldAchieveMinimumAverageScore() {
        assertThat(report.averageScore())
                .withFailMessage("Average score %.2f is below 0.4 minimum",
                        report.averageScore())
                .isGreaterThan(0.4);
    }

    @Test
    void shouldCoverAllSevenCategories() {
        assertThat(report.byCategory().keySet())
                .contains(EvalCategory.COVERAGE, EvalCategory.MOBILITY,
                        EvalCategory.ACCESSIBILITY, EvalCategory.RETAINABILITY,
                        EvalCategory.QOE, EvalCategory.MULTI_SOURCE,
                        EvalCategory.STREAM_BATCH, EvalCategory.REVERSE);
    }

    @Test
    void shouldHaveReportWithAllCases() {
        assertThat(report.results()).hasSameSizeAs(allCases);
        for (var r : report.results()) {
            assertThat(r.overallScore()).isBetween(0.0, 1.0);
        }
    }

    @Test
    void shouldReportPassRatePerCategory() {
        for (var entry : report.byCategory().entrySet()) {
            var catScore = entry.getValue();
            assertThat(catScore)
                    .withFailMessage("Category %s average score %.2f is below 0.2",
                            entry.getKey(), catScore)
                    .isGreaterThan(0.2);
        }
    }
}
```

- [ ] **Step 2: 运行 E2E 评估**

```bash
mvn test -Dtest=E2EEvalSetTest
```

期望: BUILD SUCCESS, 30-35 cases loaded, pass rate >= 30%, avg score >= 0.4.

- [ ] **Step 3: 运行全量测试确认无回归**

```bash
mvn test
```

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/wireless/agent/eval/E2EEvalSetTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m6): add parameterized E2E eval set test with report generation"
```

---

### Task 11: 回归门禁脚本 + Maven Profile

**Files:**
- Create: `D:/agent-code/data-agent/scripts/eval-regression-check.sh`
- Modify: `D:/agent-code/data-agent/pom.xml`

- [ ] **Step 1: 在 pom.xml 添加 eval profile**

在 `</dependencies>` 之后、`<build>` 之前添加：

```xml
    <profiles>
        <profile>
            <id>eval</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <version>3.5.2</version>
                        <configuration>
                            <includes>
                                <include>**/E2EEvalSetTest.java</include>
                            </includes>
                            <systemPropertyVariables>
                                <eval.report.dir>${project.build.directory}/eval-report</eval.report.dir>
                            </systemPropertyVariables>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
```

如果 pom.xml 中已有 `<build>` 段，则将 profile 放在 `</project>` 之前。

- [ ] **Step 2: 创建 eval-regression-check.sh**

```bash
#!/bin/bash
# M6 — E2E Eval Regression Check
# Compares current eval results against baseline, blocks if >= 5% regression.
set -euo pipefail

BASELINE_FILE="${BASELINE_FILE:-target/eval-report/baseline.json}"
CURRENT_FILE="${CURRENT_FILE:-target/eval-report/eval-report.json}"

echo "=== M6 E2E Eval Regression Check ==="

# Run eval suite
mvn test -P eval -q 2>&1 | tail -5

# Find the generated report
CURRENT=$(find target/eval-report -name "eval-report.json" -type f 2>/dev/null | head -1)
if [ -z "$CURRENT" ]; then
    echo "[FAIL] No eval report generated. Check E2EEvalSetTest output."
    exit 1
fi

echo "[INFO] Current report: $CURRENT"

if [ ! -f "$BASELINE_FILE" ]; then
    echo "[INFO] No baseline found at $BASELINE_FILE. Creating baseline from current run."
    cp "$CURRENT" "$BASELINE_FILE"
    echo "[PASS] Baseline created. No regression check on first run."
    exit 0
fi

echo "[INFO] Baseline: $BASELINE_FILE"

# Extract scores using grep/sed (lightweight alternative to jq)
CURRENT_SCORE=$(grep -o '"average_score" : [0-9.]*' "$CURRENT" | head -1 | awk '{print $3}')
BASELINE_SCORE=$(grep -o '"average_score" : [0-9.]*' "$BASELINE_FILE" | head -1 | awk '{print $3}')

if [ -z "$CURRENT_SCORE" ] || [ -z "$BASELINE_SCORE" ]; then
    echo "[FAIL] Could not extract scores from reports."
    exit 1
fi

# Calculate regression using awk
REGRESSION=$(awk -v curr="$CURRENT_SCORE" -v base="$BASELINE_SCORE" 'BEGIN { printf "%.4f", (base - curr) / base }')

echo "  Baseline score: $BASELINE_SCORE"
echo "  Current score:  $CURRENT_SCORE"
echo "  Regression:     $(awk -v r="$REGRESSION" 'BEGIN { printf "%.2f%%\n", r * 100 }')"

# Block if regression >= 5%
if awk -v r="$REGRESSION" 'BEGIN { exit (r >= 0.05) ? 1 : 0 }'; then
    echo "[PASS] Regression within acceptable range (< 5%)."
else
    echo "[FAIL] Regression >= 5%! Blocking merge."
    echo "  Review failing cases and fix regressions before merging."
    exit 1
fi
```

- [ ] **Step 3: 使脚本可执行**

```bash
chmod +x scripts/eval-regression-check.sh
```

- [ ] **Step 4: 验证 Maven profile 可运行**

```bash
mvn test -P eval -Dtest=E2EEvalSetTest 2>&1 | tail -10
```

期望: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add pom.xml scripts/eval-regression-check.sh
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m6): add eval Maven profile and regression gate script (5% threshold)"
```

---

### Task 12: README 更新与终验

**Files:**
- Modify: `D:/agent-code/data-agent/README.md`

- [ ] **Step 1: 更新 README.md 追加 M6 章节**

在 M5 段落后追加：

```markdown
## M6 -- Quality Baseline (E2E Eval Set)

**评估体系:** 30-35 个无线评估典型任务样本作为回归基线，多维度自动评分。

| Component | Description |
|-----------|-------------|
| EvalCase | 评估案例数据模型: NL输入 → 期望Spec → 期望代码模式 |
| EvalRunner | 评估引擎: 加载案例 → 驱动AgentCore → 评分 |
| EvalResult | 四维评分: Spec准确率 / 代码可编译 / Dry-run通过 / 轮次收敛 |
| EvalReport | 聚合报告: 通过率、均分、按类别分组 |
| Regression Gate | CI门禁: 分数跌幅 ≥ 5% 阻塞合并 |

**评估类别:** coverage(5) / mobility(5) / accessibility+retainability(5) / qoe(5) / multi-source(5) / stream-batch(5) / reverse(5)

**Usage:**

```bash
# 运行全部 E2E 评估集
mvn test -P eval

# 运行评估 + 回归检查
bash scripts/eval-regression-check.sh

# 查看评估报告
cat target/eval-report/eval-report.json
```

**评分维度:**

| 维度 | 权重 | 说明 |
|------|------|------|
| Spec准确率 | 25% | target_name/kpi_family/ne_grain/time_grain/rat/sources 匹配 |
| 代码可编译 | 25% | 生成代码非空且含期望模式 |
| Dry-run通过 | 25% | 沙箱执行成功 (must_dry_run=false时跳过) |
| 轮次收敛 | 25% | turns_used / max_turns 归一化 |

**回归门禁:**

```bash
# CI pipeline 中调用:
bash scripts/eval-regression-check.sh
# 首次运行创建baseline，后续运行对比 >=5% 跌幅则阻塞
```
```

- [ ] **Step 2: 最终验证**

```bash
mvn test
mvn test -P eval
```

期望: 全量测试 + E2E 评估均 BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add README.md
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "docs(m6): add quality baseline eval set quickstart to readme"
```

---

## M6 完成标准（DoD）

- [ ] `mvn test` 全部测试通过（含 E2EEvalSetTest）
- [ ] `mvn test -P eval` E2E 评估跑通
- [ ] EvalCase 支持 Jackson 序列化/反序列化
- [ ] EvalCategory 覆盖全部 8 个类别
- [ ] EvalResult 四维评分: spec(25%) + code(25%) + dryRun(25%) + turn(25%)
- [ ] EvalReport 聚合: 通过率、均分、按类别分组、JSON 序列化
- [ ] EvalRunner 驱动 AgentCore 无 LLM 模式完成评估
- [ ] FakeLLMForEval 支持可控 LLM 响应序列
- [ ] 35 个评估案例覆盖 7 个域内类别
- [ ] E2EEvalSetTest 参数化加载全部案例并通过最低门禁 (pass ≥ 30%, avg score ≥ 0.4)
- [ ] 回归门禁脚本: 分数对比基线，≥ 5% 跌幅阻塞
- [ ] Maven eval profile 配置完成

---

## 后续

M6 完成后，v1 所有里程碑 (M0a → M6) 全部交付。后续迭代方向：
- **v1.1:** 补充更多评估案例（50+），提升覆盖率
- **v2:** 安全合规（PII 脱敏、数据安全等级）
- **CI 集成:** 将 eval-regression-check.sh 接入 GitHub Actions / Jenkins
