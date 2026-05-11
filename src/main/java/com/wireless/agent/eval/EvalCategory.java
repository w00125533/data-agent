package com.wireless.agent.eval;

import com.fasterxml.jackson.annotation.JsonValue;

/** Seven domain categories for wireless network perception evaluation. */
public enum EvalCategory {
    COVERAGE("coverage"),
    MOBILITY("mobility"),
    ACCESSIBILITY("accessibility"),
    RETAINABILITY("retainability"),
    QOE("qoe"),
    MULTI_SOURCE("multi_source"),
    STREAM_BATCH("stream_batch"),
    REVERSE("reverse");

    private final String value;
    EvalCategory(String v) { value = v; }

    @JsonValue
    public String value() { return value; }
}
