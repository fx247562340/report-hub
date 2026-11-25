package com.reporthub.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reporthub.common.BizException;
import com.reporthub.config.ReportHubProperties;
import com.reporthub.domain.DataSourceDef;
import com.reporthub.engine.SessionAuthService;
import com.reporthub.engine.U9cOAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;

class Batch1SecurityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ConfigService newConfigService() {
        ReportHubProperties props = new ReportHubProperties();
        return new ConfigService(
                null, null, null, null, null, null,
                mapper,
                new SessionAuthService(mapper),
                null,
                new U9cOAuthService(mapper),
                new ReportResultCache(props)
        );
    }

    @Test
    void maskAuthConfigHidesSecretsAndKeepsNonSecrets() throws Exception {
        ConfigService svc = newConfigService();

        String u9Masked = svc.maskAuthConfig("u9c_oauth",
                "{\"clientId\":\"bf\",\"clientSecret\":\"real-secret\",\"entCode\":\"001\",\"ttlSeconds\":240}");
        JsonNode u9 = mapper.readTree(u9Masked);
        assertEquals("bf", u9.get("clientId").asText());
        assertEquals(ConfigService.SECRET_MASK, u9.get("clientSecret").asText());
        assertEquals("001", u9.get("entCode").asText());

        String sessMasked = svc.maskAuthConfig("session",
                "{\"username\":\"u1\",\"password\":\"p1\",\"sign\":{\"salt\":\"S1\",\"algorithm\":\"md5\"},\"token\":{\"responsePath\":\"data.token\",\"style\":\"query\"}}");
        JsonNode sess = mapper.readTree(sessMasked);
        assertEquals("u1", sess.get("username").asText());
        assertEquals(ConfigService.SECRET_MASK, sess.get("password").asText());
        assertEquals(ConfigService.SECRET_MASK, sess.path("sign").path("salt").asText());
        assertEquals("data.token", sess.path("token").path("responsePath").asText());

        String apiKeyMasked = svc.maskAuthConfig("api_key",
                "{\"headerName\":\"X-API-Key\",\"value\":\"k-123\"}");
        JsonNode ak = mapper.readTree(apiKeyMasked);
        assertEquals("X-API-Key", ak.get("headerName").asText());
        assertEquals(ConfigService.SECRET_MASK, ak.get("value").asText());
    }

    @Test
    void mergeAuthSecretsPreservesExistingWhenMaskedOrBlank() throws Exception {
        ConfigService svc = newConfigService();
        String existing = "{\"clientId\":\"bf\",\"clientSecret\":\"real-secret\",\"entCode\":\"001\"}";
        String incomingMasked = "{\"clientId\":\"bf2\",\"clientSecret\":\"******\",\"entCode\":\"002\"}";
        String incomingBlank = "{\"clientId\":\"bf3\",\"clientSecret\":\"\",\"entCode\":\"003\"}";
        String incomingNew = "{\"clientId\":\"bf\",\"clientSecret\":\"new-secret\",\"entCode\":\"001\"}";

        JsonNode m1 = mapper.readTree(svc.mergeAuthSecrets("u9c_oauth", incomingMasked, "u9c_oauth", existing));
        assertEquals("bf2", m1.get("clientId").asText());
        assertEquals("real-secret", m1.get("clientSecret").asText());

        JsonNode m2 = mapper.readTree(svc.mergeAuthSecrets("u9c_oauth", incomingBlank, "u9c_oauth", existing));
        assertEquals("bf3", m2.get("clientId").asText());
        assertEquals("real-secret", m2.get("clientSecret").asText());

        JsonNode m3 = mapper.readTree(svc.mergeAuthSecrets("u9c_oauth", incomingNew, "u9c_oauth", existing));
        assertEquals("new-secret", m3.get("clientSecret").asText());
    }

    @Test
    void maskDatasourceReturnsCopyWithoutMutatingOriginal() {
        ConfigService svc = newConfigService();
        DataSourceDef ds = new DataSourceDef();
        ds.setCode("u9c_erp");
        ds.setName("U9C");
        ds.setBaseUrl("https://example.com");
        ds.setAuthType("bearer");
        ds.setAuthConfig("{\"token\":\"abc123\"}");

        DataSourceDef masked = svc.maskDatasource(ds);
        assertNotSame(ds, masked);
        assertTrue(ds.getAuthConfig().contains("abc123"));
        assertTrue(masked.getAuthConfig().contains(ConfigService.SECRET_MASK));
        assertFalse(masked.getAuthConfig().contains("abc123"));
    }

    @Test
    void registerIsBlockedByDefault() {
        ReportHubProperties props = new ReportHubProperties();
        assertFalse(props.getSecurity().isAllowRegister());
        AuthService auth = new AuthService(null, null, null, props);
        BizException ex = assertThrows(BizException.class,
                () -> auth.register("intruder", "123456", "Intruder"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }
}
