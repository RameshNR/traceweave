package com.example.flowtracker.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sort and Filter (how a value is shown) and the blocks of its JSON / text that fold. */
class FlowValueShapeTest {

    private static FlowValue v(String name, String value, FlowValue... children) {
        FlowValue x = new FlowValue(name);
        x.value = value;
        x.children.addAll(List.of(children));
        return x;
    }

    /** hotelService › nightlyRates (a map), rooms (a list). */
    private static FlowValue hotel() {
        return v("hotelService", "{HotelService@1141}",
                v("nightlyRates", "size = 3", v("\"ooty\"", "1800.0"), v("\"goa\"", "2500.0"), v("\"kerala\"", "2800.0")),
                v("rooms", "size = 3", v("0", "12"), v("1", "7"), v("2", "10")),
                v("city", "\"Goa\""));
    }

    private static List<String> names(FlowValue v) {
        return v.children.stream().map(c -> c.name).toList();
    }

    @Test
    void sortingByNameKeepsListsInTheirOrder() {
        FlowValue sorted = FlowValueShape.apply(hotel(), FlowValueShape.Sort.A_Z, "");
        assertEquals(List.of("city", "nightlyRates", "rooms"), names(sorted));
        assertEquals(List.of("\"goa\"", "\"kerala\"", "\"ooty\""), names(sorted.children.get(1)), "map keys sorted (quotes ignored)");
        assertEquals(List.of("0", "1", "2"), names(sorted.children.get(2)), "a list keeps its order");
        FlowValue reversed = FlowValueShape.apply(hotel(), FlowValueShape.Sort.Z_A, "");
        assertEquals(List.of("rooms", "nightlyRates", "city"), names(reversed));
        assertEquals(List.of("nightlyRates", "rooms", "city"), names(hotel()), "the value itself is never changed");
    }

    @Test
    void filteringKeepsTheMatchesAndWhereTheyAre() {
        FlowValue goa = FlowValueShape.apply(hotel(), FlowValueShape.Sort.AS_SAVED, "GOA");
        assertEquals(List.of("nightlyRates", "city"), names(goa), "the fields that hold a match, in their order");
        assertEquals(List.of("\"goa\""), names(goa.children.get(0)));
        FlowValue rates = FlowValueShape.apply(hotel(), FlowValueShape.Sort.AS_SAVED, "nightly");
        assertEquals(3, rates.children.get(0).children.size(), "a matching field keeps everything in it");
        assertTrue(FlowValueShape.apply(hotel(), FlowValueShape.Sort.AS_SAVED, "nothing like it").children.isEmpty());
        assertTrue(FlowValueShape.plain(FlowValueShape.Sort.AS_SAVED, "  "));
    }

    @Test
    void jsonBlocksFold() {
        String json = "{\n  \"a\": {\n    \"b\": \"{not a block}\",\n    \"c\": [\n      1,\n      2\n    ]\n  },\n  \"d\": [1, 2]\n}\n";
        List<FlowFolds.Block> blocks = FlowFolds.of(json, true);
        assertEquals(List.of("{…}", "{…}", "[…]"), blocks.stream().map(FlowFolds.Block::placeholder).toList(),
                "multi-line blocks only; braces in strings and one-line arrays don't count");
        assertEquals(List.of(0, 1, 2), blocks.stream().map(FlowFolds.Block::depth).toList());
        assertEquals('{', json.charAt(blocks.get(1).start()));
        assertEquals('}', json.charAt(blocks.get(1).end() - 1));
    }

    @Test
    void indentedTextBlocksFold() {
        String text = "hotelService = {HotelService}\n    rooms = size = 2\n        0 = 12\n        1 = 7\n    city = \"Goa\"\n";
        List<FlowFolds.Block> blocks = FlowFolds.of(text, false);
        assertEquals(2, blocks.size());
        assertEquals("hotelService = {HotelService}", text.substring(0, blocks.get(0).start()), "the line stays, what is under it folds");
        assertEquals(1, blocks.get(1).depth());
        assertTrue(text.substring(blocks.get(1).start(), blocks.get(1).end()).endsWith("1 = 7"));
    }
}
