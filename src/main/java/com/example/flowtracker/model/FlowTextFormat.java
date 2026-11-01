package com.example.flowtracker.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pasted data, made ready to compare line by line: what kind it is (JSON, XML, a Java object's toString() or the
 * debugger's text, or plain text) and the same data laid out one value per line, so two pastes line up whatever
 * their original formatting. Instance ids such as HotelService@1141 are left out; JSON keys are sorted.
 */
public final class FlowTextFormat {
    private FlowTextFormat() {}

    /** The kind of the data, its file extension (for highlighting), and the data one value per line. */
    public record Detected(String kind, String extension, String formatted) {}

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    public static Detected detect(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) return new Detected("Empty", "txt", "");
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                JsonElement json = new Gson().fromJson(t, JsonElement.class);
                if (json != null && (json.isJsonObject() || json.isJsonArray())) {
                    return new Detected("JSON", "json", PRETTY.toJson(sorted(json)) + "\n");
                }
            } catch (JsonParseException ignored) {
                // not JSON: maybe a toString()
            }
        }
        if (t.startsWith("<") && t.endsWith(">")) return new Detected("XML / HTML", "xml", xml(t));
        if (t.indexOf('{') >= 0 || t.indexOf('[') >= 0 || t.indexOf('(') >= 0 && t.contains("=")) {
            return new Detected("Object text (toString / debugger)", "txt", objectText(FlowValueDiff.withoutIds(t)));
        }
        StringBuilder sb = new StringBuilder();
        for (String line : t.split("\\R")) sb.append(line.stripTrailing()).append('\n');
        return new Detected("Text", "txt", sb.toString());
    }

    /** JSON with every object's keys in alphabetical order (arrays keep their order). */
    private static JsonElement sorted(JsonElement e) {
        if (e.isJsonObject()) {
            Map<String, JsonElement> keys = new TreeMap<>();
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) keys.put(en.getKey(), sorted(en.getValue()));
            JsonObject o = new JsonObject();
            keys.forEach(o::add);
            return o;
        }
        if (e.isJsonArray()) {
            JsonArray a = new JsonArray();
            for (JsonElement x : e.getAsJsonArray()) a.add(sorted(x));
            return a;
        }
        return e;
    }

    /** XML laid out one tag per line, indented by nesting. */
    static String xml(String t) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (String part : t.replaceAll(">\\s*<", ">\n<").split("\n")) {
            String p = part.strip();
            if (p.isEmpty()) continue;
            boolean closing = p.startsWith("</");
            boolean selfContained = p.endsWith("/>") || p.startsWith("<?") || p.startsWith("<!") || p.matches("<([\\w:.-]+)[^>]*>.*</\\1>");
            if (closing) depth = Math.max(0, depth - 1);
            sb.append("  ".repeat(depth)).append(p).append('\n');
            if (!closing && !selfContained && p.startsWith("<")) depth++;
        }
        return sb.toString();
    }

    /**
     * A toString() or the debugger's text (e.g. Booking{id=4, rooms={ooty=12, goa=9}, guests=[Ann, Bo]}) one value per
     * line: a line break after every { [ ( and comma, and before every } ] ), indented by nesting. Text in quotes stays
     * as it is.
     */
    static String objectText(String t) {
        StringBuilder sb = new StringBuilder();
        List<String> lines = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (quote != 0) {
                sb.append(c);
                if (c == '\\' && i + 1 < t.length()) sb.append(t.charAt(++i));
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                sb.append(c);
            } else if (c == '{' || c == '[' || c == '(') {
                sb.append(c);
                flush(sb, lines, depth);
                depth++;
            } else if (c == '}' || c == ']' || c == ')') {
                flush(sb, lines, depth);
                depth = Math.max(0, depth - 1);
                sb.append(c);
            } else if (c == ',') {
                sb.append(c);
                flush(sb, lines, depth);
            } else {
                sb.append(c);
            }
        }
        flush(sb, lines, depth);
        StringBuilder out = new StringBuilder();
        for (String l : lines) out.append(l).append('\n');
        return out.toString();
    }

    private static void flush(StringBuilder sb, List<String> lines, int depth) {
        String s = sb.toString().strip();
        sb.setLength(0);
        if (s.isEmpty()) return;
        // A closing bracket right after its value joins that line's end when it is alone (e.g. "}," stays on its own).
        lines.add("  ".repeat(depth) + s);
    }
}
