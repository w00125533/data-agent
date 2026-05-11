# 无线网络感知评估 Data Agent 设计

- **状态**：设计草案（已与需求方对齐）
- **日期**：2026-05-09
- **场景**：无线网络感知评估
- **性质**：快速验证项目（v1 不含安全合规模块，留待 v2）

---

## 1. 目标与范围

### 1.1 目标

构建一个数据 agent：以多轮自然语言对话承接需求方关于无线网络感知评估的数据请求，自动生成可执行的 Flink SQL / Spark SQL / Java（Flink Stream API）代码，覆盖两类任务方向：

1. **正向 ETL**：用户描述目标数据集 → agent 在已有数据（Hive/Kafka/StarRocks）上加工
2. **反向合成**：用户给出已有评估 pipeline → agent 反推输入约束并生成测试/压测数据生产代码

代码生成后在 dry-run 沙箱中以小样本试跑、给用户预览，确认无误后由 agent 生成代码 PR + 上线工单，进入既有 review/上线流程。

### 1.2 In-Scope（v1）

- 多轮对话与主动澄清
- 数据感知：Hive Metastore + 内部数据字典 + 按需采样
- 三种引擎产物：Flink SQL、Spark SQL、Java（Flink Stream API）
- 引擎选择推荐 + 用户确认
- dry-run 小样本预览
- 反向合成（含可选的"喂回原 pipeline 双重验证"）
- 代码 PR + 工单生成（仅作为 review/审计流程，不接外部调度系统 API）
- 单团队使用、单一权限域
- DeepSeek（外部 API，OpenAI 兼容协议）作为 LLM 后端
- 内置无线评估方法论字典（弱覆盖、切换性能、感知差小区等口径预置）
- 预置 1% 采样基线服务（用于 dry-run）
- **本地 Docker 一体化验证栈**：HMS / HDFS / Kafka / StarRocks / Spark / Flink 全部以容器编排方式本地部署，**dry-run 与正式运行使用同一套环境**
- **DeepSeek 通过外部 API 接入**（不在 docker 栈内自建），由配置项注入 API endpoint / key / 模型名

### 1.3 Out-of-Scope（v1 不做）

- **PII 脱敏 / 数据安全等级 / 合规审计**：留待 v2（场景为快速验证）
- **多租户、跨团队隔离、quota**
- **生产任务监控与自愈**：v1 不进入真正生产
- **对接外部生产调度系统**（DolphinScheduler / Airflow / 内部平台等）：v1 上线目标 = 本地 Docker；PR + 工单仅作为代码 review/审计的工程流程
- **审计日志、版本管理**作为独立子系统（trace 内含基本审计能力即可）
- **LLM 直接造合成数据**（以代码生成 + Spark/Flink 执行的方式造数据，不让 LLM 直接吐出大量数据）

---

## 2. 整体架构

```
                           ┌──────────────────────┐
                           │     User (CLI/Web)   │
                           └──────────┬───────────┘
                                      │ NL 对话
                                      ▼
              ┌──────────────────────────────────────────────┐
              │         Agent Core（对话编排）               │
              │  · DeepSeek（self-hosted）                   │
              │  · ReAct / Tool-use loop                     │
              │  · Conversation State                        │
              │  · Spec Accumulator（增量积累结构化规格）    │
              └─┬───────┬───────┬───────┬───────┬───────┬───┘
                ▼       ▼       ▼       ▼       ▼       ▼
         Metadata  Profiler  Codegen  Validator Sandbox Scheduler
           Tool     Tool      Tool      Tool     Tool     Tool
            │         │         │         │        │        │
            ▼         ▼         ▼         ▼        ▼        ▼
       HMS+字典    采样/统计  intent→  syntax/   本地     PR生成 +
       Kafka       Hive/      spec→    schema/   Docker   工单创建
       StarRocks   Kafka/SR   plan→    explain   验证栈   (review流程)
       + 内置方法  (优先打     code     check    (优先读
        论字典     基线)      (LLM受    + 守护    基线;
                              模板约束)          dry-run 与
                                                 正式运行
                                                 同一栈)
                                                              
                ┌──────────────────────────────────────┐
                │  支撑组件                            │
                │  · Session Memory（对话/spec/        │
                │    采样/演进）                       │
                │  · User Preferences Store            │
                │    （引擎偏好、语义别名、默认配置）  │
                │  · Domain Knowledge Base             │
                │    （内置无线评估方法论字典）        │
                │  · Sample Baseline Service           │
                │    （定期采样 1% 子集）              │
                └──────────────────────────────────────┘
```

