package com.wireless.agent.tools;

import java.util.Map;

public record ToolResult(boolean success, Object data, String error,
                         Map<String, Object> evidence) {

    public ToolResult(boolean success, Object data, String error) {
        this(success, data, error, Map.of());
    }

    public static ToolResult ok(Object data) {
        return new ToolResult(true, data, "", Map.of());
    }

    public static ToolResult ok(Object data, Map<String, Object> evidence) {
        return new ToolResult(true, data, "", evidence);
    }

    public static ToolResult fail(String error) {
        return new ToolResult(false, null, error, Map.of());
    }

    public static ToolResult fail(String error, Object data) {
        return new ToolResult(false, data, error, Map.of());
    }
}
