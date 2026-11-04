package com.example.flowtracker.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a language or its tools put inside a value that means nothing to people, and the value without it. One set of
 * rules for a value tree (Debug Data, Compare, Stop at Value) and for JSON (a stop's JSON, pasted JSON):
 * <ul>
 *   <li>JVM (Java, Kotlin, Scala, Groovy): a String's insides (value bytes, coder, hash) are its text; an enum's
 *       {name, ordinal} is its name; a BigDecimal's insides are its number; an ArrayList's {elementData, size} is its
 *       elements; a Kotlin lazy value is the value; fields the compiler or a framework adds (this$0, val$x, $outer,
 *       bitmap$0, x$delegate, $$_hibernate_interceptor, CGLIB$…, metaClass, serialVersionUID, modCount,
 *       hibernateLazyInitializer, Spring Data's _class) go.</li>
 *   <li>.NET: Json.NET's {"$id", "$values": […]} is its list; $id / $type / $ref go; an auto-property's backing field
 *       "&lt;Name&gt;k__BackingField" is "Name".</li>
 *   <li>Python and JavaScript: "__dunder__" names, __proto__, __typename and jsonpickle's "py/…" keys go.</li>
 * </ul>
 * A type the debugger shows by its text (String, Integer, LocalDate…) with fields under it loses those fields.
 */
public final class FlowInternals {
    private FlowInternals() {
    }

    /** An enum's fields: name and ordinal (and hash, which newer Java versions add). */
    private static final Set<String> ENUM_FIELDS = Set.of("name", "ordinal", "hash");

    private static boolean isEnum(Set<String> names) {
        return names.contains("name") && names.contains("ordinal") && ENUM_FIELDS.containsAll(names);
    }

    private static final Set<String> STRING_FIELDS = Set.of("value", "coder", "hash", "hashIsZero");
    private static final Set<String> DECIMAL_FIELDS = Set.of("intVal", "scale", "precision", "stringCache", "intCompact");
    private static final Set<String> ARRAY_LIST_FIELDS = Set.of("elementData", "size", "modCount");
    private static final Set<String> LAZY_FIELDS = Set.of("initializer", "_value", "lock");
    private static final Set<String> DROPPED = Set.of("metaClass", "serialVersionUID", "modCount", "hibernateLazyInitializer", "_class");
    private static final java.util.regex.Pattern BACKING = java.util.regex.Pattern.compile("<(.+)>k__BackingField");

    /** A field's name that a compiler, runtime or framework adds (not the app's data). */
    static boolean dropped(String name) {
        if (name == null || name.isEmpty()) return false;
        if (DROPPED.contains(name)) return true;
        if (name.startsWith("py/")) return true;                         // jsonpickle (Python)
        if (name.startsWith("__")) return true;                          // Python dunders, __proto__, __typename
        return name.contains("$") && !name.matches("\\[\\d+]");         // JVM synthetic fields, Json.NET's $id / $type / $ref
    }

    /** The name to show: a C# auto-property's backing field is the property. */
    static String shownName(String name) {
        java.util.regex.Matcher m = name == null ? null : BACKING.matcher(name);
        return m != null && m.matches() ? m.group(1) : name;
    }

    // ---------- a value tree ----------

    /** True when there is something to leave out anywhere in the value. */
    public static boolean has(@Nullable FlowValue v) {
        return v != null && clean(v) != v;
    }

    /**
     * The value without internals: a copy when there were some (only the parts that change are new), else the value
     * itself. The saved value is never changed.
     */
    public static FlowValue clean(FlowValue v) {
        FlowValue folded = fold(v);
        if (folded != null) return folded;
        if (v.children.isEmpty()) return v;
        boolean changed = false;
        List<FlowValue> kids = new ArrayList<>();
        // Json.NET's {"$id": "1", "$values": [...]}: the list
        FlowValue values = child(v, "$values");
        if (values != null && v.children.stream().allMatch(c -> c.name.startsWith("$"))) {
            FlowValue out = clean(values);
            FlowValue list = new FlowValue(v.name);
            list.type = v.type;
            list.value = out.value;
            list.children.addAll(out.children);
            return list;
        }
        for (FlowValue c : v.children) {
            if (dropped(c.name)) {
                changed = true;
                continue;
            }
            FlowValue cc = clean(c);
            String name = shownName(c.name);
            if (!name.equals(c.name)) {
                FlowValue renamed = new FlowValue(name);
                renamed.type = cc.type;
                renamed.value = cc.value;
                renamed.children.addAll(cc.children);
                cc = renamed;
            }
            changed |= cc != c;
            kids.add(cc);
        }
        if (!changed) return v;
        FlowValue out = new FlowValue(v.name);
        out.type = v.type;
        out.value = v.value;
        out.children.addAll(kids);
        if (out.value != null && out.value.startsWith("size = ") && !kids.isEmpty() && kids.size() != v.children.size()) {
            out.value = "size = " + kids.size();
        }
        return out;
    }

    /** A value that is one thing shown by its insides (a String, an enum, a number, a list, a lazy value), else null. */
    private static @Nullable FlowValue fold(FlowValue v) {
        if (v.children.isEmpty()) return null;
        Set<String> names = new java.util.HashSet<>();
        for (FlowValue c : v.children) names.add(c.name);
        String text = null, type = v.type;
        if (names.contains("value") && names.contains("coder") && STRING_FIELDS.containsAll(names)) {
            text = stringText(v);
            if (text != null && (type.isEmpty() || type.equals("Object"))) type = "String";
        } else if (isEnum(names)) { // an enum: its name
            if (!type.isEmpty() && !type.equals("Object") && v.value != null && !v.value.contains("@") && !v.value.startsWith("size =")) {
                FlowValue e = new FlowValue(v.name); // the debugger shows its name already
                e.type = type;
                e.value = v.value;
                return e;
            }
            FlowValue name = clean(child(v, "name"));
            if (name.children.isEmpty() && name.value != null && name.value.startsWith("\"")) text = unquote(name.value);
        } else if (names.contains("intCompact") && names.contains("scale") && DECIMAL_FIELDS.containsAll(names)) {
            text = decimal(child(v, "stringCache"), child(v, "intCompact"), child(v, "scale"));
            if (text != null && (type.isEmpty() || type.equals("Object"))) type = "BigDecimal";
        } else if (names.contains("elementData") && names.contains("size") && ARRAY_LIST_FIELDS.containsAll(names)) {
            FlowValue data = child(v, "elementData");
            int size = number(child(v, "size"));
            if (size >= 0 && size <= data.children.size()) {
                FlowValue list = new FlowValue(v.name);
                list.type = type.isEmpty() || type.equals("Object") ? "ArrayList" : type;
                list.value = "size = " + size;
                for (int i = 0; i < size; i++) list.children.add(clean(data.children.get(i)));
                return list;
            }
        } else if (names.contains("_value") && names.contains("initializer") && LAZY_FIELDS.containsAll(names)) {
            FlowValue inner = clean(child(v, "_value"));
            FlowValue lazy = new FlowValue(v.name);
            lazy.type = inner.type;
            lazy.value = inner.value;
            lazy.children.addAll(inner.children);
            return lazy;
        } else if (FlowValueShape.isPlainType(type)) {
            FlowValue plain = new FlowValue(v.name);
            plain.type = type;
            plain.value = v.value;
            return plain;
        }
        if (text == null) return null;
        FlowValue out = new FlowValue(v.name);
        out.type = type;
        out.value = type.equals("BigDecimal") ? text : "\"" + text + "\"";
        return out;
    }

    private static @Nullable FlowValue child(FlowValue v, String name) {
        for (FlowValue c : v.children) if (c.name.equals(name)) return c;
        return null;
    }

    private static String unquote(String s) {
        String t = s.strip();
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
    }

    private static int number(@Nullable FlowValue v) {
        try {
            return v == null ? -1 : Integer.parseInt(v.value.strip());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** A String's text from its insides: the text shown already, or its bytes decoded; null when unknown. */
    private static @Nullable String stringText(FlowValue v) {
        String shown = v.value == null ? "" : v.value.strip();
        if (shown.length() >= 2 && shown.startsWith("\"") && shown.endsWith("\"")) return unquote(shown);
        FlowValue bytes = child(v, "value"), coder = child(v, "coder");
        if (bytes == null) return null;
        List<Integer> list = new ArrayList<>();
        try {
            if (!bytes.children.isEmpty()) {
                for (FlowValue b : bytes.children) list.add(Integer.parseInt(b.value.strip()));
            } else {
                List<Integer> parsed = byteText(unquote(bytes.value == null ? "" : bytes.value));
                if (parsed == null) return null;
                list = parsed;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return decode(list, coder != null && "1".equals(coder.value == null ? "" : coder.value.strip()));
    }

    /** A BigDecimal from its insides: the text it keeps, else its compact digits and scale; null when neither. */
    private static @Nullable String decimal(@Nullable FlowValue cache, @Nullable FlowValue compact, @Nullable FlowValue scale) {
        if (cache != null && cache.value != null && cache.value.strip().startsWith("\"")) return unquote(cache.value);
        try {
            long digits = Long.parseLong(compact.value.strip());
            if (digits == Long.MIN_VALUE) return null; // kept in intVal (a BigInteger): too big to read here
            return BigDecimal.valueOf(digits, Integer.parseInt(scale.value.strip())).toPlainString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "[66, 75, 45]" as numbers; null when it isn't that. */
    private static @Nullable List<Integer> byteText(String text) {
        String t = text.strip();
        if (!t.startsWith("[") || !t.endsWith("]")) return null;
        t = t.substring(1, t.length() - 1).strip();
        List<Integer> out = new ArrayList<>();
        try {
            if (!t.isEmpty()) for (String n : t.split(",")) out.add(Integer.parseInt(n.strip()));
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    private static String decode(List<Integer> list, boolean utf16) {
        byte[] raw = new byte[list.size()];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) (int) list.get(i);
        return new String(raw, utf16 ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1);
    }

    // ---------- JSON ----------

    /** JSON without internals; the JSON as it was when there were none (or it isn't JSON). */
    public static String cleanJson(String json) {
        if (json == null || json.isBlank()) return json == null ? "" : json;
        try {
            JsonElement e = JsonParser.parseString(json);
            boolean[] changed = {false};
            JsonElement c = clean(e, changed);
            return changed[0] ? new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create().toJson(c) : json;
        } catch (RuntimeException notJson) {
            return json;
        }
    }

    private static JsonElement clean(JsonElement e, boolean[] changed) {
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement x : e.getAsJsonArray()) out.add(clean(x, changed));
            return out;
        }
        if (!e.isJsonObject()) return e;
        JsonObject o = e.getAsJsonObject();
        Set<String> names = o.keySet();
        if (names.contains("value") && names.contains("coder") && STRING_FIELDS.containsAll(names)) {
            List<Integer> bytes = jsonBytes(o.get("value"));
            if (bytes != null) {
                changed[0] = true;
                JsonElement coder = o.get("coder");
                return new JsonPrimitive(decode(bytes, coder.isJsonPrimitive() && "1".equals(coder.getAsString())));
            }
        }
        if (isEnum(names)) {
            JsonElement name = clean(o.get("name"), changed);
            if (name.isJsonPrimitive() && name.getAsJsonPrimitive().isString()) {
                changed[0] = true;
                return name;
            }
        }
        if (names.contains("intCompact") && names.contains("scale") && DECIMAL_FIELDS.containsAll(names)) {
            try {
                JsonElement cache = o.get("stringCache");
                if (cache != null && cache.isJsonPrimitive()) {
                    changed[0] = true;
                    return new JsonPrimitive(new BigDecimal(cache.getAsString()));
                }
                long digits = o.get("intCompact").getAsLong();
                if (digits != Long.MIN_VALUE) {
                    changed[0] = true;
                    return new JsonPrimitive(BigDecimal.valueOf(digits, o.get("scale").getAsInt()));
                }
            } catch (RuntimeException ignored) {
                // not a number after all: left as it is
            }
        }
        if (names.contains("elementData") && names.contains("size") && ARRAY_LIST_FIELDS.containsAll(names) && o.get("elementData").isJsonArray()) {
            try {
                int size = o.get("size").getAsInt();
                JsonArray data = o.getAsJsonArray("elementData");
                if (size >= 0 && size <= data.size()) {
                    changed[0] = true;
                    JsonArray list = new JsonArray();
                    for (int i = 0; i < size; i++) list.add(clean(data.get(i), changed));
                    return list;
                }
            } catch (RuntimeException ignored) {
                // not a size: left as it is
            }
        }
        if (names.contains("_value") && names.contains("initializer") && LAZY_FIELDS.containsAll(names)) {
            changed[0] = true;
            return clean(o.get("_value"), changed);
        }
        if (names.contains("$values") && names.stream().allMatch(n -> n.startsWith("$"))) {
            changed[0] = true;
            return clean(o.get("$values"), changed);
        }
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> en : o.entrySet()) {
            if (dropped(en.getKey())) {
                changed[0] = true;
                continue;
            }
            String name = shownName(en.getKey());
            if (!name.equals(en.getKey())) changed[0] = true;
            out.add(name, clean(en.getValue(), changed));
        }
        return out;
    }

    /** A String's bytes saved as a JSON array of numbers or as text "[66, 75…]"; null when it is neither. */
    private static @Nullable List<Integer> jsonBytes(JsonElement v) {
        try {
            if (v.isJsonArray()) {
                List<Integer> out = new ArrayList<>();
                for (JsonElement b : v.getAsJsonArray()) out.add(b.getAsInt());
                return out;
            }
            return v.isJsonPrimitive() ? byteText(v.getAsString()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