**核心设计原则：**

1. **Agent Core 是编排者，不直接做"专业活"**：选择调用哪个工具、消化工具结果、决定何时反问、何时收敛
2. **状态全部在 Agent Core**：Tools 无状态，每次调用自带必要上下文
3. **Spec Accumulator 是唯一事实源**：所有 codegen 基于 spec 而非对话原文；spec 没积累到位时坚持反问
4. **每个 Tool = 确定性 service + 受控 LLM 调用**：CodegenTool 内部仍是流水线（intent → spec → plan → code），LLM 在每个小步内输入/输出 schema 固定

### 2.x 运行环境：本地 Docker 验证栈

v1 是快速验证项目，所有底层组件以一套 docker-compose 在本地拉起，**dry-run 与正式运行共用同一套环境**（区别只在数据规模与是否走完整 schedule，而不在执行集群）。

**期望容器/服务：**

| 服务 | 用途 |
|---|---|
| postgres | HMS 元数据后端 |
| hive-metastore | 提供 Thrift 元数据接口给 Spark/Flink |
| hadoop-namenode + hadoop-datanode | HDFS 表数据存储 |
| kafka + zookeeper（或 kraft 单节点） | 流式源 |
| starrocks-fe + starrocks-be | OLAP 查询 |
| spark-master + spark-worker | Spark SQL 批 dry-run 与运行 |
| flink-jobmanager + flink-taskmanager | Flink SQL / Java Stream API 流 dry-run 与运行 |
| data-agent-server | Agent Core + Tools，对外提供 CLI/HTTP 接口 |

**外部依赖（不在 docker 栈内）：**

- **DeepSeek API**：通过环境变量配置接入，包含 `DEEPSEEK_API_BASE`、`DEEPSEEK_API_KEY`、`DEEPSEEK_MODEL`（如 `deepseek-chat`、`deepseek-reasoner` 等），data-agent-server 启动时读取。所有 LLM 调用走 OpenAI 兼容协议。

**设计含义：**

- SandboxTool 提交目标 = 本地 docker 中的 spark-master / flink-jobmanager
- SchedulerTool 的"上线"语义（v1 简化版）= 生成代码 + PR + 工单 + 一份可手工执行的 `spark-submit` / `flink run` 提交脚本，作为一次性 job 触发；**v1 不做周期调度**，等切到真实环境时再叠加周期能力
- Sample Baseline Service 把基线数据落到 docker 内的 HDFS（独立路径前缀如 `/baseline/...`），与生产源数据位于同一 HDFS 但不同路径，dry-run 优先指向基线路径
- 由于环境一致，dry-run 通过基本等价于"上生产能跑"，反向路径的"双重 dry-run"也在同一栈内闭环
- LLM 调用对外网有依赖：开发机需要能访问 DeepSeek API endpoint；可选添加缓存层降低调用成本

---

## 3. 核心数据结构：Spec Accumulator

