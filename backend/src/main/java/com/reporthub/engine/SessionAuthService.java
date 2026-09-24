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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-based upstream auth: login form/json -> keep cookies (+ optional token) -> reuse.
 * Auto re-login on configured status codes.
 */
@Service
public class SessionAuthService {
    private static final Logger log = LoggerFactory.getLogger(SessionAuthService.class);

    private final ObjectMapper mapper;
    private final RestClient restClient = RestClient.builder().build();
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public SessionAuthService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public static class SessionState {
        public final Map<String, String> cookies = new LinkedHashMap<>();
        public String token;
        public Instant expiresAt;
        public String username;

        boolean alive() {
            return expiresAt == null || Instant.now().isBefore(expiresAt);
        }
    }

    /** Returns Cookie header value and optional token for this datasource. */
    public synchronized SessionState ensureSession(DataSourceDef ds) {
        Map<String, Object> cfg = readMap(ds.getAuthConfig());
        SessionState state = sessions.get(ds.getCode());
        long ttlSeconds = asLong(cfg.get("ttlSeconds"), 1800);
        if (state != null && state.alive() && (Instant.now().getEpochSecond() - (state.expiresAt == null ? 0 : state.expiresAt.minusSeconds(ttlSeconds).getEpochSecond()) < ttlSeconds)) {
            if (state.expiresAt == null || Instant.now().isBefore(state.expiresAt)) {
                return state;
            }
        }
        if (state != null && state.alive() && state.expiresAt != null) {
            return state;
        }
        return login(ds, cfg);
    }

    public synchronized SessionState login(DataSourceDef ds, Map<String, Object> cfg) {
        if (cfg == null || cfg.isEmpty()) {
            cfg = readMap(ds.getAuthConfig());
        }
        String loginPath = str(cfg, "loginPath", "/login");
        String method = str(cfg, "loginMethod", "POST").toUpperCase(Locale.ROOT);
        String contentType = str(cfg, "loginContentType", "form");
        String usernameField = str(cfg, "usernameField", "username");
        String passwordField = str(cfg, "passwordField", "password");
        String username = str(cfg, "username", "");
        String password = str(cfg, "password", "");

        Map<String, Object> form = new LinkedHashMap<>();
        form.put(usernameField, username);
        form.put(passwordField, password);

        // static extra fields (may contain {{now_ms}} etc.)
        Map<String, Object> extra = asMap(cfg.get("fields"));
        Map<String, Object> ctx = new HashMap<>(form);
        extra.forEach((k, v) -> {
            Object rendered = SignHelper.render(String.valueOf(v), ctx);
            form.put(k, rendered);
            ctx.put(k, rendered);
        });
        ctx.putAll(form);

        // sign
        Map<String, Object> signSpec = asMap(cfg.get("sign"));
        String signField = str(signSpec, "targetField", "sign");
        if (!signSpec.isEmpty() && !str(signSpec, "template", "").isBlank()) {
            String sign = SignHelper.sign(
                    str(signSpec, "algorithm", "md5"),
                    str(signSpec, "template", ""),
                    str(signSpec, "salt", ""),
                    ctx);
            form.put(signField, sign);
            ctx.put(signField, sign);
        }

        String url = joinUrl(ds.getBaseUrl(), loginPath);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");

        ResponseEntity<String> resp;
        try {
            if ("json".equalsIgnoreCase(contentType)) {
                headers.setContentType(MediaType.APPLICATION_JSON);
                JsonNode body = mapper.valueToTree(form);
                resp = restClient.method(HttpMethod.valueOf(method))
                        .uri(URI.create(url))
                        .headers(h -> h.addAll(headers))
                        .body(body.toString())
                        .retrieve()
                        .toEntity(String.class);
            } else {
                headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                StringBuilder sb = new StringBuilder();
                form.forEach((k, v) -> {
                    if (sb.length() > 0) sb.append('&');
                    sb.append(encode(k)).append('=').append(encode(v == null ? "" : String.valueOf(v)));
                });
                resp = restClient.method(HttpMethod.valueOf(method))
                        .uri(URI.create(url))
                        .headers(h -> h.addAll(headers))
                        .body(sb.toString())
                        .retrieve()
                        .toEntity(String.class);
            }
        } catch (Exception e) {
            throw new BizException("登录失败: " + e.getMessage(), HttpStatus.BAD_GATEWAY);
        }

        SessionState state = new SessionState();
        state.username = username;
        // capture set-cookie
        List<String> setCookies = resp.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
        for (String raw : setCookies) {
            parseSetCookie(raw, state.cookies);
        }
        // extra cookies from config (myId / myLineId ...)
        Map<String, Object> extraCookies = asMap(cfg.get("extraCookies"));
        extraCookies.forEach((k, v) -> {
            if (v != null && !String.valueOf(v).isBlank()) state.cookies.put(k, String.valueOf(v));
        });

        // token extract
        Map<String, Object> tokenSpec = asMap(cfg.get("token"));
        if (!tokenSpec.isEmpty()) {
            try {
                JsonNode root = mapper.readTree(resp.getBody() == null ? "{}" : resp.getBody());
                Object raw = JsonPaths.read(root, str(tokenSpec, "responsePath", "access_token"));
                if (raw != null) state.token = String.valueOf(raw);
            } catch (Exception e) {
                log.warn("token extract failed: {}", e.getMessage());
            }
        }

        long ttlSeconds = asLong(cfg.get("ttlSeconds"), 1800);
        state.expiresAt = Instant.now().plusSeconds(ttlSeconds);

        // basic success check
        if (!loginLooksOk(resp, cfg)) {
            throw new BizException("登录失败: HTTP " + resp.getStatusCode()
                    + " body=" + truncate(resp.getBody(), 200), HttpStatus.BAD_GATEWAY);
        }

        sessions.put(ds.getCode(), state);
        log.info("session login ok datasource={} cookies={} token={}", ds.getCode(), state.cookies.keySet(), state.token != null);
        return state;
    }

