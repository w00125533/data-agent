package com.wireless.agent.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Records session trace events and serializes to JSON on finish. Thread-safe. */
public class TraceRecorder {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .registerModule(new JavaTimeModule());

    private final Path outputDir;
    private final SessionTrace trace;
    private int llmCallCounter;
    private final Object lock = new Object();

    public TraceRecorder(Path outputDir, String sessionId, String user) {
        this.outputDir = outputDir;
        this.trace = new SessionTrace(sessionId, user);
    }

    public String sessionId() { return trace.sessionId; }

    /** Record a conversation turn. */
    public void recordConversation(int turnId, String userMsg, String agentMsg) {
        synchronized (lock) {
            var e = new SessionTrace.ConversationEvent();
            e.turnId = turnId;
            e.userMsg = userMsg;
            e.agentMsg = truncate(agentMsg, 2000);
            trace.events.add(e);
        }
    }

    /** Record a spec snapshot at a turn. */
    public void recordSpecSnapshot(int turnId, String specJson) {
        synchronized (lock) {
            var e = new SessionTrace.SpecSnapshotEvent();
            e.turnId = turnId;
            e.specJson = specJson;
            trace.events.add(e);
        }
    }

    /** Record a tool invocation. */
    public void recordToolCall(String tool, String input, long latencyMs,
                               String status, String error) {
        synchronized (lock) {
            var e = new SessionTrace.ToolCallEvent();
            e.tool = tool;
            e.input = truncate(input, 500);
            e.latencyMs = latencyMs;
            e.status = status;
            e.error = error;
            trace.events.add(e);
        }
    }

    /** Record an LLM call. */
    public void recordLlmCall(String model, int callIndex, int promptTokens,
                              int completionTokens, long latencyMs) {
        synchronized (lock) {
            var e = new SessionTrace.LlmCallEvent();
            e.model = model;
            e.callIndex = callIndex;
            e.promptTokens = promptTokens;
            e.completionTokens = completionTokens;
            e.latencyMs = latencyMs;
            trace.events.add(e);
            llmCallCounter++;
        }
    }

    /** Record final artifacts. */
    public void recordFinalArtifact(String code, String codeHash,
                                     String prUrl, String ticketId) {
        synchronized (lock) {
            var e = new SessionTrace.ArtifactEvent();
            e.codeHash = codeHash;
            e.prUrl = prUrl;
            e.ticketId = ticketId;
            trace.events.add(e);
        }
    }

    /** Finish the trace and write to disk. */
    public void finish(String finalState) {
        synchronized (lock) {
            trace.finalState = finalState;
            trace.endedAt = Instant.now();
            try {
                Files.createDirectories(outputDir);
                MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValue(outputDir.resolve(trace.sessionId + ".json").toFile(), trace);
            } catch (IOException e) {
                System.err.println("[TraceRecorder] Failed to write trace: " + e.getMessage());
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
