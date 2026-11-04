package com.example.flowtracker.copy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A flow as a web page (Export as HTML Page): the Markdown document TraceWeave makes (see {@link FlowMarkdown}) as
 * one .html file anyone can open in a browser, without the plugin: the summary, the call diagrams (drawn by
 * mermaid.js; without internet they show as text), each method's lines with their colors, and the Original Log. Light
 * or dark, as the browser is. It reads the Markdown TraceWeave writes (headings, tables, code blocks, bold, italic,
 * code, rules); HTML inside it (the colored code) is kept.
 */
public final class FlowHtml {
    private FlowHtml() {}

    /** The mermaid.js the page draws its diagrams with (a fixed version, so the page looks the same later). */
    static final String MERMAID = "https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.esm.min.mjs";

    public static String page(String title, String markdown) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta name="generator" content="TraceWeave - Code Flow Tracker">
                <title>%s</title>
                <style>
                :root { --bg:#f7f8fa; --card:#ffffff; --fg:#1e1f22; --muted:#6c707e; --line:#dfe1e5; --accent:#3574f0; --code:#f2f3f5; }
                @media (prefers-color-scheme: dark) {
                  :root { --bg:#151a23; --card:#19202b; --fg:#dfe3ea; --muted:#8b93a3; --line:#283041; --accent:#548af7; --code:#161c26; }
                }
                body { margin:0; background:var(--bg); color:var(--fg); font:15px/1.55 -apple-system, "Segoe UI", Roboto, sans-serif; }
                main { max-width:1100px; margin:0 auto; padding:28px 20px 60px; }
                h1 { font-size:1.7em; margin:0 0 6px; }
                h2 { font-size:1.3em; margin:32px 0 10px; padding-bottom:6px; border-bottom:1px solid var(--line); }
                h3 { font-size:1.1em; margin:24px 0 8px; } h4 { font-size:1em; margin:18px 0 6px; }
                blockquote { margin:0 0 18px; padding:8px 14px; border-left:3px solid var(--accent); background:var(--card); color:var(--muted); border-radius:6px; }
                table { border-collapse:collapse; margin:8px 0 18px; background:var(--card); border:1px solid var(--line); border-radius:8px; overflow:hidden; display:block; overflow-x:auto; }
                th, td { padding:6px 12px; border-bottom:1px solid var(--line); text-align:left; vertical-align:top; }
                th { color:var(--muted); font-weight:600; font-size:.9em; }
                td.r, th.r { text-align:right; } tr:last-child td { border-bottom:0; }
                code { font:13px/1.45 "JetBrains Mono", Consolas, monospace; }
                :not(pre) > code { background:var(--code); padding:1px 5px; border-radius:4px; }
                td code { background:transparent; padding:0; white-space:pre; }
                pre { background:var(--card); border:1px solid var(--line); border-radius:8px; padding:12px 14px; overflow-x:auto; }
                pre.mermaid { text-align:center; }
                hr { border:0; border-top:1px solid var(--line); margin:30px 0; }
                footer { color:var(--muted); font-size:.85em; margin-top:40px; }
                </style>
                </head>
                <body>
                <main>
                %s
                <footer>Made with TraceWeave - Code Flow Tracker. The diagrams are drawn by mermaid.js (needs internet; without it they show as text).</footer>
                </main>
                <script type="module">
                import mermaid from '%s';
                mermaid.initialize({ startOnLoad: true, theme: matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'default' });
                </script>
                </body>
                </html>
                """.formatted(escape(title), body(markdown), MERMAID);
    }

    /** The Markdown's body as HTML. */
    static String body(String markdown) {
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        StringBuilder out = new StringBuilder();
        List<String> paragraph = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("```")) { // a code block: a diagram, or text as it is
                flush(paragraph, out);
                String kind = line.substring(3).strip();
                StringBuilder code = new StringBuilder();
                for (i++; i < lines.length && !lines[i].startsWith("```"); i++) code.append(lines[i]).append('\n');
                if (kind.equals("mermaid")) out.append("<pre class=\"mermaid\">").append(escape(code.toString())).append("</pre>\n");
                else out.append("<pre><code>").append(escape(code.toString())).append("</code></pre>\n");
                continue;
            }
            if (line.startsWith("|")) { // a table: a header row, the alignment row, the rows
                flush(paragraph, out);
                List<String> rows = new ArrayList<>();
                for (; i < lines.length && lines[i].startsWith("|"); i++) rows.add(lines[i]);
                i--;
                table(rows, out);
                continue;
            }
            Matcher h = HEADING.matcher(line);
            if (h.matches()) {
                flush(paragraph, out);
                int level = h.group(1).length();
                out.append("<h").append(level).append('>').append(inline(h.group(2))).append("</h").append(level).append(">\n");
                continue;
            }
            if (line.startsWith("> ")) {
                flush(paragraph, out);
                out.append("<blockquote>").append(inline(line.substring(2))).append("</blockquote>\n");
                continue;
            }
            if (line.strip().equals("---")) {
                flush(paragraph, out);
                out.append("<hr>\n");
                continue;
            }
            if (line.isBlank()) flush(paragraph, out);
            else paragraph.add(line);
        }
        flush(paragraph, out);
        return out.toString();
    }

    private static final Pattern HEADING = Pattern.compile("(#{1,6})\\s+(.*)");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<![\\w])_(.+?)_(?![\\w])");

    private static void flush(List<String> paragraph, StringBuilder out) {
        if (paragraph.isEmpty()) return;
        out.append("<p>").append(inline(String.join(" ", paragraph))).append("</p>\n");
        paragraph.clear();
    }

    private static void table(List<String> rows, StringBuilder out) {
        if (rows.size() < 2) {
            for (String r : rows) out.append("<p>").append(inline(r)).append("</p>\n");
            return;
        }
        List<String> head = cells(rows.get(0)), align = cells(rows.get(1));
        out.append("<table>\n<thead><tr>");
        for (int c = 0; c < head.size(); c++) out.append(cell("th", head.get(c), right(align, c)));
        out.append("</tr></thead>\n<tbody>\n");
        for (int r = 2; r < rows.size(); r++) {
            List<String> cells = cells(rows.get(r));
            out.append("<tr>");
            for (int c = 0; c < head.size(); c++) out.append(cell("td", c < cells.size() ? cells.get(c) : "", right(align, c)));
            out.append("</tr>\n");
        }
        out.append("</tbody>\n</table>\n");
    }

    private static boolean right(List<String> align, int c) {
        return c < align.size() && align.get(c).strip().endsWith(":") && !align.get(c).strip().startsWith(":");
    }

    private static String cell(String tag, String text, boolean right) {
        return "<" + tag + (right ? " class=\"r\"" : "") + ">" + inline(text.strip()) + "</" + tag + ">";
    }

    /** A table row's cells ("| a | b |"; "&#124;" stands for a "|" inside a cell). */
    private static List<String> cells(String row) {
        String r = row.strip();
        if (r.startsWith("|")) r = r.substring(1);
        if (r.endsWith("|")) r = r.substring(0, r.length() - 1);
        List<String> out = new ArrayList<>();
        for (String c : r.split("\\|", -1)) out.add(c);
        return out;
    }

    /** Bold, italic and `code` in a line; HTML in it (TraceWeave's colored code) stays as it is. */
    static String inline(String text) {
        StringBuilder out = new StringBuilder();
        String[] parts = text.split("`", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i % 2 == 1 && i < parts.length - 1) {
                out.append("<code>").append(escape(parts[i])).append("</code>");
            } else {
                String p = i % 2 == 1 ? "`" + parts[i] : parts[i]; // an unpaired backtick stays
                p = BOLD.matcher(p).replaceAll("<b>$1</b>");
                p = ITALIC.matcher(p).replaceAll("<i>$1</i>");
                out.append(p);
            }
        }
        return out.toString();
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
