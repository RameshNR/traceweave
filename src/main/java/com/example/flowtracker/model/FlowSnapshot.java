package com.example.flowtracker.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A debug value saved from the debugger's Variables view (right-click → Save to TraceWeave): the variable
 * with its fields a few levels deep, where the debugger stopped, and in which flow. Public non-final fields so
 * IntelliJ can save it to XML.
 */
public class FlowSnapshot {
    public String id = "";
    /** The variable's name, e.g. "session". */
    public String name = "";
    /** The flow that was active when it was saved. */
    public String flowName = "";
    public String fileUrl = "";
    public String fileName = "";
    public String className = "";
    public String methodName = "";
    /** 1-based line where the debugger stopped (0 when unknown). */
    public int line;
    /** When it was saved (epoch milliseconds). */
    public long savedAt;
    /** When it went to the Recycle Bin (0 = not deleted). */
    public long deletedAt;
    public FlowValue value = new FlowValue();
    /** The data as it was saved, kept from the first change on (Reset puts it back); null while unchanged. */
    public FlowValue original;
    /** Changes made by hand, oldest first (also the ones undone by Revert). */
    public List<FlowValueEdit> edits = new ArrayList<>();

    public FlowSnapshot() {}

    /** Where it was saved, e.g. "BookingService.java:42". */
    public String where() {
        return fileName.isEmpty() ? "(no position)" : line > 0 ? fileName + ":" + line : fileName;
    }

    /** The method it was saved in, e.g. "BookingService.bookTrip()" ("" when unknown). */
    public String method() {
        if (methodName.isEmpty()) return className;
        return className.isEmpty() ? methodName : className + "." + methodName;
    }

    /** True when both are the same variable saved at the same line (what Compare matches by). */
    public boolean sameVariableAs(FlowSnapshot o) {
        return name.equals(o.name) && fileUrl.equals(o.fileUrl) && line == o.line;
    }
}
