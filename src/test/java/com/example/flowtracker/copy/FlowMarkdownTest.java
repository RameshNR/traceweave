package com.example.flowtracker.copy;

import com.example.flowtracker.code.FlowSync;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowStore;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.example.flowtracker.Fixtures.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Markdown copy: diagrams, line tables and the Original Log. */
class FlowMarkdownTest {

    private static final String KW = "<span style=\"color:#3574F0;font-weight:600\">";

    private static String link(FlowEntry caller, FlowEntry callee) {
        return FlowSync.link(FlowSync.methodKey(caller), FlowSync.methodKey(callee));
    }

    private static String diagrams(String md) {
        return md.substring(md.indexOf("## 🔀 Flow"), md.indexOf("## 🧩 Details"));
    }

    // ---------- sequence diagram built from the call graph ----------

    private final FlowEntry main = line("Main.java", "Main", "main()", 13, "destinationService.search(\"\", 15000, 4);");
    private final FlowEntry search = line("DestinationService.java", "DestinationService", "search()", 66, "travel.getDestination()");
    private final FlowEntry findById = line("DestinationService.java", "DestinationService", "findById()", 73, "return travels.stream()");
    private final FlowEntry getDest = line("Travel.java", "Travel", "getDestination()", 122, "return destination;");
    private final FlowEntry bookTrip = line("BookingService.java", "BookingService", "bookTrip()", 26, "destinationService.findById(travelId);");
    private final FlowEntry cancel = line("BookingService.java", "BookingService", "cancelBooking()", 61, "hotelService.releaseRooms()");
    private final FlowEntry calc = line("HotelService.java", "HotelService", "calculateCost()", 34, "!isAvailable(destination, rooms)");
    private final FlowEntry avail = line("HotelService.java", "HotelService", "isAvailable()", 27, "return true;");

    /** Each caller's calls in source order, as FlowSync produces them. */
    private Set<String> travelLinks() {
        Set<String> links = new LinkedHashSet<>();
        links.add(link(main, search));
        links.add(link(main, bookTrip));
        links.add(link(main, cancel));
        links.add(link(search, getDest));
        links.add(link(bookTrip, findById));
        links.add(link(bookTrip, calc));
        links.add(link(bookTrip, getDest));
        links.add(link(cancel, getDest));
        links.add(link(calc, avail));
        return links;
    }

    @Test
    void sequenceFollowsCallsNotTheGroupedLineOrder() {
        // Grouped View lists search() and findById() together (same file), which is not call order.
        String d = diagrams(FlowMarkdown.toMarkdown(List.of(main, search, findById, getDest, bookTrip, cancel, calc, avail),
                false, travelLinks(), true));
        int searchCall = d.indexOf("p1->>p2: search()");
        int getDestCall = d.indexOf("p2->>p3: getDestination()");
        int searchReturn = d.indexOf("p2-->>p1: return");
        assertTrue(searchCall >= 0, d);
        assertTrue(getDestCall > searchCall && getDestCall < searchReturn, "getDestination() is called inside search()\n" + d);
        assertTrue(d.contains("p4->>p2: findById()"), d);
        assertTrue(d.contains("p4->>p5: calculateCost()") && d.contains("p5->>p5: isAvailable()"), d);
        assertTrue(d.contains("p1->>p4: cancelBooking()"), d);
        assertEquals(1, d.split("Note over").length - 1, "one chain, starting at main()\n" + d);
    }

    @Test
    void sequenceIsTheSameInFlatView() {
        String grouped = diagrams(FlowMarkdown.toMarkdown(List.of(main, search, findById, getDest, bookTrip, cancel, calc, avail),
                false, travelLinks(), true));
        String flat = diagrams(FlowMarkdown.toMarkdown(List.of(main, search, getDest, bookTrip, findById, calc, avail, cancel),
                true, travelLinks(), true));
        assertEquals(grouped, flat);
    }

