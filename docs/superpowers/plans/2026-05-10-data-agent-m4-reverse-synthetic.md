# M4 反向合成 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 REVERSE_SYNTHETIC 反向合成管线：用户粘贴原始流水线代码 → Agent 解析输入约束 → 反问数据规模/异常比例 → 生成数据生产代码 → 双重 dry-run 验证。

**Architecture:** CodegenTool 新增 `REVERSE_SYNTHETIC` 分支（独立 system prompt + 硬编码 fallback）；SandboxTool 新增 `dualDryRun()` 两步验证（生成数据 → 喂入原始流水线）；AgentCore 的 `executeTools()` 按 taskDirection 分叉两条路径；Spec 新增 `originalPipeline` 字段存储用户粘贴的流水线代码；Prompts 新增反向提取和反问提示词。

**Tech Stack:** Java 17, Maven, Docker CLI, JUnit 5, AssertJ

---

## 文件结构（M4 完成后的新增/变更）

```
data-agent/
├── src/main/resources/
│   └── agent.properties                              # 不变
├── src/main/java/com/wireless/agent/
│   ├── core/
│   │   ├── Spec.java                                 # 修改: +originalPipeline 字段
│   │   ├── Prompts.java                              # 修改: +反向系统提示词
│   │   └── AgentCore.java                            # 修改: executeTools 反分支
│   ├── tools/
│   │   ├── CodegenTool.java                          # 修改: 反向 codegen 分支
│   │   ├── SandboxTool.java                          # 修改: +dualDryRun()
│   │   └── ValidatorTool.java                        # 修改: +schema 兼容性检查
│   └── Main.java                                     # 修改: +--reverse 标志
└── src/test/java/com/wireless/agent/
    ├── core/
    │   ├── SpecTest.java                             # 修改: +originalPipeline 序列化测试
    │   └── PromptsTest.java                          # 新增: 反向提示词测试
    ├── tools/
    │   ├── CodegenToolTest.java                      # 修改: +反向 codegen 测试
    │   ├── SandboxToolTest.java                      # 修改: +dualDryRun 测试
    │   └── ValidatorToolTest.java                    # 修改: +schema 兼容性测试
    └── IntegrationTest.java                          # 修改: +反向合成端到端
```

---

### Task 1: Spec 模型扩展 — originalPipeline 字段

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/core/Spec.java`
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/core/SpecTest.java`

- [ ] **Step 1: 在 SpecTest.java 追加测试**

在现有 SpecTest 文件中追加以下测试方法：

```java
@Test
void shouldSerializeOriginalPipeline() throws Exception {
    var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
    spec.originalPipeline("""
            INSERT INTO output_kpi
            SELECT cell_id, COUNT(*) AS failure_count
            FROM signaling_events
            WHERE event_type = 'handover'
            GROUP BY cell_id;""");

    var json = MAPPER.writeValueAsString(spec);
    assertThat(json).contains("original_pipeline");
    assertThat(json).contains("signaling_events");
}

@Test
void shouldDefaultOriginalPipelineToNull() {
    var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
    assertThat(spec.originalPipeline()).isNull();
}

@Test
void shouldReturnNullWhenPipelineNotSet() {
    var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
    assertThat(spec.originalPipeline()).isNull();
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=SpecTest
```

期望: 3 个新测试编译失败（`originalPipeline` 方法尚未定义）。

- [ ] **Step 3: 在 Spec.java 添加 originalPipeline 字段**

在 `Spec.java` 的 Spec fields 区域（`evidence` 字段之后）添加：

```java
@JsonProperty("original_pipeline") private String originalPipeline;
```

在 getter/setter 区域（`validateThroughTargetPipeline()` 之后）添加：

```java
public String originalPipeline() { return originalPipeline; }
public Spec originalPipeline(String v) { originalPipeline = v; return this; }
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -Dtest=SpecTest
```

期望: 所有测试通过（原有 + 新增 3）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/core/Spec.java src/test/java/com/wireless/agent/core/SpecTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m4): add originalPipeline field to Spec for reverse synthetic tasks"
```

---

### Task 2: Prompts — 反向合成系统提示词

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/core/Prompts.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/core/PromptsTest.java`

- [ ] **Step 1: 创建 PromptsTest.java**

