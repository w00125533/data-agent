package com.wireless.agent.eval;

import com.wireless.agent.core.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalRunnerTest {

    @Test
    void shouldScoreSingleEvalCase() {
        var evalCase = new EvalCase("test-001", EvalCategory.COVERAGE,
                Spec.TaskDirection.FORWARD_ETL, "spark_sql",
                "给我弱覆盖小区统计",
                Map.of("target_name", "weak_cov", "kpi_family", "coverage",
                        "ne_grain", "district", "sources", List.of("dw.mr_5g_15min")),
                List.of("SELECT", "rsrp"),
                List.of(), 5, true, false);

        var runner = new EvalRunner(null);
        var result = runner.evaluate(evalCase);

        assertThat(result.caseId()).isEqualTo("test-001");
        assertThat(result.category()).isEqualTo(EvalCategory.COVERAGE);
        assertThat(result.specAccuracy()).isGreaterThan(0.0);
        assertThat(result.overallScore()).isGreaterThan(0.0);
    }

    @Test
    void shouldRunFullSuite() {
        var cases = List.of(
                new EvalCase("eval-cov-001", EvalCategory.COVERAGE,
                        Spec.TaskDirection.FORWARD_ETL, "spark_sql",
                        "给我近30天每个区县5G弱覆盖小区清单",
                        Map.of("target_name", "weak_cov_by_district", "kpi_family", "coverage",
                                "ne_grain", "district", "time_grain", "day",
                                "sources", List.of("dw.mr_5g_15min")),
                        List.of("SELECT", "rsrp"),
                        List.of(), 5, true, false),
                new EvalCase("eval-mob-001", EvalCategory.MOBILITY,
                        Spec.TaskDirection.FORWARD_ETL, "flink_sql",
                        "实时监控最近1小时切换失败次数",
                        Map.of("target_name", "ho_failure", "kpi_family", "mobility",
                                "ne_grain", "cell", "time_grain", "hour",
                                "sources", List.of("signaling_events")),
                        List.of("SELECT", "handover"),
                        List.of(), 3, true, false));

        var runner = new EvalRunner(null);
        var report = runner.runSuite(cases, "spark_sql");

        assertThat(report.totalCases()).isEqualTo(2);
        assertThat(report.passedCases()).isGreaterThanOrEqualTo(0);
        assertThat(report.averageScore()).isGreaterThan(0.0);
    }

    @Test
    void shouldLoadCasesFromJsonFile() throws Exception {
        var tmpFile = java.nio.file.Files.createTempFile("eval-cases", ".json");
        try {
            java.nio.file.Files.writeString(tmpFile, """
            [
              {
                "id": "eval-001",
                "category": "coverage",
                "direction": "forward_etl",
                "engine": "spark_sql",
                "nl_input": "给我弱覆盖数据",
                "expected_spec": {
                  "target_name": "weak_cov",
                  "kpi_family": "coverage",
                  "ne_grain": "district",
                  "sources": ["dw.mr_5g_15min"]
                },
                "expected_code_patterns": ["SELECT"],
                "max_turns": 5,
                "must_compile": true,
                "must_dry_run": false
              }
            ]""");
            var cases = EvalRunner.loadCases(tmpFile);
            assertThat(cases).hasSize(1);
            assertThat(cases.get(0).id()).isEqualTo("eval-001");
        } finally {
            java.nio.file.Files.deleteIfExists(tmpFile);
        }
    }
}
