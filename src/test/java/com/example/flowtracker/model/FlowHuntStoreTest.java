package com.example.flowtracker.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stop at Value's hunts: several values, each switched on or off, added and removed while it hunts. */
class FlowHuntStoreTest {

    @Test
    void valuesCanBeSwitchedOffAddedAndRemoved() {
        FlowHuntStore store = new FlowHuntStore();
        int[] changes = {0};
        store.addListener(() -> changes[0]++);
        FlowHuntStore.Hunt h = store.start(List.of("BK-1042", "asha@example.com"), false, "ANYWHERE");
        assertEquals(List.of("BK-1042", "asha@example.com"), h.active());

        store.setOn(h, "BK-1042", false);
        assertEquals(List.of("asha@example.com"), h.active(), "switched off: kept, not looked for");
        assertEquals(List.of("BK-1042", "asha@example.com"), h.values);
        assertEquals("asha@example.com", h.valuesText());

        assertTrue(store.addValue(h, "  SAVE10 "));
        assertFalse(store.addValue(h, "SAVE10"), "once only");
        assertFalse(store.addValue(h, " "), "blank");
        assertEquals(List.of("asha@example.com", "SAVE10"), h.active());

        store.setOn(h, "BK-1042", true);
        store.removeValue(h, "asha@example.com");
        assertEquals(List.of("BK-1042", "SAVE10"), h.active());
        assertTrue(changes[0] >= 5, "every change is told (the hunt looks for the new values at once)");
    }

    @Test
    void recordsAreMadeOnceEditedAndTheirStopsDeleted() {
        FlowHuntStore store = new FlowHuntStore();
        FlowHuntStore.Hunt h = store.create(List.of("BK-1042", "SAVE10"), false, "ANYWHERE", true, "", "");
        assertEquals("STOPPED", h.status, "added, not looking yet");
        assertEquals(h, store.same(List.of("SAVE10", "BK-1042"), "ANYWHERE", "", ""), "the same values in another order: the same record");
        assertEquals(null, store.same(List.of("BK-1042"), "ANYWHERE", "", ""));
        assertEquals(null, store.same(List.of("BK-1042", "SAVE10"), "FLOW_LINES", "", ""), "another way of looking");

        FlowHuntStore.Hunt w = store.create(List.of(), false, "FIELD", true, "com.travel.common.BookingDto#status", "CHANGE");
        assertTrue(w.isWatch());
        assertEquals("BookingDto.status", w.targetText());
        assertTrue(w.name.startsWith("Watch "), w.name);
        assertEquals(w, store.same(List.of(), "FIELD", "com.travel.common.BookingDto#status", "CHANGE"));
        assertEquals(null, store.same(List.of(), "FIELD", "com.travel.common.BookingDto#status", "NULL"));
        FlowHuntStore.Hunt whole = store.create(List.of(), false, "FIELD", true, "com.travel.common.Outer$Line", "SET");
        assertEquals("Line", whole.targetText(), "a whole object: its class");

        store.setOn(h, "SAVE10", false);
        store.update(h, List.of("BK-1042", "asha@example.com"), true, "ANYWHERE", false, "", "");
        assertEquals(List.of("BK-1042", "asha@example.com"), h.active(), "a value switched off and edited away is gone");
        assertFalse(h.keepGoing);

        store.begin(h);
        assertEquals("HUNTING", h.status);
        FlowHuntStore.Stop a = new FlowHuntStore.Stop(), b = new FlowHuntStore.Stop();
        store.addStop(h, a);
        store.addStop(h, b);
        store.setSnapshot(a, "{\"booking\": {}}");
        assertEquals("{\"booking\": {}}", a.snapshot);
        store.removeStops(h, List.of(a));
        assertEquals(List.of(b), h.stops);
    }

    @Test
    void recordsAreNamedAsTypedOrNumberedNotAfterTheirValues() {
        FlowHuntStore store = new FlowHuntStore();
        assertEquals("Hunt 1", store.nextName(false));
        FlowHuntStore.Hunt a = store.create("", List.of("code"), false, "ANYWHERE", true, "", "");
        assertEquals("Hunt 1", a.name, "no name typed: numbered, not \"Hunt 1: code\"");
        store.update(a, List.of("BK-1042"), false, "ANYWHERE", true, "", "");
        assertEquals("Hunt 1", a.name, "its values changed: the name stays right");
        assertEquals("Watch 2", store.nextName(true));
        FlowHuntStore.Hunt b = store.create("  Booking status  ", List.of(), false, "FIELD", true, "com.x.BookingDto#status", "CHANGE");
        assertEquals("Booking status", b.name);
    }

    @Test
    void fieldsArePinnedAndUnpinned() {
        FlowHuntStore store = new FlowHuntStore();
        store.togglePin("BookingDto.reference");
        store.togglePin("frame.booking");
        assertEquals(java.util.Set.of("BookingDto.reference", "frame.booking"), store.pinned());
        store.togglePin("BookingDto.reference");
        assertEquals(java.util.Set.of("frame.booking"), store.pinned());
    }

    @Test
    void deletedRecordsAndStopsGoToTheRecycleBinAndComeBack() {
        FlowHuntStore store = new FlowHuntStore();
        FlowHuntStore.Hunt h = store.create("Booking ref", List.of("BK-10"), false, "ANYWHERE", true, "", "");
        FlowHuntStore.Stop a = new FlowHuntStore.Stop(), b = new FlowHuntStore.Stop(), c = new FlowHuntStore.Stop();
        a.at = 1;
        b.at = 2;
        c.at = 3;
        for (FlowHuntStore.Stop s : List.of(a, b, c)) store.addStop(h, s);

        store.removeStops(h, List.of(a, c));
        assertEquals(List.of(b), h.stops);
        assertEquals("2 stops of Booking ref", store.bin().get(0).label());
        assertTrue(store.restore(store.bin().get(0)));
        assertEquals(List.of(a, b, c), h.stops, "back in the order they were found");
        assertTrue(store.bin().isEmpty());

        store.delete(List.of(h));
        assertTrue(store.hunts().isEmpty());
        assertEquals("Booking ref (3 stops)", store.bin().get(0).label());
        assertTrue(store.restore(store.bin().get(0)));
        assertEquals(List.of(h), store.hunts());

        // stops whose record was deleted for good stay in the bin
        store.removeStops(h, List.of(b));
        store.delete(List.of(h));
        store.purge(List.of(store.bin().get(0))); // the record, for good
        assertFalse(store.restore(store.bin().get(0)));
        assertEquals(1, store.bin().size());
    }
}