    @Test
    void participantsAreFileNamesAndUnconnectedCodeIsSeparate() {
        FlowEntry field = line("BookingService.java", "BookingService", "-", 10, "private final HotelService hotelService;");
        FlowEntry ctor = line("BookingService.java", "BookingService", "BookingService() [constructor]", 15, "this.x = x;");
        FlowEntry js = line("travel.js", "-", "addEventListener(\"DOMContentLoaded\") callback", 5, "const m = 1;");
        FlowEntry tld = line("travel.tld", "travel", "[taglib]", 6, "<tlib-version>1.0</tlib-version>");
        String d = diagrams(FlowMarkdown.toMarkdown(List.of(field, ctor, bookTrip, js, tld), false, Set.of(), true));

        assertTrue(d.contains("3 separate flows"), d);
        assertTrue(d.contains("participant p1 as 📦 TravelBooking<br/>BookingService.java"), d);
        assertTrue(d.contains("participant p1 as 📦 TravelBooking<br/>travel.js"), d);
        assertTrue(d.contains("participant p1 as 📦 TravelBooking<br/>travel.tld"), d);
        assertFalse(d.substring(d.indexOf("travel.js"), d.indexOf("### 3.")).contains("tlib-version"), d);
        assertFalse(d.contains("Note over p1: -") || d.contains("[class level]"), "fields are left out\n" + d);
        String chart = FlowMarkdown.toMarkdown(List.of(field, ctor, bookTrip, js, tld), false, Set.of(), false);
        assertEquals(3, chart.split("flowchart TD").length - 1, "the flowchart is split the same way");
    }

    @Test
    void diagramsNameTheProjectOfEachFile() {
        FlowEntry a = line("Main.java", "Main", "main()", 19, "bookingService.bookTrip(101);");
        FlowEntry b = line("BookingService.java", "BookingService", "bookTrip()", 33, "travel.getDestination(), nights, rooms);");
        FlowEntry c = line("TravelReportHandler.java", "TravelReportHandler", "handle()", 39, "x();");
        a.projectName = "TravelBooking";
        b.projectName = "TravelBooking";
        c.projectName = "TravelReports";
        Set<String> links = new LinkedHashSet<>(List.of(link(a, b), link(c, b)));
        String seq = diagrams(FlowMarkdown.toMarkdown(List.of(a, b, c), true, links, true));
        assertTrue(seq.contains("participant p1 as 📦 TravelBooking<br/>Main.java"), seq);
        assertTrue(seq.contains("as 📦 TravelReports<br/>TravelReportHandler.java"), seq);
        String chart = diagrams(FlowMarkdown.toMarkdown(List.of(a, b, c), true, links, false));
        assertTrue(chart.contains("[\"📦 TravelBooking<br/>BookingService.java<br/><b>bookTrip()</b>\"]"), chart);
        assertTrue(chart.contains("[\"📦 TravelReports<br/>TravelReportHandler.java<br/><b>handle()</b>\"]"), chart);
    }

    @Test
    void jspCallingJavaIsOneFlow() {
        FlowEntry page = line("search.jsp", "-", "[scriptlet]", 21, "packages = new DestinationService().search(destination, budget, days);");
        FlowEntry js = line("travel.js", "-", "addEventListener(\"submit\") callback", 9, "const budget = 1;");
        Set<String> links = new LinkedHashSet<>(List.of(link(page, search), link(search, getDest)));
        String d = diagrams(FlowMarkdown.toMarkdown(List.of(page, search, getDest, js), true, links, true));
        assertTrue(d.contains("participant p1 as 📦 TravelBooking<br/>search.jsp") && d.contains("p1->>p2: search()"), d);
        assertTrue(d.contains("p2->>p3: getDestination()"), d);
        assertTrue(d.contains("2 separate flows") && d.contains("### 2. travel.js"), d);
    }

    @Test
    void javaScriptFunctionsCallingEachOtherAreLinked() {
        FlowEntry handler = line("booking.js", "-", "addEventListener(\"submit\") callback", 24, "if (!validate(event.target)) event.preventDefault();");
        FlowEntry validate = line("booking.js", "-", "validate()", 5, "const ok = checkBudget(...) && checkDays(...);");
        FlowEntry budget = line("booking.js", "-", "checkBudget()", 11, "return Number(value) >= 0;");
        Set<String> links = new LinkedHashSet<>(List.of(link(handler, validate), link(validate, budget)));
        String d = diagrams(FlowMarkdown.toMarkdown(List.of(handler, validate, budget), true, links, true));
        assertTrue(d.contains("p1->>p1: validate()") && d.contains("p1->>p1: checkBudget()"), d);
        assertFalse(d.contains("separate flows"), d);
    }