```yaml
spec:
  task_direction: forward_etl | reverse_synthetic
  state: gathering | clarifying | ready_to_codegen
       | codegen_done | dry_run_ok | deployed | failed

  # 域内三件事:粒度/RAT/KPI 族,无线评估必须明确
  network_context:
    ne_grain: cell | site | sector | tracking_area | district | city
    time_grain: 15min | hour | day
    rat: 4G | 5G_NSA | 5G_SA | mixed
    kpi_family: coverage | mobility | accessibility | retainability | qoe

  target:
    name: string
    schema: [{name, type, semantic, nullable, examples?}]
    grain: "(cell_id, day)"
    timeliness: batch_daily | batch_hourly | streaming
    business_definition: "弱覆盖小区 = 7 日内 RSRP < -110 dBm 占比 > 30%"
    target_storage: {type, location}?

  sources:
    - role: "mr_main" | "kpi_pm" | "engineering_param" | "signaling_topic" | ...
      binding:                    # null 表示尚未确定
        catalog: hive | kafka | starrocks | unknown
        table_or_topic: string
        schema: [...]
        filters: string?
        profile: {cardinality, null_rate, dist_sketch}?
      confidence: 0.0~1.0

  transformations:
    - op: join | filter | agg | window | udf | custom_java
      description: "按 cell_id × 15min 关联 MR 与 KPI"
      inputs: [source roles]
      output_columns: [...]
      params: {window_size, agg_fn, ...}?
      needs_clarification: string?

  engine_decision:
    recommended: flink_sql | spark_sql | java_flink_streamapi
    reasoning: "源含 Kafka 信令流 + 5min 窗口聚合,SQL 可表达 → Flink SQL"
    user_overridden: bool
    deployment: {sched_system, queue, schedule}

  validate_through_target_pipeline: bool   # 反向路径专用,默认 true

  owners:
    upstream: [...]      # 上游表/topic 的 owner,用于 PR notify
    downstream: [...]    # 目标数据集的下游消费方 owner,用于变更知会

  test_cases:            # 用户/agent 给出的"输入→期望输出"对子
    - name: "正常切换样例"
      inputs: {...}
      expected_output: {...}
    - name: "早切场景"
      inputs: {...}
      expected_output: {...}

  open_questions:
    - field_path: "transformations[2].params.window_size"
      question: "你说的'近期'是 5 分钟还是 1 小时窗口?"
      candidates: [5min, 15min, 1h]?

  evidence:
    - type: schema_lookup | sampling | profile_stats | doc_lookup
      source: "hive.dw.mr_4g_15min"
      findings: {...}
      ts: timestamp
```

**为什么这样设计：**

- `evidence` 防止重复采样
- `open_questions` 是显式队列，agent 每轮挑最高价值的一个反问
- `business_definition` 强制把口径写成文字，避免"看似聊明白、实则没收敛"
- `confidence` + `needs_clarification` 是 agent 自我评估"是否可进入 codegen"的判据
- `network_context` 强制把"粒度/RAT/KPI 族"显式化——这是无线评估场景含糊不得的三件事
- `owners` 决定 PR reviewer 与变更通知；`test_cases` 既是 codegen 的合规检查，也是上线前回归基线
- `validate_through_target_pipeline` 仅反向路径生效，控制是否做"造数据 → 喂回原 pipeline"双重验证

---

## 4. 工具契约

| Tool | 输入 | 输出 | 性质 | 失败模式 |
|---|---|---|---|---|
| **MetadataTool** | catalog + table/topic 名 (或模糊查询关键字)；可指定优先查 Domain KB | schema + 字段注释 + 字典语义 + 责任人 + 方法论口径定义 | 只读 / 有缓存 | 表不存在 → 返回候选模糊匹配；字典未命中 → 退化到 Domain KB 兜底 |
| **ProfilerTool** | source binding + 采样要求（列、行数上限） | 画像（cardinality、null 率、top-K、值域、分布草图） | 只读 / 有 quota | 数据量过大 → 改用统计型 SQL；优先读 Sample Baseline |
| **ClarifyTool** | spec.openQuestions（优先级排序） | 单一最高优先级反问 + 候选选项 | 只读 / 硬编码模板兜底 | 全部已回答 → 返回 converged:true |
| **CodegenTool** | spec（state ≥ ready_to_codegen） | 代码 + 依赖清单 + 解释 + diff（若是改写） | 内部 LLM 受模板约束；正向/反向走不同子流水线 | 模板覆盖不到 → 退化为 Java 兜底 |
| **ValidatorTool** | 代码 + spec | {pass/fail, diagnostics, plan_warnings} | 只读 | parse 失败 → 错误反给 CodegenTool 重试 |
| **SandboxTool** | 代码 + 小样本配置（优先指向 Sample Baseline） | result_preview + metrics + logs | 真执行 / 提交到**本地 Docker** spark-master / flink-jobmanager / 有时长限制 | 容器报错 → 错误回 agent，反思后重 codegen（同根因 N=2 后降级） |
| **SchedulerTool** | spec.deployment + 代码 + 依赖 + owners + test_cases | PR url + 工单号 + 一次性提交脚本（`spark-submit` / `flink run`） | 写操作 / 用户确认后才调用；v1 上线目标 = **本地 Docker**，**只生成一次性 job 提交脚本，不做周期调度** | 失败不重试，提示走人工流程 |
| **UserPreferencesStore** | user_id, key | value（引擎偏好、语义别名、默认 sched 配置） | 持久化读写 | 缺省值兜底 |

---

## 5. 端到端数据流

### 5.1 路径 A：正向 ETL

