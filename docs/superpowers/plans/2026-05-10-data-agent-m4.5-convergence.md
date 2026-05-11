# M5 多轮反问收敛 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现多轮反问收敛机制：Agent 每轮只问一个最关键问题 → 解析用户回复填充 Spec 缺口 → 自动判定规格收敛 → 推进到代码生成阶段。

**Architecture:** Spec 新增 `Question` 内类（含 answer/resolved 字段）替代裸 Map；ClarifyTool 新工具负责问题优先级排序和反问生成；AgentCore 新增 ConvergenceGuard 收敛判定（轮次上限/全部回答/用户说"好的"）；specState 状态机真正接入 processMessage 驱动流程分支；applyIntent 新增用户回复解析逻辑。

**Tech Stack:** Java 17, Maven, JUnit 5, AssertJ, Jackson JSON

---

## 文件结构（M5 完成后的新增/变更）

```
data-agent/
├── src/main/java/com/wireless/agent/
│   ├── core/
│   │   ├── Spec.java              # 修改: +Question 内类, +markAnswered, +unansweredQuestions
│   │   ├── Prompts.java           # 修改: +buildReplyParsingPrompt, 更新系统提示词
│   │   └── AgentCore.java         # 修改: +ClarifyTool, +ConvergenceGuard, +advanceState 接入
│   └── tools/
│       └── ClarifyTool.java       # 新增: 反问工具（优先排序 + 反问生成）
└── src/test/java/com/wireless/agent/
    ├── core/
    │   ├── SpecTest.java          # 修改: +Question 模型测试
    │   └── PromptsTest.java       # 修改: +replyParsing 提示词测试
    ├── tools/
    │   └── ClarifyToolTest.java   # 新增: 反问工具测试
    └── IntegrationTest.java       # 修改: +多轮收敛端到端
```

---

### Task 1: Spec.Question 内类 — 问题模型升级

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/core/Spec.java`
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/core/SpecTest.java`

- [ ] **Step 1: 在 SpecTest.java 追加 Question 模型测试**

```java
@Test
void shouldCreateQuestionWithAnswer() {
    var q = new Spec.Question("data_scale", "数据规模多大?", List.of("1k", "10k", "1M"));
    assertThat(q.fieldPath()).isEqualTo("data_scale");
    assertThat(q.question()).contains("数据规模");
    assertThat(q.candidates()).containsExactly("1k", "10k", "1M");
    assertThat(q.resolved()).isFalse();
    assertThat(q.answer()).isNull();

    var answered = q.withAnswer("10k");
    assertThat(answered.resolved()).isTrue();
    assertThat(answered.answer()).isEqualTo("10k");
}

@Test
void shouldAddAndRetrieveUnansweredQuestions() {
    var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
    spec.addQuestion("target_name", "目标表名?", List.of());
    spec.addQuestion("time_grain", "时间粒度?", List.of("hour", "day"));

    var unanswered = spec.unansweredQuestions();
    assertThat(unanswered).hasSize(2);
    assertThat(unanswered.get(0).fieldPath()).isEqualTo("target_name");
}

@Test
void shouldMarkQuestionAnswered() {
    var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
    spec.addQuestion("target_name", "目标表名?", List.of("handover_kpi", "coverage_kpi"));
    spec.addQuestion("time_grain", "时间粒度?", List.of("hour", "day"));

    spec.markAnswered("target_name", "handover_kpi");

    var unanswered = spec.unansweredQuestions();
    assertThat(unanswered).hasSize(1);
    assertThat(unanswered.get(0).fieldPath()).isEqualTo("time_grain");
    assertThat(spec.openQuestions().get(0).resolved()).isTrue();
}

@Test
void shouldNotCrashWhenMarkingNonexistentField() {
    var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
    spec.markAnswered("nonexistent", "value");
    assertThat(spec.unansweredQuestions()).isEmpty();
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=SpecTest
```

期望: 4 个新测试编译失败（`Question` 类、`markAnswered`、`unansweredQuestions` 方法不存在）。

- [ ] **Step 3: 在 Spec.java 添加 Question 内类**

在 `Evidence` record 之后、`Spec fields` 之前插入：

