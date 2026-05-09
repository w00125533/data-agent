# M0a 基础设施栈 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal**：在本地用 docker-compose 一键拉起 HMS / HDFS / Kafka / StarRocks / Spark / Flink，准备 1-2 张样例 Hive 表 + 1 个 Kafka topic 的演示数据，验证 DeepSeek API 联通——为后续 M0b（Agent 骨架）提供完整的"可执行底座"。

**Architecture**：所有服务以容器形式编排，HMS 用 Postgres 作元数据后端、Hive 4.0 作元数据服务；HDFS 由 Hadoop 3.3.6 的 namenode + datanode 提供存储；Spark 与 Flink 共享同一份 HMS catalog 与 HDFS；StarRocks 用 allin1 镜像（fe+be 一体）简化部署；Kafka 用 KRaft 单节点模式去掉 Zookeeper 依赖；DeepSeek 走外部 API（不在 docker 栈内）。

**Tech Stack**：Docker / docker-compose v2 / Bash / 少量 Python（用于 DeepSeek API 联通脚本）。

---

## 文件结构（M0a 完成后的目录形态）

```
data-agent/
├── docker-compose.yml              # 主编排文件
├── .env.example                    # 环境变量模板（DeepSeek API、镜像版本等）
├── .env                            # 本地实际配置（gitignore）
├── docker/
│   ├── hadoop/
│   │   ├── Dockerfile              # 基于 apache/hadoop:3.3.6,加入工具脚本
│   │   ├── core-site.xml
│   │   ├── hdfs-site.xml
│   │   └── entrypoint.sh
│   ├── hive/
│   │   ├── Dockerfile              # 基于 apache/hive:4.0.0
│   │   ├── hive-site.xml
│   │   └── entrypoint.sh           # 等 postgres 起来 + schematool 初始化
│   ├── spark/
│   │   ├── spark-defaults.conf
│   │   └── hive-site.xml           # 复用 hive 的 site,只读
│   └── flink/
│       ├── flink-conf.yaml
│       └── sql-conf/
│           └── hive-catalog.sql    # 注册 HMS catalog 的 init SQL
├── scripts/
│   ├── up.sh                       # 拉起整套栈
│   ├── down.sh                     # 停止 + 清理
│   ├── verify.sh                   # 健康检查所有服务
│   ├── load-sample-data.sh         # 准备 sample data
│   └── deepseek-ping.py            # 验 API 联通
├── samples/
│   ├── data/
│   │   ├── mr_5g_15min.csv         # MR 样例(cell × 15min × KPI)
│   │   └── engineering_param.csv   # 工参样例
│   ├── kafka/
│   │   └── signaling_events.jsonl  # Kafka 消息样例
│   ├── ddl/
│   │   ├── 01_create_database.sql
│   │   ├── 02_create_mr_table.sql
│   │   └── 03_create_eng_param_table.sql
│   └── jobs/
│       └── verify-spark-job.py     # 一个端到端 spark-submit 样例
├── tests/
│   └── integration/
│       └── test_stack_health.sh    # 集成验证(被 verify.sh 调用)
├── .gitignore                      # 已存在,补 .env / volumes/ 等
└── README.md                       # 拉起指引
```

**关键设计：**
- 所有可变配置（端口、镜像版本、HMS 密码、HDFS 数据卷路径、DeepSeek API key）走 `.env`；`.env.example` 是范例
- 卷映射用 `./volumes/*` 子目录，gitignore 排除
- 每个 service 在 docker-compose 里都有 healthcheck，让依赖关系（depends_on with condition: service_healthy）准确生效
- 一个共享 docker network `data-agent-net`，所有 service 互相可访问

---

## Task 1：项目骨架与环境变量模板

**Files:**
- Create: `D:/agent-code/data-agent/.env.example`
- Create: `D:/agent-code/data-agent/README.md`
- Modify: `D:/agent-code/data-agent/.gitignore`

- [ ] **Step 1**：创建 `.env.example`，写入：

```dotenv
# === Image versions ===
POSTGRES_IMAGE=postgres:15-alpine
HIVE_IMAGE=apache/hive:4.0.0
HADOOP_IMAGE=apache/hadoop:3.3.6
SPARK_IMAGE=bitnami/spark:3.5.0
FLINK_IMAGE=flink:1.18-scala_2.12-java11
KAFKA_IMAGE=apache/kafka:3.7.0
STARROCKS_IMAGE=starrocks/allin1-ubuntu:3.2-latest

# === Postgres (HMS metadata backend) ===
POSTGRES_USER=hive
POSTGRES_PASSWORD=hivepw
POSTGRES_DB=metastore

# === HMS ===
HMS_PORT=9083

# === HDFS ===
HDFS_NAMENODE_PORT=9870
HDFS_NAMENODE_RPC_PORT=8020

# === Kafka ===
KAFKA_BROKER_PORT=9092

# === StarRocks ===
STARROCKS_QUERY_PORT=9030
STARROCKS_HTTP_PORT=8030

# === Spark ===
SPARK_MASTER_PORT=7077
SPARK_MASTER_WEBUI_PORT=8080

# === Flink ===
FLINK_JOBMANAGER_PORT=8081

# === DeepSeek API (external) ===
DEEPSEEK_API_BASE=https://api.deepseek.com/v1
DEEPSEEK_API_KEY=sk-replace-me
DEEPSEEK_MODEL=deepseek-chat
```

- [ ] **Step 2**：创建 `README.md`，写入：

```markdown
# Data Agent — 无线网络感知评估

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
```

- [ ] **Step 3**：在 `.gitignore` 末尾追加：

```
# Local stack
.env
volumes/
*.bak
```

- [ ] **Step 4**：commit

```bash
cd D:/agent-code/data-agent
git add .env.example README.md .gitignore
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "chore(m0a): project skeleton with .env template and README"
```

