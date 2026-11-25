package com.reporthub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reporthub.common.BizException;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * 标准 OpenAI 兼容协议 HTTP 客户端（支持普通 Tool Calling 轮次 + SSE 流式回答轮次 + 连通性测试）。
 * 兼容 智谱 BigModel / 阿里云百炼 / DeepSeek / OpenAI / Ollama / vLLM 等所有兼容 /chat/completions 的服务。
 */
@Component
public class AiClient {

    public record ToolCall(String id, String name, String arguments) {}

    public record ChatTurnResult(String content, List<ToolCall> toolCalls, String finishReason) {}

    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public AiClient(ObjectMapper mapper) {
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 非流式调用：用于工具规划轮次，完整解析 tool_calls */
    public ChatTurnResult chatOnce(AiConfigService.AiConfig cfg,
                                   List<Map<String, Object>> messages,
                                   List<Map<String, Object>> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("temperature", cfg.temperature());
        body.put("stream", false);
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        String respBody = sendJson(cfg, body);
        try {
            JsonNode root = mapper.readTree(respBody);
            JsonNode choice = root.path("choices").path(0);
            JsonNode msg = choice.path("message");
            String content = msg.path("content").isMissingNode() || msg.path("content").isNull()
                    ? "" : msg.path("content").asText("");
            List<ToolCall> calls = new ArrayList<>();
            JsonNode tcArr = msg.path("tool_calls");
            if (tcArr.isArray()) {
                for (JsonNode tc : tcArr) {
                    String id = tc.path("id").asText("call_" + UUID.randomUUID().toString().substring(0, 8));
                    JsonNode fn = tc.path("function");
                    String name = fn.path("name").asText("");
                    JsonNode argNode = fn.path("arguments");
                    String args = argNode.isTextual() ? argNode.asText("{}") : argNode.toString();
                    if (!name.isBlank()) {
                        calls.add(new ToolCall(id, name, args));
                    }
                }
            }
            return new ChatTurnResult(content, calls, choice.path("finish_reason").asText(""));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("解析大模型响应失败: " + e.getMessage());
        }
    }

    /** 流式调用：用于最终总结回答，逐 token 回调 onDelta */
    public String chatStream(AiConfigService.AiConfig cfg,
                             List<Map<String, Object>> messages,
                             Consumer<String> onDelta) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("temperature", cfg.temperature());
        body.put("stream", true);
        body.put("messages", messages);

        String url = resolveCompletionsUrl(cfg.baseUrl());
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(30, cfg.timeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                String errText = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new BizException(formatUpstreamError(resp.statusCode(), errText));
            }

            StringBuilder full = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    try {
                        JsonNode chunk = mapper.readTree(data);
                        JsonNode delta = chunk.path("choices").path(0).path("delta");
                        JsonNode contentNode = delta.path("content");
                        if (contentNode.isTextual()) {
                            String piece = contentNode.asText();
                            if (!piece.isEmpty()) {
                                full.append(piece);
                                onDelta.accept(piece);
                            }
                        }
                    } catch (Exception ignored) {
                        // skip keepalive or malformed partial frame
                    }
                }
            }
            return full.toString();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("调用大模型流式接口失败: " + e.getMessage());
        }
    }

    /** 测试模型连通性（发一条极短的 ping 消息） */
    public Map<String, Object> testConnection(AiConfigService.AiConfig cfg) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("temperature", 0.0);
        body.put("max_tokens", 16);
        body.put("stream", false);
        body.put("messages", List.of(Map.of("role", "user", "content", "请只回复：OK")));
        String respBody = sendJson(cfg, body);
        long ms = System.currentTimeMillis() - t0;
        String reply = "";
        try {
            reply = mapper.readTree(respBody).path("choices").path(0).path("message").path("content").asText("").trim();
        } catch (Exception ignored) {}
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("model", cfg.model());
        out.put("latencyMs", ms);
        out.put("reply", reply.isEmpty() ? "连接成功" : reply);
        return out;
    }

    private String sendJson(AiConfigService.AiConfig cfg, Map<String, Object> body) {
        String url = resolveCompletionsUrl(cfg.baseUrl());
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(15, cfg.timeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new BizException(formatUpstreamError(resp.statusCode(), resp.body()));
            }
            return resp.body();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("请求大模型接口失败: " + e.getMessage());
        }
    }

    static String resolveCompletionsUrl(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/chat/completions")) return u;
        return u + "/chat/completions";
    }

    private String formatUpstreamError(int status, String body) {
        String detail = body == null ? "" : body.trim();
        try {
            JsonNode err = mapper.readTree(detail).path("error");
            String msg = err.path("message").asText("");
            if (!msg.isBlank()) detail = msg;
        } catch (Exception ignored) {}
        if (detail.length() > 240) detail = detail.substring(0, 240) + "...";
        return "大模型接口返回 HTTP " + status + (detail.isEmpty() ? "" : "：" + detail);
    }
}
