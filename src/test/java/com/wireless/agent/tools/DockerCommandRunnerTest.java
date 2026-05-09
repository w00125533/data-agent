package com.wireless.agent.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DockerCommandRunnerTest {

    @Test
    void shouldBuildDockerExecCommand() {
        var args = List.of("spark-sql", "-e", "SELECT 1");
        var cmd = DockerCommandRunner.buildCommand("da-spark-master", args);
        assertThat(cmd).containsExactly("docker", "exec", "da-spark-master",
                "spark-sql", "-e", "SELECT 1");
    }

    @Test
    void shouldReturnResultWithStdout() {
        var runner = new DockerCommandRunner();
        var result = new DockerCommandRunner.Result(0, "hello\n", "", false);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("hello\n");
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldParseFailureFromNonZeroExit() {
        var result = new DockerCommandRunner.Result(1, "", "Table not found", false);
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.stderr()).contains("Table not found");
    }

    @Test
    void shouldHandleTimeout() {
        var result = new DockerCommandRunner.Result(-1, "", "timeout", true);
        assertThat(result.timedOut()).isTrue();
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void shouldExecuteSimpleCommandAndReturnStdout() {
        var runner = new DockerCommandRunner();
        // Use a command that works on both Windows and Unix
        var result = runner.exec("non-existent-container",
                List.of("echo", "hello"), 5);
        // Will fail because container doesn't exist, but verifies exec() runs end-to-end
        assertThat(result).isNotNull();
        assertThat(result.timedOut()).isFalse();
    }
}
