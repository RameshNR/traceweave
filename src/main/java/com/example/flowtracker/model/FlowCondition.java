package com.example.flowtracker.model;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * A condition on a watched value, for Stop at Value: e.g. {@code seats > 2}, {@code status == CANCELLED && total > 10000},
 * {@code traveller.name contains "Rao"}, {@code reference != null}. Names are the value's fields (dots go deeper);
 * {@code it} (or {@code value}) is the value itself. Comparisons: == != > < >= <= and contains; numbers are compared as
 * numbers, the rest as text (an enum by its name); && (and), || (or), ! (not) and brackets join them.
 */
public final class FlowCondition {
    private final Node root;
    private final String text;

    private FlowCondition(Node root, String text) {
        this.root = root;
        this.text = text;
    }

    /** The condition read from text; IllegalArgumentException with the reason when it can't be read. */
    public static FlowCondition parse(String text) {
        Parser p = new Parser(tokens(text));
        Node n = p.or();
        if (!p.done()) throw new IllegalArgumentException("Unexpected “" + p.peek() + "”");
        return new FlowCondition(n, text.strip());
    }

    /** Why the text isn't a condition, or null when it is one. */
    public static @Nullable String error(String text) {
        if (text == null || text.isBlank()) return "Type a condition, e.g. seats > 2";
        try {
            parse(text);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /**
     * True when the value meets it. @param field a name's text in the value ("it" for the value itself): a string's
     * text (no quotes), a number, true / false, an enum's name, or null when it is null or there is no such field
     */
    public boolean test(Function<String, String> field) {
        return root.test(field);
    }

    @Override
    public String toString() {
        return text;
    }

    // ---------- the condition ----------

    private interface Node {
        boolean test(Function<String, String> field);
    }

    private record And(Node a, Node b) implements Node {
        public boolean test(Function<String, String> f) { return a.test(f) && b.test(f); }
    }

    private record Or(Node a, Node b) implements Node {
        public boolean test(Function<String, String> f) { return a.test(f) || b.test(f); }
    }

    private record Not(Node a) implements Node {
        public boolean test(Function<String, String> f) { return !a.test(f); }
    }

    /** name op literal (literal null when it is the word null). */
    private record Compare(String name, String op, @Nullable String literal) implements Node {
        public boolean test(Function<String, String> f) {
            String v = f.apply(name);
            if (literal == null) return op.equals("==") ? v == null : op.equals("!=") && v != null;
            if (v == null) return op.equals("!=");
            BigDecimal a = number(v), b = number(literal);
            int cmp = a != null && b != null ? a.compareTo(b) : v.compareTo(literal);
            return switch (op) {
                case "==" -> a != null && b != null ? cmp == 0 : v.equals(literal);
                case "!=" -> a != null && b != null ? cmp != 0 : !v.equals(literal);
                case ">" -> cmp > 0;
                case "<" -> cmp < 0;
                case ">=" -> cmp >= 0;
                case "<=" -> cmp <= 0;
                case "contains" -> v.contains(literal);
                default -> false;
            };
        }
    }

    private static @Nullable BigDecimal number(String s) {
        try {
            return new BigDecimal(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------- reading it ----------

    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '"' || c == '\'') { // a quoted text: kept with its quote mark in front (it is a literal)
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                while (j < n && text.charAt(j) != c) {
                    if (text.charAt(j) == '\\' && j + 1 < n) j++;
                    sb.append(text.charAt(j++));
                }
                if (j >= n) throw new IllegalArgumentException("A text isn't closed: " + c + sb);
                out.add("\"" + sb);
                i = j + 1;
                continue;
            }
            String two = i + 1 < n ? text.substring(i, i + 2) : "";
            if (List.of("&&", "||", "==", "!=", ">=", "<=").contains(two)) { out.add(two); i += 2; continue; }
            if ("()><!".indexOf(c) >= 0) { out.add(String.valueOf(c)); i++; continue; }
            if (c == '=') { out.add("=="); i++; continue; } // a single = means the same
            int j = i;
            while (j < n && !Character.isWhitespace(text.charAt(j)) && "()<>=!&|\"'".indexOf(text.charAt(j)) < 0) j++;
            if (j == i) throw new IllegalArgumentException("Unexpected “" + c + "”");
            out.add(text.substring(i, j));
            i = j;
        }
        return out;
    }

    private static final class Parser {
        private final List<String> t;
        private int at;

        Parser(List<String> tokens) {
            t = tokens;
        }

        boolean done() { return at >= t.size(); }

        String peek() { return done() ? "the end" : t.get(at); }

        private boolean take(String... words) {
            if (done()) return false;
            for (String w : words) {
                if (t.get(at).equalsIgnoreCase(w)) {
                    at++;
                    return true;
                }
            }
            return false;
        }

        Node or() {
            Node n = and();
            while (take("||", "or")) n = new Or(n, and());
            return n;
        }

        Node and() {
            Node n = not();
            while (take("&&", "and")) n = new And(n, not());
            return n;
        }

        Node not() {
            if (take("!", "not")) return new Not(not());
            if (take("(")) {
                Node n = or();
                if (!take(")")) throw new IllegalArgumentException("A bracket isn't closed");
                return n;
            }
            return compare();
        }

        Node compare() {
            if (done()) throw new IllegalArgumentException("Something is missing at the end");
            String name = t.get(at++);
            if (name.startsWith("\"")) throw new IllegalArgumentException("A field's name goes first, e.g. status == " + name.substring(1));
            String op = done() ? "" : t.get(at).toLowerCase(Locale.ROOT);
            if (!List.of("==", "!=", ">", "<", ">=", "<=", "contains").contains(op)) {
                throw new IllegalArgumentException("After “" + name + "”: == != > < >= <= or contains");
            }
            at++;
            if (done()) throw new IllegalArgumentException("A value is missing after “" + op + "”");
            String lit = t.get(at++);
            String literal = lit.startsWith("\"") ? lit.substring(1) : lit.equals("null") ? null : lit;
            return new Compare(name.equals("value") ? "it" : name, op, literal);
        }
    }
}
