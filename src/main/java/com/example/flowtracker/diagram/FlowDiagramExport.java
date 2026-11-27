package com.example.flowtracker.diagram;

import org.jetbrains.annotations.Nullable;

import java.awt.Shape;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The Flow Diagram in formats people edit elsewhere: Mermaid and PlantUML (text for wikis, Markdown and design docs)
 * and SVG (the drawing as shapes and text: draw.io, Inkscape, a browser). Each class is a group of its methods; each
 * call an arrow (dashed when connected by hand), with its note.
 */
final class FlowDiagramExport {
    private FlowDiagramExport() {
    }

    // ---------- Mermaid ----------

    /** A Mermaid flowchart, left to right: a subgraph per class (its project and file in the title), an arrow per call. */
    static String mermaid(FlowDiagram d) {
        StringBuilder out = new StringBuilder("flowchart LR\n");
        Map<String, String> ids = ids(d);
        int n = 0;
        for (FlowDiagram.Box b : d.boxes) {
            String title = b.name + (b.fileLine().isEmpty() ? "" : " · " + b.fileLine()) + (b.project().isEmpty() ? "" : " (" + b.project() + ")");
            out.append("  subgraph c").append(++n).append("[\"").append(mq(title)).append("\"]\n");
            for (FlowDiagram.Method m : b.methods) out.append("    ").append(ids.get(m.id)).append("[\"").append(mq(m.name)).append("\"]\n");
            out.append("  end\n");
        }
        for (FlowDiagram.Link l : d.links) {
            String a = ids.get(l.from()), z = ids.get(l.to());
            if (a == null || z == null) continue;
            String note = d.callNote(l);
            String arrow = l.manual() ? "-.->" : "-->";
            out.append("  ").append(a).append(' ').append(arrow)
                    .append(note == null || note.isBlank() ? "" : "|\"" + mq(oneLine(note)) + "\"|").append(' ').append(z).append('\n');
        }
        return out.toString();
    }

    // ---------- PlantUML ----------

    /** A PlantUML diagram: a package per class with its methods as rectangles, an arrow per call (with its note). */
    static String plantUml(FlowDiagram d) {
        StringBuilder out = new StringBuilder("@startuml\nleft to right direction\nskinparam shadowing false\n");
        Map<String, String> ids = ids(d);
        for (FlowDiagram.Box b : d.boxes) {
            String title = b.name + (b.fileLine().isEmpty() ? "" : "\\n" + b.fileLine()) + (b.project().isEmpty() ? "" : "\\n" + b.project());
            out.append("package \"").append(pq(title)).append("\" {\n");
            for (FlowDiagram.Method m : b.methods) out.append("  rectangle \"").append(pq(m.name)).append("\" as ").append(ids.get(m.id)).append('\n');
            out.append("}\n");
        }
        for (FlowDiagram.Link l : d.links) {
            String a = ids.get(l.from()), z = ids.get(l.to());
            if (a == null || z == null) continue;
            String note = d.callNote(l);
            out.append(a).append(l.manual() ? " ..> " : " --> ").append(z)
                    .append(note == null || note.isBlank() ? "" : " : " + pq(oneLine(note))).append('\n');
        }
        return out.append("@enduml\n").toString();
    }

    // ---------- SVG ----------