```java
package com.wireless.agent.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptsTest {

    @Test
    void shouldBuildReverseExtractPromptWithPipelineCode() {
        var prompt = Prompts.buildReverseExtractPrompt(
                "INSERT INTO t SELECT * FROM src WHERE event='ho'",
                "{}");

        assertThat(prompt).contains("原始流水线");
        assertThat(prompt).contains("event='ho'");
        assertThat(prompt).contains("推断输入约束");
    }

    @Test
    void shouldBuildReverseClarifyPrompt() {
        var prompt = Prompts.buildReverseClarifyPrompt("handover_failure",
                List.of("data_scale", "anomaly_ratio"));

        assertThat(prompt).contains("数据规模");
        assertThat(prompt).contains("异常比例");
        assertThat(prompt).contains("handover_failure");
    }

    @Test
    void shouldHaveReverseSystemPrompt() {
        var prompt = Prompts.REVERSE_SYNTHETIC_SYSTEM_PROMPT;
        assertThat(prompt).contains("反向合成");
        assertThat(prompt).contains("数据生产");
        assertThat(prompt).contains("分布");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=PromptsTest
```

期望: 编译失败（方法不存在）。

- [ ] **Step 3: 在 Prompts.java 追加反向提示词**

在 `buildClarifyPrompt` 方法之后追加：

```java
public static final String REVERSE_SYNTHETIC_SYSTEM_PROMPT = """
        你是无线网络感知评估 Data Agent 的反向合成模块。
        用户会粘贴一段原始流水线 SQL/Java 代码,你的职责:
        1. 解析原始流水线,提取输入表结构、过滤条件、JOIN 关系、聚合逻辑
        2. 推断需要生产的目标数据约束: 表名、列名、列类型、基数、null率、枚举值
        3. 针对缺失的约束生成精准反问: 数据规模(行数)、异常比例、是否需要学习生产分布
        4. 在约束收敛后,生成数据生产代码(SQL INSERT 或 Java Flink DataStream)

        关键概念:
        - 数据规模: 单元测试级(1k-10k行) / 回归测试级(100k-1M行) / 压力测试级(10M-100M行)
        - 异常比例: 早切5%+晚切5%、掉话1%、弱覆盖30%等
        - 学分布: 从原始流水线的输入表采样,学习列值分布后按分布生成

        输出格式: 你的每轮回复必须是 JSON:
        {
          "intent_update": {
            "target_name": null,
            "business_definition": null,
            "pipeline_inputs": [],
            "data_scale": null,
            "anomaly_ratio": null,
            "learn_distribution": false,
            "open_questions": []
          },
          "next_action": "ask_clarifying|ready_to_codegen",
          "clarifying_question": null
        }
        """;

/** 反向合成的提取提示词: 解析用户粘贴的原始流水线代码 */
public static String buildReverseExtractPrompt(String pipelineCode, String currentSpecJson) {
    return String.format("""
            当前 Spec 状态: %s

            用户粘贴的原始流水线代码:
            ```
            %s
            ```

            请解析此流水线:
            1. 提取所有输入表/主题名
            2. 提取 WHERE 条件(作为数据生成约束)
            3. 提取 GROUP BY 列(作为数据分布约束)
            4. 提取 JOIN 键(跨表数据一致性约束)
            5. 如果代码中信息不足,生成反问(数据规模?异常比例?要不要学分布?)
            """,
            currentSpecJson, pipelineCode);
}

/** 反向合成的反问提示词: 针对数据生成规格的缺口 */
public static String buildReverseClarifyPrompt(String targetName, List<String> gaps) {
    var gapList = String.join(", ", gaps);
    return String.format("""
            目标数据集: %s
            以下信息仍需确认: %s

            请生成一句清晰的中文反问,帮助用户明确:
            - 数据规模(默认: 单元测试 1 万行)
            - 异常比例(默认: 5%%)
            - 是否需要从生产表学习列值分布(默认: 否)
            只问一个最关键的问题。
            """,
            targetName, gapList);
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -Dtest=PromptsTest
```

期望: 3 passed。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/core/Prompts.java src/test/java/com/wireless/agent/core/PromptsTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m4): add reverse synthetic system prompts for pipeline parsing and clarifying"
```

---

### Task 3: CodegenTool — 反向合成 codegen 分支

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/CodegenTool.java`
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/tools/CodegenToolTest.java`

- [ ] **Step 1: 在 CodegenToolTest.java 追加测试**

```java
@Test
void shouldGenerateReverseSyntheticPrompt() {
    var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
    spec.originalPipeline("""
            INSERT INTO handover_kpi
            SELECT cell_id, COUNT(*) AS failure_count
            FROM signaling_events
            WHERE event_type = 'handover' AND result = 'failure'
            GROUP BY cell_id;""");
    spec.target(new Spec.TargetSpec()
            .name("handover_kpi_data_gen")
            .businessDefinition("生成切换失败 KPI 测试数据")
            .grain("(cell_id, hour)"));
    spec.sources(List.of(
            new Spec.SourceBinding()
                    .role("stream")
                    .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))
    ));
    spec.engineDecision(new Spec.EngineDecision("java_flink_streamapi",
            "反向合成数据生产 → Java Flink Stream API"));

    var prompt = CodegenTool.buildCodegenPrompt(spec);
    assertThat(prompt).contains("REVERSE_SYNTHETIC");
    assertThat(prompt).contains("数据生产");
    assertThat(prompt).contains("signaling_events");
    assertThat(prompt).contains("handover");
}

