# M0b Agent 骨架与 Mock Loop 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal**：构建 Agent Core（ReAct loop）+ Spec Accumulator + Mock MetadataTool + CodegenTool（仅 Spark SQL），走通"NL 输入 → 工具调用 → 代码输出"的端到端回路；用 1-2 个无线评估典型场景 hardcode 验证。

**Architecture**：Java 17 + Maven 项目，Agent Core 是编排者（ReAct loop），Spec Accumulator 是 POJO 数据模型（唯一事实源），Tools 是实现 Tool 接口的无状态类（Mock MetadataTool 返回硬编码无线表 schema，CodegenTool 通过 LLM 将 spec 翻译为 Spark SQL），DeepSeek API 走 OkHttp + Jackson（OpenAI 兼容协议），CLI 以 stdin/stdout 对话模式运行。

**Tech Stack**：Java 17+, Maven 3.8+, Jackson 2.18, OkHttp 4.12, JUnit 5, AssertJ, Mockito, TDD

---

## 文件结构（M0b 完成后的新增目录形态）

```
data-agent/
├── pom.xml
├── src/
│   └── main/java/com/wireless/agent/
│       ├── Main.java                   # CLI 入口：stdin/stdout 对话 + --demo
│       ├── core/
│       │   ├── AgentCore.java          # Agent Core: ReAct loop + tool dispatch
│       │   ├── Spec.java               # Spec / SpecAccumulator POJOs
│       │   ├── EngineSelector.java     # 简易 EngineSelector（基于 spec 信号推荐引擎）
│       │   └── Prompts.java            # LLM system/user prompt 模板
│       ├── tools/
│       │   ├── Tool.java               # Tool 接口
│       │   ├── ToolResult.java         # Tool 返回结果 record
│       │   ├── MockMetadataTool.java   # Mock MetadataTool（hardcode 无线表 schema）
│       │   └── CodegenTool.java        # CodegenTool（Spark SQL 生成）
│       └── llm/
│           └── DeepSeekClient.java     # DeepSeek API 客户端（OpenAI 兼容）
└── src/test/java/com/wireless/agent/
    ├── core/
    │   ├── SpecTest.java
    │   ├── EngineSelectorTest.java
    │   ├── PromptsTest.java
    │   └── AgentCoreTest.java
    ├── tools/
    │   ├── MockMetadataToolTest.java
    │   └── CodegenToolTest.java
    ├── llm/
    │   └── DeepSeekClientTest.java
    └── IntegrationTest.java
```

---

### Task 1：Maven 项目骨架

**Files:**
- Create: `D:/agent-code/data-agent/pom.xml`
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/` (package dirs)
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/` (package dirs)
- Modify: `D:/agent-code/data-agent/.gitignore`

- [ ] **Step 1**：在 `pom.xml` 写入：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.wireless</groupId>
    <artifactId>data-agent</artifactId>
    <version>0.1.0</version>
    <packaging>jar</packaging>

    <name>data-agent</name>
    <description>Wireless network perception data agent</description>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <!-- JSON -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>2.18.2</version>
        </dependency>

        <!-- HTTP client -->
        <dependency>
            <groupId>com.squareup.okhttp3</groupId>
            <artifactId>okhttp</artifactId>
            <version>4.12.0</version>
        </dependency>

        <!-- .env file support -->
        <dependency>
            <groupId>io.github.cdimascio</groupId>
            <artifactId>dotenv-java</artifactId>
            <version>3.0.2</version>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.11.4</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <version>3.27.3</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <version>5.15.2</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-junit-jupiter</artifactId>
            <version>5.15.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.2</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <version>3.4.2</version>
                <configuration>
                    <archive>
                        <manifest>
                            <mainClass>com.wireless.agent.Main</mainClass>
                        </manifest>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2**：创建所有包目录：

```bash
cd "D:/agent-code/data-agent"
mkdir -p src/main/java/com/wireless/agent/core
mkdir -p src/main/java/com/wireless/agent/tools
mkdir -p src/main/java/com/wireless/agent/llm
mkdir -p src/test/java/com/wireless/agent/core
mkdir -p src/test/java/com/wireless/agent/tools
mkdir -p src/test/java/com/wireless/agent/llm
```

- [ ] **Step 3**：更新 `.gitignore` 追加：

```
# Java / Maven
*target/
*.class
*.jar
*.war
dependency-reduced-pom.xml
```

- [ ] **Step 4**：验证 Maven 可用并编译空项目：

```bash
cd "D:/agent-code/data-agent"
mvn compile
```

期望：`BUILD SUCCESS`（编译成功，无源码）。

- [ ] **Step 5**：运行空测试确认 Maven + JUnit 5 可用：

```bash
mvn test
```

期望：`BUILD SUCCESS`，`No tests to run`。

- [ ] **Step 6**：commit

```bash
cd "D:/agent-code/data-agent"
git add pom.xml .gitignore src/
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "chore(m0b): maven project skeleton with jackson, okhttp, junit5"
```

---

### Task 2：DeepSeek API Client（LLM 客户端）

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/llm/DeepSeekClient.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/llm/DeepSeekClientTest.java`

- [ ] **Step 1**：在 `DeepSeekClientTest.java` 写失败测试：

```java
package com.wireless.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeepSeekClientTest {

    @Mock
    private OkHttpClient httpClient;

    @Mock
    private Call call;

    @Mock
    private Response response;

    @Test
    void shouldInitFromEnvOrDefaults() {
        var client = new DeepSeekClient(
                "https://api.deepseek.com/v1",
                "sk-test",
                "deepseek-chat"
        );
        assertThat(client.model()).isEqualTo("deepseek-chat");
        assertThat(client.apiBase()).isEqualTo("https://api.deepseek.com/v1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSendCorrectPayload() throws Exception {
        var client = new DeepSeekClient(
                "https://api.deepseek.com/v1",
                "sk-test",
                "deepseek-chat"
        );

        // Build a mock response body
        var respBody = Map.of(
                "choices", List.of(
                        Map.of("message", Map.of("content", "PONG"))
                )
        );
        var respJson = new ObjectMapper().writeValueAsString(respBody);
        var resp = new okhttp3.Response.Builder()
                .request(new Request.Builder().url("https://api.deepseek.com/v1/chat/completions").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(respJson, MediaType.parse("application/json")))
                .build();

        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(resp);

        // Inject mock client
        var messages = List.of(Map.of("role", "user", "content", "PING"));
        var result = client.chat(httpClient, messages);
        assertThat(result).isEqualTo("PONG");
    }

    @Test
    void shouldReturnErrorPrefixOnFailure() throws Exception {
        var client = new DeepSeekClient(
                "https://api.deepseek.com/v1",
                "sk-test",
                "deepseek-chat"
        );

        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenThrow(new IOException("Connection refused"));

        var messages = List.of(Map.of("role", "user", "content", "hi"));
        var result = client.chat(httpClient, messages);
        assertThat(result).startsWith("[ERROR]");
    }
}
```

- [ ] **Step 2**：运行测试确认失败：

```bash
cd "D:/agent-code/data-agent"
mvn test -pl . -Dtest=DeepSeekClientTest
```

期望：编译错误（类未定义）。

- [ ] **Step 3**：在 `DeepSeekClient.java` 写入实现：

```java
package com.wireless.agent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public record DeepSeekClient(String apiBase, String apiKey, String model) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    public DeepSeekClient() {
        this(
            System.getenv().getOrDefault("DEEPSEEK_API_BASE", ""),
            System.getenv().getOrDefault("DEEPSEEK_API_KEY", ""),
            System.getenv().getOrDefault("DEEPSEEK_MODEL", "deepseek-chat")
        );
    }

    public DeepSeekClient(String apiBase, String apiKey, String model) {
        this.apiBase = apiBase;
        this.apiKey = apiKey;
        this.model = model;
    }

    /** Create a default OkHttpClient (used in production path). */
    public static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public String chat(List<Map<String, String>> messages) {
        return chat(defaultHttpClient(), messages, 1024, 0.1);
    }

    public String chat(OkHttpClient httpClient, List<Map<String, String>> messages) {
        return chat(httpClient, messages, 1024, 0.1);
    }

    @SuppressWarnings("unchecked")
    public String chat(OkHttpClient httpClient, List<Map<String, String>> messages,
                       int maxTokens, double temperature) {
        try {
            var body = Map.of(
                "model", model,
                "messages", messages,
                "max_tokens", maxTokens,
                "temperature", temperature
            );
            var json = MAPPER.writeValueAsString(body);
            var request = new Request.Builder()
                    .url(apiBase.replaceAll("/+$", "") + "/chat/completions")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(json, JSON_MEDIA))
                    .build();

            try (var response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    var errBody = response.body() != null
                            ? response.body().string() : response.message();
                    return "[ERROR] HTTP " + response.code() + ": " + errBody;
                }
                var respBody = MAPPER.readValue(response.body().string(), Map.class);
                var choices = (List<Map<String, Object>>) respBody.get("choices");
                var message = (Map<String, String>) choices.get(0).get("message");
                return message.get("content");
            }
        } catch (IOException e) {
            return "[ERROR] " + e.getMessage();
        }
    }
}
```

- [ ] **Step 4**：运行测试确认通过：

```bash
mvn test -Dtest=DeepSeekClientTest
```

期望：3 passed。

- [ ] **Step 5**：commit

```bash
cd "D:/agent-code/data-agent"
git add src/main/java/com/wireless/agent/llm/DeepSeekClient.java \
        src/test/java/com/wireless/agent/llm/DeepSeekClientTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0b): deepseek api client with okhttp + jackson"
