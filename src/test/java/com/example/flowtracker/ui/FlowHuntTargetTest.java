package com.example.flowtracker.ui;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

/** Watch a Field or Object: what is typed ("BookingDto.status", "BookingDto") becomes the class and field to watch. */
public class FlowHuntTargetTest extends LightJavaCodeInsightFixtureTestCase {

    public void testFieldsAndClassesAreFoundByShortOrFullName() {
        myFixture.addClass("""
                package com.travel.common;
                public class Base {
                    protected String createdBy;
                }
                """);
        myFixture.addClass("""
                package com.travel.common;
                public class BookingDto extends Base {
                    private String status;
                    public static class Line { private int amount; }
                }
                """);
        assertEquals("com.travel.common.BookingDto#status", FlowHuntPanel.resolveTarget(getProject(), "BookingDto.status"));
        assertEquals("com.travel.common.BookingDto#status", FlowHuntPanel.resolveTarget(getProject(), " com.travel.common.BookingDto.status "));
        assertEquals("com.travel.common.BookingDto", FlowHuntPanel.resolveTarget(getProject(), "BookingDto"));
        assertEquals("a field of a superclass is watched where it is declared",
                "com.travel.common.Base#createdBy", FlowHuntPanel.resolveTarget(getProject(), "BookingDto.createdBy"));
        assertEquals("com.travel.common.BookingDto$Line#amount", FlowHuntPanel.resolveTarget(getProject(), "Line.amount"));
        assertTrue(FlowHuntPanel.resolveTarget(getProject(), "BookingDto.nothing").startsWith("!"));
        assertTrue(FlowHuntPanel.resolveTarget(getProject(), "Nowhere.status").startsWith("!"));
        assertTrue(FlowHuntPanel.resolveTarget(getProject(), "  ").startsWith("!"));

        // what Watch a Field or Object suggests while typing: each class, and each of its fields (inherited ones too)
        java.util.List<String> suggested = FlowWatchDialog.targets(getProject());
        for (String s : new String[]{"BookingDto", "BookingDto.status", "BookingDto.createdBy", "Base.createdBy", "Line", "Line.amount"}) {
            assertTrue(s + " in " + suggested, suggested.contains(s));
        }
    }

    /** Watching an object's data: the classes of the objects inside it too (fields, lists), a few levels deep. */
    public void testAnObjectsDataIsInTheClassesInsideIt() {
        myFixture.addClass("""
                package com.travel.common;
                public class TravellerDto { private String name; private Address address; }
                """);
        myFixture.addClass("""
                package com.travel.common;
                public class Address { private String city; }
                """);
        myFixture.addClass("""
                package com.travel.common;
                public class PriceLineDto { private int amount; }
                """);
        myFixture.addClass("""
                package com.travel.common;
                import java.util.List;
                public class BookingDto {
                    private String status;
                    private TravellerDto traveller;
                    private List<PriceLineDto> priceLines;
                    private static BookingDto LAST;
                }
                """);
        myFixture.addClass("""
                package com.travel.api;
                public class BookingService { private com.travel.common.BookingDto last; private String name; }
                """);
        assertEquals(java.util.List.of("com.travel.common.BookingDto", "com.travel.common.TravellerDto",
                        "com.travel.common.PriceLineDto", "com.travel.common.Address"),
                com.example.flowtracker.debug.FlowValueHunt.objectClasses(getProject(), "com.travel.common.BookingDto", null));
        assertEquals("the object a field holds",
                java.util.List.of("com.travel.common.TravellerDto", "com.travel.common.Address"),
                com.example.flowtracker.debug.FlowValueHunt.objectClasses(getProject(), "com.travel.common.BookingDto", "traveller"));
        assertEquals("a String holds no object of the project's classes",
                java.util.List.of(), com.example.flowtracker.debug.FlowValueHunt.objectClasses(getProject(), "com.travel.api.BookingService", "name"));
    }

