package com.example.flowtracker.diagram;

import com.example.flowtracker.Fixtures;
import com.example.flowtracker.code.FlowSync;
import com.example.flowtracker.model.FlowEntry;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowDiagramExportTest {

    private static FlowDiagram diagram() {
        FlowEntry create = Fixtures.line("BookingService.java", "BookingService", "create()", 20, "TravelCodes.normalize(code);");
        FlowEntry norm = Fixtures.line("TravelCodes.java", "TravelCodes", "normalize()", 9, "return code.trim();");
        String c = FlowSync.methodKey(create), n = FlowSync.methodKey(norm);
        return FlowDiagram.of(List.of(create, norm), Set.of(FlowSync.link(c, n)));
    }

    @Test
    void mermaidHasAGroupPerClassAndAnArrowPerCall() {
        String m = FlowDiagramExport.mermaid(diagram());
        assertTrue(m.startsWith("flowchart LR\n"), m);
        assertTrue(m.contains("subgraph c1[\"BookingService · BookingService.java (TravelBooking)\"]"), m);
        assertTrue(m.contains("m1[\"create()\"]"), m);
        assertTrue(m.contains("m1 --> m2"), m);
    }

    @Test
    void plantUmlHasAPackagePerClassAndAnArrowPerCall() {
        String p = FlowDiagramExport.plantUml(diagram());
        assertTrue(p.startsWith("@startuml"), p);
        assertTrue(p.contains("rectangle \"normalize()\" as m2"), p);
        assertTrue(p.contains("m1 --> m2"), p);
        assertTrue(p.strip().endsWith("@enduml"), p);
    }

    @Test
    void svgHasTheBoxesTheirNamesAndTheLine() {
        FlowDiagram d = diagram();
        FlowDiagramPainter painter = new FlowDiagramPainter(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        painter.autoLayout(d);
        String svg = FlowDiagramExport.svg(d, painter);
        assertTrue(svg.startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\""), svg);
        assertTrue(svg.contains(">BookingService</text>") && svg.contains(">TravelCodes</text>"), svg);
        assertTrue(svg.contains("• normalize()"), svg);
        assertEquals(1, svg.split("marker-end=\"url\\(#arrow\\)\"", -1).length - 1, "one arrow per call");
        assertTrue(svg.strip().endsWith("</svg>"));
    }
}