```
[User] "近 30 天每个区县 5G 弱覆盖小区清单,附用户感知评分"
   │
   ▼
Agent Core: init spec(forward_etl)
   │
   ├─ MetadataTool.search → 候选: dw.mr_5g_15min, dw.kpi_pm_cell_hour,
   │                              dim.engineering_param, dw.qoe_user_cell
   │  写入 spec.sources(候选, confidence=0.7)
   │
   ├─ ProfilerTool.profile (优先读 Sample Baseline)
   │  → 字段命中率/null 率/分布
   │  写入 spec.evidence
   │
   ├─ ClarifyTool.pickNextQuestion → 按优先级(target > sources > grain > timeliness)
   │   选最高价值反问:
   │   "弱覆盖按 RSRP < -110 占比 30%,还是按你们口径库的 v2 定义?"
   │
   ├─ ...(收敛 2-3 轮,口径/RAT/粒度对齐)...
   │   ConvergenceGuard 控制: 轮次上限 5 / 全部回答判定 / 强制继续关键词
   │   用户回复 → matchAnswer 候选匹配 → markAnswered → applyAnswerToSpec
   │   SpecState: GATHERING → CLARIFYING → READY_TO_CODEGEN
   │
   ├─ EngineSelector 推荐 Spark SQL
   │  显式反问用户确认("理由: 批源、简单 join+agg、无流式窗口")
   │
   ├─ CodegenTool: intent → spec → plan → 代码模板填充 → 输出
   │
   ├─ ValidatorTool: ✓ 语法 ✓ schema ⚠ shuffle 大
   │
   ├─ SandboxTool.dry_run (在 Sample Baseline 上跑) → 100 行预览 + 实际耗时
   │
   ├─ 给用户预览 + 警告 + 代码
   │
   └─ 用户确认 → SchedulerTool: 开 PR + 工单 (含 owners @、口径文档、test_cases)
```

### 5.2 路径 B：反向合成

```
[User] "这是切换失败分析 Flink SQL,造一份测试数据,
        要覆盖正常/早切/晚切" + 贴代码
   │
   ▼
Agent Core: init spec(reverse_synthetic)
   │
   ├─ CodegenTool.parse_target_pipeline:
   │   解析 AST → 抽出输入表 schema → 推断输入约束:
   │     join 用 cell_id           → 多表需有重合 cell
   │     where ts > now-7d         → ts 分布需覆盖近 7 天
   │     group by 5min window      → 同 cell 在窗口内需要 ≥1 行
   │     case when ... 早切/晚切判断 → 需各类型样本均有
   │
   ├─ 反问澄清:
   │     · 数据规模? (单测 1k 行 vs 压测 1 亿)
   │     · 异常场景比例? (默认 5% 早切 + 5% 晚切)
   │     · 是否要"看起来像生产"(用 ProfilerTool 学一份生产分布)?
   │
   ├─ (可选) ProfilerTool.profile(prod_table) 学分布
   │
   ├─ CodegenTool.generate_data_producer
   │   输出: Spark/Flink 数据生成代码
   │
   ├─ ValidatorTool: 语法 + schema 兼容
   │
   ├─ SandboxTool.dry_run:
   │   step1: 跑生成代码 → 造一小批
   │   step2 (validate_through_target_pipeline=true 时):
   │         把造出来的数据喂给【用户原始 pipeline】跑一遍
   │         验证: 不报错 + 输出非空 + 覆盖关键分支(join 命中、agg 非空、
   │              早切/晚切样本都跑出来)
   │
   └─ 用户确认 → SchedulerTool: PR + 工单
```

### 5.3 失败回流

```
SandboxTool 报错 → 错误信息回 Agent Core,写入 spec.evidence
   │
   ├─ 结构性错误(schema/类型) → 反思,重新 CodegenTool
   │
   ├─ 性能问题(shuffle/skew) → 反问用户:
   │     "数据 skew,加 salting 还是放宽 SLA?"
   │
   ├─ 业务结果不对 → 反问:
   │     "结果 X 行但你预期 Y 行,过滤还是 join 条件错?"
   │
   └─ 同一根因连续 N=2 次失败 → 降级:
        把错误透给用户、停在那里等指示,不再自动重试
```

---

## 6. 引擎选择逻辑

EngineSelector 是 Agent Core 的内部子模块（不是独立 tool），决策表：