    /** A field inside a method's variable: ".email", a getter or setter (its property), deeper fields. */
    public void testAPathIntoAVariablesObjectIsItsFields() {
        myFixture.addClass("""
                package com.travel.common;
                public class Traveller { private String name; }
                """);
        myFixture.addClass("""
                package com.travel.common;
                public class BookingDto {
                    private String email;
                    private Traveller traveller;
                    public String getEmail() { return email; }
                    public void setEmail(String e) { email = e; }
                }
                """);
        myFixture.addClass("""
                package com.travel.web;
                import com.travel.common.BookingDto;
                public class BookingWorkflow {
                    public BookingDto book() {
                        BookingDto booking = new BookingDto();
                        return booking;
                    }
                }
                """);
        String base = "~com.travel.web.BookingWorkflow#book#booking#5-7";
        assertEquals(base + "#email", FlowHuntPanel.resolveTarget(getProject(), "BookingWorkflow.book().booking.email"));
        assertEquals(base + "#email", FlowHuntPanel.resolveTarget(getProject(), "BookingWorkflow.book().booking.getEmail()"));
        assertEquals(base + "#email", FlowHuntPanel.resolveTarget(getProject(), "BookingWorkflow.book().booking.setEmail()"));
        assertEquals(base + "#traveller.name", FlowHuntPanel.resolveTarget(getProject(), "BookingWorkflow.book().booking.traveller.name"));
        assertEquals("~com.travel.web.BookingWorkflow#book#return#4-7#email",
                FlowHuntPanel.resolveTarget(getProject(), "BookingWorkflow.book().return.getEmail()"));
        assertTrue(FlowHuntPanel.resolveTarget(getProject(), "BookingWorkflow.book().booking.phone").startsWith("!BookingDto has no field phone"));
        assertTrue(FlowHuntPanel.resolveTarget(getProject(), "BookingWorkflow.book().booking.email.size").startsWith("!"));

        com.example.flowtracker.model.FlowHuntStore.Hunt h = new com.example.flowtracker.model.FlowHuntStore.Hunt();
        h.target = base + "#traveller.name";
        assertEquals("BookingWorkflow.book().booking.traveller.name", h.targetText());
        assertEquals("active", FlowHuntPanel.property("isActive()"));
        assertEquals("URL", FlowHuntPanel.property("getURL()"));
    }

    /** Watch a method's variable: "BookingWorkflow.book().booking" is the variable and the lines it is known on. */
    public void testAMethodsVariableIsWatchedOnTheLinesItIsKnownOn() {
        myFixture.addClass("""
                package com.travel.web;
                public class BookingWorkflow {
                    public Object book(String tripId, int seats) {
                        Object request = tripId;
                        Object booking = request;
                        return booking;
                    }
                }
                """);
        // lines: 1 package, 2 class, 3 book(…) {, 4 request, 5 booking, 6 return, 7 }
        assertEquals("~com.travel.web.BookingWorkflow#book#booking#5-7",
                FlowHuntPanel.resolveTarget(getProject(), "BookingWorkflow.book().booking"));
        assertEquals("a parameter: the whole method", "com.travel.web.BookingWorkflow#book#seats#3-7",
                FlowHuntPanel.resolveTarget(getProject(), "BookingWorkflow.book().seats").replace("~", ""));
        assertTrue(FlowHuntPanel.resolveTarget(getProject(), "BookingWorkflow.book().nothing").startsWith("!"));
        assertTrue(FlowHuntPanel.resolveTarget(getProject(), "BookingWorkflow.pay().booking").startsWith("!"));

        // "Class.method()": its parameters, variables and what it returns, to pick one
        assertTrue(FlowHuntPanel.resolveTarget(getProject(), "BookingWorkflow.book()").startsWith("!Pick a variable"));
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String[] v : FlowHuntPanel.methodVariables(getProject(), "BookingWorkflow", "book")) names.add(v[0] + ":" + v[2]);
        assertEquals(java.util.List.of("tripId:parameter", "seats:parameter", "request:variable", "booking:variable", "return:return"), names);
        assertEquals("what it returns: the whole method", "~com.travel.web.BookingWorkflow#book#return#3-7",
                FlowHuntPanel.resolveTarget(getProject(), "BookingWorkflow.book().return"));
        assertTrue(FlowWatchDialog.targets(getProject()).contains("BookingWorkflow.book()"));

        com.example.flowtracker.model.FlowHuntStore.Hunt h = new com.example.flowtracker.model.FlowHuntStore.Hunt();
        h.target = "*~com.travel.web.BookingWorkflow#book#booking#5-7";
        assertTrue(h.isWatch());
        assertTrue(h.isLocal());
        assertTrue(h.isDeep());
        assertEquals("BookingWorkflow.book().booking", h.targetText());
    }
}
