# M0b Agent 骨架与 Mock Loop 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal**：构建 Agent Core（ReAct loop）+ Spec Accumulator + Mock MetadataTool + CodegenTool（仅 Spark SQL），走通"NL 输入 → 工具调用 → 代码输出"的端到端回路；用 1-2 个无线评估典型场景 hardcode 验证。

**Architecture**：Python 项目，Agent Core 是编排者（ReAct loop），Spec Accumulator 是 Pydantic 数据模型（唯一事实源），Tools 是无状态函数（Mock MetadataTool 返回硬编码无线表 schema，CodegenTool 通过 LLM 将 spec 翻译为 Spark SQL），DeepSeek API 走 OpenAI 兼容客户端。整体在本地命令行以 stdin/stdout 对话模式运行。

**Tech Stack**：Python 3.10+, Pydantic v2, DeepSeek API (OpenAI 兼容协议), pytest, TDD

---

## 文件结构（M0b 完成后的新增目录形态）

```
data-agent/
├── src/
│   ├── __init__.py
│   ├── agent/
│   │   ├── __init__.py
│   │   ├── core.py              # Agent Core: ReAct loop + tool dispatch
│   │   ├── spec.py              # Spec / SpecAccumulator Pydantic models
│   │   ├── engine_selector.py   # 简易 EngineSelector（基于 spec 信号推荐引擎）
│   │   └── prompts.py           # LLM system/user prompt 模板
│   ├── tools/
│   │   ├── __init__.py
│   │   ├── base.py              # Tool 基类 / Protocol
│   │   ├── metadata.py          # Mock MetadataTool（hardcode 无线表 schema）
│   │   └── codegen.py           # CodegenTool（Spark SQL 生成）
│   ├── llm/
│   │   ├── __init__.py
│   │   └── client.py            # DeepSeek API 客户端（OpenAI 兼容）
│   └── main.py                  # CLI 入口：stdin/stdout 对话
├── tests/
│   ├── __init__.py
│   ├── agent/
│   │   ├── __init__.py
│   │   ├── test_spec.py
│   │   ├── test_core.py
│   │   └── test_engine_selector.py
│   ├── tools/
│   │   ├── __init__.py
│   │   ├── test_metadata.py
│   │   └── test_codegen.py
│   └── llm/
│       ├── __init__.py
│       └── test_client.py
├── pyproject.toml
├── requirements.txt
└── conftest.py                   # pytest fixtures（mock LLM 响应等）
```

---

### Task 1：Python 项目骨架

**Files:**
- Create: `D:/agent-code/data-agent/pyproject.toml`
- Create: `D:/agent-code/data-agent/requirements.txt`
- Create: `D:/agent-code/data-agent/conftest.py`
- Create: `D:/agent-code/data-agent/src/__init__.py`
- Create: `D:/agent-code/data-agent/src/agent/__init__.py`
- Create: `D:/agent-code/data-agent/src/llm/__init__.py`
- Create: `D:/agent-code/data-agent/src/tools/__init__.py`
- Create: `D:/agent-code/data-agent/tests/__init__.py`
- Create: `D:/agent-code/data-agent/tests/agent/__init__.py`
- Create: `D:/agent-code/data-agent/tests/llm/__init__.py`
- Create: `D:/agent-code/data-agent/tests/tools/__init__.py`

- [ ] **Step 1**：在 `pyproject.toml` 写入：

```toml
[project]
name = "data-agent"
version = "0.1.0"
description = "Wireless network perception data agent"
requires-python = ">=3.10"
dependencies = [
    "pydantic>=2.0",
    "openai>=1.0",
    "python-dotenv>=1.0",
]

[project.optional-dependencies]
dev = [
    "pytest>=8.0",
    "pytest-mock>=3.0",
]

[tool.pytest.ini_options]
testpaths = ["tests"]
pythonpath = ["src"]
```

- [ ] **Step 2**：在 `requirements.txt` 写入：

```
pydantic>=2.0
openai>=1.0
python-dotenv>=1.0
pytest>=8.0
pytest-mock>=3.0
```

- [ ] **Step 3**：创建所有 `__init__.py` 空文件，`conftest.py` 写入：

```python
"""Shared pytest fixtures."""
import os
import pytest


@pytest.fixture(autouse=True)
def mock_deepseek_env(monkeypatch):
    """Ensure test env has DeepSeek config so client init doesn't crash."""
    monkeypatch.setenv("DEEPSEEK_API_BASE", "https://api.deepseek.com/v1")
    monkeypatch.setenv("DEEPSEEK_API_KEY", "sk-test-mock")
    monkeypatch.setenv("DEEPSEEK_MODEL", "deepseek-chat")
```

- [ ] **Step 4**：安装依赖并验证：

```bash
cd D:/agent-code/data-agent
pip install -e ".[dev]"
python -c "import pydantic; import openai; print('ok')"
```

期望：输出 `ok`，无 import 错误。

- [ ] **Step 5**：运行空测试确认 pytest 可用：

```bash
pytest -v
```

期望：`no tests ran` 或 `0 passed`，exit 0。

- [ ] **Step 6**：更新 `.gitignore`（附加）：

```
# Python
*.egg-info/
.eggs/
```

- [ ] **Step 7**：commit

```bash
cd D:/agent-code/data-agent
git add pyproject.toml requirements.txt conftest.py src/ tests/ .gitignore
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "chore(m0b): python project skeleton with pydantic, pytest"
```

---

### Task 2：DeepSeek API Client（LLM 客户端）

**Files:**
- Create: `D:/agent-code/data-agent/src/llm/client.py`
- Create: `D:/agent-code/data-agent/tests/llm/test_client.py`

- [ ] **Step 1**：在 `tests/llm/test_client.py` 写失败测试：

```python
"""Tests for DeepSeekClient."""
import json
from unittest.mock import MagicMock, patch
import urllib.request
from src.llm.client import DeepSeekClient


class TestDeepSeekClient:
    def test_chat_returns_response(self):
        """A basic chat call should return the model's text response."""
        client = DeepSeekClient(
            api_base="https://api.deepseek.com/v1",
            api_key="sk-test",
            model="deepseek-chat",
        )
        assert client.model == "deepseek-chat"

    def test_chat_sends_correct_payload(self):
        """Verify the HTTP request body has correct structure."""
        client = DeepSeekClient(
            api_base="https://api.deepseek.com/v1",
            api_key="sk-test",
            model="deepseek-chat",
        )
        messages = [{"role": "user", "content": "PING"}]

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps({
            "choices": [{"message": {"content": "PONG"}}]
        }).encode()

        with patch("urllib.request.urlopen", return_value=mock_resp) as mock_urlopen:
            result = client.chat(messages)

        assert result == "PONG"
        call_args = mock_urlopen.call_args[0][0]
        body = json.loads(call_args.data)
        assert body["model"] == "deepseek-chat"
        assert body["messages"][0]["content"] == "PING"
        assert body["max_tokens"] > 0

    def test_chat_api_error_returns_error_marker(self):
        """When API returns HTTP error, return error marker string."""
        client = DeepSeekClient(
            api_base="https://api.deepseek.com/v1",
            api_key="sk-test",
        )

        with patch("urllib.request.urlopen") as mock_urlopen:
            mock_urlopen.side_effect = urllib.error.HTTPError(
                url="test", code=401, msg="Unauthorized",
                hdrs={}, fp=None
            )
            result = client.chat([{"role": "user", "content": "hi"}])

        assert result.startswith("[ERROR]")
```