    /** The drawing as SVG: the boxes (project band, class name, file, methods), the call lines with arrows and notes. */
    static String svg(FlowDiagram d, FlowDiagramPainter painter) {
        Rectangle2D all = null;
        for (FlowDiagram.Box b : d.boxes) all = all == null ? painter.bounds(b) : all.createUnion(painter.bounds(b));
        for (FlowDiagram.Link l : d.links) {
            Rectangle2D r = painter.linkPath(d, l).getBounds2D();
            all = all == null ? r : all.createUnion(r);
        }
        if (all == null) all = new Rectangle2D.Double(0, 0, 100, 100);
        double pad = 24, x0 = all.getX() - pad, y0 = all.getY() - pad, w = all.getWidth() + 2 * pad, h = all.getHeight() + 2 * pad;
        StringBuilder out = new StringBuilder();
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"").append(f(x0)).append(' ').append(f(y0)).append(' ')
                .append(f(w)).append(' ').append(f(h)).append("\" width=\"").append(f(w)).append("\" height=\"").append(f(h))
                .append("\" font-family=\"Segoe UI, Inter, Arial, sans-serif\">\n");
        out.append("  <defs><marker id=\"arrow\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerWidth=\"8\" markerHeight=\"8\" orient=\"auto-start-reverse\">"
                + "<path d=\"M0,0 L10,5 L0,10 z\" fill=\"#6C707E\"/></marker></defs>\n");
        out.append("  <rect x=\"").append(f(x0)).append("\" y=\"").append(f(y0)).append("\" width=\"").append(f(w)).append("\" height=\"")
                .append(f(h)).append("\" fill=\"#FFFFFF\"/>\n");
        // the lines first: the boxes are drawn over their ends
        for (FlowDiagram.Link l : d.links) {
            out.append("  <path d=\"").append(path(painter.linkPath(d, l))).append("\" fill=\"none\" stroke=\"")
                    .append(l.manual() ? "#C77D0A" : "#6C707E").append("\" stroke-width=\"1.5\"")
                    .append(l.manual() ? " stroke-dasharray=\"6 4\"" : "").append(" marker-end=\"url(#arrow)\"/>\n");
        }
        for (FlowDiagram.Box b : d.boxes) {
            Rectangle2D r = painter.bounds(b);
            out.append("  <g>\n    <rect x=\"").append(f(r.getX())).append("\" y=\"").append(f(r.getY())).append("\" width=\"")
                    .append(f(r.getWidth())).append("\" height=\"").append(f(r.getHeight()))
                    .append("\" rx=\"8\" fill=\"#FFFFFF\" stroke=\"#C9CCD6\"/>\n");
            double top = r.getY();
            if (!b.methods.isEmpty()) top = Math.min(top, painter.rowBounds(b, 0).getY());
            double headBottom = b.methods.isEmpty() ? r.getMaxY() : painter.rowBounds(b, 0).getY();
            out.append("    <rect x=\"").append(f(r.getX())).append("\" y=\"").append(f(r.getY())).append("\" width=\"")
                    .append(f(r.getWidth())).append("\" height=\"").append(f(Math.max(0, headBottom - r.getY())))
                    .append("\" rx=\"8\" fill=\"#EEF3FE\"/>\n");
            double tx = r.getX() + 12, ty = r.getY() + 20;
            if (!b.project().isEmpty()) {
                out.append("    <text x=\"").append(f(tx)).append("\" y=\"").append(f(ty)).append("\" font-size=\"11\" fill=\"#6C707E\">")
                        .append(x(b.project())).append("</text>\n");
                ty += 18;
            }
            out.append("    <text x=\"").append(f(tx)).append("\" y=\"").append(f(ty)).append("\" font-size=\"14\" font-weight=\"600\" fill=\"#1E1F22\">")
                    .append(x(b.name)).append("</text>\n");
            if (!b.fileLine().isEmpty()) {
                out.append("    <text x=\"").append(f(tx)).append("\" y=\"").append(f(ty + 16)).append("\" font-size=\"11\" fill=\"#6C707E\">")
                        .append(x(b.fileLine())).append("</text>\n");
            }
            for (int i = 0; i < b.methods.size(); i++) {
                Rectangle2D row = painter.rowBounds(b, i);
                out.append("    <text x=\"").append(f(row.getX() + 14)).append("\" y=\"").append(f(row.getCenterY() + 4))
                        .append("\" font-size=\"13\" fill=\"#1E1F22\">• ").append(x(b.methods.get(i).name)).append("</text>\n");
            }
            out.append("  </g>\n");
        }
        // the calls' notes, where the diagram puts them
        for (FlowDiagram.Link l : d.links) {
            String note = d.callNote(l);
            Rectangle2D r = note == null || note.isBlank() ? null : painter.callNoteBounds(d, l);
            if (r == null) continue;
            out.append("  <rect x=\"").append(f(r.getX())).append("\" y=\"").append(f(r.getY())).append("\" width=\"").append(f(r.getWidth()))
                    .append("\" height=\"").append(f(r.getHeight())).append("\" rx=\"6\" fill=\"#FFF6D6\" stroke=\"#E0C46C\"/>\n");
            out.append("  <text x=\"").append(f(r.getCenterX())).append("\" y=\"").append(f(r.getCenterY() + 4))
                    .append("\" font-size=\"11\" text-anchor=\"middle\" fill=\"#1E1F22\">").append(x(oneLine(note))).append("</text>\n");
        }
        return out.append("</svg>\n").toString();
    }

    /** A shape as an SVG path ("M x y L … C … Z"). */
    static String path(Shape s) {
        StringBuilder out = new StringBuilder();
        double[] c = new double[6];
        for (PathIterator it = s.getPathIterator(null); !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> out.append("M").append(f(c[0])).append(' ').append(f(c[1])).append(' ');
                case PathIterator.SEG_LINETO -> out.append("L").append(f(c[0])).append(' ').append(f(c[1])).append(' ');
                case PathIterator.SEG_QUADTO -> out.append("Q").append(f(c[0])).append(' ').append(f(c[1])).append(' ')
                        .append(f(c[2])).append(' ').append(f(c[3])).append(' ');
                case PathIterator.SEG_CUBICTO -> out.append("C").append(f(c[0])).append(' ').append(f(c[1])).append(' ')
                        .append(f(c[2])).append(' ').append(f(c[3])).append(' ').append(f(c[4])).append(' ').append(f(c[5])).append(' ');
                case PathIterator.SEG_CLOSE -> out.append("Z ");
                default -> { }
            }
        }
        return out.toString().strip();
    }

    // ---------- helpers ----------

    /** Short ids for the methods ("m1", "m2"…), the same in every format. */
    private static Map<String, String> ids(FlowDiagram d) {
        Map<String, String> ids = new HashMap<>();
        int n = 0;
        for (FlowDiagram.Box b : d.boxes) for (FlowDiagram.Method m : b.methods) ids.put(m.id, "m" + (++n));
        return ids;
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s*\\R\\s*", " ").strip();
    }

    /** Text in Mermaid quotes ("…"). */
    private static String mq(@Nullable String s) {
        return s == null ? "" : s.replace("\"", "#quot;");
    }

    /** Text in PlantUML quotes. */
    private static String pq(@Nullable String s) {
        return s == null ? "" : s.replace("\"", "'");
    }

    /** Text in SVG. */
    private static String x(@Nullable String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.1f", v).replace(".0", "");
    }
}
