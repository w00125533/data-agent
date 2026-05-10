# Data Agent — 无线网络感知评估

**前置需求:** Docker + Docker Compose v2

## 快速开始

```bash
# 1. 准备配置
cp .env.example .env
# 编辑 .env,至少填入 DEEPSEEK_API_KEY

# 2. 拉起本地验证栈
./scripts/up.sh

# 3. 健康检查
./scripts/verify.sh

# 4. 准备样例数据
./scripts/load-sample-data.sh

# 5. 停止与清理
./scripts/down.sh
```

详见 `docs/superpowers/specs/2026-05-09-wireless-perception-data-agent-design.md`。

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

## M1 — Real Tools Integration

**New tools:**

| Tool | Description | Connection |
|------|-------------|------------|
| HmsMetadataTool | Real HMS table schema lookup | Hive Metastore Thrift (default `thrift://hive-metastore:9083`), auto-fallback to mock when HMS unreachable |
| ProfilerTool | Data profiling (row count, null rate, distribution) | Docker exec spark-sql on spark-master |
| ValidatorTool | SQL syntax/schema validation | Pure Java: extracts SQL block, checks table references, reports warnings |
| SandboxTool | Spark SQL dry-run preview | Docker exec spark-sql on spark-master, LIMIT 100 preview |

**AgentCore pipeline:** Metadata → Profiler → EngineSelector → Codegen → Validator → Sandbox

**Configuration:** `src/main/resources/agent.properties` with environment variable override

```bash
# Start Docker stack + load sample data
bash scripts/up.sh && bash scripts/load-sample-data.sh

# Run M1 demo (dry-run on real Spark)
mvn exec:java -Dexec.mainClass="com.wireless.agent.Main" -Dexec.args="--demo --no-llm"

# With DeepSeek API
mvn exec:java -Dexec.mainClass="com.wireless.agent.Main" -Dexec.args="--demo"
```

## M2 -- Domain Knowledge Base + Sample Baseline

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

**AgentCore LLM prompt now includes KB context** -- the system prompt is augmented with relevant domain methodology definitions based on the current KPI family detected from the user's input.

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
