package com.example.flowtracker.copy;

import com.example.flowtracker.code.FlowLang;
import com.example.flowtracker.code.FlowSync;
import com.example.flowtracker.diagram.FlowDiagram;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowTag;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Copy as Markdown": the saved lines as a .md document with a summary table, a Mermaid diagram (flowchart or
 * sequence diagram), and per project / file / method sections. Code goes in ```java / ```kotlin blocks so
 * Markdown viewers color it; methods are bold, notes are italic quotes, tags are emoji.
 */
public final class FlowMarkdown {
    private FlowMarkdown() {}

    /** One stretch of consecutive lines in the same method. */
    private record Run(String project, String fileUrl, String fileName, String className, String method,
                       List<FlowEntry> lines) {
        String key() { return fileUrl + "#" + method; }
    }

    /**
     * @param entries the lines to export, in the order they are shown
     * @param flat    true when exported from Flat View (the document then says it follows click order)
     * @param links   calls between saved methods (see {@link FlowSync#link}); only these get arrows
     * @param sequence true = draw a sequence diagram (classes talking to each other) instead of a flowchart
     */
    public static String toMarkdown(List<FlowEntry> entries, boolean flat, Set<String> links, boolean sequence) {
        return toMarkdown(entries, flat, links, sequence, "TraceWeave");
    }

