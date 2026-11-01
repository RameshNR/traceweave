package com.example.flowtracker.debug;

import com.example.flowtracker.model.FlowValue;
import com.example.flowtracker.model.FlowValueText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FlowDataRecorderTest {

    private static FlowValue v(String name, String value, FlowValue... children) {
        FlowValue f = new FlowValue(name);
        f.value = value;
        f.children.addAll(List.of(children));
        return f;
    }

    private static FlowValue frame() {
        return v("Variables (now)", "",
                v("this", "BookingService@12", v("repository", "Repo@3"), v("rates", "size = 2")),
                v("booking", "Booking@40", v("id", "4")),
                v("total", "120.0"),
                v("nights", "3"));
    }

    private static List<String> names(List<FlowValue> list) {
        return list.stream().map(f -> f.name).toList();
    }

    @Test
    void savesEveryVariableOnItsOwn() {
        assertEquals(List.of("this", "booking", "total", "nights"), names(FlowDataRecorder.pick(frame(), null)));
    }

    @Test
    void onlyTheLinesVariablesAndFields() {
        assertEquals(List.of("total", "nights", "rates"),
                names(FlowDataRecorder.pick(frame(), "total = nights * rates.get(\"booking\"); // booking")));
        assertEquals(List.of("this", "booking"), names(FlowDataRecorder.pick(frame(), "this.save(booking);")));
    }

    @Test
    void signatureIgnoresInstanceIdsButSeesChanges() {
        FlowValue a = v("booking", "Booking@40", v("id", "4"));
        FlowValue b = v("booking", "Booking@41", v("id", "4"));
        FlowValue c = v("booking", "Booking@40", v("id", "5"));
        assertEquals(FlowDataRecorder.signature(a), FlowDataRecorder.signature(b));
        assertNotEquals(FlowDataRecorder.signature(a), FlowDataRecorder.signature(c));
    }

    @Test
    void valueJsonHasNoNameAround() {
        String json = FlowValueText.valueJson(v("booking", "Booking@40", v("id", "4"), v("guest", "\"Ann\"")), true);
        assertEquals("{\n  \"guest\": \"Ann\",\n  \"id\": 4\n}\n", json);
    }

    @Test
    void pastedJsonBecomesATreeThatComparesWithTheSavedOne() {
        FlowValue saved = v("hotelService", "HotelService@1141",
                v("nightlyRates", "size = 1", v("\"goa\" -> 2500.0", "")),
                v("rooms", "size = 2", v("[0]", "\"A\""), v("[1]", "\"B\"")));
        FlowValue a = FlowValueText.fromJson("value", FlowValueText.valueJson(saved, true));
        FlowValue b = FlowValueText.fromJson("value", "{\"rooms\": [\"A\", \"C\"], \"nightlyRates\": {\"goa\": 2600.0}}");
        org.junit.jupiter.api.Assertions.assertNotNull(a);
        org.junit.jupiter.api.Assertions.assertNotNull(b);
        assertEquals(List.of("nightlyRates", "rooms"), names(a.children));
        int differences = com.example.flowtracker.model.FlowValueDiff.differences(com.example.flowtracker.model.FlowValueDiff.compare(a, b));
        org.junit.jupiter.api.Assertions.assertTrue(differences >= 2, "goa's rate and the second room differ");
        org.junit.jupiter.api.Assertions.assertNull(FlowValueText.fromJson("value", "Booking{id=4}"));
    }
}
