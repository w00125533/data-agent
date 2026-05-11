package com.wireless.agent.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionTrace {

    @JsonProperty("session_id")    public String sessionId;
    @JsonProperty("user")          public String user;
    @JsonProperty("started_at")    public Instant startedAt;
    @JsonProperty("ended_at")      public Instant endedAt;
    @JsonProperty("final_state")   public String finalState;
    @JsonProperty("events")        public List<Object> events = new ArrayList<>();

    public static class ConversationEvent {
        @JsonProperty("type")      public final String type = "conversation";
        @JsonProperty("turn_id")   public int turnId;
        @JsonProperty("user_msg")  public String userMsg;
        @JsonProperty("agent_msg") public String agentMsg;
    }

    public static class SpecSnapshotEvent {
        @JsonProperty("type")      public final String type = "spec_snapshot";
        @JsonProperty("turn_id")   public int turnId;
        @JsonProperty("spec_json") public String specJson;
    }

    public static class ToolCallEvent {
        @JsonProperty("type")      public final String type = "tool_call";
        @JsonProperty("tool")      public String tool;
        @JsonProperty("input")     public String input;
        @JsonProperty("latency_ms") public long latencyMs;
        @JsonProperty("status")    public String status;
        @JsonProperty("error")     public String error;
    }

    public static class LlmCallEvent {
        @JsonProperty("type")              public final String type = "llm_call";
        @JsonProperty("model")             public String model;
        @JsonProperty("call_index")        public int callIndex;
        @JsonProperty("prompt_tokens")     public int promptTokens;
        @JsonProperty("completion_tokens") public int completionTokens;
        @JsonProperty("latency_ms")        public long latencyMs;
    }

    public static class ArtifactEvent {
        @JsonProperty("type")      public final String type = "artifact";
        @JsonProperty("code_hash") public String codeHash;
        @JsonProperty("pr_url")    public String prUrl;
        @JsonProperty("ticket_id") public String ticketId;
    }

    public SessionTrace() {}

    public SessionTrace(String sessionId, String user) {
        this.sessionId = sessionId;
        this.user = user;
        this.startedAt = Instant.now();
    }
}
