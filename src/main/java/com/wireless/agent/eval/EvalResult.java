package com.wireless.agent.eval;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Scoring result for a single eval case. */
public record EvalResult(
        @JsonProperty("case_id")          String caseId,
        @JsonProperty("category")         EvalCategory category,
        @JsonProperty("spec_accuracy")    double specAccuracy,
        @JsonProperty("code_compiles")    boolean codeCompiles,
        @JsonProperty("dry_run_passed")   boolean dryRunPassed,
        @JsonProperty("turns_used")       int turnsUsed,
        @JsonProperty("must_dry_run")     boolean mustDryRun) {

    private static final double SPEC_THRESHOLD = 0.5;

    /** Overall score: weighted average of spec/code/dryRun/turn dimensions. */
    public double overallScore() {
        double codeScore = codeCompiles ? 1.0 : 0.0;
        double dryRunScore;
        if (!mustDryRun) {
            dryRunScore = 1.0;
        } else {
            dryRunScore = dryRunPassed ? 1.0 : 0.0;
        }
        double turnScore = 1.0 - Math.min((double) turnsUsed / 5.0, 1.0);
        return (specAccuracy + codeScore + dryRunScore + turnScore) / 4.0;
    }

    /** Whether this case passes the quality gate. */
    public boolean passed() {
        return specAccuracy >= SPEC_THRESHOLD && codeCompiles && (dryRunPassed || !mustDryRun);
    }
}
