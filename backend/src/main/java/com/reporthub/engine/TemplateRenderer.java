package com.reporthub.engine;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders {{path}} placeholders from a flat context map. */
public final class TemplateRenderer {
    private static final Pattern P = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    private TemplateRenderer() {}

    public static String render(String template, Map<String, Object> ctx) {
        if (template == null) return null;
        Matcher m = P.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object val = lookup(ctx, key);
            m.appendReplacement(sb, Matcher.quoteReplacement(val == null ? "" : String.valueOf(val)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Object lookup(Map<String, Object> ctx, String key) {
        if (ctx.containsKey(key)) return ctx.get(key);
        // support filter.done_date.start style
        String[] parts = key.split("\\.");
        Object cur = ctx;
        for (String p : parts) {
            if (cur instanceof Map<?, ?> map) {
                cur = map.get(p);
            } else {
                return null;
            }
        }
        return cur;
    }
}
