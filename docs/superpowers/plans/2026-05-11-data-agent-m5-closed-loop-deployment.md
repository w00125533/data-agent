# M5 Closed-Loop Deployment 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现会话闭环：从对话到代码生成、dry-run 预览、用户确认后生成 PR + 工单 + 一次性提交脚本，并对全流程记录可重放的 Session Trace。

**Architecture:** 新增三个子系统：(1) UserPreferencesStore — JSON 文件持久化的用户偏好键值存储，供 EngineSelector 查询团队约定引擎；(2) SessionTrace/TraceRecorder — 会话事件记录器，在 AgentCore.processMessage 各阶段埋点，会话结束时序列化为 JSON trace 文件；(3) SchedulerTool — 从 spec + 代码生成三种产物：一次性 spark-submit/flink-run 提交脚本、PR 模板 Markdown、工单/变更单 Markdown。AgentCore 新增 `--deploy` 流程分支：Sandbox 成功后 → 用户确认 → SchedulerTool 生成产物 → 输出给用户。

**Tech Stack:** Java 17, Maven, JUnit 5, AssertJ, Jackson JSON

---

## 文件结构（M5 完成后的新增/变更）

```
data-agent/
├── src/main/java/com/wireless/agent/
│   ├── core/
│   │   ├── Spec.java                 # 不修改（owners/testCases/deployment 字段已存在）
│   │   ├── AgentCore.java            # 修改: +SchedulerTool, +TraceRecorder 埋点, +confirmDeploy
│   │   ├── Prompts.java              # 修改: +buildDeployConfirmationPrompt
│   │   └── EngineSelector.java       # 修改: +UserPreferencesStore 查询
│   ├── tools/
│   │   └── SchedulerTool.java        # 新增: PR + 工单 + 提交脚本生成
│   ├── prefs/
│   │   └── UserPreferencesStore.java # 新增: 用户偏好持久化存储
│   └── trace/
│       ├── SessionTrace.java         # 新增: trace 数据模型
│       ├── TraceRecorder.java        # 新增: trace 事件记录
│       └── TraceReplay.java          # 新增: trace 重放
├── src/main/resources/
│   └── agent.properties              # 修改: +deploy/scheduler/trace 配置项
├── src/test/java/com/wireless/agent/
│   ├── core/
│   │   └── AgentCoreTest.java        # 修改: +deploy 流程测试
│   ├── tools/
│   │   └── SchedulerToolTest.java    # 新增: SchedulerTool 测试
│   ├── prefs/
│   │   └── UserPreferencesStoreTest.java  # 新增: 偏好存储测试
│   ├── trace/
│   │   └── TraceRecorderTest.java    # 新增: trace 录制测试
│   └── IntegrationTest.java          # 修改: +闭环端到端测试
└── README.md                         # 修改: +M5 章节
```

---

