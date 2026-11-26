package com.reporthub.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reporthub.service.ConfigService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiAgentCoreTest {

    @Test
    void entityResolverHandlesExactSubstringAmbiguousAndSuggestions() {
        List<String> customers = List.of(
                "河北顺达电力铁塔制造有限公司",
                "山东顺达金属制品有限公司",
                "浙江华泰钢结构有限公司",
                "江苏宏远通信铁塔有限公司"
        );

        // Exact match
        var r1 = EntityResolver.resolve("浙江华泰钢结构有限公司", customers);
        assertEquals(List.of("浙江华泰钢结构有限公司"), r1.matched());
        assertFalse(r1.ambiguous());

        // Unique substring match
        var r2 = EntityResolver.resolve("华泰", customers);
        assertEquals(List.of("浙江华泰钢结构有限公司"), r2.matched());
        assertFalse(r2.ambiguous());

        // Ambiguous substring match
        var r3 = EntityResolver.resolve("顺达", customers);
        assertEquals(2, r3.matched().size());
        assertTrue(r3.ambiguous());

        // Zero match with closest suggestions
        var r4 = EntityResolver.resolve("宏远铁塔", customers);
        assertTrue(r4.matched().isEmpty());
        assertFalse(r4.suggestions().isEmpty());
        assertEquals("江苏宏远通信铁塔有限公司", r4.suggestions().get(0));
    }

    @Test
    void aiClientResolvesCompletionsUrlWithoutDuplication() {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                AiClient.resolveCompletionsUrl("https://api.deepseek.com/v1/"));
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions",
                AiClient.resolveCompletionsUrl("https://open.bigmodel.cn/api/paas/v4"));
        assertEquals("https://example.com/v1/chat/completions",
                AiClient.resolveCompletionsUrl("https://example.com/v1/chat/completions"));
        assertEquals("https://api.deepseek.com/v1/models",
                AiClient.resolveModelsUrl("https://api.deepseek.com/v1/"));
        assertEquals("https://example.com/v1/models",
                AiClient.resolveModelsUrl("https://example.com/v1/chat/completions"));
    }

    @Test
    void aiConfigServiceMasksApiKeyByDefault() {
        AiConfigService svc = new AiConfigService(null, new ObjectMapper());
        var view = svc.maskedView();
        assertNotNull(view.get("enabled"));
        assertNotEquals("real-secret", view.get("apiKey"));
        assertTrue("".equals(view.get("apiKey")) || ConfigService.SECRET_MASK.equals(view.get("apiKey")));
    }

    @Test
    void aiClientBuildsToolProbeAndParsesToolCalls() {
        AiClient client = new AiClient(new ObjectMapper());
        var probe = AiClient.buildToolProbeRequest("deepseek-v4.1-flash");
        assertEquals("deepseek-v4.1-flash", probe.get("model"));
        assertEquals("auto", probe.get("tool_choice"));
        assertInstanceOf(java.util.List.class, probe.get("tools"));
        assertEquals(2, ((java.util.List<?>) probe.get("tools")).size());

        String withToolJson = """
                {
                  "choices": [{
                    "finish_reason": "tool_calls",
                    "message": {
                      "role": "assistant",
                      "content": "",
                      "tool_calls": [{
                        "id": "call_1",
                        "type": "function",
                        "function": {
                          "name": "query_cust_monthly_flow",
                          "arguments": "{\\"direction\\":\\"out\\",\\"month\\":\\"2026-11\\"}"
                        }
                      }]
                    }
                  }]
                }
                """;
        var parsed = client.parseChatTurn(withToolJson);
        assertEquals("tool_calls", parsed.finishReason());
        assertEquals(1, parsed.toolCalls().size());
        assertEquals("query_cust_monthly_flow", parsed.toolCalls().get(0).name());

        String noToolJson = """
                {
                  "choices": [{
                    "finish_reason": "stop",
                    "message": { "role": "assistant", "content": "11月发货最多的是某公司" }
                  }]
                }
                """;
        var skipped = client.parseChatTurn(noToolJson);
        assertEquals("stop", skipped.finishReason());
        assertTrue(skipped.toolCalls().isEmpty());
    }

    @Test
    void contractScrollSummarizesU9WhQohWithUnitConversionAndNegatives() throws Exception {
        ObjectMapper om = new ObjectMapper();
        var rows = List.of(
                (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree("""
                        {"Wh":{"Code":"BF003","Name":"镀锌客供原材料库"},"ItemInfo":{"ItemCode":"1205001","ItemName":"钢结构","ItemID":{"MainItemCategory":{"Name":"客户A"}}},"StoreUOM":{"Name":"吨"},"StoreQty":"100.5"}
                        """),
                (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree("""
                        {"Wh":{"Code":"BF003","Name":"镀锌客供原材料库"},"ItemInfo":{"ItemCode":"1205002","ItemName":"支架","ItemID":{"MainItemCategory":{"Name":"客户B"}}},"StoreUOM":{"Name":"吨"},"StoreQty":"-20.25"}
                        """),
                (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree("""
                        {"Wh":{"Code":"BF003","Name":"镀锌客供原材料库"},"ItemInfo":{"ItemCode":"1205003","ItemName":"小件","ItemID":{"MainItemCategory":{"Name":"客户C"}}},"StoreUOM":{"Name":"公斤"},"StoreQty":"500"}
                        """),
                (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree("""
                        {"Wh":{"Code":"BF003","Name":"镀锌客供原材料库"},"ItemInfo":{"ItemCode":"1205004","ItemName":"预埋件","ItemID":{"MainItemCategory":{"Name":"客户D"}}},"StoreUOM":{"Name":"批"},"StoreQty":"10"}
                        """),
                (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree("""
                        {"Wh":{"Code":"BF004","Name":"镀锌成品库"},"ItemInfo":{"ItemCode":"1104001","ItemName":"成品塔筒","ItemID":{"MainItemCategory":{"Name":"客户E"}}},"StoreUOM":{"Name":"吨"},"StoreQty":"300.75"}
                        """),
                (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree("""
                        {"Wh":{"Code":"BF001","Name":"其他库"},"ItemInfo":{"ItemCode":"999","ItemName":"锌锭"},"StoreUOM":{"Name":"吨"},"StoreQty":"999"}
                        """)
        );

        var snap = com.reporthub.service.ContractScrollService.summarizeWhQoh(rows, "local", "2026-11-26T00:00:00Z");
        assertEquals(80.75, snap.blackTon(), 0.001); // 100.5 - 20.25 + 0.5 (500kg), skipping 批 and BF001
        assertEquals(300.75, snap.whiteTon(), 0.001);
        assertEquals(381.50, snap.totalTon(), 0.001);
        assertEquals(1, snap.negativeTotal());
        assertEquals(1, snap.blackNegativeCount());
        assertEquals(0, snap.whiteNegativeCount());
        assertEquals("客户B", snap.topAlerts().get(0).get("customerName"));
        assertEquals(-20.25, ((Number) snap.topAlerts().get(0).get("blackStock")).doubleValue(), 0.001);
    }

    @Test
    void hangReportDateNormalizationAndRealtimePassthroughRules() {
        assertEquals("2026-11-26 23:59:59", AiToolExecutor.normalizeEndDateInclusive("2026-11-26"));
        assertEquals("2026-11-26 18:00:00", AiToolExecutor.normalizeEndDateInclusive("2026-11-26 18:00:00"));
        assertEquals("", AiToolExecutor.normalizeEndDateInclusive(""));

        var today = java.time.LocalDate.of(2026, 11, 26);
        assertTrue(AiToolExecutor.shouldUseRealtimeForRecentHang("2026-11-26", "2026-11-26", today));
        assertTrue(AiToolExecutor.shouldUseRealtimeForRecentHang("2026-11-24", "2026-11-26", today));
        assertFalse(AiToolExecutor.shouldUseRealtimeForRecentHang("2026-11-01", "2026-11-30", today));
        assertFalse(AiToolExecutor.shouldUseRealtimeForRecentHang("2026-11-25", "2026-11-25", today));
    }
}