    // ---------- line table ----------

    @Test
    void lineTableHasHighlightedCodeAndNotes() {
        FlowEntry tagged = line("BookingService.java", "BookingService", "bookTrip()", 21, "if (customerName == null || customerName.isBlank()");
        tagged.tag = "bug";
        tagged.note = "Storing the Data";
        FlowEntry plain = line("BookingService.java", "BookingService", "bookTrip()", 27, "if (nights >= travel.getDurationDays()) {");
        String md = FlowMarkdown.toMarkdown(List.of(tagged, plain), true, Set.of(), false);

        assertTrue(md.contains("| Line | Code | Note |\n|---:|---|---|"), md);
        assertTrue(md.contains("| 21 | <code>" + KW + "if</span> (customerName == " + KW + "null</span> &#124;&#124; "
                + "customerName.isBlank()</code> | 🐞 Bug · _Storing the Data_ |"), md);
        assertTrue(md.contains("| 27 | <code>" + KW + "if</span> (nights &gt;= travel.getDurationDays()) {</code> |  |"), md);
        assertFalse(md.contains("/* 21 */") || md.contains("```java"), md);
        assertTrue(FlowMarkdown.toMarkdown(List.of(plain), true, Set.of(), false).contains("| Line | Code |\n|---:|---|\n| 27 |"),
                "no Note column when no line has one");
    }

    @Test
    void highlightingPerLanguage() {
        String js = FlowMarkdown.toMarkdown(List.of(line("a.js", "-", "f()", 3, "const s = `hi ${name}`; // greet")), true, Set.of(), false);
        assertTrue(js.contains("<code>" + KW + "const</span> s = <span style=\"color:#2E9E4F\">`hi ${name}`</span>; "
                + "<span style=\"color:#8C8C8C;font-style:italic\">// greet</span></code>"), js);
        String jsp = FlowMarkdown.toMarkdown(List.of(line("search.jsp", "-", "[page]", 29, "<h1 class=\"t\">Search</h1>")), true, Set.of(), false);
        assertTrue(jsp.contains("&lt;h1</span>") && jsp.contains("color:#2E9E4F\">\"t\"</span>"), jsp);
        String java = FlowMarkdown.toMarkdown(List.of(line("A.java", "A", "m()", 4, "List<Travel> t = repo.find(42);")), true, Set.of(), false);
        assertTrue(java.contains("color:#8E5CD9\">List</span>&lt;<span style=\"color:#8E5CD9\">Travel</span>&gt;"), java);
        assertTrue(java.contains("color:#1A97B0\">42</span>"), java);
    }

    // ---------- Original Log (FlowCopy) ----------

    @Test
    void markdownEndsWithTheOriginalLogOfTheCurrentView() {
        FlowEntry a = line("BookingService.java", "BookingService", "bookTrip()", 21, "if (customerName == null) {");
        a.note = "Storing the Data";
        FlowEntry b = line("HotelService.java", "HotelService", "calculateCost()", 34, "return nights * rooms;");
        List<FlowEntry> lines = List.of(a, b);
        FlowStore store = new FlowStore();
        store.setCopyMarkdown(true);
        store.setNestedCallLog(false); // the plain layout; the nested one is tested in FlowCallLogTest

        String grouped = FlowCopy.textFor(store, lines, lines, Set.of(), "Flow 1: Checkout bug");
        assertTrue(grouped.startsWith("# 🧭 Flow 1: Checkout bug"), grouped);
        assertTrue(grouped.indexOf("## 📜 Original Log") > grouped.indexOf("## 🧩 Details"), grouped);
        assertTrue(grouped.contains("Grouped View copy") && grouped.contains("```text\n" + FlowFormat.toText(lines) + "```"), grouped);

        store.setFlatView(true);
        String flat = FlowCopy.textFor(store, lines, lines, Set.of(), "Flow 1");
        assertTrue(flat.contains("Flat View copy") && flat.contains("```text\n" + FlowFormat.toFlatText(lines, lines) + "```"), flat);

        store.setCopyMarkdown(false);
        assertFalse(FlowCopy.textFor(store, lines, lines, Set.of(), "x").contains("Original Log"));
    }
}
