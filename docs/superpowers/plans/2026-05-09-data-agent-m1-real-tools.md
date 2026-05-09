# M1 真工具接通 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal**：将 M0b 的 Mock 工具替换为真实工具：真 HMS MetadataTool（查询 Hive Metastore Thrift）、ProfilerTool（数据画像）、ValidatorTool（SQL 语法/schema 校验）、SandboxTool（提交到本地 Docker spark-master dry-run），全部接入 AgentCore，跑通"NL → 代码 → dry-run 预览"全流程。

**Architecture**：HmsMetadataTool 通过 HiveMetastoreClient (Thrift) 直连 hive-metastore:9083 获取真实 schema。ProfilerTool 和 SandboxTool 通过 Java ProcessBuilder 执行 `docker exec da-spark-master spark-sql/spark-submit` 在容器内运行查询（M1 的 Docker exec 方案是最简单可靠的 v1 方式，后续可替换为 SparkLauncher）。ValidatorTool 是纯 Java SQL 解析器，无外部依赖。AgentCore 的 executeTools() 管线扩展为：Metadata → Profiler → EngineSelector → Codegen → Validator → Sandbox。

**Tech Stack**：Java 17, Maven, Hive Metastore 4.0 Thrift client, Jackson, JUnit 5, AssertJ, Mockito, Docker CLI (via ProcessBuilder)

---

## 文件结构（M1 完成后的新增/变更目录形态）

```
data-agent/
├── pom.xml                                       # + hive-metastore dependency
├── src/main/java/com/wireless/agent/
│   ├── core/
│   │   └── AgentCore.java                        # 修改: 接入真工具管线
│   ├── tools/
│   │   ├── HmsMetadataTool.java                  # 新增: 真 HMS 查询
│   │   ├── ProfilerTool.java                     # 新增: 数据画像
│   │   ├── ValidatorTool.java                    # 新增: SQL 校验
│   │   ├── SandboxTool.java                      # 新增: Dry-run 执行
│   │   ├── DockerCommandRunner.java              # 新增: docker exec 工具
│   │   ├── CodegenTool.java                      # 修改: 保持
│   │   ├── MockMetadataTool.java                 # 保留作为 fallback
│   │   ├── Tool.java                             # 不变
│   │   └── ToolResult.java                       # 不变
│   └── llm/
│       └── DeepSeekClient.java                   # 不变
└── src/test/java/com/wireless/agent/
    ├── core/
    │   └── AgentCoreTest.java                    # 修改: 适配新工具
    ├── tools/
    │   ├── HmsMetadataToolTest.java              # 新增
    │   ├── ProfilerToolTest.java                 # 新增
    │   ├── ValidatorToolTest.java                # 新增
    │   ├── SandboxToolTest.java                  # 新增
    │   └── DockerCommandRunnerTest.java          # 新增
    └── IntegrationTest.java                      # 修改: 端到端 dry-run
```

---

### Task 1：添加 Hive Metastore Client 依赖

**Files:**
- Modify: `D:/agent-code/data-agent/pom.xml`

- [ ] **Step 1**：在 pom.xml 的 `<dependencies>` 块中追加 Hive Metastore 和 Spark 相关依赖。

在 `<!-- .env file support -->` 依赖块之后追加：

```xml
        <!-- Hive Metastore Thrift client (for HmsMetadataTool) -->
        <dependency>
            <groupId>org.apache.hive</groupId>
            <artifactId>hive-metastore</artifactId>
            <version>4.0.0</version>
            <exclusions>
                <exclusion>
                    <groupId>org.apache.hadoop</groupId>
                    <artifactId>hadoop-yarn-server-resourcemanager</artifactId>
                </exclusion>
                <exclusion>
                    <groupId>org.apache.logging.log4j</groupId>
                    <artifactId>log4j-slf4j2-impl</artifactId>
                </exclusion>
            </exclusions>
        </dependency>

        <!-- Hadoop Common (needed by HMS client for Configuration) -->
        <dependency>
            <groupId>org.apache.hadoop</groupId>
            <artifactId>hadoop-common</artifactId>
            <version>3.3.6</version>
            <exclusions>
                <exclusion>
                    <groupId>org.apache.logging.log4j</groupId>
                    <artifactId>log4j-slf4j2-impl</artifactId>
                </exclusion>
            </exclusions>
        </dependency>

        <!-- SLF4J simple logger (avoid log4j conflicts) -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-simple</artifactId>
            <version>2.0.16</version>
        </dependency>
```

- [ ] **Step 2**：验证依赖解析成功：

```bash
cd D:/agent-code/data-agent
mvn dependency:resolve
```

期望：`BUILD SUCCESS`。

- [ ] **Step 3**：commit

```bash
git add pom.xml
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "chore(m1): add hive-metastore 4.0 and hadoop-common dependencies"
```

---

