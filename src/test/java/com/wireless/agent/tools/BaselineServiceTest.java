package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineServiceTest {

    @Test
    void shouldHaveCorrectToolName() {
        var svc = new BaselineService(new DockerCommandRunner(), "da-spark-master");
        assertThat(svc.name()).isEqualTo("baseline");
    }

    @Test
    void shouldBuildBaselineTableName() {
        var baselineName = BaselineService.baselineTableName("dw.mr_5g_15min");
        assertThat(baselineName).isEqualTo("baseline.dw__mr_5g_15min");
    }

    @Test
    void shouldBuildBaselineTableNameReplaceDots() {
        assertThat(BaselineService.baselineTableName("dim.engineering_param"))
                .isEqualTo("baseline.dim__engineering_param");
    }

    @Test
    void shouldBuildCreateBaselineSql() {
        var sql = BaselineService.buildCreateBaselineSql(
                "dw.mr_5g_15min", "baseline.dw__mr_5g_15min", 1.0);
        assertThat(sql).contains("CREATE OR REPLACE TABLE");
        assertThat(sql).contains("baseline.dw__mr_5g_15min");
        assertThat(sql).contains("rand() < 0.01");
        assertThat(sql).contains("dw.mr_5g_15min");
    }

    @Test
    void shouldBuildCreateBaselineSqlWithCustomSampleRate() {
        var sql = BaselineService.buildCreateBaselineSql(
                "src.tbl", "baseline.src__tbl", 5.0);
        assertThat(sql).contains("rand() < 0.05");
    }

    @Test
    void shouldBuildCountBaselineSql() {
        var sql = BaselineService.buildCountSql("baseline.dw__mr_5g_15min");
        assertThat(sql).isEqualTo("SELECT COUNT(*) FROM baseline.dw__mr_5g_15min;");
    }

    @Test
    void shouldTrackBaselineMetadata() {
        var svc = new BaselineService(new DockerCommandRunner(), "da-spark-master");
        svc.recordBaseline("dw.mr_5g_15min", "baseline.dw__mr_5g_15min", 1,
                Map.of("row_count", 1000));
        var meta = svc.getBaselineMeta("dw.mr_5g_15min");
        assertThat(meta).isPresent();
        assertThat(meta.get()).containsEntry("source_table", "dw.mr_5g_15min");
        assertThat(meta.get()).containsEntry("baseline_table", "baseline.dw__mr_5g_15min");
    }

    @Test
    void shouldReturnEmptyForUnknownBaseline() {
        var svc = new BaselineService(new DockerCommandRunner(), "da-spark-master");
        var meta = svc.getBaselineMeta("no_such_table");
        assertThat(meta).isEmpty();
    }

    @Test
    void shouldImplementToolInterface() {
        var svc = new BaselineService(new DockerCommandRunner(), "da-spark-master");
        assertThat(svc).isInstanceOf(Tool.class);
    }

    @Test
    void shouldReturnTrueForExistingBaseline() {
        var svc = new BaselineService(new DockerCommandRunner(), "da-spark-master");
        svc.recordBaseline("tbl", "baseline.tbl", 1, Map.of("row_count", 100));
        assertThat(svc.hasBaseline("tbl")).isTrue();
    }

    @Test
    void shouldReturnFalseForMissingBaseline() {
        var svc = new BaselineService(new DockerCommandRunner(), "da-spark-master");
        assertThat(svc.hasBaseline("no_tbl")).isFalse();
    }

    @Test
    void shouldResolveToBaselineTableWhenExists() {
        var svc = new BaselineService(new DockerCommandRunner(), "da-spark-master");
        svc.recordBaseline("dw.mr_5g_15min", "baseline.dw__mr_5g_15min", 1, Map.of());
        assertThat(svc.resolveTable("dw.mr_5g_15min")).isEqualTo("baseline.dw__mr_5g_15min");
    }

    @Test
    void shouldResolveToOriginalTableWhenNoBaseline() {
        var svc = new BaselineService(new DockerCommandRunner(), "da-spark-master");
        assertThat(svc.resolveTable("dw.mr_5g_15min")).isEqualTo("dw.mr_5g_15min");
    }

    @Test
    void shouldRunWithEmptySources() {
        var svc = new BaselineService(new DockerCommandRunner(), "da-spark-master");
        var spec = new Spec(Spec.TaskDirection.FORWARD_ETL);
        var result = svc.run(spec);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("No sources");
    }
}