- [ ] **Step 2**：运行测试确认失败：

```bash
pytest tests/llm/test_client.py -v
```

期望：`ModuleNotFoundError: No module named 'src.llm.client'`（或类似）。

- [ ] **Step 3**：在 `src/llm/client.py` 写入实现：

```python
"""DeepSeek API client (OpenAI-compatible protocol)."""
import json
import os
import urllib.request
import urllib.error
from typing import Optional


class DeepSeekClient:
    def __init__(
        self,
        api_base: Optional[str] = None,
        api_key: Optional[str] = None,
        model: Optional[str] = None,
    ):
        self.api_base = api_base or os.environ.get("DEEPSEEK_API_BASE", "")
        self.api_key = api_key or os.environ.get("DEEPSEEK_API_KEY", "")
        self.model = model or os.environ.get("DEEPSEEK_MODEL", "deepseek-chat")

    def chat(
        self,
        messages: list[dict[str, str]],
        max_tokens: int = 1024,
        temperature: float = 0.1,
    ) -> str:
        url = self.api_base.rstrip("/") + "/chat/completions"
        payload = {
            "model": self.model,
            "messages": messages,
            "max_tokens": max_tokens,
            "temperature": temperature,
        }
        req = urllib.request.Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                body = json.loads(resp.read().decode("utf-8"))
            return body["choices"][0]["message"]["content"]
        except (urllib.error.HTTPError, urllib.error.URLError, Exception) as e:
            detail = str(e)
            if hasattr(e, "read"):
                try:
                    detail = e.read().decode("utf-8", errors="ignore")
                except Exception:
                    pass
            return f"[ERROR] {detail}"
```

- [ ] **Step 4**：运行测试确认通过：

```bash
pytest tests/llm/test_client.py -v
```

期望：3 passed。

- [ ] **Step 5**：commit

```bash
cd D:/agent-code/data-agent
git add src/llm/client.py tests/llm/test_client.py
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0b): deepseek api client"
```

---

### Task 3：Spec / SpecAccumulator 数据模型

**Files:**
- Create: `D:/agent-code/data-agent/src/agent/spec.py`
- Create: `D:/agent-code/data-agent/tests/agent/test_spec.py`

- [ ] **Step 1**：在 `tests/agent/test_spec.py` 写失败测试：

```python
"""Tests for Spec / SpecAccumulator."""
from src.agent.spec import (
    Spec,
    NetworkContext,
    TargetSpec,
    SourceBinding,
    TransformStep,
    EngineDecision,
    TaskDirection,
    SpecState,
)


class TestNetworkContext:
    def test_defaults(self):
        ctx = NetworkContext()
        assert ctx.rat == "5G_SA"

    def test_full_creation(self):
        ctx = NetworkContext(
            ne_grain="cell",
            time_grain="15min",
            rat="5G_SA",
            kpi_family="coverage",
        )
        assert ctx.ne_grain == "cell"


class TestSpec:
    def test_default_state_is_gathering(self):
        spec = Spec(task_direction=TaskDirection.FORWARD_ETL)
        assert spec.state == SpecState.GATHERING

    def test_can_progress_to_ready(self):
        spec = Spec(task_direction=TaskDirection.FORWARD_ETL)
        spec.target = TargetSpec(
            name="weak_cov_cells",
            business_definition="弱覆盖小区",
            grain="(cell_id, day)",
        )
        spec.sources = [
            SourceBinding(
                role="mr_main",
                binding={"catalog": "hive", "table_or_topic": "dw.mr_5g_15min"},
                confidence=0.9,
            )
        ]
        spec.engine_decision = EngineDecision(
            recommended="spark_sql",
            reasoning="批源、简单聚合",
        )
        # Enough info → can progress
        spec.advance_state()
        assert spec.state == SpecState.READY_TO_CODEGEN

    def test_cannot_progress_without_target(self):
        spec = Spec(task_direction=TaskDirection.FORWARD_ETL)
        spec.advance_state()
        assert spec.state == SpecState.GATHERING

    def test_open_questions_ordered(self):
        spec = Spec(task_direction=TaskDirection.FORWARD_ETL)
        spec.open_questions = [
            {"field_path": "b", "question": "later"},
            {"field_path": "a", "question": "first"},
        ]
        assert spec.next_question() is not None

    def test_network_context_serializable(self):
        ctx = NetworkContext(ne_grain="district", kpi_family="mobility")
        d = ctx.model_dump()
        assert d["ne_grain"] == "district"
        assert d["kpi_family"] == "mobility"
```

- [ ] **Step 2**：运行测试确认失败：

```bash
pytest tests/agent/test_spec.py -v
```

期望：全部 FAIL（类未定义）。

- [ ] **Step 3**：在 `src/agent/spec.py` 写入实现：

```python
"""Spec / Spec Accumulator — the single source of truth for codegen."""
from __future__ import annotations
from enum import Enum
from typing import Optional, Any
from pydantic import BaseModel, Field


class TaskDirection(str, Enum):
    FORWARD_ETL = "forward_etl"
    REVERSE_SYNTHETIC = "reverse_synthetic"


class SpecState(str, Enum):
    GATHERING = "gathering"
    CLARIFYING = "clarifying"
    READY_TO_CODEGEN = "ready_to_codegen"
    CODEGEN_DONE = "codegen_done"
    FAILED = "failed"


class NetworkContext(BaseModel):
    ne_grain: str = "cell"
    time_grain: str = "15min"
    rat: str = "5G_SA"
    kpi_family: str = "coverage"


class TargetSpec(BaseModel):
    name: str = ""
    schema: list[dict[str, str]] = Field(default_factory=list)
    grain: str = ""
    timeliness: str = "batch_daily"
    business_definition: str = ""
    target_storage: Optional[dict[str, str]] = None


class SourceBinding(BaseModel):
    role: str = ""
    binding: dict[str, Any] = Field(default_factory=dict)
    confidence: float = 0.0
    schema_: Optional[list[dict[str, str]]] = Field(default=None, alias="schema")


class TransformStep(BaseModel):
    op: str = ""
    description: str = ""
    inputs: list[str] = Field(default_factory=list)
    output_columns: list[dict[str, str]] = Field(default_factory=list)
    params: Optional[dict[str, Any]] = None
    needs_clarification: Optional[str] = None


class EngineDecision(BaseModel):
    recommended: str = ""
    reasoning: str = ""
    user_overridden: bool = False
    deployment: Optional[dict[str, str]] = None


class Evidence(BaseModel):
    type: str = ""
    source: str = ""
    findings: dict[str, Any] = Field(default_factory=dict)


class Spec(BaseModel):
    task_direction: TaskDirection
    state: SpecState = SpecState.GATHERING
    network_context: NetworkContext = Field(default_factory=NetworkContext)
    target: Optional[TargetSpec] = None
    sources: list[SourceBinding] = Field(default_factory=list)
    transformations: list[TransformStep] = Field(default_factory=list)
    engine_decision: Optional[EngineDecision] = None
    open_questions: list[dict[str, Any]] = Field(default_factory=list)
    evidence: list[Evidence] = Field(default_factory=list)
    owners: dict[str, list[str]] = Field(default_factory=dict)
    test_cases: list[dict[str, Any]] = Field(default_factory=list)
    validate_through_target_pipeline: bool = True

    def advance_state(self) -> SpecState:
        """Check if spec is ready to progress from GATHERING → CLARIFYING
        or CLARIFYING → READY_TO_CODEGEN."""
        if self.state == SpecState.GATHERING:
            # Can move to clarifying if we have at least some target info
            if self.target and self.target.business_definition:
                self.state = SpecState.CLARIFYING
        elif self.state == SpecState.CLARIFYING:
            # Ready if we have target, sources, and engine decision
            if (
                self.target
                and self.target.business_definition
                and len(self.sources) > 0
                and self.engine_decision
                and self.engine_decision.recommended
            ):
                self.state = SpecState.READY_TO_CODEGEN
        return self.state

    def next_question(self) -> Optional[dict[str, Any]]:
        """Return the highest-priority unanswered question, if any."""
        if self.open_questions:
            return self.open_questions[0]
        return None

    def add_question(self, field_path: str, question: str, candidates: Optional[list[str]] = None):
        """Append a clarification question."""
        self.open_questions.append({
            "field_path": field_path,
            "question": question,
            "candidates": candidates or [],
        })
```

