package com.example.flowtracker.diagram;

import com.example.flowtracker.model.FlowEntry;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Classes between the Flow Diagram's tabs, without the window: copy, cut and paste them, move or copy them to
 * another tab with link boxes on both sides, and two tabs combined in one. One per tab; the tab's window adds its
 * History steps, messages and selection around these (see {@link FlowDiagramDialog}).
 * <p>
 * <b>Link boxes</b>: where a moved class calls (or is called by) a class that stays, both tabs show the other side
 * as the real class (its code can be seen) with "⇄ in “the other tab”" as its note and an orange frame. In the tab
 * the class left, it takes the class's place; in the tab it went to, callers stand on the left and the classes it
 * calls on the right. The calls keep their notes and styles (and stay code calls, or calls connected by hand).
 */
final class FlowDiagramTabs {

    /** A call's look: its note, the note's style and the line's style; manual: connected by hand (dashed). */
    record CallLook(String note, FlowDiagram.NoteStyle noteStyle, FlowDiagram.Line line, boolean manual) {}

    /**
     * Copied classes: their files, methods and lines, positions, the calls between them (with their notes and line
     * styles), and their calls to (and from) classes that weren't copied.
     */
    record Clip(List<BoxCopy> boxes, List<LinkCopy> links, List<Outside> outside) {}

    record BoxCopy(String name, String fileUrl, String fileName, String projectName, String origClass, boolean unknown,
                   double x, double y, List<MethodCopy> methods, @Nullable FlowDiagram.Box look) {
        BoxCopy(String name, String fileUrl, String fileName, String projectName, String origClass, boolean unknown,
                double x, double y, List<MethodCopy> methods) {
            this(name, fileUrl, fileName, projectName, origClass, unknown, x, y, methods, null);
        }
    }

    record MethodCopy(String name, boolean unknown, List<FlowEntry> lines, String tag, boolean tagSet, String note,
                      @Nullable FlowDiagram.Format format, @Nullable FlowDiagram.Format noteFormat) {
        MethodCopy(String name, boolean unknown, List<FlowEntry> lines) {
            this(name, unknown, lines, "", false, "", null, null);
        }
    }

    record LinkCopy(int fromBox, int fromMethod, int toBox, int toMethod, CallLook look) {}

    /** A call between a copied method ({box, method}) and a method that wasn't copied (class name, method name). */
    record Outside(int box, int method, String otherClass, String otherMethod, boolean outgoing, double otherX, double otherY,
                   CallLook look, BoxCopy other, MethodCopy otherM) {}

    /** A call by class and method names (between tabs), with its look. */
    record NamedCall(String fromClass, String fromMethod, String toClass, String toMethod, CallLook look) {}

    /** Where pasted classes go. */
    enum Place { RIGHT, BELOW, AS_THEY_WERE }

    /** The boxes a paste made, and how many of their calls to classes already here it drew. */
    record Pasted(List<FlowDiagram.Box> boxes, int kept) {}

    /** A move or copy: what was copied, the boxes made in the other tab, and the calls across the two tabs. */
    record Sent(Clip clip, List<FlowDiagram.Box> pasted, List<Outside> across) {}

    /** Link boxes are the classes themselves (with their code), marked "⇄ in “the tab”" in their note. */
    static final String LINK = "⇄ in ";
    /** The frame of a link box (a class that is in another tab). */
    static final Color LINK_COLOR = new Color(0xE5A83B);

    static boolean isLink(FlowDiagram.Box b) {
        return b.note != null && b.note.startsWith(LINK);
    }

    private final Supplier<FlowDiagram> diagram;
    private final FlowDiagramPainter painter;
    private final Function<FlowDiagram.Method, List<FlowEntry>> entries;
    /** Where classes that were moved here stood in the tab they came from (to put them back in a combined tab). */
    private final Map<String, double[]> homes = new HashMap<>();

    /**
     * @param diagram the tab's diagram now (Undo and the History give it another one)
     * @param entries a method's lines: the flow's own and the ones picked in the tab (copies)
     */
    FlowDiagramTabs(Supplier<FlowDiagram> diagram, FlowDiagramPainter painter, Function<FlowDiagram.Method, List<FlowEntry>> entries) {
        this.diagram = diagram;
        this.painter = painter;
        this.entries = entries;
    }