    /** @param title the document heading (Copy All Flows uses the flow's name) */
    public static String toMarkdown(List<FlowEntry> entries, boolean flat, Set<String> links, boolean sequence,
                             String title) {
        List<FlowEntry> ordered = flat ? entries : groupedOrder(entries);
        List<Run> runs = runs(ordered);
        StringBuilder md = new StringBuilder();

        // ----- title + summary line -----
        Set<String> files = new LinkedHashSet<>();
        Set<String> projects = new LinkedHashSet<>();
        for (FlowEntry e : ordered) {
            files.add(e.fileUrl);
            projects.add(projectOf(e));
        }
        md.append("# 🧭 ").append(title).append("\n\n");
        md.append("> **").append(ordered.size()).append("** ").append(plural(ordered.size(), "line"))
                .append(" · **").append(files.size()).append("** ").append(plural(files.size(), "file"))
                .append(" · **").append(projects.size()).append("** ").append(plural(projects.size(), "project"))
                .append(" · _").append(flat ? "Flat View (flow order)" : "Grouped View").append("_")
                .append(" · copied ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                .append("\n\n");
        if (ordered.isEmpty()) return md.append("_Nothing tracked yet._\n").toString();

        // ----- summary table -----
        md.append("## 📋 Summary\n\n");
        md.append("| Project | File | Methods | Lines |\n");
        md.append("|---|---|---|---:|\n");
        for (FlowFormat.FileGroup f : FlowFormat.group(ordered)) {
            int lines = f.methods().values().stream().mapToInt(List::size).sum();
            List<String> methods = new ArrayList<>();
            for (String m : f.methods().keySet()) methods.add("`" + cell(m) + "`");
            md.append("| ").append(cell(blankToDash(f.projectName()))).append(" | **").append(cell(f.fileName()))
                    .append("** | ").append(String.join(", ", methods)).append(" | ").append(lines).append(" |\n");
        }
        md.append('\n');

        // ----- flow diagrams: one per group of connected code, arrows only for real calls -----
        appendDiagrams(md, runs, links, sequence);

        // ----- details -----
        md.append("## 🧩 Details\n\n");
        String project = null, file = null;
        for (Run r : runs) {
            if (!r.project.equals(project)) {
                if (project != null) md.append("---\n\n");
                md.append("### 📦 ").append(r.project.isEmpty() ? "Project" : r.project).append("\n\n");
                file = null;
            }
            if (!r.fileUrl.equals(file)) {
                md.append("#### 📄 `").append(r.fileName).append("`");
                if (r.fileUrl.startsWith(FlowDiagram.DIAGRAM_URL)) md.append(" · _custom class, not in the code yet (added in the Flow Diagram)_");
                md.append("\n\n");
            }
            md.append("**▸ `").append(r.method).append("`**");
            if (r.lines.stream().allMatch(FlowDiagram::isDiagramOnly)) {
                md.append(" · _custom method, not in the code yet (added in the Flow Diagram)_");
            }
            md.append("\n\n");

            appendLineTable(md, r.lines);
            project = r.project;
            file = r.fileUrl;
        }
        return md.toString();
    }

    /** Grouped View order: files grouped by project (projects in order of first appearance). */
    private static List<FlowEntry> groupedOrder(Collection<FlowEntry> entries) {
        Map<String, List<FlowEntry>> byProject = new LinkedHashMap<>();
        for (FlowFormat.FileGroup f : FlowFormat.group(entries)) {
            List<FlowEntry> target = byProject.computeIfAbsent(blankToEmpty(f.projectName()), k -> new ArrayList<>());
            for (List<FlowEntry> lines : f.methods().values()) target.addAll(lines);
        }
        List<FlowEntry> out = new ArrayList<>();
        byProject.values().forEach(out::addAll);
        return out;
    }

    private static List<Run> runs(List<FlowEntry> ordered) {
        List<Run> runs = new ArrayList<>();
        Run current = null;
        for (FlowEntry e : ordered) {
            String method = FlowFormat.methodLabel(e);
            if (current == null || !current.fileUrl.equals(e.fileUrl) || !current.method.equals(method)
                    || !current.project.equals(projectOf(e))) {
                current = new Run(projectOf(e), e.fileUrl, e.fileName, classOf(e), method, new ArrayList<>());
                runs.add(current);
            }
            current.lines.add(e);
        }
        return runs;
    }

    private static String projectOf(FlowEntry e) { return blankToEmpty(e.projectName); }

    /** The class a line is in, for sequence diagram participants (the file name when unknown). */
    private static String classOf(FlowEntry e) {
        return e.className == null || e.className.isBlank() || e.className.equals("-")
                ? FlowLang.baseName(e.fileName) : e.className;
    }

    /** A method in the diagrams, with the participant (file + class) it belongs to. */
    private record Node(String key, String participant, String participantLabel, String fileName, String method,
                        String project) {}

    /**
     * The Mermaid diagram(s). Lines outside methods (fields, imports) are left out. Code that never calls
     * across (for example a Java service, a script and a tag library) gets one diagram per group, so
     * unrelated code isn't drawn as one flow. A group is the methods linked by calls, plus the other
     * methods of the same files.
     */
    private static void appendDiagrams(StringBuilder md, List<Run> runs, Set<String> links, boolean sequence) {
        Map<String, Node> nodes = new LinkedHashMap<>(); // flow order
        for (Run r : runs) {
            if (FlowFormat.isClassLevel(r.method) || nodes.containsKey(r.key())) continue;
            nodes.put(r.key(), new Node(r.key(), r.fileUrl + "#" + r.className, participantLabel(r), r.fileName, r.method,
                    r.project));
        }
        if (nodes.size() < 2) return;

        // Callees of each method, in call order (links keep each caller's calls in source order).
        Map<String, List<String>> calls = new LinkedHashMap<>();
        for (String link : links) {
            String[] ends = FlowSync.parseLink(link);
            if (ends == null || ends[0].equals(ends[1])) continue;
            if (!nodes.containsKey(ends[0]) || !nodes.containsKey(ends[1])) continue;
            calls.computeIfAbsent(ends[0], k -> new ArrayList<>()).add(ends[1]);
        }

        List<List<Node>> groups = groups(nodes, calls);
        md.append("## 🔀 Flow\n\n");
        if (groups.size() > 1) {
            md.append("_").append(groups.size()).append(" separate flows: the code in one doesn't call the code in another._\n\n");
        }
        for (int i = 0; i < groups.size(); i++) {
            List<Node> group = groups.get(i);
            if (groups.size() > 1) {
                Set<String> files = new LinkedHashSet<>();
                for (Node n : group) files.add(n.fileName);
                md.append("### ").append(i + 1).append(". ").append(String.join(", ", files)).append("\n\n");
            }
            if (sequence) appendSequence(md, group, calls);
            else appendFlowchart(md, group, calls);
        }
    }

    /** Groups of methods linked by calls or by sharing a file, each in flow order; groups in flow order too. */
    private static List<List<Node>> groups(Map<String, Node> nodes, Map<String, List<String>> calls) {
        Map<String, String> parent = new HashMap<>();
        for (String k : nodes.keySet()) parent.put(k, k);
        Map<String, String> firstInFile = new HashMap<>();
        for (Node n : nodes.values()) {
            String file = n.key.substring(0, n.key.lastIndexOf('#'));
            String other = firstInFile.putIfAbsent(file, n.key);
            if (other != null) union(parent, other, n.key);
        }
        calls.forEach((caller, callees) -> callees.forEach(callee -> union(parent, caller, callee)));

        Map<String, List<Node>> byRoot = new LinkedHashMap<>();
        for (Node n : nodes.values()) byRoot.computeIfAbsent(find(parent, n.key), k -> new ArrayList<>()).add(n);
        return new ArrayList<>(byRoot.values());
    }

    private static String find(Map<String, String> parent, String k) {
        while (!parent.get(k).equals(k)) k = parent.get(k);
        return k;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a), rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(rb, ra);
    }

