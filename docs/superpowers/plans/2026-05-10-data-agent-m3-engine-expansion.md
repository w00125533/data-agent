# M3 引擎扩展 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 扩展 Agent 支持三种引擎产物（Spark SQL / Flink SQL / Java Flink Stream API），全部能 codegen 并通过 dry-run。

**Architecture:** CodegenTool 按引擎选择分发不同 system prompt 和硬编码 fallback；SandboxTool 按引擎选择提交到不同容器（spark-master vs flink-jobmanager）；EngineSelector 新增 `java_flink_streamapi` 推荐规则（复杂状态、递归逻辑、需调外部服务时推荐）。整体保持现有管线不变：Metadata → Profiler → EngineSelector → Codegen → Validator → Sandbox。

**Tech Stack:** Java 17, Maven, Docker CLI (via ProcessBuilder), JUnit 5, AssertJ

---

## 文件结构（M3 完成后的新增/变更）

```
data-agent/
├── src/main/resources/
│   └── agent.properties                              # 修改: +flink 配置
├── src/main/java/com/wireless/agent/
│   ├── core/
│   │   ├── EngineSelector.java                       # 修改: 新增 java_flink_streamapi 规则
│   │   ├── Prompts.java                              # 修改: system prompt 覆盖三引擎
│   │   └── AgentCore.java                            # 修改: 注入 flink container 参数
│   ├── tools/
│   │   ├── CodegenTool.java                          # 修改: 三引擎 codegen
│   │   └── SandboxTool.java                          # 修改: Flink SQL dry-run 支持
│   └── Main.java                                     # 修改: 加载 flink 配置
└── src/test/java/com/wireless/agent/
    ├── core/
    │   └── EngineSelectorTest.java                   # 修改: 新增 java_flink_streamapi 测试
    ├── tools/
    │   ├── CodegenToolTest.java                      # 修改: 新增 Flink SQL/Java 测试
    │   └── SandboxToolTest.java                      # 修改: 新增 Flink dry-run 测试
    └── IntegrationTest.java                          # 修改: 新增三引擎端到端
```

---

### Task 1: EngineSelector 扩展 — java_flink_streamapi 规则

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/core/EngineSelector.java`
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/core/EngineSelectorTest.java`

现有 EngineSelector 只有 `flink_sql` 和 `spark_sql` 两条规则。M3 新增 `java_flink_streamapi` 规则。

- [ ] **Step 1: 在 EngineSelectorTest.java 追加测试**

```java
@Test
void shouldRecommendFlinkSqlForKafkaPlusTimeWindow() {
    var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
    spec.target(new Spec.TargetSpec().name("test").timeliness("streaming"));
    spec.sources(List.of(new Spec.SourceBinding().role("stream")
            .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))));
    // Has Kafka source + needs streaming timeliness → flink_sql
    assertThat(EngineSelector.select(spec).recommended()).isEqualTo("flink_sql");
}

@Test
void shouldRecommendJavaFlinkStreamApiForComplexState() {
    var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
    spec.target(new Spec.TargetSpec().name("stateful_test").businessDefinition("需要复杂状态机处理"));
    spec.sources(List.of(new Spec.SourceBinding().role("stream")
            .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))));
    // Keyword hint for complex state → java_flink_streamapi
    assertThat(EngineSelector.select(spec).recommended()).isEqualTo("java_flink_streamapi");
}

@Test
void shouldStillRecommendFlinkSqlForSimpleStreamTask() {
    var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
    spec.target(new Spec.TargetSpec().name("simple_agg").businessDefinition("切换失败次数统计"));
    spec.sources(List.of(new Spec.SourceBinding().role("stream")
            .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))));
    // Simple aggregation on Kafka → still flink_sql
    assertThat(EngineSelector.select(spec).recommended()).isEqualTo("flink_sql");
}

@Test
void shouldRecommendJavaFlinkForReverseSyntheticTask() {
    var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
    spec.target(new Spec.TargetSpec().name("synth_data").businessDefinition("生成模拟信令数据"));
    spec.sources(List.of(new Spec.SourceBinding().role("stream")
            .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))));
    // Reverse synthetic with Kafka → java_flink_streamapi (data producer = Java code)
    assertThat(EngineSelector.select(spec).recommended()).isEqualTo("java_flink_streamapi");
}
```