| 信号 | 选择 |
|---|---|
| 源含 Kafka topic 且需要时间窗口 | Flink SQL |
| 源全是 Hive 批表，逻辑能用 SQL 表达 | Spark SQL |
| 源有 CDC（binlog） + Hive 维表 lookup | Flink SQL（lookup join） |
| SQL 不能表达（递归、复杂状态、需调外部服务） | Java + Flink Stream API |
| 流式高吞吐、需要极致性能/状态控制 | Java + Flink Stream API |
| 用户在 UserPreferencesStore 里有团队约定 | 团队约定优先 |

EngineSelector 给出推荐 + 理由后，**显式反问用户确认**——不默认决断。

---

## 7. 失败处理（v1 范围）

| 失败类别 | 处理 |
|---|---|
| **工具级故障**（HMS/Kafka/LLM 等） | tool 内部重试（带 backoff），3 次仍失败 → 上报 agent → 退化到"问用户提供这部分上下文" |
| **Spec 内部冲突** | session 内 spec 快照；冲突时 agent 显式提"前面说 X，现在说 Y" |
| **CodegenTool 输出违反 spec** | ValidatorTool 抓到 → 回 codegen 重生（计入 N=2） |
| **ValidatorTool 警告（非 fail）** | 不阻塞，写进预览的"⚠ 风险"段 |
| **SandboxTool 失败** | 见 5.3 |
| **SchedulerTool 失败** | 不重试（避免重复 PR），代码 + spec 给用户走人工流程 |
| **会话中断** | spec + evidence 落盘，按 session_id 续接 |

> **安全 / 合规**：v1 不做。PII 脱敏、数据安全等级、跨级 fail 等留待 v2。

---

## 8. 可观测性

每个会话产出一条 trace：

```
trace:
  session_id, user, started_at, ended_at, final_state
  ├─ 对话事件 (turn_id, user_msg, agent_msg)
  ├─ Spec 演进 (turn_id → spec snapshot diff)
  ├─ 工具调用 (tool, input_hash, latency, status, error?)
  ├─ LLM 调用 (model, prompt_tokens, completion_tokens, latency)
  └─ 最终产物 (code_hash, pr_url, ticket_id)
```

**指标看板**：
- Agent 质量：spec 收敛轮数（中位/P90）、codegen 一次通过率、dry-run 一次通过率、用户改写次数
- 工具健康：MetadataTool/ProfilerTool 的 P99 延迟与错误率、Sandbox 资源使用
- LLM 成本：每会话 token 数 / GPU 占用

**replay**：给定 session_id 可重放整条 trace（含每次 LLM 输入/输出），用于 codegen 退化定位回归。

---

## 9. 测试与评估

### 9.1 三层测试

```
                 ┌────────────────────────────────┐
   E2E Eval Set  │ 30~50 个无线评估典型任务样本   │  ←  回归基线
                 │ (NL → 期望 spec → 期望代码骨架) │
                 └────────────────────────────────┘
                         ▲
                 ┌────────────────────────────────┐
   Codegen Eval  │ Spec → 代码 的 golden samples  │
                 │ Flink SQL/Spark SQL/Java 各一组│
                 └────────────────────────────────┘
                         ▲
                 ┌────────────────────────────────┐
   Tool Tests    │ 每个 Tool 的单测/集成测试      │
                 │ MetadataTool mock HMS 等       │
                 └────────────────────────────────┘
```

### 9.2 域内 Eval Set 题型（建议预置 30–50 道）

- **覆盖类**：弱覆盖、过覆盖、深度覆盖差小区
- **移动性类**：切换成功率、切换失败原因分布（早切/晚切）
- **接入/保持**：RRC 建立、E-RAB 建立、掉话率
- **感知 KPI**：视频卡顿、网页时延、TCP 重传率、感知差小区
- **多源联邦**：MR + KPI 关联（同 cell × 同 15min）
- **流批混合**：Kafka 信令 + Hive 工参（Flink lookup join）
- **反向合成专题**：早晚切混合、含 skew 的 cell 分布、低概率异常

每道题打分：spec 字段正确率、代码可执行、dry-run 通过、轮数（轮数过多扣分）。

### 9.3 持续评估

- 每次 CodegenTool / Domain Knowledge Base 变更 → 跑 E2E Eval Set
- 跌幅 ≥ 5% 的指标阻塞合并
- Eval set 随线上 bad case 增长