    /** One box per method (file name and method), arrows only for real calls between them. */
    private static void appendFlowchart(StringBuilder md, List<Node> group, Map<String, List<String>> calls) {
        md.append("```mermaid\nflowchart TD\n");
        Map<String, String> ids = new LinkedHashMap<>();
        for (Node n : group) {
            String id = "m" + (ids.size() + 1);
            ids.put(n.key, id);
            // the project on top (as the project band of a box in the Flow Diagram), then the file and the method
            md.append("    ").append(id).append("[\"")
                    .append(n.project.isEmpty() ? "" : "📦 " + mermaid(n.project) + "<br/>")
                    .append(mermaid(n.fileName)).append("<br/><b>").append(mermaid(n.method)).append("</b>\"]\n");
        }
        for (Node n : group) {
            for (String callee : calls.getOrDefault(n.key, List.of())) {
                md.append("    ").append(ids.get(n.key)).append(" -->|calls| ").append(ids.get(callee)).append('\n');
            }
        }
        md.append("```\n\n");
    }

    /**
     * Sequence diagram: one participant per file (and class), built from the call graph rather than from
     * the order the lines are listed in. Each method that no other method here calls starts a chain (a note
     * over its file); from there every call is an arrow, in source order, followed by the callee's own calls
     * and a dashed return. A method called from several places gets an arrow each time; its own calls are
     * shown the first time only.
     */
    private static void appendSequence(StringBuilder md, List<Node> group, Map<String, List<String>> calls) {
        md.append("```mermaid\nsequenceDiagram\n");
        Map<String, Node> methods = new LinkedHashMap<>();
        Map<String, String> ids = new LinkedHashMap<>(); // participant -> id
        for (Node n : group) {
            methods.put(n.key, n);
            if (ids.containsKey(n.participant)) continue;
            String id = "p" + (ids.size() + 1);
            ids.put(n.participant, id);
            md.append("    participant ").append(id).append(" as ").append(seq(n.participantLabel)).append('\n');
        }
        Set<String> called = new HashSet<>();
        for (Node n : group) called.addAll(calls.getOrDefault(n.key, List.of()));

        Set<String> expanded = new HashSet<>();
        for (Node n : group) {
            if (!called.contains(n.key)) startChain(md, ids, methods, calls, expanded, n.key);
        }
        // Whatever is left is only reachable through a cycle.
        for (Node n : group) {
            if (!expanded.contains(n.key)) startChain(md, ids, methods, calls, expanded, n.key);
        }
        md.append("```\n\n");
    }

    private static void startChain(StringBuilder md, Map<String, String> ids, Map<String, Node> methods,
                                   Map<String, List<String>> calls, Set<String> expanded, String key) {
        Node n = methods.get(key);
        md.append("    Note over ").append(ids.get(n.participant)).append(": ").append(seq(n.method)).append('\n');
        expandCalls(md, ids, methods, calls, expanded, key);
    }

    /** Arrows for every call the method makes (each followed by the callee's own calls and a return). */
    private static void expandCalls(StringBuilder md, Map<String, String> ids, Map<String, Node> methods,
                                    Map<String, List<String>> calls, Set<String> expanded, String key) {
        if (!expanded.add(key)) return;
        String from = ids.get(methods.get(key).participant);
        for (String callee : calls.getOrDefault(key, List.of())) {
            Node target = methods.get(callee);
            String to = ids.get(target.participant);
            md.append("    ").append(from).append("->>").append(to).append(": ").append(seq(target.method)).append('\n');
            expandCalls(md, ids, methods, calls, expanded, callee);
            md.append("    ").append(to).append("-->>").append(from).append(": return\n");
        }
    }

