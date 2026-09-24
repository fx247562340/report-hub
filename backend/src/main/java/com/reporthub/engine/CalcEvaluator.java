package com.reporthub.engine;

import java.util.List;
import java.util.Map;

/**
 * Safe arithmetic / comparison evaluator for report calc fields.
 * Variables = column keys. Ops: + - * / % ( ) &gt; &lt; &gt;= &lt;= == != and/or not
 * Functions: abs(x), round(x,n), if(cond,a,b), coalesce(a,b,...)
 */
public final class CalcEvaluator {

    public record CalcField(String key, String label, String kind, String formula, String when, String then, String otherwise, Integer precision) {
        public static CalcField formula(String key, String label, String formula, Integer precision) {
            return new CalcField(key, label, "formula", formula, null, null, null, precision);
        }
        public static CalcField condition(String key, String label, String when, String then, String otherwise) {
            return new CalcField(key, label, "condition", null, when, then, otherwise, null);
        }
    }

    private CalcEvaluator() {}

    public static Object evaluate(CalcField calc, Map<String, Object> row) {
        if (calc == null) return null;
        if ("condition".equalsIgnoreCase(calc.kind())) {
            boolean ok = toBool(eval(calc.when(), row));
            String v = ok ? calc.then() : calc.otherwise();
            return v;
        }
        Object raw = eval(calc.formula(), row);
        if (raw instanceof Number n) {
            int p = calc.precision() == null ? 3 : calc.precision();
            double d = n.doubleValue();
            double m = Math.pow(10, p);
            return Math.round(d * m) / m;
        }
        return raw;
    }

    // ---- tokenizer ----
    private enum T { NUM, ID, OP, LP, RP, COMMA, END }

    private record Tok(T type, String text) {}