```java
public record Question(
        @JsonProperty("field_path")  String fieldPath,
        @JsonProperty("question")   String question,
        @JsonProperty("candidates") List<String> candidates,
        @JsonProperty("answer")     String answer,
        @JsonProperty("resolved")   boolean resolved) {

    public Question(String fieldPath, String question, List<String> candidates) {
        this(fieldPath, question,
             candidates != null ? candidates : List.of(), null, false);
    }

    /** Return a new Question with answer filled and resolved=true. */
    public Question withAnswer(String a) {
        return new Question(fieldPath, question, candidates, a, true);
    }
}
```

- [ ] **Step 4: 修改 openQuestions 类型和 addQuestion 方法**

将 `openQuestions` 字段类型从 `List<Map<String, Object>>` 改为 `List<Question>`：

```java
@JsonProperty("open_questions")  private List<Question> openQuestions = new ArrayList<>();
```

修改 getter：

```java
public List<Question> openQuestions() { return openQuestions; }
```

重写 `addQuestion` 方法：

```java
public void addQuestion(String fieldPath, String question, List<String> candidates) {
    openQuestions.add(new Question(fieldPath, question, candidates));
}
```

修改 `nextQuestion` 方法（返回第一个未回答的）：

```java
public Question nextQuestion() {
    return openQuestions.stream()
            .filter(q -> !q.resolved())
            .findFirst()
            .orElse(null);
}
```

- [ ] **Step 5: 在 Spec.java 添加 markAnswered 和 unansweredQuestions 方法**

在 `addQuestion` 方法之后添加：

```java
/** Mark the first unanswered question with matching fieldPath as resolved with the given answer. */
public void markAnswered(String fieldPath, String answer) {
    for (int i = 0; i < openQuestions.size(); i++) {
        var q = openQuestions.get(i);
        if (!q.resolved() && q.fieldPath().equals(fieldPath)) {
            openQuestions.set(i, q.withAnswer(answer));
            return;
        }
    }
}

/** Return all questions that have not been answered yet. */
public List<Question> unansweredQuestions() {
    return openQuestions.stream()
            .filter(q -> !q.resolved())
            .toList();
}
```

- [ ] **Step 6: 运行 SpecTest**

```bash
mvn test -Dtest=SpecTest
```

期望: 13 passed（原有 9 + 新增 4）。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/wireless/agent/core/Spec.java src/test/java/com/wireless/agent/core/SpecTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m5): add Question record with answer/resolved fields and convergence helpers"
```

---

### Task 2: SpecState 状态机接入 AgentCore

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/core/AgentCore.java`

- [ ] **Step 1: 在 AgentCore.applyIntent 末尾调用 advanceState**

在 `applyIntent` 方法最后一行（`}` 之前）添加：

```java
spec.advanceState();
```

- [ ] **Step 2: 修改 processMessage 使用 spec.state() 驱动分支**

将现有 `processMessage` 中的 next_action 分支改为也检查 spec.state()。在 `callLlmExtract` 和 `applyIntent` 之后，替换现有的分支逻辑。找到这段代码：

```java
if ("ask_clarifying".equals(nextAction)) {
    return Map.of(
        "next_action", "ask_clarifying",
        ...
    );
}

if ("ready_for_tools".equals(nextAction)) {
    return executeTools();
}
```

替换为（保留原逻辑，新增 state 检查作为兜底）：

```java
if ("ask_clarifying".equals(nextAction) || spec.state() == Spec.SpecState.CLARIFYING) {
    return Map.of(
        "next_action", "ask_clarifying",
        "clarifying_question", intent.getOrDefault("clarifying_question", "请补充更多信息"),
        "code", "",
        "spec_summary", specSummary(),
        "state", spec.state().value()
    );
}

if ("ready_for_tools".equals(nextAction) || spec.state() == Spec.SpecState.READY_TO_CODEGEN) {
    return executeTools();
}
```

同时在返回 Map 中添加 `"state"` 字段。

- [ ] **Step 3: 修改 specSummary 显示当前状态**

在 `specSummary` 方法的最后一行添加状态信息（已有 `"状态: " + spec.state().value()`，确认保持）。

- [ ] **Step 4: 运行全部测试确认无回归**

```bash
mvn test
```

