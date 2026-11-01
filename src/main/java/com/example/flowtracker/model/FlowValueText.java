package com.example.flowtracker.model;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A saved debug value as text, to read, copy or export: JSON (objects as objects, maps as objects by key, lists as
 * arrays, numbers / booleans / null as such, instance ids like @1141 left out), or the plain tree the debugger
 * shows ("name = value {type}", indented).
 */
public final class FlowValueText {
    private FlowValueText() {}

    private static final Pattern INDEX = Pattern.compile("\\[?\\d+]?");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");

    // ---------- JSON ----------

    /** {"name": ...}: the value under its own name. */
    public static String toJson(FlowValue v) {
        return toJson(v, false);
    }

    /** @param sorted object keys in alphabetical order (to compare two values line by line, whatever the map order) */
    public static String toJson(FlowValue v, boolean sorted) {
        StringBuilder sb = new StringBuilder("{\n  ");
        string(sb, v.name);
        sb.append(": ");
        json(sb, v, 1, sorted);
        return sb.append("\n}\n").toString();
    }

    /** Just the value as JSON (without its name around it), e.g. to compare it with JSON pasted from elsewhere. */
    public static String valueJson(FlowValue v, boolean sorted) {
        StringBuilder sb = new StringBuilder();
        json(sb, v, 0, sorted);
        return sb.append('\n').toString();
    }

    private static void json(StringBuilder sb, FlowValue v, int indent, boolean sorted) {
        List<FlowValue> kids = new java.util.ArrayList<>(v.children.stream().filter(c -> !c.name.equals("…")).toList());
        if (kids.isEmpty()) {
            primitive(sb, v.value);
            return;
        }
        boolean entries = kids.stream().allMatch(FlowValueText::isMapEntry);
        boolean array = !entries && kids.stream().allMatch(c -> INDEX.matcher(c.name).matches());
        if (sorted && !array) kids.sort(java.util.Comparator.comparing(c -> entries ? unquote(child(c, "key").value) : c.name));
        sb.append(array ? "[" : "{");
        for (int i = 0; i < kids.size(); i++) {
            FlowValue c = kids.get(i);
            sb.append(i == 0 ? "\n" : ",\n").append("  ".repeat(indent + 1));
            if (entries) {
                string(sb, unquote(child(c, "key").value));
                sb.append(": ");
                json(sb, child(c, "value"), indent + 1, sorted);
            } else if (array) {
                json(sb, c, indent + 1, sorted);
            } else {
                string(sb, c.name);
                sb.append(": ");
                json(sb, c, indent + 1, sorted);
            }
        }
        sb.append('\n').append("  ".repeat(indent)).append(array ? "]" : "}");
    }

    /** A map: its children are all map entries (or, when it has none, its type says Map). */
    public static boolean isMap(FlowValue v) {
        if (v.children.isEmpty()) return v.type.contains("Map");
        return v.children.stream().filter(c -> !c.name.equals("…")).allMatch(FlowValueText::isMapEntry);
    }

    /** A list, set or array: its children are all indexes like 0 or [0] (or, without children, its type says so). */
    public static boolean isList(FlowValue v) {
        if (v.children.isEmpty()) return v.type.matches(".*(List|Set|\\[]|Collection|Queue|Deque).*");
        return !isMap(v) && v.children.stream().filter(c -> !c.name.equals("…")).allMatch(c -> INDEX.matcher(c.name).matches());
    }

    /** A map entry as the debugger shows it: an index with a "key" and a "value" child. */
    public static boolean isMapEntry(FlowValue v) {
        if (!INDEX.matcher(v.name).matches()) return false;
        boolean key = false, value = false;
        for (FlowValue c : v.children) {
            key |= c.name.equals("key");
            value |= c.name.equals("value");
        }
        return key && value;
    }

    private static FlowValue child(FlowValue v, String name) {
        for (FlowValue c : v.children) if (c.name.equals(name)) return c;
        return new FlowValue(name);
    }