### Task 2：DockerCommandRunner — 通用 docker exec 工具

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/DockerCommandRunner.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/tools/DockerCommandRunnerTest.java`

ProfilerTool 和 SandboxTool 都需要在 Docker 容器内执行命令。DockerCommandRunner 封装 ProcessBuilder 调用 `docker exec`。

- [ ] **Step 1**：在 `DockerCommandRunnerTest.java` 写失败测试：

```java
package com.wireless.agent.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DockerCommandRunnerTest {

    @Test
    void shouldBuildDockerExecCommand() {
        var args = List.of("spark-sql", "-e", "SELECT 1");
        var cmd = DockerCommandRunner.buildCommand("da-spark-master", args);
        assertThat(cmd).containsExactly("docker", "exec", "da-spark-master",
                "spark-sql", "-e", "SELECT 1");
    }

    @Test
    void shouldReturnResultWithStdout() {
        var runner = new DockerCommandRunner();
        // Unit test: we don't actually run docker, just verify structure
        var result = new DockerCommandRunner.Result(0, "hello\n", "", false);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("hello\n");
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldParseFailureFromNonZeroExit() {
        var result = new DockerCommandRunner.Result(1, "", "Table not found", false);
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.stderr()).contains("Table not found");
    }

    @Test
    void shouldHandleTimeout() {
        // Timeout simulation: verify that timeout=true is reflected in result
        var result = new DockerCommandRunner.Result(-1, "", "timeout", true);
        assertThat(result.timedOut()).isTrue();
        assertThat(result.isSuccess()).isFalse();
    }
}
```

- [ ] **Step 2**：运行测试确认失败：

```bash
mvn test -Dtest=DockerCommandRunnerTest
```

- [ ] **Step 3**：在 `DockerCommandRunner.java` 写入实现：

```java
package com.wireless.agent.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs commands inside Docker containers via "docker exec". */
public class DockerCommandRunner {

    private static final long DEFAULT_TIMEOUT_SEC = 120;

    public record Result(int exitCode, String stdout, String stderr, boolean timedOut) {
        public boolean isSuccess() {
            return exitCode == 0 && !timedOut;
        }
    }

    /** Build the docker exec command array from container name and args. */
    public static List<String> buildCommand(String container, List<String> args) {
        var cmd = new ArrayList<String>();
        cmd.add("docker");
        cmd.add("exec");
        cmd.add(container);
        cmd.addAll(args);
        return cmd;
    }

    /** Execute a command inside a Docker container. */
    public Result exec(String container, List<String> args) {
        return exec(container, args, DEFAULT_TIMEOUT_SEC);
    }

    /** Execute with explicit timeout. */
    public Result exec(String container, List<String> args, long timeoutSec) {
        var cmd = buildCommand(container, args);
        try {
            var pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            var process = pb.start();

            var stdout = readStream(new BufferedReader(new InputStreamReader(process.getInputStream())));
            var stderr = readStream(new BufferedReader(new InputStreamReader(process.getErrorStream())));

            var finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(-1, stdout, stderr, true);
            }
            return new Result(process.exitValue(), stdout, stderr, false);
        } catch (Exception e) {
            return new Result(-1, "", e.getMessage(), false);
        }
    }

    private String readStream(BufferedReader reader) throws Exception {
        var sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }
}
```

- [ ] **Step 4**：运行测试：

```bash
mvn test -Dtest=DockerCommandRunnerTest
```

期望：4 passed。

- [ ] **Step 5**：commit

```bash
git add src/main/java/com/wireless/agent/tools/DockerCommandRunner.java \
        src/test/java/com/wireless/agent/tools/DockerCommandRunnerTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m1): docker command runner for spark-sql/spark-submit execution"
```

---

### Task 3：HmsMetadataTool — 真 HMS Thrift 连接

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/HmsMetadataTool.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/tools/HmsMetadataToolTest.java`

用 HiveMetaStoreClient（Thrift）直连 hive-metastore:9083，查询真实 table schema。保留 MockMetadataTool 作为 HMS 不可用时的 fallback。

- [ ] **Step 1**：在 `HmsMetadataToolTest.java` 写失败测试：

```java
package com.wireless.agent.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HmsMetadataToolTest {

    @Test
    void shouldHaveCorrectToolName() {
        var tool = new HmsMetadataTool("thrift://localhost:9083");
        assertThat(tool.name()).isEqualTo("metadata");
    }

    @Test
    void shouldFallbackToMockWhenHmsUnreachable() {
        // When HMS is unreachable, fallback to mock data
        var tool = new HmsMetadataTool("thrift://nonexistent:9999");
        var result = tool.lookup("dw.mr_5g_15min");
        // Should fallback to mock
        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        var schema = (List<?>) ((Map<?, ?>) result.data()).get("schema");
        assertThat(schema).isNotNull();
    }

    @Test
    void shouldSearchByFallbackKeywordWhenHmsUnreachable() {
        var tool = new HmsMetadataTool("thrift://nonexistent:9999");
        var result = tool.lookup("MR");
        @SuppressWarnings("unchecked")
        var candidates = (List<?>) ((Map<?, ?>) result.data()).get("candidates");
        assertThat(candidates).isNotEmpty();
    }

    @Test
    void shouldImplementToolInterface() {
        var tool = new HmsMetadataTool("thrift://localhost:9083");
        assertThat(tool).isInstanceOf(Tool.class);
    }
}
```

- [ ] **Step 2**：运行测试确认失败：

```bash
mvn test -Dtest=HmsMetadataToolTest
```

- [ ] **Step 3**：在 `HmsMetadataTool.java` 写入实现：

