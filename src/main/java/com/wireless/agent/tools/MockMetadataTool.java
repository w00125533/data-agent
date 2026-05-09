package com.wireless.agent.tools;

import com.wireless.agent.core.Spec;

import java.util.*;
import java.util.stream.Collectors;

public class MockMetadataTool implements Tool {

    public static final Map<String, Map<String, Object>> KNOWN_TABLES = buildKnownTables();

    private static Map<String, Map<String, Object>> buildKnownTables() {
        var tables = new LinkedHashMap<String, Map<String, Object>>();

        tables.put("dw.mr_5g_15min", Map.of(
            "catalog", "hive",
            "schema", List.of(
                Map.of("name", "cell_id", "type", "STRING", "semantic", "小区ID"),
                Map.of("name", "ts_15min", "type", "TIMESTAMP", "semantic", "15分钟粒度时间戳"),
                Map.of("name", "rsrp_avg", "type", "DOUBLE", "semantic", "平均RSRP (dBm)"),
                Map.of("name", "rsrq_avg", "type", "DOUBLE", "semantic", "平均RSRQ (dB)"),
                Map.of("name", "sinr_avg", "type", "DOUBLE", "semantic", "平均SINR (dB)"),
                Map.of("name", "sample_count", "type", "BIGINT", "semantic", "采样数"),
                Map.of("name", "weak_cov_ratio", "type", "DOUBLE", "semantic", "弱覆盖比例 RSRP<-110")
            ),
            "owner", "无线网络优化组",
            "description", "5G MR 15分钟小区级KPI",
            "grain", "(cell_id, ts_15min)"
        ));

        tables.put("dim.engineering_param", Map.of(
            "catalog", "hive",
            "schema", List.of(
                Map.of("name", "cell_id", "type", "STRING", "semantic", "小区ID"),
                Map.of("name", "site_id", "type", "STRING", "semantic", "站点ID"),
                Map.of("name", "district", "type", "STRING", "semantic", "区县"),
                Map.of("name", "longitude", "type", "DOUBLE", "semantic", "经度"),
                Map.of("name", "latitude", "type", "DOUBLE", "semantic", "纬度"),
                Map.of("name", "rat", "type", "STRING", "semantic", "制式 4G/5G_SA/5G_NSA"),
                Map.of("name", "azimuth", "type", "INT", "semantic", "方位角"),
                Map.of("name", "downtilt", "type", "INT", "semantic", "下倾角")
            ),
            "owner", "无线网络优化组",
            "description", "小区工参维表",
            "grain", "(cell_id)"
        ));

        tables.put("dw.kpi_pm_cell_hour", Map.of(
            "catalog", "hive",
            "schema", List.of(
                Map.of("name", "cell_id", "type", "STRING", "semantic", "小区ID"),
                Map.of("name", "ts_hour", "type", "TIMESTAMP", "semantic", "小时粒度时间戳"),
                Map.of("name", "rrc_setup_succ_rate", "type", "DOUBLE", "semantic", "RRC建立成功率"),
                Map.of("name", "erab_drop_rate", "type", "DOUBLE", "semantic", "E-RAB掉话率"),
                Map.of("name", "ho_succ_rate", "type", "DOUBLE", "semantic", "切换成功率")
            ),
            "owner", "无线网络优化组",
            "description", "小区小时级PM KPI",
            "grain", "(cell_id, ts_hour)"
        ));

        tables.put("kafka.signaling_events", Map.of(
            "catalog", "kafka",
            "schema", List.of(
                Map.of("name", "ts", "type", "STRING", "semantic", "事件时间 ISO8601"),
                Map.of("name", "event_type", "type", "STRING", "semantic", "事件类型 handover/access/..."),
                Map.of("name", "src_cell", "type", "STRING", "semantic", "源小区ID"),
                Map.of("name", "dst_cell", "type", "STRING", "semantic", "目标小区ID"),
                Map.of("name", "result", "type", "STRING", "semantic", "结果 success/failure"),
                Map.of("name", "cause", "type", "STRING", "semantic", "失败原因 normal/too_early/too_late/...")
            ),
            "owner", "无线网络优化组",
            "description", "切换信令事件流 (JSONL, Kafka)",
            "grain", "(ts, event_type, src_cell)"
        ));

        return Collections.unmodifiableMap(tables);
    }

    @Override
    public String name() { return "metadata"; }

    @Override
    public String description() { return "查询 HMS/字典: 返回表 schema、字段语义、责任人"; }

    @Override
    public ToolResult run(Spec spec) {
        return ToolResult.fail("Use lookup(tableName) instead of run()");
    }

    /** Lookup a table by exact name or fuzzy keyword search. */
    public ToolResult lookup(String search) {
        if (search == null || search.isBlank()) {
            return ToolResult.fail("No search term provided");
        }

        if (KNOWN_TABLES.containsKey(search)) {
            return new ToolResult(
                true, KNOWN_TABLES.get(search), "",
                Map.of("type", "schema_lookup", "source", search,
                       "findings", Map.of("found", true))
            );
        }

        var sLower = search.toLowerCase();
        var candidates = KNOWN_TABLES.keySet().stream()
            .filter(k -> k.toLowerCase().contains(sLower)
                      || KNOWN_TABLES.get(k).get("description").toString().toLowerCase().contains(sLower))
            .collect(Collectors.toList());

        if (!candidates.isEmpty()) {
            return ToolResult.fail("Ambiguous or partial match",
                Map.of("candidates", candidates, "keyword", search));
        }
        return ToolResult.fail("Table not found",
            Map.of("candidates", new ArrayList<>(KNOWN_TABLES.keySet())));
    }
}
