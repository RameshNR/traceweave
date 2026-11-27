package com.example.flowtracker.debug;

import com.example.flowtracker.Fixtures;
import com.example.flowtracker.copy.FlowAi;
import com.example.flowtracker.model.FlowEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowRequestRecorderTest {

    @Test
    void theHooksKeepToThePackageAllTheProjectsClassesAreIn() {
        assertEquals("com.example.travel", FlowRequestRecorder.commonPackage(List.of(
                "com.example.travel.api.BookingService", "com.example.travel.common.TravelCodes", "com.example.travel.web.Workflow$Step")));
        assertEquals("com.shop", FlowRequestRecorder.commonPackage(List.of("com.shop.Order", "com.shop.cart.Cart")));
        assertEquals("", FlowRequestRecorder.commonPackage(List.of("com.shop.Order", "org.other.Thing")), "nothing shared: no package filter");
    }

    @Test
    void theAiRequestIsOnlyTheFlowsOwnText() {
        FlowEntry a = Fixtures.line("BookingService.java", "BookingService", "create()", 20, "TravelCodes.normalize(code);");
        a.note = "Storing the Data";
        String text = FlowAi.request(List.of(a), Set.of());
        assertTrue(text.contains("BookingService.java:20"), text);
        assertTrue(text.contains("TravelCodes.normalize(code);"), text);
        assertTrue(text.contains("// note: Storing the Data"), text);
        assertTrue(text.contains("The flow starts in BookingService.create()"), "the built-in summary goes first: " + text);
    }
}
