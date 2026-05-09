package com.wireless.agent.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProfilerToolTest {

    @Test
    void shouldHaveCorrectToolName() {
        var runner = new DockerCommandRunner();
        var tool = new ProfilerTool(runner, "da-spark-master");
        assertThat(tool.name()).isEqualTo("profiler");
    }

    @Test
    void shouldBuildRowCountQuery() {
        var sql = ProfilerTool.buildRowCountQuery("dw.mr_5g_15min");
        assertThat(sql).contains("COUNT(*)");
        assertThat(sql).contains("dw.mr_5g_15min");
    }

    @Test
    void shouldBuildNullCheckQuery() {
        var sql = ProfilerTool.buildNullCheckQuery("dw.mr_5g_15min", "rsrp_avg");
        assertThat(sql).contains("rsrp_avg IS NULL");
        assertThat(sql).contains("COUNT(*)");
    }

    @Test
    void shouldParseCountResult() {
        var stdout = "100\n42\n";
        var result = ProfilerTool.parseCountResult(stdout);
        assertThat(result).containsEntry("total", "100");
    }

    @Test
    void shouldGracefullyHandleEmptyColumns() {
        var sql = ProfilerTool.buildProfileQuery("empty_table", List.of(), 10);
        assertThat(sql).contains("empty_table");
        assertThat(sql).contains("SELECT *");
    }
}
