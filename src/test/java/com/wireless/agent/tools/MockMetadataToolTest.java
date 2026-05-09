package com.wireless.agent.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockMetadataToolTest {

    private final MockMetadataTool tool = new MockMetadataTool();

    @Test
    void shouldLookupKnownTableByExactName() {
        var r = tool.lookup("dw.mr_5g_15min");
        assertThat(r.success()).isTrue();
        @SuppressWarnings("unchecked")
        var schema = (java.util.List<?>) ((java.util.Map<?, ?>) r.data()).get("schema");
        assertThat(schema).hasSize(7);
    }

    @Test
    void shouldReturnCandidatesForUnknownTable() {
        var r = tool.lookup("unknown_table");
        assertThat(r.success()).isFalse();
        @SuppressWarnings("unchecked")
        var candidates = (java.util.List<?>) ((java.util.Map<?, ?>) r.data()).get("candidates");
        assertThat(candidates).isNotEmpty();
    }

    @Test
    void shouldSearchByKeyword() {
        var r = tool.lookup("MR");
        @SuppressWarnings("unchecked")
        var candidates = (java.util.List<?>) ((java.util.Map<?, ?>) r.data()).get("candidates");
        assertThat(candidates.stream().anyMatch(c -> c.toString().toLowerCase().contains("mr"))).isTrue();
    }

    @Test
    void shouldHaveAtLeastFourKnownTables() {
        assertThat(MockMetadataTool.KNOWN_TABLES).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void shouldHaveCorrectToolName() {
        assertThat(tool.name()).isEqualTo("metadata");
    }
}
