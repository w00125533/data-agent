package com.wireless.agent.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.wireless.agent.knowledge.DomainKnowledgeBase;
import com.wireless.agent.llm.DeepSeekClient;
import com.wireless.agent.tools.*;

import java.util.*;

public class AgentCore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private final DeepSeekClient llmClient;
    private final Spec spec;
    private final DomainKnowledgeBase kb;
    private final BaselineService baselineService;
    private final HmsMetadataTool metadataTool;
    private final ProfilerTool profilerTool;
    private final CodegenTool codegenTool;
    private final ValidatorTool validatorTool;
    private final SandboxTool sandboxTool;
    private final DockerCommandRunner cmdRunner;
    private int turn;
    private static final int MAX_CLARIFY_TURNS = 5;
    private static final List<String> FORCE_PROCEED_KEYWORDS = List.of(
            "就这样", "好的", "继续", "go ahead", "ok", "可以", "没问题", "直接生成");
    private final ClarifyTool clarifyTool;

    public AgentCore(DeepSeekClient llmClient) {
        this(llmClient, Spec.TaskDirection.FORWARD_ETL,
             "thrift://hive-metastore:9083", "da-spark-master",
             new DomainKnowledgeBase());
    }

    public AgentCore(DeepSeekClient llmClient, Spec.TaskDirection taskDirection) {
        this(llmClient, taskDirection,
             "thrift://hive-metastore:9083", "da-spark-master",
             new DomainKnowledgeBase());
    }

    public AgentCore(DeepSeekClient llmClient, Spec.TaskDirection taskDirection,
                     String hmsUri, String sparkContainer) {
        this(llmClient, taskDirection, hmsUri, sparkContainer,
             "da-flink-jobmanager", new DomainKnowledgeBase());
    }

    public AgentCore(DeepSeekClient llmClient, Spec.TaskDirection taskDirection,
                     String hmsUri, String sparkContainer,
                     DomainKnowledgeBase kb) {
        this(llmClient, taskDirection, hmsUri, sparkContainer,
             "da-flink-jobmanager", kb);
    }

    public AgentCore(DeepSeekClient llmClient, Spec.TaskDirection taskDirection,
                     String hmsUri, String sparkContainer, String flinkContainer,
                     DomainKnowledgeBase kb) {
        this.llmClient = llmClient;
        this.spec = new Spec(taskDirection);
        this.cmdRunner = new DockerCommandRunner();
        this.kb = kb;
        this.baselineService = new BaselineService(cmdRunner, sparkContainer);
        this.metadataTool = new HmsMetadataTool(hmsUri, kb);
        this.profilerTool = new ProfilerTool(cmdRunner, sparkContainer, baselineService);
        this.codegenTool = new CodegenTool(llmClient);
        this.validatorTool = new ValidatorTool();
        this.sandboxTool = new SandboxTool(cmdRunner, sparkContainer, flinkContainer, baselineService);
        this.clarifyTool = new ClarifyTool();
    }

    public Spec spec() { return spec; }

    @SuppressWarnings("unchecked")
    public Map<String, Object> processMessage(String userMessage) {
        turn++;

        var intent = callLlmExtract(userMessage);
        applyIntent(intent);

        // Force-proceed: user explicitly asks to continue
        if (isForceProceed(userMessage)) {
            spec.state(Spec.SpecState.READY_TO_CODEGEN);
            return executeTools();
        }

        // If spec is clarifying state and hasn't converged, use ClarifyTool
        if (!shouldConverge() && spec.state() == Spec.SpecState.CLARIFYING) {
            var clarifyResult = clarifyTool.run(spec);
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) clarifyResult.data();
            var questionText = data != null ? data.getOrDefault("question", "").toString() : "";
            spec.advanceState();
            return Map.of(
                "next_action", "ask_clarifying",
                "clarifying_question", !questionText.isEmpty() ? questionText : "请补充更多信息",
                "code", "",
                "spec_summary", specSummary(),
                "state", spec.state().value(),
                "turn", turn
            );
        }

        var nextAction = intent.getOrDefault("next_action", "ask_clarifying").toString();

        if ("ready_for_tools".equals(nextAction) || spec.state() == Spec.SpecState.READY_TO_CODEGEN) {
            return executeTools();
        }

        if ("ask_clarifying".equals(nextAction) || spec.state() == Spec.SpecState.CLARIFYING) {
            var clarifyingQuestion = intent.get("clarifying_question");
            return Map.of(
                "next_action", "ask_clarifying",
                "clarifying_question", clarifyingQuestion != null ? clarifyingQuestion.toString() : "请补充更多信息",
                "code", "",
                "spec_summary", specSummary(),
                "state", spec.state().value(),
                "turn", turn
            );
        }

        return Map.of(
            "next_action", "ask_clarifying",
            "clarifying_question", "请提供更多关于目标数据集的信息",
            "code", "",
            "spec_summary", specSummary(),
            "state", spec.state().value()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callLlmExtract(String userMessage) {
        if (llmClient == null) {
            return mockExtract(userMessage);
        }
        try {
            var currentJson = MAPPER.writeValueAsString(spec);
            var systemPrompt = Prompts.SYSTEM_PROMPT;
            // Inject KB context for the current KPI family
            var kbContext = metadataTool.kbPromptContext(spec.networkContext().kpiFamily());
            if (!kbContext.isEmpty()) {
                systemPrompt = systemPrompt + "\n\n" + kbContext;
            }
            var prompt = Prompts.buildExtractSpecPrompt(userMessage, currentJson);
            var messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
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

        // Check if user is replying to an existing open question
        var currentQ = spec.nextQuestion();
        if (currentQ != null && !isPastingCode(msg, userMessage)) {
            var matched = matchAnswer(userMessage, currentQ);
            if (matched != null) {
                spec.markAnswered(currentQ.fieldPath(), matched);
                applyAnswerToSpec(currentQ.fieldPath(), matched);
            }
        }

        var kpiFamily = "coverage";
        if (containsAny(msg, "切换", "handover", "mobility")) kpiFamily = "mobility";
        else if (containsAny(msg, "掉话", "drop", "retain")) kpiFamily = "retainability";
        else if (containsAny(msg, "接入", "rrc", "access")) kpiFamily = "accessibility";
        else if (containsAny(msg, "感知", "qoe", "视频", "tcp")) kpiFamily = "qoe";

        var isReverse = spec.taskDirection() == Spec.TaskDirection.REVERSE_SYNTHETIC;
        var isPastingCode = isReverse && containsAny(msg, "select", "insert", "from", "where", "group by");

        if (isPastingCode) {
            spec.originalPipeline(userMessage.trim());
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("intent_update", Map.of(
            "target_name", isReverse ? "synthetic_test_data" : "弱覆盖小区统计",
            "business_definition", isReverse ? "反向合成测试数据生成" : userMessage,
            "kpi_family", isReverse ? "synthetic" : kpiFamily,
            "ne_grain", "district",
            "time_grain", "day",
            "rat", "5G_SA",
            "timeliness", "batch_daily",
            "identified_sources", isReverse ? List.of("signaling_events") : List.of("dw.mr_5g_15min", "dim.engineering_param"),
            "open_questions", List.of()
        ));
        result.put("next_action", "ready_for_tools");
        result.put("clarifying_question", null);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void applyIntent(Map<String, Object> intent) {
        var update = (Map<String, Object>) intent.getOrDefault("intent_update", Map.of());
        if (update.isEmpty()) return;

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

        spec.advanceState();
    }

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
        if (profileResult.success() && profileResult.data() != null) {
            @SuppressWarnings("unchecked")
            var evidence = (Map<String, Object>) profileResult.data();
            var sourceNames = spec.sources().stream()
                    .map(s -> s.binding().getOrDefault("table_or_topic", "").toString())
                    .filter(t -> !t.isEmpty())
                    .collect(java.util.stream.Collectors.joining(", "));
            if (sourceNames.isEmpty()) sourceNames = "unknown";
            spec.evidence().add(new Spec.Evidence("data_profile", sourceNames, evidence));
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
        Map<String, Object> dryRunResult;
        if (spec.taskDirection() == Spec.TaskDirection.REVERSE_SYNTHETIC) {
            dryRunResult = sandboxTool.dualDryRun(code, spec);
        } else {
            dryRunResult = sandboxTool.dryRun(code, spec);
        }

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
        response.put("turn", turn);
        return response;
    }

    /** Determine if the spec has converged enough to proceed to codegen. */
    private boolean shouldConverge() {
        if (spec.unansweredQuestions().isEmpty()) return true;
        if (spec.state() == Spec.SpecState.READY_TO_CODEGEN) return true;
        if (turn >= MAX_CLARIFY_TURNS) return true;
        return false;
    }

    /** Check if user message contains force-proceed keywords. */
    private boolean isForceProceed(String userMessage) {
        var lower = userMessage.toLowerCase();
        return FORCE_PROCEED_KEYWORDS.stream().anyMatch(kw -> lower.contains(kw));
    }

    /** Detect if message looks like pasted SQL/code. */
    private boolean isPastingCode(String lowerMsg, String msg) {
        return containsAny(lowerMsg, "select", "insert", "from", "where", "group by") && msg.length() > 50;
    }

    /** Try to match user reply to one of the question's candidates. */
    private String matchAnswer(String userMessage, Spec.Question question) {
        if (question.candidates().isEmpty()) {
            return userMessage.length() < 100 ? userMessage.trim() : null;
        }
        var lower = userMessage.toLowerCase();
        for (var c : question.candidates()) {
            if (lower.contains(c.toLowerCase())) return c;
        }
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
        }
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

    public DomainKnowledgeBase kb() { return kb; }
    public BaselineService baselineService() { return baselineService; }

    private static boolean containsAny(String text, String... keywords) {
        for (var kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
