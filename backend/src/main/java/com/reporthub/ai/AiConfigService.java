package com.reporthub.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reporthub.common.BizException;
import com.reporthub.domain.AppSetting;
import com.reporthub.repo.AppSettingRepo;
import com.reporthub.service.ConfigService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI 兼容模型配置持久化（存放在 app_settings.ai_config，页面可动态修改，API Key 严格脱敏）。
 */
@Service
public class AiConfigService {
    public static final String SETTING_KEY = "ai_config";

    public record AiConfig(
            boolean enabled,
            String baseUrl,
            String apiKey,
            String model,
            double temperature,
            int maxToolRounds,
            int timeoutSeconds
    ) {
        public boolean isReady() {
            return enabled
                    && baseUrl != null && !baseUrl.isBlank()
                    && apiKey != null && !apiKey.isBlank()
                    && model != null && !model.isBlank();
        }
    }

    private final AppSettingRepo settingRepo;
    private final ObjectMapper mapper;
    private volatile AiConfig cached;

    public AiConfigService(AppSettingRepo settingRepo, ObjectMapper mapper) {
        this.settingRepo = settingRepo;
        this.mapper = mapper;
    }

    public AiConfig current() {
        AiConfig c = cached;
        if (c != null) return c;
        synchronized (this) {
            if (cached != null) return cached;
            AiConfig loaded = loadFromDbOrEnv();
            cached = loaded;
            return loaded;
        }
    }

    private AiConfig loadFromDbOrEnv() {
        var opt = settingRepo != null ? settingRepo.findById(SETTING_KEY) : java.util.Optional.<AppSetting>empty();
        if (opt.isPresent() && opt.get().getValue() != null && !opt.get().getValue().isBlank()) {
            try {
                var node = mapper.readTree(opt.get().getValue());
                return new AiConfig(
                        node.path("enabled").asBoolean(true),
                        node.path("baseUrl").asText(""),
                        node.path("apiKey").asText(""),
                        node.path("model").asText(""),
                        clampDouble(node.path("temperature").asDouble(0.2), 0.0, 1.5),
                        clampInt(node.path("maxToolRounds").asInt(4), 1, 8),
                        clampInt(node.path("timeoutSeconds").asInt(60), 10, 180)
                );
            } catch (Exception ignored) {
                // fall through to env defaults
            }
        }
        String envBase = env("AI_BASE_URL", "");
        String envKey = env("AI_API_KEY", "");
        String envModel = env("AI_MODEL", "");
        boolean envEnabled = !envBase.isBlank() && !envKey.isBlank() && !envModel.isBlank();
        return new AiConfig(envEnabled, envBase, envKey, envModel, 0.2, 4, 60);
    }

    /** 返回前端展示视图（API Key 始终脱敏为 ******） */
    public Map<String, Object> maskedView() {
        AiConfig c = current();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", c.enabled());
        m.put("ready", c.isReady());
        m.put("baseUrl", c.baseUrl() == null ? "" : c.baseUrl());
        m.put("apiKey", (c.apiKey() != null && !c.apiKey().isBlank()) ? ConfigService.SECRET_MASK : "");
        m.put("hasApiKey", c.apiKey() != null && !c.apiKey().isBlank());
        m.put("model", c.model() == null ? "" : c.model());
        m.put("temperature", c.temperature());
        m.put("maxToolRounds", c.maxToolRounds());
        m.put("timeoutSeconds", c.timeoutSeconds());
        return m;
    }

    /** 保存页面修改的模型配置；若 apiKey 为空或等于 ****** 则保留原密钥 */
    public Map<String, Object> save(Map<String, Object> body) {
        AiConfig existing = current();
        boolean enabled = body.containsKey("enabled")
                ? Boolean.parseBoolean(String.valueOf(body.get("enabled")))
                : existing.enabled();
        String baseUrl = body.containsKey("baseUrl")
                ? cleanUrl(String.valueOf(body.getOrDefault("baseUrl", "")))
                : existing.baseUrl();
        String model = body.containsKey("model")
                ? String.valueOf(body.getOrDefault("model", "")).trim()
                : existing.model();

        String incomingKey = body.containsKey("apiKey")
                ? String.valueOf(body.getOrDefault("apiKey", "")).trim()
                : "";
        String finalKey;
        if (incomingKey.isBlank() || ConfigService.SECRET_MASK.equals(incomingKey)) {
            finalKey = existing.apiKey() == null ? "" : existing.apiKey();
        } else {
            finalKey = incomingKey;
        }

        double temperature = body.containsKey("temperature") && body.get("temperature") != null
                ? clampDouble(Double.parseDouble(String.valueOf(body.get("temperature"))), 0.0, 1.5)
                : existing.temperature();
        int maxToolRounds = body.containsKey("maxToolRounds") && body.get("maxToolRounds") != null
                ? clampInt(Integer.parseInt(String.valueOf(body.get("maxToolRounds"))), 1, 8)
                : existing.maxToolRounds();
        int timeoutSeconds = body.containsKey("timeoutSeconds") && body.get("timeoutSeconds") != null
                ? clampInt(Integer.parseInt(String.valueOf(body.get("timeoutSeconds"))), 10, 180)
                : existing.timeoutSeconds();

        if (enabled) {
            if (baseUrl.isBlank()) throw new BizException("启用 AI 问数时，请填写 API Base URL");
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                throw new BizException("API Base URL 必须以 http:// 或 https:// 开头");
            }
            if (model.isBlank()) throw new BizException("启用 AI 问数时，请填写模型名称（Model）");
            if (finalKey.isBlank()) throw new BizException("启用 AI 问数时，请填写 API Key");
        }

        AiConfig next = new AiConfig(enabled, baseUrl, finalKey, model, temperature, maxToolRounds, timeoutSeconds);
        try {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("enabled", next.enabled());
            raw.put("baseUrl", next.baseUrl());
            raw.put("apiKey", next.apiKey());
            raw.put("model", next.model());
            raw.put("temperature", next.temperature());
            raw.put("maxToolRounds", next.maxToolRounds());
            raw.put("timeoutSeconds", next.timeoutSeconds());

            AppSetting s = settingRepo.findById(SETTING_KEY).orElseGet(AppSetting::new);
            s.setKey(SETTING_KEY);
            s.setValue(mapper.writeValueAsString(raw));
            s.setUpdatedAt(Instant.now());
            settingRepo.save(s);
            cached = next;
        } catch (Exception e) {
            throw new BizException("保存 AI 配置失败: " + e.getMessage());
        }
        return maskedView();
    }

    /** 基于页面传入（或已保存）的配置构造一次测试用 AiConfig */
    public AiConfig resolveForTest(Map<String, Object> body) {
        AiConfig existing = current();
        if (body == null || body.isEmpty()) return existing;
        String baseUrl = cleanUrl(String.valueOf(body.getOrDefault("baseUrl", existing.baseUrl())));
        String model = String.valueOf(body.getOrDefault("model", existing.model())).trim();
        String incomingKey = String.valueOf(body.getOrDefault("apiKey", "")).trim();
        String finalKey = (incomingKey.isBlank() || ConfigService.SECRET_MASK.equals(incomingKey))
                ? existing.apiKey() : incomingKey;
        if (baseUrl.isBlank() || model.isBlank() || finalKey == null || finalKey.isBlank()) {
            throw new BizException("请先填写完整的 Base URL、Model 与 API Key");
        }
        return new AiConfig(true, baseUrl, finalKey, model, 0.1, 1, 25);
    }

    private static String cleanUrl(String u) {
        String s = u == null ? "" : u.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static double clampDouble(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