    FlowDiagram diagram() {
        return diagram.get();
    }

    /** Where a class moved here stood in the tab it came from (null when it wasn't moved here). */
    @Nullable double[] homeOf(String className) {
        return homes.get(className);
    }

    boolean hasHomes() {
        return !homes.isEmpty();
    }

    // ---------- copy and paste ----------

    CallLook lookOf(FlowDiagram.Link l) {
        FlowDiagram d = diagram();
        return new CallLook(d.callNote(l), d.noteStyle(l), d.line(l), l.manual());
    }

    /** Connects two methods (unless they are already) and gives the call its note and styles. */
    void connect(@Nullable FlowDiagram.Method from, @Nullable FlowDiagram.Method to, @Nullable CallLook look) {
        if (from == null || to == null || from == to) return;
        FlowDiagram d = diagram();
        // a call the code makes stays a code call (solid); only calls connected by hand are drawn dashed
        boolean manual = look == null || look.manual();
        d.connect(from.id, to.id, manual);
        if (look == null) return;
        FlowDiagram.Link l = new FlowDiagram.Link(from.id, to.id, manual, null);
        if (!look.note().isEmpty() && d.callNote(l).isEmpty()) d.setCallNote(l, look.note());
        if (!look.noteStyle().equals(FlowDiagram.NoteStyle.DEFAULT)) d.setNoteStyle(l, look.noteStyle());
        if (!look.line().equals(FlowDiagram.Line.DEFAULT)) d.setLine(l, look.line());
    }

    private MethodCopy methodCopy(FlowDiagram.Method m) {
        return new MethodCopy(m.name, m.unknown && m.picked.isEmpty(), entries.apply(m), m.tag, m.tagSet, m.note, m.format, m.noteFormat);
    }

    /** The classes with their methods, lines and looks, the calls between them and their calls to the others. */
    Clip copyOf(List<FlowDiagram.Box> boxes) {
        FlowDiagram d = diagram();
        List<BoxCopy> out = new ArrayList<>();
        for (FlowDiagram.Box b : boxes) {
            List<MethodCopy> ms = new ArrayList<>();
            for (FlowDiagram.Method m : b.methods) ms.add(methodCopy(m));
            out.add(new BoxCopy(b.name, b.fileUrl, b.fileName, b.projectName == null ? "" : b.projectName, b.origClass,
                    b.isUnknown(), b.x, b.y, ms, b.copy()));
        }
        List<LinkCopy> links = new ArrayList<>();
        List<Outside> outside = new ArrayList<>();
        for (FlowDiagram.Link l : d.links) {
            int[] from = place(boxes, l.from()), to = place(boxes, l.to());
            if (from != null && to != null) {
                links.add(new LinkCopy(from[0], from[1], to[0], to[1], lookOf(l)));
            } else if (from != null || to != null) {
                FlowDiagram.Method other = d.method(from != null ? l.to() : l.from());
                FlowDiagram.Box ob = other == null ? null : d.boxOf(other.id);
                if (ob != null) {
                    int[] in = from != null ? from : to;
                    MethodCopy om = methodCopy(other);
                    BoxCopy obc = new BoxCopy(ob.name, ob.fileUrl, ob.fileName, ob.projectName == null ? "" : ob.projectName,
                            ob.origClass, ob.isUnknown(), ob.x, ob.y, List.of(om));
                    outside.add(new Outside(in[0], in[1], ob.name, other.name, from != null, ob.x, ob.y, lookOf(l), obc, om));
                }
            }
        }
        return new Clip(out, links, outside);
    }

    /** Where a method is among copied boxes: {box, method}, or null when it isn't copied. */
    private static @Nullable int[] place(List<FlowDiagram.Box> boxes, String methodId) {
        for (int i = 0; i < boxes.size(); i++) {
            List<FlowDiagram.Method> ms = boxes.get(i).methods;
            for (int j = 0; j < ms.size(); j++) if (ms.get(j).id.equals(methodId)) return new int[]{i, j};
        }
        return null;
    }