```

---

### Task 3：Spec / SpecAccumulator 数据模型

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/core/Spec.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/core/SpecTest.java`

- [ ] **Step 1**：在 `SpecTest.java` 写失败测试：

```java
package com.wireless.agent.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpecTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void shouldDefaultStateToGathering() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        assertThat(spec.state()).isEqualTo(Spec.SpecState.GATHERING);
    }

    @Test
    void shouldDefaultNetworkContextValues() {
        var ctx = new Spec.NetworkContext();
        assertThat(ctx.neGrain()).isEqualTo("cell");
        assertThat(ctx.rat()).isEqualTo("5G_SA");
    }

    @Test
    void shouldProgressToReadyWhenComplete() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec()
                .name("weak_cov_cells")
                .businessDefinition("弱覆盖小区")
                .grain("(cell_id, day)"));
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("mr_main")
                        .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))
                        .confidence(0.9)
        ));
        spec.engineDecision(new Spec.EngineDecision("spark_sql", "批源、简单聚合"));
        spec.advanceState();
        assertThat(spec.state()).isEqualTo(Spec.SpecState.READY_TO_CODEGEN);
    }

    @Test
    void shouldNotProgressWithoutTarget() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.advanceState();
        assertThat(spec.state()).isEqualTo(Spec.SpecState.GATHERING);
    }

    @Test
    void shouldReturnFirstOpenQuestion() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.addQuestion("field_a", "什么是活跃用户？", List.of("A", "B"));
        spec.addQuestion("field_b", "时间粒度？", null);
        var q = spec.nextQuestion();
        assertThat(q).isNotNull();
        assertThat(q.get("question")).isEqualTo("什么是活跃用户？");
    }

    @Test
    void shouldSerializeEnumsAsSnakeCase() throws JsonProcessingException {
        var spec = new Spec(Spec.TaskDirection.REVERSE_SYNTHETIC);
        spec.state(Spec.SpecState.READY_TO_CODEGEN);
        var json = mapper.writeValueAsString(spec);
        assertThat(json).contains("\"task_direction\"");
        assertThat(json).contains("\"reverse_synthetic\"");
        assertThat(json).contains("\"ready_to_codegen\"");
    }
}
```

- [ ] **Step 2**：运行测试确认失败：

```bash
mvn test -Dtest=SpecTest
```

期望：编译错误（类未定义）。

- [ ] **Step 3**：在 `Spec.java` 写入实现：

```java
package com.wireless.agent.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Spec {

    // ─── Enums ────────────────────────────────────────

    public enum TaskDirection {
        FORWARD_ETL("forward_etl"),
        REVERSE_SYNTHETIC("reverse_synthetic");

        private final String value;
        TaskDirection(String v) { value = v; }

        @JsonValue
        public String value() { return value; }
    }

    public enum SpecState {
        GATHERING("gathering"),
        CLARIFYING("clarifying"),
        READY_TO_CODEGEN("ready_to_codegen"),
        CODEGEN_DONE("codegen_done"),
        FAILED("failed");

        private final String value;
        SpecState(String v) { value = v; }

        @JsonValue
        public String value() { return value; }
    }

    // ─── Inner model classes ──────────────────────────

    public static class NetworkContext {
        @JsonProperty("ne_grain")     private String neGrain = "cell";
        @JsonProperty("time_grain")   private String timeGrain = "15min";
        @JsonProperty("rat")          private String rat = "5G_SA";
        @JsonProperty("kpi_family")   private String kpiFamily = "coverage";

        public String neGrain() { return neGrain; }
        public void neGrain(String v) { neGrain = v; }
        public String timeGrain() { return timeGrain; }
        public void timeGrain(String v) { timeGrain = v; }
        public String rat() { return rat; }
        public void rat(String v) { rat = v; }
        public String kpiFamily() { return kpiFamily; }
        public void kpiFamily(String v) { kpiFamily = v; }
    }

    public static class TargetSpec {
        @JsonProperty("name")                  private String name = "";
        @JsonProperty("schema")                private List<Map<String, String>> schema = new ArrayList<>();
        @JsonProperty("grain")                 private String grain = "";
        @JsonProperty("timeliness")            private String timeliness = "batch_daily";
        @JsonProperty("business_definition")   private String businessDefinition = "";
        @JsonProperty("target_storage")        private Map<String, String> targetStorage;

        public String name() { return name; }
        public TargetSpec name(String v) { name = v; return this; }
        public List<Map<String, String>> schema() { return schema; }
        public TargetSpec schema(List<Map<String, String>> v) { schema = v; return this; }
        public String grain() { return grain; }
        public TargetSpec grain(String v) { grain = v; return this; }
        public String timeliness() { return timeliness; }
        public TargetSpec timeliness(String v) { timeliness = v; return this; }
        public String businessDefinition() { return businessDefinition; }
        public TargetSpec businessDefinition(String v) { businessDefinition = v; return this; }
        public Map<String, String> targetStorage() { return targetStorage; }
        public TargetSpec targetStorage(Map<String, String> v) { targetStorage = v; return this; }
    }

    public static class SourceBinding {
        @JsonProperty("role")        private String role = "";
        @JsonProperty("binding")     private Map<String, Object> binding = new LinkedHashMap<>();
        @JsonProperty("confidence")  private double confidence;
        @JsonProperty("schema")      private List<Map<String, String>> schema_;

        public String role() { return role; }
        public SourceBinding role(String v) { role = v; return this; }
        public Map<String, Object> binding() { return binding; }
        public SourceBinding binding(Map<String, Object> v) { binding = v; return this; }
        public double confidence() { return confidence; }
        public SourceBinding confidence(double v) { confidence = v; return this; }
        public List<Map<String, String>> schema_() { return schema_; }
        public SourceBinding schema_(List<Map<String, String>> v) { schema_ = v; return this; }
    }

    public static class TransformStep {
        @JsonProperty("op")                   private String op = "";
        @JsonProperty("description")          private String description = "";
        @JsonProperty("inputs")               private List<String> inputs = new ArrayList<>();
        @JsonProperty("output_columns")       private List<Map<String, String>> outputColumns = new ArrayList<>();
        @JsonProperty("params")               private Map<String, Object> params;
        @JsonProperty("needs_clarification")  private String needsClarification;

        public String op() { return op; }
        public TransformStep op(String v) { op = v; return this; }
        public String description() { return description; }
        public TransformStep description(String v) { description = v; return this; }
        public List<String> inputs() { return inputs; }
        public TransformStep inputs(List<String> v) { inputs = v; return this; }
        public List<Map<String, String>> outputColumns() { return outputColumns; }
        public TransformStep outputColumns(List<Map<String, String>> v) { outputColumns = v; return this; }
        public Map<String, Object> params() { return params; }
        public TransformStep params(Map<String, Object> v) { params = v; return this; }
        public String needsClarification() { return needsClarification; }
        public TransformStep needsClarification(String v) { needsClarification = v; return this; }
    }

    public record EngineDecision(
            @JsonProperty("recommended")       String recommended,
            @JsonProperty("reasoning")         String reasoning,
            @JsonProperty("user_overridden")   Boolean userOverridden,
            @JsonProperty("deployment")        Map<String, String> deployment) {

        public EngineDecision(String recommended, String reasoning) {
            this(recommended, reasoning, null, null);
        }
    }

    public record Evidence(
            @JsonProperty("type")      String type,
            @JsonProperty("source")    String source,
            @JsonProperty("findings")  Map<String, Object> findings) {}

    // ─── Spec fields ─────────────────────────────────

    @JsonProperty("task_direction")  private TaskDirection taskDirection;
    @JsonProperty("state")           private SpecState state = SpecState.GATHERING;
    @JsonProperty("network_context") private NetworkContext networkContext = new NetworkContext();
    @JsonProperty("target")          private TargetSpec target;
    @JsonProperty("sources")         private List<SourceBinding> sources = new ArrayList<>();
    @JsonProperty("transformations") private List<TransformStep> transformations = new ArrayList<>();
    @JsonProperty("engine_decision") private EngineDecision engineDecision;
    @JsonProperty("open_questions")  private List<Map<String, Object>> openQuestions = new ArrayList<>();
    @JsonProperty("evidence")        private List<Evidence> evidence = new ArrayList<>();
    @JsonProperty("owners")          private Map<String, List<String>> owners = new LinkedHashMap<>();
    @JsonProperty("test_cases")      private List<Map<String, Object>> testCases = new ArrayList<>();
    @JsonProperty("validate_through_target_pipeline") private boolean validateThroughTargetPipeline = true;

    public Spec() {}

    public Spec(TaskDirection dir) { this.taskDirection = dir; }

    // ─── Getters / fluent setters ────────────────────

    public TaskDirection taskDirection() { return taskDirection; }
    public SpecState state() { return state; }
    public Spec state(SpecState v) { state = v; return this; }
    public NetworkContext networkContext() { return networkContext; }
    public TargetSpec target() { return target; }
    public Spec target(TargetSpec v) { target = v; return this; }
    public List<SourceBinding> sources() { return sources; }
    public Spec sources(List<SourceBinding> v) { sources = v; return this; }
    public List<TransformStep> transformations() { return transformations; }
    public EngineDecision engineDecision() { return engineDecision; }
    public Spec engineDecision(EngineDecision v) { engineDecision = v; return this; }
    public List<Map<String, Object>> openQuestions() { return openQuestions; }
    public List<Evidence> evidence() { return evidence; }
    public Map<String, List<String>> owners() { return owners; }
    public List<Map<String, Object>> testCases() { return testCases; }
    public boolean validateThroughTargetPipeline() { return validateThroughTargetPipeline; }

    // ─── Methods ─────────────────────────────────────

    public SpecState advanceState() {
        if (state == SpecState.GATHERING) {
            if (target != null && !target.businessDefinition.isEmpty()) {
                state = SpecState.CLARIFYING;
            }
        } else if (state == SpecState.CLARIFYING) {
            if (target != null && !target.businessDefinition.isEmpty()
                    && !sources.isEmpty()
                    && engineDecision != null
                    && !engineDecision.recommended().isEmpty()) {
                state = SpecState.READY_TO_CODEGEN;
            }
        }
        return state;
    }

    public Map<String, Object> nextQuestion() {
        if (openQuestions.isEmpty()) return null;
        return openQuestions.get(0);
    }

    public void addQuestion(String fieldPath, String question, List<String> candidates) {
        var q = new LinkedHashMap<String, Object>();
        q.put("field_path", fieldPath);
        q.put("question", question);
        q.put("candidates", candidates != null ? candidates : List.of());
        openQuestions.add(q);
    }
}
```

