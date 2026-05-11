package com.wireless.agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wireless.agent.core.AgentCore;
import com.wireless.agent.core.Spec;
import com.wireless.agent.llm.DeepSeekClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Runs eval cases against AgentCore and scores results. */
public class EvalRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String hmsUri;
    private final String sparkContainer;
    private final String flinkContainer;

    public EvalRunner(String hmsUri) {
        this(hmsUri, "da-spark-master", "da-flink-jobmanager");
    }

    public EvalRunner(String hmsUri, String sparkContainer, String flinkContainer) {
        this.hmsUri = hmsUri;
        this.sparkContainer = sparkContainer;
        this.flinkContainer = flinkContainer;
    }

    /** Load eval cases from a JSON file. */
    public static List<EvalCase> loadCases(Path file) throws IOException {
        var content = Files.readString(file);
        return MAPPER.readValue(content, new TypeReference<List<EvalCase>>() {});
    }

    /** Evaluate a single case. */
    public EvalResult evaluate(EvalCase evalCase) {
        var fakeLlm = evalCase.fakeLlmResponses().isEmpty()
                ? null
                : new FakeLLMForEval(evalCase.fakeLlmResponses());
        var agent = new AgentCore(fakeLlm, evalCase.direction(),
                hmsUri, sparkContainer, flinkContainer, new com.wireless.agent.knowledge.DomainKnowledgeBase());

        // Run the conversation
        Map<String, Object> result = null;
        int turns = 0;
        for (int i = 0; i < evalCase.maxTurns(); i++) {
            result = agent.processMessage(evalCase.nlInput());
            turns = i + 1;
            var nextAction = result.get("next_action").toString();
            if ("code_done".equals(nextAction) || "dry_run_ok".equals(nextAction)
                    || "sandbox_failed".equals(nextAction) || "dual_dry_run_ok".equals(nextAction)
                    || "step1_ok_step2_failed".equals(nextAction)) {
                break;
            }
        }

        var spec = agent.spec();
        var specAccuracy = computeSpecAccuracy(spec, evalCase.expectedSpec());

        // Capture final reference for lambda use
        var finalResult = result;
        var codeCompiles = finalResult != null && finalResult.get("code") != null
                && !finalResult.get("code").toString().isBlank();
        var dryRunPassed = finalResult != null && "dry_run_ok".equals(finalResult.get("next_action").toString());
        var codeOk = finalResult != null && evalCase.expectedCodePatterns().stream()
                .allMatch(p -> finalResult.get("code").toString().toLowerCase().contains(p.toLowerCase()));

        // Code compiles only if patterns match AND non-empty
        codeCompiles = codeCompiles && codeOk;

        return new EvalResult(evalCase.id(), evalCase.category(),
                specAccuracy, codeCompiles, dryRunPassed, turns, evalCase.mustDryRun());
    }

    /** Run a full suite and produce a report. */
    public EvalReport runSuite(List<EvalCase> cases, String engine) {
        var results = new ArrayList<EvalResult>();
        for (var c : cases) {
            try {
                results.add(evaluate(c));
            } catch (Exception e) {
                results.add(new EvalResult(c.id(), c.category(), 0.0, false, false, c.maxTurns(), false));
                System.err.println("[EvalRunner] Failed case " + c.id() + ": " + e.getMessage());
            }
        }
        return new EvalReport(results, engine);
    }

    /** Compute spec accuracy by comparing expected fields to actual spec state. */
    double computeSpecAccuracy(Spec spec, Map<String, Object> expected) {
        if (expected.isEmpty()) return 1.0;
        double hits = 0;
        var total = expected.size();

        for (var e : expected.entrySet()) {
            var key = e.getKey();
            var expectedVal = e.getValue();
            switch (key) {
                case "target_name" -> {
                    var target = spec.target();
                    if (target != null && expectedVal.toString().equals(target.name())) hits++;
                }
                case "kpi_family" -> {
                    if (expectedVal.toString().equals(spec.networkContext().kpiFamily())) hits++;
                }
                case "ne_grain" -> {
                    if (expectedVal.toString().equals(spec.networkContext().neGrain())) hits++;
                }
                case "time_grain" -> {
                    if (expectedVal.toString().equals(spec.networkContext().timeGrain())) hits++;
                }
                case "rat" -> {
                    if (expectedVal.toString().equals(spec.networkContext().rat())) hits++;
                }
                case "timeliness" -> {
                    var target = spec.target();
                    if (target != null && expectedVal.toString().equals(target.timeliness())) hits++;
                }
                case "business_definition" -> {
                    var target = spec.target();
                    if (target != null && !target.businessDefinition().isEmpty()) hits += 0.5;
                    if (target != null && target.businessDefinition().contains(expectedVal.toString())) hits += 0.5;
                }
                case "sources" -> {
                    @SuppressWarnings("unchecked")
                    var expectedSources = (List<String>) expectedVal;
                    if (expectedSources != null) {
                        for (var src : expectedSources) {
                            var found = spec.sources().stream()
                                    .anyMatch(s -> s.binding().getOrDefault("table_or_topic", "")
                                            .toString().contains(src));
                            if (found) hits++;
                        }
                        hits = hits / expectedSources.size();
                    }
                }
            }
        }
        return hits / total;
    }

    /** Fake LLM for eval: returns pre-canned responses in sequence. */
    static class FakeLLMForEval extends DeepSeekClient {
        private final List<String> responses;
        private int callCount;

        FakeLLMForEval(List<String> responses) {
            super("https://fake", "sk-fake", "fake-model");
            this.responses = responses;
        }

        @Override
        public String chat(List<Map<String, String>> messages) {
            if (callCount < responses.size()) return responses.get(callCount++);
            return """
            {
              "intent_update": {"open_questions": []},
              "next_action": "ready_for_tools",
              "clarifying_question": null
            }""";
        }

        @Override
        public String chat(okhttp3.OkHttpClient http, List<Map<String, String>> msgs) {
            return chat(msgs);
        }
    }
}