@Test
void shouldFallbackToReverseSyntheticCodeWhenNoLlm() {
    var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
    spec.originalPipeline("INSERT INTO handover_kpi SELECT cell_id, COUNT(*) FROM signaling_events WHERE event_type='handover' GROUP BY cell_id;");
    spec.target(new Spec.TargetSpec()
            .name("handover_data_gen")
            .businessDefinition("生成切换失败测试数据"));
    spec.sources(List.of(
            new Spec.SourceBinding()
                    .role("stream")
                    .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))
    ));
    spec.engineDecision(new Spec.EngineDecision("java_flink_streamapi",
            "反向合成 → Java"));

    var tool = new CodegenTool(null);
    var result = tool.run(spec);
    assertThat(result.success()).isTrue();
    var code = result.data().toString();
    assertThat(code).contains("DataGenerator");
    assertThat(code).contains("synthetic");
}

@Test
void shouldSelectReversePromptWhenTaskDirectionIsReverse() {
    var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
    spec.engineDecision(new Spec.EngineDecision("flink_sql", "反向合成 Flink SQL"));
    // Even with flink_sql engine, REVERSE_SYNTHETIC direction should trigger reverse prompt
    var prompt = CodegenTool.buildCodegenPrompt(spec);
    assertThat(prompt).contains("REVERSE_SYNTHETIC");
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=CodegenToolTest
```

期望: 3 个新测试 FAIL（反向逻辑尚未实现）。

- [ ] **Step 3: 修改 CodegenTool.java**

**(a) 新增 `REVERSE_SYNTHETIC_SYSTEM_PROMPT` 常量（在 `JAVA_FLINK_SYSTEM_PROMPT` 之后）：**

```java
public static final String REVERSE_SYNTHETIC_SYSTEM_PROMPT = """
        You are a synthetic data generator for wireless network perception testing.

        Rules:
        1. Output ONLY the data generation code, no explanation before or after.
        2. Generate code that produces synthetic data matching the input schema constraints.
        3. For Flink SQL: use INSERT INTO with cross-joined value lists (UNION ALL).
        4. For Java Flink: use DataStream<String> with random value generation, KeyedProcessFunction for stateful patterns.
        5. Include configurable data scale: NUM_ROWS constant at the top.
        6. Include configurable anomaly ratio: ANOMALY_RATIO constant controlling edge-case injection.
        7. Match the column types and semantics extracted from the original pipeline.
        8. Wrap the final code in ```sql or ```java code block matching the target engine.
        """;
```

**(b) 修改 `selectSystemPrompt` 方法，在 switch 之前先检查 taskDirection：**

```java
private static String selectSystemPrompt(Spec spec) {
    if (spec.taskDirection() == Spec.TaskDirection.REVERSE_SYNTHETIC) {
        return REVERSE_SYNTHETIC_SYSTEM_PROMPT;
    }
    var engine = spec.engineDecision();
    if (engine == null) return CODGEN_SYSTEM_PROMPT;
    return switch (engine.recommended()) {
        case "flink_sql" -> FLINK_SQL_SYSTEM_PROMPT;
        case "java_flink_streamapi" -> JAVA_FLINK_SYSTEM_PROMPT;
        default -> CODGEN_SYSTEM_PROMPT;
    };
}
```

**(c) 修改 `buildCodegenPrompt` 方法，在方法开头添加反向分支：**

在 `var target = spec.target();` 之前插入：

```java
if (spec.taskDirection() == Spec.TaskDirection.REVERSE_SYNTHETIC) {
    return buildReverseCodegenPrompt(spec);
}
```

**(d) 新增 `buildReverseCodegenPrompt` 方法（在 `buildCodegenPrompt` 之后）：**

```java
private static String buildReverseCodegenPrompt(Spec spec) {
    var target = spec.target();
    var engine = spec.engineDecision();
    var pipelineCode = spec.originalPipeline() != null ? spec.originalPipeline() : "(no pipeline code provided)";

    var schemaLines = new ArrayList<String>();
    for (var src : spec.sources()) {
        var tbl = src.binding().getOrDefault("table_or_topic", "unknown").toString();
        schemaLines.add("- " + src.role() + ": " + tbl);
        if (src.schema_() != null) {
            for (var col : src.schema_()) {
                schemaLines.add("    " + col.get("name") + " (" + col.get("type")
                        + "): " + col.getOrDefault("semantic", ""));
            }
        }
    }
    var schemaBlock = schemaLines.isEmpty()
            ? "(infer from pipeline code)"
            : String.join("\n", schemaLines);

    return String.format("""
            Generate DATA PRODUCTION code (REVERSE_SYNTHETIC) for wireless network test data:

            Original pipeline that needs test data:
            ```
            %s
            ```

            Target test dataset: %s
            Purpose: %s
            Input schema (data to produce):
            %s

            Engine: %s (rationale: %s)

            Requirements:
            - Generate INSERT/SOURCE code that populates test data matching the input schema
            - Default data scale: 10,000 rows (configurable via NUM_ROWS constant)
            - Default anomaly ratio: 5%% (configurable via ANOMALY_RATIO constant)
            - Column values should be realistic wireless network data (cell_id, timestamps, signal metrics)
            - Output grain: %s
            """,
            pipelineCode,
            target != null ? target.name() : "(unspecified)",
            target != null ? target.businessDefinition() : "test data generation",
            schemaBlock,
            engine != null ? engine.recommended() : "java_flink_streamapi",
            engine != null ? engine.reasoning() : "反向合成默认 Java Flink",
            target != null ? target.grain() : "cell × hour"
    );
}
```

**(e) 修改 `hardcodedCode` 方法，在 null-engine 分支前加反向检查：**

```java
static String hardcodedCode(Spec spec) {
    if (spec.taskDirection() == Spec.TaskDirection.REVERSE_SYNTHETIC) {
        return hardcodedReverseSynthetic(spec);
    }
    var engine = spec.engineDecision();
    if (engine == null) return hardcodedSparkSql(spec);
    return switch (engine.recommended()) {
        case "flink_sql" -> hardcodedFlinkSql(spec);
        case "java_flink_streamapi" -> hardcodedJavaFlink(spec);
        default -> hardcodedSparkSql(spec);
    };
}
```

**(f) 新增 `hardcodedReverseSynthetic` 方法（在 `hardcodedJavaFlink` 之后）：**

```java
/** Fallback hardcoded data generation code for reverse synthetic tasks. */
static String hardcodedReverseSynthetic(Spec spec) {
    var target = spec.target();
    var def = target != null ? target.businessDefinition() : "";
    var pipeline = spec.originalPipeline() != null ? spec.originalPipeline() : "";

    if (!pipeline.isEmpty() && (pipeline.contains("handover") || pipeline.contains("切换")
            || def.contains("切换") || def.contains("handover"))) {
        return """
                ```java
                // 切换事件测试数据生成器 (Java Flink Stream API)
                import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
                import org.apache.flink.streaming.api.datastream.DataStream;
                import java.util.Random;

                public class HandoverDataGenerator {
                    private static final int NUM_ROWS = 10_000;
                    private static final double ANOMALY_RATIO = 0.05;
                    private static final String[] CELL_IDS = {"cell_A", "cell_B", "cell_C", "cell_D"};
                    private static final Random RNG = new Random(42);

                    public static void main(String[] args) throws Exception {
                        StreamExecutionEnvironment env =
                            StreamExecutionEnvironment.getExecutionEnvironment();

                        DataStream<String> synthetic = env
                            .fromSequence(0, NUM_ROWS - 1)
                            .map(i -> {
                                String cellId = CELL_IDS[RNG.nextInt(CELL_IDS.length)];
                                String eventType = RNG.nextDouble() < ANOMALY_RATIO
                                    ? "handover" : "other";
                                String result = RNG.nextDouble() < 0.1 ? "failure" : "success";
                                long ts = System.currentTimeMillis() - RNG.nextInt(3600_000);
                                return String.format("%%s|%%s|%%s|%%d", cellId, eventType, result, ts);
                            });

                        synthetic.print();
                        env.execute("Handover Data Generator");
                    }
                }
                ```""";
    }
    return String.format("""
            ```java
            // %s — 反向合成数据生成 (Java Flink Stream API)
            import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
            import org.apache.flink.streaming.api.datastream.DataStream;
            import java.util.Random;

            public class SyntheticDataGenerator {
                private static final int NUM_ROWS = 10_000;
                private static final double ANOMALY_RATIO = 0.05;

                public static void main(String[] args) throws Exception {
                    StreamExecutionEnvironment env =
                        StreamExecutionEnvironment.getExecutionEnvironment();
                    DataStream<String> synthetic = env
                        .fromSequence(0, NUM_ROWS - 1)
                        .map(i -> String.format("synthetic_event_%%d", i));
                    synthetic.print();
                    env.execute("Synthetic Data Generator");
                }
            }
            ```""",
            target != null ? target.name() : "SyntheticDataGenerator");
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -Dtest=CodegenToolTest
```

期望: 11 passed（原有 8 + 新增 3）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/tools/CodegenTool.java src/test/java/com/wireless/agent/tools/CodegenToolTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m4): codegen tool reverse synthetic branch with data generation prompt and fallback"
```

---

### Task 4: SandboxTool — 双重 dry-run

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/SandboxTool.java`
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/tools/SandboxToolTest.java`

- [ ] **Step 1: 在 SandboxToolTest.java 追加测试**

```java
@Test
void shouldSupportDualDryRun() {
    var runner = new DockerCommandRunner();
    var sandbox = new SandboxTool(runner, "da-spark-master", "da-flink-jobmanager");

    var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
    spec.originalPipeline("SELECT cell_id, COUNT(*) FROM signaling_events WHERE event_type='handover' GROUP BY cell_id;");
    spec.engineDecision(new Spec.EngineDecision("flink_sql", "反向合成"));

    var generatedCode = """
            ```sql
            INSERT INTO signaling_events VALUES
            ('cell_A', 'handover', 'failure', 1000),
            ('cell_B', 'handover', 'success', 2000);
            ```""";

    var result = sandbox.dualDryRun(generatedCode, spec);
    // Without Docker, both steps should fail gracefully
    assertThat(result).isNotNull();
    assertThat(result).containsKey("step1_result");
    assertThat(result).containsKey("step2_result");
}

@Test
void shouldStep1GenerateDataAndStep2Validate() {
    var runner = new DockerCommandRunner();
    var sandbox = new SandboxTool(runner, "da-spark-master", "da-flink-jobmanager");

    var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
    spec.originalPipeline("SELECT * FROM handover_output;");
    spec.engineDecision(new Spec.EngineDecision("spark_sql", "反向合成 Spark SQL"));

    var generatedCode = "```sql\nINSERT INTO handover_output SELECT 'cell_A', 10;\n```";

    var result = sandbox.dualDryRun(generatedCode, spec);
    assertThat(result).containsKey("step1_result");
    assertThat(result).containsKey("step2_result");
    // Step 1 should extract and execute SQL
    // Step 2 should try running original pipeline on generated output
}

@Test
void shouldBuildJavaExecutionCommandForFlink() {
    var runner = new DockerCommandRunner();
    var sandbox = new SandboxTool(runner, "da-spark-master", "da-flink-jobmanager");

    var cmd = sandbox.buildJavaExecutionCommand("da-flink-jobmanager", "SyntheticDataGenerator.java");
    assertThat(cmd).contains("flink");
    assertThat(cmd).contains("run");
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=SandboxToolTest
```

期望: 3 个新测试 FAIL（方法不存在）。

- [ ] **Step 3: 修改 SandboxTool.java**

**(a) 新增 `dualDryRun` 方法（在 `dryRun` 方法之后）：**

```java
/** Dual dry-run for reverse synthetic: Step 1 run generated code, Step 2 verify against original pipeline. */
public Map<String, Object> dualDryRun(String generatedCode, Spec spec) {
    Objects.requireNonNull(generatedCode, "generatedCode must not be null");
    Objects.requireNonNull(spec, "spec must not be null");

    var result = new LinkedHashMap<String, Object>();

    // ── Step 1: Run the generated data production code ──
    var genSql = extractSql(generatedCode);
    if (genSql.isEmpty()) {
        // Try extracting Java code for Flink jobs
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

    // ── Step 2: Feed generated data into original pipeline for validation ──
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
```

**(b) 新增 `extractJavaCode` 和 `buildJavaExecutionCommand` 方法：**

```java
private static final Pattern JAVA_BLOCK = Pattern.compile(
        "```java\\s*\\n?(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

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
    // For now, echo the code to a file and flink run it
    return List.of("bash", "-c",
            "echo '" + javaCode.replace("'", "'\\''")
            + "' | /opt/flink/bin/sql-client.sh");
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -Dtest=SandboxToolTest
```

期望: 14 passed（原有 11 + 新增 3）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/tools/SandboxTool.java src/test/java/com/wireless/agent/tools/SandboxToolTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m4): sandbox dual dry-run for reverse synthetic data generation and validation"
```

---

### Task 5: ValidatorTool — Schema 兼容性检查

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/ValidatorTool.java`
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/tools/ValidatorToolTest.java`

- [ ] **Step 1: 在 ValidatorToolTest.java 追加测试**

```java
@Test
void shouldValidateReverseSyntheticSchemaCompatibility() {
    var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
    spec.originalPipeline("""
            SELECT cell_id, COUNT(*) AS failure_count
            FROM signaling_events
            WHERE event_type = 'handover'
            GROUP BY cell_id;""");
    spec.sources(List.of(
            new Spec.SourceBinding()
                    .role("stream")
                    .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))
    ));
    spec.engineDecision(new Spec.EngineDecision("flink_sql", "反向合成"));

    var generatedCode = """
            ```sql
            INSERT INTO signaling_events (cell_id, event_type, result, ts)
            VALUES ('cell_A', 'handover', 'failure', 1000);
            ```""";

    var tool = new ValidatorTool();
    var result = tool.validate(generatedCode, spec);
    assertThat(result.success()).isTrue();
    // Should recognize INSERT INTO target table
}

@Test
void shouldCheckSchemaCompatibilityForReverseSynthetic() {
    var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
    spec.originalPipeline("SELECT cell_id, COUNT(*) FROM signaling_events WHERE event_type='handover' GROUP BY cell_id;");
    spec.sources(List.of(
            new Spec.SourceBinding()
                    .role("stream")
                    .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))
    ));

    var generatedCode = """
            ```sql
            INSERT INTO signaling_events VALUES ('cell_A', 'handover', 'failure', 1000);
            ```""";

    var tool = new ValidatorTool();
    var result = tool.validate(generatedCode, spec);
    // The generated SQL inserts into signaling_events which matches spec source
    var warnings = ((List<?>) result.data().get("warnings"));
    assertThat(warnings.stream().noneMatch(w -> w.toString().contains("not in spec"))).isTrue();
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=ValidatorToolTest
```

期望: 新增测试 FAIL（反向逻辑未实现）。

- [ ] **Step 3: 修改 ValidatorTool.java**

在 `validate` 方法的 `// 5. Check table references against spec sources` 部分之前，添加反向合成 INSERT INTO 检查：

在 `var knownTables = new ArrayList<String>();` 这行之前插入：

```java
// For reverse synthetic: also validate INSERT INTO target matches original pipeline source
if (spec.taskDirection() == Spec.TaskDirection.REVERSE_SYNTHETIC
        && spec.originalPipeline() != null && !spec.originalPipeline().isEmpty()) {
    var insertPattern = Pattern.compile(
            "\\bINSERT\\s+INTO\\s+([a-zA-Z_][a-zA-Z0-9_.]*)", Pattern.CASE_INSENSITIVE);
    var insertMatcher = insertPattern.matcher(sql);
    var insertTargets = new ArrayList<String>();
    while (insertMatcher.find()) {
        insertTargets.add(insertMatcher.group(1).toLowerCase());
    }
    if (!insertTargets.isEmpty()) {
        // Check inserted table is one of the spec sources (the pipeline input)
        var pipelineInputs = new HashSet<String>();
        for (var src : spec.sources()) {
            var tbl = src.binding().getOrDefault("table_or_topic", "").toString();
            if (!tbl.isEmpty()) pipelineInputs.add(tbl.toLowerCase());
        }
        for (var it : insertTargets) {
            var found = pipelineInputs.stream().anyMatch(t ->
                t.equals(it) || it.endsWith("." + t) || t.endsWith("." + it));
            if (!found && !it.equals("values")) {
                warnings.add("INSERT target " + it
                        + " does not match any source in original pipeline: " + pipelineInputs);
            }
        }
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -Dtest=ValidatorToolTest
```

期望: 7 passed（原有 5 + 新增 2）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/tools/ValidatorTool.java src/test/java/com/wireless/agent/tools/ValidatorToolTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m4): validator adds insert-target schema compatibility check for reverse synthetic"
```

---

### Task 6: AgentCore — executeTools 反向分支

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/core/AgentCore.java`
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/Main.java`

- [ ] **Step 1: 修改 Main.java 支持 --reverse 标志**

在 `Main.java` 的 `main()` 方法中追加 `--reverse` 标志解析：

```java
var reverse = false;
for (var arg : args) {
    if ("--demo".equals(arg)) demo = true;
    if ("--no-llm".equals(arg)) noLlm = true;
    if ("--reverse".equals(arg)) reverse = true;
}

var taskDirection = reverse ? Spec.TaskDirection.REVERSE_SYNTHETIC : Spec.TaskDirection.FORWARD_ETL;
```

将 `runDemo` 和 `runInteractive` 调用中的 `Spec.TaskDirection.FORWARD_ETL` 替换为 `taskDirection`：

```java
if (demo) {
    runDemo(llmClient, hmsUri, sparkContainer, flinkContainer, taskDirection);
} else {
    runInteractive(llmClient, hmsUri, sparkContainer, flinkContainer, taskDirection);
}
```

更新 `runDemo` 和 `runInteractive` 方法签名，接受 `taskDirection` 参数：

```java
private static void runDemo(DeepSeekClient llmClient, String hmsUri, String sparkContainer,
        String flinkContainer, Spec.TaskDirection taskDirection) {
    // ...
    var agent = new AgentCore(llmClient, taskDirection, hmsUri, sparkContainer,
            flinkContainer, new DomainKnowledgeBase());
}

private static void runInteractive(DeepSeekClient llmClient, String hmsUri, String sparkContainer,
        String flinkContainer, Spec.TaskDirection taskDirection) {
    // ...
    var agent = new AgentCore(llmClient, taskDirection, hmsUri, sparkContainer,
            flinkContainer, new DomainKnowledgeBase());
}
```

- [ ] **Step 2: 修改 AgentCore.java — mockExtract 反向分支**

在 `mockExtract` 方法末尾（`return result;` 之前），添加反向检测。在判断 `kpiFamily` 的逻辑之后，检查是否为 REVERSE_SYNTHETIC 方向：

```java
// In mockExtract, after setting kpiFamily:
// For REVERSE_SYNTHETIC, check if user provided pipeline code
if (spec.taskDirection() == Spec.TaskDirection.REVERSE_SYNTHETIC && msg.length() > 50) {
    // Check if the message looks like pipeline code (contains SQL keywords)
    if (msg.contains("SELECT") || msg.contains("INSERT") || msg.contains("FROM")) {
        spec.originalPipeline(userMessage.trim());
        // Remove the kpiFamily override — reverse synthetic doesn't need it
    }
}
```

Wait — `mockExtract` doesn't have access to `spec`. Let me re-read the code... Actually, `mockExtract` is a method on AgentCore which has `this.spec`. So we can do:

```java
private Map<String, Object> mockExtract(String userMessage) {
    var msg = userMessage.toLowerCase();
    var kpiFamily = "coverage";
    if (containsAny(msg, "切换", "handover", "mobility")) kpiFamily = "mobility";
    else if (containsAny(msg, "掉话", "drop", "retain")) kpiFamily = "retainability";
    else if (containsAny(msg, "接入", "rrc", "access")) kpiFamily = "accessibility";
    else if (containsAny(msg, "感知", "qoe", "视频", "tcp")) kpiFamily = "qoe";

    // For REVERSE_SYNTHETIC: detect if user is pasting pipeline code
    var isReverseSynthetic = spec.taskDirection() == Spec.TaskDirection.REVERSE_SYNTHETIC;
    var bizDef = userMessage;
    var identifiedSources = List.of("dw.mr_5g_15min", "dim.engineering_param");

    if (isReverseSynthetic && containsAny(msg, "select", "insert", "from", "where", "group by")) {
        // User is pasting pipeline code, not describing KPI
        bizDef = "反向合成测试数据生成";
        kpiFamily = "synthetic";
        // Store the pasted code
        spec.originalPipeline(userMessage.trim());
        identifiedSources = List.of("signaling_events");
    } else if (isReverseSynthetic) {
        bizDef = userMessage;
        identifiedSources = List.of("signaling_events");
    }

    var result = new LinkedHashMap<String, Object>();
    result.put("intent_update", Map.of(
        "target_name", isReverseSynthetic ? "synthetic_test_data" : "弱覆盖小区统计",
        "business_definition", bizDef,
        "kpi_family", kpiFamily,
        "ne_grain", "district",
        "time_grain", "day",
        "rat", "5G_SA",
        "timeliness", "batch_daily",
        "identified_sources", identifiedSources,
        "open_questions", List.of()
    ));
    result.put("next_action", "ready_for_tools");
    result.put("clarifying_question", null);
    return result;
}
```

- [ ] **Step 3: 修改 AgentCore.java — executeTools 反向分支**

在 `executeTools()` 方法的末尾（sandbox dry-run 之后），替换 `sandboxTool.dryRun(code, spec)` 调用。找到这段代码：

```java
// 6. Sandbox dry-run
var dryRunResult = sandboxTool.dryRun(code, spec);
```

替换为按方向分发的版本：

```java
// 6. Sandbox dry-run
Map<String, Object> dryRunResult;
if (spec.taskDirection() == Spec.TaskDirection.REVERSE_SYNTHETIC) {
    dryRunResult = sandboxTool.dualDryRun(code, spec);
} else {
    dryRunResult = sandboxTool.dryRun(code, spec);
}
```

- [ ] **Step 4: 运行全部测试**

```bash
mvn test
```

期望: 全部通过，无回归。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/core/AgentCore.java src/main/java/com/wireless/agent/Main.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m4): agent core reverse synthetic branch with dual dry-run and pipeline detection"
```

---

### Task 7: 集成测试 — 反向合成端到端

**Files:**
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/IntegrationTest.java`

- [ ] **Step 1: 在 IntegrationTest.java 追加反向合成测试**

```java
@Test
void shouldProcessReverseSyntheticTaskWithPastedPipeline() {
    var agent = new AgentCore(null, Spec.TaskDirection.REVERSE_SYNTHETIC,
            "thrift://nonexistent:9999", "da-spark-master", "da-flink-jobmanager",
            new DomainKnowledgeBase());

    var result = agent.processMessage(
            "INSERT INTO handover_kpi " +
            "SELECT cell_id, COUNT(*) AS failure_count " +
            "FROM signaling_events " +
            "WHERE event_type = 'handover' AND result = 'failure' " +
            "GROUP BY cell_id;");

    assertThat(result.get("next_action"))
            .isIn("code_done", "dry_run_ok", "sandbox_failed");
    var code = result.get("code").toString();
    assertThat(code).isNotEmpty();
    // Reverse synthetic should generate data production code (INSERT or Java generator)
    assertThat(code.toLowerCase()).containsAnyOf("insert", "generator", "synthetic");
}

@Test
void shouldStoreOriginalPipelineInSpec() {
    var agent = new AgentCore(null, Spec.TaskDirection.REVERSE_SYNTHETIC,
            "thrift://nonexistent:9999", "da-spark-master", "da-flink-jobmanager",
            new DomainKnowledgeBase());

    var pipelineCode = "SELECT * FROM signaling_events WHERE event_type='handover';";
    agent.processMessage(pipelineCode);

    assertThat(agent.spec().originalPipeline()).isEqualTo(pipelineCode);
}

@Test
void shouldVerifyReverseEngineSelection() {
    // REVERSE_SYNTHETIC with Kafka source → java_flink_streamapi
    var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
    spec.sources(List.of(new Spec.SourceBinding().role("stream")
            .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))));
    spec.target(new Spec.TargetSpec().name("test").businessDefinition("生成测试数据"));

    var decision = EngineSelector.select(spec);
    assertThat(decision.recommended()).isEqualTo("java_flink_streamapi");
    assertThat(decision.reasoning()).contains("反向合成");
}
```

- [ ] **Step 2: 运行全部测试**

```bash
mvn test
```

期望: 全部通过。

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/wireless/agent/IntegrationTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "test(m4): reverse synthetic end-to-end integration tests with pipeline detection"
```