- [ ] **Step 4**：运行测试：

```bash
mvn test -Dtest=SpecTest
```

期望：6 passed。

- [ ] **Step 5**：commit

```bash
cd "D:/agent-code/data-agent"
git add src/main/java/com/wireless/agent/core/Spec.java \
        src/test/java/com/wireless/agent/core/SpecTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0b): spec accumulator pojo model with jackson"
```

---

### Task 4：Tool 接口与 ToolResult

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/ToolResult.java`
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/Tool.java`

- [ ] **Step 1**：在 `ToolResult.java` 写入（record，无需单独测试）：

```java
package com.wireless.agent.tools;

import java.util.Map;

public record ToolResult(boolean success, Object data, String error,
                         Map<String, Object> evidence) {

    public ToolResult(boolean success, Object data, String error) {
        this(success, data, error, Map.of());
    }

    public static ToolResult ok(Object data) {
        return new ToolResult(true, data, "", Map.of());
    }

    public static ToolResult ok(Object data, Map<String, Object> evidence) {
        return new ToolResult(true, data, "", evidence);
    }

    public static ToolResult fail(String error) {
        return new ToolResult(false, null, error, Map.of());
    }

    public static ToolResult fail(String error, Object data) {
        return new ToolResult(false, data, error, Map.of());
    }
}
```

- [ ] **Step 2**：在 `Tool.java` 写入：

```java
package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;

/** Stateless tool. All state is in Spec, passed as input. */
public interface Tool {

    String name();
    String description();

    /** Execute the tool synchronously. */
    ToolResult run(Spec spec);
}
```

- [ ] **Step 3**：验证编译：

```bash
mvn compile
```

期望：`BUILD SUCCESS`。

- [ ] **Step 4**：commit

```bash
cd "D:/agent-code/data-agent"
git add src/main/java/com/wireless/agent/tools/ToolResult.java \
        src/main/java/com/wireless/agent/tools/Tool.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0b): tool interface and toolresult record"
```

---

### Task 5：Mock MetadataTool

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/MockMetadataTool.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/tools/MockMetadataToolTest.java`

- [ ] **Step 1**：在 `MockMetadataToolTest.java` 写失败测试：

```java
package com.wireless.agent.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockMetadataToolTest {

    private final MockMetadataTool tool = new MockMetadataTool();

    @Test
    void shouldLookupKnownTableByExactName() {
        var result = tool.run(null);
        var r = tool.lookup("dw.mr_5g_15min");
        assertThat(r.success()).isTrue();
        @SuppressWarnings("unchecked")
        var schema = (java.util.List<?>) ((java.util.Map<?, ?>) r.data()).get("schema");
        assertThat(schema).hasSize(7);
    }

    @Test
    void shouldReturnCandidatesForUnknownTable() {
        var r = tool.lookup("unknown_table");
        assertThat(r.success()).isFalse();
        @SuppressWarnings("unchecked")
        var candidates = (java.util.List<?>) ((java.util.Map<?, ?>) r.data()).get("candidates");
        assertThat(candidates).isNotEmpty();
    }

    @Test
    void shouldSearchByKeyword() {
        var r = tool.lookup("MR");
        @SuppressWarnings("unchecked")
        var candidates = (java.util.List<?>) ((java.util.Map<?, ?>) r.data()).get("candidates");
        assertThat(candidates.stream().anyMatch(c -> c.toString().toLowerCase().contains("mr"))).isTrue();
    }

    @Test
    void shouldHaveAtLeastFourKnownTables() {
        assertThat(MockMetadataTool.KNOWN_TABLES).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void shouldHaveCorrectToolName() {
        assertThat(tool.name()).isEqualTo("metadata");
    }
}
```

- [ ] **Step 2**：运行测试确认失败：

```bash
mvn test -Dtest=MockMetadataToolTest
```

期望：编译错误（类未定义）。

- [ ] **Step 3**：在 `MockMetadataTool.java` 写入实现：

```java
package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;

import java.util.*;
import java.util.stream.Collectors;

public class MockMetadataTool implements Tool {

    public static final Map<String, Map<String, Object>> KNOWN_TABLES = buildKnownTables();

    private static Map<String, Map<String, Object>> buildKnownTables() {
        var tables = new LinkedHashMap<String, Map<String, Object>>();

        tables.put("dw.mr_5g_15min", Map.of(
            "catalog", "hive",
            "schema", List.of(
                Map.of("name", "cell_id", "type", "STRING", "semantic", "小区ID"),
                Map.of("name", "ts_15min", "type", "TIMESTAMP", "semantic", "15分钟粒度时间戳"),
                Map.of("name", "rsrp_avg", "type", "DOUBLE", "semantic", "平均RSRP (dBm)"),
                Map.of("name", "rsrq_avg", "type", "DOUBLE", "semantic", "平均RSRQ (dB)"),
                Map.of("name", "sinr_avg", "type", "DOUBLE", "semantic", "平均SINR (dB)"),
                Map.of("name", "sample_count", "type", "BIGINT", "semantic", "采样数"),
                Map.of("name", "weak_cov_ratio", "type", "DOUBLE", "semantic", "弱覆盖比例 RSRP<-110")
            ),
            "owner", "无线网络优化组",
            "description", "5G MR 15分钟小区级KPI",
            "grain", "(cell_id, ts_15min)"
        ));

        tables.put("dim.engineering_param", Map.of(
            "catalog", "hive",
            "schema", List.of(
                Map.of("name", "cell_id", "type", "STRING", "semantic", "小区ID"),
                Map.of("name", "site_id", "type", "STRING", "semantic", "站点ID"),
                Map.of("name", "district", "type", "STRING", "semantic", "区县"),
                Map.of("name", "longitude", "type", "DOUBLE", "semantic", "经度"),
                Map.of("name", "latitude", "type", "DOUBLE", "semantic", "纬度"),
                Map.of("name", "rat", "type", "STRING", "semantic", "制式 4G/5G_SA/5G_NSA"),
                Map.of("name", "azimuth", "type", "INT", "semantic", "方位角"),
                Map.of("name", "downtilt", "type", "INT", "semantic", "下倾角")
            ),
            "owner", "无线网络优化组",
            "description", "小区工参维表",
            "grain", "(cell_id)"
        ));

        tables.put("dw.kpi_pm_cell_hour", Map.of(
            "catalog", "hive",
            "schema", List.of(
                Map.of("name", "cell_id", "type", "STRING", "semantic", "小区ID"),
                Map.of("name", "ts_hour", "type", "TIMESTAMP", "semantic", "小时粒度时间戳"),
                Map.of("name", "rrc_setup_succ_rate", "type", "DOUBLE", "semantic", "RRC建立成功率"),
                Map.of("name", "erab_drop_rate", "type", "DOUBLE", "semantic", "E-RAB掉话率"),
                Map.of("name", "ho_succ_rate", "type", "DOUBLE", "semantic", "切换成功率")
            ),
            "owner", "无线网络优化组",
            "description", "小区小时级PM KPI",
            "grain", "(cell_id, ts_hour)"
        ));

        tables.put("kafka.signaling_events", Map.of(
            "catalog", "kafka",
            "schema", List.of(
                Map.of("name", "ts", "type", "STRING", "semantic", "事件时间 ISO8601"),
                Map.of("name", "event_type", "type", "STRING", "semantic", "事件类型 handover/access/..."),
                Map.of("name", "src_cell", "type", "STRING", "semantic", "源小区ID"),
                Map.of("name", "dst_cell", "type", "STRING", "semantic", "目标小区ID"),
                Map.of("name", "result", "type", "STRING", "semantic", "结果 success/failure"),
                Map.of("name", "cause", "type", "STRING", "semantic", "失败原因 normal/too_early/too_late/...")
            ),
            "owner", "无线网络优化组",
            "description", "切换信令事件流 (JSONL, Kafka)",
            "grain", "(ts, event_type, src_cell)"
        ));

        return Collections.unmodifiableMap(tables);
    }

    @Override
    public String name() { return "metadata"; }

    @Override
    public String description() { return "查询 HMS/字典: 返回表 schema、字段语义、责任人"; }

    @Override
    public ToolResult run(Spec spec) {
        return ToolResult.fail("Use lookup(tableName) instead of run()");
    }

    /** Lookup a table by exact name or fuzzy keyword search. */
    public ToolResult lookup(String search) {
        if (search == null || search.isBlank()) {
            return ToolResult.fail("No search term provided");
        }

        // Exact match
        if (KNOWN_TABLES.containsKey(search)) {
            return new ToolResult(
                true, KNOWN_TABLES.get(search), "",
                Map.of("type", "schema_lookup", "source", search,
                       "findings", Map.of("found", true))
            );
        }

        // Fuzzy search
        var sLower = search.toLowerCase();
        var candidates = KNOWN_TABLES.keySet().stream()
            .filter(k -> k.toLowerCase().contains(sLower)
                      || KNOWN_TABLES.get(k).get("description").toString().toLowerCase().contains(sLower))
            .collect(Collectors.toList());

        if (!candidates.isEmpty()) {
            return ToolResult.fail("Ambiguous or partial match",
                Map.of("candidates", candidates, "keyword", search));
        }
        return ToolResult.fail("Table not found",
            Map.of("candidates", new ArrayList<>(KNOWN_TABLES.keySet())));
    }
}
```

- [ ] **Step 4**：运行测试：

```bash
mvn test -Dtest=MockMetadataToolTest
```

期望：5 passed。

- [ ] **Step 5**：commit

```bash
cd "D:/agent-code/data-agent"
git add src/main/java/com/wireless/agent/tools/MockMetadataTool.java \
        src/test/java/com/wireless/agent/tools/MockMetadataToolTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0b): mock metadata tool with hardcoded wireless schemas"