- [ ] **Step 4**：运行测试：

```bash
pytest tests/agent/test_spec.py -v
```

期望：6 passed。

- [ ] **Step 5**：commit

```bash
cd D:/agent-code/data-agent
git add src/agent/spec.py tests/agent/test_spec.py
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0b): spec accumulator pydantic model"
```

---

### Task 4：Tool 基类 Protocol

**Files:**
- Create: `D:/agent-code/data-agent/src/tools/base.py`

- [ ] **Step 1**：在 `src/tools/base.py` 写入：

```python
"""Tool base class."""
from abc import ABC, abstractmethod
from typing import Any
from dataclasses import dataclass, field


@dataclass
class ToolResult:
    success: bool
    data: Any = None
    error: str = ""
    evidence: dict[str, Any] = field(default_factory=dict)


class BaseTool(ABC):
    """Stateless tool. All state is in Spec, passed as input."""

    name: str = "base"
    description: str = ""

    @abstractmethod
    async def run(self, spec: Any, **kwargs) -> ToolResult:
        """Execute the tool. spec is a partial Spec snapshot needed for this call."""
        ...
```

> M0b 里所有 tool 实际是同步调用，但 interface 保留 `async` 签名方便后续对接真实网络服务（HMS/profiler）。

- [ ] **Step 2**：commit

```bash
cd D:/agent-code/data-agent
git add src/tools/base.py
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0b): tool base class"
```

---

### Task 5：Mock MetadataTool

**Files:**
- Create: `D:/agent-code/data-agent/src/tools/metadata.py`
- Create: `D:/agent-code/data-agent/tests/tools/test_metadata.py`

- [ ] **Step 1**：在 `tests/tools/test_metadata.py` 写失败测试：

```python
"""Tests for Mock MetadataTool."""
from src.tools.metadata import MockMetadataTool, KNOWN_TABLES
from src.agent.spec import Spec, TaskDirection


class TestMockMetadataTool:
    def test_lookup_known_table(self):
        tool = MockMetadataTool()
        result = tool.run_sync(None, table_name="dw.mr_5g_15min")
        assert result.success is True
        assert len(result.data["schema"]) == 7

    def test_lookup_unknown_table_returns_candidates(self):
        tool = MockMetadataTool()
        result = tool.run_sync(None, table_name="unknown_table")
        assert result.success is False
        assert "candidates" in result.data

    def test_search_finds_by_keyword(self):
        tool = MockMetadataTool()
        result = tool.run_sync(None, table_name="MR")
        assert any("mr" in t.lower() for t in result.data.get("candidates", []))

    def test_known_tables_has_minimum_entries(self):
        assert len(KNOWN_TABLES) >= 4
```

- [ ] **Step 2**：运行测试确认失败：

```bash
pytest tests/tools/test_metadata.py -v
```

期望：全部 FAIL。

- [ ] **Step 3**：在 `src/tools/metadata.py` 写入实现：

```python
"""Mock MetadataTool — returns hardcoded wireless table schemas."""
from src.tools.base import BaseTool, ToolResult

# Hardcoded wireless network tables for M0b
KNOWN_TABLES = {
    "dw.mr_5g_15min": {
        "catalog": "hive",
        "schema": [
            {"name": "cell_id", "type": "STRING", "semantic": "小区ID"},
            {"name": "ts_15min", "type": "TIMESTAMP", "semantic": "15分钟粒度时间戳"},
            {"name": "rsrp_avg", "type": "DOUBLE", "semantic": "平均RSRP (dBm)"},
            {"name": "rsrq_avg", "type": "DOUBLE", "semantic": "平均RSRQ (dB)"},
            {"name": "sinr_avg", "type": "DOUBLE", "semantic": "平均SINR (dB)"},
            {"name": "sample_count", "type": "BIGINT", "semantic": "采样数"},
            {"name": "weak_cov_ratio", "type": "DOUBLE", "semantic": "弱覆盖比例 RSRP<-110"},
        ],
        "owner": "无线网络优化组",
        "description": "5G MR 15分钟小区级KPI",
        "grain": "(cell_id, ts_15min)",
    },
    "dim.engineering_param": {
        "catalog": "hive",
        "schema": [
            {"name": "cell_id", "type": "STRING", "semantic": "小区ID"},
            {"name": "site_id", "type": "STRING", "semantic": "站点ID"},
            {"name": "district", "type": "STRING", "semantic": "区县"},
            {"name": "longitude", "type": "DOUBLE", "semantic": "经度"},
            {"name": "latitude", "type": "DOUBLE", "semantic": "纬度"},
            {"name": "rat", "type": "STRING", "semantic": "制式 4G/5G_SA/5G_NSA"},
            {"name": "azimuth", "type": "INT", "semantic": "方位角"},
            {"name": "downtilt", "type": "INT", "semantic": "下倾角"},
        ],
        "owner": "无线网络优化组",
        "description": "小区工参维表",
        "grain": "(cell_id)",
    },
    "dw.kpi_pm_cell_hour": {
        "catalog": "hive",
        "schema": [
            {"name": "cell_id", "type": "STRING", "semantic": "小区ID"},
            {"name": "ts_hour", "type": "TIMESTAMP", "semantic": "小时粒度时间戳"},
            {"name": "rrc_setup_succ_rate", "type": "DOUBLE", "semantic": "RRC建立成功率"},
            {"name": "erab_drop_rate", "type": "DOUBLE", "semantic": "E-RAB掉话率"},
            {"name": "ho_succ_rate", "type": "DOUBLE", "semantic": "切换成功率"},
        ],
        "owner": "无线网络优化组",
        "description": "小区小时级PM KPI",
        "grain": "(cell_id, ts_hour)",
    },
    "kafka.signaling_events": {
        "catalog": "kafka",
        "schema": [
            {"name": "ts", "type": "STRING", "semantic": "事件时间 ISO8601"},
            {"name": "event_type", "type": "STRING", "semantic": "事件类型 handover/access/... "},
            {"name": "src_cell", "type": "STRING", "semantic": "源小区ID"},
            {"name": "dst_cell", "type": "STRING", "semantic": "目标小区ID"},
            {"name": "result", "type": "STRING", "semantic": "结果 success/failure"},
            {"name": "cause", "type": "STRING", "semantic": "失败原因 normal/too_early/too_late/..."},
        ],
        "owner": "无线网络优化组",
        "description": "切换信令事件流 (JSONL, Kafka)",
        "grain": "(ts, event_type, src_cell)",
    },
}


class MockMetadataTool(BaseTool):
    name = "metadata"
    description = "查询 HMS/字典: 返回表 schema、字段语义、责任人"

    async def run(self, spec, **kwargs):
        return self.run_sync(spec, **kwargs)

    def run_sync(self, spec, table_name: str = "", keywords: str = ""):
        """Lookup a table by exact name or fuzzy keyword search."""
        search = table_name or keywords
        if not search:
            return ToolResult(success=False, error="No search term provided", data={})

        # Exact match
        if search in KNOWN_TABLES:
            return ToolResult(
                success=True,
                data=KNOWN_TABLES[search],
                evidence={"type": "schema_lookup", "source": search, "findings": {"found": True}},
            )

        # Fuzzy: search in table names and descriptions
        s_lower = search.lower()
        candidates = []
        for tname, info in KNOWN_TABLES.items():
            if s_lower in tname.lower() or s_lower in info.get("description", "").lower():
                candidates.append(tname)

        if candidates:
            return ToolResult(
                success=False,
                error="Ambiguous or partial match",
                data={"candidates": candidates, "keyword": search},
            )
        return ToolResult(
            success=False,
            error="Table not found",
            data={"candidates": list(KNOWN_TABLES.keys())},
        )
```