### 9.4 上线前金丝雀

新部署 agent 先在 2-3 名熟悉无线评估的工程师中跑 1 周对话试用，再放给整团队。

---

## 10. 快速验证里程碑（v1 交付分阶段建议）

> 仅作为快速验证项目的演进路标，详细实施由后续 implementation plan 拆解。

| 阶段 | 范围 | 验证目标 |
|---|---|---|
| **M0a 基础设施栈** | docker-compose：HMS（postgres + hive-metastore）/ HDFS（namenode + datanode）/ Kafka / StarRocks / Spark / Flink；DeepSeek API 联通验证；准备 1-2 张样例 Hive 表 + 1 个 Kafka topic 的演示数据 | 本地栈一键拉起、互通、可手工提交一个 spark-submit 跑通；DeepSeek API 调用成功 |
| **M0b 骨架与 mock loop** | Agent Core loop + Spec Accumulator + Mock MetadataTool + CodegenTool（仅 Spark SQL）+ stdout 预览 | 端到端 loop 走通；hardcode 1-2 个无线评估典型任务 |
| **M1 真工具接通** | 真 HMS（接入 M0a 容器内）+ ProfilerTool + ValidatorTool + SandboxTool（提交到本地 docker spark-master） | 在 M0a 准备的真实样例数据集上完成"NL → 代码 → dry-run 预览"全流程 |
| **M2 域知识与基线** | Domain Knowledge Base（无线评估方法论字典）+ Sample Baseline Service（基线落到 HDFS 独立路径） | agent 能正确引用方法论口径；dry-run 速度可控 |
| **M3 引擎扩展** | 加 Flink SQL（提交到本地 docker flink-jobmanager）→ 加 Java（Flink Stream API） | 三种产物都能 codegen + 通过 dry-run |
| **M4 反向合成** | CodegenTool 反向子流水线 + 双重 dry-run（可选） | 反向路径在 2-3 个真实样例 pipeline 上跑通 |
| **M4.5 多轮反问收敛** | Spec.Question 模型（answer/resolved）+ ClarifyTool（优先级排序 + 硬编码反问模板）+ ConvergenceGuard（轮次上限 5 / 强制继续关键词 / 全回答判定）+ 用户回复解析（候选匹配 → Spec 字段自动填充）+ SpecState 状态机接入流程 | 模糊需求在 ≤5 轮内收敛到可代码生成状态；强制继续关键词可直接跳过反问 |
| **M5 闭环上线** | SchedulerTool（PR + 工单 + 一次性 spark-submit / flink run 脚本）+ UserPreferencesStore + Trace/Replay | 端到端从对话到 PR + 本地一次性提交脚本闭环 |
| **M6 质量基线** | E2E Eval Set 30-50 道 + 持续评估流水线 | 建立可回归的质量基线 |

---

## 11. 假设与开放项

### 11.1 假设

1. 内部数据字典提供查询 API（schema、字段语义、责任人、方法论口径）；若缺方法论口径，由 Domain Knowledge Base 兜底（v1 验证阶段，内部字典可用 mock + 预置数据替代）
2. **本地 Docker 验证栈**可一键拉起（docker-compose），覆盖 HMS / HDFS / Kafka / StarRocks / Spark / Flink，开发机资源能承载
3. **v1 不接外部生产调度系统**：dry-run 与正式运行均在本地 Docker 内完成，PR + 工单仅作为代码 review 的工程流程
4. 团队后续接入真实环境时，可通过替换 catalog/connector 配置无侵入式切换到真实 HMS/Kafka/StarRocks 集群
5. **DeepSeek 通过外部 API 接入**（OpenAI 兼容协议），开发机能访问到 API endpoint；API key/endpoint/模型名通过环境变量配置

### 11.2 开放项（v2 / 后续）

1. **安全合规**（PII 脱敏、数据安全等级、合规审计）—— v1 显式不做
2. **多团队 / 多租户隔离**
3. **agent 直接对接调度系统 API**（替代 PR + 工单）
4. **生产任务监控与自愈回路**
5. **代码版本管理子系统**（v1 通过 trace + PR 间接覆盖）
6. **LLM 路由层**（外部强模型 + 内部模型混合）
7. **Domain Knowledge Base 的持续运营机制**（谁来维护、怎么版本化）

---

## 12. 评审与下一步

请 review 本设计。批准后由 writing-plans 进入实施计划编写。