期望: 136 passed（无回归）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/core/AgentCore.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m5): wire SpecState machine into AgentCore processMessage flow"
```

---

### Task 3: Prompts — 反问提示词 + 回复解析提示词

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/core/Prompts.java`
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/core/PromptsTest.java`

- [ ] **Step 1: 在 PromptsTest.java 追加测试**

```java
@Test
void shouldBuildReplyParsingPrompt() {
    var currentQuestion = new Spec.Question("time_grain", "时间粒度是小时还是天?",
            List.of("hour", "day"));
    var prompt = Prompts.buildReplyParsingPrompt("按天统计", currentQuestion, "{}");

    assertThat(prompt).contains("时间粒度");
    assertThat(prompt).contains("按天统计");
    assertThat(prompt).contains("hour");
    assertThat(prompt).contains("day");
    assertThat(prompt).contains("field_path");
}

@Test
void shouldBuildClarifyPromptWithUnansweredQuestions() {
    var qs = List.of(
            new Spec.Question("target_name", "目标表名?", List.of()),
            new Spec.Question("time_grain", "时间粒度?", List.of("hour", "day")));

    var prompt = Prompts.buildClarifyPrompt(qs);
    assertThat(prompt).contains("目标表名");
    assertThat(prompt).contains("时间粒度");
    assertThat(prompt).contains("一次只问一个");
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=PromptsTest
```

期望: 2 个新测试编译失败（`buildReplyParsingPrompt` 不存在，`buildClarifyPrompt` 参数类型不匹配）。

- [ ] **Step 3: 修改 Prompts.java — 更新 buildClarifyPrompt 签名**

将 `buildClarifyPrompt` 的参数类型从 `List<Map<String, Object>>` 改为 `List<Spec.Question>`：

```java
public static String buildClarifyPrompt(List<Spec.Question> openQuestions) {
    var qs = openQuestions.stream()
            .map(q -> "- " + q.fieldPath() + ": " + q.question())
            .collect(Collectors.joining("\n"));
    return String.format("""
            你需要向用户请求澄清以下问题,一次只问一个(优先级从高到低):

            %s

            请生成一句清晰、友好的中文反问,帮助用户明确口径。
            只问一个问题,不要一次问多个。
            """, qs);
}
```

- [ ] **Step 4: 在 Prompts.java 新增 buildReplyParsingPrompt**

在 `buildClarifyPrompt` 方法之后添加：

```java
/** Parse user reply against the current open question and update spec. */
public static String buildReplyParsingPrompt(
        String userMessage, Spec.Question currentQuestion, String currentSpecJson) {
    return String.format("""
            当前 Spec 状态: %s

            上一轮反问: %s
            可选候选项: %s

            用户回复: "%s"

            请解析用户回复:
            1. 判断用户是否直接回答了问题 → 提取答案字符串
            2. 如果用户回复匹配某个候选项,选择最匹配的那个作为答案
            3. 如果用户说"就这样"/"好的"/"继续"/"go ahead",设置 next_action=ready_for_tools
            4. 如果用户回复模糊或反问新问题,生成新的反问

            在 intent_update.open_questions 中:
            - 将已回答的问题 field_path 设为当前问题,附上 answer 和 resolved:true
            - 包含当前问题的答案在 intent_update 相应字段中(如用户说"按天"→ time_grain="day")

            输出标准 JSON 回复。
            """,
            currentSpecJson,
            currentQuestion.question(),
            currentQuestion.candidates(),
            userMessage);
}
```

- [ ] **Step 5: 运行 PromptsTest**

```bash
mvn test -Dtest=PromptsTest
```

期望: 5 passed（原有 3 + 新增 2）。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/wireless/agent/core/Prompts.java src/test/java/com/wireless/agent/core/PromptsTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m5): add reply parsing prompt and update clarify prompt for Question model"
```

---

### Task 4: ClarifyTool — 反问工具

**Files:**
- Create: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/tools/ClarifyTool.java`
- Create: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/tools/ClarifyToolTest.java`

- [ ] **Step 1: 创建 ClarifyToolTest.java**

```java
package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClarifyToolTest {

    @Test
    void shouldPickHighestPriorityQuestion() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        // Lower priority (time_grain) first
        spec.addQuestion("time_grain", "时间粒度?", List.of("hour", "day"));
        // Higher priority (target_name) second — should be picked
        spec.addQuestion("target_name", "目标表名?", List.of("handover_kpi", "coverage_kpi"));

        var tool = new ClarifyTool();
        var picked = tool.pickNextQuestion(spec);

        assertThat(picked).isNotNull();
        assertThat(picked.fieldPath()).isEqualTo("target_name");
    }

    @Test
    void shouldReturnNullWhenAllQuestionsAnswered() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.addQuestion("target_name", "目标?", List.of("t1"));
        spec.markAnswered("target_name", "t1");

        var tool = new ClarifyTool();
        assertThat(tool.pickNextQuestion(spec)).isNull();
    }

    @Test
    void shouldGenerateClarifyingQuestionWithoutLlm() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.addQuestion("target_name", "目标表名是什么?", List.of("handover_kpi", "coverage_kpi"));
        spec.target(new Spec.TargetSpec().name("(未命名)").businessDefinition("切换失败统计"));

        var tool = new ClarifyTool();
        var result = tool.run(spec);

        assertThat(result.success()).isTrue();
        var data = result.data();
        assertThat(data).isNotNull();
        assertThat(data.toString()).containsAnyOf("目标表名", "handover_kpi", "coverage_kpi");
    }

    @Test
    void shouldScoreSpecGapsByPriority() {
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        spec.target(new Spec.TargetSpec().name("test").businessDefinition("test def"));
        // target exists but no sources → should detect source gap
        spec.addQuestion("identified_sources", "需要哪些数据源?", List.of("mr", "pm"));

        var tool = new ClarifyTool();
        var gaps = tool.detectGaps(spec);
        assertThat(gaps).isNotEmpty();
        assertThat(gaps).anyMatch(g -> g.contains("source") || g.contains("数据源"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn test -Dtest=ClarifyToolTest
```

期望: 编译失败（ClarifyTool 类不存在）。

- [ ] **Step 3: 创建 ClarifyTool.java**

```java
package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;

import java.util.*;

/** Picks the single most important unanswered question and generates a clarifying question. */
public class ClarifyTool {

    /** Priority order: target definition > business_definition > sources > grain > timeliness > other. */
    private static final List<String> PRIORITY_ORDER = List.of(
            "target_name", "business_definition", "identified_sources",
            "ne_grain", "time_grain", "rat", "timeliness");

    @Override
    public String name() { return "clarify"; }

    @Override
    public String description() { return "从 open_questions 中挑选最高优先级问题并生成反问"; }

    /** Pick the highest-priority unanswered question. */
    public Spec.Question pickNextQuestion(Spec spec) {
        var unanswered = spec.unansweredQuestions();
        if (unanswered.isEmpty()) return null;

        return unanswered.stream()
                .min(Comparator.comparingInt(q -> {
                    var idx = PRIORITY_ORDER.indexOf(q.fieldPath());
                    return idx >= 0 ? idx : PRIORITY_ORDER.size();
                }))
                .orElse(null);
    }

    /** Detect what's still missing from the spec. */
    public List<String> detectGaps(Spec spec) {
        var gaps = new ArrayList<String>();
        if (spec.target() == null || spec.target().name().isEmpty()) {
            gaps.add("target_name: 目标数据集名称缺失");
        }
        if (spec.target() == null || spec.target().businessDefinition().isEmpty()) {
            gaps.add("business_definition: 业务口径缺失");
        }
        if (spec.sources().isEmpty()) {
            gaps.add("identified_sources: 数据源未确认");
        }
        if (spec.networkContext().timeGrain().isEmpty()
                || spec.networkContext().timeGrain().equals("15min")) {
            gaps.add("time_grain: 时间粒度待确认");
        }
        return gaps;
    }

    @Override
    public ToolResult run(Spec spec) {
        var question = pickNextQuestion(spec);
        if (question == null) {
            return ToolResult.ok(Map.of(
                    "converged", true,
                    "message", "所有问题已回答,可以进入代码生成"));
        }

        var questionText = buildHardcodedQuestion(question, spec);
        return ToolResult.ok(Map.of(
                "converged", false,
                "field_path", question.fieldPath(),
                "question", questionText,
                "candidates", question.candidates()
        ));
    }

    /** Build a clarifying question without LLM (hardcoded templates). */
    private String buildHardcodedQuestion(Spec.Question q, Spec spec) {
        var field = q.fieldPath();
        var def = spec.target() != null ? spec.target().businessDefinition() : "";
        var candidates = q.candidates();

        if (!candidates.isEmpty()) {
            var options = String.join("/", candidates);
            return q.question() + " (" + options + ")";
        }

        return switch (field) {
            case "target_name" ->
                    "目标数据集应该叫什么名字? (如: handover_failure_by_cell)";
            case "business_definition" ->
                    "请一句话描述业务口径: \"" + (def.isEmpty() ? "?" : def) + "\" 具体指什么?";
            case "identified_sources" ->
                    "需要用到哪些数据源? (如: signaling_events, dw.mr_5g_15min)";
            case "time_grain" ->
                    "时间粒度是小时(hour)还是天(day)?";
            case "ne_grain" ->
                    "空间粒度是小区(cell)还是区县(district)?";
            default -> q.question();
        };
    }
}
```

- [ ] **Step 4: 运行 ClarifyToolTest**

```bash
mvn test -Dtest=ClarifyToolTest
```

期望: 4 passed（所有新增测试通过）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wireless/agent/tools/ClarifyTool.java src/test/java/com/wireless/agent/tools/ClarifyToolTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m5): add ClarifyTool with priority-based question picking and gap detection"
```

---

### Task 5: AgentCore — 收敛判定 + 回复解析

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/core/AgentCore.java`