```java
package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.FieldSchema;

import java.util.*;
import java.util.stream.Collectors;

/** Real HMS MetadataTool using Hive Metastore Thrift client.
 *  Falls back to MockMetadataTool when HMS is unreachable. */
public class HmsMetadataTool implements Tool {

    private final String hmsUri;
    private final MockMetadataTool fallback;
    private HiveMetaStoreClient client;

    public HmsMetadataTool(String hmsUri) {
        this.hmsUri = hmsUri;
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

        // Try real HMS client
        var result = tryHmsLookup(search);
        if (result != null) return result;

        // Fallback to mock
        return fallback.lookup(search);
    }

    private ToolResult tryHmsLookup(String search) {
        try {
            var c = getClient();
            // Parse catalog.schema.table or schema.table
            var parts = search.split("\\.");
            if (parts.length >= 2) {
                var dbName = parts[parts.length - 2];  // e.g., "dw"
                var tblName = parts[parts.length - 1]; // e.g., "mr_5g_15min"
                var table = c.getTable(dbName, tblName);
                var fields = c.getFields(dbName, tblName);

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
        } catch (Exception e) {
            // HMS unreachable or table not found — fall through to fallback
            client = null;  // Reset failed client
        }
        return null;
    }

    private HiveMetaStoreClient getClient() throws Exception {
        if (client != null) return client;

        var conf = new HiveConf();
        conf.setVar(HiveConf.ConfVars.METASTOREURIS, hmsUri);
        conf.set("hive.metastore.client.capability.check", "false");
        client = new HiveMetaStoreClient(conf);
        return client;
    }

    /** Direct search with keyword matching against known tables via fallback. */
    public ToolResult search(String keyword) {
        return fallback.lookup(keyword);
    }
}
```

- [ ] **Step 4**：运行测试：

```bash
mvn test -Dtest=HmsMetadataToolTest
```

期望：4 passed。

- [ ] **Step 5**：commit

```bash
git add src/main/java/com/wireless/agent/tools/HmsMetadataTool.java \
        src/test/java/com/wireless/agent/tools/HmsMetadataToolTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m1): real hms metadata tool with hive metastore thrift client"
```

---

### Task 4：ValidatorTool — SQL 语法/Schema 校验

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/ValidatorTool.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/tools/ValidatorToolTest.java`

纯 Java SQL 校验器：检查生成的 SQL 是否合理——提取 SQL 代码块、检查表名是否在 spec.sources 中、检查基本语法（无外部 SQL parser 依赖）。

- [ ] **Step 1**：在 `ValidatorToolTest.java` 写失败测试：

```java
package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ValidatorToolTest {

    @Test
    void shouldHaveCorrectToolName() {
        var tool = new ValidatorTool();
        assertThat(tool.name()).isEqualTo("validator");
    }

    @Test
    void shouldPassValidSparkSql() {
        var code = """
                ```sql
                CREATE OR REPLACE TEMP VIEW test AS
                SELECT m.cell_id, e.district
                FROM dw.mr_5g_15min m
                JOIN dim.engineering_param e ON m.cell_id = e.cell_id
                WHERE m.rsrp_avg < -110;
                ```""";

        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec().name("test_view").businessDefinition("弱覆盖"));
        spec.sources(List.of(
            new Spec.SourceBinding().role("mr_main")
                .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min")),
            new Spec.SourceBinding().role("eng_param")
                .binding(Map.of("catalog", "hive", "table_or_topic", "dim.engineering_param"))
        ));

        var result = new ValidatorTool().validate(code, spec);
        assertThat(result.success()).isTrue();
    }

    @Test
    void shouldFailOnMissingSqlBlock() {
        var code = "just some text, no sql code block";
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        var result = new ValidatorTool().validate(code, spec);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("SQL code block");
    }

    @Test
    void shouldWarnOnMissingTableReference() {
        var code = """
                ```sql
                SELECT * FROM some_unknown_table LIMIT 10;
                ```""";
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.sources(List.of(
            new Spec.SourceBinding().role("mr_main")
                .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))
        ));

        var result = new ValidatorTool().validate(code, spec);
        // Should still pass but with warnings
        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        var warnings = (List<String>) ((Map<?, ?>) result.data()).get("warnings");
        assertThat(warnings).isNotEmpty();
    }

    @Test
    void shouldDetectMissingFROMClause() {
        var code = """
                ```sql
                SELECT 1;
                ```""";
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        var result = new ValidatorTool().validate(code, spec);
        // Simple SELECT without FROM on a real table is suspicious but can pass
        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        var warnings = (List<String>) ((Map<?, ?>) result.data()).get("warnings");
        assertThat(warnings).isNotEmpty();
    }
}
```

- [ ] **Step 2**：运行测试确认失败。

- [ ] **Step 3**：在 `ValidatorTool.java` 写入实现：

```java
package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;

import java.util.*;
import java.util.regex.Pattern;

/** Validates generated SQL: extracts SQL block, checks table references,
 *  detects common issues. No external SQL parser dependency. */
public class ValidatorTool implements Tool {

    private static final Pattern SQL_BLOCK = Pattern.compile(
            "```sql\\s*\\n?(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_REF = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+([a-zA-Z_][a-zA-Z0-9_.]*)", Pattern.CASE_INSENSITIVE);

    @Override
    public String name() { return "validator"; }

    @Override
    public String description() { return "校验生成的 SQL 语法与 schema 兼容性"; }

    @Override
    public ToolResult run(Spec spec) {
        return ToolResult.fail("Use validate(code, spec) instead of run()");
    }