- [ ] **Step 2: 运行测试确认失败（新增测试）**

```bash
mvn test -Dtest=EngineSelectorTest
```

期望: 3/5 新增测试 FAIL（规则尚未添加）。

- [ ] **Step 3: 在 EngineSelector.java 追加规则**

在现有 RULES 列表的 `new Rule("spark_sql", ...)` 单批源规则之后，追加以下规则：

```java
new Rule("java_flink_streamapi",
    "反向合成数据生产任务,需编程控制数据分布 → Java Flink Stream API",
    spec -> spec.taskDirection() == Spec.TaskDirection.REVERSE_SYNTHETIC
            && spec.sources().stream().anyMatch(s -> {
                var cat = s.binding().get("catalog");
                return "kafka".equals(cat);
            })),
new Rule("java_flink_streamapi",
    "任务含复杂状态/递归/外部服务调用,SQL无法表达 → Java Flink Stream API",
    spec -> {
        var def = spec.target() != null ? spec.target().businessDefinition() : "";
        var lower = def.toLowerCase();
        return lower.contains("复杂状态") || lower.contains("状态机")
            || lower.contains("递归") || lower.contains("外部服务")
            || lower.contains("自定义算子") || lower.contains("custom operator");
    })
```

同时修改 `FALLBACK` 为更明确的兜底说明：

```java
private static final Spec.EngineDecision FALLBACK =
        new Spec.EngineDecision("spark_sql", "无法自动判定,默认 Spark SQL (需人工确认)");
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -Dtest=EngineSelectorTest
```

期望: 9 passed (原有 5 + 新增 4)。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/core/EngineSelector.java \
        src/test/java/com/wireless/agent/core/EngineSelectorTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m3): engine selector adds java_flink_streamapi rules for complex state and reverse synthetic"
```

---

### Task 2: CodegenTool — Flink SQL 支持

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/CodegenTool.java`
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/tools/CodegenToolTest.java`

CodegenTool 当前只生成 Spark SQL。M3 需要根据 engine 选择不同的 system prompt 和硬编码 fallback。

- [ ] **Step 1: 在 CodegenToolTest.java 追加测试**

```java
@Test
void shouldGenerateFlinkSqlPrompt() {
    var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
    spec.target(new Spec.TargetSpec()
            .name("handover_failure_by_cell")
            .businessDefinition("按cell汇总最近1小时切换失败次数")
            .grain("(cell_id, hour)")
            .timeliness("streaming"));
    spec.sources(List.of(
            new Spec.SourceBinding()
                    .role("signaling")
                    .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))
                    .confidence(0.9)
    ));
    spec.networkContext().kpiFamily("mobility");
    spec.engineDecision(new Spec.EngineDecision("flink_sql", "Kafka 流式源,需时间窗口"));

    var prompt = CodegenTool.buildCodegenPrompt(spec);
    assertThat(prompt).contains("Flink SQL");
    assertThat(prompt).contains("signaling_events");
    assertThat(prompt).contains("flink_sql");
}