---

## Task 2：docker-compose 骨架 + Postgres（HMS 元数据后端）

**Files:**
- Create: `D:/agent-code/data-agent/docker-compose.yml`
- Create: `D:/agent-code/data-agent/scripts/up.sh`
- Create: `D:/agent-code/data-agent/scripts/down.sh`

- [ ] **Step 1**：创建 `docker-compose.yml`，写入骨架 + postgres 服务：

```yaml
name: data-agent

networks:
  data-agent-net:
    driver: bridge

volumes:
  pg-data:
  hdfs-name:
  hdfs-data:

services:
  postgres:
    image: ${POSTGRES_IMAGE}
    container_name: da-postgres
    environment:
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      POSTGRES_DB: ${POSTGRES_DB}
    volumes:
      - pg-data:/var/lib/postgresql/data
    networks:
      - data-agent-net
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 5s
      timeout: 5s
      retries: 10
```

- [ ] **Step 2**：创建 `scripts/up.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ ! -f .env ]]; then
  echo "ERROR: .env not found. Run: cp .env.example .env" >&2
  exit 1
fi

docker compose --env-file .env up -d "$@"
echo "Stack starting. Run ./scripts/verify.sh to check health."
```

- [ ] **Step 3**：创建 `scripts/down.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# -v 删除 volumes(重置数据);如要保留数据,移除 -v
docker compose --env-file .env down "$@"
```

- [ ] **Step 4**：本地测试 postgres 启动：

```bash
cd D:/agent-code/data-agent
cp .env.example .env
chmod +x scripts/up.sh scripts/down.sh
./scripts/up.sh postgres
docker compose --env-file .env ps postgres
```

期望：`STATUS` 列显示 `healthy`（10-20 秒后）。

- [ ] **Step 5**：连一下确认 SQL 能跑：

```bash
docker compose --env-file .env exec postgres \
  psql -U hive -d metastore -c "SELECT version();"
```

期望：返回 PostgreSQL 15.x 版本字符串。

- [ ] **Step 6**：清理 + commit

```bash
./scripts/down.sh
git add docker-compose.yml scripts/up.sh scripts/down.sh
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0a): docker-compose skeleton with postgres for HMS"
```

---

## Task 3：HDFS（NameNode + DataNode）

**Files:**
- Create: `D:/agent-code/data-agent/docker/hadoop/core-site.xml`
- Create: `D:/agent-code/data-agent/docker/hadoop/hdfs-site.xml`
- Modify: `D:/agent-code/data-agent/docker-compose.yml`

- [ ] **Step 1**：创建 `docker/hadoop/core-site.xml`：

```xml
<?xml version="1.0"?>
<configuration>
  <property>
    <name>fs.defaultFS</name>
    <value>hdfs://hdfs-namenode:8020</value>
  </property>
  <property>
    <name>hadoop.proxyuser.hive.hosts</name>
    <value>*</value>
  </property>
  <property>
    <name>hadoop.proxyuser.hive.groups</name>
    <value>*</value>
  </property>
</configuration>
```

- [ ] **Step 2**：创建 `docker/hadoop/hdfs-site.xml`：

```xml
<?xml version="1.0"?>
<configuration>
  <property>
    <name>dfs.replication</name>
    <value>1</value>
  </property>
  <property>
    <name>dfs.namenode.name.dir</name>
    <value>/hadoop/dfs/name</value>
  </property>
  <property>
    <name>dfs.datanode.data.dir</name>
    <value>/hadoop/dfs/data</value>
  </property>
  <property>
    <name>dfs.permissions.enabled</name>
    <value>false</value>
  </property>
  <property>
    <name>dfs.namenode.rpc-bind-host</name>
    <value>0.0.0.0</value>
  </property>
  <property>
    <name>dfs.datanode.use.datanode.hostname</name>
    <value>true</value>
  </property>
</configuration>
```

- [ ] **Step 3**：在 `docker-compose.yml` 的 `services:` 段下增加两个服务（追加在 postgres 之后）：

```yaml
  hdfs-namenode:
    image: ${HADOOP_IMAGE}
    container_name: da-hdfs-namenode
    hostname: hdfs-namenode
    user: root
    environment:
      HADOOP_HOME: /opt/hadoop
    command:
      - bash
      - -c
      - |
        if [ ! -f /hadoop/dfs/name/current/VERSION ]; then
          $$HADOOP_HOME/bin/hdfs namenode -format -nonInteractive -force
        fi
        $$HADOOP_HOME/bin/hdfs namenode
    volumes:
      - hdfs-name:/hadoop/dfs/name
      - ./docker/hadoop/core-site.xml:/opt/hadoop/etc/hadoop/core-site.xml:ro
      - ./docker/hadoop/hdfs-site.xml:/opt/hadoop/etc/hadoop/hdfs-site.xml:ro
    ports:
      - "${HDFS_NAMENODE_PORT}:9870"
      - "${HDFS_NAMENODE_RPC_PORT}:8020"
    networks:
      - data-agent-net
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:9870 || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 15
      start_period: 30s

  hdfs-datanode:
    image: ${HADOOP_IMAGE}
    container_name: da-hdfs-datanode
    hostname: hdfs-datanode
    user: root
    environment:
      HADOOP_HOME: /opt/hadoop
    command:
      - bash
      - -c
      - $$HADOOP_HOME/bin/hdfs datanode
    volumes:
      - hdfs-data:/hadoop/dfs/data
      - ./docker/hadoop/core-site.xml:/opt/hadoop/etc/hadoop/core-site.xml:ro
      - ./docker/hadoop/hdfs-site.xml:/opt/hadoop/etc/hadoop/hdfs-site.xml:ro
    networks:
      - data-agent-net
    depends_on:
      hdfs-namenode:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:9864 || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 15
      start_period: 30s
```

