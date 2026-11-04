package com.example.flowtracker.debug;

import com.example.flowtracker.model.FlowValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stop at Value: what is searched in the paused frame's variables. */
class FlowValueHuntTest {

    private static FlowValue v(String name, String value, FlowValue... children) {
        FlowValue x = new FlowValue(name);
        x.value = value;
        x.children.addAll(List.of(children));
        return x;
    }

    /** The frame: this › hotelService › nightlyRates › "ooty" = 1800.0, and the request's order id. */
    private static FlowValue frame() {
        return v("frame", "", v("this", "{BookingService@1177}",
                        v("hotelService", "{HotelService@1141}", v("nightlyRates", "size = 1", v("\"ooty\"", "1800.0")))),
                v("orderId", "\"A-4711\""));
    }

    @Test
    void aValueDeepInTheVariablesIsFound() {
        assertNotNull(FlowValueHunt.firstMatch(frame(), "ooty"), "a map key, three levels deep");
        assertNotNull(FlowValueHunt.firstMatch(frame(), "4711"), "part of a value");
        assertNotNull(FlowValueHunt.firstMatch(frame(), "/A-\\d+/"), "a regular expression");
        assertNull(FlowValueHunt.firstMatch(frame(), "goa"));
    }

    @Test
    void partOfAValueIsEnough() {
        FlowValue f = v("frame", "", v("greeting", "\"Hello world\""));
        assertNotNull(FlowValueHunt.firstMatch(f, "ll"), "\"ll\" is in \"Hello world\"");
        assertNotNull(FlowValueHunt.firstMatch(f, "WORLD"), "upper / lower case doesn't matter");
    }

    @Test
    void severalValuesAnyOrAll() {
        assertEquals(List.of("ooty", "4711", "/A-\\d+/"), FlowValueHunt.values("ooty\n\n  4711 \n/A-\\d+/\n"), "one per line");
        List<java.util.regex.Pattern> ps = List.of(com.example.flowtracker.model.FlowValueSearch.pattern("ooty"),
                com.example.flowtracker.model.FlowValueSearch.pattern("goa"));
        List<String> any = FlowValueHunt.matches(frame(), ps, false);
        assertEquals(1, any.size(), "ooty is there (goa isn't): enough for any");
        assertTrue(any.get(0).contains("nightlyRates") && any.get(0).endsWith("\"ooty\" = 1800.0"), any.get(0));
        assertEquals(List.of(), FlowValueHunt.matches(frame(), ps, true), "not all of them are there");
        List<java.util.regex.Pattern> both = List.of(com.example.flowtracker.model.FlowValueSearch.pattern("ooty"),
                com.example.flowtracker.model.FlowValueSearch.pattern("4711"));
        assertEquals(2, FlowValueHunt.matches(frame(), both, true).size(), "both in the same stop");
    }

    @Test
    void theModesSayWhatTheyDo() {
        assertEquals(6, FlowValueHunt.Mode.values().length);
        assertTrue(FlowValueHunt.Mode.FIELD.waits());
        assertFalse(FlowValueHunt.Mode.forValues().contains(FlowValueHunt.Mode.FIELD), "watching a field has its own dialog");
        assertEquals(FlowValueHunt.Mode.ANYWHERE, FlowValueHunt.Mode.of("something old"));
        for (FlowValueHunt.Mode m : FlowValueHunt.Mode.values()) assertNotNull(m.tip);
        // the first three wait for the debugger to stop (the first two put their own breakpoints); the others step
        assertTrue(FlowValueHunt.Mode.ANYWHERE.waits());
        assertTrue(FlowValueHunt.Mode.FLOW_LINES.waits());
        assertTrue(FlowValueHunt.Mode.WHEREVER_IT_STOPS.waits());
        assertFalse(FlowValueHunt.Mode.STEP_INTO.waits());
        assertFalse(FlowValueHunt.Mode.STEP_METHOD.waits());
    }

    @Test
    void aWatchStopsWhenItsFieldIsSetAsAsked() {
        // values a test can make without a debugged app: null (the field is empty, or set to null)
        assertEquals(List.of("BookingDto.status: null → null"), FlowFieldWatch.watch("SET", List::of).test("BookingDto.status", null, null));
        assertTrue(FlowFieldWatch.watch("CHANGE", List::of).test("BookingDto.status", null, null).isEmpty(), "null to null: no change");
        assertTrue(FlowFieldWatch.watch("NULL", List::of).test("BookingDto.status", null, null).isEmpty(), "it was null already");
        assertTrue(FlowFieldWatch.watch("VALUE", () -> List.of(java.util.regex.Pattern.compile("CANCELLED")))
                .test("BookingDto.status", null, null).isEmpty());
        assertEquals("null", FlowFieldWatch.show(null));
    }

    @Test
    void stringsAndNumbersAreReadAsTheirText() {
        for (String t : new String[]{"String", "java.lang.String", "Integer", "java.math.BigDecimal", "LocalDate", "List<String>".substring(5, 11)}) {
            assertTrue(FlowValueReader.isPlain(t), t);
        }
        for (String t : new String[]{"BookingDto", "java.util.List<String>", "HashMap", "", "Object[]"}) {
            assertFalse(FlowValueReader.isPlain(t), t);
        }
    }
}
