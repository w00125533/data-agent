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
