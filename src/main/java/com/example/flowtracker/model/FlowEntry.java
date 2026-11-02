package com.example.flowtracker.model;

import com.example.flowtracker.code.FlowSync;
/** One saved line. Public non-final fields so IntelliJ can serialize it to XML. */
public class FlowEntry {
    public String fileName = "";
    public String fileUrl = "";
    public String className = "";
    public String methodName = "";
    public int line;          // 1-based
    public String text = "";
    /** Optional short note, shown only in Flat View. */
    public String note = "";
    /** Gradle / Maven project the file belongs to (tells same-named files apart). */
    public String projectName = "";
    /** Optional colour tag: "" (none), "bug", "important" or "question" (see {@link FlowTag}). */
    public String tag = "";
    /**
     * Methods this line calls, connected by hand (for calls the code doesn't show, e.g. an API request that
     * reaches another project). Each is a method key: {@link FlowSync#methodKey}.
     */
    public java.util.List<String> calls = new java.util.ArrayList<>();

    public FlowEntry() {}

    public FlowEntry(String fileName, String fileUrl, String className, String methodName, int line, String text) {
        this.fileName = fileName;
        this.fileUrl = fileUrl;
        this.className = className;
        this.methodName = methodName;
        this.line = line;
        this.text = text;
    }

    /** A copy (e.g. a line pasted into another flow), with its note, tag, project and connected calls. */
    public FlowEntry copy() {
        FlowEntry c = new FlowEntry(fileName, fileUrl, className, methodName, line, text);
        c.note = note;
        c.projectName = projectName;
        c.tag = tag;
        c.calls = new java.util.ArrayList<>(calls);
        return c;
    }
}