@Test
void shouldFallbackToHardcodedFlinkSqlWhenNoLlm() {
    var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
    spec.target(new Spec.TargetSpec()
            .name("handover_test")
            .businessDefinition("切换失败统计")
            .grain("(cell_id, hour)"));
    spec.sources(List.of(
            new Spec.SourceBinding()
                    .role("signaling")
                    .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))
    ));
    spec.engineDecision(new Spec.EngineDecision("flink_sql", "Kafka流式源"));

    var tool = new CodegenTool(null);
    var result = tool.run(spec);
    assertThat(result.success()).isTrue();
    var code = result.data().toString();
    assertThat(code).contains("flink_sql");
    assertThat(code).contains("handover");
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=CodegenToolTest
```

- [ ] **Step 3: 修改 CodegenTool.java**

**(a) 新增 Flink SQL system prompt 常量（在现有 `CODGEN_SYSTEM_PROMPT` 之后）：**

```java
public static final String FLINK_SQL_SYSTEM_PROMPT = """
        You are a Flink SQL code generator for wireless network perception tasks.

        Rules:
        1. Output ONLY the Flink SQL code, no explanation before or after.
        2. Use Flink SQL syntax (Flink 1.18+ with Hive catalog integration).
        3. Kafka sources must declare watermark: WATERMARK FOR ts AS ts - INTERVAL '5' SECOND.
        4. Time-windowed aggregations use TUMBLE/HOP/SESSION window functions.
        5. Include CREATE TABLE AS or INSERT INTO for target table.
        6. Join columns must match their semantic meaning.
        7. Filter conditions must reflect the business definition.
        8. For lookup join with Hive dim table, use FOR SYSTEM_TIME AS OF syntax.
        9. Wrap the final SQL in a ```sql code block.
        """;
```

**(b) 修改 `run(Spec)` 方法，根据 engine 选择 prompt 和 fallback：**

将 `CODGEN_SYSTEM_PROMPT` 的使用改为按引擎分发。找到 `run()` 中 `Map.of("role", "system", "content", CODGEN_SYSTEM_PROMPT)` 这行，替换为：

```java
var systemPrompt = selectSystemPrompt(spec);
```

同样，在 `llmClient == null` 和 LLM 错误时的 fallback 也要按引擎分发。找到 `hardcodedSparkSql(spec)` 调用，替换为 `hardcodedCode(spec)`。

**(c) 新增辅助方法：**

```java
private static String selectSystemPrompt(Spec spec) {
    var engine = spec.engineDecision();
    if (engine == null) return CODGEN_SYSTEM_PROMPT;
    return switch (engine.recommended()) {
        case "flink_sql" -> FLINK_SQL_SYSTEM_PROMPT;
        case "java_flink_streamapi" -> JAVA_FLINK_SYSTEM_PROMPT;
        default -> CODGEN_SYSTEM_PROMPT;
    };
}

