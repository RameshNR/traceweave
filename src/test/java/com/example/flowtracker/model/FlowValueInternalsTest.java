package com.example.flowtracker.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowValueInternalsTest {

    private static FlowValue field(String name, String value) {
        FlowValue v = new FlowValue(name);
        v.value = value;
        return v;
    }

    @Test
    void aStringSavedWithItsBytesShowsItsText() {
        FlowValue dto = new FlowValue("this");
        dto.type = "BookingDto";
        FlowValue ref = new FlowValue("reference"); // no type: saved as JSON
        ref.value = "size = 4";
        FlowValue bytes = new FlowValue("value");
        for (int b : new int[]{66, 75, 45, 49, 48, 52, 52}) bytes.children.add(field("[" + bytes.children.size() + "]", String.valueOf(b)));
        ref.children.add(bytes);
        ref.children.add(field("coder", "0"));
        ref.children.add(field("hash", "0"));
        ref.children.add(field("hashIsZero", "false"));
        dto.children.add(ref);
        dto.children.add(field("status", "\"CONFIRMED\""));

        assertTrue(FlowValueShape.hasInternals(dto));
        FlowValue shown = FlowValueShape.withoutInternals(dto);
        FlowValue r = shown.children.get(0);
        assertEquals("\"BK-1044\"", r.value);
        assertEquals("String", r.type);
        assertTrue(r.children.isEmpty());
        assertEquals(2, shown.children.size());
        assertEquals(4, ref.children.size(), "the saved value isn't changed");
    }

    @Test
    void bytesSavedAsTextAndTextShownAlreadyBothWork() {
        FlowValue name = new FlowValue("name");
        name.children.add(field("value", "[67, 79, 78]"));
        name.children.add(field("coder", "0"));
        assertEquals("\"CON\"", FlowValueShape.withoutInternals(name).value);

        FlowValue shown = new FlowValue("reference");
        shown.value = "\"BK-1044\"";
        shown.children.add(field("value", "[1, 2]"));
        shown.children.add(field("coder", "0"));
        assertEquals("\"BK-1044\"", FlowValueShape.withoutInternals(shown).value);
    }

    @Test
    void aStopsJsonShowsStringsAsText() {
        String json = "{\"this\": {\"reference\": {\"value\": [66, 75, 45, 49], \"coder\": 0, \"hash\": 0, \"hashIsZero\": false},"
                + " \"status\": {\"name\": {\"value\": \"[79, 75]\", \"coder\": 0, \"hash\": 5}, \"ordinal\": 1}, \"seats\": 2}}";
        String clean = FlowValueShape.withoutInternalsJson(json);
        com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(clean).getAsJsonObject().getAsJsonObject("this");
        assertEquals("BK-1", o.get("reference").getAsString());
        assertEquals("OK", o.get("status").getAsString(), "an enum is its name");
        assertEquals(2, o.get("seats").getAsInt());
        String plain = "{\"a\": 1}";
        assertEquals(plain, FlowValueShape.withoutInternalsJson(plain), "nothing to take out: the JSON as it was");
        assertEquals("not json", FlowValueShape.withoutInternalsJson("not json"));
    }

    @Test
    void otherLanguagesInternalsGoInJson() {
        String json = "{"
                + "\"this$0\": {\"x\": 1}, \"name$delegate\": {\"_value\": \"Asha\", \"initializer\": null},"     // Kotlin / Java synthetic
                + "\"lazyName\": {\"initializer\": null, \"_value\": \"Asha\", \"lock\": null},"                // Kotlin lazy
                + "\"bitmap$0\": true, \"metaClass\": {}, \"modCount\": 3,"                                       // Scala, Groovy, Java
                + "\"status\": {\"hash\": 0, \"name\": \"CONFIRMED\", \"ordinal\": 1},"                                         // enum
                + "\"total\": {\"intVal\": null, \"scale\": 2, \"precision\": 0, \"stringCache\": null, \"intCompact\": 12550},"
                + "\"lines\": {\"elementData\": [\"a\", \"b\", null, null], \"size\": 2, \"modCount\": 2},"       // ArrayList
                + "\"$id\": \"1\", \"$type\": \"Booking, App\","                                                   // .NET
                + "\"seats\": {\"$id\": \"2\", \"$values\": [1, 2]},"
                + "\"<Email>k__BackingField\": \"a@b.c\","
                + "\"__class__\": \"Booking\", \"py/object\": \"app.Booking\", \"__proto__\": {}, \"__typename\": \"Booking\""
                + "}";
        com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(FlowValueShape.withoutInternalsJson(json)).getAsJsonObject();
        assertEquals(java.util.Set.of("lazyName", "status", "total", "lines", "seats", "Email"), o.keySet());
        assertEquals("Asha", o.get("lazyName").getAsString());
        assertEquals("CONFIRMED", o.get("status").getAsString());
        assertEquals("125.50", o.get("total").getAsBigDecimal().toPlainString());
        assertEquals("[\"a\",\"b\"]", o.get("lines").toString());
        assertEquals("[1,2]", o.get("seats").toString());
        assertEquals("a@b.c", o.get("Email").getAsString());
    }

    @Test
    void otherLanguagesInternalsGoInATree() {
        FlowValue v = FlowValueText.fromJson("value",
                "{\"this$0\": 1, \"status\": {\"name\": \"PAID\", \"ordinal\": 2}, \"<Total>k__BackingField\": 5, \"city\": \"Pune\"}");
        assertTrue(FlowValueShape.hasInternals(v));
        FlowValue c = FlowValueShape.withoutInternals(v);
        assertEquals(3, c.children.size());
        assertEquals("status", c.children.get(0).name);
        assertEquals("\"PAID\"", c.children.get(0).value);
        assertEquals("Total", c.children.get(1).name);
        assertEquals("city", c.children.get(2).name);
        FlowValue plain = FlowValueText.fromJson("value", "{\"city\": \"Pune\", \"items\": [1, 2]}");
        assertTrue(!FlowValueShape.hasInternals(plain));
        assertTrue(FlowValueShape.withoutInternals(plain) == plain, "nothing to leave out: the value itself");
    }

    @Test
    void anObjectWithAValueFieldIsLeftAlone() {
        FlowValue o = new FlowValue("price");
        o.type = "Money";
        o.children.add(field("value", "12"));
        o.children.add(field("currency", "\"EUR\""));
        assertTrue(!FlowValueShape.hasInternals(o));
    }
}
