package com.example.flowtracker.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One value read from the debugger: a variable, a field, a map entry or an array element, with what it holds
 * a few levels deep. Public non-final fields so IntelliJ can save it to XML.
 */
public class FlowValue {
    /** As the debugger names it: a variable or field name, a map key ("\"cart\""), an index ("[0]"). */
    public String name = "";
    /** Its type as the debugger shows it, e.g. "String" or "HashMap" ("" when unknown). */
    public String type = "";
    /** Its value as the debugger shows it, e.g. "\"Ann\"", "3" or "size = 2". */
    public String value = "";
    public List<FlowValue> children = new ArrayList<>();

    public FlowValue() {}

    public FlowValue(String name) {
        this.name = name;
    }

    /** A deep copy. */
    public FlowValue copy() {
        FlowValue v = new FlowValue(name);
        v.type = type;
        v.value = value;
        for (FlowValue c : children) v.children.add(c.copy());
        return v;
    }
}
