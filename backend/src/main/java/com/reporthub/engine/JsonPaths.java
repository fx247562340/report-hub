package com.reporthub.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/** Minimal JSON path: dotted keys and [i] indexes, e.g. data.records[0].id */
public final class JsonPaths {
    private JsonPaths() {}

    public static Object read(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) return null;
        JsonNode cur = root;
        for (String part : path.split("\\.")) {
            if (cur == null || cur.isMissingNode() || cur.isNull()) return null;
            String key = part;
            Integer index = null;
            int br = part.indexOf('[');
            if (br > 0 && part.endsWith("]")) {
                key = part.substring(0, br);
                index = Integer.parseInt(part.substring(br + 1, part.length() - 1));
            }
            if (!key.isEmpty()) {
                cur = cur.get(key);
            }
            if (index != null) {
                if (cur == null || !cur.isArray() || cur.size() <= index) return null;
                cur = cur.get(index);
            }
        }
        if (cur == null || cur.isMissingNode() || cur.isNull()) return null;
        if (cur.isValueNode()) {
            if (cur.isNumber()) return cur.numberValue();
            if (cur.isBoolean()) return cur.asBoolean();
            return cur.asText();
        }
        return cur.toString();
    }

    public static List<ObjectNode> toRowList(JsonNode root, String listPath) {
        JsonNode arr = listPath == null || listPath.isBlank()
                ? root
                : (JsonNode) readNode(root, listPath);
        if (arr == null) {
            if (root != null && root.isArray()) arr = root;
            else if (root != null && root.isObject()) arr = root;
        }
        List<ObjectNode> rows = new ArrayList<>();
        if (arr == null) return rows;
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                if (n.isObject()) rows.add((ObjectNode) n);
            }
        } else if (arr.isObject()) {
            rows.add((ObjectNode) arr);
        }
        return rows;
    }

    public static JsonNode readNode(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) return root;
        JsonNode cur = root;
        for (String part : path.split("\\.")) {
            if (cur == null || cur.isMissingNode()) return null;
            String key = part;
            Integer index = null;
            int br = part.indexOf('[');
            if (br > 0 && part.endsWith("]")) {
                key = part.substring(0, br);
                index = Integer.parseInt(part.substring(br + 1, part.length() - 1));
            }
            if (!key.isEmpty()) cur = cur.get(key);
            if (index != null) {
                if (cur == null || !cur.isArray() || cur.size() <= index) return null;
                cur = cur.get(index);
            }
        }
        return cur;
    }

    public static Map<String, Object> mapRow(JsonNode raw, List<FieldRule> rules) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (FieldRule rule : rules) {
            Object v = read(raw, rule.sourcePath());
            out.put(rule.targetField(), cast(v, rule.dataType(), rule.transform()));
        }
        return out;
    }

    public static Object cast(Object v, String type, String transform) {
        if (v == null) return null;
        String s = String.valueOf(v);
        if (transform != null) {
            switch (transform) {
                case "trim" -> s = s.trim();
                case "upper" -> s = s.toUpperCase();
                case "lower" -> s = s.toLowerCase();
                default -> {}
            }
        }
        // numeric/date from padded upstream strings
        s = s.trim();
        if (type == null || type.isBlank() || "string".equals(type)) return s;
        return switch (type) {
            case "number" -> {
                if (v instanceof Number n) yield n;
                try {
                    yield s.contains(".") ? Double.parseDouble(s) : Long.parseLong(s);
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            case "bool", "boolean" -> Boolean.parseBoolean(s);
            case "date" -> s;
            default -> s;
        };
    }

    public static String normKey(Object v) {
        if (v == null) return "";
        if (v instanceof Number n) {
            if (n.doubleValue() == Math.rint(n.doubleValue())) {
                return String.valueOf(n.longValue());
            }
            return String.valueOf(n.doubleValue());
        }
        return String.valueOf(v).trim();
    }

    public static ObjectNode deepCopyObject(ObjectNode src, ObjectMapper mapper) {
        return src.deepCopy();
    }

    public static ArrayNode emptyArray(ObjectMapper mapper) {
        return mapper.createArrayNode();
    }

    public record FieldRule(String sourcePath, String targetField, String dataType, String transform) {}
}
