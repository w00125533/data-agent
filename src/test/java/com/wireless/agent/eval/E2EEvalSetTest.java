package com.wireless.agent.eval;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the full E2E eval set and generates a report. */
class E2EEvalSetTest {

    private static List<EvalCase> allCases;
    private static EvalReport report;

    @BeforeAll
    static void runAllEvals(@TempDir Path tmpDir) throws Exception {
        var runner = new EvalRunner("thrift://nonexistent:9999");
        allCases = new ArrayList<>();

        // Load all eval case files
        var evalDir = Path.of("src/test/resources/eval");
        if (Files.exists(evalDir)) {
            try (var files = Files.list(evalDir)) {
                for (var file : files.sorted().toList()) {
                    if (file.toString().endsWith(".json")) {
                        try {
                            allCases.addAll(EvalRunner.loadCases(file));
                        } catch (Exception e) {
                            System.err.println("Failed to load " + file + ": " + e.getMessage());
                        }
                    }
                }
            }
        }

        report = runner.runSuite(allCases, "spark_sql");

        // Write report
        var reportFile = tmpDir.resolve("eval-report.json");
        report.writeTo(reportFile);
        System.out.println("[E2E Eval] Report written to: " + reportFile);
        System.out.println("[E2E Eval] Cases: " + report.totalCases()
                + " | Passed: " + report.passedCases()
                + " | Failed: " + report.failedCases()
                + " | Avg Score: " + String.format("%.2f", report.averageScore() * 100) + "%");
    }

    @Test
    void shouldLoadAllEvalCases() {
        assertThat(allCases).isNotEmpty();
        assertThat(allCases.size()).isBetween(30, 50);
    }

    @Test
    void shouldAchieveMinimumPassRate() {
        assertThat(report.passRate())
                .withFailMessage("Pass rate %.1f%% is below 30%% minimum",
                        report.passRate() * 100)
                .isGreaterThan(0.3);
    }

    @Test
    void shouldAchieveMinimumAverageScore() {
        assertThat(report.averageScore())
                .withFailMessage("Average score %.2f is below 0.4 minimum",
                        report.averageScore())
                .isGreaterThan(0.4);
    }

    @Test
    void shouldCoverAllSevenCategories() {
        assertThat(report.byCategory().keySet())
                .contains(EvalCategory.COVERAGE, EvalCategory.MOBILITY,
                        EvalCategory.ACCESSIBILITY, EvalCategory.RETAINABILITY,
                        EvalCategory.QOE, EvalCategory.MULTI_SOURCE,
                        EvalCategory.STREAM_BATCH, EvalCategory.REVERSE);
    }

    @Test
    void shouldHaveReportWithAllCases() {
        assertThat(report.results()).hasSameSizeAs(allCases);
        for (var r : report.results()) {
            assertThat(r.overallScore()).isBetween(0.0, 1.0);
        }
    }

    @Test
    void shouldReportPassRatePerCategory() {
        for (var entry : report.byCategory().entrySet()) {
            var catScore = entry.getValue();
            assertThat(catScore)
                    .withFailMessage("Category %s average score %.2f is below 0.2",
                            entry.getKey(), catScore)
                    .isGreaterThan(0.2);
        }
    }
}
