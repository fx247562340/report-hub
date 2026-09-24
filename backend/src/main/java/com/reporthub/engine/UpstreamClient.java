package com.reporthub.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reporthub.common.BizException;
import com.reporthub.domain.ApiEndpointDef;
import com.reporthub.domain.DataSourceDef;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.*;

@Component
public class UpstreamClient {
    private final ObjectMapper mapper;
    private final RestClient restClient;
    private final SessionAuthService sessionAuthService;
    private final U9cOAuthService u9cOAuthService;

    public UpstreamClient(ObjectMapper mapper,
                          SessionAuthService sessionAuthService,
                          U9cOAuthService u9cOAuthService) {
        this.mapper = mapper;
        this.sessionAuthService = sessionAuthService;
        this.u9cOAuthService = u9cOAuthService;
        this.restClient = RestClient.builder().build();
    }

    public record UpstreamResult(List<ObjectNode> rawRows, Integer total, long durationMs, String url, String rawBody) {
        public UpstreamResult(List<ObjectNode> rawRows, Integer total, long durationMs, String url) {
            this(rawRows, total, durationMs, url, null);
        }
    }

    public UpstreamResult fetchList(DataSourceDef ds, ApiEndpointDef ep, Map<String, Object> ctx) {
        return doFetchList(ds, ep, ctx, false);
    }

    private UpstreamResult doFetchList(DataSourceDef ds, ApiEndpointDef ep, Map<String, Object> ctx, boolean isRetry) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> query = new LinkedHashMap<>(renderMap(ep.getQueryTemplate(), ctx));
        Map<String, Object> headers = renderMap(ep.getHeadersTemplate(), ctx);
        String path = TemplateRenderer.render(ep.getPath(), ctx);
        String url = joinUrl(ds.getBaseUrl(), path);

        HttpHeaders httpHeaders = buildAuth(ds, headers, query);

        String bodyType = ep.getBodyType() == null || ep.getBodyType().isBlank()
                ? ((ep.getBodyTemplate() != null && !ep.getBodyTemplate().isBlank()) ? "json" : "none")
                : ep.getBodyType().toLowerCase();

        String body = null;
        if (ep.getBodyTemplate() != null && !ep.getBodyTemplate().isBlank()
                && !"GET".equalsIgnoreCase(ep.getMethod())
                && !"none".equals(bodyType)) {
            body = renderBody(ep.getBodyTemplate(), ctx);
            if ("form".equals(bodyType)) {
                if (!httpHeaders.containsKey(HttpHeaders.CONTENT_TYPE)) {
                    httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                }
                body = toFormBody(body);
            } else if (!httpHeaders.containsKey(HttpHeaders.CONTENT_TYPE)) {
                httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            }
        } else if (!httpHeaders.containsKey(HttpHeaders.CONTENT_TYPE)) {
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        }

        String fullUrl = appendQuery(url, query);
        var request = restClient.method(HttpMethod.valueOf(ep.getMethod().toUpperCase()))
                .uri(java.net.URI.create(fullUrl))
                .headers(h -> h.addAll(httpHeaders));

        ResponseEntity<String> resp;
        try {
            if (body != null) {
                resp = request.body(body).retrieve().toEntity(String.class);
            } else {
                resp = request.retrieve().toEntity(String.class);
            }
        } catch (RestClientResponseException ex) {
            resp = ResponseEntity.status(ex.getStatusCode())
                    .headers(ex.getResponseHeaders())
                    .body(ex.getResponseBodyAsString());
        }

        if (!isRetry && "session".equalsIgnoreCase(ds.getAuthType())
                && sessionAuthService.shouldRelogin(resp.getStatusCode().value(), resp.getBody(), ds)) {
            sessionAuthService.invalidate(ds.getCode());
            return doFetchList(ds, ep, ctx, true);
        }
        if (!isRetry && U9cOAuthService.isU9c(ds.getAuthType())
                && (resp.getStatusCode().value() == 401 || resp.getStatusCode().value() == 504
                    || (resp.getBody() != null && resp.getBody().contains("无效的token")))) {
            u9cOAuthService.invalidate(ds.getCode());
            return doFetchList(ds, ep, ctx, true);
        }