    public ToolResult validate(String rawCode, Spec spec) {
        var warnings = new ArrayList<String>();

        // 1. Extract SQL from markdown code block
        var matcher = SQL_BLOCK.matcher(rawCode);
        if (!matcher.find()) {
            return ToolResult.fail("No ```sql code block found in generated output");
        }
        var sql = matcher.group(1).trim();
        if (sql.isEmpty()) {
            return ToolResult.fail("SQL code block is empty");
        }

        // 2. Check for FROM clause
        if (!sql.toUpperCase().contains("FROM")) {
            warnings.add("SQL has no FROM clause — may not reference any table");
        }

        // 3. Check table references against spec sources
        var referencedTables = new ArrayList<String>();
        var tableMatcher = TABLE_REF.matcher(sql);
        while (tableMatcher.find()) {
            referencedTables.add(tableMatcher.group(1).toLowerCase());
        }

        var knownTables = new ArrayList<String>();
        for (var src : spec.sources()) {
            var tbl = src.binding().getOrDefault("table_or_topic", "").toString();
            if (!tbl.isEmpty()) knownTables.add(tbl.toLowerCase());
        }

        for (var ref : referencedTables) {
            var found = knownTables.stream().anyMatch(t -> ref.contains(t) || t.contains(ref));
            if (!found) {
                warnings.add("Table " + ref + " not in spec sources: " + knownTables);
            }
        }

        // 4. Basic syntax checks
        if (!sql.toUpperCase().contains("SELECT")) {
            warnings.add("SQL has no SELECT clause");
        }

        return new ToolResult(true,
                Map.of("sql", sql, "warnings", warnings, "referenced_tables", referencedTables),
                warnings.isEmpty() ? "" : String.join("; ", warnings));
    }
}
```

- [ ] **Step 4**：运行测试：

```bash
mvn test -Dtest=ValidatorToolTest
```

期望：5 passed（含 validator 的 run() 测试）。

- [ ] **Step 5**：commit

```bash
git add src/main/java/com/wireless/agent/tools/ValidatorTool.java \
        src/test/java/com/wireless/agent/tools/ValidatorToolTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m1): validator tool for sql syntax and schema checking"
```

---

### Task 5：ProfilerTool — 数据画像

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/ProfilerTool.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/tools/ProfilerToolTest.java`

通过 DockerCommandRunner 在 spark-master 容器内执行 spark-sql 查询，对指定表做数据画像：行数、null 率、distinct 计数、top-K 值分布。

- [ ] **Step 1**：在 `ProfilerToolTest.java` 写失败测试：

```java
package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProfilerToolTest {

    @Test
    void shouldHaveCorrectToolName() {
        var runner = new DockerCommandRunner();
        var tool = new ProfilerTool(runner, "da-spark-master");
        assertThat(tool.name()).isEqualTo("profiler");
    }

    @Test
    void shouldBuildRowCountQuery() {
        var sql = ProfilerTool.buildProfileQuery(
                "dw.mr_5g_15min",
                List.of("cell_id", "rsrp_avg"),
                10
        );
        assertThat(sql).contains("COUNT(*)");
        assertThat(sql).contains("dw.mr_5g_15min");
        assertThat(sql).contains("LIMIT 10");
    }

    @Test
    void shouldBuildNullCheckQuery() {
        var sql = ProfilerTool.buildNullCheckQuery("dw.mr_5g_15min", "rsrp_avg");
        assertThat(sql).contains("rsrp_avg IS NULL");
        assertThat(sql).contains("COUNT(*)");
    }

    @Test
    void shouldParseSparkSqlOutput() {
        var stdout = "100\n42\n";
        var result = ProfilerTool.parseCountResult(stdout);
        assertThat(result).containsEntry("total", "100");
    }

    @Test
    void shouldGracefullyHandleEmptyTable() {
        var sql = ProfilerTool.buildProfileQuery("empty_table", List.of(), 10);
        assertThat(sql).contains("empty_table");
    }
}
```

- [ ] **Step 2**：运行测试确认失败。

- [ ] **Step 3**：在 `ProfilerTool.java` 写入实现：