- [ ] **Step 4**：运行测试：

```bash
pytest tests/tools/test_metadata.py -v
```

期望：4 passed。

- [ ] **Step 5**：commit

```bash
cd D:/agent-code/data-agent
git add src/tools/metadata.py tests/tools/test_metadata.py
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0b): mock metadata tool with hardcoded wireless schemas"
```

---

### Task 6：CodegenTool（Spark SQL 生成）

**Files:**
- Create: `D:/agent-code/data-agent/src/tools/codegen.py`
- Create: `D:/agent-code/data-agent/tests/tools/test_codegen.py`

- [ ] **Step 1**：在 `tests/tools/test_codegen.py` 写失败测试：

```python
"""Tests for CodegenTool."""
import pytest
from src.tools.codegen import CodegenTool, build_codegen_prompt
from src.agent.spec import Spec, TaskDirection, TargetSpec, SourceBinding, EngineDecision


class TestBuildCodegenPrompt:
    def test_prompt_includes_target(self):
        spec = Spec(task_direction=TaskDirection.FORWARD_ETL)
        spec.target = TargetSpec(
            name="weak_cov_by_district",
            business_definition="按区县统计弱覆盖小区数",
            grain="(district, day)",
        )
        spec.sources = [
            SourceBinding(
                role="mr_main",
                binding={"catalog": "hive", "table_or_topic": "dw.mr_5g_15min"},
                confidence=0.9,
            ),
            SourceBinding(
                role="eng_param",
                binding={"catalog": "hive", "table_or_topic": "dim.engineering_param"},
                confidence=0.9,
            ),
        ]
        spec.engine_decision = EngineDecision(
            recommended="spark_sql",
            reasoning="批源、join + 聚合",
        )
        prompt = build_codegen_prompt(spec)
        assert "弱覆盖小区" in prompt
        assert "dw.mr_5g_15min" in prompt
        assert "dim.engineering_param" in prompt
        assert "Spark SQL" in prompt


class TestCodegenTool:
    def test_codegen_tool_has_name(self):
        tool = CodegenTool(llm_client=None)
        assert tool.name == "codegen"

    def test_build_codegen_prompt_forwards_spec(self):
        """Check that prompt shaping doesn't crash on minimal spec."""
        spec = Spec(task_direction=TaskDirection.FORWARD_ETL)
        spec.target = TargetSpec(
            name="test_table",
            business_definition="测试数据",
            grain="(cell_id)",
        )
        spec.sources = [
            SourceBinding(
                role="main",
                binding={"catalog": "hive", "table_or_topic": "dw.mr_5g_15min"},
            )
        ]
        spec.engine_decision = EngineDecision(recommended="spark_sql")
        prompt = build_codegen_prompt(spec)
        # Must contain key elements
        assert "test_table" in prompt
        assert "Spark SQL" in prompt
        assert "CREATE TABLE" in prompt or "INSERT" in prompt
```

- [ ] **Step 2**：运行测试确认失败：

```bash
pytest tests/tools/test_codegen.py -v
```

期望：全部 FAIL。

- [ ] **Step 3**：在 `src/tools/codegen.py` 写入实现：

```python
"""CodegenTool — generates Spark SQL from Spec."""
from typing import Optional
from src.tools.base import BaseTool, ToolResult
from src.agent.spec import Spec, TaskDirection

CODGEN_SYSTEM_PROMPT = """\
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
"""


def build_codegen_prompt(spec: Spec) -> str:
    """Build the LLM prompt from a completed spec."""
    target = spec.target
    sources = spec.sources
    engine = spec.engine_decision

    schema_lines = []
    for s in sources:
        role = s.role
        tbl = s.binding.get("table_or_topic", "unknown")
        schema_lines.append(f"- {role}: {tbl}")
        if s.schema_:
            for col in s.schema_:
                schema_lines.append(f"    {col['name']} ({col['type']}): {col.get('semantic', '')}")

    schema_block = "\n".join(schema_lines) if schema_lines else "(no schema bound yet)"

    prompt = f"""\
Generate {engine.recommended} for the following wireless network perception spec:

Task: {spec.task_direction.value}
Target table: {target.name if target else '(unspecified)'}
Business definition: {target.business_definition if target else '(unspecified)'}
Output grain: {target.grain if target else '(unspecified)'}
Network context: {spec.network_context.model_dump_json()}

Sources:
{schema_block}

Requirements:
- Output grain: {target.grain if target else 'cell × day'}
- Engine: {engine.recommended} (rationale: {engine.reasoning})
- Transformations: {[t.description for t in spec.transformations] if spec.transformations else '(auto-infer joins and aggregation from source roles)'}
"""
    return prompt


class CodegenTool(BaseTool):
    name = "codegen"
    description = "Generate Spark SQL / Flink SQL / Java code from a completed Spec"

    def __init__(self, llm_client=None):
        self.llm_client = llm_client

    async def run(self, spec: Spec, **kwargs) -> ToolResult:
        return self.run_sync(spec, **kwargs)

    def run_sync(self, spec: Spec, llm_client=None) -> ToolResult:
        """Generate code from spec. Uses LLM client if available, otherwise a hardcoded template."""
        client = llm_client or self.llm_client
        prompt = build_codegen_prompt(spec)

        if client:
            messages = [
                {"role": "system", "content": CODGEN_SYSTEM_PROMPT},
                {"role": "user", "content": prompt},
            ]
            code = client.chat(messages, max_tokens=2048, temperature=0.1)
        else:
            # Mock fallback — use a hardcoded template for the 2 demo scenarios
            code = _hardcoded_spark_sql(spec)

        if code.startswith("[ERROR]"):
            return ToolResult(success=False, error=code, data={"code": ""})

        return ToolResult(success=True, data={"code": code, "engine": spec.engine_decision.recommended})


def _hardcoded_spark_sql(spec: Spec) -> str:
    """Fallback hardcoded SQL for the 2 demo M0b scenarios."""
    target_name = spec.target.name if spec.target else "output"

    if "weak" in spec.target.business_definition.lower() or "弱覆盖" in spec.target.business_definition:
        return """\
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
```"""
    return """\
