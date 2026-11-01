package com.example.flowtracker.model;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Two debug values side by side (a saved one and another saved one, or what the debugger shows now): every
 * field, entry and element, and whether it changed. What counts is the data, not the object:
 * <ul>
 *   <li>Instance ids the debugger shows ({@code HotelService@1141}, {@code {Double@1400} 2500.0}) are ignored: a
 *       new object with the same data is the same.</li>
 *   <li>Map entries are matched by their key, so an entry added to a map is one added entry, not every later
 *       entry "changed" because the indexes moved.</li>
 *   <li>List and array elements are lined up by their values, so an inserted element is one added element.</li>
 *   <li>Fields are matched by name.</li>
 * </ul>
 */
public final class FlowValueDiff {
    private FlowValueDiff() {}

    public enum Kind { SAME, CHANGED, ADDED, REMOVED }

    /**
     * One row: where it is (names from the root), what it is called here (a field name, or a map entry's key), its
     * value on the left and on the right (null where it doesn't exist), and how many levels deep it is.
     */
    public record Row(String label, String name, @Nullable String left, @Nullable String right, @Nullable String type, int depth, Kind kind,
                      @Nullable String leftPath, @Nullable String rightPath) {}

    /** An instance id after a type name, e.g. the "@1400" of "Double@1400". */
    private static final Pattern INSTANCE_ID = Pattern.compile("([A-Za-z_$][\\w$.]*)@\\d+");
    private static final Pattern INDEX = Pattern.compile("\\[?\\d+]?");

    /** A value or type without the debugger's instance ids, for comparing: "{Double@1400} 2500.0" → "{Double} 2500.0". */
    public static String withoutIds(@Nullable String text) {
        return text == null ? "" : INSTANCE_ID.matcher(text).replaceAll("$1");
    }

    /** All rows, in tree order (the left tree's order, with what only the right one has where it belongs). */
    public static List<Row> compare(FlowValue left, FlowValue right) {
        List<Row> out = new ArrayList<>();
        walk(left.name, left.name, left, "", right, "", 0, out);
        return out;
    }

    /** How many rows are not the same. */
    public static int differences(List<Row> rows) {
        int n = 0;
        for (Row r : rows) if (r.kind() != Kind.SAME) n++;
        return n;
    }

    /**
     * @param aPath where {@code a} is in the left value (child indexes, "" = the value itself; for a map entry, its
     *              "value"), so the row can be edited there; likewise {@code bPath}
     */
    private static void walk(String label, String name, @Nullable FlowValue a, @Nullable String aPath, @Nullable FlowValue b,
                             @Nullable String bPath, int depth, List<Row> out) {
        Kind kind = a == null ? Kind.ADDED : b == null ? Kind.REMOVED
                : same(a, b) ? Kind.SAME : Kind.CHANGED;
        String type = a != null && !a.type.isEmpty() ? withoutIds(a.type) : b != null ? withoutIds(b.type) : null;
        out.add(new Row(label, name, a == null ? null : a.value, b == null ? null : b.value, type, depth, kind,
                a == null ? null : aPath, b == null ? null : bPath));
        for (Pair p : pairs(a, b)) {
            FlowValue any = p.a() != null ? p.a() : p.b();
            String childName = shownName(any);
            // A map entry is shown as its key = its value: the entry's own "key" and "value" rows add nothing.
            walk(label + " › " + childName, childName, entryValue(p.a()), path(a, aPath, p.a()), entryValue(p.b()),
                    path(b, bPath, p.b()), depth + 1, out);
        }
    }

    /** Where a child is: its parent's path and its index (for a map entry, the path of its "value"). */
    private static @Nullable String path(@Nullable FlowValue parent, @Nullable String parentPath, @Nullable FlowValue child) {
        if (parent == null || parentPath == null || child == null) return null;
        int i = -1;
        for (int k = 0; k < parent.children.size(); k++) if (parent.children.get(k) == child) i = k;
        if (i < 0) return null;
        String p = parentPath.isEmpty() ? String.valueOf(i) : parentPath + "." + i;
        if (mapKey(child) != null) {
            for (int k = 0; k < child.children.size(); k++) if (child.children.get(k).name.equals("value")) return p + "." + k;
        }
        return p;
    }

    /** A map entry's "value" child (with the entry's name), anything else as it is. */
    private static @Nullable FlowValue entryValue(@Nullable FlowValue v) {
        if (v == null || mapKey(v) == null) return v;
        for (FlowValue c : v.children) {
            if (c.name.equals("value")) {
                FlowValue copy = new FlowValue(v.name);
                copy.type = c.type;
                copy.value = c.value;
                copy.children.addAll(c.children);
                return copy;
            }
        }
        return v;
    }

