package com.example.flowtracker.diagram;

import com.example.flowtracker.code.FlowCodeSearch;
import com.example.flowtracker.code.FlowSync;
import com.example.flowtracker.copy.FlowFormat;
import com.example.flowtracker.copy.FlowMarkdown;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowStore;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;

import static com.example.flowtracker.Fixtures.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Flow Diagram: built from lines, edited, written back to lines, drawn as images. */
class FlowDiagramTest {

    private final FlowEntry main8 = line("Main.java", "Main", "main()", 8, "BookingService s = new BookingService();");
    private final FlowEntry main13 = line("Main.java", "Main", "main()", 13, "s.bookTrip(\"Ann\", 3);");
    private final FlowEntry field = line("Main.java", "Main", "[fields]", 4, "static int count;");
    private final FlowEntry book21 = line("BookingService.java", "BookingService", "bookTrip()", 21, "if (name == null) {");
    private final FlowEntry cancel55 = line("BookingService.java", "BookingService", "cancel()", 55, "bookings.remove(id);");

    private final String mainKey = FlowSync.methodKey(main8);
    private final String bookKey = FlowSync.methodKey(book21);
    private final String cancelKey = FlowSync.methodKey(cancel55);

    private List<FlowEntry> flow() {
        return List.of(field, main8, main13, book21, cancel55);
    }

    /** main() calls bookTrip() (found in the code). */
    private FlowDiagram diagram() {
        return FlowDiagram.of(flow(), Set.of(FlowSync.link(mainKey, bookKey)));
    }

    private static FlowDiagram.Box box(FlowDiagram d, String name) {
        return d.boxes.stream().filter(b -> b.name.equals(name)).findFirst().orElseThrow();
    }

