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
    @JsonProperty("original_pipeline") private String originalPipeline;
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
    public String originalPipeline() { return originalPipeline; }
    public Spec originalPipeline(String v) { originalPipeline = v; return this; }

    // ─── Methods ─────────────────────────────────────

    public SpecState advanceState() {
        boolean progressed;
        do {
            progressed = false;
            if (state == SpecState.GATHERING) {
                if (target != null && !target.businessDefinition.isEmpty()) {
                    state = SpecState.CLARIFYING;
                    progressed = true;
                }
            } else if (state == SpecState.CLARIFYING) {
                if (target != null && !target.businessDefinition.isEmpty()
                        && !sources.isEmpty()
                        && engineDecision != null
                        && engineDecision.recommended() != null
                        && !engineDecision.recommended().isEmpty()) {
                    state = SpecState.READY_TO_CODEGEN;
                    progressed = true;
                }
            }
        } while (progressed);
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
