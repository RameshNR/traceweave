package com.example.flowtracker.copy;

import com.example.flowtracker.code.FlowSync;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowTag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A flow's summary in plain sentences, written from what TraceWeave knows of it: its methods (in the flow's
 * order), the calls between them, the files and projects, and the notes and tags on its lines. It is put together
 * here, on this machine: no AI and nothing sent anywhere. E.g.
 * <pre>
 * The flow starts in BookingController.create() (BookingController.java, travel-api).
 * It calls BookingService.create(), which calls BookingDto.setReference() and TravelCodes.normalize().
 * Notes: BookingService.create(), line 31: "Storing the Data".
 * 5 lines in 4 methods, 4 files, 2 projects.
 * </pre>
 */
public final class FlowSummary {
    private FlowSummary() {
    }

    /** One method of the flow: its name as shown, where it is, and its lines. */
    private record Method(String key, String name, String fileName, String project, List<FlowEntry> lines) {}

    /**
     * @param entries the flow's lines, in its order
     * @param links   the calls between its methods ({@link FlowSync#link} of their keys)
     */
    public static String of(List<FlowEntry> entries, Set<String> links) {
        Map<String, Method> methods = new LinkedHashMap<>();
        for (FlowEntry e : entries) {
            String key = FlowSync.methodKey(e);
            methods.computeIfAbsent(key, k -> new Method(k, name(e), e.fileName, e.projectName == null ? "" : e.projectName,
                    new ArrayList<>())).lines().add(e);
        }
        if (methods.isEmpty()) return "The flow is empty: add lines to it (the + in the editor's gutter) to get a summary.";
        // who calls whom, among the flow's methods (in the flow's order)
        Map<String, List<String>> calls = new LinkedHashMap<>();
        Set<String> called = new LinkedHashSet<>();
        for (String link : links) {
            String[] ends = FlowSync.parseLink(link);
            if (ends == null || !methods.containsKey(ends[0]) || !methods.containsKey(ends[1]) || ends[0].equals(ends[1])) continue;
            calls.computeIfAbsent(ends[0], k -> new ArrayList<>()).add(ends[1]);
            called.add(ends[1]);
        }
        List<String> order = new ArrayList<>(methods.keySet());
        calls.values().forEach(list -> list.sort(java.util.Comparator.comparingInt(order::indexOf)));

        StringBuilder out = new StringBuilder();
        Set<String> told = new LinkedHashSet<>();
        boolean first = true;
        for (String key : order) {
            if (called.contains(key) || told.contains(key)) continue; // starts only: where nothing in the flow calls it
            Method m = methods.get(key);
            out.append(first ? "The flow starts in " : "Separately, ").append(m.name()).append(" (").append(where(m)).append(").");
            first = false;
            told.add(key);
            tell(key, methods, calls, told, out, 0);
            out.append('\n');
        }
        for (String key : order) { // what is left (only reached through calls already told): a loop, e.g.
            if (told.contains(key)) continue;
            Method m = methods.get(key);
            out.append("Also in the flow: ").append(m.name()).append(" (").append(where(m)).append(").\n");
            told.add(key);
        }

        // notes and tags, as written on the lines
        List<String> notes = new ArrayList<>(), tags = new ArrayList<>();
        for (Method m : methods.values()) {
            for (FlowEntry e : m.lines()) {
                if (e.note != null && !e.note.isBlank()) notes.add(m.name() + ", line " + e.line + ": “" + e.note.strip() + "”");
                FlowTag t = FlowTag.of(e);
                if (t != null) tags.add(t.label + " at " + m.name() + ", line " + e.line);
            }
        }
        if (!notes.isEmpty()) out.append("Notes: ").append(String.join("; ", notes)).append(".\n");
        if (!tags.isEmpty()) out.append("Tagged: ").append(String.join("; ", tags)).append(".\n");

        Set<String> files = new LinkedHashSet<>(), projects = new LinkedHashSet<>();
        for (Method m : methods.values()) {
            files.add(m.fileName());
            if (!m.project().isEmpty()) projects.add(m.project());
        }
        out.append(count(entries.size(), "line")).append(" in ").append(count(methods.size(), "method")).append(", ")
                .append(count(files.size(), "file"))
                .append(projects.isEmpty() ? "" : ", " + (projects.size() == 1 ? "in " + projects.iterator().next() : count(projects.size(), "project")))
                .append('.');
        return out.toString().strip();
    }

    /** "It calls X, which calls Y and Z." for a method's calls, one sentence per level (each method told once). */
    private static void tell(String key, Map<String, Method> methods, Map<String, List<String>> calls, Set<String> told,
                             StringBuilder out, int depth) {
        List<String> next = new ArrayList<>();
        for (String c : calls.getOrDefault(key, List.of())) if (told.add(c)) next.add(c);
        if (next.isEmpty() || depth > 12) return;
        List<String> names = next.stream().map(c -> methods.get(c).name()).toList();
        out.append(depth == 0 ? " It calls " : " " + methods.get(key).name() + " calls ").append(join(names)).append('.');
        for (String c : next) tell(c, methods, calls, told, out, depth + 1);
    }

    private static String name(FlowEntry e) {
        String method = FlowFormat.methodName(e);
        String cls = e.className == null || e.className.equals("-") ? "" : e.className.endsWith("Kt") ? "" : e.className;
        return cls.isEmpty() ? method : cls + "." + method;
    }

    private static String where(Method m) {
        return m.fileName() + (m.project().isEmpty() ? "" : ", " + m.project());
    }

    private static String join(List<String> names) {
        if (names.size() == 1) return names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    private static String count(int n, String what) {
        return n + " " + what + (n == 1 ? "" : "s");
    }
}
