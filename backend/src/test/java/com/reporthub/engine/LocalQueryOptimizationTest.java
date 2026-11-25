package com.reporthub.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reporthub.domain.ReportDef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalQueryOptimizationTest {

    @Test
    void preFilterRootNarrowsRowsByRootMonthAndStatusWhileSkippingCalcFields() {
        ReportQueryEngine engine = new ReportQueryEngine(
                new ObjectMapper(), null, null, null, null, null, null, null, null, null, null);

        ReportDef report = new ReportDef();
        report.setCode("rpt_white_weight_diff");
        report.setRootDataset("ds_hang");
        report.setFieldsJson("""
                [
                  {"key":"work_date","label":"日期","from":"ds_hang.work_date"},
                  {"key":"work_status","label":"状态","from":"ds_hang.work_status"},
                  {"key":"white_weight","label":"白件重","from":"ds_hang.white_weight"},
                  {"key":"u9_complete_qty","label":"U9完工","from":"ds_u9c_complete.complete_qty"}
                ]
                """);
        report.setCalcsJson("""
                [
                  {"key":"diff_flag","label":"是否一致","kind":"condition","when":"white_weight > 0","then":"一致","else":"不一致"}
                ]
                """);

        Dataset root = new Dataset("ds_hang");
        root.addRow(row("ds_hang", "2026-10-15", "12", 100.0));
        root.addRow(row("ds_hang", "2026-11-05", "12", 200.0));
        root.addRow(row("ds_hang", "2026-11-12", "5", 300.0));
        root.addRow(row("ds_hang", "2026-11-20", "12", 400.0));

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("work_date", Map.of("start", "2026-11-01", "end", "2026-11-30"));
        filters.put("work_status", "12");
        filters.put("diff_flag", "一致"); // calc field: must be ignored during root pre-filter
        filters.put("u9_complete_qty", "5"); // joined table field: must be ignored during root pre-filter

        Map<String, String> ops = Map.of("work_date", "month", "work_status", "select", "diff_flag", "select");

        Dataset narrowed = engine.preFilterRoot(root, report, filters, ops);
        assertEquals(2, narrowed.rows().size(), "Should keep only 2026-11 rows with work_status=12");
        assertTrue(narrowed.rows().stream().allMatch(r -> String.valueOf(r.get("work_date")).startsWith("2026-11")));
        assertTrue(narrowed.rows().stream().allMatch(r -> "12".equals(r.get("work_status"))));
    }

    private Map<String, Object> row(String ds, String date, String status, double w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("work_date", date);
        m.put(ds + ".work_date", date);
        m.put("work_status", status);
        m.put(ds + ".work_status", status);
        m.put("white_weight", w);
        m.put(ds + ".white_weight", w);
        return m;
    }
}