- [ ] **Step 1: 添加 ConvergenceGuard 字段和常量**

在 AgentCore 类字段区域添加：

```java
private static final int MAX_CLARIFY_TURNS = 5;
private static final List<String> FORCE_PROCEED_KEYWORDS = List.of(
        "就这样", "好的", "继续", "go ahead", "ok", "可以", "没问题", "直接生成");
private final ClarifyTool clarifyTool;
```

在构造函数中初始化 `clarifyTool`。在所有构造函数中添加：

```java
this.clarifyTool = new ClarifyTool();
```

（在所有现有构造函数中的 `this.sandboxTool = ...` 之后添加）

- [ ] **Step 2: 添加收敛判定方法**

在 `executeTools` 方法之后添加：

```java
/** Determine if the spec has converged enough to proceed to codegen. */
private boolean shouldConverge() {
    // All questions answered
    if (spec.unansweredQuestions().isEmpty()) return true;

    // State machine says ready
    if (spec.state() == Spec.SpecState.READY_TO_CODEGEN) return true;

    // Turn limit reached
    if (turn >= MAX_CLARIFY_TURNS) return true;

    return false;
}

/** Check if user message contains force-proceed keywords. */
private boolean isForceProceed(String userMessage) {
    var lower = userMessage.toLowerCase();
    return FORCE_PROCEED_KEYWORDS.stream().anyMatch(kw -> lower.contains(kw));
}
```