    /** True when both hold the same data (instance ids aside). */
    private static boolean same(FlowValue a, FlowValue b) {
        return withoutIds(a.value).equals(withoutIds(b.value)) && withoutIds(a.type).equals(withoutIds(b.type));
    }

    /** What a child is called in the comparison: a map entry by its key ("\"goa\""), anything else by its name. */
    private static String shownName(FlowValue v) {
        FlowValue key = mapKey(v);
        return key != null ? key.value : v.name;
    }

    /** The "key" child of a map entry (the debugger shows entries as "0" = "goa" -> 2500.0, with key and value). */
    private static @Nullable FlowValue mapKey(FlowValue v) {
        if (!INDEX.matcher(v.name).matches()) return null;
        for (FlowValue c : v.children) if (c.name.equals("key")) return c;
        return null;
    }

    private record Pair(@Nullable FlowValue a, @Nullable FlowValue b) {}

    /** The children of two values, paired: map entries by key, list elements by value, fields by name. */
    private static List<Pair> pairs(@Nullable FlowValue a, @Nullable FlowValue b) {
        List<FlowValue> left = a == null ? List.of() : a.children, right = b == null ? List.of() : b.children;
        if (left.isEmpty() && right.isEmpty()) return List.of();
        if (allMapEntries(left) && allMapEntries(right)) return byKey(left, right, true);
        if (allIndexes(left) && allIndexes(right)) return aligned(left, right);
        return byKey(left, right, false);
    }

    private static boolean allMapEntries(List<FlowValue> list) {
        for (FlowValue v : list) if (mapKey(v) == null) return false;
        return true;
    }

    private static boolean allIndexes(List<FlowValue> list) {
        for (FlowValue v : list) if (!INDEX.matcher(v.name).matches()) return false;
        return true;
    }

    /** Pairs by map key (or by name), left order first; what only the right has follows. */
    private static List<Pair> byKey(List<FlowValue> left, List<FlowValue> right, boolean mapKeys) {
        Map<String, FlowValue> l = keyed(left, mapKeys), r = keyed(right, mapKeys);
        List<Pair> out = new ArrayList<>();
        for (Map.Entry<String, FlowValue> e : l.entrySet()) out.add(new Pair(e.getValue(), r.get(e.getKey())));
        for (Map.Entry<String, FlowValue> e : r.entrySet()) if (!l.containsKey(e.getKey())) out.add(new Pair(null, e.getValue()));
        return out;
    }

    /** Children by key; a key used twice gets a counter so both are kept. */
    private static Map<String, FlowValue> keyed(List<FlowValue> list, boolean mapKeys) {
        Map<String, FlowValue> out = new LinkedHashMap<>();
        for (FlowValue c : list) {
            FlowValue k = mapKeys ? mapKey(c) : null;
            String key = k != null ? withoutIds(k.value) : c.name;
            String unique = key;
            for (int n = 2; out.containsKey(unique); n++) unique = key + "#" + n;
            out.put(unique, c);
        }
        return out;
    }

    /**
     * List elements lined up by their values (the longest common sequence), so an inserted or removed element is
     * one row; elements between two lined-up ones are paired in order as changed.
     */
    private static List<Pair> aligned(List<FlowValue> left, List<FlowValue> right) {
        int n = left.size(), m = right.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = signature(left.get(i)).equals(signature(right.get(j))) ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Pair> out = new ArrayList<>();
        List<FlowValue> onlyLeft = new ArrayList<>(), onlyRight = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && signature(left.get(i)).equals(signature(right.get(j)))) {
                flush(onlyLeft, onlyRight, out);
                out.add(new Pair(left.get(i++), right.get(j++)));
            } else if (j < m && (i == n || lcs[i][j + 1] >= lcs[i + 1][j])) {
                onlyRight.add(right.get(j++));
            } else {
                onlyLeft.add(left.get(i++));
            }
        }
        flush(onlyLeft, onlyRight, out);
        return out;
    }

    /** Elements between two lined-up ones: paired in order (changed), the rest removed or added. */
    private static void flush(List<FlowValue> onlyLeft, List<FlowValue> onlyRight, List<Pair> out) {
        int k = 0;
        for (; k < Math.min(onlyLeft.size(), onlyRight.size()); k++) out.add(new Pair(onlyLeft.get(k), onlyRight.get(k)));
        for (int x = k; x < onlyLeft.size(); x++) out.add(new Pair(onlyLeft.get(x), null));
        for (int x = k; x < onlyRight.size(); x++) out.add(new Pair(null, onlyRight.get(x)));
        onlyLeft.clear();
        onlyRight.clear();
    }

    /** An element's data, for lining lists up: its value and its children's, without instance ids. */
    private static String signature(FlowValue v) {
        StringBuilder sb = new StringBuilder(withoutIds(v.value));
        for (FlowValue c : v.children) sb.append('|').append(c.name).append('=').append(withoutIds(c.value));
        return sb.toString();
    }
}