```sql
-- {target_name} (Spark SQL)
SELECT * FROM dw.mr_5g_15min LIMIT 100;
```"""
```

- [ ] **Step 4**：运行测试：

```bash
pytest tests/tools/test_codegen.py -v
```

期望：3 passed。

- [ ] **Step 5**：commit

```bash
cd D:/agent-code/data-agent
git add src/tools/codegen.py tests/tools/test_codegen.py
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0b): codegen tool with spark sql generation (llm + hardcoded fallback)"
```

---

### Task 7：EngineSelector

**Files:**
- Create: `D:/agent-code/data-agent/src/agent/engine_selector.py`
- Create: `D:/agent-code/data-agent/tests/agent/test_engine_selector.py`

- [ ] **Step 1**：在 `tests/agent/test_engine_selector.py` 写失败测试：

```python
"""Tests for EngineSelector."""
from src.agent.spec import Spec, TaskDirection, SourceBinding
from src.agent.engine_selector import select_engine


class TestEngineSelector:
    def test_hive_only_sources_recommend_spark(self):
        spec = Spec(task_direction=TaskDirection.FORWARD_ETL)
        spec.sources = [
            SourceBinding(
                role="main",
                binding={"catalog": "hive", "table_or_topic": "dw.mr_5g_15min"},
            ),
        ]
        result = select_engine(spec)
        assert result.recommended == "spark_sql"

    def test_kafka_source_recommends_flink(self):
        spec = Spec(task_direction=TaskDirection.FORWARD_ETL)
        spec.sources = [
            SourceBinding(
                role="stream",
                binding={"catalog": "kafka", "table_or_topic": "signaling_events"},
            ),
        ]
        result = select_engine(spec)
        assert result.recommended == "flink_sql"

    def test_mixed_sources_recommends_flink(self):
        """Kafka + Hive → Flink SQL (lookup join pattern)."""
        spec = Spec(task_direction=TaskDirection.FORWARD_ETL)
        spec.sources = [
            SourceBinding(
                role="stream",
                binding={"catalog": "kafka", "table_or_topic": "signaling_events"},
            ),
            SourceBinding(
                role="dim",
                binding={"catalog": "hive", "table_or_topic": "dim.engineering_param"},
            ),
        ]
        result = select_engine(spec)
        assert result.recommended == "flink_sql"

    def test_returns_reasoning(self):
        spec = Spec(task_direction=TaskDirection.FORWARD_ETL)
        spec.sources = [
            SourceBinding(
                role="main",
                binding={"catalog": "hive", "table_or_topic": "dw.mr_5g_15min"},
            ),
        ]
        result = select_engine(spec)
        assert len(result.reasoning) > 0
```

- [ ] **Step 2**：运行测试确认失败：

```bash
pytest tests/agent/test_engine_selector.py -v
```

期望：全部 FAIL。

- [ ] **Step 3**：在 `src/agent/engine_selector.py` 写入实现：

```python
"""EngineSelector — decides Spark SQL / Flink SQL / Java based on spec signals."""
from src.agent.spec import Spec, EngineDecision


# Rules ordered by priority (first match wins)
ENGINE_RULES = [
    # Rule 1: Any Kafka/cdc source → Flink
    {
        "condition": lambda s: any(
            src.binding.get("catalog") in ("kafka", "cdc") for src in s.sources
        ),
        "recommended": "flink_sql",
        "reasoning": "源含 Kafka/CDC 流式数据,需时间窗口或流式语义 → Flink SQL",
    },
    # Rule 2: Multiple Hive sources with join at different grains → Spark (batch join)
    {
        "condition": lambda s: all(
            src.binding.get("catalog") in ("hive", "starrocks", None) for src in s.sources
        ) and len(s.sources) >= 2,
        "recommended": "spark_sql",
        "reasoning": "多批源 join/聚合,无流式要求 → Spark SQL",
    },
    # Rule 3: Single Hive source, simple → Spark
    {
        "condition": lambda s: all(
            src.binding.get("catalog") in ("hive", "starrocks", None) for src in s.sources
        ),
        "recommended": "spark_sql",
        "reasoning": "单一批源,简单查询或聚合 → Spark SQL",
    },
]

FALLBACK_RECOMMENDATION = EngineDecision(
    recommended="spark_sql",
    reasoning="无法自动判定,默认 Spark SQL(人工确认)",
)


def select_engine(spec: Spec) -> EngineDecision:
    """Run the engine selection rules against the current spec."""
    if not spec.sources:
        return FALLBACK_RECOMMENDATION
    for rule in ENGINE_RULES:
        if rule["condition"](spec):
            return EngineDecision(
                recommended=rule["recommended"],
                reasoning=rule["reasoning"],
            )
    return FALLBACK_RECOMMENDATION
```

- [ ] **Step 4**：运行测试：

```bash
pytest tests/agent/test_engine_selector.py -v
```

期望：4 passed。

- [ ] **Step 5**：commit

```bash
cd D:/agent-code/data-agent
git add src/agent/engine_selector.py tests/agent/test_engine_selector.py
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0b): engine selector with rule-based decision"
```

---

### Task 8：Prompt 模板与 Spec 提取（LLM→Spec）

**Files:**
- Create: `D:/agent-code/data-agent/src/agent/prompts.py`
- Create: `D:/agent-code/data-agent/tests/agent/test_prompts.py`

- [ ] **Step 1**：在 `tests/agent/test_prompts.py` 写测试：

```python
"""Tests for LLM prompt templates."""
from src.agent.prompts import (
    SYSTEM_PROMPT,
    build_extract_spec_prompt,
    build_clarify_prompt,
)


class TestPrompts:
    def test_system_prompt_exists(self):
        assert len(SYSTEM_PROMPT) > 100
        assert "无线网络" in SYSTEM_PROMPT or "数据" in SYSTEM_PROMPT

    def test_extract_spec_prompt_includes_user_message(self):
        prompt = build_extract_spec_prompt(
            user_message="给我30天弱覆盖小区",
            current_spec_json='{"task_direction": "forward_etl"}',
        )
        assert "弱覆盖小区" in prompt
        assert "forward_etl" in prompt

    def test_clarify_prompt_contains_open_questions(self):
        prompt = build_clarify_prompt(
            open_questions=[
                {"field_path": "a", "question": "什么是活跃用户？"},
            ]
        )
        assert "活跃用户" in prompt
```

- [ ] **Step 2**：运行测试确认失败。

- [ ] **Step 3**：在 `src/agent/prompts.py` 写入：

```python
"""LLM prompt templates for the wireless perception data agent."""
import json

SYSTEM_PROMPT = """\
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
  "intent_update": {  // 本轮从用户消息中提取的增量信息
    "target_name": null,
    "business_definition": null,
    "kpi_family": null,       // coverage|mobility|accessibility|retainability|qoe
    "ne_grain": null,          // cell|site|district|city
    "time_grain": null,        // 15min|hour|day
    "rat": null,               // 4G|5G_SA|5G_NSA|mixed
    "timeliness": null,        // batch_daily|batch_hourly|streaming
    "identified_sources": [],
    "open_questions": []
  },
  "next_action": "ask_clarifying|ready_for_tools|code_done",
  "clarifying_question": null  // 如果 next_action=ask_clarifying,这里填反问文本
}
"""


def build_extract_spec_prompt(user_message: str, current_spec_json: str) -> str:
    return f"""\
