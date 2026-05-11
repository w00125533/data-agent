package com.wireless.agent.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalResultTest {

    @Test
    void shouldComputeOverallScore() {
        var r = new EvalResult("eval-001", EvalCategory.COVERAGE,
                0.9, true, true, 3, true);
        assertThat(r.overallScore()).isGreaterThan(0.6);
        assertThat(r.passed()).isTrue();
    }

    @Test
    void shouldFailWhenSpecBelowThreshold() {
        var r = new EvalResult("eval-002", EvalCategory.MOBILITY,
                0.4, true, true, 3, true);
        assertThat(r.passed()).isFalse();
    }

    @Test
    void shouldComputeRegressionDelta() {
        var baseline = new EvalResult("eval-001", EvalCategory.COVERAGE,
                0.9, true, false, 3, false);
        var current = new EvalResult("eval-001", EvalCategory.COVERAGE,
                0.7, true, false, 3, false);
        var delta = current.overallScore() - baseline.overallScore();
        assertThat(delta).isNegative();
    }

    @Test
    void shouldBuildReportFromResults() {
        var results = List.of(
                new EvalResult("eval-001", EvalCategory.COVERAGE, 0.9, true, false, 3, false),
                new EvalResult("eval-002", EvalCategory.MOBILITY, 0.5, false, false, 5, false),
                new EvalResult("eval-003", EvalCategory.QOE, 0.7, true, false, 4, false));

        var report = new EvalReport(results, "spark_sql");
        assertThat(report.totalCases()).isEqualTo(3);
        assertThat(report.passedCases()).isEqualTo(2);
        assertThat(report.failedCases()).isEqualTo(1);
        assertThat(report.passRate()).isGreaterThan(0.6);
        assertThat(report.byCategory()).containsKeys(EvalCategory.COVERAGE, EvalCategory.MOBILITY, EvalCategory.QOE);
    }
}
