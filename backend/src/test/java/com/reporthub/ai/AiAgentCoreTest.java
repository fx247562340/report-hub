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
}
