package com.reporthub.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds login signatures from templates like {{userName}}{{password}}{{timestamp}} */
public final class SignHelper {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    private SignHelper() {}

    public static String nowMs() {
        return String.valueOf(Instant.now().toEpochMilli());
    }

    public static String nowS() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    /**
     * Render template then hash.
     * template supports {{userName}} {{password}} {{timestamp}} and any form field names present in ctx.
     * Extra placeholders: {{now_ms}} {{now_s}}
     */
    public static String sign(String algorithm, String template, String salt, Map<String, Object> ctx) {
        String rendered = render(template, ctx);
        if (salt != null && !salt.isEmpty()) {
            rendered = rendered + salt;
        }
        if (algorithm == null || algorithm.isBlank() || "none".equalsIgnoreCase(algorithm)) {
            return rendered;
        }
        return hash(algorithm.toLowerCase(Locale.ROOT), rendered);
    }

    public static String render(String template, Map<String, Object> ctx) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object val;
            switch (key) {
                case "now_ms" -> val = nowMs();
                case "now_s" -> val = nowS();
                default -> val = ctx.get(key);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(val == null ? "" : String.valueOf(val)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String hash(String algorithm, String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algoName(algorithm));
            byte[] out = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("unsupported sign algorithm: " + algorithm, e);
        }
    }

    private static String algoName(String algorithm) {
        return switch (algorithm) {
            case "md5" -> "MD5";
            case "sha1" -> "SHA-1";
            case "sha256" -> "SHA-256";
            case "sha512" -> "SHA-512";
            default -> algorithm.toUpperCase(Locale.ROOT);
        };
    }
}
