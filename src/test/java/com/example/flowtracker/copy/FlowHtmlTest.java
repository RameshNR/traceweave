package com.example.flowtracker.copy;

import com.example.flowtracker.code.FlowSync;
import com.example.flowtracker.model.FlowEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.example.flowtracker.Fixtures.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Export as HTML Page: TraceWeave's Markdown as a page anyone can open in a browser. */
class FlowHtmlTest {

    @Test
    void theMarkdownBecomesAPage() {
        FlowEntry main = line("Main.java", "Main", "main()", 19, "bookingService.bookTrip(101);");
        FlowEntry book = line("BookingService.java", "BookingService", "bookTrip()", 33, "if (a < b || c) {");
        String md = FlowMarkdown.toMarkdown(List.of(main, book), false,
                Set.of(FlowSync.link(FlowSync.methodKey(main), FlowSync.methodKey(book))), true, "Flow 2");
        String page = FlowHtml.page("Flow 2", md);

        assertTrue(page.startsWith("<!doctype html>"));
        assertTrue(page.contains("<title>Flow 2</title>"));
        assertTrue(page.contains("<h1>🧭 Flow 2</h1>"), "the heading");
        assertTrue(page.contains("<pre class=\"mermaid\">sequenceDiagram"), "the diagram, for mermaid.js");
        assertTrue(page.contains("&lt;br/&gt;"), "the diagram's own markup is text for mermaid, not HTML");
        assertTrue(page.contains("<table>") && page.contains("<th>Project</th>"), "the summary table");
        assertTrue(page.contains("<code><span"), "the colored code is kept as HTML");
        assertTrue(page.contains("&#124;&#124;"), "a | in the code stays inside its cell");
        assertTrue(page.contains(FlowHtml.MERMAID));
        assertFalse(page.contains("```"), "no Markdown left");
    }

    @Test
    void inlineMarkdown() {
        assertEquals("<b>3</b> lines · <i>Grouped View</i> · <code>a &lt; b</code>", FlowHtml.inline("**3** lines · _Grouped View_ · `a < b`"));
        assertEquals("snake_case_name stays", FlowHtml.inline("snake_case_name stays"));
        assertEquals("<h2>Summary</h2>\n<p>one two</p>\n<hr>\n", FlowHtml.body("## Summary\none\ntwo\n\n---\n"));
        assertEquals("<table>\n<thead><tr><th>Line</th><th class=\"r\">N</th></tr></thead>\n<tbody>\n"
                + "<tr><td>a</td><td class=\"r\">2</td></tr>\n</tbody>\n</table>\n", FlowHtml.body("| Line | N |\n|---|---:|\n| a | 2 |"));
    }
}