/** Fallback hardcoded code for all engines when LLM is unavailable. */
static String hardcodedCode(Spec spec) {
    var engine = spec.engineDecision();
    if (engine == null) return hardcodedSparkSql(spec);
    return switch (engine.recommended()) {
        case "flink_sql" -> hardcodedFlinkSql(spec);
        case "java_flink_streamapi" -> hardcodedJavaFlink(spec);
        default -> hardcodedSparkSql(spec);
    };
}
```

**(d) 新增 Flink SQL 硬编码 fallback：**

在 `hardcodedSparkSql` 方法之后追加：

```java
/** Fallback hardcoded Flink SQL for demo scenarios. */
static String hardcodedFlinkSql(Spec spec) {
    var target = spec.target();
    var def = target != null ? target.businessDefinition() : "";

    if (def.contains("切换失败") || def.toLowerCase().contains("handover failure")) {
        return """
                ```sql
                -- 切换失败按小区统计 (Flink SQL, Kafka source + time window)
                CREATE TABLE IF NOT EXISTS handover_failure_cell_hour (
                    cell_id STRING,
                    window_start TIMESTAMP(3),
                    window_end TIMESTAMP(3),
                    failure_count BIGINT,
                    success_count BIGINT,
                    ho_succ_rate DOUBLE
                ) WITH (
                    'connector' = 'filesystem',
                    'path' = '/output/handover_failure_cell_hour',
                    'format' = 'parquet'
                );

                INSERT INTO handover_failure_cell_hour
                SELECT
                    src_cell AS cell_id,
                    TUMBLE_START(ts, INTERVAL '1' HOUR) AS window_start,
                    TUMBLE_END(ts, INTERVAL '1' HOUR) AS window_end,
                    SUM(CASE WHEN result = 'failure' THEN 1 ELSE 0 END) AS failure_count,
                    SUM(CASE WHEN result = 'success' THEN 1 ELSE 0 END) AS success_count,
                    CAST(SUM(CASE WHEN result = 'success' THEN 1 ELSE 0 END) AS DOUBLE)
                        / CAST(COUNT(*) AS DOUBLE) * 100 AS ho_succ_rate
                FROM signaling_events
                WHERE event_type = 'handover'
                GROUP BY src_cell, TUMBLE(ts, INTERVAL '1' HOUR);
                ```""";
    }
    return String.format("""
            ```sql
            -- %s (Flink SQL)
            SELECT * FROM signaling_events WHERE event_type = 'handover' LIMIT 100;
            ```""",
            target != null ? target.name() : "output");
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -Dtest=CodegenToolTest
```

期望: 6 passed (原有 4 + 新增 2)。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/tools/CodegenTool.java \
        src/test/java/com/wireless/agent/tools/CodegenToolTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m3): codegen tool supports flink sql with dedicated prompt and fallback"
```

---

### Task 3: CodegenTool — Java Flink Stream API 支持

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/CodegenTool.java`
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/tools/CodegenToolTest.java`

继 Task 2 后，在 CodegenTool 中追加 Java Flink Stream API 的 system prompt 和硬编码 fallback。

- [ ] **Step 1: 在 CodegenToolTest.java 追加测试**

```java
@Test
void shouldGenerateJavaFlinkStreamApiPrompt() {
    var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
    spec.target(new Spec.TargetSpec()
            .name("stateful_sessionization")
            .businessDefinition("需要复杂状态机的Session窗口聚合")
            .grain("(cell_id, session)")
            .timeliness("streaming"));
    spec.sources(List.of(
            new Spec.SourceBinding()
                    .role("signaling")
                    .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))
    ));
    spec.engineDecision(new Spec.EngineDecision("java_flink_streamapi",
            "复杂状态机SQL无法表达 → Java Flink Stream API"));

    var prompt = CodegenTool.buildCodegenPrompt(spec);
    assertThat(prompt).contains("Java Flink Stream API");
    assertThat(prompt).contains("java_flink_streamapi");
}

@Test
void shouldFallbackToHardcodedJavaFlinkWhenNoLlm() {
    var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
    spec.target(new Spec.TargetSpec()
            .name("session_test")
            .businessDefinition("Session窗口化切换事件")
            .grain("(cell_id, session)"));
    spec.sources(List.of(
            new Spec.SourceBinding()
                    .role("signaling")
                    .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))
    ));
    spec.engineDecision(new Spec.EngineDecision("java_flink_streamapi",
            "复杂窗口逻辑 → Java"));

    var tool = new CodegenTool(null);
    var result = tool.run(spec);
    assertThat(result.success()).isTrue();
    var code = result.data().toString();
    assertThat(code).contains("java_flink");
    assertThat(code).contains("FlinkDataStream");
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=CodegenToolTest
```

- [ ] **Step 3: 修改 CodegenTool.java — 追加 Java Flink system prompt 和 fallback**

**(a) 在 FLINK_SQL_SYSTEM_PROMPT 之后追加：**

```java
public static final String JAVA_FLINK_SYSTEM_PROMPT = """
        You are a Java Flink Stream API code generator for wireless network perception tasks.

        Rules:
        1. Output ONLY the Java code, no explanation before or after.
        2. Use org.apache.flink.streaming.api (Flink 1.18+, Java 17).
        3. Use FlinkKafkaConsumer for Kafka sources, FlinkKafkaProducer for sinks.
        4. Implement KeyedProcessFunction for stateful logic (state machines, sessions).
        5. Use MapState/ValueState for complex state management.
        6. Include main() method with StreamExecutionEnvironment setup.
        7. Handle checkpoints and state TTL where needed.
        8. Wrap the final code in a ```java code block.
        """;
```

**(b) 在 `hardcodedFlinkSql` 方法之后追加：**

```java
/** Fallback hardcoded Java Flink Stream API code for demo scenarios. */
static String hardcodedJavaFlink(Spec spec) {
    var target = spec.target();
    var def = target != null ? target.businessDefinition() : "";

    if (def.contains("切换失败") || def.contains("Session")
            || def.toLowerCase().contains("handover") || def.toLowerCase().contains("session")) {
        return """
                ```java
                // 切换事件 Session 窗口聚合 (Java Flink Stream API)
                import org.apache.flink.api.common.eventtime.WatermarkStrategy;
                import org.apache.flink.api.common.state.*;
                import org.apache.flink.streaming.api.datastream.DataStream;
                import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
                import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
                import org.apache.flink.streaming.api.windowing.assigners.*;
                import org.apache.flink.streaming.api.windowing.time.Time;
                import org.apache.flink.util.Collector;

                public class HandoverSessionizer {
                    public static void main(String[] args) throws Exception {
                        StreamExecutionEnvironment env =
                            StreamExecutionEnvironment.getExecutionEnvironment();
                        env.enableCheckpointing(60000);

                        DataStream<HandoverEvent> events = env
                            .addSource(new FlinkKafkaConsumer<>("signaling_events",
                                new HandoverEventSchema(), kafkaProps))
                            .assignTimestampsAndWatermarks(
                                WatermarkStrategy.<HandoverEvent>forBoundedOutOfOrderness(
                                    java.time.Duration.ofSeconds(5))
                                .withTimestampAssigner((e, ts) -> e.getTimestamp()));

                        events
                            .keyBy(HandoverEvent::getSrcCell)
                            .window(EventTimeSessionWindows.withGap(Time.minutes(15)))
                            .process(new SessionAggregator())
                            .addSink(new FlinkKafkaProducer<>("output_topic",
                                new SessionResultSchema(), kafkaProps));

                        env.execute("Handover Sessionizer");
                    }
                }
                ```""";
    }
    return String.format("""
            ```java
            // %s (Java Flink Stream API)
            import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
            import org.apache.flink.streaming.api.datastream.DataStream;

            public class FlinkDataStream {
                public static void main(String[] args) throws Exception {
                    StreamExecutionEnvironment env =
                        StreamExecutionEnvironment.getExecutionEnvironment();
                    DataStream<String> source = env.fromElements("sample_event");
                    source.print();
                    env.execute("DataStream Job");
                }
            }
            ```""",
            target != null ? target.name() : "FlinkDataStream");
}
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -Dtest=CodegenToolTest
```

期望: 8 passed (原有 6 + 新增 2)。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/tools/CodegenTool.java \
        src/test/java/com/wireless/agent/tools/CodegenToolTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m3): codegen tool supports java flink stream api with dedicated prompt and fallback"
```

---

### Task 4: SandboxTool — Flink SQL 和 Java Flink Dry-Run

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/SandboxTool.java`
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/tools/SandboxToolTest.java`

SandboxTool 当前只支持 Spark SQL dry-run。M3 扩展为根据引擎选择提交到不同的 Docker 容器。

- [ ] **Step 1: 在 SandboxToolTest.java 追加测试**

```java
@Test
void shouldSelectSparkContainerForSparkSql() {
    var runner = new DockerCommandRunner();
    var sandbox = new SandboxTool(runner, "da-spark-master", "da-flink-jobmanager");
    assertThat(sandbox.sparkContainer()).isEqualTo("da-spark-master");
    assertThat(sandbox.flinkContainer()).isEqualTo("da-flink-jobmanager");
}

@Test
void shouldSelectFlinkContainerForFlinkSql() {
    var runner = new DockerCommandRunner();
    var sandbox = new SandboxTool(runner, "da-spark-master", "da-flink-jobmanager");

    var spec = new com.wireless.agent.core.Spec(com.wireless.agent.core.Spec.TaskDirection.FORWARD_ETL);
    spec.engineDecision(new com.wireless.agent.core.Spec.EngineDecision("flink_sql", "流式"));

    var container = sandbox.selectContainer(spec);
    assertThat(container).isEqualTo("da-flink-jobmanager");
}

@Test
void shouldDefaultToSparkForUnknownEngine() {
    var runner = new DockerCommandRunner();
    var sandbox = new SandboxTool(runner, "da-spark-master", "da-flink-jobmanager");

    var spec = new com.wireless.agent.core.Spec(com.wireless.agent.core.Spec.TaskDirection.FORWARD_ETL);
    // No engine decision → default to spark
    var container = sandbox.selectContainer(spec);
    assertThat(container).isEqualTo("da-spark-master");
}

@Test
void shouldUseFlinkSqlClientForFlinkSqlExecution() {
    var runner = new DockerCommandRunner();
    var sandbox = new SandboxTool(runner, "da-spark-master", "da-flink-jobmanager");
    var cmd = sandbox.buildExecutionCommand("da-flink-jobmanager", "SELECT 1;");
    // Flink uses sql-client.sh, Spark uses spark-sql
    assertThat(cmd).contains("sql-client.sh");
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=SandboxToolTest
```

- [ ] **Step 3: 修改 SandboxTool.java**

**(a) 添加 flink container 字段和 getter：**

```java
private final String flinkContainer;

public String sparkContainer() { return sparkContainer; }
public String flinkContainer() { return flinkContainer; }
```

**(b) 修改构造函数（保持向后兼容）：**

```java
public SandboxTool(DockerCommandRunner runner, String sparkContainer) {
    this(runner, sparkContainer, "da-flink-jobmanager", null);
}

public SandboxTool(DockerCommandRunner runner, String sparkContainer, String flinkContainer) {
    this(runner, sparkContainer, flinkContainer, null);
}

public SandboxTool(DockerCommandRunner runner, String sparkContainer,
                   String flinkContainer, BaselineService baselineService) {
    this.runner = runner;
    this.sparkContainer = sparkContainer;
    this.flinkContainer = flinkContainer;
    this.baselineService = baselineService;
}
```

**(c) 添加容器选择和命令构建：**

```java
/** Select the target container based on engine decision. */
public String selectContainer(Spec spec) {
    var engine = spec.engineDecision();
    if (engine == null) return sparkContainer;
    return switch (engine.recommended()) {
        case "flink_sql" -> flinkContainer;
        case "java_flink_streamapi" -> flinkContainer;
        default -> sparkContainer;
    };
}

/** Build the execution command for a given container. */
public List<String> buildExecutionCommand(String container, String sql) {
    if (container.equals(flinkContainer)) {
        // Flink SQL Client
        return List.of("bash", "-c",
                "echo '" + sql.replace("'", "'\\''") + "' | /opt/flink/bin/sql-client.sh");
    }
    // Spark SQL
    return List.of("spark-sql", "--master", "spark://spark-master:7077", "-e", sql);
}
```

**(d) 修改 `dryRun()` 方法，使用 selectContainer 和 buildExecutionCommand：**

在 `dryRun` 方法中，找到 `runner.exec(sparkContainer, List.of(...))` 这行，替换为：

```java
var targetContainer = selectContainer(spec);
var execCmd = buildExecutionCommand(targetContainer, sql);
var result = runner.exec(targetContainer, execCmd);
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -Dtest=SandboxToolTest
```

期望: 11 passed (原有 7 + 新增 4)。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/tools/SandboxTool.java \
        src/test/java/com/wireless/agent/tools/SandboxToolTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m3): sandbox tool supports flink sql and java flink dry-run via flink-jobmanager"
```

---

### Task 5: AgentCore + Main + Config 接入 Flink 容器

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/core/AgentCore.java`
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/Main.java`
- Modify: `D:/agent-code/data-agent/src/main/resources/agent.properties`

- [ ] **Step 1: 更新 agent.properties**

```properties
# M3 — Flink Configuration
flink.container=da-flink-jobmanager
flink.jobmanager=flink-jobmanager:8081
```

- [ ] **Step 2: 修改 AgentCore.java 构造函数注入 flinkContainer**

在 5-arg 构造函数中添加 flinkContainer 参数，默认值 `"da-flink-jobmanager"`。修改 SandboxTool 构造为传入 flinkContainer：

```java
// In the canonical constructor (5-arg becomes 6-arg with flinkContainer):
public AgentCore(DeepSeekClient llmClient, Spec.TaskDirection taskDirection,
                 String hmsUri, String sparkContainer, String flinkContainer,
                 DomainKnowledgeBase kb) {
    // ... existing init ...
    this.sandboxTool = new SandboxTool(cmdRunner, sparkContainer, flinkContainer, baselineService);
}
```

修改 4-arg 和 5-arg 构造函数委托链传入默认 flinkContainer：

```java
public AgentCore(DeepSeekClient llmClient, Spec.TaskDirection taskDirection,
                 String hmsUri, String sparkContainer) {
    this(llmClient, taskDirection, hmsUri, sparkContainer,
         "da-flink-jobmanager", new DomainKnowledgeBase());
}

public AgentCore(DeepSeekClient llmClient, Spec.TaskDirection taskDirection,
                 String hmsUri, String sparkContainer, DomainKnowledgeBase kb) {
    this(llmClient, taskDirection, hmsUri, sparkContainer,
         "da-flink-jobmanager", kb);
}
```

- [ ] **Step 3: 修改 Main.java 加载 flink 配置**

```java
var flinkContainer = System.getenv().getOrDefault("FLINK_CONTAINER",
        props.getProperty("flink.container", "da-flink-jobmanager"));

// Pass to AgentCore:
var agent = new AgentCore(llmClient, Spec.TaskDirection.FORWARD_ETL,
        hmsUri, sparkContainer, flinkContainer);
```

- [ ] **Step 4: 运行测试**

```bash
mvn test
```

期望: 全部测试通过，无回归。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/core/AgentCore.java \
        src/main/java/com/wireless/agent/Main.java \
        src/main/resources/agent.properties
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m3): wire flink container into agentcore, main, and config"
```

---

### Task 6: 集成测试 — 三引擎端到端

**Files:**
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/IntegrationTest.java`

- [ ] **Step 1: 在 IntegrationTest.java 追加三引擎端到端测试**

```java
@Test
void shouldProcessFlinkSqlTaskWithKafkaSource() {
    var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
            "thrift://nonexistent:9999", "da-spark-master", "da-flink-jobmanager");

    var result = agent.processMessage("实时监控最近1小时每个小区的切换失败次数");

    assertThat(result.get("next_action"))
            .isIn("code_done", "dry_run_ok", "sandbox_failed");
    var code = result.get("code").toString();
    assertThat(code).isNotEmpty();
}

@Test
void shouldProcessSparkSqlTaskWithBatchSources() {
    var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
            "thrift://nonexistent:9999", "da-spark-master", "da-flink-jobmanager");

    var result = agent.processMessage("按区县统计最近30天弱覆盖小区数量");

    assertThat(result.get("next_action"))
            .isIn("code_done", "dry_run_ok", "sandbox_failed");
    var code = result.get("code").toString();
    assertThat(code).isNotEmpty();
    // Spark SQL batch task
    var engine = result.get("engine").toString();
    assertThat(engine).isIn("spark_sql", "flink_sql");
}

@Test
void shouldSupportAllThreeEngineTypes() {
    // Verify EngineSelector covers all 3 types
    var sparkSpec = new Spec(Spec.TaskDirection.FORWARD_ETL);
    sparkSpec.sources(List.of(new Spec.SourceBinding().role("main")
            .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))));
    assertThat(EngineSelector.select(sparkSpec).recommended()).isEqualTo("spark_sql");

    var flinkSpec = new Spec(Spec.TaskDirection.FORWARD_ETL);
    flinkSpec.sources(List.of(new Spec.SourceBinding().role("stream")
            .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))));
    assertThat(EngineSelector.select(flinkSpec).recommended()).isEqualTo("flink_sql");

    var javaFlinkSpec = new Spec(Spec.TaskDirection.FORWARD_ETL);
    javaFlinkSpec.target(new Spec.TargetSpec()
            .name("stateful").businessDefinition("复杂状态机分析"));
    javaFlinkSpec.sources(List.of(new Spec.SourceBinding().role("stream")
            .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))));
    assertThat(EngineSelector.select(javaFlinkSpec).recommended())
            .isEqualTo("java_flink_streamapi");
}
```

- [ ] **Step 2: 运行测试**

```bash
mvn test
```

期望: 全部通过。

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/wireless/agent/IntegrationTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "test(m3): three-engine end-to-end integration tests"
```

---

### Task 7: 文档与最终验证

**Files:**
- Modify: `D:/agent-code/data-agent/README.md`

- [ ] **Step 1: 更新 README.md 追加 M3 段落**

```markdown
## M3 -- Engine Expansion (Flink SQL + Java Flink Stream API)

**Three engine artifacts now supported:**

| Engine | Use Case | Dry-run Target |
|--------|----------|----------------|
| `spark_sql` | 批源 Hive/StarRocks, join+聚合 | `da-spark-master` (spark-sql) |
| `flink_sql` | 流源 Kafka/CDC, 时间窗口聚合 | `da-flink-jobmanager` (sql-client.sh) |
| `java_flink_streamapi` | 复杂状态机/递归/外部服务调用 | `da-flink-jobmanager` (flink run) |

**Engine selection logic:**

| Signal | Selection |
|--------|-----------|
| 源含 Kafka + 时间窗口 | `flink_sql` |
| 源含 Kafka + 复杂状态/状态机 | `java_flink_streamapi` |
| 反向合成数据生产 | `java_flink_streamapi` |
| 全 Hive 批源 | `spark_sql` |

**Hardcoded fallbacks** for all three engines when LLM is unavailable (demo mode).
```

- [ ] **Step 2: 最终验证**

```bash
mvn test && mvn exec:java -Dexec.mainClass="com.wireless.agent.Main" -Dexec.args="--demo --no-llm"
```

- [ ] **Step 3: Commit**

```bash
git add README.md
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "docs(m3): add three-engine expansion quickstart to readme"
```

---

## M3 完成标准（DoD）

- [ ] `mvn test` 全部测试通过，无回归
- [ ] EngineSelector 覆盖三种引擎：`spark_sql` / `flink_sql` / `java_flink_streamapi`
- [ ] CodegenTool 为三种引擎提供不同的 system prompt（含引擎特有的语法要求）
- [ ] CodegenTool 为三种引擎提供硬编码 fallback（无 LLM 时 demo 可用）
- [ ] SandboxTool 根据引擎选择目标容器（spark-master vs flink-jobmanager）
- [ ] Flink SQL dry-run 提交到 `da-flink-jobmanager`，使用 `sql-client.sh`
- [ ] Java Flink Stream API 生成完整可编译的 Java 代码（含 main、source、sink）
- [ ] `mvn exec:java ... --demo --no-llm` 端到端跑通

---

## 后续

M3 完成后 → M4（反向合成: CodegenTool 反向子流水线 + 双重 dry-run）。