- [ ] **Step 3: 修改 mockExtract 支持回复解析**

在 `mockExtract` 方法的开头（在 `var msg = userMessage.toLowerCase();` 之后），添加回复解析逻辑。将现有逻辑包装为检测用户是否在回复上一轮反问：

```java
// Check if user is replying to an existing open question
var currentQ = spec.nextQuestion();
if (currentQ != null && !isPastingCode(msg, userMessage)) {
    // Try to match answer from candidates
    var matched = matchAnswer(userMessage, currentQ);
    if (matched != null) {
        spec.markAnswered(currentQ.fieldPath(), matched);
        // If the answer maps to a spec field, apply it
        applyAnswerToSpec(currentQ.fieldPath(), matched);
    }
}
```

在 `mockExtract` 方法之后添加辅助方法：

```java
private boolean isPastingCode(String lowerMsg, String msg) {
    return containsAny(lowerMsg, "select", "insert", "from", "where", "group by") && msg.length() > 50;
}

/** Try to match user reply to one of the question's candidates. */
private String matchAnswer(String userMessage, Spec.Question question) {
    if (question.candidates().isEmpty()) {
        // No candidates — use the user message as answer if it's short
        return userMessage.length() < 100 ? userMessage.trim() : null;
    }
    var lower = userMessage.toLowerCase();
    for (var c : question.candidates()) {
        if (lower.contains(c.toLowerCase())) return c;
    }
    // If no match but reply is short, use it as-is
    return userMessage.length() < 50 ? userMessage.trim() : null;
}

/** Apply a resolved answer to the corresponding spec field. */
private void applyAnswerToSpec(String fieldPath, String answer) {
    switch (fieldPath) {
        case "target_name" -> {
            if (spec.target() == null) spec.target(new Spec.TargetSpec());
            spec.target().name(answer);
        }
        case "business_definition" -> {
            if (spec.target() == null) spec.target(new Spec.TargetSpec());
            spec.target().businessDefinition(answer);
        }
        case "time_grain" -> spec.networkContext().timeGrain(answer);
        case "ne_grain" -> spec.networkContext().neGrain(answer);
        case "rat" -> spec.networkContext().rat(answer);
        // Other fields stored in question.answer, applied via intent_update
    }
}
```