    /** Every class of the tab. */
    Clip copyAll() {
        return copyOf(new ArrayList<>(diagram().boxes));
    }

    /** Every class but the link boxes (to combine tabs). */
    Clip copyReal() {
        List<FlowDiagram.Box> real = new ArrayList<>();
        for (FlowDiagram.Box b : diagram().boxes) if (!isLink(b)) real.add(b);
        return copyOf(real);
    }

    /**
     * Pastes copied classes: their looks, tags, notes and formats come along (shown like the classes they were: no
     * "added" badge), with the calls between them and to the classes here they called.
     */
    Pasted paste(Clip clip, Place where) {
        FlowDiagram d = diagram();
        List<FlowDiagram.Box> boxesMade = new ArrayList<>();
        if (clip == null || clip.boxes().isEmpty()) return new Pasted(boxesMade, 0);
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        for (BoxCopy c : clip.boxes()) {
            minX = Math.min(minX, c.x());
            minY = Math.min(minY, c.y());
        }
        double maxX = 40, maxY = 40;
        for (FlowDiagram.Box b : d.boxes) {
            maxX = Math.max(maxX, b.x + painter.width(b) + 60);
            maxY = Math.max(maxY, b.y + painter.height(b) + 120);
        }
        boolean empty = d.boxes.isEmpty();
        List<List<FlowDiagram.Method>> made = new ArrayList<>();
        for (BoxCopy c : clip.boxes()) {
            double x = switch (where) {
                case AS_THEY_WERE -> c.x();
                case BELOW -> 40 + (c.x() - minX);
                default -> maxX + (c.x() - minX);
            };
            double y = switch (where) {
                case AS_THEY_WERE -> c.y();
                case BELOW -> (empty ? 40 : maxY) + (c.y() - minY);
                default -> 40 + (c.y() - minY);
            };
            FlowDiagram.Box box = c.unknown() ? d.addUnknownClass(c.name(), c.fileName(), x, y)
                    : d.addKnownClass(c.fileUrl(), c.fileName(), c.projectName(), c.origClass(), null, x, y);
            if (c.look() != null) box.copyLook(c.look()); // colors, note, text formats, project style
            box.moved = true;
            List<FlowDiagram.Method> ms = new ArrayList<>();
            for (MethodCopy m : c.methods()) {
                FlowDiagram.Method nm = m.unknown() || m.lines().isEmpty() ? d.addUnknownMethod(box, m.name())
                        : d.addKnownMethod(box, m.name(), m.lines());
                nm.moved = true;
                if (m.tagSet() || !m.tag().isEmpty()) d.setMethodTag(nm, m.tag().isEmpty() ? null : m.tag());
                if (!m.note().isEmpty()) nm.note = m.note();
                if (m.format() != null) nm.format = m.format();
                if (m.noteFormat() != null) nm.noteFormat = m.noteFormat();
                ms.add(nm);
            }
            made.add(ms);
            boxesMade.add(box);
        }
        for (LinkCopy l : clip.links()) connect(made.get(l.fromBox()).get(l.fromMethod()), made.get(l.toBox()).get(l.toMethod()), l.look());
        int kept = 0;
        for (Outside o : clip.outside()) { // calls to classes this tab has too (not link boxes: those are made apart)
            FlowDiagram.Method here = made.get(o.box()).get(o.method()), there = realMethod(o.otherClass(), o.otherMethod());
            if (here == null || there == null || here == there) continue;
            if (o.outgoing()) connect(here, there, o.look());
            else connect(there, here, o.look());
            kept++;
        }
        return new Pasted(boxesMade, kept);
    }

    /** A method of a real class here (not a link box) by the class's and the method's names. */
    @Nullable FlowDiagram.Method realMethod(String className, String methodName) {
        for (FlowDiagram.Box b : diagram().boxes) {
            if (isLink(b) || !b.name.equals(className)) continue;
            for (FlowDiagram.Method m : b.methods) if (m.name.equals(methodName)) return m;
        }
        return null;
    }

