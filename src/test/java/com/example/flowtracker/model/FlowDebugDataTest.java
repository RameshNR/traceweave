package com.example.flowtracker.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Debug data saved from the debugger: store, edits with history, Recycle Bin, search and compare. */
class FlowDebugDataTest {

    private static FlowValue v(String name, String type, String value, FlowValue... children) {
        FlowValue x = new FlowValue(name);
        x.type = type;
        x.value = value;
        x.children.addAll(List.of(children));
        return x;
    }

    /** session › attributes › "cart" › items, total; and the user. */
    private static FlowValue session(String total) {
        return v("session", "StandardSessionFacade", "{...}",
                v("attributes", "ConcurrentHashMap", "size = 2",
                        v("\"cart\"", "Cart", "{Cart@51}",
                                v("items", "ArrayList", "size = 2"),
                                v("total", "BigDecimal", total)),
                        v("\"user\"", "String", "\"ann@example.com\"")));
    }

    private static FlowSnapshot snapshot(String flow, String total) {
        FlowSnapshot s = new FlowSnapshot();
        s.name = "session";
        s.flowName = flow;
        s.fileUrl = "file:///p/CartServlet.java";
        s.fileName = "CartServlet.java";
        s.line = 42;
        s.value = session(total);
        return s;
    }

    @Test
    void valuesAreFoundByPathAndNamedForShowing() {
        FlowValue root = session("12.50");
        assertEquals("total", FlowDebugStore.at(root, "0.0.1").name);
        assertSame(root, FlowDebugStore.at(root, ""));
        assertNull(FlowDebugStore.at(root, "0.9"));
        assertEquals("session › attributes › \"cart\" › total", FlowDebugStore.label(root, "0.0.1"));
    }

    @Test
    void editsAreKeptInTheHistoryAndCanBeReverted() {
        FlowDebugStore store = new FlowDebugStore();
        FlowSnapshot s = snapshot("Flow 1", "12.50");
        store.add(s);
        store.editValue(s, "0.0.1", "10.00");
        assertEquals("10.00", FlowDebugStore.at(s.value, "0.0.1").value);
        assertEquals(1, s.edits.size());
        assertEquals("12.50", s.edits.get(0).before);
        assertEquals("session › attributes › \"cart\" › total", s.edits.get(0).label);
        store.editValue(s, "0.0.1", "10.00");
        assertEquals(1, s.edits.size(), "the same value again is no change");

        store.revert(s, s.edits.get(0));
        assertEquals("12.50", FlowDebugStore.at(s.value, "0.0.1").value);
        assertEquals(2, s.edits.size(), "reverting is a change too: the history stays complete");
    }

    @Test
    void deletedValuesGoToTheRecycleBinAndComeBack() {
        FlowDebugStore store = new FlowDebugStore();
        FlowSnapshot a = snapshot("Flow 1", "12.50"), b = snapshot("Flow 2", "15.00");
        a.savedAt = 1000;
        b.savedAt = 2000;
        store.add(a);
        store.add(b);
        assertEquals(List.of(b, a), store.getSnapshots(), "newest first");

        store.delete(List.of(a));
        assertEquals(List.of(b), store.getSnapshots());
        assertEquals(List.of(a), store.getDeleted());
        assertTrue(a.deletedAt > 0);

        store.restore(List.of(a));
        assertEquals(List.of(b, a), store.getSnapshots(), "back in saving order");
        assertEquals(0, a.deletedAt);

        store.delete(List.of(a));
        store.purge(List.of(a));
        assertTrue(store.getDeleted().isEmpty());
    }

    @Test
    void theSameVariableAtTheSameLineIsOfferedForComparison() {
        FlowDebugStore store = new FlowDebugStore();
        FlowSnapshot first = snapshot("Flow 1", "12.50"), other = snapshot("Flow 2", "15.00");
        FlowSnapshot elsewhere = snapshot("Flow 2", "15.00");
        elsewhere.line = 50;
        store.add(first);
        store.add(elsewhere);
        store.add(other);
        assertEquals(List.of(first), store.sameVariable(other), "same name, file and line, any flow");
    }

