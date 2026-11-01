package com.example.flowtracker.diagram;

/**
 * How one thing of a flow's Flow Diagram looks: a class box (where it is, its colors, title format and note), a
 * method (its name format and note) or a call (its line). Saved with the flow, so public non-final fields that
 * IntelliJ can serialize to XML (and Gson to a .flow.json file).
 * <p>
 * What it is for: a class box has only {@link #fileUrl} and {@link #className}; a method also {@link #method}; a
 * call also the method it calls ({@link #toFileUrl}, {@link #toClass}, {@link #toMethod}). Classes are named as the
 * diagram shows them, and custom classes (not in the code) have no file.
 */
public class FlowDiagramLook {
    public String fileUrl = "";
    public String className = "";
    public String method = "";
    public String toFileUrl = "";
    public String toClass = "";
    public String toMethod = "";

    /** True when {@link #x} / {@link #y} hold the box's position. */
    public boolean placed;
    public double x;
    public double y;

    /** Background of the box's method section, "#RRGGBB" ("" = the usual one). */
    public String fill = "";
    /** Background of the box's class section (its name, file name and note). */
    public String header = "";
    /** Box border, or the call's line color. */
    public String border = "";
    /**
     * Text format of the class title or method name: "b" bold, "i" italic, "u" underline, "s" strike-through, then
     * a color, e.g. "bu#C62828".
     */
    public String format = "";
    /** A box's file name format, and the format of its method names (a method's own {@link #format} wins). */
    public String fileFormat = "";
    public String methodFormat = "";
    /** A note shown under the class name or the method name (several lines allowed). */
    public String note = "";
    public String noteFormat = "";
    /** A class box's project band: its background, border and text format ("" = like the class section). */
    public String projectFill = "";
    public String projectBorder = "";
    public String projectFormat = "";
    /** A call's line: pattern, width, shape and arrow head, e.g. "DASHED,THICK,ELBOW,OPEN" ("" = the usual one). */
    public String line = "";
    /** How a call's note is drawn: shape;background;text;border, e.g. "PILL;#FFF1D6;;#C27C0E" ("" = the usual one). */
    public String noteStyle = "";

    public FlowDiagramLook() {}

    boolean isLink() { return !toMethod.isEmpty(); }
    boolean isMethod() { return !isLink() && !method.isEmpty(); }
    boolean isBox() { return method.isEmpty(); }

    /** Everything it holds, as one text (to compare looks). */
    String text() {
        return String.join("\u0000", fileUrl, className, method, toFileUrl, toClass, toMethod,
                placed ? Math.round(x) + "," + Math.round(y) : "", fill, header, border, format, fileFormat, methodFormat,
                note, noteFormat, line, noteStyle == null ? "" : noteStyle, projectFill == null ? "" : projectFill,
                projectBorder == null ? "" : projectBorder, projectFormat == null ? "" : projectFormat);
    }
}