### Task 1: UserPreferencesStore — 用户偏好持久化存储

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/prefs/UserPreferencesStore.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/prefs/UserPreferencesStoreTest.java`

- [ ] **Step 1: 创建 UserPreferencesStoreTest.java**

```java
package com.wireless.agent.prefs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserPreferencesStoreTest {

    @Test
    void shouldReturnDefaultWhenNoPreferenceSaved(@TempDir Path tmpDir) {
        var store = new UserPreferencesStore(tmpDir);
        var engine = store.get("user1", "engine_preference", "spark_sql");
        assertThat(engine).isEqualTo("spark_sql");
    }

    @Test
    void shouldSaveAndRetrievePreference(@TempDir Path tmpDir) {
        var store = new UserPreferencesStore(tmpDir);
        store.set("user1", "engine_preference", "flink_sql");

        var engine = store.get("user1", "engine_preference", "spark_sql");
        assertThat(engine).isEqualTo("flink_sql");
    }

    @Test
    void shouldIsolateUsers(@TempDir Path tmpDir) {
        var store = new UserPreferencesStore(tmpDir);
        store.set("user1", "engine_preference", "flink_sql");
        store.set("user2", "engine_preference", "spark_sql");

        assertThat(store.get("user1", "engine_preference", "")).isEqualTo("flink_sql");
        assertThat(store.get("user2", "engine_preference", "")).isEqualTo("spark_sql");
    }

    @Test
    void shouldGetAllPreferences(@TempDir Path tmpDir) {
        var store = new UserPreferencesStore(tmpDir);
        store.set("user1", "engine_preference", "flink_sql");
        store.set("user1", "default_queue", "root.prod.wireless");

        var all = store.getAll("user1");
        assertThat(all).containsEntry("engine_preference", "flink_sql");
        assertThat(all).containsEntry("default_queue", "root.prod.wireless");
    }

    @Test
    void shouldOverwriteExistingPreference(@TempDir Path tmpDir) {
        var store = new UserPreferencesStore(tmpDir);
        store.set("user1", "engine_preference", "flink_sql");
        store.set("user1", "engine_preference", "java_flink_streamapi");

        assertThat(store.get("user1", "engine_preference", "")).isEqualTo("java_flink_streamapi");
    }

    @Test
    void shouldPersistAcrossStoreInstances(@TempDir Path tmpDir) {
        var store1 = new UserPreferencesStore(tmpDir);
        store1.set("user1", "nickname", "老王");

        var store2 = new UserPreferencesStore(tmpDir);
        assertThat(store2.get("user1", "nickname", "")).isEqualTo("老王");
    }

    @Test
    void shouldReturnDefaultForMissingUserFile(@TempDir Path tmpDir) {
        var store = new UserPreferencesStore(tmpDir);
        assertThat(store.get("nonexistent_user", "any_key", "default")).isEqualTo("default");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=UserPreferencesStoreTest
```

期望: 编译失败（UserPreferencesStore 类不存在）。

- [ ] **Step 3: 创建 UserPreferencesStore.java**

```java
package com.wireless.agent.prefs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** JSON-file backed user preferences store. Thread-safe. */
public class UserPreferencesStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path storeDir;
    private final Map<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    public UserPreferencesStore(Path storeDir) {
        this.storeDir = storeDir;
    }

    /** Get a preference value, returning defaultValue if not set. */
    public String get(String userId, String key, String defaultValue) {
        var prefs = load(userId);
        return prefs.getOrDefault(key, defaultValue);
    }

    /** Set a preference value and persist immediately. */
    public void set(String userId, String key, String value) {
        var prefs = new LinkedHashMap<>(load(userId));
        prefs.put(key, value);
        save(userId, prefs);
        cache.put(userId, Collections.unmodifiableMap(prefs));
    }

    /** Return all preferences for a user. */
    public Map<String, String> getAll(String userId) {
        return Collections.unmodifiableMap(load(userId));
    }

    private Map<String, String> load(String userId) {
        var cached = cache.get(userId);
        if (cached != null) return cached;

        try {
            var file = prefsFile(userId);
            if (!Files.exists(file)) return Map.of();
            var content = Files.readString(file);
            if (content.isBlank()) return Map.of();
            Map<String, String> prefs = MAPPER.readValue(content,
                    new TypeReference<Map<String, String>>() {});
            cache.put(userId, prefs);
            return prefs;
        } catch (IOException e) {
            return Map.of();
        }
    }

    private void save(String userId, Map<String, String> prefs) {
        try {
            Files.createDirectories(storeDir);
            var file = prefsFile(userId);
            MAPPER.writeValue(file.toFile(), prefs);
        } catch (IOException e) {
            System.err.println("[UserPreferencesStore] Failed to save prefs for " + userId + ": " + e.getMessage());
        }
    }

    private Path prefsFile(String userId) {
        return storeDir.resolve(userId + ".json");
    }
}
```

- [ ] **Step 4: 运行 UserPreferencesStoreTest**

```bash
mvn test -Dtest=UserPreferencesStoreTest
```

期望: 7 passed。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/prefs/UserPreferencesStore.java src/test/java/com/wireless/agent/prefs/UserPreferencesStoreTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m5): add UserPreferencesStore with JSON-file backed per-user key-value persistence"
```

---

### Task 2: EngineSelector 接入 UserPreferencesStore

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/core/EngineSelector.java`
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/core/EngineSelectorTest.java`

- [ ] **Step 1: 在 EngineSelectorTest.java 追加偏好查询测试**

在现有测试类末尾添加：

```java
@Test
void shouldRespectUserEnginePreference() throws Exception {
    var tmpDir = java.nio.file.Files.createTempDirectory("prefs-test");
    try {
        var prefs = new com.wireless.agent.prefs.UserPreferencesStore(tmpDir);
        prefs.set("test-user", "engine_preference", "flink_sql");

        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.sources(List.of(new Spec.SourceBinding().role("main")
                .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))));

        // Without user pref → would be spark_sql, but pref overrides to flink_sql
        var decision = EngineSelector.select(spec, prefs, "test-user");
        assertThat(decision.recommended()).isEqualTo("flink_sql");
        assertThat(decision.userOverridden()).isTrue();
    } finally {
        deleteRecursively(tmpDir);
    }
}

@Test
void shouldFallbackWhenNoUserPreference() {
    var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
    spec.sources(List.of(new Spec.SourceBinding().role("main")
            .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))));

    var decision = EngineSelector.select(spec, null, null);
    assertThat(decision.recommended()).isEqualTo("spark_sql");
    assertThat(decision.userOverridden()).isNull();
}

@Test
void shouldNotOverrideWhenUserPrefIsUnknownEngine() {
    var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
    spec.sources(List.of(new Spec.SourceBinding().role("main")
            .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))));

    // User pref with invalid engine name should be ignored
    var decision = EngineSelector.select(spec, "invalid_engine");
    assertThat(decision.recommended()).isEqualTo("spark_sql");
    assertThat(decision.userOverridden()).isNull();
}

private static void deleteRecursively(Path dir) throws Exception {
    if (dir == null || !Files.exists(dir)) return;
    try (var s = Files.walk(dir)) {
        s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try { Files.delete(p); } catch (Exception ignored) {}
        });
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=EngineSelectorTest
```

期望: 3 个新测试编译失败（新 `select` 重载方法不存在）。

- [ ] **Step 3: 修改 EngineSelector.java 新增偏好查询重载**

在原有的 `select(Spec spec)` 方法之后添加：

```java
private static final List<String> VALID_ENGINES = List.of(
        "spark_sql", "flink_sql", "java_flink_streamapi");

/** Select engine with optional user preference override. */
public static Spec.EngineDecision select(Spec spec,
        UserPreferencesStore prefs, String userId) {
    if (prefs != null && userId != null) {
        var preferred = prefs.get(userId, "engine_preference", "");
        if (!preferred.isEmpty() && VALID_ENGINES.contains(preferred)) {
            return new Spec.EngineDecision(preferred,
                    "用户偏好引擎: " + preferred, true, spec.engineDecision() != null
                            ? spec.engineDecision().deployment() : null);
        }
    }
    return select(spec);
}

/** Select engine with a direct engine name override (for testing). */
public static Spec.EngineDecision select(Spec spec, String directOverride) {
    if (directOverride != null && VALID_ENGINES.contains(directOverride)) {
        return new Spec.EngineDecision(directOverride,
                "用户指定引擎: " + directOverride, true, null);
    }
    return select(spec);
}
```

同时给 `select(Spec spec)` 方法添加 `deployment` 填充：

在原有 `select(Spec spec)` 方法中，每个 return 语句的 `new Spec.EngineDecision(...)` 改为包含 deployment：

```java
// FALLBACK 修改为：
private static final Spec.EngineDecision FALLBACK =
        new Spec.EngineDecision("spark_sql", "无法自动判定,默认 Spark SQL (需人工确认)",
                null, Map.of("submission_mode", "one_shot"));

// 每个 Rule 匹配的 return 改为包含 deployment:
// return new Spec.EngineDecision(rule.recommended(), rule.reasoning(),
//         null, Map.of("submission_mode", "one_shot"));
```

- [ ] **Step 4: 运行 EngineSelectorTest**

```bash
mvn test -Dtest=EngineSelectorTest
```

期望: 全部通过（原有 + 新增 3 = 至少 8 passed）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/core/EngineSelector.java src/test/java/com/wireless/agent/core/EngineSelectorTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m5): add UserPreferencesStore query to EngineSelector for team convention override"
```

---

### Task 3: SessionTrace 数据模型 + TraceRecorder

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/trace/SessionTrace.java`
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/trace/TraceRecorder.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/trace/TraceRecorderTest.java`

- [ ] **Step 1: 创建 TraceRecorderTest.java**

```java
package com.wireless.agent.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceRecorderTest {

    @Test
    void shouldRecordConversationEvent(@TempDir Path tmpDir) throws Exception {
        var sessionId = "test-session-1";
        var recorder = new TraceRecorder(tmpDir, sessionId, "test-user");

        recorder.recordConversation(1, "做个弱覆盖统计", "请补充数据源信息");
        recorder.finish("codegen_done");

        var traceFile = tmpDir.resolve(sessionId + ".json");
        assertThat(Files.exists(traceFile)).isTrue();

        var content = Files.readString(traceFile);
        assertThat(content).contains("test-session-1");
        assertThat(content).contains("弱覆盖统计");
        assertThat(content).contains("codegen_done");
    }

    @Test
    void shouldRecordToolCallEvent(@TempDir Path tmpDir) throws Exception {
        var sessionId = "test-session-2";
        var recorder = new TraceRecorder(tmpDir, sessionId, "test-user");

        recorder.recordToolCall("metadata", "lookup_dw.mr_5g_15min",
                150L, "success", null);
        recorder.finish("dry_run_ok");

        var content = Files.readString(tmpDir.resolve(sessionId + ".json"));
        assertThat(content).contains("metadata");
        assertThat(content).contains("150");
        assertThat(content).contains("success");
    }

    @Test
    void shouldRecordSpecSnapshot(@TempDir Path tmpDir) throws Exception {
        var sessionId = "test-session-3";
        var recorder = new TraceRecorder(tmpDir, sessionId, "test-user");

        recorder.recordSpecSnapshot(1, "{\"target\":{\"name\":\"弱覆盖统计\"}}");
        recorder.finish("codegen_done");

        var content = Files.readString(tmpDir.resolve(sessionId + ".json"));
        assertThat(content).contains("弱覆盖统计");
    }

    @Test
    void shouldRecordLlmCallEvent(@TempDir Path tmpDir) throws Exception {
        var sessionId = "test-session-4";
        var recorder = new TraceRecorder(tmpDir, sessionId, "test-user");

        recorder.recordLlmCall("deepseek-chat", 1, 1200, 450, 800L);
        recorder.finish("codegen_done");

        var content = Files.readString(tmpDir.resolve(sessionId + ".json"));
        assertThat(content).contains("deepseek-chat");
        assertThat(content).contains("1200");
    }

    @Test
    void shouldRecordFinalArtifacts(@TempDir Path tmpDir) throws Exception {
        var sessionId = "test-session-5";
        var recorder = new TraceRecorder(tmpDir, sessionId, "test-user");

        recorder.recordFinalArtifact("SELECT * FROM test;",
                "abc123", "pr-url-123", "TICKET-456");
        recorder.finish("codegen_done");

        var content = Files.readString(tmpDir.resolve(sessionId + ".json"));
        assertThat(content).contains("pr-url-123");
        assertThat(content).contains("TICKET-456");
        assertThat(content).contains("abc123");
    }

    @Test
    void shouldSetTimestamps(@TempDir Path tmpDir) throws Exception {
        var before = Instant.now();
        var sessionId = "test-session-6";
        var recorder = new TraceRecorder(tmpDir, sessionId, "test-user");
        recorder.recordConversation(1, "hello", "hi");
        recorder.finish("codegen_done");
        var after = Instant.now();

        var content = Files.readString(tmpDir.resolve(sessionId + ".json"));
        assertThat(content).contains("started_at");
        assertThat(content).contains("ended_at");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=TraceRecorderTest
```

期望: 编译失败（类不存在）。

- [ ] **Step 3: 创建 SessionTrace.java — trace 数据模型**

```java
package com.wireless.agent.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionTrace {

    @JsonProperty("session_id")    public String sessionId;
    @JsonProperty("user")          public String user;
    @JsonProperty("started_at")    public Instant startedAt;
    @JsonProperty("ended_at")      public Instant endedAt;
    @JsonProperty("final_state")   public String finalState;
    @JsonProperty("events")        public List<Object> events = new ArrayList<>();

    public static class ConversationEvent {
        @JsonProperty("type")      public final String type = "conversation";
        @JsonProperty("turn_id")   public int turnId;
        @JsonProperty("user_msg")  public String userMsg;
        @JsonProperty("agent_msg") public String agentMsg;
    }

    public static class SpecSnapshotEvent {
        @JsonProperty("type")      public final String type = "spec_snapshot";
        @JsonProperty("turn_id")   public int turnId;
        @JsonProperty("spec_json") public String specJson;
    }

    public static class ToolCallEvent {
        @JsonProperty("type")      public final String type = "tool_call";
        @JsonProperty("tool")      public String tool;
        @JsonProperty("input")     public String input;
        @JsonProperty("latency_ms") public long latencyMs;
        @JsonProperty("status")    public String status;
        @JsonProperty("error")     public String error;
    }

    public static class LlmCallEvent {
        @JsonProperty("type")              public final String type = "llm_call";
        @JsonProperty("model")             public String model;
        @JsonProperty("call_index")        public int callIndex;
        @JsonProperty("prompt_tokens")     public int promptTokens;
        @JsonProperty("completion_tokens") public int completionTokens;
        @JsonProperty("latency_ms")        public long latencyMs;
    }

    public static class ArtifactEvent {
        @JsonProperty("type")      public final String type = "artifact";
        @JsonProperty("code_hash") public String codeHash;
        @JsonProperty("pr_url")    public String prUrl;
        @JsonProperty("ticket_id") public String ticketId;
    }

    public SessionTrace() {}

    public SessionTrace(String sessionId, String user) {
        this.sessionId = sessionId;
        this.user = user;
        this.startedAt = Instant.now();
    }
}
```

- [ ] **Step 4: 创建 TraceRecorder.java**

```java
package com.wireless.agent.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Records session trace events and serializes to JSON on finish. Thread-safe. */
public class TraceRecorder {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private final Path outputDir;
    private final SessionTrace trace;
    private int llmCallCounter;
    private final Object lock = new Object();

    public TraceRecorder(Path outputDir, String sessionId, String user) {
        this.outputDir = outputDir;
        this.trace = new SessionTrace(sessionId, user);
    }

    public String sessionId() { return trace.sessionId; }

    /** Record a conversation turn. */
    public void recordConversation(int turnId, String userMsg, String agentMsg) {
        synchronized (lock) {
            var e = new SessionTrace.ConversationEvent();
            e.turnId = turnId;
            e.userMsg = userMsg;
            e.agentMsg = truncate(agentMsg, 2000);
            trace.events.add(e);
        }
    }

    /** Record a spec snapshot at a turn. */
    public void recordSpecSnapshot(int turnId, String specJson) {
        synchronized (lock) {
            var e = new SessionTrace.SpecSnapshotEvent();
            e.turnId = turnId;
            e.specJson = specJson;
            trace.events.add(e);
        }
    }

    /** Record a tool invocation. */
    public void recordToolCall(String tool, String input, long latencyMs,
                               String status, String error) {
        synchronized (lock) {
            var e = new SessionTrace.ToolCallEvent();
            e.tool = tool;
            e.input = truncate(input, 500);
            e.latencyMs = latencyMs;
            e.status = status;
            e.error = error;
            trace.events.add(e);
        }
    }

    /** Record an LLM call. */
    public void recordLlmCall(String model, int callIndex, int promptTokens,
                              int completionTokens, long latencyMs) {
        synchronized (lock) {
            var e = new SessionTrace.LlmCallEvent();
            e.model = model;
            e.callIndex = callIndex;
            e.promptTokens = promptTokens;
            e.completionTokens = completionTokens;
            e.latencyMs = latencyMs;
            trace.events.add(e);
            llmCallCounter++;
        }
    }

    /** Record final artifacts. */
    public void recordFinalArtifact(String code, String codeHash,
                                     String prUrl, String ticketId) {
        synchronized (lock) {
            var e = new SessionTrace.ArtifactEvent();
            e.codeHash = codeHash;
            e.prUrl = prUrl;
            e.ticketId = ticketId;
            trace.events.add(e);
        }
    }

    /** Finish the trace and write to disk. */
    public void finish(String finalState) {
        synchronized (lock) {
            trace.finalState = finalState;
            trace.endedAt = Instant.now();
            try {
                Files.createDirectories(outputDir);
                MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValue(outputDir.resolve(trace.sessionId + ".json").toFile(), trace);
            } catch (IOException e) {
                System.err.println("[TraceRecorder] Failed to write trace: " + e.getMessage());
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
```

- [ ] **Step 5: 运行 TraceRecorderTest**

```bash
mvn test -Dtest=TraceRecorderTest
```

期望: 6 passed。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/wireless/agent/trace/SessionTrace.java src/main/java/com/wireless/agent/trace/TraceRecorder.java src/test/java/com/wireless/agent/trace/TraceRecorderTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m5): add SessionTrace model and TraceRecorder for session observability"
```

---

### Task 4: TraceReplay — 会话重放

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/trace/TraceReplay.java`
- 无独立测试文件（TraceReplay 功能在集成测试中验证）

- [ ] **Step 1: 创建 TraceReplay.java**

```java
package com.wireless.agent.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Loads and replays session traces from disk. */
public class TraceReplay {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /** Load a single trace by session ID. */
    public SessionTrace load(Path traceDir, String sessionId) throws IOException {
        var file = traceDir.resolve(sessionId + ".json");
        if (!Files.exists(file)) {
            throw new IOException("Trace file not found: " + file);
        }
        return MAPPER.readValue(file.toFile(), SessionTrace.class);
    }

    /** List all trace session IDs in the trace directory. */
    public List<String> listSessions(Path traceDir) throws IOException {
        if (!Files.exists(traceDir)) return List.of();
        try (var s = Files.list(traceDir)) {
            return s.filter(p -> p.toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString().replace(".json", ""))
                    .toList();
        }
    }

    /** Get a summary of all recorded traces. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listSummaries(Path traceDir) throws IOException {
        if (!Files.exists(traceDir)) return List.of();
        try (var s = Files.list(traceDir)) {
            return s.filter(p -> p.toString().endsWith(".json"))
                    .map(p -> {
                        try {
                            var trace = MAPPER.readValue(p.toFile(), SessionTrace.class);
                            return Map.<String, Object>of(
                                "session_id", trace.sessionId,
                                "user", trace.user != null ? trace.user : "",
                                "started_at", trace.startedAt != null ? trace.startedAt.toString() : "",
                                "final_state", trace.finalState != null ? trace.finalState : "",
                                "event_count", trace.events.size()
                            );
                        } catch (IOException e) {
                            return Map.<String, Object>of(
                                "file", p.getFileName().toString(),
                                "error", e.getMessage()
                            );
                        }
                    })
                    .toList();
        }
    }

    /** Count events by type in a trace. */
    public Map<String, Long> eventTypeCounts(SessionTrace trace) {
        return trace.events.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        e -> e.getClass().getSimpleName(),
                        java.util.stream.Collectors.counting()));
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/wireless/agent/trace/TraceReplay.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m5): add TraceReplay for loading and summarizing session traces"
```

---

### Task 5: SchedulerTool — PR + 工单 + 提交脚本生成

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/SchedulerTool.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/tools/SchedulerToolTest.java`

- [ ] **Step 1: 创建 SchedulerToolTest.java**

```java
package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerToolTest {

    private Spec buildReadySpec() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.state(Spec.SpecState.CODEGEN_DONE);
        spec.target(new Spec.TargetSpec()
                .name("weak_cov_by_district")
                .businessDefinition("近30天5G弱覆盖按区县统计")
                .timeliness("batch_daily"));
        spec.sources(List.of(
                new Spec.SourceBinding().role("main")
                        .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))
                        .confidence(0.9),
                new Spec.SourceBinding().role("dim")
                        .binding(Map.of("catalog", "hive", "table_or_topic", "dim.engineering_param"))
                        .confidence(0.9)));
        spec.engineDecision(new Spec.EngineDecision("spark_sql", "全批源 join → Spark SQL",
                null, Map.of("submission_mode", "one_shot")));
        spec.owners().put("upstream", List.of("data-team"));
        spec.owners().put("downstream", List.of("coverage-team"));
        spec.testCases().add(Map.of("name", "正常样例", "expected_rows", 50));
        return spec;
    }

    @Test
    void shouldNameScheduler() {
        var tool = new SchedulerTool();
        assertThat(tool.name()).isEqualTo("scheduler");
        assertThat(tool.description()).contains("PR");
    }

    @Test
    void shouldGenerateSparkSubmitScript() {
        var tool = new SchedulerTool();
        var spec = buildReadySpec();
        var result = tool.run(spec, "SELECT * FROM test;");
        assertThat(result.success()).isTrue();

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.data();
        var submitScript = data.get("submit_script").toString();
        assertThat(submitScript).contains("spark-submit");
        assertThat(submitScript).contains("weak_cov_by_district");
    }

    @Test
    void shouldGenerateFlinkRunScript() {
        var tool = new SchedulerTool();
        var spec = buildReadySpec();
        spec.engineDecision(new Spec.EngineDecision("flink_sql",
                "Kafka source → Flink SQL", null,
                Map.of("submission_mode", "one_shot")));

        var result = tool.run(spec, "INSERT INTO kpi_output SELECT ...");
        assertThat(result.success()).isTrue();

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.data();
        var submitScript = data.get("submit_script").toString();
        assertThat(submitScript).contains("sql-client.sh");
    }

    @Test
    void shouldGeneratePrTemplate() {
        var tool = new SchedulerTool();
        var spec = buildReadySpec();
        var result = tool.run(spec, "CREATE TABLE test ...");
        assertThat(result.success()).isTrue();

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.data();
        var pr = data.get("pr_template").toString();
        assertThat(pr).contains("weak_cov_by_district");
        assertThat(pr).contains("## 变更内容");
        assertThat(pr).contains("coverage-team");
    }

    @Test
    void shouldGenerateTicketTemplate() {
        var tool = new SchedulerTool();
        var spec = buildReadySpec();
        var result = tool.run(spec, "SELECT * FROM test;");
        assertThat(result.success()).isTrue();

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.data();
        var ticket = data.get("ticket_template").toString();
        assertThat(ticket).contains("上线工单");
        assertThat(ticket).contains("weak_cov_by_district");
        assertThat(ticket).contains("test_case");
    }

    @Test
    void shouldFailWhenSpecNotReady() {
        var tool = new SchedulerTool();
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.state(Spec.SpecState.CLARIFYING); // not ready

        var result = tool.run(spec, "SELECT * FROM test;");
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not ready");
    }

    @Test
    void shouldFailWhenCodeIsEmpty() {
        var tool = new SchedulerTool();
        var spec = buildReadySpec();

        var result = tool.run(spec, "");
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("code");
    }

    @Test
    void shouldGenerateJavaFlinkSubmitCommand() {
        var tool = new SchedulerTool();
        var spec = buildReadySpec();
        spec.engineDecision(new Spec.EngineDecision("java_flink_streamapi",
                "复杂状态机 → Java Flink", null,
                Map.of("submission_mode", "one_shot")));

        var result = tool.run(spec, "// Java Flink code...");
        assertThat(result.success()).isTrue();

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.data();
        var submitScript = data.get("submit_script").toString();
        assertThat(submitScript).contains("flink run");
        assertThat(submitScript).contains(".jar");
    }

    @Test
    void shouldIncludeOwnersInPrTemplate() {
        var tool = new SchedulerTool();
        var spec = buildReadySpec();
        spec.owners().put("upstream", List.of("data-eng-team", "platform-team"));
        spec.owners().put("downstream", List.of("wireless-alert-system"));

        var result = tool.run(spec, "SELECT 1;");
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.data();
        var pr = data.get("pr_template").toString();
        assertThat(pr).contains("@data-eng-team");
        assertThat(pr).contains("@platform-team");
        assertThat(pr).contains("wireless-alert-system");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=SchedulerToolTest
```

期望: 编译失败（SchedulerTool 类不存在）。

- [ ] **Step 3: 创建 SchedulerTool.java**

```java
package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** Generates PR + ticket + one-shot submission script from spec + code. */
public class SchedulerTool implements Tool {

    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final List<String> VALID_ENGINES = List.of(
            "spark_sql", "flink_sql", "java_flink_streamapi");

    @Override
    public String name() { return "scheduler"; }

    @Override
    public String description() {
        return "从 spec + 代码生成 PR 模板、上线工单和一次性 spark-submit/flink run 提交脚本";
    }

    @Override
    public ToolResult run(Spec spec) {
        return ToolResult.fail("Use run(spec, code) with generated code");
    }

    /** Main entry: generate all three artifacts. */
    public ToolResult run(Spec spec, String code) {
        if (spec.state() != Spec.SpecState.CODEGEN_DONE) {
            return ToolResult.fail("Spec not ready for deployment: state="
                    + spec.state().value() + ", need codegen_done");
        }
        if (code == null || code.isBlank()) {
            return ToolResult.fail("No code to deploy");
        }

        var targetName = spec.target() != null ? spec.target().name() : "unnamed_task";
        var engine = spec.engineDecision() != null
                ? spec.engineDecision().recommended() : "spark_sql";

        var submitScript = generateSubmitScript(engine, targetName, code);
        var prTemplate = generatePrTemplate(spec, code, targetName);
        var ticketTemplate = generateTicketTemplate(spec, code, targetName, engine);
        var codeHash = sha256(code);

        return ToolResult.ok(Map.of(
            "submit_script", submitScript,
            "pr_template", prTemplate,
            "ticket_template", ticketTemplate,
            "code_hash", codeHash,
            "engine", engine,
            "target_name", targetName,
            "generated_at", LocalDateTime.now().format(DT_FMT)
        ));
    }

    /** Generate one-shot submission script based on engine type. */
    String generateSubmitScript(String engine, String targetName, String code) {
        var ts = LocalDateTime.now().format(DT_FMT);
        var jobName = targetName + "_" + ts;

        return switch (engine) {
            case "spark_sql" -> String.format("""
                    #!/bin/bash
                    # One-shot Spark SQL job — generated by Data Agent
                    # Target: %s
                    # Generated: %s

                    spark-sql \\
                      --master yarn \\
                      --deploy-mode cluster \\
                      --name "%s" \\
                      --queue root.prod.wireless \\
                      --conf spark.sql.adaptive.enabled=true \\
                      --conf spark.sql.adaptive.coalescePartitions.enabled=true \\
                      -e "%s"
                    """, targetName, ts, jobName, escapeForShell(code));

            case "flink_sql" -> String.format("""
                    #!/bin/bash
                    # One-shot Flink SQL job — generated by Data Agent
                    # Target: %s
                    # Generated: %s

                    echo '%s' | \\
                      /opt/flink/bin/sql-client.sh \\
                      -j /opt/flink/lib/flink-sql-connector-kafka.jar
                    """, targetName, ts, escapeForShell(code));

            case "java_flink_streamapi" -> String.format("""
                    #!/bin/bash
                    # One-shot Flink Stream API job — generated by Data Agent
                    # Target: %s
                    # Generated: %s

                    flink run \\
                      --jobmanager flink-jobmanager:8081 \\
                      --class com.wireless.agent.generated.%sJob \\
                      --parallelism 4 \\
                      target/data-agent-0.1.0.jar
                    """, targetName, ts, sanitizeClassName(targetName));

            default -> "# Unknown engine: " + engine;
        };
    }

    /** Generate PR markdown template. */
    String generatePrTemplate(Spec spec, String code, String targetName) {
        var sb = new StringBuilder();
        sb.append("# Data Agent Generated: ").append(targetName).append("\n\n");

        sb.append("## 变更内容\n\n");
        sb.append("新增数据集: `").append(targetName).append("`\n\n");

        var target = spec.target();
        if (target != null && !target.businessDefinition().isEmpty()) {
            sb.append("**业务口径:** ").append(target.businessDefinition()).append("\n\n");
        }
        if (target != null && !target.timeliness().isEmpty()) {
            sb.append("**时效性:** ").append(target.timeliness()).append("\n\n");
        }

        sb.append("## 代码\n\n");
        var engine = spec.engineDecision() != null
                ? spec.engineDecision().recommended() : "spark_sql";
        sb.append("```").append(engine.equals("java_flink_streamapi") ? "java" : "sql").append("\n");
        sb.append(code).append("\n");
        sb.append("```\n\n");

        sb.append("## 数据源\n\n");
        for (var src : spec.sources()) {
            var tbl = src.binding().getOrDefault("table_or_topic", "").toString();
            var cat = src.binding().getOrDefault("catalog", "unknown").toString();
            sb.append("- ").append(cat).append(": `").append(tbl).append("`");
            if (src.confidence() > 0) {
                sb.append(" (confidence: ").append(String.format("%.0f%%", src.confidence() * 100)).append(")");
            }
            sb.append("\n");
        }
        sb.append("\n");

        sb.append("## Owner / Reviewers\n\n");
        var upstream = spec.owners().get("upstream");
        if (upstream != null && !upstream.isEmpty()) {
            sb.append("**上游数据 owner:** ");
            sb.append(upstream.stream().map(o -> "@" + o).collect(Collectors.joining(", ")));
            sb.append("\n\n");
        }
        var downstream = spec.owners().get("downstream");
        if (downstream != null && !downstream.isEmpty()) {
            sb.append("**下游消费方:** ");
            sb.append(String.join(", ", downstream));
            sb.append("\n\n");
        }

        sb.append("## 测试用例\n\n");
        if (!spec.testCases().isEmpty()) {
            sb.append("| 用例 | 输入 | 期望输出 |\n");
            sb.append("|------|------|----------|\n");
            for (var tc : spec.testCases()) {
                var name = tc.getOrDefault("name", "-").toString();
                var inputs = tc.getOrDefault("inputs", "-").toString();
                var expected = tc.getOrDefault("expected_output", "-").toString();
                sb.append("| ").append(name).append(" | ").append(inputs)
                        .append(" | ").append(expected).append(" |\n");
            }
        } else {
            sb.append("(无预置测试用例)\n");
        }
        sb.append("\n");

        sb.append("## 引擎选择\n\n");
        if (spec.engineDecision() != null) {
            sb.append("**引擎:** ").append(spec.engineDecision().recommended()).append("\n");
            sb.append("**理由:** ").append(spec.engineDecision().reasoning()).append("\n");
        }
        sb.append("\n---\n_由 Data Agent 自动生成_");

        return sb.toString();
    }

    /** Generate ticket/change-request markdown template. */
    String generateTicketTemplate(Spec spec, String code, String targetName, String engine) {
        var sb = new StringBuilder();
        sb.append("# 上线工单 — ").append(targetName).append("\n\n");

        sb.append("| 字段 | 值 |\n");
        sb.append("|------|-----|\n");
        sb.append("| **任务名** | ").append(targetName).append(" |\n");
        sb.append("| **引擎** | ").append(engine).append(" |\n");
        sb.append("| **生成时间** | ").append(LocalDateTime.now().format(DT_FMT)).append(" |\n");

        var target = spec.target();
        sb.append("| **时效性** | ").append(target != null ? target.timeliness() : "-").append(" |\n");
        sb.append("| **状态** | 待 review |\n\n");

        sb.append("## 业务口径\n\n");
        sb.append(target != null && !target.businessDefinition().isEmpty()
                ? target.businessDefinition() : "(待确认)");
        sb.append("\n\n");

        sb.append("## 提交方式\n\n");
        sb.append("一次性 job 提交脚本已生成，执行方式:\n\n");
        sb.append("```bash\n");
        sb.append("# 将此脚本在目标集群上执行\n");
        sb.append("chmod +x submit_").append(sanitizeClassName(targetName)).append(".sh\n");
        sb.append("./submit_").append(sanitizeClassName(targetName)).append(".sh\n");
        sb.append("```\n\n");

        sb.append("## 验证清单\n\n");
        sb.append("- [ ] 代码 review 通过\n");
        sb.append("- [ ] 测试用例 (").append(spec.testCases().size()).append(" 条) 验证通过\n");
        sb.append("- [ ] Dry-run 结果确认\n");
        sb.append("- [ ] 上游数据 owner 知会\n");
        sb.append("- [ ] 下游消费方通知\n\n");

        sb.append("## 回滚方案\n\n");
        sb.append("若输出数据异常:\n");
        sb.append("1. 删除输出表 `").append(targetName).append("`\n");
        sb.append("2. 修正代码后重新提交\n");
        sb.append("3. 必要时通知下游消费方\n\n");

        sb.append("---\n_由 Data Agent 自动生成_");
        return sb.toString();
    }

    private String escapeForShell(String s) {
        return s.replace("\\", "\\\\")
                .replace("'", "'\\''")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }

    private String sanitizeClassName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String sha256(String input) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            var bytes = md.digest(input.getBytes());
            var sb = new StringBuilder();
            for (var b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
```

- [ ] **Step 4: 运行 SchedulerToolTest**

```bash
mvn test -Dtest=SchedulerToolTest
```

期望: 9 passed。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/tools/SchedulerTool.java src/test/java/com/wireless/agent/tools/SchedulerToolTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m5): add SchedulerTool generating PR template, ticket, and one-shot submission script"
```

---

### Task 6: AgentCore — SchedulerTool + TraceRecorder 接入

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/core/AgentCore.java`
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/core/AgentCoreTest.java`

- [ ] **Step 1: 在 AgentCoreTest.java 追加 deploy 流程测试**

```java
@Test
void shouldGenerateDeployArtifactsWhenCodegenDone() {
    var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
            "thrift://nonexistent:9999", "da-spark-master", "da-flink-jobmanager",
            new DomainKnowledgeBase());
    agent.processMessage("弱覆盖小区统计"); // goes through to codegen_done

    var result = agent.confirmDeploy();
    assertThat(result.get("next_action")).isEqualTo("deployed");
    assertThat(result.get("submit_script").toString()).contains("spark-submit");
    assertThat(result.get("pr_template").toString()).contains("弱覆盖");
    assertThat(result.get("ticket_template").toString()).contains("上线工单");
}

@Test
void shouldRejectDeployWhenNotCodegenDone() {
    var agent = new AgentCore(null);
    // state is GATHERING, not CODEGEN_DONE
    var result = agent.confirmDeploy();
    assertThat(result.get("next_action")).isEqualTo("deploy_failed");
    assertThat(result.get("error").toString()).contains("not ready");
}

@Test
void shouldRecordTracesAcrossSession() {
    var tmpDir = java.nio.file.Files.createTempDirectory("trace-test");
    try {
        var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
                "thrift://nonexistent:9999", "da-spark-master", "da-flink-jobmanager",
                new DomainKnowledgeBase());
        agent.enableTrace(tmpDir, "trace-test-user");

        agent.processMessage("弱覆盖统计");
        var traceFile = tmpDir.resolve(agent.getSessionId() + ".json");
        assertThat(Files.exists(traceFile)).isTrue();
    } finally {
        deleteRecursively(tmpDir);
    }
}

private void deleteRecursively(Path dir) {
    if (dir == null || !Files.exists(dir)) return;
    try (var s = Files.walk(dir)) {
        s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try { Files.delete(p); } catch (Exception ignored) {}
        });
    } catch (Exception ignored) {}
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=AgentCoreTest
```

期望: 3 个新测试编译失败（confirmDeploy / enableTrace / getSessionId 方法不存在）。

- [ ] **Step 3: 修改 AgentCore.java — 添加 SchedulerTool + TraceRecorder 字段**

在现有字段区域添加：

```java
private final SchedulerTool schedulerTool;
private TraceRecorder traceRecorder;
private String sessionId;
```

在所有构造函数末尾（`this.clarifyTool = ...` 之后）添加：

```java
this.schedulerTool = new SchedulerTool();
this.sessionId = "session-" + System.currentTimeMillis();
```

- [ ] **Step 4: 添加 confirmDeploy 和 enableTrace 方法**

在 `executeTools` 方法之后添加：

```java
/** Enable trace recording for this session. */
public void enableTrace(Path traceDir, String user) {
    this.traceRecorder = new TraceRecorder(traceDir, sessionId, user);
}

/** Get the current session ID. */
public String getSessionId() { return sessionId; }

/** Generate deploy artifacts after codegen is done and user confirms. */
@SuppressWarnings("unchecked")
public Map<String, Object> confirmDeploy() {
    if (spec.state() != Spec.SpecState.CODEGEN_DONE) {
        return Map.of(
            "next_action", "deploy_failed",
            "error", "Spec not ready for deployment: state=" + spec.state().value(),
            "submit_script", "",
            "pr_template", "",
            "ticket_template", ""
        );
    }

    // Retrieve code from spec evidence or last codegen result
    var code = lastGeneratedCode;
    if (code == null || code.isBlank()) {
        return Map.of(
            "next_action", "deploy_failed",
            "error", "No generated code available to deploy",
            "submit_script", "",
            "pr_template", "",
            "ticket_template", ""
        );
    }

    // Populate deployment config if not set
    if (spec.engineDecision() != null
            && spec.engineDecision().deployment() == null) {
        var current = spec.engineDecision();
        spec.engineDecision(new Spec.EngineDecision(
                current.recommended(), current.reasoning(),
                current.userOverridden(),
                Map.of("submission_mode", "one_shot",
                       "generated_at", java.time.LocalDateTime.now()
                               .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME))));
    }

    var result = schedulerTool.run(spec, code);
    if (!result.success()) {
        return Map.of(
            "next_action", "deploy_failed",
            "error", result.error(),
            "submit_script", "",
            "pr_template", "",
            "ticket_template", ""
        );
    }

    var data = (Map<String, Object>) result.data();

    // Record final artifact in trace
    if (traceRecorder != null) {
        var codeHash = data.getOrDefault("code_hash", "").toString();
        traceRecorder.recordFinalArtifact(code, codeHash, "", "");
        traceRecorder.finish("deployed");
    }

    var response = new LinkedHashMap<String, Object>();
    response.put("next_action", "deployed");
    response.put("submit_script", data.getOrDefault("submit_script", ""));
    response.put("pr_template", data.getOrDefault("pr_template", ""));
    response.put("ticket_template", data.getOrDefault("ticket_template", ""));
    response.put("code_hash", data.getOrDefault("code_hash", ""));
    response.put("engine", spec.engineDecision().recommended());
    response.put("turn", turn);
    return response;
}
```

- [ ] **Step 5: 添加 lastGeneratedCode 字段并在 executeTools 中保存**

在 AgentCore 字段中添加：

```java
private String lastGeneratedCode;
```

在 `executeTools` 方法中，`code` 变量提取之后（约第 321 行，`var code = codegenData.getOrDefault("code", "").toString();` 之后）添加：

```java
this.lastGeneratedCode = code;
```

- [ ] **Step 6: 在 processMessage 中接入 trace 埋点**

在 `processMessage` 方法的 `callLlmExtract` 调用后添加 spec 快照记录：

```java
var intent = callLlmExtract(userMessage);
applyIntent(intent);

// Record spec snapshot in trace
if (traceRecorder != null) {
    try {
        traceRecorder.recordSpecSnapshot(turn, MAPPER.writeValueAsString(spec));
    } catch (Exception ignored) {}
}
```

在 `processMessage` 的每个 return 之前添加 conversation event 记录。提取 response 到变量，记录后返回。修改方法最后部分：在每个 return Map.of(...) 之前，改为先构建 response Map，记录 trace conversation，再返回。

由于现有流程分散，我们用一个辅助方法包装所有 return：

在 `processMessage` 方法开头添加：

```java
turn++;
// Record spec at start of turn
if (traceRecorder != null) {
    try {
        traceRecorder.recordSpecSnapshot(turn, MAPPER.writeValueAsString(spec));
    } catch (Exception ignored) {}
}
```

然后在每个 return 点之前添加 `traceRecorder.recordConversation(...)` 调用记录本轮对话。

修改 executeTools 调用后的 return: `return executeTools()` 改为：

```java
var toolsResult = executeTools();
if (traceRecorder != null) {
    var summary = toolsResult.getOrDefault("next_action", "unknown").toString();
    traceRecorder.recordConversation(turn, userMessage, summary);
    try {
        traceRecorder.recordSpecSnapshot(turn, MAPPER.writeValueAsString(spec));
    } catch (Exception ignored) {}
}
return toolsResult;
```

对于 clarifying 分支，修改为：

```java
var clarifyResponse = Map.of( ... );
if (traceRecorder != null) {
    var summary = "反问: " + clarifyResponse.get("clarifying_question");
    traceRecorder.recordConversation(turn, userMessage, summary);
}
return clarifyResponse;
```

对 ask_clarifying 分支同理。

在 `executeTools` 末尾，codegen/validation/sandbox 各步添加 tool call 记录。在 `executeTools` 的 metadata lookup 循环中（for each source）添加：

```java
if (traceRecorder != null) {
    var tbl = src.binding().getOrDefault("table_or_topic", "").toString();
    traceRecorder.recordToolCall("metadata", tbl, 0, "success", null);
}
```

在 profiler 调用后添加：

```java
if (traceRecorder != null && profileResult.success()) {
    traceRecorder.recordToolCall("profiler", "profile", 0, "success", null);
}
```

在 codegen、validator、sandbox 调用后同样添加 tool call 记录。

- [ ] **Step 7: 运行全部测试确认无回归**

```bash
mvn test
```

期望: 全部通过（含 AgentCoreTest 新增测试），无回归。

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/wireless/agent/core/AgentCore.java src/test/java/com/wireless/agent/core/AgentCoreTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m5): wire SchedulerTool + TraceRecorder into AgentCore with confirmDeploy flow"
```

---

### Task 7: EngineDecision.deployment + Spec 最终态填充

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/core/AgentCore.java`

- [ ] **Step 1: 在 executeTools 的引擎选择之后填充 deployment**

在 `executeTools` 方法中，引擎选择步骤（`spec.engineDecision(EngineSelector.select(spec));` 之后）添加：

```java
// Populate deployment config for the selected engine
spec.engineDecision(new Spec.EngineDecision(
    spec.engineDecision().recommended(),
    spec.engineDecision().reasoning(),
    spec.engineDecision().userOverridden(),
    Map.of(
        "submission_mode", "one_shot",
        "target_container", spec.engineDecision().recommended().contains("flink")
                ? flinkContainer : sparkContainer
    )));
```

（需要访问 sparkContainer/flinkContainer 成员，这些构造函数已经设置。需要添加字段存储。）

在 AgentCore 字段区域添加：

```java
private final String sparkContainer;
private final String flinkContainer;
```

修改所有构造函数，将已有的 sparkContainer/flinkContainer 存入成员字段。注意现有构造函数已经接收这些参数，但传给 SandboxTool 后没有存储。在所有构造函数中添加：

```java
this.sparkContainer = sparkContainer;
this.flinkContainer = flinkContainer;
```

- [ ] **Step 2: 运行全部测试**

```bash
mvn test
```

期望: 全部通过，无回归。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wireless/agent/core/AgentCore.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m5): populate EngineDecision.deployment with submission mode and target container"
```

---

### Task 8: Main + Config — deploy 标志位与配置项

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/Main.java`
- Modify: `D:/agent-code/data-agent/src/main/resources/agent.properties`

- [ ] **Step 1: 修改 agent.properties 添加 M5 配置项**

在文件末尾添加：

```properties
# M5 — Scheduler & Deploy
deploy.submit_script_dir=./output/submit-scripts
deploy.pr_template_dir=./output/pr-templates
deploy.ticket_template_dir=./output/tickets
scheduler.submission_mode=one_shot
scheduler.default_queue=root.prod.wireless

# M5 — Trace
trace.output_dir=./.data-agent/traces
trace.enabled=true
```

- [ ] **Step 2: 修改 Main.java 添加 --deploy 和 --trace 标志位**

修改 main 方法中的参数解析：

```java
var deploy = false;
var traceEnabled = false;
for (var arg : args) {
    if ("--demo".equals(arg)) demo = true;
    if ("--no-llm".equals(arg)) noLlm = true;
    if ("--reverse".equals(arg)) reverse = true;
    if ("--deploy".equals(arg)) deploy = true;
    if ("--trace".equals(arg)) traceEnabled = true;
}
```

修改 `runInteractive` 方法，在 agent 创建后添加 trace 启用：

```java
if (traceEnabled || "true".equals(System.getenv().getOrDefault("TRACE_ENABLED",
        props.getProperty("trace.enabled", "true")))) {
    var traceDir = Path.of(System.getenv().getOrDefault("TRACE_OUTPUT_DIR",
            props.getProperty("trace.output_dir", "./.data-agent/traces")));
    agent.enableTrace(traceDir, System.getProperty("user.name", "anonymous"));
    System.out.println("[Info] Trace recording to: " + traceDir);
}
```

修改交互循环，在 `code_done` / `dry_run_ok` case 后添加 deploy 询问：

在 `switch` 的 `case "code_done"` 和 `case "dry_run_ok"` (以及简并的 `default` 处理中) 之后：

```java
case "code_done", "dry_run_ok" -> {
    var turn = result.get("turn");
    if (turn != null) {
        System.out.println("[收敛] 经过 " + turn + " 轮反问,规格已就绪");
    }
    System.out.println("[引擎] " + result.get("engine") + " — " + result.get("reasoning"));
    System.out.println("[代码]\n" + result.get("code"));
    if (result.get("preview") != null && !result.get("preview").toString().isEmpty()) {
        System.out.println("[预览]\n" + result.get("preview"));
    }
    // Ask for deploy confirmation
    if (deploy) {
        System.out.println();
        System.out.println("─".repeat(40));
        System.out.println("是否生成上线产物? (yes / 直接回车跳过)");
        System.out.print("> ");
        var confirm = scanner.nextLine().trim().toLowerCase();
        if ("yes".equals(confirm) || "y".equals(confirm)) {
            var deployResult = agent.confirmDeploy();
            if ("deployed".equals(deployResult.get("next_action"))) {
                System.out.println();
                System.out.println("=== 提交脚本 ===");
                System.out.println(deployResult.get("submit_script"));
                System.out.println();
                System.out.println("=== PR 模板 ===");
                System.out.println(deployResult.get("pr_template"));
                System.out.println();
                System.out.println("=== 上线工单 ===");
                System.out.println(deployResult.get("ticket_template"));
                System.out.println();
                System.out.println("[部署] 产物已生成 (code_hash: " + deployResult.get("code_hash") + ")");
            } else {
                System.out.println("[部署失败] " + deployResult.get("error"));
            }
        }
    }
}
```

添加必要的 import：

```java
import java.nio.file.Path;
```

同时在 `runDemo` 方法中，如果 `deploy` 开关打开，则在每个场景结束后也调用 `confirmDeploy`：

```java
if (deploy && "code_done".equals(result.get("next_action"))) {
    var deployResult = agent.confirmDeploy();
    System.out.println("  [部署] " + deployResult.get("next_action"));
}
```

- [ ] **Step 3: 编译验证**

```bash
mvn compile
```

期望: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/wireless/agent/Main.java src/main/resources/agent.properties
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m5): add --deploy flag and trace config to Main, wire confirmDeploy into interactive mode"
```

---

### Task 9: 集成测试 — 闭环端到端

**Files:**
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/IntegrationTest.java`

- [ ] **Step 1: 在 IntegrationTest.java 追加闭环测试**

```java
@Test
void shouldCompleteFullLoopIncludingDeploy() {
    var agent = new AgentCore(
        new FakeLLMForIntegration(),
        Spec.TaskDirection.FORWARD_ETL
    );
    // Run through to codegen_done
    var result = agent.processMessage("给我近30天每个区县5G弱覆盖小区清单");
    assertThat(result.get("next_action")).isIn("code_done", "dry_run_ok", "sandbox_failed");
    assertThat(agent.spec().state()).isEqualTo(Spec.SpecState.CODEGEN_DONE);

    // Confirm deploy
    var deployResult = agent.confirmDeploy();
    assertThat(deployResult.get("next_action")).isEqualTo("deployed");
    assertThat(deployResult.get("submit_script").toString()).contains("spark-submit");
    assertThat(deployResult.get("pr_template").toString())
            .contains("弱覆盖", "## 变更内容");
    assertThat(deployResult.get("ticket_template").toString())
            .contains("上线工单", "回滚方案");
    assertThat(deployResult.get("code_hash").toString()).isNotEmpty();
}

@Test
void shouldGenerateFlinkDeployArtifacts() {
    var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
            "thrift://nonexistent:9999", "da-spark-master", "da-flink-jobmanager",
            new DomainKnowledgeBase());
    agent.processMessage("实时监控最近1小时每个小区的切换失败次数");

    var deployResult = agent.confirmDeploy();
    if ("deployed".equals(deployResult.get("next_action"))) {
        // For Flink tasks, submit script should use sql-client
        var script = deployResult.get("submit_script").toString();
        assertThat(script.toLowerCase()).containsAnyOf("flink", "sql-client");
    }
}

@Test
void shouldRejectDeployWhenStateIsClarifying() {
    var agent = new AgentCore(null);
    // Don't process any message — state is still GATHERING
    var result = agent.confirmDeploy();
    assertThat(result.get("next_action")).isEqualTo("deploy_failed");
}

@Test
void shouldRecordTraceForFullSession() throws Exception {
    var tmpDir = Files.createTempDirectory("trace-integration-test");
    try {
        var agent = new AgentCore(
            new FakeLLMForIntegration(),
            Spec.TaskDirection.FORWARD_ETL
        );
        agent.enableTrace(tmpDir, "integration-test-user");

        agent.processMessage("弱覆盖小区统计");
        agent.confirmDeploy();

        var replay = new com.wireless.agent.trace.TraceReplay();
        var sessions = replay.listSessions(tmpDir);
        assertThat(sessions).isNotEmpty();

        var trace = replay.load(tmpDir, sessions.get(0));
        assertThat(trace.finalState).isIn("deployed", "codegen_done");
        assertThat(trace.events).isNotEmpty();

        // Verify event type distribution
        var counts = replay.eventTypeCounts(trace);
        assertThat(counts).isNotEmpty();
    } finally {
        try (var s = Files.walk(tmpDir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (Exception ignored) {}
            });
        }
    }
}
```

需要添加的 import：

```java
import java.nio.file.Files;
import java.nio.file.Path;
```

- [ ] **Step 2: 运行集成测试**

```bash
mvn test -Dtest=IntegrationTest
```

期望: 21 passed（原有 17 + 新增 4）。

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/wireless/agent/IntegrationTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "test(m5): add closed-loop deploy integration tests with trace recording verification"
```

---

### Task 10: README 更新与终验

**Files:**
- Modify: `D:/agent-code/data-agent/README.md`

- [ ] **Step 1: 更新 README.md 追加 M5 章节**

在 M4.5 段落后追加：

```markdown
## M5 -- Closed-Loop Deployment

**端到端闭环:** 从对话到代码、dry-run、用户确认后生成上线产物:

| Component | Description |
|-----------|-------------|
| SchedulerTool | 生成一次性 spark-submit/flink run 提交脚本 + PR 模板 + 上线工单 |
| UserPreferencesStore | JSON 文件持久化用户偏好(引擎选择、默认队列) |
| TraceRecorder | 会话级事件录制:对话/Spec演进/工具调用/LLM调用/最终产物 |
| TraceReplay | 按 session_id 加载和重放历史会话轨迹 |

**Usage:**

```bash
# 交互模式 + 闭环部署 (代码生成后询问是否生成上线产物)
mvn exec:java -Dexec.mainClass="com.wireless.agent.Main" -- --deploy

# 交互模式 + 会话追踪
mvn exec:java -Dexec.mainClass="com.wireless.agent.Main" -- --deploy --trace

# Demo 模式 + 闭环部署
mvn exec:java -Dexec.mainClass="com.wireless.agent.Main" -- --demo --no-llm --deploy
```

**生成产物示例:**

```
=== 提交脚本 ===
#!/bin/bash
spark-sql --master yarn --deploy-mode cluster --name "弱覆盖按区县统计" ...

=== PR 模板 ===
# Data Agent Generated: 弱覆盖按区县统计
## 变更内容
...

=== 上线工单 ===
# 上线工单 — 弱覆盖按区县统计
| 字段 | 值 |
...
```

**Trace 文件位置:** `.data-agent/traces/<session_id>.json`

**UserPreferences 文件位置:** `.data-agent/prefs/<user_id>.json`
```

- [ ] **Step 2: 最终验证**

```bash
mvn test
```

期望: BUILD SUCCESS, 全部测试通过（约 160+ tests）。

- [ ] **Step 3: Commit**

```bash
git add README.md
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "docs(m5): add closed-loop deployment quickstart to readme"
```

---

## M5 完成标准（DoD）

- [ ] `mvn test` 全部测试通过，无回归
- [ ] UserPreferencesStore: 按 userId 持久化键值偏好，跨实例读取
- [ ] EngineSelector: 支持从 UserPreferencesStore 查询团队约定引擎进行覆盖
- [ ] SessionTrace: 包含所有事件类型（Conversation/SpecSnapshot/ToolCall/LlmCall/Artifact）
- [ ] TraceRecorder: 会话结束时输出完整 JSON trace
- [ ] TraceReplay: 可列出/加载/汇总历史 trace
- [ ] SchedulerTool: 生成三种引擎对应的 spark-submit / sql-client / flink run 脚本
- [ ] SchedulerTool: 生成含 owners/@mentions/test_cases 的 PR 模板
- [ ] SchedulerTool: 生成含验证清单/回滚方案的上线工单模板
- [ ] AgentCore.confirmDeploy: Codgen_done 状态后才允许部署；部署失败返回明确错误
- [ ] AgentCore: Trace 埋点覆盖 processMessage 关键阶段
- [ ] Main: `--deploy` 标志位，代码生成后询问用户确认并输出三种产物
- [ ] Main: `--trace` 标志位，启用会话轨迹录制
- [ ] agent.properties: deploy/trace 配置项
- [ ] 闭环集成测试: 正向 ETL 全流程 + deploy 产出验证 + trace 录制验证

---

## 后续

M5 完成后 → M6 质量基线（E2E Eval Set 30–50 道 + 持续评估流水线）。
