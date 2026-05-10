package com.wireless.agent.knowledge;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainKnowledgeBaseTest {

    private static DomainKnowledgeBase kb;

    @BeforeAll
    static void setUp() {
        kb = new DomainKnowledgeBase();
    }

    @Test
    void shouldLoadFromClasspathResource() {
        var all = kb.all();
        assertThat(all).isNotEmpty();
        assertThat(all.size()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void shouldLookupByKpiFamily() {
        var coverageKpis = kb.lookupByKpiFamily("coverage");
        assertThat(coverageKpis).isNotEmpty();
        assertThat(coverageKpis.stream().map(KnowledgeEntry::name))
                .contains("弱覆盖小区");
    }

    @Test
    void shouldLookupByKeyword() {
        var results = kb.search("弱覆盖");
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).name()).contains("弱覆盖");
    }

    @Test
    void shouldMatchAliasesForSearch() {
        var results = kb.search("poor coverage");
        assertThat(results).isNotEmpty();
        assertThat(results.stream().anyMatch(e -> e.name().equals("弱覆盖小区"))).isTrue();
    }

    @Test
    void shouldLookupByCategory() {
        var methods = kb.lookupByCategory("methodology");
        assertThat(methods).isNotEmpty();
        assertThat(methods.stream().map(KnowledgeEntry::name))
                .contains("弱覆盖小区识别标准流程 (v2口径)");
    }

    @Test
    void shouldFindRelatedTables() {
        var entries = kb.search("cell_id");
        assertThat(entries).isNotEmpty();
    }

    @Test
    void shouldReturnEmptyForNoMatch() {
        var results = kb.search("zzz_no_such_term_xyz");
        assertThat(results).isEmpty();
    }

    @Test
    void shouldGetTerminologyDefinitions() {
        var terms = kb.lookupByCategory("terminology");
        assertThat(terms).isNotEmpty();
        assertThat(terms.stream().map(KnowledgeEntry::name))
                .contains("RAT 制式术语", "网络粒度术语", "时间粒度术语");
    }

    @Test
    void shouldGetJoinPatterns() {
        var patterns = kb.lookupByCategory("join_pattern");
        assertThat(patterns).isNotEmpty();
        assertThat(patterns.get(0).formula()).isNotNull();
    }

    @Test
    void shouldSummarizeAllForLlmWhenNullKpiFamily() {
        var summaries = kb.summarizeForLlm(null);
        assertThat(summaries).isNotEmpty();
        assertThat(summaries.size()).isEqualTo(kb.all().size());
    }

    @Test
    void shouldSummarizeFilteredForLlm() {
        var summaries = kb.summarizeForLlm("coverage");
        assertThat(summaries).isNotEmpty();
        assertThat(summaries.size()).isLessThan(kb.all().size());
    }

    @Test
    void shouldBuildPromptContextForSpecificKpiFamily() {
        var ctx = kb.buildPromptContext("mobility");
        assertThat(ctx).contains("## 无线评估方法论字典");
        assertThat(ctx).contains("切换成功率");
        assertThat(ctx).contains("切换失败根因定位流程");
    }

    @Test
    void shouldBuildPromptContextForNullKpiFamily() {
        var ctx = kb.buildPromptContext(null);
        assertThat(ctx).contains("## 无线评估方法论字典");
        assertThat(ctx).contains("KPI 定义");
        assertThat(ctx).contains("数据源说明");
    }

    @Test
    void shouldBuildPromptContextIncludeSourceDesc() {
        var ctx = kb.buildPromptContext("coverage");
        assertThat(ctx).contains("数据源说明");
        assertThat(ctx).contains("5G MR 15分钟 KPI 表");
    }

    @Test
    void shouldReturnEmptyForNullLookupByKpiFamily() {
        assertThat(kb.lookupByKpiFamily(null)).isEmpty();
        assertThat(kb.lookupByKpiFamily("")).isEmpty();
    }

    @Test
    void shouldReturnEmptyForNullSearch() {
        assertThat(kb.search(null)).isEmpty();
        assertThat(kb.search("")).isEmpty();
    }

    @Test
    void shouldReturnImmutableListFromAll() {
        var result = kb.all();
        assertThatThrownBy(() -> result.clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
