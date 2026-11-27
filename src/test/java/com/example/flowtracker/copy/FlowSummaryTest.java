package com.example.flowtracker.copy;

import com.example.flowtracker.Fixtures;
import com.example.flowtracker.code.FlowSync;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowValueDiff;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowSummaryTest {

    @Test
    void aFlowIsToldFromItsStartThroughItsCalls() {
        FlowEntry create = Fixtures.line("BookingController.java", "BookingController", "create()", 31, "return service.create(r);");
        FlowEntry service = Fixtures.line("BookingService.java", "BookingService", "create()", 20, "dto.setReference(ref);");
        FlowEntry service2 = Fixtures.line("BookingService.java", "BookingService", "create()", 24, "TravelCodes.normalize(code);");
        FlowEntry ref = Fixtures.line("BookingDto.java", "BookingDto", "setReference()", 28, "this.reference = reference;");
        FlowEntry norm = Fixtures.line("TravelCodes.java", "TravelCodes", "normalize()", 9, "return code.trim();");
        create.projectName = "travel-api";
        service.projectName = "travel-api";
        service2.projectName = "travel-api";
        ref.projectName = "travel-common";
        norm.projectName = "travel-common";
        service.note = "Storing the Data";
        norm.tag = "bug";
        String c = FlowSync.methodKey(create), s = FlowSync.methodKey(service), r = FlowSync.methodKey(ref), n = FlowSync.methodKey(norm);
        String text = FlowSummary.of(List.of(create, service, service2, ref, norm),
                Set.of(FlowSync.link(c, s), FlowSync.link(s, r), FlowSync.link(s, n)));
        assertTrue(text.startsWith("The flow starts in BookingController.create() (BookingController.java, travel-api)."), text);
        assertTrue(text.contains("It calls BookingService.create(). BookingService.create() calls BookingDto.setReference() and TravelCodes.normalize()."), text);
        assertTrue(text.contains("Notes: BookingService.create(), line 20: “Storing the Data”."), text);
        assertTrue(text.contains("Tagged: "), text);
        assertTrue(text.endsWith("5 lines in 4 methods, 4 files, 2 projects."), text);
    }

    @Test
    void anEmptyFlowSaysSo() {
        assertTrue(FlowSummary.of(List.of(), Set.of()).startsWith("The flow is empty"));
    }

    @Test
    void onlyTheCharactersThatDifferAreMarked() {
        assertArrayEquals(new int[]{6, 0}, FlowValueDiff.differingSpan("BK-1042", "BK-1043"));
        assertArrayEquals(new int[]{1, 1}, FlowValueDiff.differingSpan("\"OK\"", "\"NO\""));
        // never overlapping: "aa" → "aaa" shares 2 at the start, none left for the end
        int[] span = FlowValueDiff.differingSpan("aa", "aaa");
        assertEquals(2, span[0] + span[1]);
    }
}
