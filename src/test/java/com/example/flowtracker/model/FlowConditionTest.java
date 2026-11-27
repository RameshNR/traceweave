package com.example.flowtracker.model;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowConditionTest {
    private static final Map<String, String> BOOKING = new java.util.HashMap<>(Map.of(
            "seats", "3", "status", "CANCELLED", "total", "10810.80", "reference", "BK-1042", "traveller.name", "Asha Rao"));

    private static Function<String, String> booking() {
        return BOOKING::get;
    }

    @Test
    void comparesNumbersAsNumbersAndTheRestAsText() {
        assertTrue(FlowCondition.parse("seats > 2").test(booking()));
        assertFalse(FlowCondition.parse("seats > 3").test(booking()));
        assertTrue(FlowCondition.parse("seats >= 3").test(booking()));
        assertTrue(FlowCondition.parse("total > 10000").test(booking()), "10810.80 as a number");
        assertTrue(FlowCondition.parse("status == CANCELLED").test(booking()), "an enum by its name");
        assertTrue(FlowCondition.parse("status = CANCELLED").test(booking()), "a single = means the same");
        assertTrue(FlowCondition.parse("reference contains \"104\"").test(booking()));
        assertTrue(FlowCondition.parse("traveller.name == 'Asha Rao'").test(booking()), "a field inside, in quotes with a space");
    }

    @Test
    void joinsWithAndOrNotAndBrackets() {
        assertTrue(FlowCondition.parse("status == CANCELLED && total > 10000").test(booking()));
        assertFalse(FlowCondition.parse("status == CONFIRMED && total > 10000").test(booking()));
        assertTrue(FlowCondition.parse("status == CONFIRMED || seats > 2").test(booking()));
        assertTrue(FlowCondition.parse("!(seats < 2) and reference != null").test(booking()));
        assertTrue(FlowCondition.parse("missing == null").test(booking()), "no such field: null");
        assertFalse(FlowCondition.parse("missing > 1").test(booking()));
    }

    @Test
    void tellsWhyTextIsntACondition() {
        assertNull(FlowCondition.error("seats > 2"));
        assertNotNull(FlowCondition.error(""));
        assertNotNull(FlowCondition.error("seats >"), "a value is missing");
        assertNotNull(FlowCondition.error("seats 2"), "no comparison");
        assertNotNull(FlowCondition.error("(seats > 2"), "a bracket isn't closed");
        assertNotNull(FlowCondition.error("status == \"open"), "a text isn't closed");
    }
}
