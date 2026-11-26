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
}
