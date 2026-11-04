package com.example.flowtracker.diagram;

import com.example.flowtracker.code.FlowSync;
import com.example.flowtracker.model.FlowEntry;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.example.flowtracker.Fixtures.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Classes between the Flow Diagram's tabs: copy, move with link boxes on both sides, and two tabs combined. */
class FlowDiagramTabsTest {

    private final FlowEntry main19 = line("Main.java", "Main", "main()", 19, "bookingService.bookTrip(101);");
    private final FlowEntry book33 = line("BookingService.java", "BookingService", "bookTrip()", 33, "travel.getDestination(), nights);");
    private final FlowEntry dest43 = line("Travel.java", "Travel", "getDestination()", 43, "public String getDestination() {");
    private final FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));

    /** A tab: its diagram, with the methods' lines as the tab gives them. */
    private final class Tab {
        FlowDiagram diagram;
        final FlowDiagramTabs tabs;

        Tab(FlowDiagram d) {
            diagram = d;
            tabs = new FlowDiagramTabs(() -> diagram, painter, m -> {
                List<FlowEntry> out = new ArrayList<>();
                for (FlowEntry e : List.of(main19, book33, dest43)) if (m.origKey != null && m.origKey.equals(FlowSync.methodKey(e))) out.add(e.copy());
                out.addAll(m.picked);
                return out;
            });
        }
    }

    /** Main.main() → BookingService.bookTrip() → Travel.getDestination(), found in the code, laid out left to right. */
    private Tab flow() {
        FlowDiagram d = FlowDiagram.of(List.of(main19, book33, dest43), Set.of(
                FlowSync.link(FlowSync.methodKey(main19), FlowSync.methodKey(book33)),
                FlowSync.link(FlowSync.methodKey(book33), FlowSync.methodKey(dest43))));
        d.autoLayout(painter.measure(), 180, 50, 40);
        return new Tab(d);
    }

    private static FlowDiagram.Box box(FlowDiagram d, String name, boolean link) {
        return d.boxes.stream().filter(b -> b.name.equals(name) && FlowDiagramTabs.isLink(b) == link).findFirst()
                .orElseThrow(() -> new AssertionError("no " + (link ? "link box " : "class ") + name + " in " + names(d)));
    }

    private static List<String> names(FlowDiagram d) {
        return d.boxes.stream().map(b -> b.name + (FlowDiagramTabs.isLink(b) ? " (link)" : "")).toList();
    }

    /** "Main.main() > BookingService.bookTrip()" for every call. */
    private static List<String> calls(FlowDiagram d) {
        List<String> out = new ArrayList<>();
        for (FlowDiagram.Link l : d.links) {
            out.add(d.boxOf(l.from()).name + "." + d.method(l.from()).name + " > " + d.boxOf(l.to()).name + "." + d.method(l.to()).name
                    + (l.manual() ? " (by hand)" : ""));
        }
        return out;
    }

    @Test
    void movingAClassLeavesLinkBoxesOnBothSides() {
        Tab a = flow(), b = new Tab(FlowDiagram.of(List.of(), Set.of()));
        FlowDiagram.Box booking = box(a.diagram, "BookingService", false);
        double x = booking.x, y = booking.y;

        FlowDiagramTabs.Sent sent = FlowDiagramTabs.send(a.tabs, "Flow 2", b.tabs, "Flow 2 – BookingService",
                List.of(booking), true, true);
        assertEquals(2, sent.across().size(), "its call from Main and its call to Travel");

        // the tab it left: Main and Travel stay, BookingService is a link box in its place, connected as it was
        assertEquals(Set.of("Main", "Travel", "BookingService (link)"), Set.copyOf(names(a.diagram)));
        FlowDiagram.Box left = box(a.diagram, "BookingService", true);
        assertEquals("⇄ in “Flow 2 – BookingService”", left.note);
        assertEquals(FlowDiagramTabs.LINK_COLOR, left.border, "the link orange");
        assertEquals(x, left.x);
        assertEquals(y, left.y);
        assertTrue(calls(a.diagram).containsAll(List.of("Main.main() > BookingService.bookTrip()",
                "BookingService.bookTrip() > Travel.getDestination()")), calls(a.diagram).toString());
        assertTrue(calls(a.diagram).stream().noneMatch(c -> c.endsWith("(by hand)")), "code calls stay code calls (solid)");

        // the tab it went to: the class itself, Main (calls it) on the left, Travel (it calls) on the right
        assertEquals(Set.of("BookingService", "Main (link)", "Travel (link)"), Set.copyOf(names(b.diagram)));
        FlowDiagram.Box moved = box(b.diagram, "BookingService", false);
        FlowDiagram.Box caller = box(b.diagram, "Main", true), callee = box(b.diagram, "Travel", true);
        assertEquals("⇄ in “Flow 2”", caller.note);
        assertEquals(FlowDiagramTabs.LINK_COLOR, callee.border);
        assertTrue(caller.x < moved.x && moved.x < callee.x, "callers left, called classes right");
        assertTrue(moved.moved, "no \"added\" badge on a moved class");
        assertTrue(calls(b.diagram).containsAll(List.of("Main.main() > BookingService.bookTrip()",
                "BookingService.bookTrip() > Travel.getDestination()")), calls(b.diagram).toString());
        assertNotNull(b.tabs.homeOf("BookingService"), "where it stood is kept, to put it back when combined");
    }

    @Test
    void copyingKeepsTheClassWhereItWas() {
        Tab a = flow(), b = new Tab(FlowDiagram.of(List.of(), Set.of()));
        FlowDiagramTabs.send(a.tabs, "Flow 2", b.tabs, "Copy", List.of(box(a.diagram, "BookingService", false)), false, true);
        assertEquals(Set.of("Main", "BookingService", "Travel"), Set.copyOf(names(a.diagram)), "nothing changes in the first tab");
        assertEquals(Set.of("BookingService", "Main (link)", "Travel (link)"), Set.copyOf(names(b.diagram)));
    }

    @Test
    void tagsNotesAndCallNotesTravelWithTheClasses() {
        Tab a = flow(), b = new Tab(FlowDiagram.of(List.of(), Set.of()));
        FlowDiagram.Box booking = box(a.diagram, "BookingService", false);
        FlowDiagram.Box travel = box(a.diagram, "Travel", false);
        FlowDiagram.Method bookTrip = booking.methods.get(0);
        a.diagram.setMethodTag(bookTrip, "BUG");
        bookTrip.note = "stores the booking";
        FlowDiagram.Link call = a.diagram.links.stream().filter(l -> l.from().equals(bookTrip.id)).findFirst().orElseThrow();
        a.diagram.setCallNote(call, "REST: GET /destination");

        FlowDiagramTabs.send(a.tabs, "Flow 2", b.tabs, "Two", List.of(booking, travel), true, true);
        FlowDiagram.Method copied = box(b.diagram, "BookingService", false).methods.get(0);
        assertEquals("BUG", copied.tag);
        assertEquals("stores the booking", copied.note);
        FlowDiagram.Link copiedCall = b.diagram.links.stream().filter(l -> l.from().equals(copied.id)).findFirst().orElseThrow();
        assertEquals("REST: GET /destination", b.diagram.callNote(copiedCall), "the call between the two moved classes keeps its note");
        assertTrue(copied.moved, "no \"(+1)\" on moved methods");
    }

    @Test
    void pastingNextToTheClassesThere() {
        Tab a = flow();
        Tab b = new Tab(FlowDiagram.of(List.of(book33, dest43), Set.of(FlowSync.link(FlowSync.methodKey(book33), FlowSync.methodKey(dest43)))));
        b.diagram.autoLayout(painter.measure(), 180, 50, 40);
        double rightEdge = b.diagram.boxes.stream().mapToDouble(x -> x.x + painter.width(x)).max().orElse(0);
        FlowDiagramTabs.Pasted p = b.tabs.paste(a.tabs.copyOf(List.of(box(a.diagram, "Main", false))), FlowDiagramTabs.Place.RIGHT);
        assertEquals(1, p.boxes().size());
        assertTrue(p.boxes().get(0).x >= rightEdge, "on the right of what the tab has");
        assertEquals(1, p.kept(), "its call to BookingService (which the tab has) is drawn");
    }

    @Test
    void aClassTheTabHasAlreadyIsNotPastedTwice() {
        Tab a = flow(), b = flow();
        FlowDiagramTabs.Pasted p = b.tabs.paste(a.tabs.copyOf(List.of(box(a.diagram, "Main", false))), FlowDiagramTabs.Place.RIGHT);
        assertEquals(box(b.diagram, "Main", false), p.boxes().get(0), "its own box");
        assertEquals(3, b.diagram.boxes.size());
    }

    @Test
    void twoTabsCombinedPutTheMovedClassesBack() {
        Tab a = flow(), b = new Tab(FlowDiagram.of(List.of(), Set.of()));
        FlowDiagramTabs.send(a.tabs, "Flow 2", b.tabs, "Flow 2 – BookingService", List.of(box(a.diagram, "BookingService", false)), true, true);

        Tab both = new Tab(FlowDiagram.of(List.of(), Set.of()));
        both.tabs.combine(b.tabs, "Flow 2 – BookingService", a.tabs, "Flow 2"); // in either order
        assertEquals(Set.of("Main", "Travel", "BookingService"), Set.copyOf(names(both.diagram)), "real classes only, no link boxes");
        assertTrue(calls(both.diagram).containsAll(List.of("Main.main() > BookingService.bookTrip()",
                "BookingService.bookTrip() > Travel.getDestination()")), calls(both.diagram).toString());
        FlowDiagram.Box main = box(both.diagram, "Main", false), booking = box(both.diagram, "BookingService", false),
                travel = box(both.diagram, "Travel", false);
        assertTrue(main.x < booking.x && booking.x < travel.x, "laid out left to right");
        assertNull(booking.border, "back in place: no frame");
        assertFalse(both.diagram.links.stream().anyMatch(FlowDiagram.Link::manual));
    }
}