```java
package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;

import java.util.*;
import java.util.stream.Collectors;

/** Profiles data tables: row count, null rate, distinct count, top-K distribution.
 *  Executes profiling SQL via DockerCommandRunner on spark-master container. */
public class ProfilerTool implements Tool {

    private final DockerCommandRunner runner;
    private final String sparkContainer;

    public ProfilerTool(DockerCommandRunner runner, String sparkContainer) {
        this.runner = runner;
        this.sparkContainer = sparkContainer;
    }

    @Override
    public String name() { return "profiler"; }

    @Override
    public String description() { return "对指定表做数据画像: 行数、null率、distinct计数、top-K分布"; }

    @Override
    public ToolResult run(Spec spec) {
        if (spec.sources().isEmpty()) {
            return ToolResult.fail("No sources to profile");
        }
        var results = new LinkedHashMap<String, Object>();
        for (var src : spec.sources()) {
            var tbl = src.binding().getOrDefault("table_or_topic", "").toString();
            if (!tbl.isEmpty()) {
                var profile = profileTable(tbl, 5);
                results.put(tbl, profile.data());
            }
        }
        return ToolResult.ok(results,
                Map.of("type", "data_profile", "findings", Map.of("profiled_tables", results.size())));
    }

    /** Profile a single table: return row count + per-column stats. */
    public ToolResult profileTable(String tableName, int topK) {
        try {
            var rowCount = runSparkSql(buildRowCountQuery(tableName));
            var stats = new LinkedHashMap<String, Object>();
            stats.put("row_count", parseCount(rowCount));

            // For each column in known schemas, compute null rate
            var mockFallback = new MockMetadataTool();
            var schemaResult = mockFallback.lookup(tableName);
            if (schemaResult.success()) {
                @SuppressWarnings("unchecked")
                var data = (Map<String, Object>) schemaResult.data();
                @SuppressWarnings("unchecked")
                var schema = (List<Map<String, String>>) data.get("schema");
                for (var col : schema) {
                    var colName = col.get("name");
                    var nullCount = runSparkSql(buildNullCheckQuery(tableName, colName));
                    stats.put(colName + "_null_rate",
                            String.format("%.2f%%", 100.0 * parseCount(nullCount) / Math.max(1, parseCount(rowCount))));
                }
            }
            return ToolResult.ok(stats);
        } catch (Exception e) {
            return ToolResult.fail("Profiling failed: " + e.getMessage(),
                    Map.of("row_count", "N/A"));
        }
    }

    static String buildProfileQuery(String table, List<String> columns, int topK) {
        var cols = columns.isEmpty() ? "*" : String.join(", ", columns);
        return String.format(
                "SELECT COUNT(*) AS cnt FROM %s; " +
                "SELECT %s FROM %s LIMIT %d;",
                table, cols, table, topK);
    }

    static String buildRowCountQuery(String table) {
        return String.format("SELECT COUNT(*) FROM %s;", table);
    }

    static String buildNullCheckQuery(String table, String column) {
        return String.format("SELECT COUNT(*) FROM %s WHERE %s IS NULL;", table, column);
    }

    static Map<String, String> parseCountResult(String stdout) {
        var lines = stdout.trim().split("\\n");
        var result = new LinkedHashMap<String, String>();
        for (int i = 0; i < lines.length; i++) {
            result.put("total", lines[i].trim());
            break;
        }
        return result;
    }

    private int parseCount(String stdout) {
        try {
            var lines = stdout.trim().split("\\n");
            // spark-sql output: header line then count
            for (var line : lines) {
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

    private String runSparkSql(String sql) {
        var result = runner.exec(sparkContainer,
                List.of("spark-sql", "--master", "spark://spark-master:7077", "-e", sql));
        if (result.isSuccess()) {
            return result.stdout();
        }
        return result.stderr();
    }
}
```

- [ ] **Step 4**：运行测试：

```bash
mvn test -Dtest=ProfilerToolTest
```

期望：5 passed。

- [ ] **Step 5**：commit

```bash
git add src/main/java/com/wireless/agent/tools/ProfilerTool.java \
        src/test/java/com/wireless/agent/tools/ProfilerToolTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m1): profiler tool for data row count, null rate, and distribution"
```

---

### Task 6：SandboxTool — Spark SQL Dry-Run

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/SandboxTool.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/tools/SandboxToolTest.java`

提交生成的 Spark SQL 到 Docker spark-master 做 dry-run 预览。

- [ ] **Step 1**：在 `SandboxToolTest.java` 写失败测试：

```java
package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxToolTest {

    @Test
    void shouldHaveCorrectToolName() {
        var runner = new DockerCommandRunner();
        var tool = new SandboxTool(runner, "da-spark-master");
        assertThat(tool.name()).isEqualTo("sandbox");
    }

    @Test
    void shouldExtractSqlFromMarkdownBlock() {
        var code = """
                ```sql
                SELECT cell_id, AVG(rsrp_avg) AS avg_rsrp
                FROM dw.mr_5g_15min
                WHERE rsrp_avg < -110
                GROUP BY cell_id
                LIMIT 10;
                ```""";
        var sql = SandboxTool.extractSql(code);
        assertThat(sql).contains("SELECT");
        assertThat(sql).contains("LIMIT 10");
        assertThat(sql).doesNotContain("```");
    }

    @Test
    void shouldAppendLimitIfMissing() {
        var sql = "SELECT * FROM dw.mr_5g_15min WHERE rsrp_avg < -110;";
        var result = SandboxTool.ensureLimit(sql, 100);
        assertThat(result).contains("LIMIT 100");
    }

    @Test
    void shouldNotDoubleAppendLimit() {
        var sql = "SELECT * FROM t LIMIT 50;";
        var result = SandboxTool.ensureLimit(sql, 100);
        assertThat(result.toUpperCase()).containsOnlyOnce("LIMIT 50");
        assertThat(result.toUpperCase()).doesNotContain("LIMIT 100");
    }

    @Test
    void shouldBuildDryRunPreviewMessage() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec().name("test_view").businessDefinition("弱覆盖"));
        spec.sources(List.of(new Spec.SourceBinding().role("mr")
                .binding(Map.of("catalog", "hive", "table_or_topic", "dw.mr_5g_15min"))));

        var code = "```sql\nSELECT * FROM dw.mr_5g_15min LIMIT 10;\n```";
        var result = new SandboxTool(new DockerCommandRunner(), "da-spark-master")
                .dryRun(code, spec);

        // Without Docker running, should produce error but not crash
        assertThat(result).isNotNull();
        assertThat(result).containsKey("next_action");
    }
}
```

- [ ] **Step 2**：运行测试确认失败。

- [ ] **Step 3**：在 `SandboxTool.java` 写入实现：

```java
package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;

