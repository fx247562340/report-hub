package com.reporthub.ai;

import java.util.*;

/**
 * 确定性实体消歧器：将用户口语中的简称（如“顺达”“华泰”）精准映射到库内标准客户名称。
 * 区分：唯一命中、多候选歧义、无命中（附相近候选建议）。
 */
public final class EntityResolver {

    private EntityResolver() {}

    public record Resolution(
            String query,
            List<String> matched,
            boolean ambiguous,
            List<String> suggestions
    ) {}

    public static String normalize(String s) {
        if (s == null) return "";
        return s.trim()
                .replace("（", "(")
                .replace("）", ")")
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    public static Resolution resolve(String keyword, Collection<String> universe) {
        String q = normalize(keyword);
        if (q.isEmpty() || universe == null || universe.isEmpty()) {
            return new Resolution(keyword == null ? "" : keyword, List.of(), false, List.of());
        }

        // 去重并保持原名
        Map<String, String> normToOriginal = new LinkedHashMap<>();
        for (String name : universe) {
            if (name == null || name.isBlank()) continue;
            normToOriginal.putIfAbsent(normalize(name), name.trim());
        }

        // 1. 精确命中
        if (normToOriginal.containsKey(q)) {
            return new Resolution(keyword, List.of(normToOriginal.get(q)), false, List.of());
        }

        // 2. 子串包含匹配（简称包含在全称中，或全称包含在用户输入中）
        List<String> hits = new ArrayList<>();
        for (var e : normToOriginal.entrySet()) {
            if (e.getKey().contains(q) || q.contains(e.getKey())) {
                hits.add(e.getValue());
            }
        }
        if (!hits.isEmpty()) {
            return new Resolution(keyword, hits, hits.size() > 1, List.of());
        }

        // 3. 字符重叠召回 Top 5 相近建议（帮助纠错）
        Set<Character> qChars = toCharSet(q);
        List<Map.Entry<String, Double>> scored = new ArrayList<>();
        for (var e : normToOriginal.entrySet()) {
            Set<Character> cChars = toCharSet(e.getKey());
            int overlap = 0;
            for (char ch : qChars) {
                if (cChars.contains(ch)) overlap++;
            }
            if (overlap >= Math.min(2, qChars.size())) {
                double score = (double) overlap / Math.max(1, qChars.size());
                scored.add(Map.entry(e.getValue(), score));
            }
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<String> suggestions = scored.stream().limit(5).map(Map.Entry::getKey).toList();
        return new Resolution(keyword, List.of(), false, suggestions);
    }

    private static Set<Character> toCharSet(String s) {
        Set<Character> set = new HashSet<>();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                set.add(c);
            }
        }
        return set;
    }
}
