package com.example.flowtracker.model;

import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A value as it is shown (Debug Data and Compare: Sort and Filter), never as it is saved: a copy whose fields are
 * sorted by name and / or only the ones that match a filter. Lists keep their order (their elements are numbered);
 * a field that matches keeps everything in it, and the fields around it stay so it can be seen where it is.
 */
public final class FlowValueShape {
    private FlowValueShape() {}

    public enum Sort {
        AS_SAVED("As Saved"), A_Z("A → Z"), Z_A("Z → A");
        public final String label;
        Sort(String label) { this.label = label; }
    }

    private static final Pattern INDEX = Pattern.compile("\\[?\\d+]?");

    /** True when nothing is sorted or filtered (the value is shown as it is saved). */
    // ---------- internals (a String's bytes, compiler fields…, in any language: see FlowInternals) ----------

    private static final String HIDE_KEY = "TraceWeave.hideJavaInternals.on";
    private static final java.util.Set<String> PLAIN = java.util.Set.of("String", "Integer", "Long", "Short", "Byte", "Double",
            "Float", "Boolean", "Character", "BigDecimal", "BigInteger", "LocalDate", "LocalDateTime", "LocalTime", "Instant",
            "ZonedDateTime", "OffsetDateTime", "Duration", "UUID", "StringBuilder", "StringBuffer", "Date", "Timestamp");

    /** True for a type shown by its text (e.g. "String", "java.lang.String", "java.math.BigDecimal"). */
    public static boolean isPlainType(@Nullable String type) {
        if (type == null || type.isEmpty()) return false;
        String t = type.strip();
        int generic = t.indexOf('<');
        if (generic >= 0) t = t.substring(0, generic);
        t = t.substring(t.lastIndexOf('.') + 1);
        return PLAIN.contains(t);
    }

    private static final String WHITESPACE_KEY = "TraceWeave.ignoreWhitespace";

    /** The setting: differences only in whitespace aren't shown (on by default), in Compare and the diffs. */
    public static boolean ignoreWhitespace() {
        return com.intellij.ide.util.PropertiesComponent.getInstance().getBoolean(WHITESPACE_KEY, true);
    }

    public static void setIgnoreWhitespace(boolean ignore) {
        com.intellij.ide.util.PropertiesComponent.getInstance().setValue(WHITESPACE_KEY, ignore, true);
    }

    /**
     * Text with its whitespace made alike, line by line (the indentation kept): spaces and tabs in a row are one
     * space, none at a line's end. Two texts that differ only in whitespace are then the same.
     */
    public static String withoutWhitespaceDifferences(@Nullable String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            int i = 0;
            while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
            if (out.length() > 0) out.append('\n');
            out.append(line, 0, i).append(line.substring(i).replaceAll("[ \\t]+", " ").stripTrailing());
        }
        return out.toString();
    }

    /** True when two values' texts differ only in whitespace (spaces, tabs, line ends, blank lines, indentation). */
    public static boolean sameIgnoringWhitespace(@Nullable String a, @Nullable String b) {
        if (a == null || b == null) return a == b;
        return a.replaceAll("\\s+", "").equals(b.replaceAll("\\s+", ""));
    }

    private static final com.google.gson.Gson PRETTY = new com.google.gson.GsonBuilder().setPrettyPrinting()
            .disableHtmlEscaping().serializeNulls().create();

    /**
     * A text for a diff with Ignore Whitespace on: JSON laid out the same way on both sides (indentation, line breaks,
     * blank lines don't count); other text without blank lines and with its spaces made alike.
     */
    public static String forDiff(@Nullable String text) {
        String t = text == null ? "" : text.strip();
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                return PRETTY.toJson(com.google.gson.JsonParser.parseString(t));
            } catch (RuntimeException notJson) {
                // as text
            }
        }
        StringBuilder out = new StringBuilder();
        for (String line : t.split("\\R")) {
            if (line.isBlank()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(withoutWhitespaceDifferences(line));
        }
        return out.toString();
    }

    /** The setting: Java internals hidden (on by default), in Debug Data and Compare. */
    public static boolean hideInternals() {
        return com.intellij.ide.util.PropertiesComponent.getInstance().getBoolean(HIDE_KEY, true);
    }

    public static void setHideInternals(boolean hide) {
        com.intellij.ide.util.PropertiesComponent.getInstance().setValue(HIDE_KEY, hide, true);
    }

    /** True when a value holds internals somewhere (any language: see {@link FlowInternals}). */
    public static boolean hasInternals(@Nullable FlowValue v) {
        return FlowInternals.has(v);
    }

    /** The value without internals: a copy when there were some, else the value. The saved value isn't changed. */
    public static FlowValue withoutInternals(FlowValue v) {
        return FlowInternals.clean(v);
    }

    /** JSON (a stop's, or pasted) without internals; the JSON as it was when there were none. */
    public static String withoutInternalsJson(String json) {
        return FlowInternals.cleanJson(json);
    }

    public static boolean plain(Sort sort, @Nullable String filter) {
        return sort == Sort.AS_SAVED && (filter == null || filter.isBlank());
    }

    /** The value sorted and filtered (a copy; the value itself is never changed). */
    public static FlowValue apply(FlowValue v, Sort sort, @Nullable String filter) {
        FlowValue out = v.copy();
        if (sort != Sort.AS_SAVED) sort(out, sort);
        String q = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        if (!q.isEmpty() && !matches(out, q)) keep(out, q);
        return out;
    }

    private static void sort(FlowValue v, Sort sort) {
        boolean list = !v.children.isEmpty() && v.children.stream().allMatch(c -> INDEX.matcher(c.name).matches());
        if (!list) {
            Comparator<FlowValue> byName = Comparator.comparing(c -> c.name.replace("\"", "").toLowerCase(Locale.ROOT));
            v.children.sort(sort == Sort.A_Z ? byName : byName.reversed());
        }
        for (FlowValue c : v.children) sort(c, sort);
    }

    /** Keeps the children that match or hold a match; true when anything is left. */
    private static boolean keep(FlowValue v, String q) {
        List<FlowValue> kids = v.children;
        kids.removeIf(c -> !(matches(c, q) || keep(c, q)));
        return !kids.isEmpty();
    }

    /** True when the field's name, value or type has the text. */
    static boolean matches(FlowValue v, String q) {
        return has(v.name, q) || has(v.value, q) || has(v.type, q);
    }

    private static boolean has(@Nullable String s, String q) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(q);
    }
}
