package com.example.flowtracker.ui;

import com.example.flowtracker.code.FlowCallPath;
import com.example.flowtracker.code.FlowLang;
import com.example.flowtracker.copy.CodeHighlighter;
import com.example.flowtracker.model.FlowEntry;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.List;
import java.util.function.Function;

/**
 * "Choose a way" (Flow tab and editor): the ways Connect the Flow found, as a {@link FlowWaysList} (what is already in
 * the flow marked, filters), and on the right the picked way's methods with the lines it adds, in the code's colors.
 * Double-click (or the OK button) takes it.
 */
public final class FlowWaysDialog extends DialogWrapper {
    private final FlowCallPath.Ways ways;
    private final FlowWaysList ways_;
    private final Function<FlowCallPath.Step, FlowWaysList.Where> where;
    private final JEditorPane details = new JEditorPane();
    private final String from, to, there;
    private @Nullable Component parent;

    /**
     * @param where  where each step is: in the flow (its method, its class) or new
     * @param there  what that is, for the filter ("the flow", "this tab")
     * @param action the OK button's text, e.g. "Add This Way"
     * @return the way picked, or null when cancelled
     */
    public static @Nullable FlowCallPath.Way choose(@Nullable Project project, @Nullable Component parent, FlowCallPath.Ways ways,
                                                    String from, String to, String action,
                                                    Function<FlowCallPath.Step, FlowWaysList.Where> where, String there) {
        return choose(project, parent, ways, from, to, action, where, there, null);
    }

    /** @param onTab how to tell a way that is there already (null: every method of it is) */
    public static @Nullable FlowCallPath.Way choose(@Nullable Project project, @Nullable Component parent, FlowCallPath.Ways ways,
                                                    String from, String to, String action,
                                                    Function<FlowCallPath.Step, FlowWaysList.Where> where, String there,
                                                    @Nullable java.util.function.Predicate<FlowCallPath.Way> onTab) {
        return choose(project, parent, ways, from, to, action, where, there, onTab, null);
    }

    /**
     * @param onHover the way the mouse is over, to show it on the diagram behind the window (null: none; the window
     *                closing tells null too)
     */
    public static @Nullable FlowCallPath.Way choose(@Nullable Project project, @Nullable Component parent, FlowCallPath.Ways ways,
                                                    String from, String to, String action,
                                                    Function<FlowCallPath.Step, FlowWaysList.Where> where, String there,
                                                    @Nullable java.util.function.Predicate<FlowCallPath.Way> onTab,
                                                    @Nullable java.util.function.Consumer<FlowCallPath.Way> onHover) {
        FlowWaysDialog d = parent != null ? new FlowWaysDialog(parent, ways, from, to, action, where, there)
                : new FlowWaysDialog(project, ways, from, to, action, where, there);
        if (onTab != null) d.ways_.setThere(onTab);
        if (onHover != null) d.ways_.onHover(onHover);
        try {
            return d.showAndGet() ? d.ways_.waysList().getSelectedValue() : null;
        } finally {
            if (onHover != null) onHover.accept(null); // the window is gone: no preview left on the diagram
        }
    }

    private FlowWaysDialog(@Nullable Project project, FlowCallPath.Ways ways, String from, String to, String action,
                           Function<FlowCallPath.Step, FlowWaysList.Where> where, String there) {
        super(project, true);
        this.ways = ways;
        this.where = where;
        this.from = from;
        this.to = to;
        this.there = there;
        this.ways_ = new FlowWaysList(ways, where, there);
        setup(action);
    }

    private FlowWaysDialog(Component parent, FlowCallPath.Ways ways, String from, String to, String action,
                           Function<FlowCallPath.Step, FlowWaysList.Where> where, String there) {
        super(parent, true);
        this.parent = parent;
        this.ways = ways;
        this.where = where;
        this.from = from;
        this.to = to;
        this.there = there;
        this.ways_ = new FlowWaysList(ways, where, there);
        setup(action);
    }

    private void setup(String action) {
        setTitle("Connect the Flow: Choose a Way");
        setOKButtonText(action);
        ways_.waysList().addListSelectionListener(e -> {
            showDetails();
            setOKActionEnabled(ways_.waysList().getSelectedValue() != null); // nothing picked: nothing to add
        });
        ways_.onPick(w -> doOKAction());
        details.setEditable(false);
        details.setContentType("text/html");
        details.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        details.setBorder(JBUI.Borders.empty(10, 14));
        details.setBackground(FlowTheme.CARD_BG);
        init();
        showDetails();
    }

    @Override
    protected JComponent createCenterPanel() {
        int n = ways.ways().size();
        JBLabel head = new JBLabel(n + (n == 1 ? " way" : " ways") + " from " + from + " to " + to);
        head.setFont(JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD, JBUI.Fonts.label().getSize2D() + 2f));
        JBLabel sub = new JBLabel("<html>The first one carries what the start works with (it is the closest). What is in " + there
                + " already is marked; pick a way to see the lines it adds, double-click to take it.</html>");
        sub.setForeground(UIUtil.getContextHelpForeground());
        JPanel top = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        top.add(head, BorderLayout.NORTH);
        top.add(sub, BorderLayout.CENTER);
        top.setBorder(JBUI.Borders.emptyBottom(10));

