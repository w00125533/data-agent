package com.wireless.agent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wireless.agent.core.Spec;

import java.util.List;
import java.util.Map;

/** A single evaluation case: NL input → expected spec + code. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalCase(
        @JsonProperty("id")                    String id,
        @JsonProperty("category")              EvalCategory category,
        @JsonProperty("direction")             Spec.TaskDirection direction,
        @JsonProperty("engine")                String engine,
        @JsonProperty("nl_input")              String nlInput,
        @JsonProperty("expected_spec")         Map<String, Object> expectedSpec,
        @JsonProperty("expected_code_patterns") List<String> expectedCodePatterns,
        @JsonProperty("fake_llm_responses")    List<String> fakeLlmResponses,
        @JsonProperty("max_turns")             int maxTurns,
        @JsonProperty("must_compile")          boolean mustCompile,
        @JsonProperty("must_dry_run")          boolean mustDryRun) {

    public EvalCase {
        if (fakeLlmResponses == null) fakeLlmResponses = List.of();
        if (expectedCodePatterns == null) expectedCodePatterns = List.of();
    }
}