- [ ] **Step 4**：拉起 HDFS：

```bash
./scripts/up.sh hdfs-namenode hdfs-datanode
sleep 30
docker compose --env-file .env ps hdfs-namenode hdfs-datanode
```

期望：两个容器均 `healthy`。如果 NameNode 起不来，看日志：`docker compose logs hdfs-namenode`。

- [ ] **Step 5**：验证 HDFS 可写：

```bash
docker compose --env-file .env exec hdfs-namenode \
  bash -c '/opt/hadoop/bin/hdfs dfs -mkdir -p /user/hive/warehouse && /opt/hadoop/bin/hdfs dfs -mkdir -p /baseline && /opt/hadoop/bin/hdfs dfs -ls /'
```

期望输出包含 `/user` 和 `/baseline` 两个目录。

- [ ] **Step 6**：commit

```bash
./scripts/down.sh
git add docker/hadoop/ docker-compose.yml
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0a): add HDFS namenode and datanode"
```

---

## Task 4：Hive Metastore

**Files:**
- Create: `D:/agent-code/data-agent/docker/hive/hive-site.xml`
- Create: `D:/agent-code/data-agent/docker/hive/entrypoint.sh`
- Modify: `D:/agent-code/data-agent/docker-compose.yml`

- [ ] **Step 1**：创建 `docker/hive/hive-site.xml`：

```xml
<?xml version="1.0"?>
<configuration>
  <property>
    <name>javax.jdo.option.ConnectionURL</name>
    <value>jdbc:postgresql://postgres:5432/metastore</value>
  </property>
  <property>
    <name>javax.jdo.option.ConnectionDriverName</name>
    <value>org.postgresql.Driver</value>
  </property>
  <property>
    <name>javax.jdo.option.ConnectionUserName</name>
    <value>hive</value>
  </property>
  <property>
    <name>javax.jdo.option.ConnectionPassword</name>
    <value>hivepw</value>
  </property>
  <property>
    <name>hive.metastore.warehouse.dir</name>
    <value>hdfs://hdfs-namenode:8020/user/hive/warehouse</value>
  </property>
  <property>
    <name>hive.metastore.uris</name>
    <value>thrift://hive-metastore:9083</value>
  </property>
  <property>
    <name>hive.metastore.event.db.notification.api.auth</name>
    <value>false</value>
  </property>
  <property>
    <name>fs.defaultFS</name>
    <value>hdfs://hdfs-namenode:8020</value>
  </property>
</configuration>
```

- [ ] **Step 2**：创建 `docker/hive/entrypoint.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

echo "Waiting for postgres..."
until pg_isready -h postgres -U hive -d metastore -t 2 >/dev/null 2>&1; do
  sleep 2
done

# 初始化 schema(幂等:只在首次跑)
SCHEMA_INIT_FLAG=/tmp/.hms-schema-initialized
if [[ ! -f "$SCHEMA_INIT_FLAG" ]]; then
  echo "Initializing HMS schema in postgres..."
  /opt/hive/bin/schematool -dbType postgres -initSchema || true
  touch "$SCHEMA_INIT_FLAG"
fi

echo "Starting Hive Metastore on port 9083..."
exec /opt/hive/bin/hive --service metastore
```

- [ ] **Step 3**：在 `docker-compose.yml` 追加 hive-metastore 服务：

```yaml
  hive-metastore:
    image: ${HIVE_IMAGE}
    container_name: da-hive-metastore
    hostname: hive-metastore
    user: root
    environment:
      SERVICE_NAME: metastore
      DB_DRIVER: postgres
    command: ["bash", "/opt/hive/entrypoint.sh"]
    volumes:
      - ./docker/hive/hive-site.xml:/opt/hive/conf/hive-site.xml:ro
      - ./docker/hive/entrypoint.sh:/opt/hive/entrypoint.sh:ro
      - ./docker/hadoop/core-site.xml:/opt/hadoop/etc/hadoop/core-site.xml:ro
    ports:
      - "${HMS_PORT}:9083"
    networks:
      - data-agent-net
    depends_on:
      postgres:
        condition: service_healthy
      hdfs-namenode:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "ss -tlnp | grep -q ':9083' || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 20
      start_period: 60s
```

> 注：`apache/hive:4.0.0` 镜像里 `pg_isready` 可能没装。如 entrypoint 报 command not found，把 `until pg_isready ...` 改成 `until nc -z postgres 5432; do sleep 2; done`。

- [ ] **Step 4**：拉起 HMS：

```bash
./scripts/up.sh postgres hdfs-namenode hdfs-datanode hive-metastore
sleep 60
docker compose --env-file .env ps hive-metastore
docker compose --env-file .env logs hive-metastore | tail -30
```

期望：HMS 容器 `healthy`，日志含 "Started ServerSocket" 或类似。

- [ ] **Step 5**：用 thrift 探一下端口：

```bash
docker compose --env-file .env exec hive-metastore \
  bash -c "ss -tlnp | grep 9083"
```

期望：监听 `0.0.0.0:9083`。

- [ ] **Step 6**：commit

```bash
./scripts/down.sh
git add docker/hive/ docker-compose.yml
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0a): add hive metastore with postgres backend"
```

---

## Task 5：Spark（master + worker）

**Files:**
- Create: `D:/agent-code/data-agent/docker/spark/spark-defaults.conf`
- Modify: `D:/agent-code/data-agent/docker-compose.yml`

- [ ] **Step 1**：创建 `docker/spark/spark-defaults.conf`：

```properties
spark.master                              spark://spark-master:7077
spark.sql.catalogImplementation           hive
spark.sql.hive.metastore.version          4.0.0
spark.sql.hive.metastore.jars             builtin
spark.hadoop.hive.metastore.uris          thrift://hive-metastore:9083
spark.hadoop.fs.defaultFS                 hdfs://hdfs-namenode:8020
spark.sql.warehouse.dir                   hdfs://hdfs-namenode:8020/user/hive/warehouse
spark.serializer                          org.apache.spark.serializer.KryoSerializer
spark.eventLog.enabled                    false
```