    // ---------- moving and copying to another tab ----------

    /**
     * The classes go (or are copied) from one tab to another: pasted there (where they were, in a new tab; else on the
     * right), and where they are connected to classes that stay, link boxes on both sides with the calls.
     *
     * @param fresh the other tab is new (its classes are laid out after)
     */
    static Sent send(FlowDiagramTabs from, String fromTab, FlowDiagramTabs to, String toTab, List<FlowDiagram.Box> boxes,
                     boolean move, boolean fresh) {
        Clip clip = from.copyOf(boxes);
        Pasted pasted = to.paste(clip, fresh ? Place.AS_THEY_WERE : Place.RIGHT);
        for (BoxCopy c : clip.boxes()) to.homes.put(c.name(), new double[]{c.x(), c.y()});
        // the calls between the classes that go and the ones that stay (not the ones the other tab has itself)
        List<Outside> across = new ArrayList<>();
        for (Outside o : clip.outside()) if (to.realMethod(o.otherClass(), o.otherMethod()) == null) across.add(o);
        if (move) for (FlowDiagram.Box b : boxes) from.diagram().removeClass(b);
        if (!across.isEmpty()) {
            if (move) from.leaveLinkBoxes(clip, across, toTab);
            to.receiveLinkBoxes(clip, across, fromTab, pasted.boxes());
        }
        if (fresh) to.layOut();
        return new Sent(clip, pasted.boxes(), across);
    }

    /**
     * A link box (one per class and tab): the class from the code (click its methods to see their code), with
     * "⇄ in “the other tab”" under its name and the link orange frame.
     */
    private FlowDiagram.Box linkBox(BoxCopy cls, String tab, double x, double y) {
        FlowDiagram d = diagram();
        String mark = LINK + "“" + tab + "”";
        for (FlowDiagram.Box b : d.boxes) if (isLink(b) && b.name.equals(cls.name()) && mark.equals(b.note)) return b;
        FlowDiagram.Box box = cls.unknown() ? d.addUnknownClass(cls.name(), cls.fileName(), x, y)
                : d.addKnownClass(cls.fileUrl(), cls.fileName(), cls.projectName(), cls.origClass(), null, x, y);
        box.note = mark;
        box.moved = true; // not "added": it is a class of the other tab
        // always the link orange (on both sides): a tab's own color can be the selection blue, which hides it
        box.border = LINK_COLOR;
        return box;
    }

    private FlowDiagram.Method linkMethod(FlowDiagram.Box box, MethodCopy m) {
        FlowDiagram d = diagram();
        for (FlowDiagram.Method x : box.methods) if (x.name.equals(m.name())) return x;
        FlowDiagram.Method made = m.unknown() || m.lines().isEmpty() || box.isUnknown() ? d.addUnknownMethod(box, m.name())
                : d.addKnownMethod(box, m.name(), m.lines());
        made.moved = true;
        return made;
    }

    /** Here (the tab the classes left): each moved class becomes a link box in its place, connected as it was. */
    void leaveLinkBoxes(Clip clip, List<Outside> across, String otherTab) {
        for (Outside o : across) {
            BoxCopy moved = clip.boxes().get(o.box());
            FlowDiagram.Box box = linkBox(moved, otherTab, moved.x(), moved.y());
            FlowDiagram.Method lm = linkMethod(box, moved.methods().get(o.method()));
            FlowDiagram.Method stay = realMethod(o.otherClass(), o.otherMethod());
            if (stay == null) continue;
            if (o.outgoing()) connect(lm, stay, o.look());
            else connect(stay, lm, o.look());
        }
    }

