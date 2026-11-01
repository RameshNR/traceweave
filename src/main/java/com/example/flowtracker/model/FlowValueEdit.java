package com.example.flowtracker.model;

/** A change made by hand to a saved debug value: which value, what it was, what it became, and when. */
public class FlowValueEdit {
    /** Where the value is in its snapshot: child indexes from the root, e.g. "0.3" ("" = the root). */
    public String path = "";
    /** The value's names from the root, for showing, e.g. "session › attributes › \"cart\"". */
    public String label = "";
    public String before = "";
    public String after = "";
    /** When (epoch milliseconds). */
    public long at;
    /** What changed: "" its value, "NAME" its name, "ADD" a field added, "REMOVE" a field removed. */
    public String kind = "";
    /** A removed field (with its children), to put it back on Revert. */
    public FlowValue item;

    public FlowValueEdit() {}

    FlowValueEdit(String path, String label, String before, String after) {
        this.path = path;
        this.label = label;
        this.before = before;
        this.after = after;
        this.at = System.currentTimeMillis();
    }
}
