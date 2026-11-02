package com.example.flowtracker.code;

import com.example.flowtracker.model.FlowEntry;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.psi.PsiClass;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.List;

/**
 * Connect: from a line of main() to Travel.getDestination(), the way goes through what the first line makes
 * (hotelService → bookingService → bookTrip(), where hotelService is used with travel.getDestination()), with the
 * lines of each method that make the calls. Real Java code, read by the IDE's own parser.
 */
public class FlowCallPathTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String MAIN = """
            package com.travel;

            import java.util.List;

            public class Main {

                public static void main(String[] args) {
                    DestinationService destinationService = new DestinationService();
                    HotelService hotelService = new HotelService();
                    BookingService bookingService = new BookingService(destinationService, hotelService);

                    System.out.println("===== AVAILABLE TRAVEL PACKAGES =====");
                    List<Travel> packages = destinationService.search("", 15000, 4);
                    packages.forEach(System.out::println);

                    System.out.println("\\n===== BOOKING A TRIP =====");
                    try {
                        BookingService.Booking booking =
                                bookingService.bookTrip(101, "Ramesh", 2, 2, 1);
                        System.out.println(booking);

                        System.out.println("\\n===== ALL BOOKINGS =====");
                        bookingService.getBookings().values().forEach(System.out::println);

                        System.out.println("\\n===== CANCELLATION =====");
                        boolean cancelled = bookingService.cancelBooking(booking.getBookingId());
                        System.out.println("Cancellation successful: " + cancelled);
                        System.out.println(booking);
                    } catch (IllegalArgumentException | IllegalStateException exception) {
                        System.err.println("Operation failed: " + exception.getMessage());
                    }
                }
            }
            """;

    private static final String BOOKING = """
            package com.travel;

            import java.util.LinkedHashMap;
            import java.util.Map;

            public class BookingService {

                private final DestinationService destinationService;
                private final HotelService hotelService;
                private final Map<Integer, Booking> bookings = new LinkedHashMap<>();
                private int bookingSequence = 1000;

                public BookingService(DestinationService destinationService, HotelService hotelService) {
                    this.destinationService = destinationService;
                    this.hotelService = hotelService;
                }

                public Booking bookTrip(int travelId, String customerName,
                                        int travelers, int nights, int rooms) {
                    if (customerName == null || customerName.isBlank()
                            || travelers <= 0 || nights <= 0 || rooms <= 0 || rooms > travelers) {
                        throw new IllegalArgumentException("Invalid booking details");
                    }

                    Travel travel = destinationService.findById(travelId);
                    if (nights >= travel.getDurationDays()) {
                        throw new IllegalArgumentException(
                                "Hotel nights must be fewer than package duration");
                    }

                    double hotelCost = hotelService.calculateCost(
                            travel.getDestination(), nights, rooms);

                    if (travel.getAvailableSeats() < travelers) {
                        throw new IllegalStateException("Not enough seats available");
                    }
                    if (!travel.reserveSeats(travelers)) {
                        throw new IllegalStateException("Unable to reserve travel seats");
                    }
                    if (!hotelService.reserveRooms(travel.getDestination(), rooms)) {
                        travel.releaseSeats(travelers);
                        throw new IllegalStateException("Hotel rooms are no longer available");
                    }

                    double totalCost = travel.getPackagePrice() * travelers + hotelCost;
                    int bookingId = ++bookingSequence;
                    Booking booking = new Booking(bookingId, travel);
                    bookings.put(bookingId, booking);
                    return booking;
                }

                public boolean cancelBooking(int bookingId) {
                    Booking booking = bookings.get(bookingId);
                    if (booking == null) {
                        return false;
                    }
                    booking.getTravel().releaseSeats(1);
                    hotelService.releaseRooms(booking.getTravel().getDestination(), 1);
                    return true;
                }

                public Map<Integer, Booking> getBookings() {
                    return bookings;
                }

                public static class Booking {
                    private final int bookingId;
                    private final Travel travel;

                    public Booking(int bookingId, Travel travel) {
                        this.bookingId = bookingId;
                        this.travel = travel;
                    }

                    public int getBookingId() { return bookingId; }
                    public Travel getTravel() { return travel; }
                }
            }
            """;

    private static final String TRAVEL = """
            package com.travel;

            public class Travel {

                private final int id;
                private final String destination;
                private final double packagePrice;
                private final int durationDays;
                private int availableSeats;

                public Travel(int id, String destination, double packagePrice,
                              int durationDays, int availableSeats) {
                    this.id = id;
                    this.destination = destination;
                    this.packagePrice = packagePrice;
                    this.durationDays = durationDays;
                    this.availableSeats = availableSeats;
                }

                public boolean reserveSeats(int seats) {
                    boolean reserved = seats > 0 && seats <= availableSeats;
                    if (reserved) {
                        availableSeats -= seats;
                    }
                    return reserved;
                }

                public void releaseSeats(int seats) {
                    availableSeats += seats;
                }

                public int getId() { return id; }
                public String getDestination() { return destination; }
                public double getPackagePrice() { return packagePrice; }
                public int getDurationDays() { return durationDays; }
                public int getAvailableSeats() { return availableSeats; }
            }
            """;

    private static final String DESTINATION = """
            package com.travel;

            import java.util.ArrayList;
            import java.util.List;

            public class DestinationService {
                private final List<Travel> packages = new ArrayList<>();

                public List<Travel> search(String destination, double maxBudget, int maxDays) {
                    List<Travel> out = new ArrayList<>();
                    for (Travel t : packages) {
                        if (t.getDestination().contains(destination) && t.getPackagePrice() <= maxBudget) out.add(t);
                    }
                    return out;
                }

                public Travel findById(int id) {
                    for (Travel t : packages) if (t.getId() == id) return t;
                    throw new IllegalArgumentException("No travel " + id);
                }
            }
            """;

    private static final String HOTEL = """
            package com.travel;

            public class HotelService {
                public double calculateCost(String destination, int nights, int rooms) { return nights * rooms * 100.0; }
                public boolean reserveRooms(String destination, int rooms) { return true; }
                public void releaseRooms(String destination, int rooms) {}
            }
            """;

    /** A flow line of a class's file: the line (1-based) that holds the text. */
    private FlowEntry line(PsiClass cls, String text) {
        Document doc = FileDocumentManager.getInstance().getDocument(cls.getContainingFile().getVirtualFile());
        assertNotNull(doc);
        int at = doc.getText().indexOf(text);
        assertTrue("no line with " + text, at >= 0);
        int line = doc.getLineNumber(at);
        FlowEntry e = FlowCapture.lineEntry(getProject(), doc, line);
        assertNotNull(e);
        return e;
    }

    /**
     * The diagram's Connect the Flow to…: main() (whose flow line is a later one, line 23) to getDestination(): the
     * first call of main() that leads there (bookTrip, line 19), then both lines of bookTrip() that call it.
     */
    public void testMethodToMethodTakesTheFirstCallThatLeadsThere() {
        PsiClass main = myFixture.addClass(MAIN);
        PsiClass travel = myFixture.addClass(TRAVEL);
        myFixture.addClass(BOOKING);
        myFixture.addClass(DESTINATION);
        myFixture.addClass(HOTEL);

        FlowEntry start = line(main, "bookingService.getBookings()");
        FlowEntry end = line(travel, "public String getDestination()");
        com.example.flowtracker.model.FlowStore.get(getProject()).add(start);
        com.example.flowtracker.model.FlowStore.get(getProject()).add(end);
        FlowCallPath.Result r = FlowCallPath.betweenMethods(getProject(), List.of(start), end);
        assertNotNull(r.message(), r.lines());
        assertEquals(3, r.lines().size());
        // the same with the flow line made with the service (line 10): bookTrip, not search(), which also gets there
        r = FlowCallPath.betweenMethods(getProject(), List.of(line(main, "BookingService bookingService =")), end);
        assertNotNull(r.message(), r.lines());
        List<String> got = r.lines().stream().map(e -> e.fileName + ":" + e.text.strip()).toList();
        assertEquals(r.message(), List.of(
                "Main.java:bookingService.bookTrip(101, \"Ramesh\", 2, 2, 1);",
                "BookingService.java:travel.getDestination(), nights, rooms);",
                "BookingService.java:if (!hotelService.reserveRooms(travel.getDestination(), rooms)) {"), got);
    }

    /** From the bookTrip(…) line (its second line, as saved in the flow): that call is the way, not a later one. */
    public void testTheWayGoesThroughTheStartLinesOwnCall() {
        PsiClass main = myFixture.addClass(MAIN);
        PsiClass travel = myFixture.addClass(TRAVEL);
        myFixture.addClass(BOOKING);
        myFixture.addClass(DESTINATION);
        myFixture.addClass(HOTEL);

        FlowEntry start = line(main, "bookingService.bookTrip(101");
        FlowEntry end = line(travel, "public String getDestination()");
        com.example.flowtracker.model.FlowStore.get(getProject()).add(start);
        com.example.flowtracker.model.FlowStore.get(getProject()).add(end);
        FlowCallPath.Result r = FlowCallPath.between(getProject(), start, end);
        assertNotNull(r.message(), r.lines());
        List<String> got = r.lines().stream().map(e -> e.fileName + ":" + e.text.strip()).toList();
        assertEquals(r.message(), List.of(
                "BookingService.java:travel.getDestination(), nights, rooms);",
                "BookingService.java:if (!hotelService.reserveRooms(travel.getDestination(), rooms)) {"), got);
    }

    public void testTheWayFollowsWhatTheFirstLineMakes() {
        PsiClass main = myFixture.addClass(MAIN);
        PsiClass booking = myFixture.addClass(BOOKING);
        PsiClass travel = myFixture.addClass(TRAVEL);
        myFixture.addClass(DESTINATION);
        myFixture.addClass(HOTEL);

        FlowEntry start = line(main, "HotelService hotelService = new HotelService();");
        FlowEntry end = line(travel, "public String getDestination()");
        // both ends are lines of the flow already (as when connecting two boxes of a diagram)
        com.example.flowtracker.model.FlowStore.get(getProject()).add(start);
        com.example.flowtracker.model.FlowStore.get(getProject()).add(end);
        com.example.flowtracker.model.FlowStore.get(getProject()).add(line(booking, "double hotelCost"));
        FlowCallPath.Result r = FlowCallPath.between(getProject(), start, end);
        assertNotNull(r.message(), r.lines());
        List<String> got = r.lines().stream().map(e -> e.fileName + ":" + e.text.strip()).toList();
        assertEquals(r.message(), List.of(
                "Main.java:bookingService.bookTrip(101, \"Ramesh\", 2, 2, 1);",
                "BookingService.java:travel.getDestination(), nights, rooms);",
                "BookingService.java:if (!hotelService.reserveRooms(travel.getDestination(), rooms)) {"), got);
        assertEquals(line(main, "bookingService.bookTrip(101").line, r.lines().get(0).line);
        assertEquals(line(booking, "travel.getDestination(), nights").line, r.lines().get(1).line);
    }
}
