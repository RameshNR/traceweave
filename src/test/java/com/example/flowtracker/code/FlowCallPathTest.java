package com.example.flowtracker.code;

import com.example.flowtracker.model.FlowEntry;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
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

    /**
     * All the ways from main() to getDestination(): through bookTrip() (the one that carries bookingService, first),
     * search() and cancelBooking() too, each once, with its own lines.
     */
    public void testAllTheWaysTheClosestFirst() {
        PsiClass main = myFixture.addClass(MAIN);
        PsiClass travel = myFixture.addClass(TRAVEL);
        myFixture.addClass(BOOKING);
        myFixture.addClass(DESTINATION);
        myFixture.addClass(HOTEL);

        FlowEntry start = line(main, "BookingService bookingService =");
        FlowEntry end = line(travel, "public String getDestination()");
        FlowCallPath.Ways w = FlowCallPath.ways(getProject(), start, end, List.of(start), FlowCallPath.MAX_WAYS,
                FlowCallPath.Progress.NONE);
        List<String> chains = w.ways().stream().map(FlowCallPath.Way::chain).toList();
        assertEquals(w.message(), "Main.main() → BookingService.bookTrip() → Travel.getDestination()", chains.get(0));
        assertTrue(chains.toString(), chains.contains("Main.main() → DestinationService.search() → Travel.getDestination()"));
        assertTrue(chains.toString(), chains.contains("Main.main() → BookingService.cancelBooking() → Travel.getDestination()"));
        assertEquals("every way once", chains.size(), new java.util.HashSet<>(chains).size());
        FlowCallPath.Way best = w.ways().get(0);
        assertEquals(List.of("bookingService"), best.carries());
        assertEquals(3, best.lines().size());
        assertEquals(2, best.steps().get(1).lines().size()); // bookTrip(): lines 33 and 41
        assertTrue(w.message(), w.message().startsWith("Found " + chains.size() + " ways"));
        // the single way the Flow tab takes is the first one
        assertEquals(best.lines().stream().map(x -> x.fileName + ":" + x.line).toList(),
                FlowCallPath.betweenMethods(getProject(), List.of(start), end).lines().stream().map(x -> x.fileName + ":" + x.line).toList());
    }

    /** A call through an interface reaches its implementation (e.g. a Spring bean injected as its interface). */
    public void testCallsThroughAnInterfaceReachTheImplementation() {
        PsiClass checkout = myFixture.addClass("""
                package com.shop;
                public class Checkout {
                    private final PaymentGateway gateway;
                    public Checkout(PaymentGateway gateway) { this.gateway = gateway; }
                    public void pay(int amount) {
                        gateway.charge(amount);
                    }
                }
                """);
        myFixture.addClass("""
                package com.shop;
                public interface PaymentGateway { void charge(int amount); }
                """);
        myFixture.addClass("""
                package com.shop;
                public class CardGateway implements PaymentGateway {
                    @Override public void charge(int amount) {
                        Ledger.record(amount);
                    }
                }
                """);
        PsiClass ledger = myFixture.addClass("""
                package com.shop;
                public class Ledger {
                    public static void record(int amount) {
                        System.out.println(amount);
                    }
                }
                """);
        FlowCallPath.Ways w = FlowCallPath.ways(getProject(), line(checkout, "gateway.charge(amount);"),
                line(ledger, "System.out.println(amount);"), null, 4, FlowCallPath.Progress.NONE);
        assertFalse(w.message(), w.ways().isEmpty());
        FlowCallPath.Way way = w.ways().get(0);
        assertEquals("Checkout.pay() → CardGateway.charge() → Ledger.record()", way.chain());
        assertEquals("implements PaymentGateway.charge()", way.steps().get(1).via());
        assertEquals(List.of("CardGateway.java:Ledger.record(amount);"),
                way.lines().stream().map(e -> e.fileName + ":" + e.text.strip()).toList());
    }

    /** A method reference (ids.forEach(this::print)) is followed like a call. */
    public void testMethodReferencesAreFollowed() {
        PsiClass report = myFixture.addClass("""
                package com.shop;
                import java.util.List;
                public class Report {
                    public void run(List<Integer> ids) {
                        ids.forEach(this::print);
                    }
                    void print(Integer id) {
                        Printer.out(id);
                    }
                }
                """);
        PsiClass printer = myFixture.addClass("""
                package com.shop;
                public class Printer {
                    public static void out(Object o) {
                        System.out.println(o);
                    }
                }
                """);
        FlowCallPath.Ways w = FlowCallPath.ways(getProject(), line(report, "ids.forEach(this::print);"),
                line(printer, "System.out.println(o);"), null, 4, FlowCallPath.Progress.NONE);
        assertFalse(w.message(), w.ways().isEmpty());
        FlowCallPath.Way way = w.ways().get(0);
        assertEquals("Report.run() → Report.print() → Printer.out()", way.chain());
        assertEquals("method reference", way.steps().get(1).via());
    }

    /** Spring: publishEvent(new BookingCreated()) reaches the @EventListener method of BookingCreated. */
    public void testSpringEventsReachTheirListeners() {
        myFixture.addClass("""
                package org.springframework.context.event;
                public @interface EventListener {}
                """);
        myFixture.addClass("""
                package org.springframework.context;
                public interface ApplicationEventPublisher { void publishEvent(Object event); }
                """);
        myFixture.addClass("""
                package com.shop;
                public class BookingCreated {}
                """);
        PsiClass service = myFixture.addClass("""
                package com.shop;
                import org.springframework.context.ApplicationEventPublisher;
                public class BookingFlow {
                    private final ApplicationEventPublisher events;
                    public BookingFlow(ApplicationEventPublisher events) { this.events = events; }
                    public void book() {
                        events.publishEvent(new BookingCreated());
                    }
                }
                """);
        myFixture.addClass("""
                package com.shop;
                import org.springframework.context.event.EventListener;
                public class Mailer {
                    @EventListener
                    public void onBooking(BookingCreated event) {
                        Mail.send(event);
                    }
                    @EventListener
                    public void onOther(String other) {}
                }
                """);
        PsiClass mail = myFixture.addClass("""
                package com.shop;
                public class Mail {
                    public static void send(Object what) {
                        System.out.println(what);
                    }
                }
                """);
        FlowCallPath.Ways w = FlowCallPath.ways(getProject(), line(service, "events.publishEvent(new BookingCreated());"),
                line(mail, "System.out.println(what);"), null, 4, FlowCallPath.Progress.NONE);
        assertFalse(w.message(), w.ways().isEmpty());
        FlowCallPath.Way way = w.ways().get(0);
        assertEquals("BookingFlow.book() → Mailer.onBooking() → Mail.send()", way.chain());
        assertEquals("Spring event BookingCreated", way.steps().get(1).via());
    }

    /** Kotlin: the calls of Kotlin functions are followed too (read through UAST). */
    public void testKotlinCallsAreFollowed() {
        PsiFile file = myFixture.addFileToProject("com/trip/Trips.kt", """
                package com.trip

                class Planner(private val hotels: Hotels) {
                    fun plan(city: String): Double {
                        val nights = 2
                        return hotels.price(city, nights)
                    }
                }

                class Hotels {
                    fun price(city: String, nights: Int): Double {
                        return Rates.rate(city) * nights
                    }
                }

                object Rates {
                    fun rate(city: String): Double {
                        return 100.0
                    }
                }
                """);
        Document doc = FileDocumentManager.getInstance().getDocument(file.getVirtualFile());
        assertNotNull(doc);
        FlowEntry from = FlowCapture.lineEntry(getProject(), doc, doc.getLineNumber(doc.getText().indexOf("val nights = 2")));
        FlowEntry to = FlowCapture.lineEntry(getProject(), doc, doc.getLineNumber(doc.getText().indexOf("return 100.0")));
        FlowCallPath.Ways w = FlowCallPath.ways(getProject(), from, to, null, 4, FlowCallPath.Progress.NONE);
        assertFalse(w.message(), w.ways().isEmpty());
        FlowCallPath.Way way = w.ways().get(0);
        assertEquals("Planner.plan() → Hotels.price() → Rates.rate()", way.chain());
        assertEquals(List.of("Trips.kt:return hotels.price(city, nights)", "Trips.kt:return Rates.rate(city) * nights"),
                way.lines().stream().map(e -> e.fileName + ":" + e.text.strip()).toList());
    }

    /** A task given to a thread: its run() is followed. */
    public void testTasksRunInAThreadAreFollowed() {
        PsiClass job = myFixture.addClass("""
                package com.shop;
                public class Nightly {
                    public void start() {
                        Thread t = new Thread(new ReportTask());
                        t.start();
                    }
                }
                """);
        myFixture.addClass("""
                package com.shop;
                public class ReportTask implements Runnable {
                    @Override public void run() {
                        Reports.build();
                    }
                }
                """);
        PsiClass reports = myFixture.addClass("""
                package com.shop;
                public class Reports {
                    public static void build() {
                        System.out.println("built");
                    }
                }
                """);
        FlowCallPath.Ways w = FlowCallPath.ways(getProject(), line(job, "Thread t = new Thread(new ReportTask());"),
                line(reports, "System.out.println(\"built\");"), null, 4, FlowCallPath.Progress.NONE);
        assertFalse(w.message(), w.ways().isEmpty());
        assertEquals("Nightly.start() → ReportTask.run() → Reports.build()", w.ways().get(0).chain());
        assertEquals("runs in a thread", w.ways().get(0).steps().get(1).via());
    }

    /** An HTTP call to a path reaches the Spring method that handles it. */
    public void testApiCallsReachTheirHandlers() {
        myFixture.addClass("""
                package org.springframework.web.bind.annotation;
                public @interface GetMapping { String[] value() default {}; }
                """);
        myFixture.addClass("""
                package org.springframework.web.bind.annotation;
                public @interface RequestMapping { String[] value() default {}; }
                """);
        PsiClass client = myFixture.addClass("""
                package com.web;
                public class ReportClient {
                    private static final String BASE = "http://localhost:8081";
                    public String report(String city) {
                        String url = BASE + "/api/travel-report/" + city + "?days=3";
                        return Http.get(url);
                    }
                }
                """);
        myFixture.addClass("""
                package com.web;
                public class Http { public static String get(String url) { return ""; } }
                """);
        PsiClass controller = myFixture.addClass("""
                package com.web;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                @RequestMapping("/api")
                public class ReportController {
                    @GetMapping("/travel-report/{city}")
                    public String report(String city) {
                        return city.toUpperCase();
                    }
                    @GetMapping("/other")
                    public String other() { return ""; }
                }
                """);
        FlowCallPath.Ways w = FlowCallPath.ways(getProject(), line(client, "String url = BASE"),
                line(controller, "return city.toUpperCase();"), null, 4, FlowCallPath.Progress.NONE);
        assertFalse(w.message(), w.ways().isEmpty());
        assertEquals("ReportClient.report() → ReportController.report()", w.ways().get(0).chain());
        assertEquals("API call GET /api/travel-report/{city}", w.ways().get(0).steps().get(1).via());
    }

    /**
     * The travel sample: TravelApiService.getBooking() gives a lambda to ApiThreadManager.execute(), which runs it in a
     * worker thread; the lambda calls the API at "/api/bookings/" + reference, handled by BookingController.find().
     */
    public void testAnApiCallMadeInATaskForAThreadReachesItsHandler() {
        myFixture.addClass("""
                package org.springframework.web.bind.annotation;
                public @interface GetMapping { String[] value() default {}; }
                """);
        myFixture.addClass("""
                package org.springframework.web.bind.annotation;
                public @interface PostMapping { String[] value() default {}; }
                """);
        myFixture.addClass("""
                package org.springframework.web.bind.annotation;
                public @interface RequestMapping { String[] value() default {}; }
                """);
        PsiClass manager = myFixture.addClass("""
                package com.travel.web;
                import java.util.concurrent.*;
                public class ApiThreadManager {
                    private final ExecutorService executor = Executors.newFixedThreadPool(4);
                    public <T> T execute(Callable<T> apiTask) {
                        Future<T> future = executor.submit(apiTask);
                        try {
                            return future.get(12, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
                """);
        PsiClass service = myFixture.addClass("""
                package com.travel.web;
                public class TravelApiService {
                    private final ApiThreadManager threadManager = new ApiThreadManager();
                    public Object getBooking(String reference) {
                        return threadManager.execute(() -> get(
                                "/api/bookings/" + encode(reference)));
                    }
                    public Object createBooking(Object booking) {
                        return threadManager.execute(() -> post("/api/bookings", booking));
                    }
                    private Object get(String path) throws Exception { return send(path); }
                    private Object post(String path, Object body) throws Exception { return send(path); }
                    private Object send(String path) throws Exception { return path; }
                    private String encode(String v) { return v; }
                }
                """);
        PsiClass controller = myFixture.addClass("""
                package com.travel.api;
                import org.springframework.web.bind.annotation.*;
                @RequestMapping("/api")
                public class BookingController {
                    @PostMapping("/bookings")
                    public Object create(Object request) {
                        return "created";
                    }
                    @GetMapping("/bookings/{reference}")
                    public Object find(String reference) {
                        return reference.trim();
                    }
                    @PostMapping("/bookings/{reference}/cancel")
                    public Object cancel(String reference) {
                        return "cancelled";
                    }
                }
                """);
        FlowCallPath.Ways w = FlowCallPath.ways(getProject(), line(service, "return threadManager.execute(() -> get("),
                line(controller, "return reference.trim();"), null, 4, FlowCallPath.Progress.NONE);
        assertFalse(w.message(), w.ways().isEmpty());
        assertEquals("TravelApiService.getBooking() → BookingController.find()", w.ways().get(0).chain());
        assertEquals("API call GET /api/bookings/{reference}, in the task given to ApiThreadManager.execute()",
                w.ways().get(0).steps().get(1).via());
        assertNotNull(manager);
    }

    /** HttpServer.createContext("/path", new Handler()): a call to that path reaches Handler.handle(). */
    public void testHttpServerContextsAreHandlers() {
        myFixture.addClass("""
                package com.sun.net.httpserver;
                public abstract class HttpServer { public abstract Object createContext(String path, Object handler); }
                """);
        myFixture.addClass("""
                package com.reports;
                public class Main {
                    public static void main(com.sun.net.httpserver.HttpServer server) {
                        server.createContext("/api/travel-report", new ReportHandler());
                    }
                }
                """);
        PsiClass handler = myFixture.addClass("""
                package com.reports;
                public class ReportHandler {
                    public void handle(Object exchange) {
                        System.out.println(exchange);
                    }
                }
                """);
        PsiClass page = myFixture.addClass("""
                package com.web;
                public class Search {
                    public void run(String query) {
                        String apiUrl = "http://localhost:8081/api/travel-report?" + query;
                        fetch(apiUrl);
                    }
                    void fetch(String url) {}
                }
                """);
        FlowCallPath.Ways w = FlowCallPath.ways(getProject(), line(page, "String apiUrl"),
                line(handler, "System.out.println(exchange);"), null, 4, FlowCallPath.Progress.NONE);
        assertFalse(w.message(), w.ways().isEmpty());
        assertEquals("Search.run() → ReportHandler.handle()", w.ways().get(0).chain());
    }

    /** Cancel stops the search; progress is told. */
    public void testTheSearchCanBeStopped() {
        PsiClass main = myFixture.addClass(MAIN);
        PsiClass travel = myFixture.addClass(TRAVEL);
        myFixture.addClass(BOOKING);
        myFixture.addClass(DESTINATION);
        myFixture.addClass(HOTEL);
        FlowEntry start = line(main, "BookingService bookingService =");
        FlowCallPath.Ways w = FlowCallPath.ways(getProject(), start, line(travel, "public String getDestination()"),
                List.of(start), FlowCallPath.MAX_WAYS, new FlowCallPath.Progress() {
                    @Override public boolean cancelled() { return true; }
                    @Override public void read(int methods, int ways) {}
                });
        assertTrue(w.ways().isEmpty());
        assertEquals("Stopped before a way was found.", w.message());
    }
}