- [ ] **Step 4: 修改 processMessage 接入收敛逻辑**

修改 `processMessage`，在 `applyIntent(intent)` 之后、检查 `nextAction` 之前插入收敛判定：

```java
applyIntent(intent);

// Check force-proceed
if (isForceProceed(userMessage)) {
    spec.state(Spec.SpecState.READY_TO_CODEGEN);
    return executeTools();
}

// If still clarifying and haven't converged, ask next question
if (!shouldConverge()) {
    var nextQ = spec.nextQuestion();
    if (nextQ == null && !spec.unansweredQuestions().isEmpty()) {
        // All questions have answers pending, wait for next turn
    }
}
```

然后替换现有的 `ask_clarifying` 分支：

```java
if ("ask_clarifying".equals(nextAction) || (!shouldConverge() && spec.state() != Spec.SpecState.READY_TO_CODEGEN)) {
    // Use ClarifyTool to generate the next question
    var clarifyResult = clarifyTool.run(spec);
    @SuppressWarnings("unchecked")
    var data = (Map<String, Object>) clarifyResult.data();
    var questionText = data != null ? data.getOrDefault("question", "").toString() : "";
    var llmQuestion = intent.getOrDefault("clarifying_question", "").toString();

    return Map.of(
        "next_action", "ask_clarifying",
        "clarifying_question", !llmQuestion.isEmpty() ? llmQuestion : questionText,
        "code", "",
        "spec_summary", specSummary(),
        "state", spec.state().value(),
        "turn", turn
    );
}
```

- [ ] **Step 5: 运行全部测试确认无回归**

```bash
mvn test
```

期望: 全部通过，无回归。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/wireless/agent/core/AgentCore.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m5): add convergence guard with turn limit, reply parsing, and force-proceed detection"
```

---

### Task 6: Main — 多轮交互显示

**Files:**
- Modify: `D:/agent-code/data-agent/src/main/java/com/wireless/agent/Main.java`

- [ ] **Step 1: 修改 runInteractive 显示轮次和收敛状态**

在 `runInteractive` 的 switch 分支中添加状态显示。修改现有的 `ask_clarifying` case：

```java
case "ask_clarifying" -> {
    var turn = result.get("turn");
    var state = result.get("state");
    var turnInfo = turn != null ? "[轮次 " + turn + "/5] " : "";
    var stateInfo = state != null ? "(" + state + ") " : "";
    System.out.println(turnInfo + stateInfo + "[反问] " + result.get("clarifying_question"));
}
```

在 `code_done` case 中添加收敛信息：

```java
case "code_done" -> {
    var turn = result.get("turn");
    if (turn != null) {
        System.out.println("[收敛] 经过 " + turn + " 轮反问,规格已就绪");
    }
    System.out.println("[引擎] " + result.get("engine") + " — " + result.get("reasoning"));
    System.out.println("[代码]\n" + result.get("code"));
}
```

修改 `default` case：

```java
default -> {
    var turn = result.get("turn");
    var prefix = turn != null ? "[轮次 " + turn + "] " : "";
    System.out.println(prefix + "[状态] " + result.get("next_action"));
}
```

- [ ] **Step 2: 运行全部测试**

```bash
mvn test
```

期望: 全部通过。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wireless/agent/Main.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "feat(m5): show turn count and convergence progress in interactive mode"
```

---

### Task 7: 集成测试 — 多轮反问收敛端到端

**Files:**
- Modify: `D:/agent-code/data-agent/src/test/java/com/wireless/agent/IntegrationTest.java`

- [ ] **Step 1: 在 IntegrationTest.java 追加多轮收敛测试**

