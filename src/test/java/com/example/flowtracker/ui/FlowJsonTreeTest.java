package com.example.flowtracker.ui;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.swing.tree.DefaultMutableTreeNode;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A stop's variables as a tree: objects and lists open like folders, each field with its value. */
class FlowJsonTreeTest {

    private static FlowJsonTree.Row row(DefaultMutableTreeNode n) {
        return (FlowJsonTree.Row) n.getUserObject();
    }

    @Test
    void jsonBecomesRowsWithTheirValues() {
        DefaultMutableTreeNode booking = FlowJsonTree.node("booking", JsonParser.parseString(
                "{\"reference\": \"BK-1042\", \"seats\": 2, \"paid\": false, \"promo\": null, \"lines\": [{\"amount\": 10}, {\"amount\": 5}]}"));
        assertEquals(new FlowJsonTree.Row("booking", "{5 fields}", FlowJsonTree.Kind.OBJECT), row(booking));
        assertEquals(new FlowJsonTree.Row("reference", "\"BK-1042\"", FlowJsonTree.Kind.STRING), row((DefaultMutableTreeNode) booking.getChildAt(0)));
        assertEquals(new FlowJsonTree.Row("seats", "2", FlowJsonTree.Kind.NUMBER), row((DefaultMutableTreeNode) booking.getChildAt(1)));
        assertEquals(new FlowJsonTree.Row("paid", "false", FlowJsonTree.Kind.BOOLEAN), row((DefaultMutableTreeNode) booking.getChildAt(2)));
        assertEquals(new FlowJsonTree.Row("promo", "null", FlowJsonTree.Kind.NULL), row((DefaultMutableTreeNode) booking.getChildAt(3)));
        DefaultMutableTreeNode lines = (DefaultMutableTreeNode) booking.getChildAt(4);
        assertEquals(new FlowJsonTree.Row("lines", "[2 items]", FlowJsonTree.Kind.ARRAY), row(lines));
        assertEquals("[1]", row((DefaultMutableTreeNode) lines.getChildAt(1)).name());
        assertEquals(new FlowJsonTree.Row("amount", "5", FlowJsonTree.Kind.NUMBER),
                row((DefaultMutableTreeNode) lines.getChildAt(1).getChildAt(0)));
    }

    @Test
    void variablesKeepTheirTypesLikeTheDebugger() {
        com.example.flowtracker.model.FlowValue thisValue = new com.example.flowtracker.model.FlowValue("this");
        thisValue.type = "com.travel.common.BookingDto";
        com.example.flowtracker.model.FlowValue lines = new com.example.flowtracker.model.FlowValue("priceLines");
        lines.type = "java.util.ArrayList<PriceLineDto>";
        lines.value = "size = 0";
        com.example.flowtracker.model.FlowValue ref = new com.example.flowtracker.model.FlowValue("reference");
        ref.type = "String";
        ref.value = "\"BK-1044\"";
        thisValue.children.add(lines);
        thisValue.children.add(ref);
        DefaultMutableTreeNode n = FlowJsonTree.varNode(thisValue, true);
        assertEquals(new FlowJsonTree.VarRow("this", "com.travel.common.BookingDto", "", true, true, "frame.this", false), n.getUserObject());
        assertEquals(new FlowJsonTree.VarRow("priceLines", "java.util.ArrayList<PriceLineDto>", "size = 0", false, false, "BookingDto.priceLines", false),
                ((DefaultMutableTreeNode) n.getChildAt(0)).getUserObject());
        assertEquals("BookingDto", FlowJsonTree.simpleType("com.travel.common.BookingDto"));
        assertEquals("ArrayList", FlowJsonTree.simpleType("java.util.ArrayList<PriceLineDto>"));
    }

    @Test
    void pinnedFieldsComeFirstInEveryObjectOfTheirType() {
        com.example.flowtracker.model.FlowValue booking = new com.example.flowtracker.model.FlowValue("this");
        booking.type = "com.travel.common.BookingDto";
        for (String f : new String[]{"status", "trip", "reference", "total"}) booking.children.add(new com.example.flowtracker.model.FlowValue(f));
        assertEquals("BookingDto.reference", FlowJsonTree.pinKey(booking, "reference"));
        assertEquals("frame.booking", FlowJsonTree.pinKey(null, "booking"));
        java.util.Set<String> pinned = new java.util.LinkedHashSet<>(java.util.List.of("BookingDto.total", "BookingDto.reference", "Other.status"));
        assertEquals(java.util.List.of("reference", "total", "status", "trip"),
                FlowJsonTree.pinnedFirst(booking, booking.children, pinned).stream().map(v -> v.name).toList(),
                "pinned ones first (in the object's order), then the others; another type's pin doesn't count");
        DefaultMutableTreeNode n = FlowJsonTree.varNode(booking, true);
        assertEquals("status", ((FlowJsonTree.VarRow) ((DefaultMutableTreeNode) n.getChildAt(0)).getUserObject()).name(), "nothing pinned: as read");
    }
}