    @Test
    void compareShowsWhatChanged() {
        FlowValue before = session("12.50"), after = session("15.00");
        after.children.get(0).children.add(v("\"coupon\"", "String", "\"SPRING\""));
        List<FlowValueDiff.Row> rows = FlowValueDiff.compare(before, after);
        assertEquals(2, FlowValueDiff.differences(rows));
        FlowValueDiff.Row total = rows.stream().filter(r -> r.label().endsWith("total")).findFirst().orElseThrow();
        assertEquals(FlowValueDiff.Kind.CHANGED, total.kind());
        assertEquals("12.50", total.left());
        assertEquals("15.00", total.right());
        FlowValueDiff.Row coupon = rows.stream().filter(r -> r.label().endsWith("\"coupon\"")).findFirst().orElseThrow();
        assertEquals(FlowValueDiff.Kind.ADDED, coupon.kind());
        assertNull(coupon.left());
        assertEquals(0, FlowValueDiff.differences(FlowValueDiff.compare(before, session("12.50"))));
    }

    /** A map entry as the debugger shows it: "3" = "goa" -> {Double@1400} 2500.0, with key and value. */
    private static FlowValue entry(int index, String key, int id, String value) {
        return v(String.valueOf(index), "", "\"" + key + "\" -> {Double@" + id + "} " + value,
                v("key", "String", "\"" + key + "\""), v("value", "Double@" + id, value));
    }

    @Test
    void mapEntriesAreMatchedByKeyAndInstanceIdsAreIgnored() {
        FlowValue before = v("hotelService", "HotelService@1141", "{HotelService@1141}",
                v("nightlyRates", "HashMap@1371", "size = 3",
                        entry(0, "ooty", 1397, "1800.0"), entry(1, "goa", 1400, "2500.0"), entry(2, "manali", 1401, "3000.0")));
        // A new run: new objects (other ids), and "Delli" added in the middle, which moves goa and manali down.
        FlowValue after = v("hotelService", "HotelService@2207", "{HotelService@2207}",
                v("nightlyRates", "HashMap@2210", "size = 4",
                        entry(0, "ooty", 2301, "1800.0"), entry(1, "Delli", 2418, "5000.0"),
                        entry(2, "goa", 2302, "2500.0"), entry(3, "manali", 2303, "3000.0")));
        List<FlowValueDiff.Row> rows = FlowValueDiff.compare(before, after);
        List<FlowValueDiff.Row> differ = rows.stream().filter(r -> r.kind() != FlowValueDiff.Kind.SAME).toList();
        assertEquals(List.of("nightlyRates", "\"Delli\""), differ.stream().map(FlowValueDiff.Row::name).toList(),
                "only the size and the new entry; not the object ids, not goa and manali");
        assertEquals(FlowValueDiff.Kind.ADDED, differ.get(1).kind());
        assertEquals("5000.0", differ.get(1).right(), "an entry is shown as key = value, without its key / value rows");
        assertEquals("{HotelService}", FlowValueDiff.withoutIds("{HotelService@1141}"));
        assertEquals("\"ann@example.com\"", FlowValueDiff.withoutIds("\"ann@example.com\""), "text with an @ stays");
    }

    @Test
    void listElementsAreLinedUpByValue() {
        FlowValue before = v("items", "ArrayList", "size = 3", v("0", "", "\"a\""), v("1", "", "\"b\""), v("2", "", "\"c\""));
        FlowValue after = v("items", "ArrayList", "size = 4", v("0", "", "\"a\""), v("1", "", "\"x\""), v("2", "", "\"b\""),
                v("3", "", "\"c\""));
        List<FlowValueDiff.Row> differ = FlowValueDiff.compare(before, after).stream()
                .filter(r -> r.kind() != FlowValueDiff.Kind.SAME).toList();
        assertEquals(2, differ.size(), "the size and one added element: " + differ);
        assertEquals(FlowValueDiff.Kind.ADDED, differ.get(1).kind());
        assertEquals("\"x\"", differ.get(1).right());
    }