    /**
     * Participant title: the project on top (when the line has one), then the file name with its extension
     * ("BookingService.java", "travel.js"), plus the class when it isn't the file's main class
     * ("BookingService.java · Booking").
     */
    private static String participantLabel(Run r) {
        String top = r.project.isEmpty() ? "" : "📦 " + r.project + "<br/>";
        String base = FlowLang.baseName(r.fileName);
        String cls = r.className;
        if (cls == null || cls.isBlank() || cls.equals("-") || cls.equals(base) || cls.equals(base + "Kt")) {
            return top + r.fileName;
        }
        return top + r.fileName + " \u00b7 " + (cls.startsWith(base + ".") ? cls.substring(base.length() + 1) : cls);
    }

    /**
     * The lines of one method as a table: the line number in its own column, the code, and (when any line
     * has one) a Note column with the tag and the note. Easier to read than numbers mixed into the code.
     */
    private static void appendLineTable(StringBuilder md, List<FlowEntry> lines) {
        boolean notes = lines.stream().anyMatch(e -> FlowTag.of(e) != null || (e.note != null && !e.note.isBlank()));
        md.append(notes ? "| Line | Code | Note |\n|---:|---|---|\n" : "| Line | Code |\n|---:|---|\n");
        for (FlowEntry e : lines) {
            md.append("| ").append(FlowFormat.lineNumber(e)).append(" | ").append(codeCell(e)).append(" |");
            if (notes) {
                FlowTag tag = FlowTag.of(e);
                List<String> parts = new ArrayList<>();
                if (tag != null) parts.add(tag.emoji + " " + tag.label);
                if (e.note != null && !e.note.isBlank()) parts.add("_" + cell(e.note.strip()) + "_");
                md.append(' ').append(String.join(" \u00b7 ", parts)).append(" |");
            }
            md.append('\n');
        }
        md.append('\n');
    }

    /**
     * The code of a line, syntax highlighted for its language: an HTML {@code <code>} element with a colored
     * span per token (keywords, types, strings, numbers, comments), using the same tokenizer as the panel.
     * The colors are mid-tones that read on light and dark previews. Viewers that drop inline styles
     * (GitHub) still show it as plain inline code. '|' is written as an entity so it can't split the cell.
     */
    private static String codeCell(FlowEntry e) {
        if (FlowDiagram.isDiagramOnly(e)) return "_No code yet (added in the Flow Diagram)_";
        StringBuilder out = new StringBuilder("<code>");
        for (CodeHighlighter.Span span : CodeHighlighter.highlight(e.text.strip(), FlowLang.kind(e.fileName))) {
            String text = html(span.text());
            String style = switch (span.token()) {
                case KEYWORD -> "color:#3574F0;font-weight:600";
                case TYPE -> "color:#8E5CD9";
                case STRING -> "color:#2E9E4F";
                case NUMBER -> "color:#1A97B0";
                case COMMENT -> "color:#8C8C8C;font-style:italic";
                case PLAIN -> null;
            };
            if (style == null) out.append(text);
            else out.append("<span style=\"").append(style).append("\">").append(text).append("</span>");
        }
        return out.append("</code>").toString();
    }

    /** Text safe inside HTML in a table cell: &lt; &gt; &amp; and | escaped, runs of spaces kept. */
    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("|", "&#124;")
                .replace("  ", " &nbsp;");
    }

    /** Text safe in a Mermaid sequence diagram (';' and '#' would end or escape the statement). */
    private static String seq(String s) {
        return s.replace(";", ",").replace("#", "").replace("\n", " ");
    }

    private static String blankToEmpty(String s) { return s == null || s.isBlank() ? "" : s; }

    private static String blankToDash(String s) { return s == null || s.isBlank() ? "-" : s; }

    private static String plural(int n, String word) { return n == 1 ? word : word + "s"; }

    /** Text safe inside a Markdown table cell. */
    private static String cell(String s) { return s.replace("|", "\\|"); }

    /** Text safe inside a quoted Mermaid label. */
    private static String mermaid(String s) {
        return s.replace("\"", "#quot;").replace("<", "#lt;").replace(">", "#gt;");
    }
}
