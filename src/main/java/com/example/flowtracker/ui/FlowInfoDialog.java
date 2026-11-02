package com.example.flowtracker.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** "Info" from the settings popup: what TraceWeave does and how to use every part of it. */
final class FlowInfoDialog extends DialogWrapper {

    FlowInfoDialog(Project project) {
        super(project, false);
        setTitle("TraceWeave — Info");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JEditorPane text = new JEditorPane("text/html", html());
        text.setEditable(false);
        text.setOpaque(false);
        text.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        text.setFont(JBUI.Fonts.label());
        text.setBorder(JBUI.Borders.empty(4, 8));
        text.setCaretPosition(0);

        JBScrollPane scroll = new JBScrollPane(text);
        scroll.setBorder(JBUI.Borders.empty());
        scroll.setPreferredSize(new Dimension(JBUI.scale(580), JBUI.scale(400)));

        // Screenshots on top (when the plugin has them), the help text below.
        FlowCarousel carousel = new FlowCarousel();
        if (!carousel.hasSlides()) return scroll;
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.add(carousel, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction()};
    }

    /** The plugin version, written into flowtracker/version.properties by Gradle (build.gradle.kts). */
    private static String version() {
        try (InputStream in = FlowInfoDialog.class.getResourceAsStream("/flowtracker/version.properties")) {
            if (in == null) return "dev";
            Properties p = new Properties();
            p.load(in);
            return p.getProperty("version", "dev");
        } catch (IOException e) {
            return "dev";
        }
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static String html() {
        String muted = hex(FlowTheme.FG_MUTED);
        String accent = hex(FlowTheme.ACCENT);
        String fg = hex(UIUtil.getLabelForeground());
        return "<html><body style='color:" + fg + "'>"
                + "<h2 style='margin-bottom:2px'>🧭 TraceWeave - Code Flow Tracker <span style='color:" + muted
                + ";font-weight:normal'>v" + version() + "</span></h2>"
                + "<p style='color:" + muted + "'>Collect the lines of a code path while you read it, keep them in "
                + "call order, add notes, and copy the whole flow in one click.</p>"

                + section(accent, "Add lines")
                + "<ul><li>Hover a line in a supported file and click the <b>+</b> icon in the gutter. Click the green "
                + "marker to remove the line again.</li>"
                + "<li>Or right-click in the editor \u2192 <b>Add to TraceWeave</b> (<b>Ctrl+Alt+Shift+A</b>): the caret "
                + "line, or every selected line, as one undo step. On a saved line it reads <b>Remove from Flow "
                + "Tracker</b>.</li>"
                + "<li>Saved lines show their <b>step number</b> in the gutter; hover it for the method, tag and note. "
                + "They follow your edits (in both views, while the file is open).</li>"
                + "<li><b>Add Bottom / Add Top</b> chooses where new lines go (↓ end, ↑ start).</li>"
                + "<li><b>Undo / Redo</b> (toolbar) revert adding, deleting and clearing lines, wherever it "
                + "happened (editor gutter or panel). Hover them to see what they will change.</li>"
                + "<li>Each line remembers its file, class, method, line number and project.</li></ul>"

                + section(accent, "Supported files")
                + "<ul><li><b>Java, Groovy</b> (<code>.java .groovy .gradle</code>) and <b>Kotlin</b> "
                + "(<code>.kt .kts</code>): class and method from the code; Sync and the diagrams follow calls.</li>"
                + "<li><b>JavaScript / TypeScript</b> (<code>.js .jsx .mjs .cjs .ts .tsx</code>): the enclosing "
                + "class and function, or the callback, e.g. <i>addEventListener(\"submit\") callback</i>. Calls "
                + "between saved functions get arrows.</li>"
                + "<li><b>JSP / tag files</b> (<code>.jsp .jspf .jspx .tag .tagx</code>): the method in a "
                + "<code>&lt;%! %&gt;</code> declaration, <i>[scriptlet]</i>, <i>[script]</i> or the element, e.g. "
                + "<i>form#searchForm</i>. Calls from the page's Java code to saved Java methods get arrows "
                + "(matched by name and number of arguments).</li>"
                + "<li><b>TLD</b>: the tag or function, e.g. <i>tag status</i>. <b>XML / HTML</b>: the nearest "
                + "element with an id or name. <b>YAML</b>: the parent keys. <b>Properties, SQL</b>: the file.</li>"
                + "<li>Lines outside methods are grouped as <i>[fields]</i>, <i>[imports]</i> or <i>[class]</i>.</li>"
                + "<li>IntelliJ Community can't parse JavaScript or JSP, so for web and config files names are read "
                + "from the text (a best effort).</li></ul>"

                + section(accent, "Record while debugging")
                + "<ul><li>Click <b>Record</b>, then debug as usual: every line where the debugger stops "
                + "(a breakpoint or a step) is added at the bottom, in the order it ran. Click <b>Recording</b> to "
                + "stop.</li>"
                + "<li>It also picks up sessions that are already running, and turns Sync off so the execution "
                + "order is kept.</li></ul>"

                + section(accent, "Flows")
                + "<ul><li>Keep several named flows per project. The flow button at the top opens the <b>Flows</b> "
                + "popup: click a flow to show it (the current one is checked, with its line count).</li>"
                + "<li><b>Drag</b> a flow by its handle (or select it and press <b>Ctrl+Up / Ctrl+Down</b>) to "
                + "reorder. Copy All Flows copies the flows in this order.</li>"
                + "<li><b>New Flow</b> and <b>Rename Flow</b> start the name as <i>Flow 1: </i>, so you type the "
                + "name after it (<i>Flow 1: Checkout bug</i>). You can delete the prefix too.</li>"
                + "<li><b>Delete Flow</b> moves the flow to the Recycle Bin; deleting the only flow empties it. "
                + "<b>Delete Flows…</b> deletes several (or all) at once.</li>"
                + "<li>The Flow tab and <b>Debug Data</b> share their flows: a new flow and the selected flow are the same "
                + "in both, and a deleted flow goes from both (at once when neither has anything in it, else after one "
                + "confirmation that says what goes in each). Their <b>names</b> are separate: renaming in one doesn't "
                + "rename in the other.</li>"
                + "<li>Exported files start with the project's name in camelCase (<i>travelBooking_Flow 1.flow.json</i>).</li>"
                + "<li><b>Export Flow</b> saves the flow as a <code>.flow.json</code> file with paths relative to "
                + "the project. <b>Import Flow</b> opens it as a new flow on any machine; lines whose file isn't in "
                + "the project are marked <i>file missing</i>.</li>"
                + "<li>Undo / Redo history belongs to the flow it was made in.</li></ul>"

                + section(accent, "Recycle Bin")
                + "<ul><li>Everything you delete goes here: single lines (panel or gutter), Clear, and deleted "
                + "flows. Open it from <b>Settings \u2192 Recycle Bin</b>.</li>"
                + "<li><b>Restore</b> (or double-click) puts lines back into their flow at their old places (a deleted "
                + "flow comes back as a flow). <b>Delete Permanently</b> or the <b>Delete</b> key removes the selected "
                + "items for good, after asking; <b>Empty Recycle Bin</b> removes everything.</li>"
                + "<li>Undoing a delete takes it out of the bin. The bin keeps the latest 500 items.</li></ul>"

                + section(accent, "Views and search")
                + "<ul><li><b>Grouped View</b> — file → method → lines, with a project tag on each file.</li>"
                + "<li><b>Flat View</b> — one sequence in flow order, with step numbers.</li>"
                + "<li>The <b>search box</b> shows only lines whose code, file, class, method, note, project or tag "
                + "contains the text (or whose line number matches). Copy then copies only those lines.</li></ul>"

                + section(accent, "Working with rows")
                + "<ul><li><b>Hover a row</b> for its action bar: ↑ ↓ move (only when there is something "
                + "to swap with) | Copy and Note (code lines only) | Delete.</li>"
                + "<li><b>Drag</b> a file, method or line to move it. <b>Double-click</b> a line to open it.</li>"
                + "<li><b>Right-click</b> for Move Up/Down, Copy, Add/Edit Note, Jump to Source, Walk from Here, "
                + "Connect Call To, Tag and Delete.</li>"
                + "<li><b>Connect Call To</b> joins a line by hand to the method it reaches when the code can't show "
                + "it (e.g. an API call handled in another project). It then counts like a real call: Sync order, "
                + "diagrams and the nested Original Log, and it's saved and exported with the flow.</li>"
                + "<li><b>Connect the Flow Between These Lines</b>: select two lines, right-click: the calls that lead "
                + "from the first to the second are read from the code and added between them (one Undo step).</li>"
                + "<li><b>Tags</b> 🐞 Bug, ⭐ Important and ❓ Question color-mark lines and appear in every copy "
                + "format.</li>"
                + "<li>A <b>warning</b> marks lines that no longer match the code (file missing, moved to line N, "
                + "code changed). Right-click \u2192 <b>Re-link to Code</b> fixes one; <b>Re-link All Moved Lines</b> "
                + "fixes them all.</li>"
                + "<li><b>Ctrl+C</b> copies the selection, <b>Delete</b> removes it.</li></ul>"
                + "<h3>Flow Diagram</h3>"
                + "<ul><li><b>Flow Diagram</b> (toolbar) opens the flow as a diagram, in a window that doesn't block the IDE "
                + "(a normal window with Minimize, Maximize and Close; it asks before dropping changes that aren't saved): "
                + "a box per class or file with its "
                + "methods, solid arrows for calls found in the code and dashed ones for calls connected by hand.</li>"
                + "<li><b>Add File</b> asks for a file of the project (Java, JSP, JavaScript, TLD...): type at least "
                + "3 letters of its name, a pattern such as <code>*Cruise*.java</code> or a regular expression such as "
                + "<code>/Book.*Service/</code>, then pick one of its classes when it has several. A file that isn't in the "
                + "project becomes a custom class named after it (PaymentGateway.java: PaymentGateway); right-click it to "
                + "rename it or change its file. <b>Add Function</b> lists the class's methods (filtered the same "
                + "way), then shows the method's code so you can pick the lines to add (none picked: its first line); a name "
                + "the class doesn't have becomes a custom method.</li>"
                + "<li>Custom classes and methods (not in the code) are drawn in amber. Each box shows its file and project. "
                + "Double-click a class name to pick another class for it, its methods to add one, or an empty spot to add "
                + "a class. Right-click a method → <b>Tag</b> to tag all its lines Bug, Important or Question.</li>"
                + "<li><b>Connect</b>: drag from a method to the method it calls. <b>Rename</b> (F2) and <b>Delete</b> change "
                + "the selected class, method or call. Drag boxes, or use <b>Auto Layout</b>.</li>"
                + "<li><b>Moving around</b>: boxes never cover each other (the others make room when a box grows or is "
                + "dropped on one), and lines go around boxes in their way. Drag an empty spot (or use the middle mouse "
                + "button) to move the view; zoom with the <b>− / 100% / +</b> buttons in the bottom right corner, "
                + "Ctrl+mouse wheel or Ctrl+Plus / Minus / 0.</li>"
                + "<li><b>Tabs</b>: one tab per flow, like a browser's. <b>+</b> opens a new flow or a saved one; "
                + "right-click a tab to rename its flow, give it a color, connect it with another tab, close it or delete "
                + "its flow. A tab closes at once when it is empty or saved, else it asks (Save and Close, Don't Save, Move to "
                + "Another Tab). Flows left empty are deleted when the window closes.</li>"
                + "<li><b>Several classes</b>: Ctrl+click (or Ctrl+A) to select, Ctrl+C / Ctrl+X / Ctrl+V to copy, cut and "
                + "paste them (also into another tab), or right-click → <b>Copy to Tab / Move to Tab</b> (an open tab or a new "
                + "one). They keep their look, tags and notes. Where a moved class is called by (or calls) one that stays, "
                + "both tabs show the other side as an orange-framed <b>link box</b> (<i>⇄ in “the other tab”</i>), and a "
                + "tab with both combined opens.</li>"
                + "<li><b>Connect the Flow to…</b> (right-click a method): pick another method; the calls between them are "
                + "read from the code and added, with their classes (e.g. main() → bookTrip(), both lines that call "
                + "getDestination() → getDestination()), laid out left to right.</li>"
                + "<li><b>Code panel</b>: click a method to see its lines of code under the diagram, with syntax colors and "
                + "the rest of the file folded. <b>View Full Code</b> shows the whole file at that method. Hover a line "
                + "number: <b>+</b> adds the line, <b>−</b> takes a flow line out (not one that makes a call drawn to another "
                + "box: a warning says why). Double-click a line to open it in the editor; Ctrl+click a name to see its code "
                + "here (Back returns).</li>"
                + "<li><b>Format</b> (or right-click): a box's theme (Plain, Light, Dark and eight colors) or its own class "
                + "section, method section and border colors, with text that stays readable; class, file and method names "
                + "and notes in bold, italic, underline, strike-through and any color; the note under a class or method "
                + "name; and a call's line (pattern, width, shape, arrow, color) and its note, shown at the middle of the arrow "
                + "and under it in the sequence diagram. Each window has a <b>Reset</b> button; "
                + "<b>Reset Theme</b> in the toolbar gives every class, method and line the default look again.</li>"
                + "<li>The <b>History</b> lists every change and stays after Save (while the IDE runs); <b>Undo / Redo</b> "
                + "(Ctrl+Z / Ctrl+Y) or a click on a step goes back and forth. Changes that change nothing (e.g. Auto "
                + "Layout again) aren't listed.</li>"
                + "<li><b>Save</b> writes the changes to the flow as one step the toolbar's Undo can revert: picked "
                + "lines become saved lines, renamed classes and methods get their new names (in TraceWeave only, "
                + "not in your code), removed ones go to the Recycle Bin, and custom ones become lines marked "
                + "<b>+</b> (no code yet). Formats, notes and positions are saved with the flow and exported with it. "
                + "<b>Reset</b> drops the changes since the last Save. <b>Close</b> closes the tab; the <b>▾</b> of Close "
                + "has Close All, Don't Save and Close All, Save and Close All, and the ▾ of Save has Save All.</li>"
                + "<li><b>Copy PNG</b> copies the diagram as an image; <b>Copy Sequence PNG</b> copies its calls as a "
                + "sequence diagram image. Their <b>▾</b> picks a light or dark background. The diagram's <b>Settings</b> "
                + "set the images' default background and the window's background; the <b>Dark / Light</b> switch in the "
                + "drawing area's corner flips it.</li></ul>"

                + section(accent, "Debug Data")
                + "<ul><li>While debugging, right-click a variable in the Variables view (or an Evaluate Expression result) → "
                + "<b>Save to TraceWeave</b>: it is saved with its fields (4 levels deep), where the debugger stopped and "
                + "the active flow, in the <b>Debug Data</b> tab. In the editor, select an expression (or put the caret on a "
                + "variable) → right-click → <b>Save … to TraceWeave</b>; or type one with <b>Add Expression…</b> (enabled while "
                + "the debugger is paused).</li>"
                + "<li><b>Capture Data</b> saves the frame's variables by itself while you debug, each on its own: where the "
                + "debugger stops, or (choose it with ▾) at every line it runs up to the next breakpoint, saving what changed; "
                + "all variables, or only the ones used on the line.</li>"
                + "<li><b>Compare</b> (Ctrl+D) two selected values with each other, or one with pasted data: the saved value "
                + "on the left, paste the other one on the right and press Ctrl+Enter. The Compare window shows Tree, JSON "
                + "and Text views and can be minimized and maximized. <b>Layout</b> places the list and the value side by side or top and bottom.</li>"
                + "<li><b>Compare</b> a saved value with the same variable saved at that line before (any flow, any debug "
                + "run) or with its value now: every field side by side, differences highlighted (map entries by key, instance "
                + "ids like @1141 ignored). Saving offers it right away.</li>"
                + "<li><b>Compare as JSON</b> shows both in IntelliJ's diff viewer; <b>Compare</b> with nothing selected compares two pasted pieces of "
                + "data (JSON, XML, toString(), text: the kind is detected) and shows the added, removed and changed lines.</li>"
                + "<li>Show a value as a <b>Tree</b>, as <b>JSON</b> or as <b>Text</b>; <b>Copy</b> it or <b>Export…</b> it "
                + "to a .json or .txt file.</li>"
                + "<li><b>Find</b> a name, map key, type or value (or a /regex/) in all saved values, and in the paused debug "
                + "session's variables with <b>Also in the current debug session</b>.</li>"
                + "<li>In the JSON or Text view, <b>Edit</b>, change the data and <b>Apply</b> (Ctrl+Enter): maps stay maps, "
                + "unchanged values stay as the debugger showed them. <b>Reset</b> goes back to the saved data; both are in "
                + "History.</li>"
                + "<li><b>New Value…</b> adds a value by hand (JSON becomes fields). <b>Add Field</b> (Insert), <b>Edit</b> "
                + "(F2) a name or value, <b>Remove Field</b> (Delete); right-click a field to copy its value, "
                + "name = value, or JSON.</li>"
                + "<li><b>Edit</b> (or double-click) a saved field; <b>History</b> lists the changes and reverts them. "
                + "<b>Delete</b> moves values to the tab's Recycle Bin, where they can be restored.</li>"
                + "<li><b>Export…</b> saves the selected (or all) values with their edit history to a .flowdebug.json file; "
                + "<b>Import…</b> adds the values of such a file.</li>"
                + "<li>The <b>Flow ▾</b> button (left of the search) picks the flow new values go to, with New, Rename, "
                + "Delete, Delete Flows, export and import. Right-click the list to rename a flow (F2), <b>Name This "
                + "Line…</b>, or move, export, import and delete a flow. Selecting a flow here selects it in the Flow tab. "
                + "<b>Delete</b> on selected flows deletes them in both screens (at once when empty, else after asking).</li>"
                + "<li><b>Settings</b> (the gear) holds the Layout and Capture Data's options. The tab has the Flow tab's "
                + "look. In the Compare window, <b>Open File…</b> puts a file on a side and <b>Export…</b> saves a side or "
                + "the differences; comparing pasted data shows the result in the same window (<b>Back to Edit</b> "
                + "returns).</li></ul>"

                + section(accent, "Walk-through")
                + "<ul><li><b>Next Step / Previous Step</b> (toolbar, or <b>Alt+]</b> / <b>Alt+[</b> anywhere) open "
                + "each saved line in the editor in turn, with the step number, method, tag and note in a hint.</li>"
                + "<li>Select a line (or right-click \u2192 <b>Walk from Here</b>) to continue from it. Also under "
                + "Tools \u2192 TraceWeave; rebind the keys in Settings \u2192 Keymap.</li></ul>"

                + section(accent, "Sync (call order)")
                + "<ul><li>When on, lines are kept in call order automatically: a method first, and the methods "
                + "it calls right after the line that calls them, across files (JSP pages calling Java too).</li>"
                + "<li>Moving anything by hand turns Sync off so your order is kept. Click <b>Sync</b> in the "
                + "toolbar (or the switch in Settings) to re-sort.</li>"
                + "<li>Only methods that have saved lines are linked. A configured project JDK gives the most "
                + "accurate results.</li></ul>"

                + section(accent, "Settings and copy formats")
                + "<ul><li><b>Default copy</b> — indented text (Grouped) or numbered steps (Flat).</li>"
                + "<li><b>Optimize Flat View Log</b> — in Flat View, a compact log with [project], file and "
                + "method headings and <code>line | code</code> rows.</li>"
                + "<li><b>Copy as Markdown</b> — a .md document: a summary table, Mermaid diagrams of the real "
                + "calls, each method's lines as a table (line number, syntax-highlighted code, tag and note; "
                + "GitHub shows the code without colors), and at the end an "
                + "<b>Original Log</b>: the lines as plain text. Only one of the two copy options can be on.</li>"
                + "<li><b>Nest Calls in Original Log</b> — each called method's lines go right under the line that "
                + "calls it, indented like a call stack, in Grouped and Flat View. Code lines sit one tab under "
                + "their method at every level, an empty line closes each called block, and the project is named only when it differs "
                + "from the caller's. Off: the plain file / method list or numbered steps.</li>"
                + "<li>Diagrams title each box with the file name (e.g. <code>travel.js</code>), give code that "
                + "doesn't call across its own diagram, and leave out fields and imports.</li>"
                + "<li><b>Sequence Diagram</b> — draw the calls as a Mermaid sequence diagram instead of a "
                + "flowchart, following the real calls whatever order the lines are listed in.</li>"
                + "<li><b>Open Markdown</b> (next to Copy) — opens the flow as a Markdown file with the preview, "
                + "diagrams included; all flows when Copy All Flows is on.</li>"
                + "<li><b>Copy All Flows</b> — the Copy button copies every flow, one after the other under its "
                + "name, in the Flows popup's order. Ctrl+C still copies the selected lines.</li>"
                + "<li><b>Use IntelliJ Theme</b> — take every TraceWeave color (panel, buttons, popups, highlighted "
                + "code and the Flow Diagram) from the current IntelliJ theme and editor color scheme. Off: Flow "
                + "Tracker's own colors.</li>"
                + "<li><b>Recycle Bin</b> and <b>Info</b> open from the same popup.</li>"
                + "<li>On by default: Sync Files, Copy as Markdown, Sequence Diagram, Copy All Flows and Nest Calls "
                + "in Original Log.</li></ul>"

                + section(accent, "Storage")
                + "<p>Flows, settings and the Recycle Bin are saved per project in <code>.idea/workspace.xml</code> "
                + "and survive restarts. Nothing leaves your machine unless you export a flow.</p>"
                + "</body></html>";
    }

    private static String section(String accent, String title) {
        return "<h3 style='color:" + accent + ";margin-top:10px;margin-bottom:0'>" + title + "</h3>";
    }
}