    /** A leaf value: "text" as a string, a number, true / false / null as such, anything else as its text. */
    private static void primitive(StringBuilder sb, String value) {
        String v = FlowValueDiff.withoutIds(value).strip();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) string(sb, v.substring(1, v.length() - 1));
        else if (NUMBER.matcher(v).matches() || v.equals("true") || v.equals("false") || v.equals("null")) sb.append(v);
        else string(sb, v);
    }

    private static String unquote(String s) {
        String v = s.strip();
        return v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"") ? v.substring(1, v.length() - 1) : v;
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---------- plain text ----------

    /** The tree as the debugger shows it, one value per line, indented: "name = value  {type}". */
    public static String toText(FlowValue v) {
        StringBuilder sb = new StringBuilder();
        text(sb, v, 0);
        return sb.toString();
    }

    private static void text(StringBuilder sb, FlowValue v, int depth) {
        sb.append("    ".repeat(depth)).append(v.name).append(" = ").append(v.value);
        if (!v.type.isEmpty()) sb.append("  {").append(v.type).append('}');
        sb.append('\n');
        for (FlowValue c : v.children) text(sb, c, depth + 1);
    }

    // ---------- JSON back to a value ----------

    /**
     * Pasted JSON as a value tree (objects' keys and arrays' [i] as children), to compare it field by field; null
     * when the text isn't a JSON object or array.
     */
    public static @org.jetbrains.annotations.Nullable FlowValue fromJson(String name, String text) {
        try {
            com.google.gson.JsonElement e = com.google.gson.JsonParser.parseString(text.strip());
            if (!e.isJsonObject() && !e.isJsonArray()) return null;
            return value(name, e);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** The Java type a value typed by hand most likely has: "Ann" String, 12 Integer, 2.5 Double, true Boolean. */
    public static String guessType(String value) {
        String v = value.strip();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) return "String";
        if (v.matches("-?\\d{1,9}")) return "Integer";
        if (v.matches("-?\\d+")) return "Long";
        if (NUMBER.matcher(v).matches()) return "Double";
        if (v.equals("true") || v.equals("false")) return "Boolean";
        return "";
    }

    /** Types for a value made by hand where it has none: guessed for simple values, Object / List for the others. */
    public static void typeNew(FlowValue v) {
        for (FlowValue c : v.children) typeNew(c);
        if (!v.type.isEmpty()) return;
        if (v.children.isEmpty()) v.type = guessType(v.value);
        else if (isList(v)) v.type = "List";
        else if (!isMap(v)) v.type = "Object";
    }

    private static FlowValue value(String name, com.google.gson.JsonElement e) {
        FlowValue v = new FlowValue(name);
        if (e.isJsonObject()) {
            com.google.gson.JsonObject o = e.getAsJsonObject();
            v.value = "size = " + o.size();
            v.type = "Object";
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> en : o.entrySet()) v.children.add(value(en.getKey(), en.getValue()));
        } else if (e.isJsonArray()) {
            com.google.gson.JsonArray a = e.getAsJsonArray();
            v.value = "size = " + a.size();
            v.type = "List";
            for (int i = 0; i < a.size(); i++) v.children.add(value("[" + i + "]", a.get(i)));
        } else if (e.isJsonNull()) {
            v.value = "null";
        } else {
            com.google.gson.JsonPrimitive p = e.getAsJsonPrimitive();
            v.value = p.isString() ? "\"" + p.getAsString() + "\"" : p.getAsString();
            v.type = guessType(v.value);
        }
        return v;
    }

    // ---------- edited JSON or text back to a value ----------

    /**
     * JSON edited by hand, applied to the value it was made from: maps stay maps (entries matched by key, new keys
     * become entries), objects keep their fields' types and ids, lists keep their element names, and a value that
     * didn't change keeps exactly what the debugger showed. {"name": …} around it (as the JSON view shows it) is
     * taken off. Throws IllegalArgumentException with the reason when the text isn't JSON.
     */
    public static FlowValue applyJson(FlowValue old, String text) {
        com.google.gson.JsonElement e;
        try {
            e = com.google.gson.JsonParser.parseString(text.strip());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("It isn't valid JSON: " + reason(ex));
        }
        if (e.isJsonObject() && e.getAsJsonObject().size() == 1 && e.getAsJsonObject().has(old.name)) {
            e = e.getAsJsonObject().get(old.name);
        }
        return merge(old, old.name, e);
    }

    private static String reason(RuntimeException ex) {
        Throwable t = ex.getCause() != null ? ex.getCause() : ex;
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static FlowValue merge(@org.jetbrains.annotations.Nullable FlowValue old, String name, com.google.gson.JsonElement e) {
        FlowValue v = new FlowValue(name);
        if (old != null) v.type = old.type;
        if (e.isJsonObject()) {
            com.google.gson.JsonObject o = e.getAsJsonObject();
            if (old != null && isMap(old)) {
                boolean hasEntries = old.children.stream().anyMatch(FlowValueText::isMapEntry);
                boolean quoted = old.children.stream().filter(FlowValueText::isMapEntry)
                        .anyMatch(c -> child(c, "key").value.strip().startsWith("\""));
                int i = 0;
                for (String k : inOldOrder(o, old, true)) {
                    FlowValue was = null;
                    for (FlowValue c : old.children) {
                        if (isMapEntry(c) && unquote(child(c, "key").value).equals(k)) was = c;
                    }
                    FlowValue entry = new FlowValue(String.valueOf(i++));
                    FlowValue key = was != null ? child(was, "key").copy() : new FlowValue("key");
                    if (was == null) key.value = quoted || !hasEntries && !NUMBER.matcher(k).matches() ? "\"" + k + "\"" : k;
                    FlowValue value = merge(was == null ? null : child(was, "value"), "value", o.get(k));
                    if (was != null) entry.type = was.type;
                    else entriesLike(old, entry, key, value);
                    entry.value = was != null ? was.value : "";
                    entry.children.add(key);
                    entry.children.add(value);
                    v.children.add(entry);
                    fixEntryText(entry, old);
                }
                v.value = sized(old.value, v.children.size());
                return v;
            }
            for (String k : inOldOrder(o, old, false)) {
                FlowValue was = old == null ? null : named(old, k);
                v.children.add(merge(was, k, o.get(k)));
            }
            v.value = old != null && !old.children.isEmpty() ? old.value : ""; // an object keeps its {Type@id}
            if (old == null) v.type = "Object";
            return v;
        }
        if (e.isJsonArray()) {
            com.google.gson.JsonArray a = e.getAsJsonArray();
            boolean brackets = old == null || old.children.isEmpty() || old.children.get(0).name.startsWith("[");
            for (int i = 0; i < a.size(); i++) {
                FlowValue was = old != null && i < old.children.size() ? old.children.get(i) : null;
                v.children.add(merge(was, brackets ? "[" + i + "]" : String.valueOf(i), a.get(i)));
            }
            v.value = "size = " + a.size();
            if (old == null) v.type = "List";
            return v;
        }
        String now = e.isJsonNull() ? "null" : e.getAsJsonPrimitive().isString()
                ? "\"" + e.getAsJsonPrimitive().getAsString() + "\"" : e.getAsJsonPrimitive().getAsString();
        if (old != null && old.children.isEmpty()) {
            StringBuilder was = new StringBuilder();
            primitive(was, old.value);
            StringBuilder is = new StringBuilder();
            primitive(is, now);
            if (was.toString().equals(is.toString())) {
                v.value = old.value; // unchanged: keep what the debugger showed
                return v;
            }
        }
        v.value = now;
        if (v.type.isEmpty()) v.type = guessType(now);
        return v;
    }

    private static final Pattern ENTRY_TYPE = Pattern.compile(" -> (\\{[^{}]*} )");

    /**
     * A map entry's own text from its key and value, in the debugger's way ("ooty" -> {Integer@1155} 12): the
     * {Type} part it had stays (so an unchanged entry stays as it was, and Compare sees no change); a new entry takes
     * it from the map's other entries (without their ids), when they have one.
     */
    public static void fixEntryText(FlowValue entry, @org.jetbrains.annotations.Nullable FlowValue map) {
        FlowValue key = child(entry, "key"), value = child(entry, "value");
        java.util.regex.Matcher m = ENTRY_TYPE.matcher(entry.value);
        String part = m.find() ? m.group(1) : null;
        if (part == null && map != null && !entry.value.contains(" -> ")) {
            for (FlowValue c : map.children) {
                if (c == entry || !isMapEntry(c)) continue;
                java.util.regex.Matcher o = ENTRY_TYPE.matcher(c.value);
                if (o.find()) {
                    part = "{" + (value.type.isEmpty() ? FlowValueDiff.withoutIds(o.group(1)).replaceAll("[{} ]", "")
                            : value.type) + "} ";
                    break;
                }
            }
        }
        entry.value = key.value + " -> " + (part == null ? "" : part) + value.value;
    }

    /**
     * A new map entry looks like the map's other entries: the same entry, key and value types (e.g. HashMap$Node,
     * String, Integer), or a guessed one when the map has none yet.
     */
    public static void entriesLike(FlowValue map, FlowValue entry, FlowValue key, FlowValue value) {
        for (FlowValue c : map.children) {
            if (!isMapEntry(c)) continue;
            if (entry.type.isEmpty()) entry.type = c.type;
            if (key.type.isEmpty()) key.type = child(c, "key").type;
            if (value.type.isEmpty() && value.children.isEmpty() == child(c, "value").children.isEmpty()) value.type = child(c, "value").type;
        }
        typeNew(key);
        typeNew(value);
    }

    /**
     * The JSON object's keys in the order the value had them (JSON may show them sorted), then the new ones as they
     * come, so editing as JSON doesn't reorder anything.
     */
    private static List<String> inOldOrder(com.google.gson.JsonObject o, @org.jetbrains.annotations.Nullable FlowValue old, boolean mapKeys) {
        List<String> out = new java.util.ArrayList<>();
        if (old != null) {
            for (FlowValue c : old.children) {
                String k = mapKeys ? (isMapEntry(c) ? unquote(child(c, "key").value) : null) : c.name;
                if (k != null && o.has(k) && !out.contains(k)) out.add(k);
            }
        }
        for (String k : o.keySet()) if (!out.contains(k)) out.add(k);
        return out;
    }

    private static @org.jetbrains.annotations.Nullable FlowValue named(FlowValue v, String name) {
        for (FlowValue c : v.children) if (c.name.equals(name)) return c;
        return null;
    }

    /**
     * Text edited by hand (as the Text view shows it: "name = value  {type}", four spaces deeper for each level)
     * read back to a value. Throws IllegalArgumentException with the line when a line can't be read.
     */
    public static FlowValue fromText(String text) {
        FlowValue root = null;
        java.util.List<FlowValue> open = new java.util.ArrayList<>();
        String[] lines = text.split("\\R");
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            if (line.isBlank()) continue;
            int spaces = 0;
            while (spaces < line.length() && line.charAt(spaces) == ' ') spaces++;
            int depth = (spaces + 2) / 4;
            String body = line.strip();
            int eq = body.indexOf(" = ");
            if (eq < 0 && body.endsWith(" =")) eq = body.length() - 2;
            if (eq <= 0) throw new IllegalArgumentException("Line " + (n + 1) + " isn't \"name = value\": " + body);
            FlowValue v = new FlowValue(body.substring(0, eq).strip());
            String rest = eq + 3 <= body.length() ? body.substring(eq + 3) : "";
            java.util.regex.Matcher type = java.util.regex.Pattern.compile("^(.*?)\\s{2}\\{([^{}]*)}$").matcher(rest);
            if (type.matches()) {
                v.value = type.group(1);
                v.type = type.group(2);
            } else {
                v.value = rest;
            }
            if (root == null) {
                if (depth != 0) throw new IllegalArgumentException("Line " + (n + 1) + ": the first line must not be indented.");
                root = v;
                open.add(v);
                continue;
            }
            if (depth == 0) throw new IllegalArgumentException("Line " + (n + 1) + ": only one value at the top (indent it).");
            if (depth > open.size()) throw new IllegalArgumentException("Line " + (n + 1) + " is indented too far.");
            while (open.size() > depth) open.remove(open.size() - 1);
            open.get(open.size() - 1).children.add(v);
            open.add(v);
        }
        if (root == null) throw new IllegalArgumentException("There is nothing to read.");
        fixEntries(root);
        return root;
    }

    /**
     * Map entries' own text follows their key and value (new ones get the types of the map's other entries); lists'
     * and maps' "size = n" follows their children.
     */
    private static void fixEntries(FlowValue v) {
        for (FlowValue c : v.children) fixEntries(c);
        if (isMap(v)) {
            for (FlowValue e : v.children) {
                if (!isMapEntry(e)) continue;
                if (e.type.isEmpty() || child(e, "key").type.isEmpty() || child(e, "value").type.isEmpty()) {
                    entriesLike(v, e, child(e, "key"), child(e, "value"));
                }
                fixEntryText(e, v);
            }
        }
        if (!v.children.isEmpty() || isMap(v) || isList(v)) v.value = sized(v.value, v.children.size());
    }

    /** "size = 5" → "size = 6" (also inside a longer text); anything else stays. */
    public static String sized(String value, int size) {
        return value.matches("(?s).*\\bsize = \\d+.*") ? value.replaceFirst("\\bsize = \\d+", "size = " + size) : value;
    }

    /**
     * After text without instance ids (as Compare shows it) was read back: where a value is the same as before (ids
     * aside), it gets back exactly what the debugger showed, ids and all; types likewise.
     */
    public static void keepIds(FlowValue edited, FlowValue old) {
        if (FlowValueDiff.withoutIds(edited.value).equals(FlowValueDiff.withoutIds(old.value))) edited.value = old.value;
        if (FlowValueDiff.withoutIds(edited.type).equals(FlowValueDiff.withoutIds(old.type))) edited.type = old.type;
        for (FlowValue c : edited.children) {
            FlowValue was = null;
            for (FlowValue o : old.children) if (o.name.equals(c.name)) { was = o; break; }
            if (was != null) keepIds(c, was);
        }
    }
}
