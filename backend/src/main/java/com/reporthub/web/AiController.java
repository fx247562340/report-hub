package com.reporthub.web;

import com.reporthub.ai.AiChatService;
import com.reporthub.ai.AiClient;
import com.reporthub.ai.AiConfigService;
import com.reporthub.common.ApiResponse;
import com.reporthub.domain.AiSession;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * AI 智能问数接口（当前仅管理员开放，含页面可配置 OpenAI 兼容模型与持久化会话记录）。
 */
@RestController
@RequestMapping("/api/ai")
@PreAuthorize("hasRole('ADMIN')")
public class AiController {

    private final AiConfigService configService;
    private final AiClient aiClient;
    private final AiChatService chatService;

    public AiController(AiConfigService configService, AiClient aiClient, AiChatService chatService) {
        this.configService = configService;
        this.aiClient = aiClient;
        this.chatService = chatService;
    }

    // ---- 模型配置（OpenAI 兼容协议，API Key 脱敏） ----

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> getConfig() {
        return ApiResponse.ok(configService.maskedView());
    }

    @PostMapping("/config")
    public ApiResponse<Map<String, Object>> saveConfig(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(configService.save(body));
    }

    @PostMapping("/config/models")
    public ApiResponse<List<String>> fetchUpstreamModels(@RequestBody(required = false) Map<String, Object> body) {
        AiConfigService.AiConfig cfg = configService.resolveForFetchModels(body);
        return ApiResponse.ok(aiClient.fetchModels(cfg));
    }

    @PostMapping("/config/test")
    public ApiResponse<Map<String, Object>> testConfig(@RequestBody(required = false) Map<String, Object> body) {
        AiConfigService.AiConfig cfg = configService.resolveForTest(body);
        return ApiResponse.ok(aiClient.testConnection(cfg));
    }

    // ---- 会话与历史记录 ----

    @GetMapping("/sessions")
    public ApiResponse<List<AiSession>> listSessions(Authentication auth) {
        return ApiResponse.ok(chatService.listSessions(auth.getName()));
    }

    @PostMapping("/sessions")
    public ApiResponse<AiSession> createSession(Authentication auth, @RequestBody(required = false) Map<String, String> body) {
        String title = body == null ? null : body.get("title");
        return ApiResponse.ok(chatService.createSession(auth.getName(), title));
    }

    @PatchMapping("/sessions/{id}")
    public ApiResponse<AiSession> renameSession(Authentication auth,
                                                @PathVariable String id,
                                                @RequestBody Map<String, String> body) {
        return ApiResponse.ok(chatService.renameSession(auth.getName(), id, body.get("title")));
    }

    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Void> deleteSession(Authentication auth, @PathVariable String id) {
        chatService.deleteSession(auth.getName(), id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/sessions/{id}/messages")
    public ApiResponse<List<Map<String, Object>>> messages(Authentication auth, @PathVariable String id) {
        return ApiResponse.ok(chatService.listMessages(auth.getName(), id));
    }

    // ---- SSE 流式问数 ----

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication auth, @RequestBody Map<String, String> body) {
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return chatService.streamChat(
                auth.getName(),
                isAdmin,
                body.get("sessionId"),
                body.get("question"),
                body.get("model")
        );
    }
}
