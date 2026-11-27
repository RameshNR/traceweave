package com.example.flowtracker.model;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pasted or typed data as a value with fields, whatever form it has: JSON, TraceWeave's text ("name = value {Type}",
 * one field a line, indented), or a Java toString() ("BookingDto{reference='BK-1', trip=TripDto{…}}", Lombok's
 * "BookingDto(reference=BK-1, …)", a map's "{a=1, b=2}", a list's "[x, y]"). Other text isn't data: null.
 */
public final class FlowDataParse {
    private FlowDataParse() {}

    /** What the text is, for the user: "JSON", "Text (name = value)", "toString()", or "" when it is just text. */
    public static String kind(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) return "";
        if (FlowValueText.fromJson("v", t) != null) return "JSON";
        if (fromValueText("v", t) != null) return "Text (name = value)";
        if (fromToString("v", t) != null) return "toString()";
        return "";
    }

    /** The data as a value (named {@code name}), or null when it is just text. */
    public static @Nullable FlowValue parse(String name, String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.strip();
        FlowValue v = FlowValueText.fromJson(name, t);
        if (v == null) v = fromValueText(name, t);
        if (v == null) v = fromToString(name, t);
        return v;
    }

    // ---------- name = value {Type}, one a line ----------

    private static final Pattern LINE = Pattern.compile("^(\\S(?:.*?\\S)?)\\s*=(?:\\s(.*))?$");
    private static final Pattern WITH_TYPE = Pattern.compile("^(.*?)\\s*\\{([^{}]*)}$");

    /** Lines of "name = value  {Type}", nested by indentation (any width); null when the text isn't that. */
    static @Nullable FlowValue fromValueText(String name, String text) {
        List<FlowValue> tops = new ArrayList<>();
        List<int[]> indents = new ArrayList<>();
        List<FlowValue> open = new ArrayList<>();
        int lines = 0;
        for (String raw : text.split("\\R")) {
            if (raw.isBlank()) continue;
            String line = raw.replace("\t", "    ");
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            Matcher m = LINE.matcher(line.strip());
            if (!m.matches()) return null;
            String key = m.group(1).strip();
            if (key.contains("{") || key.contains("\"") || key.length() > 120) return null;
            FlowValue v = new FlowValue(key);
            String rest = m.group(2) == null ? "" : m.group(2).strip();
            Matcher typed = WITH_TYPE.matcher(rest);
            if (typed.matches()) {
                v.value = typed.group(1).strip();
                v.type = typed.group(2).strip();
            } else {
                v.value = rest;
            }
            while (!open.isEmpty() && indents.get(indents.size() - 1)[0] >= indent) {
                open.remove(open.size() - 1);
                indents.remove(indents.size() - 1);
            }
            if (open.isEmpty()) tops.add(v);
            else open.get(open.size() - 1).children.add(v);
            open.add(v);
            indents.add(new int[]{indent});
            lines++;
        }
        if (lines == 0) return null;
        if (tops.size() == 1) {
            FlowValue top = tops.get(0);
            top.name = name;
            return top;
        }
        FlowValue all = new FlowValue(name);
        all.children.addAll(tops);
        return all;
    }

    // ---------- toString() ----------

    /** A Java toString() as a value; null when the text isn't one (at least one "name=value" inside brackets). */
    static @Nullable FlowValue fromToString(String name, String text) {
        String t = text.strip();
        if (!(t.endsWith("}") || t.endsWith(")") || t.endsWith("]"))) return null;
        try {
            Reader r = new Reader(t);
            FlowValue v = r.value(name);
            r.skipSpaces();
            if (!r.done() || !r.sawPair && v.children.isEmpty()) return null;
            if (v.children.isEmpty()) return null;
            return v;
        } catch (RuntimeException notOne) {
            return null;
        }
    }

    private static final class Reader {
        private final String s;
        private int i;
        boolean sawPair;

        Reader(String s) {
            this.s = s;
        }

        boolean done() {
            return i >= s.length();
        }

        void skipSpaces() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        FlowValue value(String name) {
            skipSpaces();
            int start = i;
            // ClassName{…} / ClassName(…) / {…} / […]
            while (i < s.length() && (Character.isJavaIdentifierPart(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '$')) i++;
            String type = s.substring(start, i);
            skipSpaces();
            if (i < s.length() && (s.charAt(i) == '{' || s.charAt(i) == '(') && (type.isEmpty() || Character.isJavaIdentifierStart(type.charAt(0)))) {
                char close = s.charAt(i) == '{' ? '}' : ')';
                int save = i;
                try {
                    return object(name, type, close);
                } catch (RuntimeException notAnObject) {
                    i = save; // e.g. "Text(with brackets)" that isn't name=value inside: a plain value
                }
            }
            if (i < s.length() && s.charAt(i) == '[' && type.isEmpty()) return list(name);
            i = start;
            return scalar(name);
        }

        private FlowValue object(String name, String type, char close) {
            i++; // { or (
            FlowValue v = new FlowValue(name);
            v.type = type.contains(".") ? type.substring(type.lastIndexOf('.') + 1) : type;
            skipSpaces();
            if (i < s.length() && s.charAt(i) == close) {
                i++;
                return v;
            }
            while (true) {
                skipSpaces();
                int k = i;
                while (i < s.length() && s.charAt(i) != '=' && s.charAt(i) != ',' && s.charAt(i) != close && s.charAt(i) != ':') i++;
                if (i >= s.length() || s.charAt(i) != '=' && s.charAt(i) != ':') throw new IllegalStateException("not name=value");
                String key = s.substring(k, i).strip();
                if (key.isEmpty() || key.contains(" ") && !key.matches("[\\w.$ -]+")) throw new IllegalStateException("not a name");
                i++; // =
                sawPair = true;
                v.children.add(value(key));
                skipSpaces();
                if (i >= s.length()) throw new IllegalStateException("not closed");
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == close) {
                    i++;
                    break;
                }
                throw new IllegalStateException("unexpected " + c);
            }
            v.value = "";
            return v;
        }

        private FlowValue list(String name) {
            i++; // [
            FlowValue v = new FlowValue(name);
            v.type = "List";
            skipSpaces();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                v.value = "size = 0";
                return v;
            }
            while (true) {
                v.children.add(value(String.valueOf(v.children.size())));
                skipSpaces();
                if (i >= s.length()) throw new IllegalStateException("not closed");
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == ']') {
                    i++;
                    break;
                }
                throw new IllegalStateException("unexpected " + c);
            }
            v.value = "size = " + v.children.size();
            return v;
        }

        /** Up to the next , ] ) or } that isn't inside quotes or brackets. */
        private FlowValue scalar(String name) {
            skipSpaces();
            FlowValue v = new FlowValue(name);
            if (i < s.length() && (s.charAt(i) == '\'' || s.charAt(i) == '"')) {
                char q = s.charAt(i);
                int start = ++i;
                while (i < s.length() && s.charAt(i) != q) {
                    if (s.charAt(i) == '\\') i++;
                    i++;
                }
                String inner = s.substring(start, Math.min(i, s.length()));
                i++;
                v.value = "\"" + inner.replace("\"", "\\\"") + "\"";
                v.type = "String";
                return v;
            }
            int start = i, depth = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '(' || c == '[' || c == '{') depth++;
                else if (c == ')' || c == ']' || c == '}') {
                    if (depth == 0) break;
                    depth--;
                } else if (c == ',' && depth == 0) break;
                i++;
            }
            String raw = s.substring(start, i).strip();
            boolean literal = raw.matches("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?") || raw.equals("true") || raw.equals("false") || raw.equals("null");
            v.value = literal ? raw : "\"" + raw.replace("\"", "\\\"") + "\"";
            v.type = literal ? FlowValueText.guessType(raw) : "String";
            return v;
        }
    }
}