    private static FlowDiagram.Method method(FlowDiagram.Box b, String name) {
        return b.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    @Test
    void oneBoxPerClassWithItsMethodsAndCalls() {
        FlowDiagram d = diagram();
        assertEquals(List.of("Main", "BookingService"), d.boxes.stream().map(b -> b.name).toList());
        FlowDiagram.Box main = box(d, "Main");
        assertEquals(List.of("main()"), main.methods.stream().map(m -> m.name).toList(), "fields get no method row");
        assertEquals(2, method(main, "main()").lineCount());
        assertEquals("Main.java  ·  TravelBooking", main.subtitle(), "the file and the project it is in");
        assertEquals(1, d.links.size());
        assertFalse(d.links.get(0).manual(), "found in the code");
    }

    @Test
    void nothingChangedKeepsTheSameLines() {
        FlowDiagram.Result r = diagram().apply(flow());
        assertTrue(r.removed().isEmpty());
        assertEquals(flow().size(), r.entries().size());
        for (int i = 0; i < flow().size(); i++) assertSame(flow().get(i), r.entries().get(i));
    }

    @Test
    void renamesChangeTheLinesAndKeepConnections() {
        FlowEntry holder = line("Main.java", "Main", "main()", 14, "api.post(order);");
        holder.calls.add(cancelKey); // connected by hand
        List<FlowEntry> flow = List.of(main8, holder, book21, cancel55);
        FlowDiagram d = FlowDiagram.of(flow, Set.of());
        assertTrue(d.links.get(0).manual());

        d.renameClass(box(d, "BookingService"), "TripService");
        d.renameMethod(method(box(d, "TripService"), "cancel()"), "cancelTrip");
        FlowDiagram.Result r = d.apply(flow);

        assertSame(main8, r.entries().get(0), "unchanged lines stay the same objects");
        FlowEntry cancel = r.entries().get(3);
        assertNotSame(cancel55, cancel, "changed lines are copies; the flow before stays for Undo");
        assertEquals("TripService", cancel.className);
        assertEquals("cancelTrip()", cancel.methodName);
        assertEquals("BookingService", cancel55.className, "the original is untouched");
        assertEquals(List.of(FlowSync.methodKey(cancel)), r.entries().get(1).calls,
                "the connection follows the rename and stays on the line that held it");
    }

    @Test
    void removedClassesAndMethodsLeaveTheFlow() {
        FlowDiagram d = diagram();
        d.removeMethod(method(box(d, "BookingService"), "cancel()"));
        d.removeClass(box(d, "Main"));
        assertTrue(d.links.isEmpty(), "calls of a removed class go too");
        FlowDiagram.Result r = d.apply(flow());
        assertEquals(List.of(book21), r.entries());
        assertEquals(List.of(field, main8, main13, cancel55), r.removed());
    }

    @Test
    void addedMethodsAndClassesBecomeDiagramLines() {
        FlowDiagram d = diagram();
        FlowDiagram.Box service = box(d, "BookingService");
        FlowDiagram.Method validate = d.addUnknownMethod(service, "validate");
        assertEquals("validate()", validate.name);
        FlowDiagram.Box payment = d.addUnknownClass("PaymentGateway", 500, 40);
        FlowDiagram.Method charge = d.addUnknownMethod(payment, "charge()");
        assertTrue(d.connect(validate.id, charge.id));
        assertFalse(d.connect(validate.id, charge.id), "no duplicate calls");
        d.addUnknownClass("AuditLog", 500, 200); // no methods

        List<FlowEntry> out = d.apply(flow()).entries();
        FlowEntry v = out.get(5);
        assertEquals("validate()", v.methodName);
        assertEquals(book21.fileUrl, v.fileUrl, "placed in its class's file, after its other lines");
        assertEquals(0, v.line);
        assertTrue(FlowDiagram.isDiagramOnly(v));
        assertEquals("+", FlowFormat.lineNumber(v));

        FlowEntry c = out.get(6);
        assertEquals(FlowDiagram.DIAGRAM_URL + "PaymentGateway", c.fileUrl);
        assertEquals("PaymentGateway", c.className);
        assertEquals("charge()", c.methodName);
        assertEquals(List.of(FlowSync.methodKey(c)), v.calls, "drawn calls become manual connections");

        FlowEntry audit = out.get(7);
        assertEquals("AuditLog", audit.className);
        assertEquals(FlowDiagram.NEW_CLASS_TEXT, audit.text);
    }

    @Test
    void classesAddedEarlierCanBeEditedAgain() {
        FlowDiagram first = diagram();
        FlowDiagram.Box payment = first.addUnknownClass("PaymentGateway", 0, 0);
        first.addUnknownMethod(payment, "charge");
        List<FlowEntry> once = first.apply(flow()).entries();

        FlowDiagram again = FlowDiagram.of(once, Set.of());
        FlowDiagram.Box reopened = box(again, "PaymentGateway");
        assertTrue(reopened.isUnknown());
        assertTrue(method(reopened, "charge()").unknown);
        FlowDiagram.Result same = again.apply(once);
        assertTrue(same.removed().isEmpty());
        for (int i = 0; i < once.size(); i++) assertSame(once.get(i), same.entries().get(i), "a round trip changes nothing");

        again.renameClass(reopened, "Payments");
        FlowEntry renamed = again.apply(once).entries().stream().filter(FlowDiagram::isDiagramOnly).findFirst().orElseThrow();
        assertEquals(FlowDiagram.DIAGRAM_URL + "Payments", renamed.fileUrl);
        assertEquals("Payments", renamed.fileName);
    }

    @Test
    void codeCallsCantBeRemovedButManualOnesCan() {
        FlowDiagram d = diagram();
        FlowDiagram.Link code = d.links.get(0);
        assertFalse(d.disconnect(code));
        FlowDiagram.Method cancel = method(box(d, "BookingService"), "cancel()");
        FlowDiagram.Method main = method(box(d, "Main"), "main()");
        d.connect(main.id, cancel.id);
        FlowDiagram.Link manual = d.links.get(1);
        assertTrue(d.disconnect(manual));
        assertEquals(1, d.links.size());
    }

    @Test
    void namesAreChecked() {
        FlowDiagram d = diagram();
        assertNotNull(d.checkClassName(null, "Main"), "taken");
        assertNotNull(d.checkClassName(null, " "), "empty");
        assertNotNull(d.checkClassName(null, "has space"));
        assertNull(d.checkClassName(null, "PaymentGateway"));
        FlowDiagram.Box service = box(d, "BookingService");
        assertNotNull(d.checkMethodName(service, null, "cancel"), "taken (with or without parentheses)");
        assertNull(d.checkMethodName(service, method(service, "cancel()"), "cancel"), "its own name is fine");
        assertNull(d.checkMethodName(service, null, "refund()"));
    }

    @Test
    void historyCopiesAreIndependent() {
        FlowDiagram d = diagram();
        FlowDiagram snapshot = d.copy();
        d.renameClass(box(d, "Main"), "App");
        d.addUnknownMethod(box(d, "App"), "run");
        assertEquals("Main", snapshot.boxes.get(0).name);
        assertEquals(1, snapshot.boxes.get(0).methods.size());
        assertFalse(snapshot.signature().equals(d.signature()));
        box(d, "App").x += 50;
        assertEquals(d.signature(), d.copy().signature(), "moving a box isn't a change to the flow");
    }

    @Test
    void sequenceFollowsTheCalls() {
        FlowDiagram d = diagram();
        FlowDiagram.Method book = method(box(d, "BookingService"), "bookTrip()");
        FlowDiagram.Method cancel = method(box(d, "BookingService"), "cancel()");
        d.connect(book.id, cancel.id);
        List<String> steps = d.sequence().stream().map(s -> {
            if (s instanceof FlowDiagram.Start st) return "note " + st.box().name + ": " + st.method().name;
            if (s instanceof FlowDiagram.Call c) return c.from().name + " -> " + c.to().name + ": " + c.method().name;
            FlowDiagram.Return r = (FlowDiagram.Return) s;
            return r.from().name + " --> " + r.to().name;
        }).toList();
        assertEquals(List.of(
                "note Main: main()",
                "Main -> BookingService: bookTrip()",
                "BookingService -> BookingService: cancel()",
                "BookingService --> BookingService",
                "BookingService --> Main"), steps);
    }

    @Test
    void autoLayoutPutsCallersLeftOfCallees() {
        FlowDiagram d = diagram();
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        painter.autoLayout(d);
        assertTrue(box(d, "Main").x < box(d, "BookingService").x);
    }

    @Test
    void spreadOutMovesBoxesApartAndKeepsThePinnedOne() {
        FlowDiagram d = diagram();
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        painter.autoLayout(d);
        assertFalse(d.spreadOut(painter.measure(), 24, null)); // the layout has no overlaps
        FlowDiagram.Box wide = d.addUnknownClass("travel", 0, 0);
        d.addUnknownMethod(wide, "addEventListener(\"submit\") callback with a long name");
        FlowDiagram.Box main = box(d, "Main");
        wide.x = main.x + 10;
        wide.y = main.y + 10;
        assertTrue(d.spreadOut(painter.measure(), 24, wide));
        assertEquals(main.x + 10, wide.x, 0.001); // pinned: stays where it was put
        for (FlowDiagram.Box a : d.boxes) {
            for (FlowDiagram.Box b : d.boxes) {
                if (a != b) assertFalse(painter.bounds(a).intersects(painter.bounds(b)), a.name + " covers " + b.name);
            }
        }
    }

    @Test
    void linesGoAroundBoxesInTheirWay() {
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        for (FlowDiagram.LineShape shape : FlowDiagram.LineShape.values()) {
            FlowDiagram d = new FlowDiagram();
            FlowDiagram.Box main = d.addUnknownClass("Main", 40, 40);
            FlowDiagram.Method run = d.addUnknownMethod(main, "main()");
            FlowDiagram.Box travel = d.addUnknownClass("travel", 40, 40 + painter.height(main) + 50);
            d.addUnknownMethod(travel, "addEventListener(\"submit\") callback with a long name");
            FlowDiagram.Box booking = d.addUnknownClass("BookingService", travel.x + painter.width(travel) + 40, travel.y);
            FlowDiagram.Method book = d.addUnknownMethod(booking, "bookTrip()");
            assertTrue(d.connect(run.id, book.id));
            FlowDiagram.Link link = d.links.get(0);
            d.setLine(link, new FlowDiagram.Line(FlowDiagram.LinePattern.AUTO, FlowDiagram.LineWidth.NORMAL, shape,
                    FlowDiagram.ArrowHead.FILLED, null));
            java.awt.geom.Area through = new java.awt.geom.Area(new java.awt.BasicStroke(1).createStrokedShape(painter.linkPath(d, link)));
            through.intersect(new java.awt.geom.Area(painter.bounds(travel)));
            assertTrue(through.isEmpty(), shape + " line runs through the travel box");
        }
    }

    @Test
    void notesOnCallsAreKeptAndShownInTheSequence() {
        FlowDiagram d = diagram();
        FlowDiagram.Link call = d.links.get(0);
        d.setCallNote(call, "  REST: POST /bookings  ");
        assertEquals("REST: POST /bookings", d.callNote(call));
        FlowDiagram copy = d.copy();
        assertEquals("REST: POST /bookings", copy.callNote(copy.links.get(0)), "copies (History steps) keep it");
        assertFalse(d.sameAs(diagram()), "a note is a change");

        FlowDiagram again = diagram();
        again.applyLooks(d.looks());
        assertEquals("REST: POST /bookings", again.callNote(again.links.get(0)), "saved with the flow");

        d.resetLooks();
        assertEquals("REST: POST /bookings", d.callNote(call), "Reset Theme keeps notes");
        boolean found = false;
        for (FlowDiagram.Step s : d.sequence()) {
            if (s instanceof FlowDiagram.Call c && c.note().equals("REST: POST /bookings")) found = true;
        }
        assertTrue(found, "the sequence diagram's call carries the note");

        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        painter.autoLayout(d);
        java.awt.geom.Rectangle2D r = painter.callNoteBounds(d, call);
        assertNotNull(r);
        assertSame(call, painter.hit(d, new java.awt.geom.Point2D.Double(r.getCenterX(), r.getCenterY())).link(),
                "clicking the note selects the call");
        d.setCallNote(call, "");
        assertNull(painter.callNoteBounds(d, call));
    }

    @Test
    void notesOfCallsFromTheSameMethodDontCoverEachOther() {
        FlowDiagram d = diagram();
        FlowDiagram.Method main = method(box(d, "Main"), "main()");
        assertTrue(d.connect(main.id, method(box(d, "BookingService"), "cancel()").id));
        for (FlowDiagram.Link l : d.links) d.setCallNote(l, "call to " + d.method(l.to()).name);
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        painter.autoLayout(d);
        List<java.awt.geom.Rectangle2D> notes = new java.util.ArrayList<>(painter.callNoteLayout(d).values());
        assertEquals(2, notes.size());
        assertFalse(notes.get(0).intersects(notes.get(1)), "two notes on one spot: " + notes);
        for (java.awt.geom.Rectangle2D n : notes) {
            for (FlowDiagram.Box b : d.boxes) assertFalse(n.intersects(painter.bounds(b)), "a note on " + b.name);
        }
    }

    @Test
    void noteStylesAreKeptAndResetThemeResetsThem() {
        FlowDiagram d = diagram();
        FlowDiagram.Link call = d.links.get(0);
        d.setCallNote(call, "REST\nPOST /bookings");
        FlowDiagram.NoteStyle style = new FlowDiagram.NoteStyle(FlowDiagram.NoteShape.PILL,
                new java.awt.Color(0xFFF1D6), null, new java.awt.Color(0xC27C0E));
        d.setNoteStyle(call, style);
        FlowDiagram again = diagram();
        again.applyLooks(d.looks());
        assertEquals(style, again.noteStyle(again.links.get(0)), "saved with the flow");
        assertEquals("REST\nPOST /bookings", again.callNote(again.links.get(0)), "several lines");
        d.resetLooks();
        assertEquals(FlowDiagram.NoteStyle.DEFAULT, d.noteStyle(call));
        assertEquals("REST\nPOST /bookings", d.callNote(call), "Reset Theme keeps the note");
    }

    @Test
    void aCustomClassCanHaveAProject() {
        FlowDiagram d = new FlowDiagram();
        FlowDiagram.Box b = d.addUnknownClass("PaymentGateway", "PaymentGateway.java", 0, 0);
        d.addUnknownMethod(b, "charge");
        d.setCustomFile(b, "PaymentGateway.java", "Payments");
        assertEquals("PaymentGateway.java  ·  Payments", b.subtitle());
        FlowDiagram.Result r = d.apply(List.of());
        assertEquals("Payments", r.entries().get(0).projectName);
        assertEquals("Payments", box(FlowDiagram.of(r.entries(), Set.of()), "PaymentGateway").projectName, "read back from the lines");
    }

    @Test
    void aTagSetInTheDiagramGoesToAllTheMethodsLines() {
        FlowDiagram d = diagram();
        FlowDiagram.Method main = method(box(d, "Main"), "main()");
        assertEquals("", main.tag);
        d.setMethodTag(main, "bug");
        FlowDiagram.Result r = d.apply(flow());
        int tagged = 0;
        for (FlowEntry e : r.entries()) {
            if (e.className.equals("Main") && e.methodName.equals("main()")) {
                assertEquals("bug", e.tag);
                tagged++;
            }
        }
        assertTrue(tagged > 0);
        assertTrue(r.removed().isEmpty(), "tagged lines are the same lines, not removed ones");
        assertEquals("bug", method(box(FlowDiagram.of(r.entries(), Set.of()), "Main"), "main()").tag, "read back from the lines");
    }

    @Test
    void connectingACallByHandRemovesNoLine() {
        FlowDiagram d = diagram();
        assertTrue(d.connect(method(box(d, "BookingService"), "bookTrip()").id, method(box(d, "BookingService"), "cancel()").id));
        FlowDiagram.Result r = d.apply(flow());
        assertTrue(r.removed().isEmpty(), "the calling line gets the connection; it isn't removed: " + r.removed());
    }

    @Test
    void aCustomClassKeepsTheFileItWasNamedAfter() {
        FlowDiagram d = new FlowDiagram();
        FlowDiagram.Box b = d.addUnknownClass("PaymentGateway", "PaymentGateway.java", 0, 0);
        assertEquals("PaymentGateway.java", b.subtitle());
        d.addUnknownMethod(b, "charge");
        FlowDiagram.Result r = d.apply(List.of());
        assertEquals("PaymentGateway.java", r.entries().get(0).fileName);
        FlowDiagram back = FlowDiagram.of(r.entries(), Set.of());
        assertEquals("PaymentGateway.java", box(back, "PaymentGateway").fileName);
        d.setCustomFile(b, "payment.js");
        assertEquals("payment.js", b.subtitle());
        FlowDiagram.Box plain = d.addUnknownClass("Audit", 0, 0);
        assertEquals("", plain.subtitle(), "named after itself: no file line");
        d.renameClass(plain, "AuditLog");
        assertEquals("AuditLog", plain.fileName, "its file follows the new name");
    }

    @Test
    void imagesAreDrawn() {
        FlowDiagram d = diagram();
        d.addUnknownClass("PaymentGateway", 0, 0);
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        painter.autoLayout(d);
        BufferedImage classes = painter.classImage(d, "Checkout", FlowDiagramPainter.LIGHT);
        BufferedImage sequence = painter.sequenceImage(d, "Checkout", FlowDiagramPainter.LIGHT);
        assertTrue(classes.getWidth() > 400 && classes.getHeight() > 100);
        assertTrue(sequence.getWidth() > 300 && sequence.getHeight() > 150);
        assertTrue(hasInk(classes) && hasInk(sequence));
        assertEquals(0xFFFFFF, classes.getRGB(1, 1) & 0xFFFFFF, "light images have a white background");

        BufferedImage darkClasses = painter.classImage(d, "Checkout", FlowDiagramPainter.DARK);
        BufferedImage darkSequence = painter.sequenceImage(d, "Checkout", FlowDiagramPainter.DARK);
        assertEquals(FlowDiagramPainter.DARK.background().getRGB() & 0xFFFFFF, darkClasses.getRGB(1, 1) & 0xFFFFFF);
        assertEquals(FlowDiagramPainter.DARK.background().getRGB() & 0xFFFFFF, darkSequence.getRGB(1, 1) & 0xFFFFFF);
        assertEquals(classes.getWidth(), darkClasses.getWidth(), "same layout, only the colors differ");

        BufferedImage empty = painter.sequenceImage(new FlowDiagram(), "Empty", FlowDiagramPainter.LIGHT);
        assertTrue(empty.getWidth() > 0, "an empty diagram still gives an image");
    }

    @Test
    void hitTestingFindsRowsHeadersAndCalls() {
        FlowDiagram d = diagram();
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        painter.autoLayout(d);
        FlowDiagram.Box main = box(d, "Main");
        java.awt.geom.Rectangle2D row = painter.rowBounds(main, 0);
        assertEquals("main()", painter.hit(d, new java.awt.geom.Point2D.Double(row.getCenterX(), row.getCenterY())).method().name);
        FlowDiagramPainter.Hit header = painter.hit(d, new java.awt.geom.Point2D.Double(main.x + 5, main.y + 5));
        assertSame(main, header.box());
        assertNull(header.method());
        java.awt.geom.CubicCurve2D curve = (java.awt.geom.CubicCurve2D) painter.linkPath(d, d.links.get(0));
        double mx = (curve.getX1() + curve.getX2()) / 2, my = (curve.getY1() + curve.getY2()) / 2;
        assertNotNull(painter.hit(d, new java.awt.geom.Point2D.Double(mx, my)).link(), "the middle of a call's curve");
    }

    @Test
    void applyingIsOneUndoableStep() {
        FlowStore store = new FlowStore();
        store.addAll(flow(), false);
        FlowDiagram d = FlowDiagram.of(store.getEntries(), Set.of());
        d.removeMethod(method(box(d, "BookingService"), "cancel()"));
        d.renameClass(box(d, "Main"), "App");
        FlowDiagram.Result r = d.apply(store.getEntries());
        store.applyDiagram(r.entries(), r.removed(), "Flow Diagram (2 changes)", d.looks());

        assertEquals(4, store.getEntries().size());
        assertEquals("App", store.getEntries().get(1).className);
        assertEquals("Flow Diagram (2 changes)", store.undoLabel());
        assertEquals("Flow Diagram (2 changes) (1 line)", store.getRecycleBin().get(0).label);

        store.undo();
        assertEquals(flow(), store.getEntries());
        assertTrue(store.getRecycleBin().isEmpty());
        store.redo();
        assertEquals(r.entries(), store.getEntries());
        assertEquals(1, store.getRecycleBin().size());
    }

    @Test
    void closingTheDiagramDropsEveryEmptyFlowButKeepsOne() {
        FlowStore store = new FlowStore();
        store.addAll(flow(), false);
        String full = store.getActiveFlowName();
        store.newFlow("Flow 2 – BookingService", List.of());
        store.newFlow("Flow 2 + Flow 2 – BookingService", List.of());
        assertEquals(2, store.dropEmptyFlows());
        assertEquals(List.of(full), store.getFlowNames());
        assertEquals(flow(), store.getEntries(), "the flow with lines stays, with its lines");

        FlowStore empty = new FlowStore();
        empty.newFlow("Another", List.of());
        empty.dropEmptyFlows();
        assertEquals(1, empty.getFlowNames().size(), "one flow is always left");
    }

    @Test
    void diagramLinesAreNeverTheSameAsCodeLines() {
        FlowStore store = new FlowStore();
        FlowEntry a = line("A.java", "A", "a()", 0, FlowDiagram.NEW_METHOD_TEXT);
        FlowEntry b = line("A.java", "A", "b()", 0, FlowDiagram.NEW_METHOD_TEXT);
        store.applyDiagram(List.of(a, b), List.of(), "Flow Diagram", List.of());
        assertFalse(store.contains(a.fileUrl, 0));
        assertEquals("A.a():+", FlowFormat.flatLabel(a));
        assertTrue(store.add(line("A.java", "A", "a()", 1, "x();")), "a real line can still be added");
    }

    // ---------- classes and methods picked from the code ----------

    @Test
    void searchTextNeedsThreeLettersAndTakesWildcards() {
        assertNull(FlowCodeSearch.pattern("ab"), "fewer than three letters: no search yet");
        assertNull(FlowCodeSearch.pattern("*a*"), "wildcards don't count as letters");
        java.util.regex.Pattern glob = FlowCodeSearch.pattern("*Cruise*.java");
        assertTrue(glob.matcher("OdysseyCruiseProcessor.java").find());
        assertTrue(glob.matcher("cruise.java").find(), "ignores case");
        assertFalse(glob.matcher("OdysseyCruiseProcessor.kt").find(), "the whole name must match");
        assertTrue(FlowCodeSearch.pattern("Book?ngService").matcher("BookingService").find());
        java.util.regex.Pattern plain = FlowCodeSearch.pattern("cruise");
        assertTrue(plain.matcher("OdysseyCruiseProcessor.java").find(), "plain text: the name contains it");
        assertTrue(FlowCodeSearch.pattern("a.b").matcher("a.b").find());
        assertFalse(FlowCodeSearch.pattern("a.b").matcher("axb").find(), "'.' is not a regex wildcard");
    }

    @Test
    void searchTextCanBeARegularExpression() {
        java.util.regex.Pattern re = FlowCodeSearch.pattern("/Book.*Service/");
        assertTrue(re.matcher("OnlineBookingService").find(), "found anywhere in the name");
        assertTrue(re.matcher("bookingservice.java").find(), "ignores case");
        assertFalse(re.matcher("BookingDao").find());
        assertNull(FlowCodeSearch.pattern("/a.b/"), "a regex needs three letters too");
        assertNull(FlowCodeSearch.pattern("/Book(/"), "an invalid regex searches nothing");
        assertNotNull(FlowCodeSearch.patternError("/Book(/"));
        assertNull(FlowCodeSearch.patternError("/Book.*/"));
        assertNull(FlowCodeSearch.patternError("Book("), "without slashes it is plain text");
    }

    @Test
    void methodsAreFilteredLikeClasses() {
        FlowCodeSearch.MethodHit get = new FlowCodeSearch.MethodHit("getName()", "getName(String)", 1, 3, 1);
        FlowCodeSearch.MethodHit cancel = new FlowCodeSearch.MethodHit("cancelBooking()", "cancelBooking(int)", 5, 9, 5);
        assertNull(FlowCodeSearch.filterPattern("  "), "empty: every method");
        assertTrue(FlowCodeSearch.matchesMethod(FlowCodeSearch.filterPattern("na"), get), "short text works in a list");
        assertTrue(FlowCodeSearch.matchesMethod(FlowCodeSearch.filterPattern("get*"), get));
        assertFalse(FlowCodeSearch.matchesMethod(FlowCodeSearch.filterPattern("get*"), cancel));
        assertTrue(FlowCodeSearch.matchesMethod(FlowCodeSearch.filterPattern("*Booking"), cancel));
        assertTrue(FlowCodeSearch.matchesMethod(FlowCodeSearch.filterPattern("/^(get|cancel)/"), cancel));
        assertTrue(FlowCodeSearch.matchesMethod(FlowCodeSearch.filterPattern("/\\(int\\)/"), cancel), "the signature counts");
        assertFalse(FlowCodeSearch.matchesMethod(FlowCodeSearch.filterPattern("/^set/"), get));
    }

    @Test
    void pickedLinesJoinTheFlowAfterTheirMethod() {
        FlowDiagram d = diagram();
        FlowDiagram.Box service = box(d, "BookingService");
        FlowEntry l24 = line("BookingService.java", "BookingService", "bookTrip()", 24, "validate(name);");
        FlowEntry l25 = line("BookingService.java", "BookingService", "bookTrip()", 25, "save(booking);");
        FlowDiagram.Method book = d.addKnownMethod(service, "bookTrip()", List.of(l24, l25));
        assertSame(method(service, "bookTrip()"), book, "lines of a method the flow has join that method");
        assertEquals(3, book.lineCount());
        d.addKnownMethod(service, "bookTrip()", List.of(line("BookingService.java", "BookingService", "bookTrip()", 24, "dup")));
        assertEquals(3, book.lineCount(), "a line picked twice is added once");

        FlowEntry r70 = line("BookingService.java", "BookingService", "refund()", 70, "public void refund(String id) {");
        FlowDiagram.Method refund = d.addKnownMethod(service, "refund()", List.of(r70));
        assertFalse(refund.unknown, "picked from the code");

        List<FlowEntry> out = d.apply(flow()).entries();
        assertEquals(List.of("[fields]:4", "main():8", "main():13", "bookTrip():21", "bookTrip():24", "bookTrip():25",
                "cancel():55", "refund():70"), out.stream().map(e -> e.methodName + ":" + e.line).toList());
        assertNotSame(l24, out.get(4), "the picked line is copied, so the diagram's history keeps its own");
        assertFalse(out.stream().anyMatch(FlowDiagram::isDiagramOnly), "all real code lines");
    }

    @Test
    void aPickedClassKeepsItsDeclarationUntilItHasMethods() {
        FlowDiagram d = diagram();
        FlowEntry decl = line("Odyssey/CruiseProcessor.java", "CruiseProcessor", "[class]", 12, "public class CruiseProcessor {");
        FlowDiagram.Box cruise = d.addKnownClass(decl.fileUrl, decl.fileName, "Odyssey", "CruiseProcessor", decl, 400, 20);
        assertTrue(cruise.picked);
        assertFalse(cruise.isUnknown());
        assertSame(cruise, d.addKnownClass(decl.fileUrl, decl.fileName, "Odyssey", "CruiseProcessor", decl, 0, 0),
                "a class is added once");

        List<FlowEntry> onlyClass = d.apply(flow()).entries();
        assertEquals("[class]", onlyClass.get(onlyClass.size() - 1).methodName, "kept in the flow by its declaration");
        assertEquals(12, onlyClass.get(onlyClass.size() - 1).line);

        d.addKnownMethod(cruise, "sail()", List.of(line("Odyssey/CruiseProcessor.java", "CruiseProcessor", "sail()", 20, "route.start();")));
        List<FlowEntry> withMethod = d.apply(flow()).entries();
        assertEquals("sail()", withMethod.get(withMethod.size() - 1).methodName);
        assertEquals(flow().size() + 1, withMethod.size(), "the declaration isn't needed once it has a method");

        FlowDiagram again = FlowDiagram.of(withMethod, Set.of());
        assertEquals(List.of("sail()"), box(again, "CruiseProcessor").methods.stream().map(m -> m.name).toList(),
                "after Apply it is an ordinary class of the flow");
    }

    @Test
    void anotherClassCanTakeABoxsPlace() {
        FlowDiagram d = diagram();
        FlowDiagram.Box service = box(d, "BookingService");
        service.x = 300;
        FlowDiagram.Method book = method(service, "bookTrip()");
        FlowEntry decl = line("TripService.java", "TripService", "[class]", 5, "public class TripService {");
        FlowDiagram.Box trip = d.replaceWithKnownClass(service, decl.fileUrl, decl.fileName, "TravelBooking", "TripService", decl);

        assertEquals(1, d.boxes.indexOf(trip), "same place in the list");
        assertEquals(300, trip.x, "and on the canvas");
        assertEquals(List.of("bookTrip()", "cancel()"), trip.methods.stream().map(m -> m.name).toList());
        assertTrue(trip.methods.get(0).unknown, "the methods are custom methods of the new class");
        assertEquals(book.id, trip.methods.get(0).id, "same method, so its calls stay");
        assertTrue(d.links.get(0).manual(), "a call found in BookingService's code is kept as a connection by hand");
        assertNull(d.replaceWithKnownClass(box(d, "Main"), decl.fileUrl, decl.fileName, "TravelBooking", "TripService", decl),
                "a class is in one box only");

        List<FlowEntry> out = d.apply(flow()).entries();
        assertFalse(out.contains(book21), "BookingService's code lines leave the flow");
        FlowEntry custom = out.stream().filter(e -> e.methodName.equals("bookTrip()")).findFirst().orElseThrow();
        assertEquals(decl.fileUrl, custom.fileUrl);
        assertTrue(FlowDiagram.isDiagramOnly(custom), "a custom method of TripService");
        assertEquals(List.of(FlowSync.methodKey(custom)), out.get(1).calls, "main() still calls it");
    }

    @Test
    void renamingToAClassTheProjectDoesntHaveMakesItCustom() {
        FlowDiagram d = diagram();
        FlowDiagram.Box payments = d.replaceWithCustomClass(box(d, "BookingService"), "PaymentService");
        assertTrue(payments.isUnknown());
        assertEquals("PaymentService", payments.name);
        List<FlowEntry> out = d.apply(flow()).entries();
        assertTrue(out.stream().filter(e -> e.className.equals("PaymentService")).allMatch(FlowDiagram::isDiagramOnly));
        assertEquals(FlowDiagram.DIAGRAM_URL + "PaymentService",
                out.stream().filter(e -> e.className.equals("PaymentService")).findFirst().orElseThrow().fileUrl);
    }

    @Test
    void doubleClickTellsTheClassNameFromItsMethods() {
        FlowDiagram d = diagram();
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        painter.autoLayout(d);
        FlowDiagram.Box main = box(d, "Main");
        assertTrue(painter.inHeader(main, new java.awt.geom.Point2D.Double(main.x + 10, main.y + 10)));
        java.awt.geom.Rectangle2D row = painter.rowBounds(main, 0);
        assertFalse(painter.inHeader(main, new java.awt.geom.Point2D.Double(row.getCenterX(), row.getCenterY())));
    }

    @Test
    void unknownClassNamesCantBeFileNames() {
        assertNotNull(diagram().checkClassName(null, "BookingService.java"));
    }

    @Test
    void changesThatChangeNothingArentRecorded() {
        FlowDiagram.History h = new FlowDiagram.History();
        FlowDiagram d = diagram();
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        h.open("Open", d, flow());
        box(d, "Main").x += 300;
        assertTrue(h.change("Move Main", d));
        painter.autoLayout(d);
        assertTrue(h.change("Auto layout", d), "the layout moved a box");
        painter.autoLayout(d);
        assertFalse(h.change("Auto layout", d), "the same layout again is no change");
        assertFalse(h.change("Auto layout", d.copy()));
        assertEquals(3, h.steps.size());
        assertEquals(2, h.position);

        h.position = 1; // undone: Redo can still bring the layout back after a no-change
        FlowDiagram moved = h.current().diagram().copy();
        assertFalse(h.change("Move Main", moved));
        assertEquals(3, h.steps.size(), "a no-change doesn't drop the undone steps");

        d.renameClass(box(d, "Main"), "App");
        assertTrue(h.change("Rename class Main to App", d));
        assertEquals(3, h.steps.size(), "a real change replaces the undone step");
    }

    // ---------- look: colors, formats, notes and lines ----------

    @Test
    void formatsAreWrittenAndReadBack() {
        FlowDiagram.Format f = new FlowDiagram.Format(true, true, new java.awt.Color(0xC62828));
        assertEquals("bi#C62828", f.encode());
        assertEquals(f, FlowDiagram.Format.decode("bi#C62828"));
        assertEquals(new FlowDiagram.Format(false, true, null), FlowDiagram.Format.decode("i"));
        assertNull(FlowDiagram.Format.decode(""), "no format: the usual look");
        assertNull(FlowDiagram.parseColor("red"));

        FlowDiagram.Line line = new FlowDiagram.Line(FlowDiagram.LinePattern.DOTTED, FlowDiagram.LineWidth.THICK,
                FlowDiagram.LineShape.ELBOW, FlowDiagram.ArrowHead.OPEN, null);
        assertEquals(line, FlowDiagram.Line.decode(line.encode(), null));
        assertEquals("", FlowDiagram.Line.DEFAULT.encode());
        assertEquals(FlowDiagram.Line.DEFAULT, FlowDiagram.Line.decode("NOPE,THIN", null), "unknown values: the usual line");
    }

    @Test
    void looksAreSavedAndGivenBackToANewDiagram() {
        FlowDiagram d = diagram();
        FlowDiagram.Box main = box(d, "Main");
        main.x = 120;
        main.y = 40;
        main.fill = new java.awt.Color(0xE3F2FD);
        main.border = new java.awt.Color(0x1565C0);
        main.title = new FlowDiagram.Format(false, true, new java.awt.Color(0x1565C0));
        main.note = "Entry point\nreads the arguments";
        FlowDiagram.Method book = method(box(d, "BookingService"), "bookTrip()");
        book.format = new FlowDiagram.Format(true, false, null);
        book.note = "validates the name";
        book.noteFormat = new FlowDiagram.Format(false, true, new java.awt.Color(0x2E7D32));
        FlowDiagram.Line dotted = new FlowDiagram.Line(FlowDiagram.LinePattern.DOTTED, FlowDiagram.LineWidth.HEAVY,
                FlowDiagram.LineShape.STRAIGHT, FlowDiagram.ArrowHead.NONE, new java.awt.Color(0xAD1457));
        d.setLine(d.links.get(0), dotted);

        List<FlowDiagramLook> looks = d.looks();
        assertEquals(4, looks.size(), "two boxes, one method with a format, one call with its own line");

        FlowDiagram again = diagram();
        again.applyLooks(looks);
        FlowDiagram.Box main2 = box(again, "Main");
        assertEquals(120, main2.x);
        assertEquals(40, main2.y);
        assertEquals(main.fill, main2.fill);
        assertEquals(main.border, main2.border);
        assertEquals(main.title, main2.title);
        assertEquals(main.note, main2.note);
        FlowDiagram.Method book2 = method(box(again, "BookingService"), "bookTrip()");
        assertEquals(book.format, book2.format);
        assertEquals("validates the name", book2.note);
        assertEquals(book.noteFormat, book2.noteFormat);
        assertEquals(dotted, again.line(again.links.get(0)));
        assertTrue(FlowDiagram.sameLooks(looks, again.looks()));
        assertEquals(d.lookSignature().length(), again.lookSignature().length());

        FlowDiagram copy = d.copy();
        assertTrue(copy.sameAs(d), "a copy keeps the look");
        box(copy, "Main").note = "changed";
        assertFalse(copy.sameAs(d), "a new note is a change for the History");
        assertEquals("Entry point\nreads the arguments", main.note, "copies are independent");
    }

    @Test
    void notesMakeBoxesTallerAndWider() {
        FlowDiagram d = diagram();
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        FlowDiagram.Box main = box(d, "Main");
        FlowDiagram.Method m = method(main, "main()");
        double header = painter.headerHeight(main), height = painter.height(main);
        main.note = "first line\nsecond line";
        assertEquals(header + 2 * FlowDiagramPainter.NOTE_LINE + 4, painter.headerHeight(main));
        m.note = "a note that is long enough to wrap onto more than one line, because it keeps going and going on";
        assertTrue(painter.rowHeight(m) > FlowDiagramPainter.ROW + FlowDiagramPainter.NOTE_LINE, "long notes wrap");
        assertTrue(painter.height(main) > height + 3 * FlowDiagramPainter.NOTE_LINE);
        assertTrue(painter.width(main) <= FlowDiagramPainter.NOTE_WIDTH + 40, "wrapped, not one wide line");

        // The note belongs to its row: clicking it selects the method.
        painter.autoLayout(d);
        java.awt.geom.Rectangle2D row = painter.rowBounds(main, 0);
        assertSame(m, painter.hit(d, new java.awt.geom.Point2D.Double(row.getCenterX(), row.getMaxY() - 3)).method());
    }

    @Test
    void lineShapesAndFormatsAreDrawn() {
        FlowDiagram d = diagram();
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        painter.autoLayout(d);
        FlowDiagram.Link link = d.links.get(0);
        for (FlowDiagram.LineShape shape : FlowDiagram.LineShape.values()) {
            for (FlowDiagram.LinePattern pattern : FlowDiagram.LinePattern.values()) {
                d.setLine(link, new FlowDiagram.Line(pattern, FlowDiagram.LineWidth.THICK, shape, FlowDiagram.ArrowHead.OPEN, null));
                java.awt.geom.Rectangle2D r = painter.linkPath(d, link).getBounds2D();
                assertTrue(r.getWidth() > 0, shape + " " + pattern);
            }
            java.awt.geom.Point2D mid = new java.awt.geom.Point2D.Double(
                    painter.linkPath(d, link).getBounds2D().getCenterX(), painter.linkPath(d, link).getBounds2D().getCenterY());
            if (shape == FlowDiagram.LineShape.STRAIGHT) assertSame(link, painter.hit(d, mid).link(), "straight lines can be clicked");
        }
        box(d, "Main").fill = new java.awt.Color(0x2B2D30);
        box(d, "Main").title = new FlowDiagram.Format(false, true, java.awt.Color.WHITE);
        assertTrue(hasInk(painter.classImage(d, "Styled", FlowDiagramPainter.LIGHT)));
        assertTrue(hasInk(painter.sequenceImage(d, "Styled", FlowDiagramPainter.LIGHT)));
    }

    @Test
    void textOnAColoredBoxStaysReadable() {
        FlowDiagramPainter.Palette light = FlowDiagramPainter.LIGHT;
        assertSame(light, FlowDiagramPainter.boxPalette(light, null), "no background of its own: the diagram's colors");
        FlowDiagramPainter.Palette onBlue = FlowDiagramPainter.boxPalette(light, new java.awt.Color(0x2F2BF0));
        assertFalse(FlowDiagram.isDark(onBlue.title()), "light text on a dark blue box");
        assertFalse(FlowDiagram.isDark(onBlue.muted()), "the file name and line counts too");
        FlowDiagramPainter.Palette onCream = FlowDiagramPainter.boxPalette(FlowDiagramPainter.DARK, new java.awt.Color(0xFFF8E1));
        assertTrue(FlowDiagram.isDark(onCream.title()), "dark text on a light box, even in a dark diagram");
        assertTrue(FlowDiagram.isDark(onCream.text()));
    }

    @Test
    void underlineAndStrikeThroughAreKept() {
        FlowDiagram.Format f = new FlowDiagram.Format(false, true, true, true, new java.awt.Color(0x1565C0));
        assertEquals("ius#1565C0", f.encode());
        assertEquals(f, FlowDiagram.Format.decode(f.encode()));
        assertEquals(new FlowDiagram.Format(true, false, null), FlowDiagram.Format.decode("b"), "older saved formats still read");
        java.awt.Font font = FlowDiagramPainter.styled(new Font(Font.SANS_SERIF, Font.PLAIN, 12), f, Font.PLAIN);
        assertTrue(font.isItalic());
        assertEquals(java.awt.font.TextAttribute.UNDERLINE_ON, font.getAttributes().get(java.awt.font.TextAttribute.UNDERLINE));
        assertEquals(java.awt.font.TextAttribute.STRIKETHROUGH_ON, font.getAttributes().get(java.awt.font.TextAttribute.STRIKETHROUGH));

        FlowDiagram d = diagram();
        method(box(d, "Main"), "main()").format = f;
        assertTrue(hasInk(new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12)).classImage(d, "x", FlowDiagramPainter.LIGHT)));
    }

    @Test
    void everyLineDesignAndArrowIsDrawn() {
        for (FlowDiagram.LinePattern pattern : FlowDiagram.LinePattern.values()) {
            for (FlowDiagram.ArrowHead arrow : FlowDiagram.ArrowHead.values()) {
                BufferedImage img = new BufferedImage(120, 40, BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = img.createGraphics();
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, 120, 40);
                g.setColor(java.awt.Color.BLACK);
                FlowDiagramPainter.drawLine(g, new java.awt.geom.Line2D.Double(10, 20, 100, 20), pattern, true, 2.6f, java.awt.Color.WHITE);
                FlowDiagramPainter.arrowHead(g, new java.awt.geom.Point2D.Double(10, 20), new java.awt.geom.Point2D.Double(100, 20), arrow, 2.6f);
                g.dispose();
                int ink = 0; // every pixel: a thin dashed line is too small for hasInk's sampling
                for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) & 0xFFFFFF) != 0xFFFFFF) ink++;
                assertTrue(ink > 60, pattern + " " + arrow + ": " + ink);
            }
        }
        FlowDiagram.Line l = new FlowDiagram.Line(FlowDiagram.LinePattern.DOUBLE_DASHED, FlowDiagram.LineWidth.THIN,
                FlowDiagram.LineShape.ELBOW, FlowDiagram.ArrowHead.DIAMOND, null);
        assertEquals(l, FlowDiagram.Line.decode(l.encode(), null));
    }

    @Test
    void theClassAndMethodSectionsFollowEachOtherUntilBothAreSet() {
        FlowDiagram d = diagram();
        FlowDiagram.Box b = box(d, "Main");
        java.awt.Color navy = new java.awt.Color(0x1F2A4D), cream = new java.awt.Color(0xFFF8E1);
        assertNull(FlowDiagramPainter.bodyColor(b), "nothing set: the diagram's own colors");
        assertNull(FlowDiagramPainter.headerColor(b, FlowDiagramPainter.LIGHT));

        b.headerFill = navy;
        java.awt.Color body = FlowDiagramPainter.bodyColor(b);
        assertNotNull(body);
        assertFalse(body.equals(navy), "the method section is a shade of the class section");
        assertTrue(FlowDiagram.isDark(body), "a little darker for a dark class section");

        b.headerFill = null;
        b.fill = cream;
        java.awt.Color header = FlowDiagramPainter.headerColor(b, FlowDiagramPainter.LIGHT);
        assertNotNull(header);
        assertFalse(header.equals(cream), "the class section is a shade of the method section");

        b.headerFill = navy;
        assertEquals(cream, FlowDiagramPainter.bodyColor(b), "both set: each keeps its own");
        assertEquals(navy, FlowDiagramPainter.headerColor(b, FlowDiagramPainter.LIGHT));
    }

    @Test
    void fileAndMethodNameFormatsAreSavedWithTheBox() {
        FlowDiagram d = diagram();
        FlowDiagram.Box b = box(d, "BookingService");
        b.headerFill = new java.awt.Color(0x1F2A4D);
        b.fileFormat = new FlowDiagram.Format(false, true, new java.awt.Color(0x90A4AE));
        b.methodsFormat = new FlowDiagram.Format(true, false, false, true, null);
        FlowDiagram again = diagram();
        again.applyLooks(d.looks());
        FlowDiagram.Box b2 = box(again, "BookingService");
        assertEquals(b.headerFill, b2.headerFill);
        assertEquals(b.fileFormat, b2.fileFormat);
        assertEquals(b.methodsFormat, b2.methodsFormat);
        assertFalse(d.copy().lookSignature().isEmpty());
        FlowDiagram changed = d.copy();
        box(changed, "BookingService").methodsFormat = null;
        assertFalse(changed.sameAs(d), "a box's method format is a change for the History");
        assertTrue(hasInk(new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12)).classImage(d, "x", FlowDiagramPainter.LIGHT)));
    }

    @Test
    void resetThemeGivesEverythingTheDefaultLookButKeepsNotesAndPlaces() {
        FlowDiagram d = diagram();
        FlowDiagram plain = d.copy();
        FlowDiagram.Box main = box(d, "Main");
        main.x = 300;
        main.fill = java.awt.Color.WHITE;
        main.headerFill = java.awt.Color.BLUE;
        main.border = java.awt.Color.RED;
        main.title = new FlowDiagram.Format(false, true, null);
        main.fileFormat = main.title;
        main.methodsFormat = main.title;
        main.note = "keep me";
        main.noteFormat = main.title;
        FlowDiagram.Method m = method(main, "main()");
        m.format = main.title;
        m.note = "me too";
        m.noteFormat = main.title;
        d.setLine(d.links.get(0), new FlowDiagram.Line(FlowDiagram.LinePattern.DOTTED, FlowDiagram.LineWidth.HEAVY,
                FlowDiagram.LineShape.ELBOW, FlowDiagram.ArrowHead.NONE, java.awt.Color.RED));

        d.resetLooks();
        assertNull(main.fill);
        assertNull(main.headerFill);
        assertNull(main.border);
        assertNull(main.title);
        assertNull(main.fileFormat);
        assertNull(main.methodsFormat);
        assertNull(main.noteFormat);
        assertNull(m.format);
        assertNull(m.noteFormat);
        assertEquals(FlowDiagram.Line.DEFAULT, d.line(d.links.get(0)));
        assertEquals("keep me", main.note, "notes stay");
        assertEquals("me too", m.note);
        assertEquals(300, main.x, "positions stay");
        assertEquals(plain.signature(), d.signature(), "classes, methods and calls stay");
    }

    @Test
    void applyingKeepsTheLookAndUndoTakesItBack() {
        FlowStore store = new FlowStore();
        store.addAll(flow(), false);
        FlowDiagram d = FlowDiagram.of(store.getEntries(), Set.of());
        box(d, "Main").note = "start here";
        FlowDiagram.Result r = d.apply(store.getEntries());
        store.applyDiagram(r.entries(), r.removed(), "Flow Diagram: Note on Main", d.looks());
        assertEquals(flow(), store.getEntries(), "a note changes no lines");
        assertTrue(store.getDiagramLooks().stream().anyMatch(l -> l.note.equals("start here")));
        store.undo();
        assertTrue(store.getDiagramLooks().isEmpty());
        store.redo();
        assertTrue(store.getDiagramLooks().stream().anyMatch(l -> l.note.equals("start here")));
    }

    @Test
    void historyContinuesAfterApply() {
        FlowDiagram.History h = new FlowDiagram.History();
        FlowDiagram d = diagram();
        h.open("Open", d, flow());
        d.renameClass(box(d, "Main"), "App");
        h.push("Rename class Main to App", d, flow(), false);
        List<FlowEntry> after = d.apply(flow()).entries();
        h.markApplied(after);
        assertTrue(h.current().applied());

        assertTrue(h.continues(after), "reopened on the applied lines: same history");
        assertFalse(h.continues(List.of(main8)), "the flow changed outside the diagram");

        FlowDiagram reopened = FlowDiagram.of(after, Set.of());
        h.open("Reopen", reopened, after);
        assertEquals(3, h.steps.size());
        h.open("Reopen", reopened, after);
        assertEquals(3, h.steps.size(), "opening again without changes doesn't add a step");

        // Going back to the step before the rename and applying it restores the old names.
        h.position = 0;
        FlowDiagram.Result back = h.current().diagram().apply(h.current().base(), after);
        assertEquals(flow(), back.entries());
        assertEquals(3, back.removed().size(), "the renamed copies leave the flow (to the Recycle Bin)");

        FlowDiagram.History copy = h.copy();
        copy.push("x", d, after, false);
        assertEquals(3, h.steps.size(), "the window edits a copy; Cancel keeps the stored history");
    }

    @Test
    void markdownSaysWhatIsNotInTheCodeYet() {
        FlowDiagram d = diagram();
        FlowDiagram.Box pay = d.addUnknownClass("PaymentGateway", 0, 0);
        d.addUnknownMethod(pay, "charge");
        d.addUnknownMethod(box(d, "BookingService"), "validate");
        List<FlowEntry> out = d.apply(flow()).entries();
        String md = FlowMarkdown.toMarkdown(out, false, Set.of(), true);
        assertTrue(md.contains("`PaymentGateway` · _custom class, not in the code yet (added in the Flow Diagram)_"), md);
        assertTrue(md.contains("`validate()`** · _custom method, not in the code yet (added in the Flow Diagram)_"), md);
        assertTrue(md.contains("| + | _No code yet (added in the Flow Diagram)_ |"), md);
        assertFalse(md.contains("`bookTrip()`** · _custom method"), "methods with code have no such note");
    }

    private static boolean hasInk(BufferedImage img) {
        int dark = 0;
        for (int y = 0; y < img.getHeight(); y += 3) {
            for (int x = 0; x < img.getWidth(); x += 3) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                if (((rgb >> 16) & 0xFF) < 128 && ((rgb >> 8) & 0xFF) < 128 && (rgb & 0xFF) < 128) dark++;
            }
        }
        return dark > 20;
    }

    @Test
    void twoCallsFromOneMethodLeaveFromTheirOwnSpots() {
        FlowDiagram d = FlowDiagram.of(flow(), Set.of(FlowSync.link(mainKey, bookKey), FlowSync.link(mainKey, cancelKey)));
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        painter.autoLayout(d);
        assertEquals(2, d.links.size());
        String bookId = method(box(d, "BookingService"), "bookTrip()").id;
        FlowDiagram.Link toBook = d.links.stream().filter(l -> l.to().equals(bookId)).findFirst().orElseThrow();
        FlowDiagram.Link toCancel = d.links.stream().filter(l -> !l.to().equals(bookId)).findFirst().orElseThrow();
        double[] a = new double[6], b = new double[6];
        painter.linkPath(d, toBook).getPathIterator(null).currentSegment(a);
        painter.linkPath(d, toCancel).getPathIterator(null).currentSegment(b);
        assertEquals(a[0], b[0], 0.001, "both leave main()'s row on the same side");
        assertTrue(b[1] - a[1] >= 6, "separate starts, the call to the lower row lower: " + a[1] + " / " + b[1]);
    }

    @Test
    void theProjectBandHasItsOwnLookThatIsSavedAndReset() {
        FlowDiagram d = diagram();
        FlowDiagram.Box main = box(d, "Main");
        main.projectFill = new java.awt.Color(0x1F2A4D);
        main.projectBorder = new java.awt.Color(0xC27C0E);
        main.projectFormat = new FlowDiagram.Format(true, true, new java.awt.Color(0xFFFFFF));
        assertFalse(d.sameAs(diagram()), "a look change");
        FlowDiagram again = diagram();
        again.applyLooks(d.looks());
        FlowDiagram.Box back = box(again, "Main");
        assertEquals(main.projectFill, back.projectFill);
        assertEquals(main.projectBorder, back.projectBorder);
        assertEquals(main.projectFormat, back.projectFormat);
        assertEquals(main.projectFill, box(d.copy(), "Main").projectFill, "History copies keep it");
        d.resetLooks();
        assertNull(main.projectFill);
        assertNull(main.projectFormat);
    }

    @Test
    void autoLayoutSendsEveryCallLeftToRight() {
        FlowDiagram d = FlowDiagram.of(List.of(), Set.of());
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        FlowDiagram.Box a = d.addUnknownClass("Main", 0, 0), b = d.addUnknownClass("DestinationService", 0, 0),
                c = d.addUnknownClass("Travel", 0, 0);
        FlowDiagram.Method am = d.addUnknownMethod(a, "main()"), bm = d.addUnknownMethod(b, "search()"),
                cm = d.addUnknownMethod(c, "getDestination()");
        d.connect(am.id, bm.id);
        d.connect(bm.id, cm.id);
        d.connect(am.id, cm.id); // called directly too: it still goes after DestinationService
        painter.autoLayout(d);
        assertTrue(a.x < b.x, "Main before DestinationService");
        assertTrue(b.x < c.x, "DestinationService before Travel: no call goes right to left");
    }

    // ---------- the flow changed while the diagram was open ----------

    @Test
    void linesAddedInTheFlowShowInAnOpenDiagram() {
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        List<FlowEntry> before = List.of(main8, main13, book21);
        FlowDiagram d = FlowDiagram.of(before, Set.of(FlowSync.link(mainKey, bookKey)));
        painter.autoLayout(d);
        FlowDiagram.Box main = d.boxes.get(0), booking = d.boxes.get(1);
        main.note = "kept";

        FlowEntry price24 = line("PricingEngine.java", "PricingEngine", "quote()", 24, "BigDecimal running = BigDecimal.ZERO;");
        FlowEntry book22 = line("BookingService.java", "BookingService", "bookTrip()", 22, "return null;");
        String priceKey = FlowSync.methodKey(price24);
        List<FlowEntry> now = List.of(main8, main13, book21, book22, price24);
        FlowDiagram s = d.synced(now, List.of(book22, price24), Set.of(FlowSync.link(mainKey, bookKey),
                FlowSync.link(bookKey, priceKey)), new FlowDiagram.IdPool(d.lastId()), painter.measure(), 24);

        assertEquals(3, s.boxes.size(), "PricingEngine came in");
        FlowDiagram.Box pricing = s.boxes.get(2);
        assertEquals("PricingEngine", pricing.name);
        assertEquals("quote()", pricing.methods.get(0).name);
        assertEquals(2, s.box(booking.id).methods.get(0).saved, "bookTrip() has its new line");
        assertEquals("kept", s.box(main.id).note, "what was done in the diagram stays");
        assertTrue(s.links.stream().anyMatch(l -> l.to().equals(pricing.methods.get(0).id)), "its call is drawn");
        assertTrue(pricing.x > s.box(booking.id).x, "placed right of the class that calls it");
        assertFalse(s.spreadOut(painter.measure(), 24, null), "it covers no other class");
        assertEquals(2, d.boxes.size(), "the diagram it came from is unchanged");
    }

    @Test
    void methodsWhoseLinesLeftTheFlowLeaveAnOpenDiagram() {
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        FlowDiagram d = FlowDiagram.of(List.of(main8, main13, book21, cancel55), Set.of(FlowSync.link(mainKey, bookKey)));
        FlowDiagram s = d.synced(List.of(main8, book21), List.of(), Set.of(FlowSync.link(mainKey, bookKey)),
                new FlowDiagram.IdPool(d.lastId()), painter.measure(), 24);
        FlowDiagram.Box booking = s.boxes.get(1);
        assertEquals(List.of("bookTrip()"), booking.methods.stream().map(m -> m.name).toList(), "cancel() left");
        assertEquals(1, s.boxes.get(0).methods.get(0).saved, "main() has one line left");

        FlowDiagram gone = s.synced(List.of(main8), List.of(), Set.of(), new FlowDiagram.IdPool(s.lastId()), painter.measure(), 24);
        assertEquals(1, gone.boxes.size(), "BookingService has nothing left in the flow");
    }

    @Test
    void theSameNewMethodGetsTheSameIdInEveryStep() {
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        FlowDiagram a = FlowDiagram.of(List.of(main8), Set.of());
        FlowDiagram b = FlowDiagram.of(List.of(main8, book21), Set.of());
        FlowDiagram.IdPool pool = new FlowDiagram.IdPool(Math.max(a.lastId(), b.lastId()));
        List<FlowEntry> now = List.of(main8, book21, cancel55);
        FlowDiagram sa = a.synced(now, List.of(cancel55), Set.of(), pool, painter.measure(), 24);
        FlowDiagram sb = b.synced(now, List.of(cancel55), Set.of(), pool, painter.measure(), 24);
        String idA = sa.boxes.stream().flatMap(x -> x.methods.stream()).filter(m -> m.name.equals("cancel()")).findFirst().orElseThrow().id;
        String idB = sb.boxes.stream().flatMap(x -> x.methods.stream()).filter(m -> m.name.equals("cancel()")).findFirst().orElseThrow().id;
        assertEquals(idA, idB);
    }

    // ---------- find a class or method (Ctrl+F) ----------

    @Test
    void searchListsClassesWithTheirMethodsAndNarrowsAsYouType() {
        FlowDiagram d = diagram();
        List<FlowDiagramSearch.Item> all = FlowDiagramSearch.items(d);
        assertEquals(List.of("Main", "Main.main()", "BookingService", "BookingService.bookTrip()", "BookingService.cancel()"),
                all.stream().map(FlowDiagramSearch.Item::text).toList());
        assertEquals(all, FlowDiagramSearch.filter(all, "  "), "nothing typed: everything");
        assertEquals(List.of("BookingService", "BookingService.bookTrip()", "BookingService.cancel()"),
                FlowDiagramSearch.filter(all, "booking").stream().map(FlowDiagramSearch.Item::text).toList(), "a class brings its methods");
        assertEquals(List.of("BookingService.cancel()"),
                FlowDiagramSearch.filter(all, "CANC").stream().map(FlowDiagramSearch.Item::text).toList(), "case is ignored");
        assertEquals(List.of("BookingService.bookTrip()"),
                FlowDiagramSearch.filter(all, "service trip").stream().map(FlowDiagramSearch.Item::text).toList(), "every word");
        assertTrue(FlowDiagramSearch.filter(all, "nothing like it").isEmpty());
    }

    // ---------- calls with notes ----------

    /**
     * Two calls into TravelCodes.normalize() (from BookingService.create() and PromoDiscountRule.apply()) that go
     * around the same boxes: with notes, each has its own line (no shared stretch), so a note isn't read as the
     * other call's.
     */
    @Test
    void callsWithNotesIntoTheSameMethodEachHaveTheirOwnLine() {
        FlowEntry create = line("BookingService.java", "BookingService", "create()", 20, "TravelCodes.normalize(code);");
        FlowEntry quote = line("PricingEngine.java", "PricingEngine", "quote()", 24, "rules.apply();");
        FlowEntry season = line("SeasonalSurchargeRule.java", "SeasonalSurchargeRule", "apply()", 18, "TravelCodes.normalize(mode);");
        FlowEntry normalize = line("TravelCodes.java", "TravelCodes", "normalize()", 9, "return code.trim();");
        FlowEntry promo = line("PromoDiscountRule.java", "PromoDiscountRule", "apply()", 19, "TravelCodes.normalize(promo);");
        String c = FlowSync.methodKey(create), q = FlowSync.methodKey(quote), se = FlowSync.methodKey(season),
                n = FlowSync.methodKey(normalize), p = FlowSync.methodKey(promo);
        FlowDiagram d = FlowDiagram.of(List.of(create, quote, season, normalize, promo),
                Set.of(FlowSync.link(c, q), FlowSync.link(q, se), FlowSync.link(se, n), FlowSync.link(c, n), FlowSync.link(p, n)));
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        // the screenshot's layout: one row of boxes, PromoDiscountRule under the first one
        double[][] at = {{450, 0}, {860, 0}, {1250, 0}, {1650, 0}, {20, 190}};
        for (int k = 0; k < at.length; k++) {
            d.boxes.get(k).x = at[k][0];
            d.boxes.get(k).y = at[k][1];
        }
        FlowDiagram.Link fromCreate = null, fromPromo = null;
        for (FlowDiagram.Link l : d.links) {
            String from = d.boxOf(l.from()).name, to = d.boxOf(l.to()).name;
            if (to.equals("TravelCodes") && from.equals("BookingService")) fromCreate = l;
            if (to.equals("TravelCodes") && from.equals("PromoDiscountRule")) fromPromo = l;
        }
        assertNotNull(fromCreate);
        assertNotNull(fromPromo);
        double merged = painter.sharedLength(d, fromCreate, fromPromo);
        assertTrue(merged < 30, "even without notes, the two calls keep their own way where there is room (" + merged + ")");
        // the ends meet at the same method row: a few pixels by the arrow head are shared whatever the way
        d.setCallNote(fromCreate, "BookingService");
        d.setCallNote(fromPromo, "PromoDiscountRule");
        double apart = painter.sharedLength(d, fromCreate, fromPromo);
        assertTrue(apart < 30, "each call with a note has its own line (" + apart + " shared)");
        assertTrue(painter.sharedLength(d, fromPromo, fromCreate) < 30);

        // no hook at the end: the line from below doesn't go up past normalize()'s row to come back down to it
        double row = painter.rowBounds(d.boxOf(fromPromo.to()), 0).getCenterY();
        double top = painter.linkPath(d, fromPromo).getBounds2D().getMinY();
        assertTrue(top > row - 15, "no hook above the end row (" + top + " vs row " + row + ")");

        // every note is on the drawing (a line around the top of the boxes doesn't take its note off it)
        for (Rectangle2D r : painter.callNoteLayout(d).values()) {
            assertTrue(r.getY() >= 0 && r.getX() >= 0, "note on the drawing: " + r);
        }

        // only one of them with a note: still apart (the note is on its own call's line)
        d.setCallNote(fromPromo, null);
        assertTrue(painter.sharedLength(d, fromCreate, fromPromo) < 30);
    }

    /** A line dragged by hand keeps its way (saved with the looks); its middle pieces can be grabbed. */
    @Test
    void aLineRoutedByHandKeepsItsWay() {
        FlowDiagram d = diagram();
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        painter.autoLayout(d);
        FlowDiagram.Link l = d.links.get(0);
        List<Double> lanes = painter.lanesOf(d, l);
        assertEquals(1, lanes.size() % 2, "lanes start and end with a vertical piece: " + lanes);

        // dragged: down below the boxes and back up
        double below = 600;
        double x1 = lanes.get(0);
        d.setRoute(l, List.of(x1, below, x1 + 40));
        java.awt.geom.Rectangle2D shape = painter.linkPath(d, l).getBounds2D();
        assertEquals(below, shape.getMaxY(), 1, "the line runs along the lane it was dragged to");
        assertEquals(1, painter.laneAt(d, l, d.route(l), new java.awt.geom.Point2D.Double((x1 + x1 + 40) / 2, below)),
                "the horizontal piece is lane 1");
        assertEquals(-1, painter.laneAt(d, l, d.route(l), new java.awt.geom.Point2D.Double(5000, 5000)));

        // saved with the looks and back
        FlowDiagram again = diagram();
        again.applyLooks(d.looks());
        assertEquals(List.of((double) Math.round(x1), below, (double) Math.round(x1 + 40)), again.route(again.links.get(0)));
        assertFalse(d.sameAs(again) && !d.lookSignature().equals(diagram().lookSignature()) && false);
        assertNotEquals(d.lookSignature(), diagram().lookSignature(), "a moved line is a change (Undo, Save)");

        // dragged onto a class: it goes to the nearest place that misses every box
        FlowDiagram.Box other = d.boxes.get(1);
        java.awt.geom.Rectangle2D ob = painter.bounds(other);
        List<Double> through = new java.util.ArrayList<>(List.of(x1, ob.getCenterY(), x1 + 40));
        through.set(0, ob.getCenterX());
        assertFalse(painter.handRouteClear(d, l, through), "a line through " + other.name);
        Double free = painter.clearLane(d, l, through, 0, ob.getCenterX());
        assertNotNull(free);
        through.set(0, free);
        assertTrue(painter.handRouteClear(d, l, through) || painter.clearLane(d, l, through, 1, ob.getCenterY()) != null);

        d.setRoute(l, null);
        assertNull(d.route(l), "Reset Line Route");
    }

    /** A dragged line comes into its method from the side its last lane is on, going around the box. */
    @Test
    void aDraggedLineEntersFromTheSideItIsOn() {
        FlowDiagram d = diagram();
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        painter.autoLayout(d);
        FlowDiagram.Link l = d.links.get(0);
        java.awt.geom.Rectangle2D to = painter.bounds(d.boxOf(l.to()));
        double under = to.getMaxY() + 60;
        // the last vertical lane past the middle of the callee's box: in from the right
        d.setRoute(l, List.of(to.getX() - 40, under, to.getX() + to.getWidth() * 0.8));
        java.awt.geom.Rectangle2D shape = painter.linkPath(d, l).getBounds2D();
        assertTrue(shape.getMaxX() > to.getMaxX(), "it goes round to the right side");
        assertTrue(painter.handRouteClear(d, l, d.route(l)), "around the box, not through it");
        // before the middle: under the box where it was dropped, then up to just under it, left, up and in from the left
        double dropped = to.getX() + to.getWidth() * 0.4;
        d.setRoute(l, List.of(to.getX() - 40, under, dropped));
        java.awt.geom.Rectangle2D left = painter.linkPath(d, l).getBounds2D();
        assertEquals(dropped, left.getMaxX(), 1, "the vertical piece stays where it was dropped, under the box");
        assertEquals(under, left.getMaxY(), 1);
        assertTrue(painter.handRouteClear(d, l, d.route(l)), "around the box, not through it");
        assertEquals(2, painter.laneAt(d, l, d.route(l), new java.awt.geom.Point2D.Double(dropped, under - 20)),
                "the piece under the box is still the lane dragged");

        // lines may cross the classes when the setting says so
        java.awt.geom.Rectangle2D other = painter.bounds(d.boxes.get(0));
        List<Double> across = List.of(other.getCenterX() + 300, other.getCenterY(), to.getX() - 20);
        painter.linesOverBoxes = true;
        assertTrue(painter.handRouteClear(d, l, across));
        painter.linesOverBoxes = false;
    }

    /** A note dragged along its line stays there (saved with the looks); it never leaves its line. */
    @Test
    void aNoteDraggedAlongItsLineStaysThere() {
        FlowDiagram d = diagram();
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        painter.autoLayout(d);
        FlowDiagram.Link l = d.links.get(0);
        d.setCallNote(l, "books it");
        java.awt.Shape path = painter.linkPath(d, l);
        java.awt.geom.Point2D near = new java.awt.geom.Point2D.Double(path.getBounds2D().getMinX() + 20, path.getBounds2D().getCenterY() + 200);
        double t = painter.nearestAlong(d, l, near);
        assertTrue(t < 0.5, "the mouse far off the line: the nearest spot on it (" + t + ")");
        d.setNotePlace(l, t);
        java.awt.geom.Rectangle2D note = painter.callNoteBounds(d, l);
        assertNotNull(note);
        assertTrue(path.intersects(note.getX() - 1, note.getY() - 1, note.getWidth() + 2, note.getHeight() + 2), "on its line");

        FlowDiagram again = diagram();
        again.applyLooks(d.looks());
        assertEquals(Math.round(t * 1000) / 1000.0, again.notePlace(again.links.get(0)), 0.001);
    }

    /**
     * The booking flow as laid out in the screenshot: BookingService.create() calls BookingDto, PricingEngine and
     * TravelCodes; TravelCodes.normalize() is also called from SeasonalSurchargeRule and PromoDiscountRule. Where there
     * is room, no two of these lines run along each other (out of create(), or into normalize()).
     */
    @Test
    void callsOutOfAndIntoOneMethodDontOverlapWhereThereIsRoom() {
        FlowEntry create = line("BookingService.java", "BookingService", "create()", 20, "TravelCodes.normalize(code);");
        FlowEntry ref = line("BookingDto.java", "BookingDto", "setReference()", 28, "this.reference = reference;");
        FlowEntry quote = line("PricingEngine.java", "PricingEngine", "quote()", 24, "rules.apply();");
        FlowEntry season = line("SeasonalSurchargeRule.java", "SeasonalSurchargeRule", "apply()", 18, "TravelCodes.normalize(mode);");
        FlowEntry normalize = line("TravelCodes.java", "TravelCodes", "normalize()", 9, "return code.trim();");
        FlowEntry promo = line("PromoDiscountRule.java", "PromoDiscountRule", "apply()", 19, "TravelCodes.normalize(promo);");
        String c = FlowSync.methodKey(create), r = FlowSync.methodKey(ref), q = FlowSync.methodKey(quote),
                se = FlowSync.methodKey(season), n = FlowSync.methodKey(normalize), p = FlowSync.methodKey(promo);
        FlowDiagram d = FlowDiagram.of(List.of(create, ref, quote, season, normalize, promo),
                Set.of(FlowSync.link(c, r), FlowSync.link(c, q), FlowSync.link(c, n), FlowSync.link(q, se),
                        FlowSync.link(se, n), FlowSync.link(p, n)));
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        java.util.Map<String, double[]> at = java.util.Map.of("BookingService", new double[]{430, 0},
                "BookingDto", new double[]{815, 0}, "PricingEngine", new double[]{815, 187},
                "SeasonalSurchargeRule", new double[]{1200, 187}, "TravelCodes", new double[]{1593, 125},
                "PromoDiscountRule", new double[]{0, 187});
        for (FlowDiagram.Box b : d.boxes) {
            b.x = at.get(b.name)[0];
            b.y = at.get(b.name)[1];
        }
        String png = System.getenv("TRACEWEAVE_RENDER");
        if (png != null) { // a picture of it, to look at
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(1900, 420, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, 1900, 420);
            g.translate(20, 20);
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            painter.paint(g, d, FlowDiagramPainter.LIGHT, FlowDiagramPainter.Selection.NONE);
            g.dispose();
            try { javax.imageio.ImageIO.write(img, "png", new java.io.File(png)); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        }
        java.util.Map<String, FlowDiagram.Link> by = new java.util.HashMap<>();
        for (FlowDiagram.Link l : d.links) by.put(d.boxOf(l.from()).name + ">" + d.boxOf(l.to()).name, l);
        FlowDiagram.Link toCodes = by.get("BookingService>TravelCodes");
        for (String other : List.of("BookingService>PricingEngine", "BookingService>BookingDto",
                "SeasonalSurchargeRule>TravelCodes", "PromoDiscountRule>TravelCodes")) {
            double shared = painter.sharedLength(d, toCodes, by.get(other));
            assertTrue(shared < 30, "create() > normalize() runs along " + other + " for " + shared);
        }
        assertTrue(painter.sharedLength(d, by.get("PromoDiscountRule>TravelCodes"), by.get("SeasonalSurchargeRule>TravelCodes")) < 30);
    }
}