```java
@Test
void shouldConvergeAfterAnsweringQuestions() {
    var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
            "thrift://nonexistent:9999", "da-spark-master", "da-flink-jobmanager",
            new DomainKnowledgeBase());

    // Turn 1: Vague request triggers clarifying question
    var result1 = agent.processMessage("做个切换失败统计");
    assertThat(result1.get("next_action")).isIn("ask_clarifying", "code_done");

    // If clarifying, answer the question and check convergence
    if ("ask_clarifying".equals(result1.get("next_action"))) {
        var result2 = agent.processMessage("按cell_id汇总,用signaling_events表");
        // Should make progress (either more questions or ready for codegen)
        assertThat(result2.get("next_action"))
                .isIn("ask_clarifying", "code_done", "dry_run_ok", "sandbox_failed");
    }
}

@Test
void shouldForceProceedOnKeyword() {
    var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
            "thrift://nonexistent:9999", "da-spark-master", "da-flink-jobmanager",
            new DomainKnowledgeBase());

    // Turn 1: Vague request
    agent.processMessage("做个覆盖分析");

    // Turn 2: Force proceed
    var result = agent.processMessage("就这样,直接生成");
    // Should proceed to codegen regardless of unresolved questions
    assertThat(result.get("next_action"))
            .isIn("code_done", "dry_run_ok", "sandbox_failed");
    assertThat(result.get("code").toString()).isNotEmpty();
}

@Test
void shouldTrackTurnCountAcrossConversation() {
    var agent = new AgentCore(null, Spec.TaskDirection.FORWARD_ETL,
            "thrift://nonexistent:9999", "da-spark-master", "da-flink-jobmanager",
            new DomainKnowledgeBase());

    agent.processMessage("做个切换分析");
    agent.processMessage("按cell_id汇总");
    var result = agent.processMessage("用signaling_events数据源,直接生成代码");

    assertThat(result.get("next_action"))
            .isIn("code_done", "dry_run_ok", "sandbox_failed");
    // After 3 turns, should have converged or force-proceeded
}
```

- [ ] **Step 2: 运行集成测试**

```bash
mvn test -Dtest=IntegrationTest
```

期望: 17 passed（原有 14 + 新增 3）。

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/wireless/agent/IntegrationTest.java
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "test(m5): multi-turn convergence integration tests with force-proceed and turn tracking"
```

---

### Task 8: 文档与最终验证

**Files:**
- Modify: `D:/agent-code/data-agent/README.md`

- [ ] **Step 1: 更新 README.md 追加 M5 段落**

在 M4 段落后追加：

```markdown
## M5 -- Multi-Turn Clarifying Conversation Convergence

**Convergence mechanism** ensures the agent asks targeted questions and converges to codegen:

| Component | Description |
|-----------|-------------|
| ClarifyTool | Picks highest-priority unanswered question (target > sources > grain > timeliness) |
| Reply Parsing | Matches user answers against question candidates, auto-fills spec fields |
| ConvergenceGuard | Turn limit (max 5), force-proceed keywords ("就这样"/"好的"), all-resolved check |
| SpecState Machine | GATHERING → CLARIFYING → READY_TO_CODEGEN → CODEGEN_DONE, now wired into flow |

**Usage:**
```bash
# Interactive mode with convergence (default FORWARD_ETL)
mvn exec:java -Dexec.mainClass="com.wireless.agent.Main"

# Reverse synthetic with convergence
mvn exec:java -Dexec.mainClass="com.wireless.agent.Main" -- --reverse
```

**Example conversation:**
```
> 做个切换失败统计
[反问] 目标数据集应该叫什么名字? (handover_failure / ho_kpi)

> handover_failure_by_cell
[反问] 需要用到哪些数据源? (signaling_events / dw.kpi_pm_cell_hour)

> signaling_events
[收敛] 经过 3 轮反问,规格已就绪
[引擎] flink_sql — ...
[代码] ...
```
```

- [ ] **Step 2: 最终验证**

```bash
mvn test
```

- [ ] **Step 3: Commit**

```bash
git add README.md
git -c user.name="kingx" -c user.email="sandy.kingxp@proton.me" commit -m "docs(m5): add multi-turn convergence quickstart to readme"
```

---

## M5 完成标准（DoD）

- [ ] `mvn test` 全部测试通过，无回归
- [ ] Spec.Question record 支持 answer/resolved 字段
- [ ] spec.markAnswered() 正确标记已回答问题
- [ ] spec.unansweredQuestions() 返回未回答列表
- [ ] ClarifyTool 按优先级挑选问题
- [ ] ClarifyTool 无 LLM 时生成硬编码反问
- [ ] AgentCore 收敛判定: 全部回答 / turn 上限 / 强制继续关键词
- [ ] applyIntent 解析用户回复 → 匹配候选项 → 填充 spec 字段
- [ ] SpecState 状态机接入 processMessage 流程
- [ ] Main 交互模式显示轮次和收敛进度
- [ ] 多轮收敛集成测试通过

---

## 后续

M5 完成后 → M6（闭环上线: SchedulerTool + UserPreferencesStore + Trace/Replay）。
