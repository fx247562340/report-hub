package com.reporthub.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reporthub.common.BizException;
import com.reporthub.domain.DataSourceDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Yonyou U9C OpenAPI OAuth2 token:
 * GET /webapi/OAuth2/AuthLogin?clientid&clientsecret&entCode&userCode&orgCode
 * Business calls use Header: token=&lt;accessToken&gt; (default TTL 5 min).
 */
@Service
public class U9cOAuthService {
    private static final Logger log = LoggerFactory.getLogger(U9cOAuthService.class);

    private final ObjectMapper mapper;
    private final RestClient restClient = RestClient.builder().build();
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    public U9cOAuthService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    private record CachedToken(String token, Instant expiresAt) {
        boolean alive() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    public synchronized String ensureToken(DataSourceDef ds) {
        CachedToken c = cache.get(ds.getCode());
        if (c != null && c.alive()) return c.token();
        return login(ds);
    }

    public synchronized String login(DataSourceDef ds) {
        Map<String, Object> cfg = readMap(ds.getAuthConfig());
        String clientId = str(cfg, "clientId", str(cfg, "clientid", ""));
        String clientSecret = str(cfg, "clientSecret", str(cfg, "clientsecret", ""));
        String entCode = str(cfg, "entCode", "");
        String userCode = str(cfg, "userCode", "");
        String orgCode = str(cfg, "orgCode", "");
        if (clientId.isBlank() || clientSecret.isBlank() || entCode.isBlank()
                || userCode.isBlank() || orgCode.isBlank()) {
            throw new BizException("U9C OAuth 配置不完整：需要 clientId/clientSecret/entCode/userCode/orgCode");
        }

        String url = join(ds.getBaseUrl(), "/webapi/OAuth2/AuthLogin")
                + "?clientid=" + enc(clientId)
                + "&clientsecret=" + enc(clientSecret)
                + "&entCode=" + enc(entCode)
                + "&userCode=" + enc(userCode)
                + "&orgCode=" + enc(orgCode)
                + "&loginType=1&language=zh-CN";

        try {
            ResponseEntity<String> resp = restClient.get()
                    .uri(URI.create(url))
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .retrieve()
                    .toEntity(String.class);
            JsonNode root = mapper.readTree(resp.getBody() == null ? "{}" : resp.getBody());
            int resCode = root.path("ResCode").asInt(-1);
            if (resCode != 0) {
                throw new BizException("U9C AuthLogin 失败: " + root.path("ResMsg").asText());
            }
            String token = root.path("Data").asText("");
            if (token.isBlank()) {
                throw new BizException("U9C AuthLogin 未返回 token");
            }
            // official message: 5 minutes
            int ttl = (int) asLong(cfg.get("ttlSeconds"), 240);
            cache.put(ds.getCode(), new CachedToken(token, Instant.now().plusSeconds(ttl)));
            log.info("U9C OAuth login ok datasource={} ttlSeconds={}", ds.getCode(), ttl);
            return token;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("U9C AuthLogin 调用失败: " + e.getMessage(), HttpStatus.BAD_GATEWAY);
        }
    }

    public void invalidate(String dataSourceCode) {
        cache.remove(dataSourceCode);
    }

    public void applyAuth(DataSourceDef ds, HttpHeaders headers) {
        if (!isU9c(ds.getAuthType())) return;
        headers.set("token", ensureToken(ds));
    }

    public static boolean isU9c(String authType) {
        return authType != null && ("u9c_oauth".equalsIgnoreCase(authType) || "u9c".equalsIgnoreCase(authType));
    }

    private String join(String base, String path) {
        if (base.endsWith("/") && path.startsWith("/")) return base + path.substring(1);
        if (!base.endsWith("/") && !path.startsWith("/")) return base + "/" + path;
        return base + path;
    }

    private String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String str(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private long asLong(Object v, long def) {
        if (v == null) return def;
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return def;
        }
    }
}