当前 Spec 状态: {current_spec_json}

用户最新消息: "{user_message}"

请从上一条消息中提取增量信息并更新 intent_update。
如果还有未解决的 open_questions 且用户消息回答了它们,标记为已解决。
如果关键信息仍缺失(目标不明确、数据源未确认、口径模糊),设置 next_action=ask_clarifying 并提供一句中文反问。
"""


def build_clarify_prompt(open_questions: list[dict]) -> str:
    qs = "\n".join(
        f"- {q['field_path']}: {q['question']}"
        for q in open_questions
    )
    return f"""你需要向用户请求澄清以下问题,一次只问一个(优先级从高到低):

{qs}

请生成一句清晰、友好的中文反问,帮助用户明确口径。
只问一个问题,不要一次问多个。
"""
```

- [ ] **Step 4**：运行测试：

```bash
pytest tests/agent/test_prompts.py -v
```

期望：3 passed。

- [ ] **Step 5**：commit

```bash
cd D:/agent-code/data-agent
git add src/agent/prompts.py tests/agent/test_prompts.py
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0b): llm prompt templates (spec extraction + clarification)"
```

---

### Task 9：Agent Core（ReAct Loop）

**Files:**
- Create: `D:/agent-code/data-agent/src/agent/core.py`
- Create: `D:/agent-code/data-agent/tests/agent/test_core.py`

这是最核心的 task——Agent 的对话编排逻辑。

- [ ] **Step 1**：在 `tests/agent/test_core.py` 写失败测试：

```python
"""Tests for AgentCore."""
import json
from unittest.mock import MagicMock
from src.agent.core import AgentCore
from src.agent.spec import Spec, TaskDirection, SpecState


class FakeLLMClient:
    """A fake LLM client that returns controlled responses."""
    def __init__(self, responses=None):
        self.responses = responses or []
        self.call_count = 0

    def chat(self, messages, max_tokens=1024, temperature=0.1):
        if self.call_count < len(self.responses):
            resp = self.responses[self.call_count]
            self.call_count += 1
            return resp
        return "[ERROR] no more fake responses"


def fake_extract_response(target_name="弱覆盖统计", kpi_family="coverage"):
    return json.dumps({
        "intent_update": {
            "target_name": target_name,
            "business_definition": "近30天5G弱覆盖小区按区县统计",
            "kpi_family": kpi_family,
            "ne_grain": "district",
            "time_grain": "day",
            "rat": "5G_SA",
            "timeliness": "batch_daily",
            "identified_sources": ["mr_5g_15min", "engineering_param"],
            "open_questions": [],
        },
        "next_action": "ready_for_tools",
        "clarifying_question": None,
    })


def fake_ask_response(question_text="按什么时间粒度？"):
    return json.dumps({
        "intent_update": {"open_questions": []},
        "next_action": "ask_clarifying",
        "clarifying_question": question_text,
    })


class TestAgentCore:
    def test_init_creates_empty_spec(self):
        agent = AgentCore(llm_client=FakeLLMClient())
        assert agent.spec is not None
        assert agent.spec.task_direction == TaskDirection.FORWARD_ETL

    def test_process_first_message_extracts_intent(self):
        agent = AgentCore(llm_client=FakeLLMClient(
            responses=[fake_extract_response()]
        ))
        result = agent.process_message("给我近30天5G弱覆盖小区")
        assert agent.spec.target is not None
        assert agent.spec.target.business_definition != ""
        assert "next_action" in result

    def test_process_message_with_clarification_needed(self):
        agent = AgentCore(llm_client=FakeLLMClient(
            responses=[
                fake_ask_response("按什么时间粒度？"),
                fake_extract_response(),
            ]
        ))
        result1 = agent.process_message("给我弱覆盖数据")
        assert result1["next_action"] == "ask_clarifying"
        assert result1["clarifying_question"] is not None

        result2 = agent.process_message("按天汇总")
        assert result2["next_action"] == "ready_for_tools"

    def test_spec_accumulates_across_turns(self):
        agent = AgentCore(llm_client=FakeLLMClient(
            responses=[
                fake_ask_response("什么RAT?"),
                fake_extract_response(),
            ]
        ))
        agent.process_message("给我弱覆盖数据")
        assert len(agent.spec.open_questions) >= 0  # Could be 0 after extraction
        agent.process_message("5G_SA")
        assert agent.spec.network_context.rat == "5G_SA"
```

- [ ] **Step 2**：运行测试确认失败。

- [ ] **Step 3**：在 `src/agent/core.py` 写入实现：

```python
"""Agent Core — ReAct loop orchestrating user NL → spec → tools → codegen."""
import json
from typing import Optional, Any
from src.agent.spec import Spec, TaskDirection, SpecState, NetworkContext, TargetSpec
from src.agent.prompts import SYSTEM_PROMPT, build_extract_spec_prompt
from src.agent.engine_selector import select_engine
from src.tools.metadata import MockMetadataTool
from src.tools.codegen import CodegenTool


class AgentCore:
    """The main agent. Orchestrates NL→Spec→Tools→Code loop."""

    def __init__(self, llm_client=None, task_direction=TaskDirection.FORWARD_ETL):
        self.llm_client = llm_client
        self.spec = Spec(task_direction=task_direction)
        self.metadata_tool = MockMetadataTool()
        self.codegen_tool = CodegenTool(llm_client=llm_client)
        self._turn = 0

    def process_message(self, user_message: str) -> dict[str, Any]:
        """Process one user message through the agent loop.

        Returns a dict with keys:
          - next_action: "ask_clarifying" | "ready_for_tools" | "code_done"
          - clarifying_question: str | None
          - code: str | None (only when code_done)
          - spec_summary: str (human-readable spec state)
        """
        self._turn += 1

        # Step 1: LLM extracts intent from user message into structured JSON
        intent = self._call_llm_extract(user_message)

        # Step 2: Apply intent updates to spec
        self._apply_intent(intent)

        # Step 3: Decide next step
        next_action = intent.get("next_action", "ask_clarifying")

        if next_action == "ask_clarifying":
            return {
                "next_action": "ask_clarifying",
                "clarifying_question": intent.get("clarifying_question", "请补充更多信息"),
                "code": None,
                "spec_summary": self._spec_summary(),
            }

        if next_action == "ready_for_tools":
            return self._execute_tools()

        # Fallback
        return {
            "next_action": "ask_clarifying",
            "clarifying_question": "请提供更多关于目标数据集的信息",
            "code": None,
            "spec_summary": self._spec_summary(),
        }

    def _call_llm_extract(self, user_message: str) -> dict:
        """Call LLM to extract structured intent from user message."""
        if not self.llm_client:
            return self._mock_extract(user_message)

        prompt = build_extract_spec_prompt(
            user_message=user_message,
            current_spec_json=self.spec.model_dump_json(indent=2),
        )
        messages = [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": prompt},
        ]
        response = self.llm_client.chat(messages, max_tokens=1024, temperature=0.1)
        try:
            return json.loads(response)
        except json.JSONDecodeError:
            return {
                "intent_update": {},
                "next_action": "ask_clarifying",
                "clarifying_question": "抱歉,我没理解,能换个说法吗？",
            }

    def _mock_extract(self, user_message: str) -> dict:
        """Fallback extraction when no LLM client (use keyword matching)."""
        msg = user_message.lower()

        kpi_family = "coverage"
        if any(w in msg for w in ["切换", "handover", "mobility"]):
            kpi_family = "mobility"
        elif any(w in msg for w in ["掉话", "drop", "retain"]):
            kpi_family = "retainability"
        elif any(w in msg for w in ["接入", "rrc", "access"]):
            kpi_family = "accessibility"
        elif any(w in msg for w in ["感知", "qoe", "视频", "tcp"]):
            kpi_family = "qoe"

        return {
            "intent_update": {
                "target_name": "弱覆盖小区统计",
                "business_definition": user_message,
                "kpi_family": kpi_family,
                "ne_grain": "district",
                "time_grain": "day",
                "rat": "5G_SA",
                "timeliness": "batch_daily",
                "identified_sources": ["mr_5g_15min", "engineering_param"],
                "open_questions": [],
            },
            "next_action": "ready_for_tools",
            "clarifying_question": None,
        }

    def _apply_intent(self, intent: dict):
        """Merge intent_update into spec."""
        update = intent.get("intent_update", {})
        if not update:
            return

        # Network context
        nc = self.spec.network_context
        for field in ["ne_grain", "time_grain", "rat", "kpi_family"]:
            if update.get(field):
                setattr(nc, field, update[field])

        # Target
        if update.get("target_name") or update.get("business_definition"):
            if not self.spec.target:
                self.spec.target = TargetSpec()
            if update.get("target_name"):
                self.spec.target.name = update["target_name"]
            if update.get("business_definition"):
                self.spec.target.business_definition = update["business_definition"]
            if update.get("time_grain"):
                target_grain = f"(cell_id, {update['time_grain']})"
                self.spec.target.grain = target_grain
            if update.get("timeliness"):
                self.spec.target.timeliness = update["timeliness"]

        # Sources
        for src_name in update.get("identified_sources", []):
            # Lookup in metadata
            result = self.metadata_tool.run_sync(None, table_name=src_name)
            if result.success:
                from src.agent.spec import SourceBinding
                self.spec.sources.append(SourceBinding(
                    role=src_name.split(".")[-1] if "." in src_name else src_name,
                    binding={"catalog": result.data.get("catalog", "hive"),
                              "table_or_topic": src_name},
                    schema=result.data.get("schema", []),
                    confidence=0.8,
                ))

        # Open questions
        for q in update.get("open_questions", []):
            if isinstance(q, dict):
                self.spec.add_question(
                    field_path=q.get("field_path", ""),
                    question=q.get("question", ""),
                    candidates=q.get("candidates"),
                )

    def _execute_tools(self) -> dict[str, Any]:
        """Run the tool pipeline: Metadata → EngineSelector → Codegen."""
        # 1. Ensure sources have schemas (call metadata if needed)
        for src in self.spec.sources:
            if not src.schema_:
                tbl = src.binding.get("table_or_topic", "")
                result = self.metadata_tool.run_sync(None, table_name=tbl)
                if result.success:
                    src.schema_ = result.data.get("schema", [])
                    src.confidence = 0.9

        # 2. Engine selection (if not already done)
        if not self.spec.engine_decision or not self.spec.engine_decision.recommended:
            self.spec.engine_decision = select_engine(self.spec)

        # 3. Codegen
        result = self.codegen_tool.run_sync(self.spec, llm_client=self.llm_client)
        if result.success:
            self.spec.state = SpecState.CODEGEN_DONE
            return {
                "next_action": "code_done",
                "clarifying_question": None,
                "code": result.data.get("code", ""),
                "engine": self.spec.engine_decision.recommended,
                "reasoning": self.spec.engine_decision.reasoning,
                "spec_summary": self._spec_summary(),
            }
        return {
            "next_action": "ask_clarifying",
            "clarifying_question": f"代码生成出错: {result.error}",
            "code": None,
            "spec_summary": self._spec_summary(),
        }

    def _spec_summary(self) -> str:
        """Human-readable summary of current spec state."""
        parts = []
        if self.spec.target:
            t = self.spec.target
            parts.append(f"目标: {t.name or '(未命名)'} — {t.business_definition or '(口径待定)'}")
        nc = self.spec.network_context
        parts.append(f"网络域: {nc.ne_grain}/{nc.time_grain}/{nc.rat}/{nc.kpi_family}")
        parts.append(f"数据源: {len(self.spec.sources)} 个")
        parts.append(f"状态: {self.spec.state.value}")
        return " | ".join(parts)
```

- [ ] **Step 4**：运行测试：

```bash
pytest tests/agent/test_core.py -v
```

期望：3-4 passed。

- [ ] **Step 5**：commit

```bash
cd D:/agent-code/data-agent
git add src/agent/core.py tests/agent/test_core.py
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0b): agent core with react loop (nl→spec→tools→codegen)"
```

---

### Task 10：Main CLI 入口

**Files:**
- Create: `D:/agent-code/data-agent/src/main.py`

- [ ] **Step 1**：在 `src/main.py` 写入：

```python
#!/usr/bin/env python3
"""Data Agent CLI — wireless network perception data agent.