import java.util.*;
import java.util.regex.Pattern;

/** Submits generated Spark SQL to local Docker spark-master for dry-run preview. */
public class SandboxTool implements Tool {

    private static final Pattern SQL_BLOCK = Pattern.compile(
            "```sql\\s*\\n?(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern HAS_LIMIT = Pattern.compile(
            "\\bLIMIT\\s+\\d+", Pattern.CASE_INSENSITIVE);

    private final DockerCommandRunner runner;
    private final String sparkContainer;

    public SandboxTool(DockerCommandRunner runner, String sparkContainer) {
        this.runner = runner;
        this.sparkContainer = sparkContainer;
    }

    @Override
    public String name() { return "sandbox"; }

    @Override
    public String description() { return "提交 Spark SQL 到本地 Docker spark-master dry-run 预览"; }

    @Override
    public ToolResult run(Spec spec) {
        return ToolResult.fail("Use dryRun(code, spec) instead of run()");
    }

    /** Execute dry-run: extract SQL, add LIMIT if needed, submit to Spark, return preview. */
    public Map<String, Object> dryRun(String rawCode, Spec spec) {
        var sql = extractSql(rawCode);
        if (sql.isEmpty()) {
            return Map.of(
                "next_action", "sandbox_failed",
                "error", "No executable SQL found in generated code",
                "preview", ""
            );
        }

        sql = ensureLimit(sql, 100);

        try {
            var result = runner.exec(sparkContainer,
                    List.of("spark-sql", "--master", "spark://spark-master:7077", "-e", sql));
            if (result.isSuccess()) {
                var preview = truncate(result.stdout(), 2000);
                return Map.of(
                    "next_action", "dry_run_ok",
                    "preview", preview,
                    "rows", countLines(preview),
                    "spec_summary", specSummaryBrief(spec)
                );
            }
            return Map.of(
                "next_action", "sandbox_failed",
                "error", result.stderr(),
                "preview", result.stdout()
            );
        } catch (Exception e) {
            return Map.of(
                "next_action", "sandbox_failed",
                "error", e.getMessage(),
                "preview", ""
            );
        }
    }

    public static String extractSql(String rawCode) {
        var matcher = SQL_BLOCK.matcher(rawCode);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        // Fallback: treat entire content as SQL
        return rawCode.trim();
    }

    public static String ensureLimit(String sql, int limit) {
        if (HAS_LIMIT.matcher(sql).find()) {
            return sql;
        }
        // Add LIMIT before closing semicolon or at end
        var trimmed = sql.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + " LIMIT " + limit + ";";
    }

    private String specSummaryBrief(Spec spec) {
        var t = spec.target();
        return "目标: " + (t != null ? t.name() : "?")
                + " | 引擎: " + (spec.engineDecision() != null
                        ? spec.engineDecision().recommended() : "?");
    }

    private String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "\n... (truncated, " + s.length() + " chars total)";
    }

    private int countLines(String s) {
        return (int) s.lines().count();
    }
}
```

- [ ] **Step 4**：运行测试：

```bash
mvn test -Dtest=SandboxToolTest
```

期望：5 passed。

- [ ] **Step 5**：commit

```bash
git add src/main/java/com/wireless/agent/tools/SandboxTool.java \
        src/test/java/com/wireless/agent/tools/SandboxToolTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m1): sandbox tool for spark sql dry-run on docker spark-master"
```

---

### Task 7：AgentCore 接入真工具管线

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/core/AgentCore.java`
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/Main.java`

将 AgentCore 的 executeTools() 管线从 Mock → 升级为：Metadata → Profiler → EngineSelector → Codegen → Validator → Sandbox。

- [ ] **Step 1**：在 `AgentCore.java` 中修改字段和构造函数。

读取当前 `AgentCore.java`，做以下修改：

**(a) 字段区：将 `MockMetadataTool` 替换为 `HmsMetadataTool`，新增 `ProfilerTool`、`ValidatorTool`、`SandboxTool`：**

```java
private final HmsMetadataTool metadataTool;
private final ProfilerTool profilerTool;
private final CodegenTool codegenTool;
private final ValidatorTool validatorTool;
private final SandboxTool sandboxTool;
private final DockerCommandRunner cmdRunner;
```

同时添加 import：
```java
import com.wireless.agent.tools.*;
```

**(b) 构造函数：**

```java
public AgentCore(DeepSeekClient llmClient, Spec.TaskDirection taskDirection,
                 String hmsUri, String sparkContainer) {
    this.llmClient = llmClient;
    this.spec = new Spec(taskDirection);
    this.cmdRunner = new DockerCommandRunner();
    this.metadataTool = new HmsMetadataTool(hmsUri);
    this.profilerTool = new ProfilerTool(cmdRunner, sparkContainer);
    this.codegenTool = new CodegenTool(llmClient);
    this.validatorTool = new ValidatorTool();
    this.sandboxTool = new SandboxTool(cmdRunner, sparkContainer);
}