---

### Task 8: 文档与最终验证

**Files:**
- Modify: `D:/agent-code/data-agent/README.md`

- [ ] **Step 1: 更新 README.md 追加 M4 段落**

在 M3 段落后追加：

```markdown
## M4 -- Reverse Synthetic Data Pipeline

**REVERSE_SYNTHETIC task direction** generates test data for existing pipelines:

| Step | Description |
|------|-------------|
| 1. Parse | Extracts input schemas and constraints from user-pasted pipeline code |
| 2. Clarify | Asks about data scale (1k–100M rows), anomaly ratio (1–10%), distribution learning |
| 3. Codegen | Generates data production code: SQL INSERT or Java Flink DataStream generator |
| 4. Dual Dry-Run | Step 1: Run generated code to produce test data; Step 2: Feed data into original pipeline to verify |

**Usage:**
```bash
mvn exec:java -Dexec.mainClass="com.wireless.agent.Main" -Dexec.args="--demo --no-llm --reverse"
```

**Example:** Paste a Flink SQL pipeline, and the agent generates a Java Flink data generator with configurable NUM_ROWS and ANOMALY_RATIO.
```

- [ ] **Step 2: 最终验证**

```bash
mvn test
```

- [ ] **Step 3: Commit**

```bash
git add README.md
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "docs(m4): add reverse synthetic pipeline quickstart to readme"
```

---

## M4 完成标准（DoD）

- [ ] `mvn test` 全部测试通过，无回归
- [ ] Spec 支持 `originalPipeline` 字段序列化/反序列化
- [ ] CodegenTool 在 REVERSE_SYNTHETIC 方向使用数据生产提示词
- [ ] CodegenTool 提供数据生成硬编码 fallback（含 NUM_ROWS 和 ANOMALY_RATIO）
- [ ] SandboxTool 的 `dualDryRun()` 执行两步验证
- [ ] ValidatorTool 检查 INSERT INTO 目标表与原始流水线源的兼容性
- [ ] AgentCore `executeTools()` 在反向分叉调用 `dualDryRun`
- [ ] Main.java `--reverse` 标志切换 REVERSE_SYNTHETIC 方向
- [ ] 反向合成集成测试通过

---

## 后续

M4 完成后 → M5（多轮反问收敛: 缺失规格的精准反问 + 用户回复解析 + 规格收敛判定）。