- [ ] **Step 2**：在 `docker-compose.yml` 追加 spark-master + spark-worker：

```yaml
  spark-master:
    image: ${SPARK_IMAGE}
    container_name: da-spark-master
    hostname: spark-master
    environment:
      SPARK_MODE: master
      SPARK_MASTER_PORT: 7077
      SPARK_MASTER_WEBUI_PORT: 8080
    volumes:
      - ./docker/spark/spark-defaults.conf:/opt/bitnami/spark/conf/spark-defaults.conf:ro
      - ./docker/hive/hive-site.xml:/opt/bitnami/spark/conf/hive-site.xml:ro
      - ./docker/hadoop/core-site.xml:/opt/bitnami/spark/conf/core-site.xml:ro
      - ./docker/hadoop/hdfs-site.xml:/opt/bitnami/spark/conf/hdfs-site.xml:ro
    ports:
      - "${SPARK_MASTER_PORT}:7077"
      - "${SPARK_MASTER_WEBUI_PORT}:8080"
    networks:
      - data-agent-net
    depends_on:
      hive-metastore:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:8080 || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 20
      start_period: 30s

  spark-worker:
    image: ${SPARK_IMAGE}
    container_name: da-spark-worker
    environment:
      SPARK_MODE: worker
      SPARK_MASTER_URL: spark://spark-master:7077
      SPARK_WORKER_MEMORY: 2G
      SPARK_WORKER_CORES: 2
    volumes:
      - ./docker/spark/spark-defaults.conf:/opt/bitnami/spark/conf/spark-defaults.conf:ro
      - ./docker/hive/hive-site.xml:/opt/bitnami/spark/conf/hive-site.xml:ro
      - ./docker/hadoop/core-site.xml:/opt/bitnami/spark/conf/core-site.xml:ro
      - ./docker/hadoop/hdfs-site.xml:/opt/bitnami/spark/conf/hdfs-site.xml:ro
    networks:
      - data-agent-net
    depends_on:
      spark-master:
        condition: service_healthy
```

- [ ] **Step 3**：拉起 Spark：

```bash
./scripts/up.sh
sleep 30
docker compose --env-file .env ps spark-master spark-worker
```

期望：spark-master `healthy`、spark-worker 启动。Web UI：http://localhost:8080，可看到 1 个 worker 注册。

- [ ] **Step 4**：跑一个内置示例验证：

```bash
docker compose --env-file .env exec spark-master \
  spark-submit --master spark://spark-master:7077 \
    --class org.apache.spark.examples.SparkPi \
    /opt/bitnami/spark/examples/jars/spark-examples_2.12-3.5.0.jar 10
```

期望：日志含 `Pi is roughly 3.14...`。

- [ ] **Step 5**：commit

```bash
./scripts/down.sh
git add docker/spark/ docker-compose.yml
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0a): add spark master and worker with hms+hdfs config"
```

---

## Task 6：Flink（jobmanager + taskmanager）

**Files:**
- Create: `D:/agent-code/data-agent/docker/flink/flink-conf.yaml`
- Modify: `D:/agent-code/data-agent/docker-compose.yml`

- [ ] **Step 1**：创建 `docker/flink/flink-conf.yaml`：

```yaml
jobmanager.rpc.address: flink-jobmanager
jobmanager.rpc.port: 6123
jobmanager.memory.process.size: 1600m
taskmanager.memory.process.size: 1728m
taskmanager.numberOfTaskSlots: 2
parallelism.default: 1
rest.port: 8081
rest.address: 0.0.0.0
rest.bind-address: 0.0.0.0
state.backend: hashmap
execution.checkpointing.interval: 60000
execution.checkpointing.mode: EXACTLY_ONCE
```

- [ ] **Step 2**：在 `docker-compose.yml` 追加 flink-jobmanager + flink-taskmanager：

```yaml
  flink-jobmanager:
    image: ${FLINK_IMAGE}
    container_name: da-flink-jobmanager
    hostname: flink-jobmanager
    command: jobmanager
    environment:
      - |
        FLINK_PROPERTIES=
        jobmanager.rpc.address: flink-jobmanager
    volumes:
      - ./docker/flink/flink-conf.yaml:/opt/flink/conf/flink-conf.yaml:ro
      - ./docker/hive/hive-site.xml:/opt/flink/conf/hive-site.xml:ro
    ports:
      - "${FLINK_JOBMANAGER_PORT}:8081"
    networks:
      - data-agent-net
    depends_on:
      hive-metastore:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:8081/overview || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 20
      start_period: 30s

  flink-taskmanager:
    image: ${FLINK_IMAGE}
    container_name: da-flink-taskmanager
    command: taskmanager
    environment:
      - |
        FLINK_PROPERTIES=
        jobmanager.rpc.address: flink-jobmanager
    volumes:
      - ./docker/flink/flink-conf.yaml:/opt/flink/conf/flink-conf.yaml:ro
    networks:
      - data-agent-net
    depends_on:
      flink-jobmanager:
        condition: service_healthy
```

- [ ] **Step 3**：拉起 Flink：

```bash
./scripts/up.sh
sleep 30
docker compose --env-file .env ps flink-jobmanager flink-taskmanager
curl -s http://localhost:8081/overview | head -20
```

期望：jobmanager `healthy`，`/overview` 返回 JSON，`taskmanagers: 1`。

- [ ] **Step 4**：跑一个内置 example 验证：

```bash
docker compose --env-file .env exec flink-jobmanager \
  bash -c "flink run /opt/flink/examples/batch/WordCount.jar"
```