        try {
            JsonNode root = mapper.readTree(resp.getBody() == null ? "{}" : resp.getBody());
            List<ObjectNode> rows = JsonPaths.toRowList(root, ep.getListPath());
            Integer total = null;
            if (ep.getTotalPath() != null && !ep.getTotalPath().isBlank()) {
                Object t = JsonPaths.read(root, ep.getTotalPath());
                if (t instanceof Number n) total = n.intValue();
                else if (t != null) {
                    try { total = Integer.parseInt(String.valueOf(t)); } catch (Exception ignored) {}
                }
            }
            return new UpstreamResult(rows, total, System.currentTimeMillis() - t0, fullUrl,
                    truncate(resp.getBody(), 20000));
        } catch (Exception e) {
            throw new BizException("upstream parse failed for " + fullUrl + ": " + e.getMessage(), HttpStatus.BAD_GATEWAY);
        }
    }

    private String truncate(String s, int n) {
        if (s == null) return null;
        return s.length() <= n ? s : s.substring(0, n) + "...(truncated)";
    }

    /**
     * 将 JSON 对象模板渲染结果转成 x-www-form-urlencoded。
     * 支持 {"a":"1","b":"{{x}}"}；数组/标量会保留成字符串值。
     */
    private String toFormBody(String renderedJson) {
        try {
            JsonNode node = mapper.readTree(renderedJson == null || renderedJson.isBlank() ? "{}" : renderedJson);
            StringBuilder sb = new StringBuilder();
            if (node.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = node.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if (sb.length() > 0) sb.append('&');
                    String val = e.getValue().isNull() ? "" : (e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString());
                    sb.append(java.net.URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8))
                      .append('=')
                      .append(java.net.URLEncoder.encode(val, java.nio.charset.StandardCharsets.UTF_8));
                }
            } else {
                // 非对象时按原文传，避免静默丢参数
                return renderedJson == null ? "" : renderedJson;
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BizException("form body 不是合法 JSON 对象: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> mapAll(List<ObjectNode> rawRows, List<JsonPaths.FieldRule> rules) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ObjectNode raw : rawRows) {
            out.add(JsonPaths.mapRow(raw, rules));
        }
        return out;
    }

    /**
     * Body template supports {{id}}/{{ids}} placeholders.
     * For batch key lists, if template is a JSON array item pattern like
     * [ {"DocNo":"{{key}}"} ] and ctx contains keyList, expand one object per key.
     */
    private String renderBody(String template, Map<String, Object> ctx) {
        if (ctx.get("keyList") instanceof List<?> keys && !keys.isEmpty()) {
            String t = template.trim();
            boolean isArrItem = t.startsWith("[") && (t.contains("{{key}}") || t.contains("{{id}}"));
            if (isArrItem) {
                List<String> items = new ArrayList<>();
                for (Object k : keys) {
                    Map<String, Object> itemCtx = new LinkedHashMap<>(ctx);
                    String ks = String.valueOf(k);
                    itemCtx.put("id", ks);
                    itemCtx.put("key", ks);
                    String item = TemplateRenderer.render(t, itemCtx);
                    // unwrap [ ... ] wrapper to get one element
                    item = item.trim();
                    if (item.startsWith("[")) {
                        item = item.substring(1);
                        if (item.endsWith("]")) item = item.substring(0, item.length() - 1);
                    }
                    item = item.trim();
                    if (!item.isEmpty()) items.add(item);
                }
                return "[" + String.join(",", items) + "]";
            }
        }
        return TemplateRenderer.render(template, ctx);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> renderMap(String json, Map<String, Object> ctx) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            String rendered = TemplateRenderer.render(json, ctx);
            JsonNode node = mapper.readTree(rendered);
            if (!node.isObject()) return new LinkedHashMap<>();
            return new LinkedHashMap<>(mapper.convertValue(node, Map.class));
        } catch (Exception e) {
            throw new BizException("invalid template json: " + e.getMessage());
        }
    }

    private HttpHeaders buildAuth(DataSourceDef ds, Map<String, Object> headers, Map<String, Object> query) {
        HttpHeaders h = new HttpHeaders();
        headers.forEach((k, v) -> {
            if (v != null) h.add(k, String.valueOf(v));
        });
        String type = ds.getAuthType() == null ? "none" : ds.getAuthType();
        if (U9cOAuthService.isU9c(type)) {
            u9cOAuthService.applyAuth(ds, h);
            return h;
        }
        if ("session".equalsIgnoreCase(type)) {
            sessionAuthService.applyAuth(ds, h, query);
            return h;
        }
        try {
            JsonNode auth = ds.getAuthConfig() == null || ds.getAuthConfig().isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(ds.getAuthConfig());
            switch (type) {
                case "basic" -> {
                    String user = auth.path("username").asText("");
                    String pass = auth.path("password").asText("");
                    String token = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
                    h.set(HttpHeaders.AUTHORIZATION, "Basic " + token);
                }
                case "bearer" -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + auth.path("token").asText(""));
                case "api_key" -> {
                    String name = auth.path("headerName").asText("X-API-Key");
                    h.set(name, auth.path("value").asText(""));
                }
                default -> {}
            }
        } catch (Exception e) {
            throw new BizException("invalid datasource auth config");
        }
        return h;
    }

    private String appendQuery(String url, Map<String, Object> query) {
        if (query == null || query.isEmpty()) return url;
        org.springframework.web.util.UriComponentsBuilder builder =
                org.springframework.web.util.UriComponentsBuilder.fromHttpUrl(url);
        for (Map.Entry<String, Object> e : query.entrySet()) {
            if (e.getValue() == null || String.valueOf(e.getValue()).isEmpty()) continue;
            String k = e.getKey();
            String s = String.valueOf(e.getValue());
            if (k.endsWith("[]") || k.endsWith("List")) {
                String bare = k.endsWith("[]") ? k.substring(0, k.length() - 2) : k;
                for (String part : s.split(",")) {
                    if (!part.isBlank()) builder.queryParam(bare, part.trim());
                }
            } else {
                builder.queryParam(k, s);
            }
        }
        return builder.build().encode().toUriString();
    }

    private String joinUrl(String base, String path) {
        if (path == null) path = "";
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        if (base.endsWith("/") && path.startsWith("/")) return base + path.substring(1);
        if (!base.endsWith("/") && !path.startsWith("/")) return base + "/" + path;
        return base + path;
    }

    public ObjectMapper mapper() { return mapper; }
}