    public synchronized void invalidate(String dataSourceCode) {
        sessions.remove(dataSourceCode);
    }

    public void applyAuth(DataSourceDef ds, HttpHeaders headers, Map<String, Object> query) {
        if (!"session".equalsIgnoreCase(ds.getAuthType())) return;
        SessionState state = ensureSession(ds);
        if (!state.cookies.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            state.cookies.forEach((k, v) -> {
                if (sb.length() > 0) sb.append("; ");
                sb.append(k).append('=').append(v);
            });
            headers.set(HttpHeaders.COOKIE, sb.toString());
        }
        Map<String, Object> tokenSpec = asMap(readMap(ds.getAuthConfig()).get("token"));
        if (state.token != null && !tokenSpec.isEmpty()) {
            String style = str(tokenSpec, "style", "query"); // query | header | bearer | cookie
            String name = str(tokenSpec, "name", "access_token");
            switch (style) {
                case "header" -> headers.set(name, state.token);
                case "bearer" -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + state.token);
                case "cookie" -> {
                    String existing = headers.getFirst(HttpHeaders.COOKIE);
                    String cookie = (existing == null || existing.isBlank() ? "" : existing + "; ") + name + "=" + state.token;
                    headers.set(HttpHeaders.COOKIE, cookie);
                }
                default -> {
                    if (query != null) query.put(name, state.token);
                }
            }
        }
    }

    public boolean shouldRelogin(int statusCode, String body, DataSourceDef ds) {
        Map<String, Object> cfg = readMap(ds.getAuthConfig());
        List<Integer> codes = new ArrayList<>();
        Object raw = cfg.get("reloginOnStatus");
        if (raw instanceof List<?> list) {
            for (Object o : list) codes.add(Integer.parseInt(String.valueOf(o)));
        } else {
            codes.add(401);
            codes.add(403);
        }
        if (codes.contains(statusCode)) return true;
        // redirect to login html
        if (body != null && body.contains("login") && statusCode == 200 && body.length() < 5000) {
            return body.contains("请登录") || body.contains("未登录") || body.contains("window.location");
        }
        return false;
    }

    private boolean loginLooksOk(ResponseEntity<String> resp, Map<String, Object> cfg) {
        if (resp.getStatusCode().is4xxClientError() || resp.getStatusCode().is5xxServerError()) {
            // some gateways return 200 with error body; 4xx/5xx always fail
            if (!(resp.getStatusCode().value() == 401 || resp.getStatusCode().value() == 403)) {
                // still check body below only for 2xx; fail hard here
            }
            if (resp.getStatusCode().isError() && (resp.getBody() == null || !resp.getBody().contains("\"code\""))) {
                return false;
            }
            if (resp.getStatusCode().is4xxClientError()) return false;
        }
        Map<String, Object> success = readMap(str(cfg, "success", "{}"));
        String type = str(success, "type", "auto");
        if ("status".equalsIgnoreCase(type)) {
            return resp.getStatusCode().is2xxSuccessful();
        }
        String body = resp.getBody() == null ? "" : resp.getBody();
        if ("json_code".equalsIgnoreCase(type) || "auto".equalsIgnoreCase(type) || type.isBlank()) {
            try {
                JsonNode root = mapper.readTree(body.isBlank() ? "{}" : body);
                if (root.isObject() && (root.has("code") || root.has("success") || root.has("status"))) {
                    String codePath = str(success, "codePath", root.has("code") ? "code" : (root.has("success") ? "success" : "status"));
                    Object code = JsonPaths.read(root, codePath);
                    String cs = String.valueOf(code);
                    Object okValues = success.get("okValues");
                    if (okValues instanceof List<?> list) {
                        for (Object o : list) if (String.valueOf(o).equals(cs)) return true;
                        return false;
                    }
                    return "0".equals(cs) || "200".equals(cs) || "true".equalsIgnoreCase(cs) || "ok".equalsIgnoreCase(cs);
                }
            } catch (Exception ignored) {
                // non-JSON body falls through
            }
            if ("json_code".equalsIgnoreCase(type)) return false;
        }
        return resp.getStatusCode().is2xxSuccessful() || resp.getStatusCode().is3xxRedirection();
    }

    private void parseSetCookie(String raw, Map<String, String> cookies) {
        if (raw == null || raw.isBlank()) return;
        String first = raw.split(";", 2)[0];
        int eq = first.indexOf('=');
        if (eq <= 0) return;
        String name = first.substring(0, eq).trim();
        String value = first.substring(eq + 1).trim();
        if (!value.isEmpty()) cookies.put(name, value);
    }

    private String joinUrl(String base, String path) {
        if (path.startsWith("http")) return path;
        if (base.endsWith("/") && path.startsWith("/")) return base + path.substring(1);
        if (!base.endsWith("/") && !path.startsWith("/")) return base + "/" + path;
        return base + path;
    }

    private String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) return Map.of();
        String s = json.trim();
        if (s.startsWith("{")) {
            try {
                return mapper.readValue(s, Map.class);
            } catch (Exception e) {
                return Map.of();
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        if (o instanceof String s) return readMap(s);
        return Map.of();
    }

    private Map<String, Object> readMap(Map<String, Object> map) {
        return map == null ? Map.of() : map;
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

    private String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