public AgentCore(DeepSeekClient llmClient) {
    this(llmClient, Spec.TaskDirection.FORWARD_ETL,
         "thrift://hive-metastore:9083", "da-spark-master");
}
```

**(c) executeTools() 方法：替换为增强管线：**

```java
private Map<String, Object> executeTools() {
    // 1. Metadata lookup — fill schema for each source
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

    // 2. Profiler — sample data for evidence
    var profileResult = profilerTool.run(spec);
    if (profileResult.success()) {
        @SuppressWarnings("unchecked")
        var evidence = (Map<String, Object>) profileResult.data();
        spec.evidence().add(new Spec.Evidence("data_profile",
                spec.sources().stream()
                        .map(s -> s.binding().getOrDefault("table_or_topic", "").toString())
                        .filter(t -> !t.isEmpty())
                        .findFirst().orElse("unknown"),
                evidence));
    }

    // 3. Engine selection
    if (spec.engineDecision() == null || spec.engineDecision().recommended().isEmpty()) {
        spec.engineDecision(EngineSelector.select(spec));
    }

    // 4. Codegen
    var codegenResult = codegenTool.run(spec);
    if (!codegenResult.success()) {
        return Map.of(
            "next_action", "ask_clarifying",
            "clarifying_question", "代码生成出错: " + codegenResult.error(),
            "code", "",
            "spec_summary", specSummary()
        );
    }
    @SuppressWarnings("unchecked")
    var codegenData = (Map<String, Object>) codegenResult.data();
    var code = codegenData.getOrDefault("code", "").toString();

    // 5. Validate
    var validation = validatorTool.validate(code, spec);
    if (!validation.success()) {
        return Map.of(
            "next_action", "ask_clarifying",
            "clarifying_question", "SQL 校验失败: " + validation.error(),
            "code", code,
            "spec_summary", specSummary()
        );
    }
    @SuppressWarnings("unchecked")
    var validationData = (Map<String, Object>) validation.data();
    @SuppressWarnings("unchecked")
    var warnings = (List<String>) validationData.get("warnings");

    // 6. Sandbox dry-run
    var dryRunResult = sandboxTool.dryRun(code, spec);

    spec.state(Spec.SpecState.CODEGEN_DONE);

    var response = new LinkedHashMap<String, Object>();
    response.put("next_action", dryRunResult.getOrDefault("next_action", "code_done"));
    response.put("code", code);
    response.put("engine", spec.engineDecision().recommended());
    response.put("reasoning", spec.engineDecision().reasoning());
    response.put("spec_summary", specSummary());
    response.put("warnings", warnings != null ? warnings : List.of());
    response.put("preview", dryRunResult.getOrDefault("preview", ""));
    response.put("error", dryRunResult.getOrDefault("error", ""));
    return response;
}
```

- [ ] **Step 2**：在 `Main.java` 中更新构造函数调用。读取 Main.java，修改 AgentCore 创建：

```java
// 旧: var agent = new AgentCore(llmClient);
// 新:
var hmsUri = System.getenv().getOrDefault("HMS_URI", "thrift://hive-metastore:9083");
var sparkContainer = System.getenv().getOrDefault("SPARK_CONTAINER", "da-spark-master");
var agent = new AgentCore(llmClient, Spec.TaskDirection.FORWARD_ETL, hmsUri, sparkContainer);
```

在两处（runDemo 和 runInteractive）都做修改。

- [ ] **Step 3**：运行全套测试确认编译通过且无回归：

```bash
mvn test
```

- [ ] **Step 4**：commit

```bash
git add src/main/java/com/wireless/agent/core/AgentCore.java \
        src/main/java/com/wireless/agent/Main.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m1): wire real tools into agent core pipeline (metadata→profiler→codegen→validate→sandbox)"
```

---

### Task 8：集成测试 — 栈内 dry-run 验证

**Files:**
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/IntegrationTest.java`
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/core/AgentCoreTest.java`

更新集成测试，验证新工具管线。

- [ ] **Step 1**：更新 `AgentCoreTest.java` 适配新构造函数。将 `FakeLLMClient` 测试中的 AgentCore 创建改为使用 HMS URI 和 spark 容器参数，但测试环境用 mock fallback（HMS unreachable 时自动 fallback 到 MockMetadataTool）。

修改构造函数调用：
```java
// 旧: var agent = new AgentCore(new FakeLLMClient(...));
// 新: var agent = new AgentCore(new FakeLLMClient(...),
//         Spec.TaskDirection.FORWARD_ETL,
//         "thrift://nonexistent:9999",  // triggers fallback to mock
//         "da-spark-master");
```

- [ ] **Step 2**：在 `IntegrationTest.java` 中添加端到端 dry-run 测试：

```java
@Test
void shouldRunFullPipelineWithRealToolsAndFallback() {
    // Uses HMS fallback (mock schemas) + real Validator + Sandbox
    var agent = new AgentCore(
            null,  // no LLM, uses mock extraction
            Spec.TaskDirection.FORWARD_ETL,
            "thrift://nonexistent:9999",  // HMS fallback → mock
            "da-spark-master"
    );
    var result = agent.processMessage("给我近30天每个区县5G弱覆盖小区清单");

    assertThat(result.get("next_action"))
            .isIn("code_done", "dry_run_ok", "sandbox_failed");
    assertThat(result.get("code")).isNotNull();
    assertThat(result.get("code").toString()).isNotEmpty();
    // Warnings and preview should be present (even if sandbox fails because Docker isn't running)
    assertThat(result).containsKey("warnings");
    assertThat(result).containsKey("preview");
}
```

- [ ] **Step 3**：运行全套测试：

```bash
mvn test
```

期望：所有已有测试通过，新集成测试通过。

- [ ] **Step 4**：commit

```bash
git add src/test/java/com/wireless/agent/IntegrationTest.java \
        src/test/java/com/wireless/agent/core/AgentCoreTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "test(m1): integration tests for real tool pipeline and dry-run"