    /** There (the tab the classes went to): the other tab's classes they call or are called by, as link boxes around them. */
    void receiveLinkBoxes(Clip clip, List<Outside> across, String otherTab, List<FlowDiagram.Box> pasted) {
        double left = Double.MAX_VALUE, right = 0, top = Double.MAX_VALUE;
        for (FlowDiagram.Box b : pasted) {
            left = Math.min(left, b.x);
            right = Math.max(right, b.x + painter.width(b));
            top = Math.min(top, b.y);
        }
        int callers = 0, called = 0;
        for (Outside o : across) {
            FlowDiagram.Method here = realMethod(clip.boxes().get(o.box()).name(), clip.boxes().get(o.box()).methods().get(o.method()).name());
            if (here == null) continue;
            boolean callsIn = !o.outgoing(); // the other class calls the moved one: it goes before it
            FlowDiagram.Box existing = null;
            for (FlowDiagram.Box b : diagram().boxes) if (isLink(b) && b.name.equals(o.otherClass())) existing = b;
            double x = callsIn ? Math.max(40, left - 360) : right + 120;
            double y = top + (callsIn ? callers : called) * 150;
            FlowDiagram.Box box = existing != null ? existing : linkBox(o.other(), otherTab, x, y);
            if (existing == null) {
                if (callsIn) callers++;
                else called++;
            }
            FlowDiagram.Method lm = linkMethod(box, o.otherM());
            if (callsIn) connect(lm, here, o.look());
            else connect(here, lm, o.look());
        }
    }

    /** Every call goes left to right, with room between the boxes for the calls' notes. */
    void layOut() {
        diagram().autoLayout(painter.measure(), 180, 50, 40);
    }

    // ---------- two tabs combined ----------

    /** The calls through link boxes, as the real classes are named (a link box has its class's name), with their looks. */
    List<NamedCall> linkCalls() {
        FlowDiagram d = diagram();
        List<NamedCall> out = new ArrayList<>();
        for (FlowDiagram.Link l : d.links) {
            FlowDiagram.Box fb = d.boxOf(l.from()), tb = d.boxOf(l.to());
            FlowDiagram.Method fm = d.method(l.from()), tm = d.method(l.to());
            if (fb == null || tb == null || fm == null || tm == null || !(isLink(fb) || isLink(tb))) continue;
            out.add(new NamedCall(fb.name, fm.name, tb.name, tm.name, lookOf(l)));
        }
        return out;
    }

    /** Connects two real classes' methods by name (the calls of combined tabs), with the call's look. */
    void connectByName(NamedCall c) {
        connect(realMethod(c.fromClass(), c.fromMethod()), realMethod(c.toClass(), c.toMethod()), c.look());
    }

    /**
     * This tab shows two tabs combined: the one the classes came from as it is (its layout is the base), the moved
     * classes back where they stood (the other tab's other classes under it), and the calls between them through the
     * link boxes, with their notes; then laid out left to right.
     */
    void combine(FlowDiagramTabs a, String aTab, FlowDiagramTabs b, String bTab) {
        if (a.hasHomes() && !b.hasHomes()) { // b is where the classes came from: its layout is the base
            FlowDiagramTabs t = a;
            a = b;
            b = t;
        }
        List<FlowDiagram.Box> first = paste(a.copyReal(), Place.AS_THEY_WERE).boxes();
        double below = 40;
        for (FlowDiagram.Box x : first) below = Math.max(below, x.y + painter.height(x) + 120);
        Clip second = b.copyReal();
        double minY = Double.MAX_VALUE;
        for (BoxCopy c : second.boxes()) if (b.homeOf(c.name()) == null) minY = Math.min(minY, c.y());
        List<BoxCopy> placed = new ArrayList<>();
        for (BoxCopy c : second.boxes()) {
            double[] home = b.homeOf(c.name());
            double x = home != null ? home[0] : c.x(), y = home != null ? home[1] : below + (c.y() - minY);
            placed.add(new BoxCopy(c.name(), c.fileUrl(), c.fileName(), c.projectName(), c.origClass(), c.unknown(), x, y,
                    c.methods(), c.look()));
        }
        List<FlowDiagram.Box> back = paste(new Clip(placed, second.links(), second.outside()), Place.AS_THEY_WERE).boxes();
        for (FlowDiagram.Box x : back) if (b.homeOf(x.name) != null) x.border = null; // back in place: no "moved" frame
        List<NamedCall> calls = new ArrayList<>(a.linkCalls());
        calls.addAll(b.linkCalls());
        for (NamedCall c : calls) connectByName(c); // their notes and styles, where the code makes the call
        layOut();
    }
}