```

---

### Task 6：CodegenTool（Spark SQL 生成）

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/CodegenTool.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/tools/CodegenToolTest.java`

- [ ] **Step 1**：在 `CodegenToolTest.java` 写失败测试：

```java
package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CodegenToolTest {

    @Test
    void shouldHaveCorrectToolName() {
        var tool = new CodegenTool(null);
        assertThat(tool.name()).isEqualTo("codegen");
    }

    @Test
    void shouldBuildPromptWithTargetAndSources() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec()
                .name("weak_cov_by_district")
                .businessDefinition("按区县统计弱覆盖小区数")
                .grain("(district, day)"));
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("mr_main")
                        .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))
                        .confidence(0.9),
                new Spec.SourceBinding()
                        .role("eng_param")
                        .binding(Map.of("catalog", "hive", "table_or_topic", "dim.engineering_param"))
                        .confidence(0.9)
        ));
        spec.engineDecision(new Spec.EngineDecision("spark_sql", "批源、join + 聚合"));

        var prompt = CodegenTool.buildCodegenPrompt(spec);
        assertThat(prompt).contains("弱覆盖小区");
        assertThat(prompt).contains("dw.mr_5g_15min");
        assertThat(prompt).contains("dim.engineering_param");
        assertThat(prompt).contains("Spark SQL");
    }

    @Test
    void shouldFallbackToHardcodedSqlWhenNoLlmClient() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec()
                .name("weak_cov_test")
                .businessDefinition("弱覆盖统计")
                .grain("(cell_id, day)"));
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("main")
                        .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))
        ));
        spec.engineDecision(new Spec.EngineDecision("spark_sql", "单一Hive源"));

        var tool = new CodegenTool(null);
        var result = tool.run(spec);
        assertThat(result.success()).isTrue();
        assertThat(result.data().toString()).contains("弱覆盖");
        assertThat(result.data().toString()).contains("rsrp_avg");
    }

    @Test
    void shouldReturnGenericSqlForNonWeakCoverageTarget() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec()
                .name("test_table")
                .businessDefinition("测试数据")
                .grain("(cell_id)"));
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("main")
                        .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))
        ));
        spec.engineDecision(new Spec.EngineDecision("spark_sql", "test"));

        var tool = new CodegenTool(null);
        var result = tool.run(spec);
        assertThat(result.success()).isTrue();
        // The generic fallback should still produce SQL
        assertThat(result.data().toString()).contains("SELECT");
    }
}
```

- [ ] **Step 2**：运行测试确认失败：

```bash
mvn test -Dtest=CodegenToolTest
```

期望：编译错误（类未定义）。

- [ ] **Step 3**：在 `CodegenTool.java` 写入实现：

