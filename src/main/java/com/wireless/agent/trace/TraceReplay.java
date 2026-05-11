package com.wireless.agent.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Loads and replays session traces from disk. */
public class TraceReplay {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .registerModule(new JavaTimeModule());

    /** Load a single trace by session ID. */
    public SessionTrace load(Path traceDir, String sessionId) throws IOException {
        var file = traceDir.resolve(sessionId + ".json");
        if (!Files.exists(file)) {
            throw new IOException("Trace file not found: " + file);
        }
        return MAPPER.readValue(file.toFile(), SessionTrace.class);
    }

    /** List all trace session IDs in the trace directory. */
    public List<String> listSessions(Path traceDir) throws IOException {
        if (!Files.exists(traceDir)) return List.of();
        try (var s = Files.list(traceDir)) {
            return s.filter(p -> p.toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString().replace(".json", ""))
                    .toList();
        }
    }

    /** Get a summary of all recorded traces. */
    public List<Map<String, Object>> listSummaries(Path traceDir) throws IOException {
        if (!Files.exists(traceDir)) return List.of();
        try (var s = Files.list(traceDir)) {
            return s.filter(p -> p.toString().endsWith(".json"))
                    .map(p -> {
                        try {
                            var trace = MAPPER.readValue(p.toFile(), SessionTrace.class);
                            return Map.<String, Object>of(
                                "session_id", trace.sessionId != null ? trace.sessionId : "",
                                "user", trace.user != null ? trace.user : "",
                                "started_at", trace.startedAt != null ? trace.startedAt.toString() : "",
                                "final_state", trace.finalState != null ? trace.finalState : "",
                                "event_count", trace.events.size()
                            );
                        } catch (IOException e) {
                            return Map.<String, Object>of(
                                "file", p.getFileName().toString(),
                                "error", e.getMessage()
                            );
                        }
                    })
                    .toList();
        }
    }

    /** Count events by type in a trace. */
    public Map<String, Long> eventTypeCounts(SessionTrace trace) {
        return trace.events.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getClass().getSimpleName(),
                        Collectors.counting()));
    }
}