```

---

### Task 9：配置文件与环境变量

**Files:**
- Create: `D:/agent-code/data-agent/src/main/resources/agent.properties`
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/Main.java`

集中管理 M1 的配置项。

- [ ] **Step 1**：在 `src/main/resources/agent.properties` 写入：

```properties
# M1 — Real Tool Configuration
hms.uri=thrift://hive-metastore:9083
spark.container=da-spark-master
spark.master=spark://spark-master:7077
profiler.top_k=5
sandbox.preview_limit=100
sandbox.timeout_seconds=120
```

- [ ] **Step 2**：在 `Main.java` 中添加配置加载（使用 `java.util.Properties` 读 classpath 资源，环境变量覆盖）：

```java
import java.util.Properties;
import java.io.InputStream;

// In main():
var props = new Properties();
try (var in = Main.class.getClassLoader().getResourceAsStream("agent.properties")) {
    if (in != null) props.load(in);
}

var hmsUri = System.getenv().getOrDefault("HMS_URI",
        props.getProperty("hms.uri", "thrift://hive-metastore:9083"));
var sparkContainer = System.getenv().getOrDefault("SPARK_CONTAINER",
        props.getProperty("spark.container", "da-spark-master"));
```

- [ ] **Step 3**：验证编译：

```bash
mvn compile
```

- [ ] **Step 4**：commit

```bash
git add src/main/resources/agent.properties src/main/java/com/wireless/agent/Main.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m1): agent configuration properties file with env var override"
```

---

### Task 10：端到端验证与文档

**Files:**
- Modify: `D:/agent-code/data-agent/README.md`

- [ ] **Step 1**：确保 M0a Docker 栈已启动并加载了样例数据：

```bash
cd D:/agent-code/data-agent
bash scripts/up.sh
bash scripts/load-sample-data.sh
```

- [ ] **Step 2**：运行端到端 dry-run 演示：

```bash
mvn exec:java -Dexec.mainClass="com.wireless.agent.Main" -Dexec.args="--demo --no-llm"
```

期望输出包含：
- `[INFO]` metadata lookup via HMS (或 fallback 日志)
- `[Validator]` warnings (如有)
- `[Sandbox]` preview 数据（若 Docker 运行）或 error 消息
- Spark SQL 代码

- [ ] **Step 3**：更新 README.md 追加 M1 文档：

```markdown
## M1 — 真工具接通

**新增工具：**

| 工具 | 说明 | 连接方式 |
|------|------|----------|
| HmsMetadataTool | 真 HMS 查询 | Hive Metastore Thrift（默认 `thrift://hive-metastore:9083`），HMS 不可用时自动 fallback 到 Mock |
| ProfilerTool | 数据画像 | Docker exec spark-sql 查询行数/null率/分布 |
| ValidatorTool | SQL 校验 | 纯 Java 解析：提取 SQL block、检查表引用、诊断警告 |
| SandboxTool | Dry-run 执行 | Docker exec spark-sql 提交到 spark-master，LIMIT 100 预览 |

**AgentCore 管线：** Metadata → Profiler → EngineSelector → Codegen → Validator → Sandbox

**配置：** `src/main/resources/agent.properties` 或环境变量覆盖

```bash
# 启动 Docker 栈 + 加载样例数据
bash scripts/up.sh && bash scripts/load-sample-data.sh

# 运行 M1 演示（dry-run 在真 Spark 上执行）
mvn exec:java -Dexec.mainClass="com.wireless.agent.Main" -Dexec.args="--demo --no-llm"

# 使用 DeepSeek API
mvn exec:java -Dexec.mainClass="com.wireless.agent.Main" -Dexec.args="--demo"
```
```

- [ ] **Step 4**：最后跑一次全套测试并 push：

```bash
mvn test && git add README.md && \
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" \
  commit -m "docs(m1): add real-tool integration quickstart to readme" && \
git push
```

---

## M1 完成标准（DoD）

- [ ] `mvn test` 全部测试通过
- [ ] HmsMetadataTool 能连接真实 HMS（Docker 栈运行时）并返回表 schema
- [ ] HMS 不可用时自动降级到 MockMetadataTool
- [ ] ValidatorTool 校验生成的 SQL：提取 SQL block、检查缺失表引用、输出 warnings
- [ ] SandboxTool 在 Docker 栈运行时提交 Spark SQL 到 spark-master 并返回预览结果
- [ ] AgentCore 管线按顺序执行：Metadata → Profiler → EngineSelector → Codegen → Validator → Sandbox
- [ ] `--demo --no-llm` 在 Docker 栈上端到端跑通（含 dry-run 预览输出）

---

## 后续

M1 完成后 → M2（Domain Knowledge Base + Sample Baseline Service）。