    @Test
    void searchFindsNamesKeysTypesAndValuesAnywhere() {
        FlowSnapshot s = snapshot("Flow 1", "12.50");
        Pattern cart = FlowValueSearch.pattern("CART");
        assertNotNull(cart);
        List<FlowValueSearch.Match> m = FlowValueSearch.findInSnapshots(List.of(s), cart, 100);
        assertEquals("session › attributes › \"cart\"", m.get(0).label(), "a map key, ignoring case");
        assertEquals("0.0", m.get(0).path());

        List<FlowValueSearch.Match> mail = FlowValueSearch.findInSnapshots(List.of(s), FlowValueSearch.pattern("example.com"), 100);
        assertEquals("value", mail.get(0).matched());
        assertEquals(1, FlowValueSearch.findInSnapshots(List.of(s), FlowValueSearch.pattern("/^Big/"), 100).size(), "a type, by regex");
        assertNull(FlowValueSearch.pattern("  "));
        assertNotNull(FlowValueSearch.error("/(/"));
        assertNull(FlowValueSearch.error("plain (text"));
    }

    @Test
    void exportedValuesComeBackWithTheirFieldsAndEdits() throws Exception {
        FlowDebugStore store = new FlowDebugStore();
        FlowSnapshot s = snapshot("Flow 1", "12.50");
        store.add(s);
        store.editValue(s, "0.0.1", "10.00");
        String json = FlowDebugTransfer.toJson(store.getSnapshots());

        List<FlowSnapshot> again = FlowDebugTransfer.fromJson(json, List.of());
        assertEquals(1, again.size());
        FlowSnapshot back = again.get(0);
        assertEquals("session", back.name);
        assertEquals("CartServlet.java:42", back.where());
        assertEquals("10.00", FlowDebugStore.at(back.value, "0.0.1").value);
        assertEquals(1, back.edits.size(), "the edit history travels too");
        assertEquals(s.id, back.id);

        List<FlowSnapshot> twice = FlowDebugTransfer.fromJson(json, store.getSnapshots());
        assertTrue(!twice.get(0).id.equals(s.id), "importing what is there already keeps both");
        store.addAll(twice);
        assertEquals(2, store.getSnapshots().size());
        assertEquals(List.of(s), store.sameVariable(twice.get(0)), "imported values are compared like saved ones");

        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> FlowDebugTransfer.fromJson("{\"a\": 1}", List.of()));
    }

    @Test
    void valuesBecomeJsonAndText() {
        FlowValue hotel = v("hotelService", "HotelService@1141", "{HotelService@1141}",
                v("nightlyRates", "HashMap@1371", "size = 2", entry(0, "ooty", 1397, "1800.0"), entry(1, "goa", 1400, "2500.0")),
                v("names", "ArrayList", "size = 2", v("0", "String", "\"Ann\""), v("1", "String", "\"Bo \\\"B\\\"\"")),
                v("open", "boolean", "true"), v("manager", "Person", "null"));
        String json = FlowValueText.toJson(hotel);
        assertTrue(json.contains("\"nightlyRates\": {"), json);
        assertTrue(json.contains("\"ooty\": 1800.0"), "a map is an object by key: " + json);
        assertTrue(json.contains("\"names\": ["), "a list is an array: " + json);
        assertTrue(json.contains("\"Ann\""), json);
        assertTrue(json.contains("\"open\": true") && json.contains("\"manager\": null"), json);
        assertTrue(!json.contains("@1141"), "no instance ids: " + json);

        String text = FlowValueText.toText(hotel);
        assertTrue(text.startsWith("hotelService = {HotelService@1141}  {HotelService@1141}\n    nightlyRates = size = 2"), text);
    }

    @Test
    void pastedDataIsDetectedAndLaidOutLineByLine() {
        FlowTextFormat.Detected json = FlowTextFormat.detect("{\"b\": 2, \"a\": {\"y\": [1, 2], \"x\": null}}");
        assertEquals("JSON", json.kind());
        assertEquals("json", json.extension());
        assertTrue(json.formatted().indexOf("\"a\"") < json.formatted().indexOf("\"b\""), "keys sorted: " + json.formatted());
        assertTrue(json.formatted().contains("\"x\": null"), json.formatted());

        FlowTextFormat.Detected obj = FlowTextFormat.detect("Booking@1305{id=4, rooms={ooty=12, goa=9}, guests=[Ann, \"Bo, B\"]}");
        assertEquals("Object text (toString / debugger)", obj.kind());
        List<String> lines = List.of(obj.formatted().split("\n"));
        assertEquals("Booking{", lines.get(0), "instance id left out: " + lines);
        assertTrue(lines.contains("    ooty=12,"), lines.toString());
        assertTrue(lines.contains("    \"Bo, B\""), "a comma in quotes stays: " + lines);

        FlowTextFormat.Detected xml = FlowTextFormat.detect("<a><b>1</b><c/></a>");
        assertEquals("XML / HTML", xml.kind());
        assertEquals("<a>\n  <b>1</b>\n  <c/>\n</a>\n", xml.formatted());
        assertEquals("Text", FlowTextFormat.detect("plain line").kind());
    }

    @Test
    void sortedJsonComparesWhateverTheMapOrder() {
        FlowValue a = v("rooms", "HashMap", "size = 2", entry(0, "ooty", 1, "12"), entry(1, "goa", 2, "9"));
        FlowValue b = v("rooms", "HashMap", "size = 2", entry(0, "goa", 7, "9"), entry(1, "ooty", 8, "12"));
        assertEquals(FlowValueText.toJson(a, true), FlowValueText.toJson(b, true));
    }

    @Test
    void fieldsCanBeAddedRenamedAndRemovedAndEveryChangeReverted() {
        FlowDebugStore store = new FlowDebugStore();
        FlowSnapshot s = snapshot("Flow 1", "12.50");
        store.add(s);
        String path = store.addField(s, "", "note", "\"check\"");
        assertEquals("\"check\"", FlowDebugStore.at(s.value, path).value);
        store.editName(s, path, "comment");
        assertEquals("comment", FlowDebugStore.at(s.value, path).name);
        int before = s.value.children.size();
        store.removeField(s, "0");
        assertEquals(before - 1, s.value.children.size());
        FlowValueEdit removal = s.edits.get(s.edits.size() - 1);
        assertEquals("REMOVE", removal.kind);
        store.revert(s, removal);
        assertEquals(before, s.value.children.size(), "the removed field is back");
        store.revert(s, s.edits.get(1)); // the rename
        assertEquals("note", FlowDebugStore.at(s.value, path).name);
        store.revert(s, s.edits.get(0)); // the added field
        assertEquals(before - 1, s.value.children.size(), "the added field is gone again");
    }

    /** hotelService › availableRooms: a HashMap as the debugger shows it ("jaipur" -> 7 with key and value). */
    private static FlowSnapshot hotel() {
        FlowSnapshot s = new FlowSnapshot();
        s.name = "hotelService";
        s.value = v("hotelService", "HotelService", "{HotelService@1141}",
                v("availableRooms", "HashMap", "size = 2",
                        v("0", "HashMap$Node", "\"ooty\" -> 12", v("key", "String", "\"ooty\""), v("value", "Integer", "12")),
                        v("1", "HashMap$Node", "\"jaipur\" -> 7", v("key", "String", "\"jaipur\""), v("value", "Integer", "7"))));
        return s;
    }

    @Test
    void editingAMapKeyChangesTheEntryAndItsJson() {
        FlowDebugStore store = new FlowDebugStore();
        FlowSnapshot s = hotel();
        store.add(s);
        store.editValue(s, "0.1.0", "\"jaipurCity\"");
        assertEquals("\"jaipurCity\" -> 7", FlowDebugStore.at(s.value, "0.1").value);
        String json = FlowValueText.valueJson(s.value, true);
        assertTrue(json.contains("\"jaipurCity\": 7"), json);
        assertTrue(!json.contains("\"jaipur\""), json);
    }

    @Test
    void jsonEditsKeepMapsAsMapsAndUnchangedValuesAsTheyWere() {
        FlowSnapshot s = hotel();
        FlowValue now = FlowValueText.applyJson(s.value,
                "{\"availableRooms\": {\"ooty\": 12, \"jaipurCity\": 7, \"CAN\": 10}}");
        FlowValue rooms = now.children.get(0);
        assertEquals("size = 3", rooms.value);
        assertTrue(FlowValueText.isMap(rooms), "still a map of entries");
        assertEquals("\"ooty\" -> 12", rooms.children.get(0).value);
        assertEquals("Integer", rooms.children.get(0).children.get(1).type, "unchanged: the debugger's type is kept");
        assertEquals("\"jaipurCity\" -> 7", rooms.children.get(1).value);
        assertEquals("\"CAN\" -> 10", rooms.children.get(2).value);
        assertEquals("{HotelService@1141}", now.value);
    }

    @Test
    void textEditsAreReadBackAndResetGoesBackToTheSavedData() {
        FlowDebugStore store = new FlowDebugStore();
        FlowSnapshot s = hotel();
        store.add(s);
        String text = FlowValueText.toText(s.value).replace("\"jaipur\"", "\"pune\"");
        FlowValue now = FlowValueText.fromText(text);
        assertEquals("\"pune\" -> 7", FlowDebugStore.at(now, "0.1").value, "the entry follows its key");
        assertEquals(FlowValueText.toText(s.value).replace("jaipur", "pune"), FlowValueText.toText(now));
        store.replaceValue(s, now, "edited as text");
        assertTrue(FlowDebugStore.isChanged(s));
        assertEquals("REPLACE", s.edits.get(0).kind);
        store.reset(s);
        assertTrue(!FlowDebugStore.isChanged(s), "back to the saved data");
        assertEquals("\"jaipur\" -> 7", FlowDebugStore.at(s.value, "0.1").value);
        store.revert(s, s.edits.get(1)); // undo the reset
        assertEquals("\"pune\" -> 7", FlowDebugStore.at(s.value, "0.1").value);
    }

    @Test
    void addingToAMapMakesAnEntryAndCountsIt() {
        FlowDebugStore store = new FlowDebugStore();
        FlowSnapshot s = hotel();
        store.add(s);
        FlowValue entry = v("2", "", "\"CAN\" -> 10", v("key", "", "\"CAN\""), v("value", "", "10"));
        store.addField(s, "0", entry);
        assertEquals("size = 3", FlowDebugStore.at(s.value, "0").value);
        assertTrue(FlowValueText.valueJson(s.value, true).contains("\"CAN\": 10"));
        store.removeField(s, "0.2");
        assertEquals("size = 2", FlowDebugStore.at(s.value, "0").value);
    }

    @Test
    void textThatCantBeReadSaysWhy() {
        try {
            FlowValueText.fromText("a = 1" + System.lineSeparator() + "            b = 2");
            throw new AssertionError("should fail");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Line 2"), e.getMessage());
        }
        try {
            FlowValueText.applyJson(hotel().value, "{oops");
            throw new AssertionError("should fail");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().startsWith("It isn't valid JSON"), e.getMessage());
        }
    }

    @Test
    void valuesAddedByHandGetTypesLikeTheDebuggers() {
        FlowValue now = FlowValueText.applyJson(hotel().value,
                "{\"availableRooms\": {\"ooty\": 12, \"jaipur\": 7, \"Vada\": 120}, \"notAvailableRooms\": {\"mailam\": 150}}");
        FlowValue vada = now.children.get(0).children.get(2);
        assertEquals("HashMap$Node", vada.type, "like the map's other entries");
        assertEquals("String", vada.children.get(0).type);
        assertEquals("Integer", vada.children.get(1).type);
        FlowValue added = now.children.get(1);
        assertEquals("Object", added.type);
        assertEquals("Integer", added.children.get(0).type);
        assertEquals("String", FlowValueText.guessType("\"Goa\""));
        assertEquals("Double", FlowValueText.guessType("2500.0"));
        assertEquals("Long", FlowValueText.guessType("12345678901"));
        assertEquals("Boolean", FlowValueText.guessType("true"));
    }

    /** A map as the debugger really shows it: entries "ooty" -> {Integer@1155} 12, and an empty one. */
    private static FlowValue rooms() {
        return v("hotelService", "HotelService", "{HotelService@1141}",
                v("availableRooms", "HashMap@1146", "size = 2",
                        v("0", "HashMap$Node@1150", "\"ooty\" -> {Integer@1155} 12", v("key", "String", "\"ooty\""), v("value", "Integer@1155", "12")),
                        v("1", "HashMap$Node@1151", "\"goa\" -> {Double@1159} 2500.0", v("key", "String", "\"goa\""), v("value", "Double@1159", "2500.0"))),
                v("notAvailableRooms", "HashMap@1147", "size = 0"));
    }

    @Test
    void editsKeepTheDebuggersEntryTextSoCompareSeesNoChange() {
        FlowValue old = rooms();
        FlowValue json = FlowValueText.applyJson(old, FlowValueText.valueJson(old, true));
        assertEquals(FlowValueText.toText(old), FlowValueText.toText(json), "JSON round trip changes nothing");
        FlowValue text = FlowValueText.fromText(FlowValueText.toText(old));
        assertEquals(FlowValueText.toText(old), FlowValueText.toText(text), "text round trip changes nothing");
        assertEquals(0, FlowValueDiff.differences(FlowValueDiff.compare(old, json)));
        FlowValue changed = FlowValueText.applyJson(old, "{\"availableRooms\": {\"ooty\": 15, \"goa\": 2500.0}, \"notAvailableRooms\": {}}");
        assertEquals("\"ooty\" -> {Integer@1155} 15", changed.children.get(0).children.get(0).value);
    }

    @Test
    void anEmptyMapCountsWhatIsAddedAndNewEntriesLookLikeTheOthers() {
        FlowDebugStore store = new FlowDebugStore();
        FlowSnapshot s = new FlowSnapshot();
        s.name = "hotelService";
        s.value = rooms();
        store.add(s);
        FlowValue json = FlowValueText.applyJson(s.value, "{\"availableRooms\": {\"ooty\": 12, \"goa\": 2500.0, \"kollu\": 4},"
                + " \"notAvailableRooms\": {\"delly\": 12}}");
        assertEquals("size = 3", json.children.get(0).value);
        assertEquals("size = 1", json.children.get(1).value, "the empty map counts its new entry");
        assertEquals("\"delly\" -> 12", json.children.get(1).children.get(0).value);
        FlowValue kollu = json.children.get(0).children.get(2);
        assertTrue(kollu.value.startsWith("\"kollu\" -> {"), kollu.value);
        String text = FlowValueText.toText(s.value).replace("notAvailableRooms = size = 0  {HashMap@1147}",
                "notAvailableRooms = size = 0  {HashMap@1147}\n        0 = \"delly\" -> 12\n            key = \"delly\"\n            value = 12");
        FlowValue read = FlowValueText.fromText(text);
        assertEquals("size = 1", read.children.get(1).value);
        assertEquals("Integer", read.children.get(1).children.get(0).children.get(1).type);
    }

    @Test
    void compareRowsKnowWhereTheirValuesAre() {
        FlowValue a = rooms(), b = rooms();
        b.children.get(0).children.get(0).children.get(1).value = "15";
        FlowValueDiff.Row ooty = FlowValueDiff.compare(a, b).stream().filter(r -> r.name().equals("\"ooty\"")).findFirst().orElseThrow();
        assertEquals("0.0.1", ooty.leftPath(), "the entry's value");
        assertEquals("15", FlowDebugStore.at(b, ooty.rightPath()).value);
    }

    @Test
    void textWithoutIdsGetsItsIdsBackWhereNothingChanged() {
        FlowValue old = rooms();
        FlowValue edited = FlowValueText.fromText(FlowValueDiff.withoutIds(FlowValueText.toText(old)).replace("2500.0", "2600.0"));
        FlowValueText.keepIds(edited, old);
        assertEquals("{HotelService@1141}", edited.value);
        assertEquals("\"ooty\" -> {Integer@1155} 12", edited.children.get(0).children.get(0).value);
        assertEquals("2600.0", edited.children.get(0).children.get(1).children.get(1).value);
    }
}
