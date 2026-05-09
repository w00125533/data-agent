package com.wireless.agent.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HmsMetadataToolTest {

    @Test
    void shouldHaveCorrectToolName() {
        var tool = new HmsMetadataTool("thrift://localhost:9083");
        assertThat(tool.name()).isEqualTo("metadata");
    }

    @Test
    void shouldFallbackToMockWhenHmsUnreachable() {
        // When HMS is unreachable, fallback to mock data
        var tool = new HmsMetadataTool("thrift://nonexistent:9999");
        var result = tool.lookup("dw.mr_5g_15min");
        // Should fallback to mock
        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        var schema = (List<?>) ((Map<?, ?>) result.data()).get("schema");
        assertThat(schema).isNotNull();
    }

    @Test
    void shouldSearchByFallbackKeywordWhenHmsUnreachable() {
        var tool = new HmsMetadataTool("thrift://nonexistent:9999");
        var result = tool.lookup("MR");
        @SuppressWarnings("unchecked")
        var candidates = (List<?>) ((Map<?, ?>) result.data()).get("candidates");
        assertThat(candidates).isNotEmpty();
    }

    @Test
    void shouldImplementToolInterface() {
        var tool = new HmsMetadataTool("thrift://localhost:9083");
        assertThat(tool).isInstanceOf(Tool.class);
    }
}