期望：返回单词计数结果（如 `the 3` 等）。

- [ ] **Step 5**：commit

```bash
./scripts/down.sh
git add docker/flink/ docker-compose.yml
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0a): add flink jobmanager and taskmanager"
```

---

## Task 7：Kafka（KRaft 单节点）

**Files:**
- Modify: `D:/agent-code/data-agent/docker-compose.yml`

- [ ] **Step 1**：在 `docker-compose.yml` 追加 kafka 服务：

```yaml
  kafka:
    image: ${KAFKA_IMAGE}
    container_name: da-kafka
    hostname: kafka
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_LOG_DIRS: /var/lib/kafka/data
      CLUSTER_ID: MkU3OEVBNTcwNTJENDM2Qk
    ports:
      - "${KAFKA_BROKER_PORT}:9092"
    networks:
      - data-agent-net
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1 || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 20
      start_period: 30s
```

- [ ] **Step 2**：拉起 Kafka：

```bash
./scripts/up.sh kafka
sleep 30
docker compose --env-file .env ps kafka
```

期望：kafka `healthy`。

- [ ] **Step 3**：建一个 topic 验证：

```bash
docker compose --env-file .env exec kafka \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --create --topic m0a-smoke --partitions 1 --replication-factor 1
docker compose --env-file .env exec kafka \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

期望：列表输出含 `m0a-smoke`。

- [ ] **Step 4**：produce/consume 一条消息：

```bash
docker compose --env-file .env exec kafka bash -c \
  "echo 'hello-m0a' | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic m0a-smoke"

docker compose --env-file .env exec kafka \
  /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic m0a-smoke --from-beginning --max-messages 1 --timeout-ms 5000
```

期望：consumer 输出 `hello-m0a`。

- [ ] **Step 5**：commit

```bash
./scripts/down.sh
git add docker-compose.yml
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0a): add kafka in kraft single-node mode"
```

---

## Task 8：StarRocks（allin1 单容器）

**Files:**
- Modify: `D:/agent-code/data-agent/docker-compose.yml`

- [ ] **Step 1**：在 `docker-compose.yml` 追加 starrocks 服务：

```yaml
  starrocks:
    image: ${STARROCKS_IMAGE}
    container_name: da-starrocks
    hostname: starrocks
    ports:
      - "${STARROCKS_QUERY_PORT}:9030"
      - "${STARROCKS_HTTP_PORT}:8030"
    networks:
      - data-agent-net
    healthcheck:
      test: ["CMD-SHELL", "mysql -h127.0.0.1 -P9030 -uroot -e 'SHOW DATABASES;' >/dev/null 2>&1 || exit 1"]
      interval: 15s
      timeout: 10s
      retries: 20
      start_period: 90s
```

> 注：StarRocks allin1 镜像首次启动需要 60-90 秒做初始化。

- [ ] **Step 2**：拉起 StarRocks：

```bash
./scripts/up.sh starrocks
sleep 90
docker compose --env-file .env ps starrocks
```

期望：starrocks `healthy`。如果未 healthy，看日志：`docker compose logs starrocks | tail -50`。

- [ ] **Step 3**：用 mysql 客户端连一下：

```bash
docker compose --env-file .env exec starrocks \
  mysql -h127.0.0.1 -P9030 -uroot -e "SHOW DATABASES; SHOW BACKENDS;"
```

期望：列出系统库（`information_schema` 等），SHOW BACKENDS 返回 1 行（自身）状态 `Alive=true`。

- [ ] **Step 4**：commit

```bash
./scripts/down.sh
git add docker-compose.yml
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0a): add starrocks allin1 service"
```

---

## Task 9：样例数据准备（无线 KPI / 工参 / 信令）

**Files:**
- Create: `D:/agent-code/data-agent/samples/data/mr_5g_15min.csv`
- Create: `D:/agent-code/data-agent/samples/data/engineering_param.csv`
- Create: `D:/agent-code/data-agent/samples/kafka/signaling_events.jsonl`
- Create: `D:/agent-code/data-agent/samples/ddl/01_create_database.sql`
- Create: `D:/agent-code/data-agent/samples/ddl/02_create_mr_table.sql`
- Create: `D:/agent-code/data-agent/samples/ddl/03_create_eng_param_table.sql`
- Create: `D:/agent-code/data-agent/scripts/load-sample-data.sh`

- [ ] **Step 1**：创建 `samples/data/mr_5g_15min.csv`，写入 100 行样例（cell × 15min × KPI）：

```csv
cell_id,ts_15min,rsrp_avg,rsrq_avg,sinr_avg,sample_count,weak_cov_ratio
C001,2026-05-01 00:00:00,-95.2,-12.1,8.5,1234,0.05
C001,2026-05-01 00:15:00,-96.0,-12.5,8.0,1180,0.06
C002,2026-05-01 00:00:00,-115.3,-18.2,2.1,987,0.45
C002,2026-05-01 00:15:00,-114.8,-17.9,2.3,1003,0.42
C003,2026-05-01 00:00:00,-88.1,-9.5,15.2,2103,0.01
```

> 实际写时建议生成 100 行覆盖 5 个 cell × 20 个时段，weak_cov_ratio 包含 < 0.1 与 > 0.3 两类样本。可用如下 Python 脚本一次性生成（在 step 末尾跑一次，把输出 redirect 到 csv）：

```python
import csv, random, datetime
random.seed(42)
cells = [f"C{i:03d}" for i in range(1,6)]
weak_ratios = {"C001":0.05,"C002":0.42,"C003":0.01,"C004":0.35,"C005":0.08}
rows = []
ts_base = datetime.datetime(2026,5,1)
for c in cells:
    for i in range(20):
        ts = ts_base + datetime.timedelta(minutes=15*i)
        rsrp = -95 + random.uniform(-25,5) if weak_ratios[c]>0.3 else -90 + random.uniform(-10,5)
        rsrq = -12 + random.uniform(-6,3)
        sinr = 10 + random.uniform(-8,8)
        n = random.randint(800,2500)
        rows.append([c, ts.strftime("%Y-%m-%d %H:%M:%S"), round(rsrp,2), round(rsrq,2), round(sinr,2), n, weak_ratios[c]+random.uniform(-0.05,0.05)])
