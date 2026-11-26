package com.reporthub.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reporthub.common.BizException;
import com.reporthub.domain.AiMessage;
import com.reporthub.domain.AiSession;
import com.reporthub.repo.AiMessageRepo;
import com.reporthub.repo.AiSessionRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 智能问数会话管理与 ReAct 工具调用编排服务。
 */
@Service
public class AiChatService {
    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private final AiConfigService configService;
    private final AiClient aiClient;
    private final AiToolExecutor toolExecutor;
    private final AiSessionRepo sessionRepo;
    private final AiMessageRepo messageRepo;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-chat-worker");
        t.setDaemon(true);
        return t;
    });

    public AiChatService(AiConfigService configService,
                         AiClient aiClient,
                         AiToolExecutor toolExecutor,
                         AiSessionRepo sessionRepo,
                         AiMessageRepo messageRepo,
                         ObjectMapper mapper) {
        this.configService = configService;
        this.aiClient = aiClient;
        this.toolExecutor = toolExecutor;
        this.sessionRepo = sessionRepo;
        this.messageRepo = messageRepo;
        this.mapper = mapper;
    }

    public List<AiSession> listSessions(String username) {
        return sessionRepo.findTop50ByUsernameOrderByUpdatedAtDesc(username);
    }

    public AiSession createSession(String username, String title) {
        AiSession s = new AiSession();
        s.setId(UUID.randomUUID().toString());
        s.setUsername(username);
        s.setTitle(title != null && !title.isBlank() ? truncate(title.trim(), 60) : "新问询会话");
        Instant now = Instant.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        return sessionRepo.save(s);
    }

    public AiSession renameSession(String username, String sessionId, String title) {
        AiSession s = requireOwnedSession(username, sessionId);
        if (title == null || title.isBlank()) throw new BizException("会话标题不能为空");
        s.setTitle(truncate(title.trim(), 60));
        s.setUpdatedAt(Instant.now());
        return sessionRepo.save(s);
    }

    @Transactional
    public void deleteSession(String username, String sessionId) {
        AiSession s = requireOwnedSession(username, sessionId);
        messageRepo.deleteBySessionId(s.getId());
        sessionRepo.delete(s);
    }

    public List<Map<String, Object>> listMessages(String username, String sessionId) {
        requireOwnedSession(username, sessionId);
        return messageRepo.findBySessionIdOrderByCreatedAtAscIdAsc(sessionId).stream().map(m -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", m.getId());
            item.put("sessionId", m.getSessionId());
            item.put("role", m.getRole());
            item.put("content", m.getContent());
            item.put("tools", parseJsonList(m.getToolsJson()));
            item.put("links", parseJsonList(m.getLinksJson()));
            item.put("durationMs", m.getDurationMs());
            item.put("createdAt", m.getCreatedAt());
            return item;
        }).toList();
    }

    /** 发起一次流式多轮问答，并把问答记录、调用工具与下钻链接持久化落库 */
    public SseEmitter streamChat(String username, boolean isAdmin, String sessionId, String question, String requestedModel) {
        if (question == null || question.isBlank()) {
            throw new BizException("请输入要查询的问题");
        }
        AiConfigService.AiConfig cfg = configService.current();
        if (!cfg.isReady()) {
            throw new BizException("尚未配置可用的 AI 模型，请先前往「系统管理 → 模型配置」完成设置");
        }
        if (requestedModel != null && !requestedModel.isBlank()) {
            String rm = requestedModel.trim();
            boolean allowed = rm.equals(cfg.model()) || (cfg.models() != null && cfg.models().contains(rm));
            if (!allowed) {
                throw new BizException("所选模型未在系统配置列表中: " + rm);
            }
            cfg = cfg.withModel(rm);
        }

        // 确保会话归属；未传 sessionId 时自动新建会话并以问题前 24 字命名
        AiSession session;
        if (sessionId == null || sessionId.isBlank()) {
            session = createSession(username, truncate(question.trim(), 24));
        } else {
            session = requireOwnedSession(username, sessionId);
            if ("新问询会话".equals(session.getTitle())) {
                session.setTitle(truncate(question.trim(), 24));
                session.setUpdatedAt(Instant.now());
                sessionRepo.save(session);
            }
        }

        // 先加载历史消息再写入本条 user 消息
        List<AiMessage> history = messageRepo.findBySessionIdOrderByCreatedAtAscIdAsc(session.getId());
        AiMessage userMsg = new AiMessage();
        userMsg.setSessionId(session.getId());
        userMsg.setRole("user");
        userMsg.setContent(question.trim());
        userMsg.setCreatedAt(Instant.now());
        messageRepo.save(userMsg);

        SseEmitter emitter = new SseEmitter(180_000L);
        final AiSession finalSession = session;
        final AiConfigService.AiConfig effectiveCfg = cfg;
        executor.execute(() -> runAgentLoop(emitter, effectiveCfg, finalSession, history, question.trim(), isAdmin));
        return emitter;
    }

    private void runAgentLoop(SseEmitter emitter,
                              AiConfigService.AiConfig cfg,
                              AiSession session,
                              List<AiMessage> history,
                              String question,
                              boolean isAdmin) {
        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> usedTools = new ArrayList<>();
        Map<String, AiToolExecutor.DrillLink> linkDedup = new LinkedHashMap<>();
        try {
            sendEvent(emitter, "session", Map.of(
                    "sessionId", session.getId(),
                    "title", session.getTitle()
            ));

            List<Map<String, Object>> llmMessages = new ArrayList<>();
            llmMessages.add(Map.of("role", "system", "content", AiPromptBuilder.buildSystemPrompt()));

            // 仅携带最近 8 条历史，控制 token 成本
            int startIdx = Math.max(0, history.size() - 8);
            for (int i = startIdx; i < history.size(); i++) {
                AiMessage hm = history.get(i);
                if ("user".equals(hm.getRole()) || "assistant".equals(hm.getRole())) {
                    String content = hm.getContent();
                    if (content != null && !content.isBlank()) {
                        llmMessages.add(Map.of("role", hm.getRole(), "content", truncate(content, 1500)));
                    }
                }
            }
            llmMessages.add(Map.of("role", "user", "content", question));

            List<Map<String, Object>> tools = toolExecutor.toolDefinitions(isAdmin);
            List<String> toolResultNotes = new ArrayList<>();
            String finalAnswer = null;

            // Phase A: 多轮 Tool Calling 规划与数据拉取
            for (int round = 0; round < cfg.maxToolRounds(); round++) {
                sendEvent(emitter, "status", Map.of(
                        "step", "thinking",
                        "text", round == 0 ? "正在理解问题并规划查询…" : "正在综合数据判断是否需要补充查询…"
                ));

                AiClient.ChatTurnResult turn = aiClient.chatOnce(cfg, llmMessages, tools);
                if (turn.toolCalls() == null || turn.toolCalls().isEmpty()) {
                    // 无需再调工具，直接把结果分段推给前端（如已调过工具则走 Phase B 流式润色更丝滑，否则直接输出）
                    finalAnswer = turn.content();
                    break;
                }

                // 追加 assistant 的 tool_calls 消息
                List<Map<String, Object>> tcPayload = new ArrayList<>();
                for (AiClient.ToolCall tc : turn.toolCalls()) {
                    tcPayload.add(Map.of(
                            "id", tc.id(),
                            "type", "function",
                            "function", Map.of("name", tc.name(), "arguments", tc.arguments())
                    ));
                }
                Map<String, Object> assistantTurn = new LinkedHashMap<>();
                assistantTurn.put("role", "assistant");
                assistantTurn.put("content", turn.content() == null ? "" : turn.content());
                assistantTurn.put("tool_calls", tcPayload);
                llmMessages.add(assistantTurn);

                // 顺序执行本轮工具并推送实时进度
                for (AiClient.ToolCall tc : turn.toolCalls()) {
                    String label = toolExecutor.displayLabelOf(tc.name());
                    sendEvent(emitter, "status", Map.of(
                            "step", "tool",
                            "tool", tc.name(),
                            "text", "正在查询「" + label + "」…"
                    ));
                    AiToolExecutor.ToolResult tr = toolExecutor.execute(tc.name(), tc.arguments(), isAdmin);
                    Map<String, Object> toolAudit = new LinkedHashMap<>();
                    toolAudit.put("tool", tr.toolName());
                    toolAudit.put("label", tr.displayLabel());
                    toolAudit.put("summary", tr.summaryText());
                    usedTools.add(toolAudit);
                    for (AiToolExecutor.DrillLink dl : tr.links()) {
                        linkDedup.putIfAbsent(dl.to(), dl);
                    }
                    String toolJson = mapper.writeValueAsString(tr.payload());
                    toolResultNotes.add("【" + tr.displayLabel() + "（" + tr.summaryText() + "）】\n" + truncate(toolJson, 5000));
                    llmMessages.add(Map.of(
                            "role", "tool",
                            "tool_call_id", tc.id(),
                            "content", toolJson
                    ));
                }
            }

            List<Map<String, String>> linksPayload = linkDedup.values().stream()
                    .map(l -> Map.of("label", l.label(), "to", l.to()))
                    .toList();
            if (!usedTools.isEmpty()) {
                sendEvent(emitter, "tools", usedTools);
            }
            if (!linksPayload.isEmpty()) {
                sendEvent(emitter, "links", linksPayload);
            }

            // Phase B: 流式输出最终答案（若前面直接返回了答案，则按小块平滑输出；若达到轮次上限则显式收口总结，流式为空时纯文本非流式兜底）
            sendEvent(emitter, "status", Map.of("step", "writing", "text", "正在整理分析结论…"));
            if (finalAnswer != null && !finalAnswer.isBlank()) {
                emitTextChunks(emitter, finalAnswer);
            } else {
                List<Map<String, Object>> wrapUpMessages = new ArrayList<>(llmMessages);
                wrapUpMessages.add(Map.of(
                        "role", "user",
                        "content", "工具调用已达到最大轮次上限，请停止调用任何工具，直接基于上方已经获取到的工具结果，用清晰、严谨的中文给出最终结论（若部分明细未查到，请如实说明已查到的数值和未查到的原因）。"
                ));
                try {
                    finalAnswer = aiClient.chatStream(cfg, wrapUpMessages, piece -> {
                        try {
                            sendEvent(emitter, "delta", Map.of("content", piece));
                        } catch (IOException ignored) {}
                    });
                } catch (Exception streamEx) {
                    log.warn("Phase B stream wrap-up failed, falling back to plain summary: {}", streamEx.getMessage());
                    finalAnswer = "";
                }

                // 部分兼容网关在历史含 role=tool 但不传 tools 时会返回空流，此时用纯文本上下文做非流式兜底总结
                if (finalAnswer == null || finalAnswer.isBlank()) {
                    try {
                        List<Map<String, Object>> plainMsgs = buildPlainSummaryMessages(
                                AiPromptBuilder.buildSystemPrompt(), question, toolResultNotes);
                        AiClient.ChatTurnResult fallbackTurn = aiClient.chatOnce(cfg, plainMsgs, null);
                        if (fallbackTurn.content() != null && !fallbackTurn.content().isBlank()) {
                            finalAnswer = fallbackTurn.content();
                            emitTextChunks(emitter, finalAnswer);
                        }
                    } catch (Exception fbEx) {
                        log.warn("Phase B plain fallback summary failed: {}", fbEx.getMessage());
                    }
                }
            }

            if (finalAnswer == null || finalAnswer.isBlank()) {
                finalAnswer = "本次查询已完成，但模型未返回文本内容，请尝试换种表述重试。";
                sendEvent(emitter, "delta", Map.of("content", finalAnswer));
            }

            int durationMs = (int) (System.currentTimeMillis() - t0);
            AiMessage assistantMsg = new AiMessage();
            assistantMsg.setSessionId(session.getId());
            assistantMsg.setRole("assistant");
            assistantMsg.setContent(finalAnswer);
            assistantMsg.setToolsJson(mapper.writeValueAsString(usedTools));
            assistantMsg.setLinksJson(mapper.writeValueAsString(linksPayload));
            assistantMsg.setDurationMs(durationMs);
            assistantMsg.setCreatedAt(Instant.now());
            AiMessage saved = messageRepo.save(assistantMsg);

            session.setUpdatedAt(Instant.now());
            sessionRepo.save(session);

            sendEvent(emitter, "done", Map.of(
                    "messageId", saved.getId(),
                    "sessionId", session.getId(),
                    "durationMs", durationMs,
                    "tools", usedTools,
                    "links", linksPayload
            ));
            emitter.complete();
        } catch (Exception e) {
            log.warn("AI chat failed: {}", e.getMessage());
            try {
                sendEvent(emitter, "error", Map.of("message", e.getMessage() == null ? "AI 问数执行异常" : e.getMessage()));
                emitter.complete();
            } catch (Exception ignored) {
                emitter.completeWithError(e);
            }
        }
    }

    private AiSession requireOwnedSession(String username, String sessionId) {
        AiSession s = sessionRepo.findById(sessionId).orElseThrow(() -> new BizException("会话不存在"));
        if (!Objects.equals(s.getUsername(), username)) {
            throw new BizException("无权访问该会话");
        }
        return s;
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(mapper.writeValueAsString(data)));
    }

    private List<Object> parseJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, Object.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private void emitTextChunks(SseEmitter emitter, String text) throws IOException {
        int step = 24;
        for (int i = 0; i < text.length(); i += step) {
            String chunk = text.substring(i, Math.min(text.length(), i + step));
            sendEvent(emitter, "delta", Map.of("content", chunk));
        }
    }

    static List<Map<String, Object>> buildPlainSummaryMessages(String systemPrompt,
                                                               String question,
                                                               List<String> toolResultNotes) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题：").append(question).append("\n\n");
        if (toolResultNotes != null && !toolResultNotes.isEmpty()) {
            sb.append("以下是系统工具已查询到的真实业务数据：\n\n");
            for (int i = 0; i < toolResultNotes.size(); i++) {
                sb.append(i + 1).append(". ").append(toolResultNotes.get(i)).append("\n\n");
            }
        }
        sb.append("请严格基于以上真实查询结果，用简洁严谨的中文直接回答用户问题（严禁编造未出现的数字；如有未能查到的部分请如实说明）。");
        return List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", sb.toString())
        );
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
