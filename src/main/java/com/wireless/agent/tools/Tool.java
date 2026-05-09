package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;

/** Stateless tool. All state is in Spec, passed as input. */
public interface Tool {

    String name();
    String description();

    /** Execute the tool synchronously. */
    ToolResult run(Spec spec);
}