    private static List<Tok> lex(String s) {
        List<Tok> out = new java.util.ArrayList<>();
        int i = 0;
        if (s == null) return out;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '(') { out.add(new Tok(T.LP, "(")); i++; continue; }
            if (c == ')') { out.add(new Tok(T.RP, ")")); i++; continue; }
            if (c == ',') { out.add(new Tok(T.COMMA, ",")); i++; continue; }
            if (Character.isDigit(c) || (c == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
                int j = i + 1;
                while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++;
                out.add(new Tok(T.NUM, s.substring(i, j)));
                i = j;
                continue;
            }
            if (Character.isLetter(c) || c == '_' || c == '.') {
                int j = i + 1;
                while (j < s.length()) {
                    char d = s.charAt(j);
                    if (Character.isLetterOrDigit(d) || d == '_' || d == '.') j++;
                    else break;
                }
                out.add(new Tok(T.ID, s.substring(i, j)));
                i = j;
                continue;
            }
            // operators
            String two = i + 1 < s.length() ? s.substring(i, i + 2) : String.valueOf(c);
            if (two.equals(">=") || two.equals("<=") || two.equals("==") || two.equals("!=") || two.equals("&&") || two.equals("||")) {
                out.add(new Tok(T.OP, two));
                i += 2;
                continue;
            }
            if ("+-*/%><!".indexOf(c) >= 0) {
                out.add(new Tok(T.OP, String.valueOf(c)));
                i++;
                continue;
            }
            throw new IllegalArgumentException("非法字符: " + c);
        }
        out.add(new Tok(T.END, ""));
        return out;
    }

    private static Object eval(String expr, Map<String, Object> row) {
        List<Tok> toks = lex(expr);
        Parser p = new Parser(toks, row);
        Object v = p.parseExpr();
        p.expect(T.END);
        return v;
    }

    private static final class Parser {
        private final List<Tok> toks;
        private final Map<String, Object> row;
        private int i = 0;

        Parser(List<Tok> toks, Map<String, Object> row) {
            this.toks = toks;
            this.row = row;
        }

        Tok peek() { return toks.get(i); }
        Tok next() { return toks.get(i++); }
        void expect(T t) {
            if (peek().type() != t) throw new IllegalArgumentException("表达式语法错误");
            next();
        }

        Object parseExpr() { return parseOr(); }

        Object parseOr() {
            Object l = parseAnd();
            while (peek().type() == T.OP && peek().text().equals("||")) {
                next();
                Object r = parseAnd();
                l = toBool(l) || toBool(r);
            }
            return l;
        }

        Object parseAnd() {
            Object l = parseCmp();
            while (peek().type() == T.OP && peek().text().equals("&&")) {
                next();
                Object r = parseCmp();
                l = toBool(l) && toBool(r);
            }
            return l;
        }

        Object parseCmp() {
            Object l = parseAdd();
            while (peek().type() == T.OP) {
                String op = peek().text();
                if (!(op.equals(">") || op.equals("<") || op.equals(">=") || op.equals("<=")
                        || op.equals("==") || op.equals("!="))) break;
                next();
                Object r = parseAdd();
                l = compare(l, r, op);
            }
            return l;
        }

        Object parseAdd() {
            Object l = parseMul();
            while (peek().type() == T.OP && (peek().text().equals("+") || peek().text().equals("-"))) {
                String op = next().text();
                Object r = parseMul();
                double a = toNum(l), b = toNum(r);
                l = op.equals("+") ? a + b : a - b;
            }
            return l;
        }

        Object parseMul() {
            Object l = parseUnary();
            while (peek().type() == T.OP && ("*/%".contains(peek().text()))) {
                String op = next().text();
                Object r = parseUnary();
                double a = toNum(l), b = toNum(r);
                l = switch (op) {
                    case "*" -> a * b;
                    case "/" -> b == 0 ? null : a / b;
                    default -> b == 0 ? null : a % b;
                };
            }
            return l;
        }

        Object parseUnary() {
            if (peek().type() == T.OP && (peek().text().equals("-") || peek().text().equals("!"))) {
                String op = next().text();
                Object v = parseUnary();
                return op.equals("-") ? -toNum(v) : !toBool(v);
            }
            return parsePrimary();
        }

        Object parsePrimary() {
            Tok t = peek();
            if (t.type() == T.NUM) {
                next();
                return Double.parseDouble(t.text());
            }
            if (t.type() == T.LP) {
                next();
                Object v = parseExpr();
                expect(T.RP);
                return v;
            }
            if (t.type() == T.ID) {
                next();
                String name = t.text();
                if (peek().type() == T.LP) {
                    next();
                    java.util.List<Object> args = new java.util.ArrayList<>();
                    if (peek().type() != T.RP) {
                        args.add(parseExpr());
                        while (peek().type() == T.COMMA) {
                            next();
                            args.add(parseExpr());
                        }
                    }
                    expect(T.RP);
                    return call(name, args);
                }
                return row.get(name);
            }
            throw new IllegalArgumentException("表达式语法错误: " + t.text());
        }

        Object call(String name, List<Object> args) {
            switch (name.toLowerCase()) {
                case "abs" -> { return Math.abs(toNum(args.get(0))); }
                case "round" -> {
                    double v = toNum(args.get(0));
                    int p = args.size() > 1 ? (int) toNum(args.get(1)) : 0;
                    double m = Math.pow(10, p);
                    return Math.round(v * m) / m;
                }
                case "if" -> { return toBool(args.get(0)) ? args.get(1) : args.get(2); }
                case "coalesce" -> {
                    for (Object a : args) if (a != null && !String.valueOf(a).isBlank()) return a;
                    return null;
                }
                case "min" -> { return Math.min(toNum(args.get(0)), toNum(args.get(1))); }
                case "max" -> { return Math.max(toNum(args.get(0)), toNum(args.get(1))); }
                default -> throw new IllegalArgumentException("未知函数: " + name);
            }
        }

        Object compare(Object l, Object r, String op) {
            if (l == null || r == null) {
                return switch (op) {
                    case "==" -> l == null && r == null;
                    case "!=" -> !(l == null && r == null);
                    default -> false;
                };
            }
            if (l instanceof Number || r instanceof Number) {
                double a = toNum(l), b = toNum(r);
                return switch (op) {
                    case ">" -> a > b;
                    case "<" -> a < b;
                    case ">=" -> a >= b;
                    case "<=" -> a <= b;
                    case "==" -> a == b;
                    default -> a != b;
                };
            }
            int c = String.valueOf(l).compareTo(String.valueOf(r));
            return switch (op) {
                case ">" -> c > 0;
                case "<" -> c < 0;
                case ">=" -> c >= 0;
                case "<=" -> c <= 0;
                case "==" -> c == 0;
                default -> c != 0;
            };
        }
    }

    private static double toNum(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        String s = String.valueOf(v).trim();
        return !s.isEmpty() && !"0".equals(s) && !"false".equalsIgnoreCase(s) && !"否".equals(s);
    }
}
