package com.example.flowtracker.model;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Finding values in saved debug data (and in what the debugger shows now): a name, a map key, a type or a value
 * anywhere in the trees, e.g. the session attribute that holds a cart, or which object has the id 4711.
 */
public final class FlowValueSearch {
    private FlowValueSearch() {}

    /** What a match is in: a saved value, or (snapshot null) the current debug session's variables. */
    public record Match(@Nullable FlowSnapshot snapshot, FlowValue root, String path, String label, FlowValue value, String matched) {}

    /**
     * The test for a search text: text found anywhere (ignoring case), or a regular expression between slashes
     * ({@code /cart|basket/}). Null for an empty text; an error message for a broken expression is in {@link #error}.
     */
    public static @Nullable Pattern pattern(String query) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) return null;
        try {
            if (q.length() > 2 && q.startsWith("/") && q.endsWith("/")) {
                return Pattern.compile(q.substring(1, q.length() - 1), Pattern.CASE_INSENSITIVE);
            }
            return Pattern.compile(Pattern.quote(q), Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /** Why a search text isn't a valid regular expression, or null. */
    public static @Nullable String error(String query) {
        String q = query == null ? "" : query.strip();
        if (q.length() <= 2 || !q.startsWith("/") || !q.endsWith("/")) return null;
        try {
            Pattern.compile(q.substring(1, q.length() - 1));
            return null;
        } catch (PatternSyntaxException e) {
            return "Not a valid regular expression: " + e.getDescription();
        }
    }

    /** Every value in the tree whose name, type or value matches, in tree order (at most {@code limit}). */
    public static List<Match> find(@Nullable FlowSnapshot snapshot, FlowValue root, Pattern p, int limit, List<Match> out) {
        walk(snapshot, root, root, "", p, limit, out);
        return out;
    }

    private static void walk(@Nullable FlowSnapshot s, FlowValue root, FlowValue v, String path, Pattern p, int limit, List<Match> out) {
        if (out.size() >= limit) return;
        String what = p.matcher(v.name).find() ? "name" : p.matcher(v.value).find() ? "value" : p.matcher(v.type).find() ? "type" : null;
        if (what != null) out.add(new Match(s, root, path, FlowDebugStore.label(root, path), v, what));
        for (int i = 0; i < v.children.size() && out.size() < limit; i++) {
            walk(s, root, v.children.get(i), path.isEmpty() ? String.valueOf(i) : path + "." + i, p, limit, out);
        }
    }

    /** Search in all saved values, newest first. */
    public static List<Match> findInSnapshots(List<FlowSnapshot> snapshots, Pattern p, int limit) {
        List<Match> out = new ArrayList<>();
        for (FlowSnapshot s : snapshots) {
            find(s, s.value, p, limit, out);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** True when the text contains the search, for highlighting (case ignored). */
    public static boolean contains(String text, String query) {
        return !query.isBlank() && text.toLowerCase(Locale.ROOT).contains(query.strip().toLowerCase(Locale.ROOT));
    }
}