with open("samples/data/mr_5g_15min.csv","w",newline="") as f:
    w = csv.writer(f)
    w.writerow(["cell_id","ts_15min","rsrp_avg","rsrq_avg","sinr_avg","sample_count","weak_cov_ratio"])
    w.writerows(rows)
```

- [ ] **Step 2**：创建 `samples/data/engineering_param.csv`：

```csv
cell_id,site_id,district,longitude,latitude,rat,azimuth,downtilt
C001,S001,海淀,116.30,39.98,5G_SA,30,5
C002,S001,海淀,116.30,39.98,5G_SA,150,5
C003,S002,朝阳,116.45,39.92,5G_SA,0,4
C004,S003,西城,116.36,39.91,5G_NSA,90,6
C005,S004,东城,116.41,39.92,5G_SA,180,5
```

- [ ] **Step 3**：创建 `samples/kafka/signaling_events.jsonl`（每行一个 JSON）：

```json
{"ts":"2026-05-01T00:00:01","event_type":"handover","src_cell":"C001","dst_cell":"C003","result":"success","cause":"normal"}
{"ts":"2026-05-01T00:00:05","event_type":"handover","src_cell":"C001","dst_cell":"C002","result":"failure","cause":"too_late"}
{"ts":"2026-05-01T00:00:08","event_type":"handover","src_cell":"C003","dst_cell":"C004","result":"success","cause":"normal"}
{"ts":"2026-05-01T00:00:12","event_type":"handover","src_cell":"C002","dst_cell":"C001","result":"failure","cause":"too_early"}
{"ts":"2026-05-01T00:00:15","event_type":"handover","src_cell":"C005","dst_cell":"C003","result":"success","cause":"normal"}
```

- [ ] **Step 4**：创建 `samples/ddl/01_create_database.sql`：

```sql
CREATE DATABASE IF NOT EXISTS dw;
CREATE DATABASE IF NOT EXISTS dim;
```

- [ ] **Step 5**：创建 `samples/ddl/02_create_mr_table.sql`：

```sql
USE dw;
CREATE TABLE IF NOT EXISTS mr_5g_15min (
  cell_id          STRING,
  ts_15min         TIMESTAMP,
  rsrp_avg         DOUBLE,
  rsrq_avg         DOUBLE,
  sinr_avg         DOUBLE,
  sample_count     BIGINT,
  weak_cov_ratio   DOUBLE
)
ROW FORMAT DELIMITED FIELDS TERMINATED BY ','
STORED AS TEXTFILE
TBLPROPERTIES ("skip.header.line.count"="1");
```

- [ ] **Step 6**：创建 `samples/ddl/03_create_eng_param_table.sql`：

```sql
USE dim;
CREATE TABLE IF NOT EXISTS engineering_param (
  cell_id     STRING,
  site_id     STRING,
  district    STRING,
  longitude   DOUBLE,
  latitude    DOUBLE,
  rat         STRING,
  azimuth     INT,
  downtilt    INT
)
ROW FORMAT DELIMITED FIELDS TERMINATED BY ','
STORED AS TEXTFILE
TBLPROPERTIES ("skip.header.line.count"="1");
```

- [ ] **Step 7**：创建 `scripts/load-sample-data.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

echo "[1/4] Uploading sample CSVs to HDFS..."
docker compose --env-file .env exec -T hdfs-namenode bash -c "
  /opt/hadoop/bin/hdfs dfs -mkdir -p /user/hive/warehouse/dw.db/mr_5g_15min &&
  /opt/hadoop/bin/hdfs dfs -mkdir -p /user/hive/warehouse/dim.db/engineering_param
"
docker cp samples/data/mr_5g_15min.csv da-hdfs-namenode:/tmp/mr_5g_15min.csv
docker cp samples/data/engineering_param.csv da-hdfs-namenode:/tmp/engineering_param.csv
docker compose --env-file .env exec -T hdfs-namenode bash -c "
  /opt/hadoop/bin/hdfs dfs -put -f /tmp/mr_5g_15min.csv /user/hive/warehouse/dw.db/mr_5g_15min/ &&
  /opt/hadoop/bin/hdfs dfs -put -f /tmp/engineering_param.csv /user/hive/warehouse/dim.db/engineering_param/
"