```java
package com.wireless.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.wireless.agent.core.Spec;
import com.wireless.agent.llm.DeepSeekClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CodegenTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    public static final String CODGEN_SYSTEM_PROMPT = """
            You are a Spark SQL code generator for wireless network perception tasks.

            Rules:
            1. Output ONLY the SQL code, no explanation before or after.
            2. Use Hive-style Spark SQL (Spark 3.5, Hive 4.0 Metastore).
            3. Include CREATE TABLE AS or INSERT OVERWRITE as the target table.
            4. Join columns must match their semantic meaning (e.g., cell_id joins with cell_id).
            5. Aggregations use GROUP BY at the grain specified in the spec.
            6. Filter conditions must reflect the business definition (e.g., weak coverage = RSRP < -110).
            7. Use COALESCE for null handling in key columns.
            8. Wrap the final SQL in a ```sql code block.
            """;

    private final DeepSeekClient llmClient;

    public CodegenTool(DeepSeekClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public String name() { return "codegen"; }

    @Override
    public String description() {
        return "Generate Spark SQL / Flink SQL / Java code from a completed Spec";
    }

    @Override
    public ToolResult run(Spec spec) {
        try {
            var prompt = buildCodegenPrompt(spec);

            String code;
            if (llmClient != null) {
                var messages = List.of(
                    Map.of("role", "system", "content", CODGEN_SYSTEM_PROMPT),
                    Map.of("role", "user", "content", prompt)
                );
                code = llmClient.chat(messages);
            } else {
                code = hardcodedSparkSql(spec);
            }

            if (code.startsWith("[ERROR]")) {
                return ToolResult.fail(code, Map.of("code", ""));
            }
            return ToolResult.ok(Map.of(
                "code", code,
                "engine", spec.engineDecision().recommended()
            ));
        } catch (Exception e) {
            return ToolResult.fail("Codegen failed: " + e.getMessage());
        }
    }

    public static String buildCodegenPrompt(Spec spec) {
        var target = spec.target();
        var engine = spec.engineDecision();
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
                ? "(no schema bound yet)"
                : String.join("\n", schemaLines);

        try {
            var ncJson = MAPPER.writeValueAsString(spec.networkContext());
            var transforms = spec.transformations().stream()
                    .map(Spec.TransformStep::description)
                    .collect(Collectors.joining(", "));
            if (transforms.isEmpty()) transforms = "(auto-infer joins and aggregation from source roles)";

            return String.format("""
                    Generate %s for the following wireless network perception spec:

                    Task: %s
                    Target table: %s
                    Business definition: %s
                    Output grain: %s
                    Network context: %s

                    Sources:
                    %s

                    Requirements:
                    - Output grain: %s
                    - Engine: %s (rationale: %s)
                    - Transformations: %s
                    """,
                    engine.recommended(),
                    spec.taskDirection().value(),
                    target != null ? target.name() : "(unspecified)",
                    target != null ? target.businessDefinition() : "(unspecified)",
                    target != null ? target.grain() : "(unspecified)",
                    ncJson,
                    schemaBlock,
                    target != null ? target.grain() : "cell × day",
                    engine.recommended(),
                    engine.reasoning(),
                    transforms
            );
        } catch (Exception e) {
            return "Generate Spark SQL for: "
                    + (target != null ? target.businessDefinition() : "unknown task");
        }
    }

    /** Fallback hardcoded SQL for the 2 demo M0b scenarios. */
    static String hardcodedSparkSql(Spec spec) {
        var target = spec.target();
        var def = target != null ? target.businessDefinition() : "";

        if (def.contains("弱覆盖") || def.toLowerCase().contains("weak")) {
            return """
                    ```sql
                    -- 弱覆盖小区按区县统计 (Spark SQL)
                    CREATE OR REPLACE TEMP VIEW weak_cov_cells AS
                    SELECT
                        m.cell_id,
                        e.district,
                        e.rat,
                        AVG(m.rsrp_avg) AS avg_rsrp,
                        AVG(m.weak_cov_ratio) AS avg_weak_cov_ratio,
                        SUM(m.sample_count) AS total_samples
                    FROM dw.mr_5g_15min m
                    JOIN dim.engineering_param e ON m.cell_id = e.cell_id
                    WHERE m.rsrp_avg < -110
                      AND m.weak_cov_ratio > 0.3
                    GROUP BY m.cell_id, e.district, e.rat
                    ORDER BY e.district, avg_weak_cov_ratio DESC;
                    ```""";
        }
        return String.format("""
                ```sql
                -- %s (Spark SQL)
                SELECT * FROM dw.mr_5g_15min LIMIT 100;
                ```""",
                target != null ? target.name() : "output");
    }
}
```

- [ ] **Step 4**：运行测试：

```bash
mvn test -Dtest=CodegenToolTest
```

期望：4 passed。

- [ ] **Step 5**：commit

```bash
cd "D:/agent-code/data-agent"
git add src/main/java/com/wireless/agent/tools/CodegenTool.java \
        src/test/java/com/wireless/agent/tools/CodegenToolTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0b): codegen tool with spark sql generation (llm + hardcoded fallback)"
```

---

### Task 7：EngineSelector

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/core/EngineSelector.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/core/EngineSelectorTest.java`

- [ ] **Step 1**：在 `EngineSelectorTest.java` 写失败测试：

```java
package com.wireless.agent.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EngineSelectorTest {

    @Test
    void shouldRecommendSparkForHiveOnlySources() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("main")
                        .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))
        ));
        var result = EngineSelector.select(spec);
        assertThat(result.recommended()).isEqualTo("spark_sql");
    }

    @Test
    void shouldRecommendFlinkForKafkaSource() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("stream")
                        .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events"))
        ));
        var result = EngineSelector.select(spec);
        assertThat(result.recommended()).isEqualTo("flink_sql");
    }

    @Test
    void shouldRecommendFlinkForMixedSources() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("stream")
                        .binding(Map.of("catalog", "kafka", "table_or_topic", "signaling_events")),
                new Spec.SourceBinding()
                        .role("dim")
                        .binding(Map.of("catalog", "hive", "table_or_topic", "dim.engineering_param"))
        ));
        var result = EngineSelector.select(spec);
        assertThat(result.recommended()).isEqualTo("flink_sql");
    }

    @Test
    void shouldProvideReasoning() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.sources(List.of(
                new Spec.SourceBinding()
                        .role("main")
                        .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))
        ));
        var result = EngineSelector.select(spec);
        assertThat(result.reasoning()).isNotEmpty();
    }

    @Test
    void shouldReturnFallbackForEmptySources() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        var result = EngineSelector.select(spec);
        assertThat(result.recommended()).isEqualTo("spark_sql");  // default fallback
    }
}
```

- [ ] **Step 2**：运行测试确认失败：

```bash
mvn test -Dtest=EngineSelectorTest
```

期望：编译错误（类未定义）。

- [ ] **Step 3**：在 `EngineSelector.java` 写入实现：

```java
package com.wireless.agent.core;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Rule-based engine selector. First match wins. */
public final class EngineSelector {

    private EngineSelector() {}

    private static final Spec.EngineDecision FALLBACK =
            new Spec.EngineDecision("spark_sql", "无法自动判定,默认 Spark SQL(人工确认)");

    /** A simple rule: condition → (engine, reasoning). */
    record Rule(String recommended, String reasoning, Predicate<Spec> condition) {}

    private static final List<Rule> RULES = List.of(
        new Rule("flink_sql",
            "源含 Kafka/CDC 流式数据,需时间窗口或流式语义 → Flink SQL",
            spec -> spec.sources().stream().anyMatch(s -> {
                var cat = s.binding().get("catalog");
                return "kafka".equals(cat) || "cdc".equals(cat);
            })),
        new Rule("spark_sql",
            "多批源 join/聚合,无流式要求 → Spark SQL",
            spec -> spec.sources().size() >= 2
                    && spec.sources().stream().allMatch(s -> {
                        var cat = s.binding().get("catalog");
                        return cat == null || "hive".equals(cat) || "starrocks".equals(cat);
                    })),
        new Rule("spark_sql",
            "单一批源,简单查询或聚合 → Spark SQL",
            spec -> spec.sources().stream().allMatch(s -> {
                var cat = s.binding().get("catalog");
                return cat == null || "hive".equals(cat) || "starrocks".equals(cat);
            }))
    );

    public static Spec.EngineDecision select(Spec spec) {
        if (spec.sources().isEmpty()) {
            return FALLBACK;
        }
        for (var rule : RULES) {
            if (rule.condition().test(spec)) {
                return new Spec.EngineDecision(rule.recommended(), rule.reasoning());
            }
        }
        return FALLBACK;
    }
}
```

- [ ] **Step 4**：运行测试：

```bash
mvn test -Dtest=EngineSelectorTest
```

期望：5 passed。

- [ ] **Step 5**：commit

```bash
cd "D:/agent-code/data-agent"
git add src/main/java/com/wireless/agent/core/EngineSelector.java \
        src/test/java/com/wireless/agent/core/EngineSelectorTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0b): engine selector with rule-based decision"
```

---

### Task 8：Prompt 模板

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/core/Prompts.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/core/PromptsTest.java`

- [ ] **Step 1**：在 `PromptsTest.java` 写失败测试：

```java
package com.wireless.agent.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptsTest {

    @Test
    void systemPromptShouldContainWirelessDomainKnowledge() {
        assertThat(Prompts.SYSTEM_PROMPT).contains("无线网络");
        assertThat(Prompts.SYSTEM_PROMPT).contains("弱覆盖");
        assertThat(Prompts.SYSTEM_PROMPT).contains("RSRP");
    }

    @Test
    void extractSpecPromptShouldIncludeUserMessage() {
        var prompt = Prompts.buildExtractSpecPrompt(
            "给我30天弱覆盖小区",
            "{\"task_direction\": \"forward_etl\"}"
        );
        assertThat(prompt).contains("弱覆盖小区");
        assertThat(prompt).contains("forward_etl");
    }

    @Test
    void clarifyPromptShouldContainOpenQuestions() {
        var questions = List.of(
            Map.<String, Object>of("field_path", "a", "question", "什么是活跃用户？")
        );
        var prompt = Prompts.buildClarifyPrompt(questions);
        assertThat(prompt).contains("活跃用户");
    }
}
```

- [ ] **Step 2**：运行测试确认失败：

```bash
mvn test -Dtest=PromptsTest
```

期望：编译错误（类未定义）。

- [ ] **Step 3**：在 `Prompts.java` 写入实现：

```java
package com.wireless.agent.core;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class Prompts {

    private Prompts() {}

    public static final String SYSTEM_PROMPT = """
            你是无线网络感知评估 Data Agent。你的职责:
            1. 从用户自然语言中提取结构化规格(目标数据集、数据源、网络域上下文)
            2. 识别规格中缺失或模糊的部分,生成精准的反问
            3. 在规格收敛后调用工具生成代码

            无线域知识(内置字典):
            - 弱覆盖: RSRP < -110 dBm 的采样占比 > 30%
            - 过覆盖: 邻区数 > 6 且 RSRP > -100 dBm
            - 切换失败率: HO failure / total HO attempts
            - 掉话率: E-RAB abnormal release / total E-RAB
            - RRC 建立成功率: RRC success / RRC attempts
            - 感知差小区: TCP 重传率高 or 视频卡顿率 > 5% or 网页时延 > 3s

            可用数据源: dw.mr_5g_15min (MR KPI), dim.engineering_param (工参),
                         dw.kpi_pm_cell_hour (PM KPI), kafka.signaling_events (信令流)

            输出格式: 你的每轮回复必须是 JSON,包含以下字段:
            {
              "intent_update": {
                "target_name": null,
                "business_definition": null,
                "kpi_family": null,
                "ne_grain": null,
                "time_grain": null,
                "rat": null,
                "timeliness": null,
                "identified_sources": [],
                "open_questions": []
              },
              "next_action": "ask_clarifying|ready_for_tools|code_done",
              "clarifying_question": null
            }
            """;

    public static String buildExtractSpecPrompt(String userMessage, String currentSpecJson) {
        return String.format("""
                当前 Spec 状态: %s

                用户最新消息: "%s"

                请从上一条消息中提取增量信息并更新 intent_update。
                如果还有未解决的 open_questions 且用户消息回答了它们,标记为已解决。
                如果关键信息仍缺失(目标不明确、数据源未确认、口径模糊),设置 next_action=ask_clarifying 并提供一句中文反问。
                """,
                currentSpecJson, userMessage);
    }

    public static String buildClarifyPrompt(List<Map<String, Object>> openQuestions) {
        var qs = openQuestions.stream()
                .map(q -> "- " + q.get("field_path") + ": " + q.get("question"))
                .collect(Collectors.joining("\n"));
        return String.format("""
                你需要向用户请求澄清以下问题,一次只问一个(优先级从高到低):

                %s

                请生成一句清晰、友好的中文反问,帮助用户明确口径。
                只问一个问题,不要一次问多个。
                """, qs);
    }
}
```

- [ ] **Step 4**：运行测试：

```bash
mvn test -Dtest=PromptsTest
```

期望：3 passed。

- [ ] **Step 5**：commit

```bash
cd "D:/agent-code/data-agent"
git add src/main/java/com/wireless/agent/core/Prompts.java \
        src/test/java/com/wireless/agent/core/PromptsTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0b): llm prompt templates (spec extraction + clarification)"
```

---

### Task 9：Agent Core（ReAct Loop）

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/core/AgentCore.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/core/AgentCoreTest.java`

这是最核心的 task——Agent 的对话编排逻辑。

- [ ] **Step 1**：在 `AgentCoreTest.java` 写失败测试：

```java
package com.wireless.agent.core;

import com.wireless.agent.llm.DeepSeekClient;
import com.wireless.agent.tools.MockMetadataTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCoreTest {

    /** Fake LLM client that returns controlled JSON responses. */
    static class FakeLLMClient extends DeepSeekClient {
        private final List<String> responses;
        private int callCount;

        FakeLLMClient(List<String> responses) {
            super("https://fake", "sk-fake", "fake-model");
            this.responses = responses;
        }

        @Override
        public String chat(List<Map<String, String>> messages) {
            if (callCount < responses.size()) {
                return responses.get(callCount++);
            }
            return "[ERROR] no more fake responses";
        }

        @Override
        public String chat(okhttp3.OkHttpClient http, List<Map<String, String>> msgs) {
            return chat(msgs);
        }
    }

    static String extractResp(String targetName, String kpiFamily) {
        return """
        {
          "intent_update": {
            "target_name": "%s",
            "business_definition": "近30天5G弱覆盖小区按区县统计",
            "kpi_family": "%s",
            "ne_grain": "district",
            "time_grain": "day",
            "rat": "5G_SA",
            "timeliness": "batch_daily",
            "identified_sources": ["dw.mr_5g_15min", "dim.engineering_param"],
            "open_questions": []
          },
          "next_action": "ready_for_tools",
          "clarifying_question": null
        }""".formatted(targetName, kpiFamily);
    }

    static String askResp(String question) {
        return """
        {
          "intent_update": {"open_questions": []},
          "next_action": "ask_clarifying",
          "clarifying_question": "%s"
        }""".formatted(question);
    }

    @Test
    void shouldInitWithEmptySpec() {
        var agent = new AgentCore(new FakeLLMClient(List.of()));
        assertThat(agent.spec()).isNotNull();
        assertThat(agent.spec().taskDirection()).isEqualTo(Spec.TaskDirection.FORWARD_ETL);
    }

    @Test
    void shouldExtractIntentFromFirstMessage() {
        var agent = new AgentCore(new FakeLLMClient(List.of(
            extractResp("weak_cov_by_district", "coverage")
        )));
        var result = agent.processMessage("给我近30天5G弱覆盖小区");
        assertThat(agent.spec().target()).isNotNull();
        assertThat(agent.spec().target().businessDefinition()).isNotEmpty();
        assertThat(result).containsKey("next_action");
    }

    @Test
    void shouldAskClarifyingWhenNeeded() {
        var agent = new AgentCore(new FakeLLMClient(List.of(
            askResp("按什么时间粒度？"),
            extractResp("weak_cov", "coverage")
        )));
        var r1 = agent.processMessage("给我弱覆盖数据");
        assertThat(r1.get("next_action")).isEqualTo("ask_clarifying");
        assertThat(r1.get("clarifying_question")).isNotNull();

        var r2 = agent.processMessage("按天汇总");
        assertThat(r2.get("next_action")).isEqualTo("code_done");
    }

    @Test
    void shouldWorkWithoutLlmClient() {
        var agent = new AgentCore(null);
        var result = agent.processMessage("弱覆盖小区统计");
        assertThat(result.get("next_action")).isEqualTo("code_done");
        assertThat(result.get("code").toString()).contains("SELECT");
    }

    @Test
    void shouldAccumulateSpecAcrossTurns() {
        var agent = new AgentCore(new FakeLLMClient(List.of(
            askResp("什么RAT? 4G or 5G?"),
            extractResp("weak_cov", "coverage")
        )));
        agent.processMessage("给我弱覆盖数据");
        agent.processMessage("5G_SA");
        // Spec should have been updated
        assertThat(agent.spec().networkContext().rat()).isEqualTo("5G_SA");
    }
}
```

- [ ] **Step 2**：运行测试确认失败：

```bash
mvn test -Dtest=AgentCoreTest
```

期望：编译错误（类未定义）。

- [ ] **Step 3**：在 `AgentCore.java` 写入实现：

```java
package com.wireless.agent.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.wireless.agent.llm.DeepSeekClient;
import com.wireless.agent.tools.CodegenTool;
import com.wireless.agent.tools.MockMetadataTool;

import java.util.*;

/** The main agent. Orchestrates NL→Spec→Tools→Code loop. */
public class AgentCore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private final DeepSeekClient llmClient;
    private final Spec spec;
    private final MockMetadataTool metadataTool;
    private final CodegenTool codegenTool;
    private int turn;

    public AgentCore(DeepSeekClient llmClient) {
        this(llmClient, Spec.TaskDirection.FORWARD_ETL);
    }

    public AgentCore(DeepSeekClient llmClient, Spec.TaskDirection taskDirection) {
        this.llmClient = llmClient;
        this.spec = new Spec(taskDirection);
        this.metadataTool = new MockMetadataTool();
        this.codegenTool = new CodegenTool(llmClient);
    }

    public Spec spec() { return spec; }

    /**
     * Process one user message through the agent loop.
     * Returns a map with keys: next_action, clarifying_question, code, engine,
     * reasoning, spec_summary.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> processMessage(String userMessage) {
        turn++;

        // Step 1: LLM extracts intent from user message into structured JSON
        var intent = callLlmExtract(userMessage);

        // Step 2: Apply intent updates to spec
        applyIntent(intent);

        // Step 3: Decide next step
        var nextAction = intent.getOrDefault("next_action", "ask_clarifying").toString();

        if ("ask_clarifying".equals(nextAction)) {
            return Map.of(
                "next_action", "ask_clarifying",
                "clarifying_question", intent.getOrDefault("clarifying_question", "请补充更多信息"),
                "code", "",
                "spec_summary", specSummary()
            );
        }

        if ("ready_for_tools".equals(nextAction)) {
            return executeTools();
        }

        // Fallback
        return Map.of(
            "next_action", "ask_clarifying",
            "clarifying_question", "请提供更多关于目标数据集的信息",
            "code", "",
            "spec_summary", specSummary()
        );
    }

    // ─── Private helpers ─────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> callLlmExtract(String userMessage) {
        if (llmClient == null) {
            return mockExtract(userMessage);
        }

        try {
            var currentJson = MAPPER.writeValueAsString(spec);
            var prompt = Prompts.buildExtractSpecPrompt(userMessage, currentJson);
            var messages = List.of(
                Map.of("role", "system", "content", Prompts.SYSTEM_PROMPT),
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

    private Map<String, Object> mockExtract(String userMessage) {
        var msg = userMessage.toLowerCase();
        var kpiFamily = "coverage";
        if (containsAny(msg, "切换", "handover", "mobility")) kpiFamily = "mobility";
        else if (containsAny(msg, "掉话", "drop", "retain")) kpiFamily = "retainability";
        else if (containsAny(msg, "接入", "rrc", "access")) kpiFamily = "accessibility";
        else if (containsAny(msg, "感知", "qoe", "视频", "tcp")) kpiFamily = "qoe";

        return Map.of(
            "intent_update", Map.of(
                "target_name", "弱覆盖小区统计",
                "business_definition", userMessage,
                "kpi_family", kpiFamily,
                "ne_grain", "district",
                "time_grain", "day",
                "rat", "5G_SA",
                "timeliness", "batch_daily",
                "identified_sources", List.of("dw.mr_5g_15min", "dim.engineering_param"),
                "open_questions", List.of()
            ),
            "next_action", "ready_for_tools",
            "clarifying_question", null
        );
    }

    @SuppressWarnings("unchecked")
    private void applyIntent(Map<String, Object> intent) {
        var update = (Map<String, Object>) intent.getOrDefault("intent_update", Map.of());
        if (update.isEmpty()) return;

        // Network context
        var nc = spec.networkContext();
        for (var field : List.of("ne_grain", "time_grain", "rat", "kpi_family")) {
            var val = update.get(field);
            if (val != null && !val.toString().isEmpty()) {
                switch (field) {
                    case "ne_grain" -> nc.neGrain(val.toString());
                    case "time_grain" -> nc.timeGrain(val.toString());
                    case "rat" -> nc.rat(val.toString());
                    case "kpi_family" -> nc.kpiFamily(val.toString());
                }
            }
        }

        // Target
        var targetName = (String) update.get("target_name");
        var bizDef = (String) update.get("business_definition");
        if (targetName != null || bizDef != null) {
            if (spec.target() == null) {
                spec.target(new Spec.TargetSpec());
            }
            if (targetName != null) spec.target().name(targetName);
            if (bizDef != null) spec.target().businessDefinition(bizDef);
            var timeGrain = (String) update.get("time_grain");
            if (timeGrain != null) spec.target().grain("(cell_id, " + timeGrain + ")");
            var timeliness = (String) update.get("timeliness");
            if (timeliness != null) spec.target().timeliness(timeliness);
        }

        // Sources
        var sources = (List<String>) update.get("identified_sources");
        if (sources != null) {
            for (var srcName : sources) {
                var result = metadataTool.lookup(srcName);
                if (result.success()) {
                    @SuppressWarnings("unchecked")
                    var data = (Map<String, Object>) result.data();
                    var role = srcName.contains(".") ? srcName.substring(srcName.lastIndexOf('.') + 1) : srcName;
                    @SuppressWarnings("unchecked")
                    var schema = (List<Map<String, String>>) data.get("schema");
                    spec.sources().add(new Spec.SourceBinding()
                            .role(role)
                            .binding(Map.of(
                                "catalog", data.getOrDefault("catalog", "hive"),
                                "table_or_topic", srcName))
                            .schema_(schema)
                            .confidence(0.8));
                }
            }
        }

        // Open questions
        var questions = (List<Map<String, Object>>) update.get("open_questions");
        if (questions != null) {
            for (var q : questions) {
                var fieldPath = q.getOrDefault("field_path", "").toString();
                var question = q.getOrDefault("question", "").toString();
                @SuppressWarnings("unchecked")
                var candidates = (List<String>) q.get("candidates");
                spec.addQuestion(fieldPath, question, candidates);
            }
        }
    }

    private Map<String, Object> executeTools() {
        // 1. Ensure sources have schemas
        for (var src : spec.sources()) {
            if (src.schema_() == null || src.schema_().isEmpty()) {
                var tbl = src.binding().getOrDefault("table_or_topic", "").toString();
                var result = metadataTool.lookup(tbl);
                if (result.success()) {
                    @SuppressWarnings("unchecked")
                    var data = (Map<String, Object>) result.data();
                    @SuppressWarnings("unchecked")
                    var schema = (List<Map<String, String>>) data.get("schema");
                    src.schema_(schema);
                    src.confidence(0.9);
                }
            }
        }

        // 2. Engine selection
        if (spec.engineDecision() == null || spec.engineDecision().recommended().isEmpty()) {
            spec.engineDecision(EngineSelector.select(spec));
        }

        // 3. Codegen
        var result = codegenTool.run(spec);
        if (result.success()) {
            spec.state(Spec.SpecState.CODEGEN_DONE);
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) result.data();
            return Map.of(
                "next_action", "code_done",
                "clarifying_question", "",
                "code", data.getOrDefault("code", ""),
                "engine", spec.engineDecision().recommended(),
                "reasoning", spec.engineDecision().reasoning(),
                "spec_summary", specSummary()
            );
        }
        return Map.of(
            "next_action", "ask_clarifying",
            "clarifying_question", "代码生成出错: " + result.error(),
            "code", "",
            "spec_summary", specSummary()
        );
    }

    public String specSummary() {
        var parts = new ArrayList<String>();
        var t = spec.target();
        if (t != null) {
            parts.add("目标: " + (t.name().isEmpty() ? "(未命名)" : t.name())
                    + " — " + (t.businessDefinition().isEmpty() ? "(口径待定)" : t.businessDefinition()));
        }
        var nc = spec.networkContext();
        parts.add("网络域: " + nc.neGrain() + "/" + nc.timeGrain() + "/" + nc.rat() + "/" + nc.kpiFamily());
        parts.add("数据源: " + spec.sources().size() + " 个");
        parts.add("状态: " + spec.state().value());
        return String.join(" | ", parts);
    }

    private static boolean containsAny(String text, String... keywords) {
        for (var kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
```

- [ ] **Step 4**：运行测试：

```bash
mvn test -Dtest=AgentCoreTest
```

期望：5 passed。

- [ ] **Step 5**：commit

```bash
cd "D:/agent-code/data-agent"
git add src/main/java/com/wireless/agent/core/AgentCore.java \
        src/test/java/com/wireless/agent/core/AgentCoreTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0b): agent core with react loop (nl→spec→tools→codegen)"
```

---

### Task 10：Main CLI 入口

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/Main.java`
- Create: `D:/agent-code/data-agent/src/main/resources/.env.example`（Maven 资源目录）

- [ ] **Step 1**：在 `src/main/resources/.env.example` 写入：

```
DEEPSEEK_API_BASE=https://api.deepseek.com/v1
DEEPSEEK_API_KEY=sk-your-key-here
DEEPSEEK_MODEL=deepseek-chat
```

- [ ] **Step 2**：在 `Main.java` 写入：

```java
package com.wireless.agent;

import com.wireless.agent.core.AgentCore;
import com.wireless.agent.core.Spec;
import com.wireless.agent.llm.DeepSeekClient;

import java.util.List;
import java.util.Scanner;

/**
 * Data Agent CLI — wireless network perception data agent.
 *
 * Usage:
 *   java -jar data-agent.jar              # Interactive stdin/stdout mode
 *   java -jar data-agent.jar --demo       # Run 2 hardcoded demo scenarios
 *   java -jar data-agent.jar --no-llm     # Run without LLM (mock extract only)
 */
public class Main {

    private static final List<String> DEMO_SCENARIOS = List.of(
        "给我近30天每个区县5G弱覆盖小区清单",
        "按cell_id汇总切换失败次数，并关联工参表的district信息"
    );

    public static void main(String[] args) {
        var demo = false;
        var noLlm = false;
        for (var arg : args) {
            if ("--demo".equals(arg)) demo = true;
            if ("--no-llm".equals(arg)) noLlm = true;
        }

        DeepSeekClient llmClient = null;
        if (!noLlm) {
            try {
                llmClient = new DeepSeekClient();
                System.out.println("[INFO] LLM: " + llmClient.model() + " @ " + llmClient.apiBase());
            } catch (Exception e) {
                System.out.println("[WARN] LLM init failed: " + e.getMessage() + ", falling back to mock mode");
            }
        }

        if (demo) {
            runDemo(llmClient);
        } else {
            runInteractive(llmClient);
        }
    }

    private static void runDemo(DeepSeekClient llmClient) {
        System.out.println("=" .repeat(60));
        System.out.println("M0b Demo — 无线网络感知评估 Data Agent");
        System.out.println("=" .repeat(60));

        for (int i = 0; i < DEMO_SCENARIOS.size(); i++) {
            var msg = DEMO_SCENARIOS.get(i);
            System.out.println();
            System.out.println("─".repeat(60));
            System.out.println("场景 " + (i + 1) + ": " + msg);
            System.out.println("─".repeat(60));

            var agent = new AgentCore(llmClient);
            var result = agent.processMessage(msg);

            System.out.println("  [状态] " + result.get("next_action"));
            var q = result.get("clarifying_question");
            if (q != null && !q.toString().isEmpty()) {
                System.out.println("  [反问] " + q);
            }
            var code = result.get("code");
            if (code != null && !code.toString().isEmpty()) {
                System.out.println("  [代码]\n" + code);
            }
            var reasoning = result.get("reasoning");
            if (reasoning != null && !reasoning.toString().isEmpty()) {
                System.out.println("  [引擎] " + result.get("engine") + " — " + reasoning);
            }
            System.out.println("  [Spec] " + result.get("spec_summary"));
        }
        System.out.println();
        System.out.println("=" .repeat(60));
        System.out.println("Demo 完成。");
    }

    private static void runInteractive(DeepSeekClient llmClient) {
        System.out.println("Data Agent — 无线网络感知评估 (输入 /quit 退出)");
        var agent = new AgentCore(llmClient);
        System.out.println("[Spec] " + agent.specSummary());

        var scanner = new Scanner(System.in);
        while (true) {
            System.out.println();
            System.out.print("> ");
            String input;
            try {
                input = scanner.nextLine();
            } catch (Exception e) {
                System.out.println();
                break;
            }
            if (input.isBlank()) continue;
            if (List.of("/quit", "/exit", "quit", "exit").contains(input.trim().toLowerCase())) {
                System.out.println("再见。");
                break;
            }

            var result = agent.processMessage(input.trim());

            switch (result.get("next_action").toString()) {
                case "ask_clarifying" ->
                    System.out.println("[反问] " + result.get("clarifying_question"));
                case "code_done" -> {
                    System.out.println("[引擎] " + result.get("engine") + " — " + result.get("reasoning"));
                    System.out.println("[代码]\n" + result.get("code"));
                }
                default -> System.out.println("[状态] " + result.get("next_action"));
            }
            System.out.println("[Spec] " + result.get("spec_summary"));
        }
    }
}
```

- [ ] **Step 3**：编译并打包：

```bash
mvn compile
```

期望：`BUILD SUCCESS`。

- [ ] **Step 4**：跑 —demo —no-llm（不依赖 LLM）：

```bash
mvn exec:java -Dexec.mainClass="com.wireless.agent.Main" -Dexec.args="--demo --no-llm"
```

需要先在 pom.xml 的 `<build><plugins>` 中加入 exec-maven-plugin，或者用 java 命令直接运行：

```bash
mvn package -DskipTests
java -cp target/data-agent-0.1.0.jar com.wireless.agent.Main --demo --no-llm
```

期望：输出两个场景的代码和 spec 摘要。

> **注意**：若使用 `java -cp` 方式，需要将依赖 jar 也加入 classpath。推荐使用 `mvn exec:java` 或 `mvn package` + shade plugin。为方便，后续可添加 exec-maven-plugin。

在 Task 10 的 commit 前，更新 pom.xml 加入 exec-maven-plugin：

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>3.5.0</version>
</plugin>
```

- [ ] **Step 5**：commit

```bash
cd "D:/agent-code/data-agent"
git add src/main/java/com/wireless/agent/Main.java pom.xml
mkdir -p src/main/resources
echo "DEEPSEEK_API_BASE=https://api.deepseek.com/v1" > src/main/resources/.env.example
echo "DEEPSEEK_API_KEY=sk-your-key-here" >> src/main/resources/.env.example
echo "DEEPSEEK_MODEL=deepseek-chat" >> src/main/resources/.env.example
git add src/main/resources/.env.example
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0b): main cli entry (interactive + demo mode)"
```

---

### Task 11：集成测试与完整回路验证

**Files:**
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/IntegrationTest.java`

- [ ] **Step 1**：在 `IntegrationTest.java` 写集成测试：

```java
package com.wireless.agent;

import com.wireless.agent.core.AgentCore;
import com.wireless.agent.core.Spec;
import com.wireless.agent.llm.DeepSeekClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationTest {

    /** Simulates a full agent conversation across multiple turns. */
    static class FakeLLMForIntegration extends DeepSeekClient {
        private int turn;

        FakeLLMForIntegration() {
            super("https://fake", "sk-fake", "fake-model");
        }

        @Override
        public String chat(List<Map<String, String>> messages) {
            turn++;
            if (turn == 1) {
                return """
                {
                  "intent_update": {
                    "target_name": "weak_cov_by_district",
                    "business_definition": "近30天5G弱覆盖小区按区县统计",
                    "kpi_family": "coverage",
                    "ne_grain": "district",
                    "time_grain": "day",
                    "rat": "5G_SA",
                    "timeliness": "batch_daily",
                    "identified_sources": ["dw.mr_5g_15min", "dim.engineering_param"],
                    "open_questions": []
                  },
                  "next_action": "ready_for_tools",
                  "clarifying_question": null
                }""";
            }
            return """
            {
              "intent_update": {},
              "next_action": "ready_for_tools",
              "clarifying_question": null
            }""";
        }

        @Override
        public String chat(okhttp3.OkHttpClient http, List<Map<String, String>> msgs) {
            return chat(msgs);
        }
    }

    @Test
    void shouldCompleteFullLoopForWeakCoverageScenario() {
        var agent = new AgentCore(
            new FakeLLMForIntegration(),
            Spec.TaskDirection.FORWARD_ETL
        );
        var result = agent.processMessage("给我近30天每个区县5G弱覆盖小区清单");

        assertThat(result.get("next_action")).isEqualTo("code_done");
        var code = result.get("code").toString();
        assertThat(code.length()).isGreaterThan(50);
        assertThat(code.toLowerCase()).contains("select");
        assertThat(agent.spec().state()).isEqualTo(Spec.SpecState.CODEGEN_DONE);
    }

    @Test
    void shouldProduceCodeWithoutLlmClient() {
        var agent = new AgentCore(null);
        var result = agent.processMessage("弱覆盖小区");

        assertThat(result.get("next_action")).isEqualTo("code_done");
        assertThat(result.get("code")).isNotNull();
        assertThat(result.get("code").toString()).contains("SELECT");
    }

    @Test
    void shouldReturnCodeForHandoverScenario() {
        var agent = new AgentCore(null);
        var result = agent.processMessage("按cell_id汇总切换失败次数");

        assertThat(result.get("next_action")).isEqualTo("code_done");
        assertThat(result.get("code")).isNotNull();
    }
}
```

- [ ] **Step 2**：运行集成测试：

```bash
mvn test -Dtest=IntegrationTest
```

期望：3 passed（验证了端到端回路闭合）。

- [ ] **Step 3**：运行全套测试确认无回归：

```bash
mvn test
```

期望：全部 passed（约 25-28 个测试）。

- [ ] **Step 4**：commit

```bash
cd "D:/agent-code/data-agent"
git add src/test/java/com/wireless/agent/IntegrationTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "test(m0b): integration tests for full agent loop"
```

---

### Task 12：文档与收尾

- [ ] **Step 1**：更新 `README.md`，在末尾追加：

```markdown
## M0b — Agent 骨架 (Java)

```bash
# 编译
mvn compile

# 跑全部测试
mvn test

# 演示模式（无需 LLM）
mvn exec:java -Dexec.mainClass="com.wireless.agent.Main" -Dexec.args="--demo --no-llm"

# 演示模式（使用 DeepSeek API，需先配置 .env 或环境变量）
mvn exec:java -Dexec.mainClass="com.wireless.agent.Main" -Dexec.args="--demo"

# 交互模式
mvn exec:java -Dexec.mainClass="com.wireless.agent.Main"

# 打包
mvn package -DskipTests
java -cp target/data-agent-0.1.0.jar com.wireless.agent.Main --demo --no-llm
```

**环境变量（DeepSeek API）：**

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `DEEPSEEK_API_BASE` | API 地址 | (必填) |
| `DEEPSEEK_API_KEY` | API Key | (必填) |
| `DEEPSEEK_MODEL` | 模型名 | `deepseek-chat` |
```

- [ ] **Step 2**：最后跑一次全套测试，确认全部绿色：

```bash
mvn test
```

期望输出：`Tests run: N, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`。

- [ ] **Step 3**：commit + push

```bash
cd "D:/agent-code/data-agent"
git add README.md
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "docs(m0b): add agent skeleton quickstart to readme"
git push
```

---

## M0b 完成标准（DoD）

- [ ] `mvn test` 全部测试通过（约 25-30 个）
- [ ] `mvn exec:java -Dexec.mainClass="com.wireless.agent.Main" -Dexec.args="--demo --no-llm"` 成功输出两个场景的 Spark SQL 代码
- [ ] `mvn exec:java -Dexec.mainClass="com.wireless.agent.Main" -Dexec.args="--demo"`（使用 DeepSeek API）成功输出两个场景的 Spark SQL 代码
- [ ] 交互模式可多轮对话（NL → 反问 → 代码）
- [ ] Spec Accumulator 在多轮对话中正确累积状态
- [ ] EngineSelector 自动判定 spark_sql（纯批源场景）和 flink_sql（包含 Kafka 源场景）
- [ ] Mock MetadataTool 返回正确的无线表 schema（4 张表）
- [ ] 所有代码提交并推送到 origin/main

---

## 后续

M0b 完成后回到 brainstorming → 根据 M0b 暴露的 LLM/loop 问题微调 spec → writing-plans 写 M1（真工具接通：真 HMS、ProfilerTool、ValidatorTool、SandboxTool）。