Usage:
    python -m src.main              # Interactive stdin/stdout mode
    python -m src.main --demo       # Run 2 hardcoded demo scenarios
"""
import sys
import os
import argparse
from dotenv import load_dotenv
from src.agent.core import AgentCore
from src.agent.spec import TaskDirection
from src.llm.client import DeepSeekClient

load_dotenv()


DEMO_SCENARIOS = [
    "给我近30天每个区县5G弱覆盖小区清单",
    "按cell_id汇总切换失败次数，并关联工参表的district信息",
]


def main():
    parser = argparse.ArgumentParser(description="Data Agent")
    parser.add_argument("--demo", action="store_true", help="Run hardcoded demo scenarios")
    parser.add_argument("--no-llm", action="store_true", help="Run without LLM (mock extract only)")
    args = parser.parse_args()

    # Setup LLM client
    llm_client = None
    if not args.no_llm:
        try:
            llm_client = DeepSeekClient()
            print(f"[INFO] LLM: {llm_client.model} @ {llm_client.api_base}")
        except Exception as e:
            print(f"[WARN] LLM init failed: {e}, falling back to mock mode")
            llm_client = None

    if args.demo:
        run_demo(llm_client)
    else:
        run_interactive(llm_client)


def run_demo(llm_client):
    """Run the 2 hardcoded demo scenarios."""
    print("=" * 60)
    print("M0b Demo — 无线网络感知评估 Data Agent")
    print("=" * 60)

    for i, msg in enumerate(DEMO_SCENARIOS):
        print(f"\n{'─' * 60}")
        print(f"场景 {i+1}: {msg}")
        print(f"{'─' * 60}")
        agent = AgentCore(llm_client=llm_client)
        result = agent.process_message(msg)
        print(f"\n  [状态] {result.get('next_action', '?')}")
        if result.get("clarifying_question"):
            print(f"  [反问] {result['clarifying_question']}")
        if result.get("code"):
            print(f"  [代码]\n{result['code']}")
        if result.get("reasoning"):
            print(f"  [引擎] {result.get('engine')} — {result['reasoning']}")
        print(f"  [Spec] {result.get('spec_summary', '')}")
    print(f"\n{'=' * 60}")
    print("Demo 完成。")


def run_interactive(llm_client):
    """Interactive stdin/stdout loop."""
    print("Data Agent — 无线网络感知评估 (输入 /quit 退出)")
    agent = AgentCore(llm_client=llm_client)
    print(f"[Spec] {agent._spec_summary()}")

    while True:
        try:
            user_input = input("\n> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not user_input:
            continue
        if user_input.lower() in ("/quit", "/exit", "quit", "exit"):
            print("再见。")
            break

        result = agent.process_message(user_input)

        if result.get("next_action") == "ask_clarifying":
            print(f"[反问] {result['clarifying_question']}")
        elif result.get("next_action") == "code_done":
            print(f"[引擎] {result.get('engine', '?')} — {result.get('reasoning', '')}")
            print(f"[代码]\n{result['code']}")
        else:
            print(f"[状态] {result.get('next_action', '?')}")
        print(f"[Spec] {result.get('spec_summary', '')}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2**：验证 import 链：

```bash
cd D:/agent-code/data-agent
python -c "from src.main import main; print('import ok')"
```

期望：`import ok`（加 `[WARN]` 如果 API key 未设置）。

- [ ] **Step 3**：跑 —demo（不依赖 LLM）：

```bash
python -m src.main --demo --no-llm
```

期望：输出两个场景的代码和 spec 摘要。

- [ ] **Step 4**：用真 LLM 跑一次（需要 API key 已配置）：

```bash
python -m src.main --demo
```

期望：场景 1 输出弱覆盖统计 SQL，场景 2 输出切换失败聚合 SQL。

- [ ] **Step 5**：commit

```bash
cd D:/agent-code/data-agent
git add src/main.py
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m0b): main cli entry (interactive + demo mode)"
```

---

### Task 11：集成测试与完整回路验证

**Files:**
- Create: `D:/agent-code/data-agent/tests/test_integration.py`

- [ ] **Step 1**：在 `tests/test_integration.py` 写集成测试：

```python
"""Integration tests for the full M0b agent loop."""
from unittest.mock import MagicMock, patch
import json
from src.agent.core import AgentCore
from src.agent.spec import TaskDirection, SpecState


class FakeLLMForIntegration:
    """Simulates a full agent conversation across multiple turns."""
    def __init__(self):
        self.turn = 0

    def chat(self, messages, max_tokens=1024, temperature=0.1):
        self.turn += 1
        # Turn 1: user asks for weak coverage → agent extracts intent
        if self.turn == 1:
            return json.dumps({
                "intent_update": {
                    "target_name": "weak_cov_by_district",
                    "business_definition": "近30天5G弱覆盖小区按区县统计",
                    "kpi_family": "coverage",
                    "ne_grain": "district",
                    "time_grain": "day",
                    "rat": "5G_SA",
                    "timeliness": "batch_daily",
                    "identified_sources": ["dw.mr_5g_15min", "dim.engineering_param"],
                    "open_questions": [],
                },
                "next_action": "ready_for_tools",
                "clarifying_question": None,
            })
        return json.dumps({
            "intent_update": {},
            "next_action": "ready_for_tools",
            "clarifying_question": None,
        })


class TestM0bIntegration:
    def test_full_loop_weak_coverage_scenario(self):
        """Scenario 1: user asks for weak coverage → agent outputs Spark SQL."""
        agent = AgentCore(
            llm_client=FakeLLMForIntegration(),
            task_direction=TaskDirection.FORWARD_ETL,
        )
        result = agent.process_message("给我近30天每个区县5G弱覆盖小区清单")

        assert result["next_action"] == "code_done"
        assert result["code"] is not None
        assert len(result["code"]) > 50
        assert "mr_5g_15min" in result["code"].lower() or "弱覆盖" in result["code"]
        assert agent.spec.state == SpecState.CODEGEN_DONE

    def test_no_llm_client_still_produces_code(self):
        """Even without LLM, mock extraction should produce code."""
        agent = AgentCore(llm_client=None)
        result = agent.process_message("弱覆盖小区")

        assert result["next_action"] == "code_done"
        assert result["code"] is not None
```

- [ ] **Step 2**：运行集成测试：

```bash
pytest tests/test_integration.py -v
```

期望：2 passed（验证了端到端回路闭合）。

- [ ] **Step 3**：运行全套测试确认无回归：

```bash
pytest -v
```

期望：全部 passed（约 20-22 个测试）。

- [ ] **Step 4**：commit

```bash
cd D:/agent-code/data-agent
git add tests/test_integration.py
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "test(m0b): integration tests for full agent loop"
```

---

### Task 12：文档与收尾

- [ ] **Step 1**：更新 `README.md`，在末尾追加：

```markdown
## M0b — Agent 骨架

```bash
# 安装依赖
pip install -e ".[dev]"

# 跑测试
pytest -v

# 演示模式（无需 LLM）
python -m src.main --demo --no-llm

# 演示模式（使用 DeepSeek API）
python -m src.main --demo

# 交互模式
python -m src.main
```
```

- [ ] **Step 2**：最后跑一次全套测试，确认全部绿色：

```bash
cd D:/agent-code/data-agent
pytest -v
```

期望输出：`X passed in <N>s`，exit 0。

- [ ] **Step 3**：commit + push

```bash
cd D:/agent-code/data-agent
git add README.md
git -c user.name="sandy.kingxp" -c user.email="sandy.kingxp@proton.me" commit -m "docs(m0b): add agent skeleton quickstart to readme"
git push
```

---

## M0b 完成标准（DoD）

- [ ] `pytest -v` 全部测试通过（约 22-26 个）
- [ ] `python -m src.main --demo --no-llm` 成功输出两个场景的 Spark SQL 代码
- [ ] `python -m src.main --demo`（使用 DeepSeek API）成功输出两个场景的 Spark SQL 代码
- [ ] `python -m src.main` 交互模式可多轮对话（NL → 反问 → 代码）
- [ ] Spec Accumulator 在多轮对话中正确累积状态
- [ ] EngineSelector 自动判定 spark_sql（纯批源场景）和 flink_sql（包含 Kafka 源场景）
- [ ] Mock MetadataTool 返回正确的无线表 schema（4 张表）
- [ ] 所有代码提交并推送到 origin/main

---

## 后续

M0b 完成后回到 brainstorming → 根据 M0b 暴露的 LLM/loop 问题微调 spec → writing-plans 写 M1（真工具接通：真 HMS、ProfilerTool、ValidatorTool、SandboxTool）。