echo "[2/4] Creating Hive databases & tables via Spark..."
for sql in samples/ddl/*.sql; do
  echo "  $sql"
  docker compose --env-file .env exec -T spark-master \
    spark-sql --master spark://spark-master:7077 \
    -f - < "$sql"
done

echo "[3/4] Creating Kafka topic & loading signaling events..."
docker compose --env-file .env exec -T kafka \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --create --if-not-exists --topic signaling_events \
    --partitions 1 --replication-factor 1
docker cp samples/kafka/signaling_events.jsonl da-kafka:/tmp/signaling_events.jsonl
docker compose --env-file .env exec -T kafka bash -c "
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
    --topic signaling_events < /tmp/signaling_events.jsonl
"

echo "[4/4] Done. Verify with:"
echo "  docker compose --env-file .env exec spark-master spark-sql -e 'SELECT COUNT(*) FROM dw.mr_5g_15min;'"
```

- [ ] **Step 8**：跑一次完整 load：

```bash
chmod +x scripts/load-sample-data.sh
./scripts/up.sh
sleep 90
./scripts/load-sample-data.sh
```

- [ ] **Step 9**：验证 Hive 数据可查：

```bash
docker compose --env-file .env exec spark-master \
  spark-sql --master spark://spark-master:7077 \
    -e "SELECT cell_id, COUNT(*) FROM dw.mr_5g_15min GROUP BY cell_id;"
```

期望：5 个 cell 各 20 行。

- [ ] **Step 10**：验证 Kafka 数据可消费：

```bash
docker compose --env-file .env exec kafka \
  /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic signaling_events --from-beginning --max-messages 5 --timeout-ms 5000
```

期望：5 条 JSON 消息。

- [ ] **Step 11**：commit

```bash
./scripts/down.sh
git add samples/ scripts/load-sample-data.sh
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0a): sample wireless data (mr/engineering/signaling)"
```

---

## Task 10：DeepSeek API 联通脚本

**Files:**
- Create: `D:/agent-code/data-agent/scripts/deepseek-ping.py`

- [ ] **Step 1**：创建 `scripts/deepseek-ping.py`：

```python
#!/usr/bin/env python3
"""
验证 DeepSeek API 联通。读 .env,发一个最小 chat 请求,打印响应或错误。
Exit 0 = 成功, 非 0 = 失败。
"""
import os
import sys
import json
import urllib.request
import urllib.error
from pathlib import Path

def load_env(path):
    if not path.exists():
        return {}
    env = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        env[k.strip()] = v.strip()
    return env

def main():
    repo_root = Path(__file__).resolve().parent.parent
    env = load_env(repo_root / ".env")
    base = env.get("DEEPSEEK_API_BASE") or os.environ.get("DEEPSEEK_API_BASE")
    key = env.get("DEEPSEEK_API_KEY") or os.environ.get("DEEPSEEK_API_KEY")
    model = env.get("DEEPSEEK_MODEL") or os.environ.get("DEEPSEEK_MODEL", "deepseek-chat")

    missing = [n for n, v in [("DEEPSEEK_API_BASE", base), ("DEEPSEEK_API_KEY", key)] if not v]
    if missing:
        print(f"ERROR: missing env vars: {missing}", file=sys.stderr)
        return 2

    url = base.rstrip("/") + "/chat/completions"
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": "You are a connectivity test."},
            {"role": "user", "content": "Reply with exactly: PONG"},
        ],
        "max_tokens": 16,
        "temperature": 0.0,
    }
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {key}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        print(f"HTTPError {e.code}: {e.read().decode('utf-8', errors='ignore')}", file=sys.stderr)
        return 3
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 4

    try:
        content = body["choices"][0]["message"]["content"]
    except (KeyError, IndexError):
        print(f"Unexpected response shape: {body}", file=sys.stderr)
        return 5

    print(f"OK model={model} response={content!r}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2**：运行（前提：`.env` 已填入有效 `DEEPSEEK_API_KEY`）：

```bash
chmod +x scripts/deepseek-ping.py
python scripts/deepseek-ping.py
```

期望：标准输出 `OK model=deepseek-chat response='PONG'`，exit 0。
若 401：检查 `.env` 里的 `DEEPSEEK_API_KEY`。

- [ ] **Step 3**：commit

```bash
git add scripts/deepseek-ping.py
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0a): deepseek api connectivity ping script"
```

---

## Task 11：Spark 端到端样例 job

**Files:**
- Create: `D:/agent-code/data-agent/samples/jobs/verify-spark-job.py`

- [ ] **Step 1**：创建 `samples/jobs/verify-spark-job.py`：

```python
"""
M0a 端到端验证:
1. 从 Hive 读 dw.mr_5g_15min
2. 关联 dim.engineering_param,按 district 聚合
3. 写回 Hive 一张新表 dw.weak_cov_by_district_m0a_check
4. 打印结果
"""
from pyspark.sql import SparkSession

spark = (
    SparkSession.builder
    .appName("m0a-verify")
    .enableHiveSupport()
    .getOrCreate()
)

mr = spark.table("dw.mr_5g_15min")
ep = spark.table("dim.engineering_param")

result = (
    mr.alias("m")
    .join(ep.alias("e"), "cell_id")
    .groupBy("e.district")
    .agg(
        {"m.weak_cov_ratio": "avg", "m.sample_count": "sum"}
    )
    .withColumnRenamed("avg(weak_cov_ratio)", "avg_weak_cov_ratio")
    .withColumnRenamed("sum(sample_count)", "total_samples")
)

result.show(truncate=False)

spark.sql("DROP TABLE IF EXISTS dw.weak_cov_by_district_m0a_check")
result.write.mode("overwrite").saveAsTable("dw.weak_cov_by_district_m0a_check")

print("SUCCESS: m0a verify job completed.")
spark.stop()
```

- [ ] **Step 2**：跑一次：

```bash
./scripts/up.sh
sleep 90
./scripts/load-sample-data.sh

docker cp samples/jobs/verify-spark-job.py da-spark-master:/tmp/verify.py
docker compose --env-file .env exec spark-master \
  spark-submit --master spark://spark-master:7077 /tmp/verify.py
```

期望：日志含 `SUCCESS: m0a verify job completed.`，且看到按 district 的聚合表（海淀/朝阳/西城/东城各 1 行）。

- [ ] **Step 3**：验证写回的表：

```bash
docker compose --env-file .env exec spark-master \
  spark-sql --master spark://spark-master:7077 \
    -e "SELECT * FROM dw.weak_cov_by_district_m0a_check;"
```

期望：4-5 行（按 district 分组）。

- [ ] **Step 4**：commit

```bash
git add samples/jobs/verify-spark-job.py
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0a): end-to-end spark verify job"
```

---

## Task 12：整栈健康检查脚本（verify.sh）

**Files:**
- Create: `D:/agent-code/data-agent/tests/integration/test_stack_health.sh`
- Create: `D:/agent-code/data-agent/scripts/verify.sh`

- [ ] **Step 1**：创建 `tests/integration/test_stack_health.sh`：

```bash
#!/usr/bin/env bash
# 集成健康检查:逐个 service 跑一个最小验证。
# 退出码非 0 = 失败,标准输出/错误打印诊断信息。
set -uo pipefail
cd "$(dirname "$0")/../.."

FAIL=0
ok()   { echo "  [OK]   $1"; }
fail() { echo "  [FAIL] $1" >&2; FAIL=1; }

echo "== 1. postgres =="
docker compose --env-file .env exec -T postgres \
  psql -U hive -d metastore -c "SELECT 1;" >/dev/null 2>&1 \
  && ok "postgres reachable" || fail "postgres unreachable"

echo "== 2. hdfs =="
docker compose --env-file .env exec -T hdfs-namenode \
  /opt/hadoop/bin/hdfs dfs -ls / >/dev/null 2>&1 \
  && ok "hdfs reachable" || fail "hdfs unreachable"

echo "== 3. hive metastore =="
docker compose --env-file .env exec -T hive-metastore \
  bash -c "ss -tlnp | grep -q ':9083'" \
  && ok "hms listening on 9083" || fail "hms not listening"

echo "== 4. spark =="
docker compose --env-file .env exec -T spark-master \
  curl -sf http://localhost:8080 >/dev/null \
  && ok "spark master ui up" || fail "spark master ui down"

echo "== 5. flink =="
docker compose --env-file .env exec -T flink-jobmanager \
  curl -sf http://localhost:8081/overview >/dev/null \
  && ok "flink jobmanager up" || fail "flink jobmanager down"

echo "== 6. kafka =="
docker compose --env-file .env exec -T kafka \
  /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1 \
  && ok "kafka broker reachable" || fail "kafka broker unreachable"

echo "== 7. starrocks =="
docker compose --env-file .env exec -T starrocks \
  mysql -h127.0.0.1 -P9030 -uroot -e "SELECT 1;" >/dev/null 2>&1 \
  && ok "starrocks reachable" || fail "starrocks unreachable"

echo "== 8. deepseek api =="
python scripts/deepseek-ping.py >/dev/null 2>&1 \
  && ok "deepseek api reachable" || fail "deepseek api unreachable"

echo
if [[ $FAIL -eq 0 ]]; then
  echo "ALL CHECKS PASSED"
  exit 0
else
  echo "ONE OR MORE CHECKS FAILED" >&2
  exit 1
fi
```

- [ ] **Step 2**：创建 `scripts/verify.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
exec bash tests/integration/test_stack_health.sh
```

- [ ] **Step 3**：拉栈 + load + 验证：

```bash
chmod +x scripts/verify.sh tests/integration/test_stack_health.sh
./scripts/up.sh
sleep 120  # 全栈首启动稍慢(starrocks)
./scripts/load-sample-data.sh
./scripts/verify.sh
```

期望：8 项全部 `[OK]`，最后输出 `ALL CHECKS PASSED`，exit 0。

- [ ] **Step 4**：故障注入测试（验证 verify.sh 真能发现问题）：

```bash
docker compose --env-file .env stop kafka
./scripts/verify.sh || echo "(expected non-zero exit)"
```

期望：第 6 项报 `[FAIL] kafka broker unreachable`，最终 `ONE OR MORE CHECKS FAILED`，exit 非 0。

恢复：

```bash
docker compose --env-file .env start kafka
sleep 20
./scripts/verify.sh
```

期望：恢复 `ALL CHECKS PASSED`。

- [ ] **Step 5**：commit + 收尾

```bash
./scripts/down.sh
git add scripts/verify.sh tests/integration/test_stack_health.sh
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0a): stack health check script (verify.sh)"
git push
```

---

## M0a 完成标准（DoD）

- [ ] `./scripts/up.sh` 一键拉起，120 秒内所有 service 进入 `healthy` 状态
- [ ] `./scripts/load-sample-data.sh` 成功导入样例数据（Hive 2 张表 + Kafka 1 个 topic）
- [ ] `./scripts/verify.sh` 8 项全部通过
- [ ] `samples/jobs/verify-spark-job.py` 跑通，写回的 `dw.weak_cov_by_district_m0a_check` 表内容正确
- [ ] `python scripts/deepseek-ping.py` 返回 `OK ... response='PONG'`
- [ ] `./scripts/down.sh` 干净停止与清理（`docker compose ps` 无残留容器）
- [ ] 全部代码已 commit + push 到 origin/main

---

## 可能踩到的坑（M0a 已知风险）

| 风险 | 现象 | 缓解 |
|---|---|---|
| **Windows 行尾** | 容器内 `bash` 报 `\r: command not found` | `.gitattributes` 已隐含；如遇到，把 `*.sh` 文件改 LF（VSCode 右下角切换） |
| **Hive 4.0 schematool 卡住** | HMS 容器循环重启 | 改用 `apache/hive:3.1.3` 或在 entrypoint 里加 `set -x` 看具体卡在哪 |
| **HDFS NameNode 首次格式化失败** | 容器循环重启，日志 "Storage directory does not exist or is not accessible" | 删掉 volume：`docker volume rm data-agent_hdfs-name`，再 `up.sh` |
| **StarRocks 启动慢** | 90 秒没 healthy | 延长 sleep 到 180；首次启动可能要 2 分钟 |
| **DeepSeek API 401** | ping 脚本报 HTTPError 401 | 检查 `.env` 里的 KEY 是否复制对、是否含有效引号 |
| **端口冲突** | 8080/9092/3306 等被占 | 改 `.env` 里的 `*_PORT`，重新 up |

---

## 后续

M0a 完成后回到 brainstorming，根据基础设施暴露的问题（如某个服务实际行为与 spec 假设不一致）微调 M0b 计划，然后 writing-plans 写 M0b。
