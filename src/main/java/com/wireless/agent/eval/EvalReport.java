package com.wireless.agent.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/** Aggregated evaluation report across all cases. */
public record EvalReport(
        @JsonProperty("total_cases")      int totalCases,
        @JsonProperty("passed_cases")     int passedCases,
        @JsonProperty("failed_cases")     int failedCases,
        @JsonProperty("pass_rate")        double passRate,
        @JsonProperty("average_score")    double averageScore,
        @JsonProperty("engine")           String engine,
        @JsonProperty("by_category")      Map<EvalCategory, Double> byCategory,
        @JsonProperty("results")          List<EvalResult> results,
        @JsonProperty("generated_at")     String generatedAt) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    public EvalReport(List<EvalResult> results, String engine) {
        this(results.size(),
             (int) results.stream().filter(EvalResult::passed).count(),
             (int) results.stream().filter(r -> !r.passed()).count(),
             results.isEmpty() ? 0.0 : (double) results.stream().filter(EvalResult::passed).count() / results.size(),
             results.isEmpty() ? 0.0 : results.stream().mapToDouble(EvalResult::overallScore).average().orElse(0.0),
             engine,
             results.stream().collect(Collectors.groupingBy(
                     EvalResult::category,
                     Collectors.averagingDouble(EvalResult::overallScore))),
             results,
             java.time.LocalDateTime.now().toString());
    }

    /** Write report as JSON to file. */
    public void writeTo(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), this);
    }

    /** Load a previous baseline report for comparison. */
    public static EvalReport loadFrom(Path file) throws IOException {
        return MAPPER.readValue(file.toFile(), EvalReport.class);
    }

    /** Compute regression delta vs a baseline report. */
    public double regressionDelta(EvalReport baseline) {
        return this.averageScore - baseline.averageScore;
    }
}
