package com.example.flowtracker.code;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowCallCheckTest {
    private static final String SERVICE = "file:///p/src/BookingService.java#create()";
    private static final String CODES = "file:///p/src/TravelCodes.java#normalize()";
    private static final String DTO = "file:///p/src/BookingDto.java#setReference()";

    @Test
    void anExpectedCallThatIsGoneIsTold() {
        List<String> expected = List.of(FlowSync.link(SERVICE, CODES), FlowSync.link(SERVICE, DTO));
        Set<String> now = new LinkedHashSet<>(List.of(FlowSync.link(SERVICE, DTO)));
        List<String> gone = FlowCallCheck.missing(expected, now);
        assertEquals(List.of(FlowSync.link(SERVICE, CODES)), gone);
        assertEquals("BookingService.create() → TravelCodes.normalize()", FlowCallCheck.label(gone.get(0)));
        String report = FlowCallCheck.report("Flow 1", gone, List.of());
        assertTrue(report.contains("1 expected call isn't in the code any more"), report);
        assertTrue(report.contains("BookingService.create() → TravelCodes.normalize()"), report);
    }

    @Test
    void newCallsAreToldTooAndNothingGoneSaysSo() {
        List<String> expected = List.of(FlowSync.link(SERVICE, DTO));
        Set<String> now = new LinkedHashSet<>(List.of(FlowSync.link(SERVICE, DTO), FlowSync.link(SERVICE, CODES)));
        assertTrue(FlowCallCheck.missing(expected, now).isEmpty());
        assertEquals(List.of(FlowSync.link(SERVICE, CODES)), FlowCallCheck.added(expected, now));
        String report = FlowCallCheck.report("Flow 1", List.of(), FlowCallCheck.added(expected, now));
        assertTrue(report.startsWith("“Flow 1”: every expected call is still in the code."), report);
        assertTrue(report.contains("New since it was marked"), report);
    }
}
