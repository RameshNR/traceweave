package com.example.flowtracker.code;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FlowStackTraceTest {

    @Test
    void theRootCauseIsTakenWithItsFrames() {
        String trace = """
                org.springframework.web.util.NestedServletException: Request processing failed
                \tat org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:1014)
                \tat javax.servlet.http.HttpServlet.service(HttpServlet.java:665)
                Caused by: java.lang.IllegalStateException: No seats left on TR-102
                \tat com.example.travel.api.service.BookingService.create(BookingService.java:42)
                \tat com.example.travel.api.controller.BookingController.create(BookingController.java:31)
                \tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
                \t... 52 more
                """;
        FlowStackTrace.Trace t = FlowStackTrace.parse(trace);
        assertNotNull(t);
        assertEquals("IllegalStateException: No seats left on TR-102", t.exception());
        assertEquals(List.of("BookingService.create:42", "BookingController.create:31", "NativeMethodAccessorImpl.invoke0:0"),
                t.frames().stream().map(f -> f.className().substring(f.className().lastIndexOf('.') + 1) + "." + f.method() + ":" + f.line()).toList());
    }

    @Test
    void withoutCausedByTheFirstPartIsTaken() {
        FlowStackTrace.Trace t = FlowStackTrace.parse("""
                java.lang.NullPointerException
                    at app//com.shop.Order$Line.total(Order.java:88)
                    at com.shop.Cart.sum(Cart.java:12)
                """);
        assertNotNull(t);
        assertEquals("NullPointerException", t.exception());
        assertEquals("com.shop.Order$Line", t.frames().get(0).className(), "the module part (app//) is left out");
        assertEquals(88, t.frames().get(0).line());
        assertEquals(2, t.frames().size());
    }

    @Test
    void textWithoutFramesIsNoTrace() {
        assertNull(FlowStackTrace.parse("just some log line\nand another"));
    }
}