        JBScrollPane right = FlowScroll.wheel(new JBScrollPane(details));
        right.setBorder(JBUI.Borders.customLine(FlowTheme.CARD_BORDER));
        JBSplitter split = new JBSplitter(false, 0.45f);
        split.setFirstComponent(ways_);
        split.setSecondComponent(right);

        JPanel p = new JPanel(new BorderLayout());
        p.add(top, BorderLayout.NORTH);
        p.add(split, BorderLayout.CENTER);
        // large: most of the window it is opened over (the Flow Diagram's), else a roomy size
        java.awt.Window over = parent == null ? null : javax.swing.SwingUtilities.getWindowAncestor(parent);
        p.setPreferredSize(over != null && over.getWidth() > 0
                ? new Dimension((int) (over.getWidth() * 0.85), (int) (over.getHeight() * 0.8))
                : new Dimension(JBUI.scale(1040), JBUI.scale(600)));
        return p;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return ways_.waysList();
    }

    /** The picked way: each method (on the flow already, or new) with the lines it adds, in the code's colors. */
    private void showDetails() {
        FlowCallPath.Way w = ways_.waysList().getSelectedValue();
        details.setText(w == null ? "" : html(w, ways_.number(w), where, there));
        details.setCaretPosition(0);
    }

    /** A way's details as HTML (also for the Flow Diagram's preview). */
    static String html(FlowCallPath.Way w, int number, Function<FlowCallPath.Step, FlowWaysList.Where> where, String there) {
        String font = EditorColorsManager.getInstance().getGlobalScheme().getEditorFontName();
        String fg = hex(FlowTheme.FG), muted = hex(FlowTheme.FG_MUTED), accent = hex(FlowTheme.ACCENT), lineNo = hex(FlowTheme.LINE_NO);
        String codeBg = hex(FlowTheme.CODE_BG), border = hex(FlowTheme.CARD_BORDER), via = hex(FlowTheme.WARNING);
        StringBuilder h = new StringBuilder("<html><body style='font-family:sans-serif;color:" + fg + "'>");
        h.append("<div style='font-size:110%;font-weight:bold'>Way ").append(number).append("</div>");
        int calls = w.calls(), lines = w.lines().size();
        h.append("<div style='color:").append(muted).append(";margin-top:2px;margin-bottom:8px'>").append(calls)
                .append(calls == 1 ? " call" : " calls").append(" · ").append(lines).append(lines == 1 ? " line" : " lines")
                .append(w.carries().isEmpty() ? " · doesn't use the start's data" : " · carries " + esc(String.join(", ", w.carries())))
                .append("</div>");
        List<FlowCallPath.Step> steps = w.steps();
        for (int s = 0; s < steps.size(); s++) {
            FlowCallPath.Step st = steps.get(s);
            boolean last = s == steps.size() - 1;
            FlowWaysList.Where at = where.apply(st);
            String mark = at == FlowWaysList.Where.NEW ? "<span style='color:" + hex(FlowWaysList.NEW) + "'>✚ new</span>"
                    : "<span style='color:" + hex(FlowWaysList.THERE) + "'>● " + (at == FlowWaysList.Where.METHOD_THERE ? "on " + there
                    : "its class is on " + there) + "</span>";
            h.append("<div style='margin-top:8px'><span style='color:").append(accent).append(";font-weight:bold'>")
                    .append(s + 1).append(".</span> <b>").append(esc(st.label())).append("</b> <span style='color:").append(muted)
                    .append("'>").append(esc(st.fileName())).append("</span> &nbsp;").append(mark);
            if (!st.via().isEmpty()) h.append(" <span style='color:").append(via).append("'>· ").append(esc(st.via())).append("</span>");
            h.append("</div>");
            if (last) {
                h.append("<div style='color:").append(muted).append(";margin-left:18px'>the end of the way</div>");
                continue;
            }
            if (st.lines().isEmpty()) {
                h.append("<div style='color:").append(muted).append(";margin-left:18px'>the start line makes the call</div>");
                continue;
            }
            h.append("<table cellspacing='0' cellpadding='3' style='margin-left:16px;margin-top:3px;background:").append(codeBg)
                    .append(";border:1px solid ").append(border).append("'>");
            for (FlowEntry e : st.lines()) {
                h.append("<tr><td align='right' style='color:").append(lineNo).append(";font-family:").append(font).append("'>")
                        .append(e.line).append("</td><td style='font-family:").append(font).append("'>").append(code(e)).append("</td></tr>");
            }
            h.append("</table>");
        }
        return h.append("</body></html>").toString();
    }

    private static String code(FlowEntry e) {
        StringBuilder out = new StringBuilder();
        for (CodeHighlighter.Span span : CodeHighlighter.highlight(e.text.strip(), FlowLang.kind(e.fileName))) {
            out.append("<span style='color:").append(hex(span.color())).append("'>").append(esc(span.text())).append("</span>");
        }
        return out.toString();
    }

    private static String esc(String s) {
        return StringUtil.escapeXmlEntities(s);
    }

    private static String hex(java.awt.Color c) {
        return FlowWaysList.hex(c);
    }
}
