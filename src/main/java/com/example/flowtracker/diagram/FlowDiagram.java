package com.example.flowtracker.diagram;

import com.example.flowtracker.code.FlowLang;
import com.example.flowtracker.code.FlowSync;
import com.example.flowtracker.copy.FlowFormat;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowTag;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The editable model behind the Flow Diagram window: one box per class (with its methods) and the calls
 * between methods. It is built from a flow's lines, changed by the window, and turned back into lines by
 * {@link #apply}.
 * <p>
 * Classes and methods come in two kinds:
 * <ul>
 *   <li><b>known</b>: picked from the project's code. They carry real lines (the ones picked in the code, or the
 *       class / method declaration), which become ordinary saved lines on Apply.</li>
 *   <li><b>unknown</b>: not in the code (yet). They become "diagram lines": line number 0 (see
 *       {@link #isDiagramOnly}); an unknown class gets a {@link #DIAGRAM_URL} address instead of a file.</li>
 * </ul>
 */
public final class FlowDiagram {

    /** Address prefix of an unknown class (it has no file). */
    public static final String DIAGRAM_URL = "diagram:///";
    /** The code text of a diagram line. */
    static final String NEW_METHOD_TEXT = "// added in Flow Diagram";
    static final String NEW_CLASS_TEXT = "// class added in Flow Diagram";
    /** Method name of the line that keeps an unknown class without methods in the flow. */
    private static final String CLASS_MARKER = "[class]";

    /** True for a line added in the Flow Diagram that has no code (line number 0). */
    public static boolean isDiagramOnly(FlowEntry e) {
        return e.line <= 0;
    }

    /** One class: its methods in flow order, and where the window draws it. */
    static final class Box {
        final String id;
        /** File and class of its lines; fileUrl is "" for an unknown class. */
        final String fileUrl, origClass;
        /** The project its file is in (for a custom class: the one it is meant for, set by hand; "" = none). */
        String projectName;
        /** Its file's name; for a custom class the file it will be in (at first the name typed for it). */
        String fileName;
        String name;
        /** True for a known class picked in this diagram (not in the flow yet). */
        final boolean picked;
        /** Lines outside methods picked with it (its declaration), kept when the class has no other lines. */
        final List<FlowEntry> header = new ArrayList<>();
        double x, y;
        final List<Method> methods = new ArrayList<>();
        /**
         * Its look (null = the usual one): the method section's background ({@code fill}), the class section's
         * ({@code headerFill}; without one, a shade of the method section's, and the other way round), the border, and
         * the formats of the class name, the file name and the method names (a method's own format wins).
         */
        @Nullable Color fill, headerFill, border;
        @Nullable Format title, fileFormat, methodsFormat;
        /** The project band's own look (null = like the class section): background, border, text format and color. */
        @Nullable Color projectFill, projectBorder;
        @Nullable Format projectFormat;
        /** A note under the class name ("" = none), and its format. */
        String note = "";
        @Nullable Format noteFormat;

        Box(String id, String fileUrl, String fileName, String projectName, String origClass, String name, boolean picked) {
            this.id = id;
            this.fileUrl = fileUrl;
            this.fileName = fileName;
            this.projectName = projectName;
            this.origClass = origClass;
            this.name = name;
            this.picked = picked;
        }

        /** A class that isn't in the code (it has no file). */
        boolean isUnknown() { return fileUrl.isEmpty(); }

        void copyLook(Box from) {
            fill = from.fill;
            headerFill = from.headerFill;
            border = from.border;
            title = from.title;
            fileFormat = from.fileFormat;
            methodsFormat = from.methodsFormat;
            note = from.note;
            noteFormat = from.noteFormat;
            projectFill = from.projectFill;
            projectBorder = from.projectBorder;
            projectFormat = from.projectFormat;
        }

        /** The file name to show under the class name ("" for an unknown class). */
        /** The line under the class name in the diagram: its file ("" for a custom class named after itself). */
        String fileLine() {
            if (isUnknown()) return fileName.isBlank() || fileName.equals(name) ? "" : fileName;
            return fileName;
        }

        /** The project it is in ("" for none), shown in a band above the class name. */
        String project() {
            return projectName == null ? "" : projectName.strip();
        }

        /** The file name, and the project it is in ("" for a custom class). */
        String subtitle() {
            if (isUnknown()) {
                String file = fileName.isBlank() || fileName.equals(name) ? "" : fileName;
                if (projectName == null || projectName.isBlank()) return file;
                return file.isEmpty() ? projectName : file + "  \u00b7  " + projectName;
            }
            return projectName == null || projectName.isBlank() ? fileName : fileName + "  \u00b7  " + projectName;
        }

        Box copy() {
            Box b = new Box(id, fileUrl, fileName, projectName, origClass, name, picked);
            b.x = x;
            b.y = y;
            b.header.addAll(header);
            for (Method m : methods) b.methods.add(m.copy());
            b.copyLook(this);
            return b;
        }
    }

    /**
     * One method. {@code origKey} is its {@link FlowSync#methodKey} in the flow (null when added here); the
     * lines picked in the code for it ({@link #picked}) join the flow on Apply.
     */
    static final class Method {
        final String id;
        final @Nullable String origKey;
        String name;
        /** How many saved lines it has in the flow. */
        final int saved;
        /** True when its saved lines have no code (an unknown method, added now or earlier). */
        final boolean unknown;
        /** Lines picked in the code, not in the flow yet. */
        final List<FlowEntry> picked = new ArrayList<>();
        /** The name's format (null = the usual one), and a note under the name ("" = none). */
        @Nullable Format format;
        String note = "";
        @Nullable Format noteFormat;
        /** Its lines' tag ({@link FlowTag#id}, "" = none); with {@code tagSet} the diagram gives it to all its lines on Apply. */
        String tag = "";
        boolean tagSet;

        Method(String id, @Nullable String origKey, String name, int saved, boolean unknown) {
            this.id = id;
            this.origKey = origKey;
            this.name = name;
            this.saved = saved;
            this.unknown = unknown;
        }

        int lineCount() { return saved + picked.size(); }

        Method copy() {
            Method m = new Method(id, origKey, name, saved, unknown);
            m.picked.addAll(picked);
            m.copyLook(this);
            m.tag = tag;
            m.tagSet = tagSet;
            return m;
        }

        void copyLook(Method from) {
            format = from.format;
            note = from.note;
            noteFormat = from.noteFormat;
        }
    }

    /**
     * A call from one method to another. Manual ones (Connect Call To, or drawn here) can be removed; the
     * others were found in the code and are only shown.
     */
    record Link(String from, String to, boolean manual, @Nullable FlowEntry holder) {}

    final List<Box> boxes = new ArrayList<>();
    final List<Link> links = new ArrayList<>();
    /** The calls whose line looks different from the usual one, by {@link #lineKey}. */
    final Map<String, Line> lines = new HashMap<>();
    /** Notes written on calls (shown at the middle of the arrow), by {@link #lineKey}. */
    final Map<String, String> callNotes = new HashMap<>();
    /** How notes on calls are drawn, when not the usual way, by {@link #lineKey}. */
    final Map<String, NoteStyle> noteStyles = new HashMap<>();
    private int nextId;

    // ---------- building ----------

    /**
     * The diagram of a flow's lines. Lines outside methods (fields, imports) keep their class box but get no
     * method row.
     *
     * @param calls the flow's calls (found in the code plus the ones connected by hand), in call order
     */
    static FlowDiagram of(List<FlowEntry> entries, Set<String> calls) {
        FlowDiagram d = new FlowDiagram();
        Map<String, Box> byClass = new LinkedHashMap<>();
        Map<String, Method> byKey = new HashMap<>();
        Map<String, Integer> lineCount = new HashMap<>();
        Map<String, Boolean> onlyDiagram = new HashMap<>();
        Map<String, String> tagOf = new HashMap<>(); // a method's tag: the first tag among its lines
        for (FlowEntry e : entries) {
            String key = FlowSync.methodKey(e);
            lineCount.merge(key, 1, Integer::sum);
            onlyDiagram.merge(key, isDiagramOnly(e), Boolean::logicalAnd);
            if (FlowTag.of(e) != null) tagOf.putIfAbsent(key, e.tag);
        }
        for (FlowEntry e : entries) {
            Box box = byClass.computeIfAbsent(classKey(e), k -> {
                Box b = new Box(d.newId("c"), e.fileUrl.startsWith(DIAGRAM_URL) ? "" : e.fileUrl, e.fileName,
                        e.projectName == null ? "" : e.projectName, e.className, className(e), false);
                d.boxes.add(b);
                return b;
            });
            String key = FlowSync.methodKey(e);
            if (FlowFormat.isClassLevel(FlowFormat.methodLabel(e)) || byKey.containsKey(key)) continue;
            Method m = new Method(d.newId("m"), key, FlowFormat.methodName(e), lineCount.get(key), onlyDiagram.get(key));
            m.tag = tagOf.getOrDefault(key, "");
            box.methods.add(m);
            byKey.put(key, m);
        }

        Map<String, FlowEntry> manual = new LinkedHashMap<>(); // link -> the line that holds it
        for (FlowEntry e : entries) {
            for (String callee : e.calls) manual.putIfAbsent(FlowSync.link(FlowSync.methodKey(e), callee), e);
        }
        Set<String> all = new LinkedHashSet<>(calls);
        all.addAll(manual.keySet());
        for (String link : all) {
            String[] ends = FlowSync.parseLink(link);
            if (ends == null) continue;
            Method from = byKey.get(ends[0]), to = byKey.get(ends[1]);
            if (from == null || to == null || from == to) continue;
            d.links.add(new Link(from.id, to.id, manual.containsKey(link), manual.get(link)));
        }
        return d;
    }

    /** File + class of a line: the lines of one box. */
    private static String classKey(FlowEntry e) {
        return e.fileUrl + "\n" + e.className;
    }

    /** The class name to show: the class, or the file name without extension when the class is unknown. */
    static String className(FlowEntry e) {
        return e.className == null || e.className.isBlank() || e.className.equals("-")
                ? FlowLang.baseName(e.fileName) : e.className;
    }

    private String newId(String prefix) {
        return prefix + (++nextId);
    }

    /** An independent copy (for the window's history). */
    FlowDiagram copy() {
        FlowDiagram d = new FlowDiagram();
        d.nextId = nextId;
        for (Box b : boxes) d.boxes.add(b.copy());
        d.links.addAll(links);
        d.lines.putAll(lines);
        d.callNotes.putAll(callNotes);
        d.noteStyles.putAll(noteStyles);
        return d;
    }

    /** Takes the box positions of an earlier diagram of the same flow (matched by file and class, or name). */
    void keepPositions(FlowDiagram earlier) {
        for (Box b : boxes) {
            for (Box old : earlier.boxes) {
                boolean same = b.isUnknown() ? old.isUnknown() && old.name.equals(b.name)
                        : b.fileUrl.equals(old.fileUrl) && (b.origClass.equals(old.origClass) || b.name.equals(old.name));
                if (same) {
                    b.x = old.x;
                    b.y = old.y;
                    break;
                }
            }
        }
    }

    // ---------- lookups ----------

    @Nullable Box box(String id) {
        for (Box b : boxes) if (b.id.equals(id)) return b;
        return null;
    }

    @Nullable Method method(String id) {
        for (Box b : boxes) for (Method m : b.methods) if (m.id.equals(id)) return m;
        return null;
    }

    /** The class a method belongs to. */
    @Nullable Box boxOf(String methodId) {
        for (Box b : boxes) for (Method m : b.methods) if (m.id.equals(methodId)) return b;
        return null;
    }

    /** The known class box of a file and class, if the diagram has one. */
    @Nullable Box knownBox(String fileUrl, String origClass) {
        for (Box b : boxes) if (!b.isUnknown() && b.fileUrl.equals(fileUrl) && b.origClass.equals(origClass)) return b;
        return null;
    }

    // ---------- edits ----------

    /** Why a name can't be used for an unknown class, or null when it can. */
    @Nullable String checkClassName(@Nullable Box self, String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) return "Enter a class name";
        if (FlowLang.isSupportedName(n)) return "That's a file name: use Add Class to pick it from the project";
        if (!n.matches("[\\p{L}_$][\\p{L}\\p{N}_$.]*")) return "Use letters, digits, '_', '$' and '.'";
        for (Box b : boxes) {
            if (b != self && b.name.equals(n) && (self == null || Objects.equals(b.fileUrl, self.fileUrl) || b.isUnknown() || self.isUnknown())) {
                return "There is a class “" + n + "” already";
            }
        }
        return null;
    }

    /** Why a method name can't be used in the class, or null when it can. */
    @Nullable String checkMethodName(Box box, @Nullable Method self, String name) {
        String n = methodName(name);
        if (n.equals("()")) return "Enter a method name";
        if (!n.matches("[\\p{L}_$<][\\p{L}\\p{N}_$<>]*\\(\\)")) return "Use letters, digits, '_' and '$'";
        for (Method m : box.methods) {
            if (m != self && m.name.equals(n)) return box.name + " has a method “" + n + "” already";
        }
        return null;
    }

    /** "validate" and "validate()" both become "validate()". */
    static String methodName(@Nullable String name) {
        String n = name == null ? "" : name.strip();
        int paren = n.indexOf('(');
        if (paren >= 0) n = n.substring(0, paren).strip();
        return n + "()";
    }

    /** Adds a class that isn't in the code. */
    Box addUnknownClass(String name, double x, double y) {
        return addUnknownClass(name, name, x, y);
    }

    /** Adds a custom class (not in the code) that will be in the given file, e.g. PaymentGateway in PaymentGateway.java. */
    Box addUnknownClass(String name, String fileName, double x, double y) {
        Box b = new Box(newId("c"), "", fileName.isBlank() ? name.strip() : fileName.strip(), "", "", name.strip(), true);
        b.x = x;
        b.y = y;
        boxes.add(b);
        return b;
    }

    /**
     * Adds a class picked from the code (or returns the box it has already).
     *
     * @param header its declaration line (kept in the flow while the class has no other lines), or null
     */
    Box addKnownClass(String fileUrl, String fileName, String projectName, String className,
                      @Nullable FlowEntry header, double x, double y) {
        Box existing = knownBox(fileUrl, className);
        if (existing != null) return existing;
        String name = className == null || className.isBlank() || className.equals("-") ? FlowLang.baseName(fileName) : className;
        Box b = new Box(newId("c"), fileUrl, fileName, projectName, className, name, true);
        if (header != null) b.header.add(header);
        b.x = x;
        b.y = y;
        boxes.add(b);
        return b;
    }

    void renameClass(Box b, String name) {
        // A custom class whose file was just named after it keeps it that way.
        if (b.isUnknown() && b.fileName.equals(b.name)) b.fileName = name.strip();
        b.name = name.strip();
    }

    /** The file a custom class will be in. */
    /** The file a custom class will be in, and the project it is meant for ("" = none). */
    void setCustomFile(Box b, String fileName, String projectName) {
        setCustomFile(b, fileName);
        b.projectName = projectName == null ? "" : projectName.strip();
    }

    void setCustomFile(Box b, String fileName) {
        b.fileName = fileName.isBlank() ? b.name : fileName.strip();
    }

    /**
     * Puts a class from the code in the place of a box (same position). The box's methods come along as custom
     * methods of the new class (their old code lines leave the flow on Apply), and calls to and from them are
     * kept as calls connected by hand. Returns null when the diagram has that class already in another box.
     */
    @Nullable Box replaceWithKnownClass(Box old, String fileUrl, String fileName, String projectName, String className,
                                        @Nullable FlowEntry header) {
        Box other = knownBox(fileUrl, className);
        if (other != null && other != old) return null;
        String name = className == null || className.isBlank() || className.equals("-") ? FlowLang.baseName(fileName) : className;
        Box b = new Box(newId("c"), fileUrl, fileName, projectName, className, name, true);
        if (header != null) b.header.add(header);
        return swap(old, b);
    }

    /** Turns a box into a custom class (not in the code) with this name, keeping its methods as custom ones. */
    Box replaceWithCustomClass(Box old, String name) {
        return replaceWithCustomClass(old, name, name);
    }

    Box replaceWithCustomClass(Box old, String name, String fileName) {
        return swap(old, new Box(newId("c"), "", fileName.isBlank() ? name.strip() : fileName.strip(), "", "", name.strip(), true));
    }

    private Box swap(Box old, Box b) {
        b.x = old.x;
        b.y = old.y;
        b.copyLook(old);
        Set<String> ids = new HashSet<>();
        for (Method m : old.methods) {
            Method nm = new Method(m.id, null, m.name, 0, true);
            nm.copyLook(m);
            b.methods.add(nm);
            ids.add(m.id);
        }
        // Calls found in the code no longer hold for custom methods: keep them as calls connected by hand.
        links.replaceAll(l -> ids.contains(l.from()) || ids.contains(l.to()) ? new Link(l.from(), l.to(), true, null) : l);
        boxes.set(boxes.indexOf(old), b);
        return b;
    }

    /** Removes the class, its methods and every call to or from them. */
    void removeClass(Box b) {
        for (Method m : b.methods) dropLinks(m.id);
        boxes.remove(b);
    }

    /** Adds a method that isn't in the code. */
    Method addUnknownMethod(Box b, String name) {
        Method m = new Method(newId("m"), null, methodName(name), 0, true);
        b.methods.add(m);
        return m;
    }

    /**
     * Adds lines picked in the code to a method of a known class: to the method of that name when the class
     * has it, else to a new method. Lines that the method has picked already are skipped.
     *
     * @param name  the method's name as the lines have it, e.g. "search()"
     * @param lines the picked lines (at least one)
     */
    Method addKnownMethod(Box b, String name, List<FlowEntry> lines) {
        Method m = null;
        for (Method x : b.methods) if (x.name.equals(name)) m = x;
        if (m == null) {
            m = new Method(newId("m"), null, name, 0, false);
            b.methods.add(m);
        }
        for (FlowEntry e : lines) {
            boolean dup = false;
            for (FlowEntry p : m.picked) dup |= p.line == e.line && p.fileUrl.equals(e.fileUrl);
            if (!dup) m.picked.add(e);
        }
        return m;
    }

    /** Tags all the method's lines (on Apply): a {@link FlowTag#id}, or "" for no tag. */
    void setMethodTag(Method m, @Nullable String tagId) {
        m.tag = tagId == null ? "" : tagId;
        m.tagSet = true;
    }

    void renameMethod(Method m, String name) {
        m.name = methodName(name);
    }

    void removeMethod(Method m) {
        Box b = boxOf(m.id);
        if (b != null) b.methods.remove(m);
        dropLinks(m.id);
    }

    /** Connects a method to one it calls. Returns false for a call to itself or one that exists already. */
    boolean connect(String fromMethod, String toMethod) {
        if (fromMethod.equals(toMethod) || method(fromMethod) == null || method(toMethod) == null) return false;
        for (Link l : links) if (l.from().equals(fromMethod) && l.to().equals(toMethod)) return false;
        links.add(new Link(fromMethod, toMethod, true, null));
        return true;
    }

    /** Removes a call connected by hand; calls found in the code stay. */
    boolean disconnect(Link link) {
        if (!link.manual() || !links.remove(link)) return false;
        lines.remove(lineKey(link));
        callNotes.remove(lineKey(link));
        noteStyles.remove(lineKey(link));
        return true;
    }

    private void dropLinks(String methodId) {
        links.removeIf(l -> l.from().equals(methodId) || l.to().equals(methodId));
        lines.keySet().removeIf(k -> k.startsWith(methodId + ">") || k.endsWith(">" + methodId));
        callNotes.keySet().removeIf(k -> k.startsWith(methodId + ">") || k.endsWith(">" + methodId));
        noteStyles.keySet().removeIf(k -> k.startsWith(methodId + ">") || k.endsWith(">" + methodId));
    }

    /** Text that changes only when something that {@link #apply} writes back changes (not box positions). */
    String signature() {
        StringBuilder sb = new StringBuilder();
        for (Box b : boxes) {
            sb.append(b.id).append('=').append(b.name).append('@').append(b.fileName).append('@').append(b.projectName).append('{');
            for (Method m : b.methods) {
                sb.append(m.id).append('=').append(m.name).append('+').append(m.picked.size()).append('#').append(m.tag).append(';');
            }
            sb.append('}');
        }
        for (Link l : links) sb.append(l.from()).append('>').append(l.to()).append(l.manual() ? "m" : "c").append(';');
        return sb.toString();
    }

    /** True when the other diagram has the same classes, methods, calls and box positions (nothing to record). */
    boolean sameAs(FlowDiagram other) {
        if (!signature().equals(other.signature()) || boxes.size() != other.boxes.size()) return false;
        if (!lookSignature().equals(other.lookSignature())) return false;
        for (int i = 0; i < boxes.size(); i++) {
            Box a = boxes.get(i), b = other.boxes.get(i);
            if (Math.abs(a.x - b.x) > 0.5 || Math.abs(a.y - b.y) > 0.5) return false;
        }
        return true;
    }

    // ---------- look: colors, text formats, notes and lines ----------

    /**
     * Bold, italic, underline, strike-through and a color for a piece of text (a null color: automatic, readable on
     * the box's background).
     */
    record Format(boolean bold, boolean italic, boolean underline, boolean strike, @Nullable Color color) {
        static final Format PLAIN = new Format(false, false, null);

        Format(boolean bold, boolean italic, @Nullable Color color) {
            this(bold, italic, false, false, color);
        }

        /**
         * "b" bold, "i" italic, "u" underline, "s" strike-through, then the color: e.g. "bu#C62828"; "" for plain
         * text in the automatic color.
         */
        String encode() {
            return (bold ? "b" : "") + (italic ? "i" : "") + (underline ? "u" : "") + (strike ? "s" : "")
                    + (color == null ? "" : hex(color));
        }

        static @Nullable Format decode(@Nullable String s) {
            if (s == null || s.isEmpty()) return null;
            int hash = s.indexOf('#');
            String flags = hash < 0 ? s : s.substring(0, hash);
            return new Format(flags.contains("b"), flags.contains("i"), flags.contains("u"), flags.contains("s"),
                    hash < 0 ? null : parseColor(s.substring(hash)));
        }

        Format withColor(@Nullable Color c) {
            return new Format(bold, italic, underline, strike, c);
        }
    }

    /** True for a dark color (light text reads better on it). */
    static boolean isDark(Color c) {
        return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255 < 0.55;
    }

    /** "#RRGGBB". */
    static String hex(Color c) {
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }

    /** The color of "#RRGGBB", or null for anything else (e.g. ""). */
    static @Nullable Color parseColor(@Nullable String hex) {
        if (hex == null || !hex.matches("#[0-9A-Fa-f]{6}")) return null;
        return new Color(Integer.parseInt(hex.substring(1), 16));
    }

    /** How a call's line is drawn. */
    enum LinePattern {
        AUTO("Automatic (solid: in the code, dashed: by hand)"), SOLID("Solid"), DASHED("Dashed"), SHORT_DASH("Short dashes"),
        LONG_DASH("Long dashes"), DOTTED("Dotted"), DASH_DOT("Dash-dot"), DASH_DOT_DOT("Dash-dot-dot"),
        DOUBLE("Double line"), DOUBLE_DASHED("Double dashed");
        final String label;
        LinePattern(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    enum LineWidth {
        THIN("Thin", 1f), NORMAL("Normal", 1.6f), THICK("Thick", 2.6f), HEAVY("Heavy", 3.8f);
        final String label;
        final float width;
        LineWidth(String label, float width) { this.label = label; this.width = width; }
        @Override public String toString() { return label; }
    }

    enum LineShape {
        CURVED("Curved"), STRAIGHT("Straight"), ELBOW("Elbow (right angles)");
        final String label;
        LineShape(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    enum ArrowHead {
        FILLED("Sharp arrow"), TRIANGLE("Triangle"), OPEN("Open arrow"), DIAMOND("Diamond"), CIRCLE("Dot"), NONE("No arrow");
        final String label;
        ArrowHead(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    /** A call's line: pattern, width, shape, arrow head and color (null: the usual one). */
    record Line(LinePattern pattern, LineWidth width, LineShape shape, ArrowHead arrow, @Nullable Color color) {
        static final Line DEFAULT = new Line(LinePattern.AUTO, LineWidth.NORMAL, LineShape.CURVED, ArrowHead.FILLED, null);

        String encode() {
            return equals(DEFAULT) ? "" : pattern.name() + "," + width.name() + "," + shape.name() + "," + arrow.name();
        }

        static Line decode(@Nullable String s, @Nullable Color color) {
            String[] p = s == null || s.isEmpty() ? new String[0] : s.split(",");
            try {
                return new Line(p.length > 0 ? LinePattern.valueOf(p[0]) : LinePattern.AUTO,
                        p.length > 1 ? LineWidth.valueOf(p[1]) : LineWidth.NORMAL,
                        p.length > 2 ? LineShape.valueOf(p[2]) : LineShape.CURVED,
                        p.length > 3 ? ArrowHead.valueOf(p[3]) : ArrowHead.FILLED, color);
            } catch (IllegalArgumentException e) {
                return new Line(LinePattern.AUTO, LineWidth.NORMAL, LineShape.CURVED, ArrowHead.FILLED, color);
            }
        }
    }

    static String lineKey(Link l) {
        return l.from() + ">" + l.to();
    }

    /** How the call's line is drawn. */
    Line line(Link l) {
        return lines.getOrDefault(lineKey(l), Line.DEFAULT);
    }

    void setLine(Link l, Line line) {
        if (line.equals(Line.DEFAULT)) lines.remove(lineKey(l));
        else lines.put(lineKey(l), line);
    }

    /** The shape of a note's card. */
    enum NoteShape {
        ROUNDED("Rounded"), RECTANGLE("Rectangle"), PILL("Pill"), PLAIN("Text only (no card)");
        final String label;
        NoteShape(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    /** How a call's note is drawn: its card's shape, and its background, text and border colors (null: the usual one). */
    record NoteStyle(NoteShape shape, @Nullable Color background, @Nullable Color text, @Nullable Color border) {
        static final NoteStyle DEFAULT = new NoteStyle(NoteShape.ROUNDED, null, null, null);

        String encode() {
            if (equals(DEFAULT)) return "";
            return shape.name() + ";" + (background == null ? "" : hex(background)) + ";" + (text == null ? "" : hex(text))
                    + ";" + (border == null ? "" : hex(border));
        }

        static NoteStyle decode(@Nullable String s) {
            if (s == null || s.isBlank()) return DEFAULT;
            String[] p = s.split(";", -1);
            NoteShape shape;
            try {
                shape = NoteShape.valueOf(p[0]);
            } catch (IllegalArgumentException e) {
                shape = NoteShape.ROUNDED;
            }
            return new NoteStyle(shape, p.length > 1 ? parseColor(p[1]) : null, p.length > 2 ? parseColor(p[2]) : null,
                    p.length > 3 ? parseColor(p[3]) : null);
        }
    }

    NoteStyle noteStyle(Link l) {
        return noteStyles.getOrDefault(lineKey(l), NoteStyle.DEFAULT);
    }

    void setNoteStyle(Link l, @Nullable NoteStyle style) {
        if (style == null || style.equals(NoteStyle.DEFAULT)) noteStyles.remove(lineKey(l));
        else noteStyles.put(lineKey(l), style);
    }

    /** The note written on a call ("" for none). */
    String callNote(Link l) {
        return callNotes.getOrDefault(lineKey(l), "");
    }

    void setCallNote(Link l, @Nullable String note) {
        if (note == null || note.isBlank()) callNotes.remove(lineKey(l));
        else callNotes.put(lineKey(l), note.strip());
    }

    /**
     * Reset Theme: every box, method and call gets the default look again (colors, text formats, lines). Notes,
     * names, calls and positions stay.
     */
    void resetLooks() {
        for (Box b : boxes) {
            b.fill = null;
            b.headerFill = null;
            b.border = null;
            b.title = null;
            b.fileFormat = null;
            b.methodsFormat = null;
            b.noteFormat = null;
            b.projectFill = null;
            b.projectBorder = null;
            b.projectFormat = null;
            for (Method m : b.methods) {
                m.format = null;
                m.noteFormat = null;
            }
        }
        lines.clear();
        noteStyles.clear(); // the notes themselves stay
    }

    /** Every color, format, note and line, as one text (positions are compared separately). */
    String lookSignature() {
        StringBuilder sb = new StringBuilder();
        for (Box b : boxes) {
            sb.append(b.id).append(':').append(b.fill == null ? "" : hex(b.fill)).append(',')
                    .append(b.border == null ? "" : hex(b.border)).append(',').append(enc(b.title)).append(',')
                    .append(b.headerFill == null ? "" : hex(b.headerFill)).append(',').append(enc(b.fileFormat)).append(',')
                    .append(enc(b.methodsFormat)).append(',')
                    .append(b.note).append(',').append(enc(b.noteFormat)).append(',')
                    .append(b.projectFill == null ? "" : hex(b.projectFill)).append(',')
                    .append(b.projectBorder == null ? "" : hex(b.projectBorder)).append(',').append(enc(b.projectFormat)).append(';');
            for (Method m : b.methods) {
                sb.append(m.id).append(':').append(enc(m.format)).append(',').append(m.note).append(',')
                        .append(enc(m.noteFormat)).append(';');
            }
        }
        new java.util.TreeMap<>(lines).forEach((k, v) -> sb.append(k).append('=').append(v.encode())
                .append(v.color() == null ? "" : hex(v.color())).append(';'));
        new java.util.TreeMap<>(callNotes).forEach((k, v) -> sb.append(k).append('"').append(v).append(';'));
        new java.util.TreeMap<>(noteStyles).forEach((k, v) -> sb.append(k).append('%').append(v.encode()).append(';'));
        return sb.toString();
    }

    private static String enc(@Nullable Format f) {
        return f == null ? "" : "[" + f.encode() + "]";
    }

    /**
     * The look of every box (with its position), every method with a format or note, and every call with its own
     * line, to save with the flow.
     */
    List<FlowDiagramLook> looks() {
        List<FlowDiagramLook> out = new ArrayList<>();
        for (Box b : boxes) {
            FlowDiagramLook l = target(b, null);
            l.placed = true;
            l.x = Math.round(b.x);
            l.y = Math.round(b.y);
            l.fill = b.fill == null ? "" : hex(b.fill);
            l.border = b.border == null ? "" : hex(b.border);
            l.format = b.title == null ? "" : b.title.encode();
            l.header = b.headerFill == null ? "" : hex(b.headerFill);
            l.fileFormat = b.fileFormat == null ? "" : b.fileFormat.encode();
            l.methodFormat = b.methodsFormat == null ? "" : b.methodsFormat.encode();
            l.note = b.note;
            l.noteFormat = b.noteFormat == null ? "" : b.noteFormat.encode();
            l.projectFill = b.projectFill == null ? "" : hex(b.projectFill);
            l.projectBorder = b.projectBorder == null ? "" : hex(b.projectBorder);
            l.projectFormat = b.projectFormat == null ? "" : b.projectFormat.encode();
            out.add(l);
            for (Method m : b.methods) {
                if (m.format == null && m.note.isEmpty() && m.noteFormat == null) continue;
                FlowDiagramLook ml = target(b, m);
                ml.format = m.format == null ? "" : m.format.encode();
                ml.note = m.note;
                ml.noteFormat = m.noteFormat == null ? "" : m.noteFormat.encode();
                out.add(ml);
            }
        }
        for (Link link : links) {
            Line line = line(link);
            String note = callNote(link);
            Box fb = boxOf(link.from()), tb = boxOf(link.to());
            NoteStyle style = noteStyle(link);
            if (line.equals(Line.DEFAULT) && note.isEmpty() && style.equals(NoteStyle.DEFAULT) || fb == null || tb == null) continue;
            FlowDiagramLook l = target(fb, method(link.from()));
            FlowDiagramLook to = target(tb, method(link.to()));
            l.toFileUrl = to.fileUrl;
            l.toClass = to.className;
            l.toMethod = to.method;
            l.line = line.encode();
            l.border = line.color() == null ? "" : hex(line.color());
            l.note = note;
            l.noteStyle = style.encode();
            out.add(l);
        }
        return out;
    }

    /** What a look is for: the box (method null) or one of its methods. */
    private static FlowDiagramLook target(Box b, @Nullable Method m) {
        FlowDiagramLook l = new FlowDiagramLook();
        l.fileUrl = b.isUnknown() ? "" : b.fileUrl;
        l.className = b.name;
        l.method = m == null ? "" : m.name;
        return l;
    }

    /** Gives the boxes, methods and calls the looks saved with the flow (matched by file, class and method name). */
    void applyLooks(List<FlowDiagramLook> looks) {
        for (FlowDiagramLook l : looks) {
            Box b = findBox(l.fileUrl, l.className);
            if (b == null) continue;
            if (l.isBox()) {
                if (l.placed) {
                    b.x = Math.max(0, l.x);
                    b.y = Math.max(0, l.y);
                }
                b.fill = parseColor(l.fill);
                b.border = parseColor(l.border);
                b.title = Format.decode(l.format);
                b.headerFill = parseColor(l.header);
                b.fileFormat = Format.decode(l.fileFormat);
                b.methodsFormat = Format.decode(l.methodFormat);
                b.note = l.note == null ? "" : l.note;
                b.noteFormat = Format.decode(l.noteFormat);
                b.projectFill = parseColor(l.projectFill);
                b.projectBorder = parseColor(l.projectBorder);
                b.projectFormat = Format.decode(l.projectFormat);
                continue;
            }
            Method m = findMethod(b, l.method);
            if (m == null) continue;
            if (l.isMethod()) {
                m.format = Format.decode(l.format);
                m.note = l.note == null ? "" : l.note;
                m.noteFormat = Format.decode(l.noteFormat);
                continue;
            }
            Box tb = findBox(l.toFileUrl, l.toClass);
            Method to = tb == null ? null : findMethod(tb, l.toMethod);
            if (to == null) continue;
            for (Link link : links) {
                if (!link.from().equals(m.id) || !link.to().equals(to.id)) continue;
                setLine(link, Line.decode(l.line, parseColor(l.border)));
                setCallNote(link, l.note);
                setNoteStyle(link, NoteStyle.decode(l.noteStyle));
            }
        }
    }

    private @Nullable Box findBox(String fileUrl, String name) {
        for (Box b : boxes) {
            if (b.name.equals(name) && (b.isUnknown() ? fileUrl == null || fileUrl.isEmpty() : b.fileUrl.equals(fileUrl))) return b;
        }
        return null;
    }

    private static @Nullable Method findMethod(Box b, String name) {
        for (Method m : b.methods) if (m.name.equals(name)) return m;
        return null;
    }

    /** True when both lists hold the same looks. */
    static boolean sameLooks(List<FlowDiagramLook> a, List<FlowDiagramLook> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).text().equals(b.get(i).text())) return false;
        return true;
    }

    // ---------- layout ----------

    /** Box size for the layout: width and height of a box when drawn. */
    interface Measure {
        double width(Box b);
        double height(Box b);
    }

    /**
     * Places the boxes in columns by call depth, left to right: classes nobody calls first, then the classes
     * they call, and so on. Within a column, boxes keep flow order.
     */
    void autoLayout(Measure measure, double gapX, double gapY, double margin) {
        Map<Box, Integer> depth = new IdentityHashMap<>();
        Map<Box, Set<Box>> calls = new IdentityHashMap<>();
        Set<Box> called = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Link l : links) {
            Box from = boxOf(l.from()), to = boxOf(l.to());
            if (from == null || to == null || from == to) continue;
            calls.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
            called.add(to);
        }
        List<Box> queue = new ArrayList<>();
        for (Box b : boxes) {
            if (!called.contains(b)) { depth.put(b, 0); queue.add(b); }
        }
        for (int i = 0; i < queue.size(); i++) {
            Box b = queue.get(i);
            for (Box next : calls.getOrDefault(b, Set.of())) {
                if (!depth.containsKey(next)) { depth.put(next, depth.get(b) + 1); queue.add(next); }
            }
        }
        for (Box b : boxes) depth.putIfAbsent(b, 0); // only reachable through a cycle

        Map<Integer, List<Box>> columns = new java.util.TreeMap<>();
        for (Box b : boxes) columns.computeIfAbsent(depth.get(b), k -> new ArrayList<>()).add(b);
        double x = margin;
        for (List<Box> column : columns.values()) {
            double y = margin, widest = 0;
            for (Box b : column) {
                b.x = x;
                b.y = y;
                y += measure.height(b) + gapY;
                widest = Math.max(widest, measure.width(b));
            }
            x += widest + gapX;
        }
    }

    /**
     * Moves boxes apart so none covers another (e.g. after a box grew with a longer name or a note, or was dropped
     * on another). The {@code pinned} box stays where it is; the others are taken left to right and each is pushed
     * right or down, whichever is the shorter move, until it is clear of the boxes before it.
     *
     * @return true when a box moved
     */
    boolean spreadOut(Measure measure, double gap, @Nullable Box pinned) {
        List<Box> order = new ArrayList<>(boxes);
        order.sort(Comparator.<Box>comparingDouble(b -> b.x).thenComparingDouble(b -> b.y));
        if (pinned != null && order.remove(pinned)) order.add(0, pinned);
        List<Rectangle2D> placed = new ArrayList<>();
        boolean moved = false;
        for (Box b : order) {
            double w = measure.width(b), h = measure.height(b);
            for (int guard = 0; guard < 1000; guard++) {
                Rectangle2D hit = null;
                for (Rectangle2D r : placed) {
                    if (b.x < r.getMaxX() + gap && r.getX() < b.x + w + gap && b.y < r.getMaxY() + gap && r.getY() < b.y + h + gap) {
                        hit = r;
                        break;
                    }
                }
                if (hit == null) break;
                double right = hit.getMaxX() + gap - b.x, down = hit.getMaxY() + gap - b.y;
                if (right <= down) b.x += right;
                else b.y += down;
                moved = true;
            }
            placed.add(new Rectangle2D.Double(b.x, b.y, w, h));
        }
        return moved;
    }

    // ---------- sequence ----------

    /** One row of the sequence diagram. */
    sealed interface Step permits Start, Call, Return {}
    /** A method nobody here calls: a note over its class, where a chain of calls starts. */
    record Start(Box box, Method method) implements Step {}
    /** A call, with the note written on it ("" for none). */
    record Call(Box from, Box to, Method method, String note, NoteStyle noteStyle) implements Step {
        Call(Box from, Box to, Method method, String note) {
            this(from, to, method, note, NoteStyle.DEFAULT);
        }
    }
    record Return(Box from, Box to) implements Step {}

    /**
     * The calls as a sequence: each method that no other method here calls starts a chain; every call is an
     * arrow, followed by the callee's own calls and a return. A method's calls are shown the first time only
     * (the same rules as the Markdown sequence diagram).
     */
    List<Step> sequence() {
        Map<String, List<String>> callees = new LinkedHashMap<>();
        Set<String> called = new HashSet<>();
        for (Link l : links) {
            callees.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(l.to());
            called.add(l.to());
        }
        List<Step> out = new ArrayList<>();
        Set<String> expanded = new HashSet<>();
        List<Method> all = new ArrayList<>();
        for (Box b : boxes) all.addAll(b.methods);
        for (Method m : all) {
            if (!called.contains(m.id) && callees.containsKey(m.id)) chain(m, callees, expanded, out);
        }
        for (Method m : all) {
            if (callees.containsKey(m.id) && !expanded.contains(m.id)) chain(m, callees, expanded, out);
        }
        return out;
    }

    private void chain(Method m, Map<String, List<String>> callees, Set<String> expanded, List<Step> out) {
        out.add(new Start(boxOf(m.id), m));
        expand(m, callees, expanded, out);
    }

    private void expand(Method m, Map<String, List<String>> callees, Set<String> expanded, List<Step> out) {
        if (!expanded.add(m.id)) return;
        Box from = boxOf(m.id);
        for (String calleeId : callees.getOrDefault(m.id, List.of())) {
            Method callee = method(calleeId);
            Box to = boxOf(calleeId);
            if (callee == null || to == null) continue;
            String key = m.id + ">" + calleeId;
            out.add(new Call(from, to, callee, callNotes.getOrDefault(key, ""), noteStyles.getOrDefault(key, NoteStyle.DEFAULT)));
            expand(callee, callees, expanded, out);
            out.add(new Return(to, from));
        }
    }

    // ---------- back to lines ----------

    /** The flow after the diagram's changes, and the lines of {@code current} it no longer has. */
    record Result(List<FlowEntry> entries, List<FlowEntry> removed) {}

    /** The diagram's changes applied to the lines it was built from; nothing is removed but those lines. */
    Result apply(List<FlowEntry> base) {
        return apply(base, base);
    }

    /**
     * The flow with the diagram's changes:
     * <ul>
     *   <li>lines of removed classes and methods are left out; renamed ones get the new class / method name;</li>
     *   <li>lines picked in the code join the flow after their method's (or class's) other lines;</li>
     *   <li>unknown classes and methods become diagram lines;</li>
     *   <li>each line's connections match the diagram's manual calls.</li>
     * </ul>
     * Lines that don't change are kept as they are (same objects); changed and added ones are new objects.
     *
     * @param base    the lines the diagram was built from
     * @param current the flow's lines now (for {@link Result#removed}: those the result no longer has)
     */
    Result apply(List<FlowEntry> base, List<FlowEntry> current) {
        Map<String, Box> boxByClass = new HashMap<>();
        Map<String, Method> methodByKey = new HashMap<>();
        for (Box b : boxes) {
            boxByClass.put((b.isUnknown() ? DIAGRAM_URL + b.origClass : b.fileUrl) + "\n" + b.origClass, b);
            for (Method m : b.methods) if (m.origKey != null) methodByKey.put(m.origKey, m);
        }

        List<FlowEntry> out = new ArrayList<>();
        Map<FlowEntry, FlowEntry> copies = new IdentityHashMap<>(); // base line -> its copy
        Set<FlowEntry> fresh = Collections.newSetFromMap(new IdentityHashMap<>()); // made here
        Map<Method, List<FlowEntry>> linesOf = new IdentityHashMap<>();
        Map<Box, List<FlowEntry>> linesOfBox = new IdentityHashMap<>();
        Set<String> taken = new HashSet<>(); // file + line of every code line in the result
        for (FlowEntry e : base) {
            Box box = boxByClass.get(classKey(e));
            if (box == null) continue;
            // The line that kept an empty unknown class in the flow isn't needed once the class has methods.
            if (isDiagramOnly(e) && CLASS_MARKER.equals(e.methodName) && !box.methods.isEmpty()) continue;
            Method m = null;
            if (!FlowFormat.isClassLevel(FlowFormat.methodLabel(e))) {
                m = methodByKey.get(FlowSync.methodKey(e));
                if (m == null) continue;
            }
            FlowEntry now = e;
            String cls = targetClass(box, e);
            String method = m == null ? e.methodName : m.name;
            String url = box.isUnknown() ? DIAGRAM_URL + box.name : e.fileUrl;
            String file = box.isUnknown() ? box.fileName : e.fileName;
            String project = box.isUnknown() ? box.projectName : e.projectName == null ? "" : e.projectName;
            if (!cls.equals(e.className) || !method.equals(e.methodName) || !url.equals(e.fileUrl) || !file.equals(e.fileName)
                    || !project.equals(e.projectName == null ? "" : e.projectName)) {
                now = copy(e);
                now.className = cls;
                now.methodName = method;
                now.fileUrl = url;
                now.fileName = file;
                now.projectName = project;
                copies.put(e, now);
                fresh.add(now);
            }
            out.add(now);
            if (!isDiagramOnly(e)) taken.add(e.fileUrl + "#" + e.line);
            linesOfBox.computeIfAbsent(box, k -> new ArrayList<>()).add(now);
            if (m != null) linesOf.computeIfAbsent(m, k -> new ArrayList<>()).add(now);
        }

        // Lines picked in the code for methods the flow has: right after that method's lines.
        for (Box b : boxes) {
            for (Method m : b.methods) {
                if (!linesOf.containsKey(m) || m.picked.isEmpty()) continue;
                List<FlowEntry> add = pickedLines(b, m, m.picked, taken, fresh);
                insertAfter(out, linesOf.get(m), add);
                linesOf.get(m).addAll(add);
                linesOfBox.get(b).addAll(add);
            }
        }

        // New methods (and classes): their picked lines, or one diagram line, after their class's other lines.
        for (Box b : boxes) {
            List<FlowEntry> add = new ArrayList<>();
            for (Method m : b.methods) {
                if (linesOf.containsKey(m)) continue;
                List<FlowEntry> lines = m.picked.isEmpty() ? List.of(diagramLine(b, m.name, NEW_METHOD_TEXT))
                        : pickedLines(b, m, m.picked, taken, fresh);
                if (lines.isEmpty()) continue; // every picked line is in the flow already
                fresh.addAll(lines);
                add.addAll(lines);
                linesOf.put(m, new ArrayList<>(lines));
            }
            if (!linesOfBox.containsKey(b) && add.isEmpty()) {
                if (b.isUnknown()) {
                    add.add(diagramLine(b, CLASS_MARKER, NEW_CLASS_TEXT));
                } else {
                    for (FlowEntry h : b.header) {
                        if (taken.add(h.fileUrl + "#" + h.line)) add.add(copy(h));
                    }
                }
                fresh.addAll(add);
            }
            if (add.isEmpty()) continue;
            insertAfter(out, linesOfBox.getOrDefault(b, List.of()), add);
            linesOfBox.computeIfAbsent(b, k -> new ArrayList<>()).addAll(add);
        }

        // Connections made by hand: on the line that held them, else on the caller's first line.
        Map<FlowEntry, List<String>> wanted = new IdentityHashMap<>();
        for (Link l : links) {
            if (!l.manual()) continue;
            Method from = method(l.from()), to = method(l.to());
            if (from == null || to == null || !linesOf.containsKey(from) || !linesOf.containsKey(to)) continue;
            List<FlowEntry> callerLines = linesOf.get(from);
            FlowEntry holder = l.holder() == null ? null : copies.getOrDefault(l.holder(), l.holder());
            if (holder == null || !callerLines.contains(holder)) holder = callerLines.get(0);
            List<String> list = wanted.computeIfAbsent(holder, k -> new ArrayList<>());
            String key = FlowSync.methodKey(linesOf.get(to).get(0));
            if (!list.contains(key)) list.add(key);
        }
        for (int i = 0; i < out.size(); i++) {
            FlowEntry e = out.get(i);
            List<String> want = wanted.getOrDefault(e, List.of());
            if (want.equals(e.calls)) continue;
            FlowEntry c = fresh.contains(e) ? e : copy(e);
            c.calls = new ArrayList<>(want);
            if (c != e) {
                copies.put(e, c); // the same line with its connections, not a removed one
                fresh.add(c);
            }
            out.set(i, c);
        }

        // Tags set in the diagram go to every line of their method.
        for (Map.Entry<Method, List<FlowEntry>> ml : linesOf.entrySet()) {
            Method m = ml.getKey();
            if (!m.tagSet) continue;
            Set<FlowEntry> lines = Collections.newSetFromMap(new IdentityHashMap<>());
            lines.addAll(ml.getValue());
            for (int i = 0; i < out.size(); i++) {
                FlowEntry e = out.get(i);
                if (!lines.contains(e) || m.tag.equals(e.tag == null ? "" : e.tag)) continue;
                FlowEntry c = fresh.contains(e) ? e : copy(e);
                c.tag = m.tag;
                if (c != e) {
                    copies.put(e, c); // the same line with its new tag, not a removed one
                    fresh.add(c);
                }
                out.set(i, c);
            }
        }

        Set<FlowEntry> kept = Collections.newSetFromMap(new IdentityHashMap<>());
        kept.addAll(out);
        kept.addAll(copies.keySet()); // renamed, not removed: the copy takes its place
        List<FlowEntry> removed = new ArrayList<>();
        for (FlowEntry e : current) if (!kept.contains(e)) removed.add(e);
        return new Result(out, removed);
    }

    /** Copies of picked lines for a method (with its current class and method name), minus lines in the flow. */
    private static List<FlowEntry> pickedLines(Box b, Method m, List<FlowEntry> picked, Set<String> taken, Set<FlowEntry> fresh) {
        List<FlowEntry> out = new ArrayList<>();
        for (FlowEntry p : picked) {
            if (!taken.add(p.fileUrl + "#" + p.line)) continue;
            FlowEntry c = copy(p);
            if (!b.name.equals(className(p))) c.className = b.name;
            c.methodName = m.name;
            out.add(c);
            fresh.add(c);
        }
        return out;
    }

    /** Inserts lines after the last of {@code after} in {@code out} (at the end when none of them is in it). */
    private static void insertAfter(List<FlowEntry> out, List<FlowEntry> after, List<FlowEntry> lines) {
        int at = -1;
        for (int i = 0; i < out.size(); i++) {
            for (FlowEntry a : after) if (out.get(i) == a) at = i;
        }
        out.addAll(at < 0 ? out.size() : at + 1, lines);
    }

    /** The class a line gets: the box's new name (a line of a nested class keeps its own when unchanged). */
    private static String targetClass(Box box, FlowEntry e) {
        if (box.name.equals(className(e))) return e.className;
        return box.name;
    }

    /** A line for an unknown method (or class): no code, line number 0. */
    private static FlowEntry diagramLine(Box b, String method, String text) {
        String url = b.isUnknown() ? DIAGRAM_URL + b.name : b.fileUrl;
        String file = b.fileName;
        FlowEntry e = new FlowEntry(file, url, b.isUnknown() ? b.name : b.origClass, method, 0, text);
        e.projectName = b.projectName;
        if (!b.isUnknown() && !b.name.equals(className(e))) e.className = b.name;
        return e;
    }

    static FlowEntry copy(FlowEntry e) {
        FlowEntry c = new FlowEntry(e.fileName, e.fileUrl, e.className, e.methodName, e.line, e.text);
        c.note = e.note;
        c.projectName = e.projectName;
        c.tag = e.tag;
        c.calls = new ArrayList<>(e.calls);
        return c;
    }

    // ---------- history ----------

    /**
     * The Flow Diagram window's history of one flow, kept while the IDE runs so it is still there after Apply
     * and when the window opens again. Each step has the diagram after it and the lines it applies to.
     */
    public static final class History {
        /**
         * @param base    the flow's lines the diagram was built from (Apply applies the diagram to these)
         * @param applied true once this step was applied to the flow
         * @param open    true for the step made when the window opened (before any change)
         */
        record Step(String label, FlowDiagram diagram, List<FlowEntry> base, boolean applied, boolean open) {}

        final List<Step> steps = new ArrayList<>();
        int position;
        /** The flow's lines right after the last Apply (null before any). */
        @Nullable List<FlowEntry> appliedLines;

        static final int LIMIT = 200;

        /** True when the flow still has the lines of the current step or of the last Apply. */
        boolean continues(List<FlowEntry> lines) {
            return !steps.isEmpty() && (current().base().equals(lines) || lines.equals(appliedLines));
        }

        Step current() {
            return steps.get(position);
        }

        /**
         * Records a change: a step after the current one (dropping the steps that were undone). A "change" that
         * leaves the diagram as the current step has it (e.g. Auto Layout clicked again) isn't recorded.
         *
         * @return false when nothing changed, so no step was added
         */
        boolean change(String label, FlowDiagram diagram) {
            if (!steps.isEmpty() && current().diagram().sameAs(diagram)) return false;
            push(label, diagram, steps.isEmpty() ? List.of() : current().base(), false);
            return true;
        }

        /** Adds a step after the current one (dropping the steps that were undone). */
        void push(String label, FlowDiagram diagram, List<FlowEntry> base, boolean open) {
            while (steps.size() > position + 1) steps.remove(steps.size() - 1);
            steps.add(new Step(label, diagram.copy(), List.copyOf(base), false, open));
            while (steps.size() > LIMIT) steps.remove(0);
            position = steps.size() - 1;
        }

        /**
         * The step for opening the window on these lines. When nothing happened since the last opening (the
         * current step is that opening, on the same lines), it is refreshed instead of added again.
         */
        void open(String label, FlowDiagram diagram, List<FlowEntry> lines) {
            if (!steps.isEmpty() && position == steps.size() - 1 && current().open() && current().base().equals(lines)) {
                steps.set(position, new Step(current().label(), diagram.copy(), List.copyOf(lines), current().applied(), true));
            } else {
                push(label, diagram, lines, true);
            }
        }

        /** Marks the current step as applied; {@code lines} are the flow's lines after it. */
        void markApplied(List<FlowEntry> lines) {
            Step s = current();
            steps.set(position, new Step(s.label(), s.diagram(), s.base(), true, s.open()));
            appliedLines = List.copyOf(lines);
        }

        /** An independent copy: the window edits it, and only Apply keeps it. */
        History copy() {
            History h = new History();
            h.steps.addAll(steps);
            h.position = position;
            h.appliedLines = appliedLines;
            return h;
        }
    }
}
