package com.wireless.agent.trace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TraceRecorderTest {

    @Test
    void shouldRecordConversationEvent(@TempDir Path tmpDir) throws Exception {
        var sessionId = "test-session-1";
        var recorder = new TraceRecorder(tmpDir, sessionId, "test-user");

        recorder.recordConversation(1, "做个弱覆盖统计", "请补充数据源信息");
        recorder.finish("codegen_done");

        var traceFile = tmpDir.resolve(sessionId + ".json");
        assertThat(Files.exists(traceFile)).isTrue();

        var content = Files.readString(traceFile);
        assertThat(content).contains("test-session-1");
        assertThat(content).contains("弱覆盖统计");
        assertThat(content).contains("codegen_done");
    }

    @Test
    void shouldRecordToolCallEvent(@TempDir Path tmpDir) throws Exception {
        var sessionId = "test-session-2";
        var recorder = new TraceRecorder(tmpDir, sessionId, "test-user");

        recorder.recordToolCall("metadata", "lookup_dw.mr_5g_15min",
                150L, "success", null);
        recorder.finish("dry_run_ok");

        var content = Files.readString(tmpDir.resolve(sessionId + ".json"));
        assertThat(content).contains("metadata");
        assertThat(content).contains("150");
        assertThat(content).contains("success");
    }

    @Test
    void shouldRecordSpecSnapshot(@TempDir Path tmpDir) throws Exception {
        var sessionId = "test-session-3";
        var recorder = new TraceRecorder(tmpDir, sessionId, "test-user");

        recorder.recordSpecSnapshot(1, "{\"target\":{\"name\":\"弱覆盖统计\"}}");
        recorder.finish("codegen_done");

        var content = Files.readString(tmpDir.resolve(sessionId + ".json"));
        assertThat(content).contains("弱覆盖统计");
    }

    @Test
    void shouldRecordLlmCallEvent(@TempDir Path tmpDir) throws Exception {
        var sessionId = "test-session-4";
        var recorder = new TraceRecorder(tmpDir, sessionId, "test-user");

        recorder.recordLlmCall("deepseek-chat", 1, 1200, 450, 800L);
        recorder.finish("codegen_done");

        var content = Files.readString(tmpDir.resolve(sessionId + ".json"));
        assertThat(content).contains("deepseek-chat");
        assertThat(content).contains("1200");
    }

    @Test
    void shouldRecordFinalArtifacts(@TempDir Path tmpDir) throws Exception {
        var sessionId = "test-session-5";
        var recorder = new TraceRecorder(tmpDir, sessionId, "test-user");

        recorder.recordFinalArtifact("SELECT * FROM test;",
                "abc123", "pr-url-123", "TICKET-456");
        recorder.finish("codegen_done");

        var content = Files.readString(tmpDir.resolve(sessionId + ".json"));
        assertThat(content).contains("pr-url-123");
        assertThat(content).contains("TICKET-456");
        assertThat(content).contains("abc123");
    }

    @Test
    void shouldSetTimestamps(@TempDir Path tmpDir) throws Exception {
        var sessionId = "test-session-6";
        var recorder = new TraceRecorder(tmpDir, sessionId, "test-user");
        recorder.recordConversation(1, "hello", "hi");
        recorder.finish("codegen_done");

        var content = Files.readString(tmpDir.resolve(sessionId + ".json"));
        assertThat(content).contains("started_at");
        assertThat(content).contains("ended_at");
    }
}
