package com.example.flowtracker.ui;

import com.example.flowtracker.model.FlowDebugStore;
import com.example.flowtracker.model.FlowSnapshot;
import com.example.flowtracker.model.FlowValue;
import com.example.flowtracker.model.FlowValueDiff;
import com.example.flowtracker.model.FlowValueShape;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * A variable's timeline: every time it was saved at its line (in any flow, any debug run), the oldest first, and what
 * changed from one save to the next. Compare with Previous opens the two in the Compare window.
 */
final class FlowTimelineDialog extends DialogWrapper {
    private final Project project;
    private final List<FlowSnapshot> saves;
    private final JBList<FlowSnapshot> list;
    private final JEditorPane changes = new JEditorPane();
    private final Action compare;

    /** One save and how it differs from the one before it (null for the first). */
    private record Step(int changed, int added, int removed) {}

    private final java.util.Map<FlowSnapshot, Step> steps = new java.util.IdentityHashMap<>();

    FlowTimelineDialog(Project project, FlowSnapshot of) {
        super(project, true);
        this.project = project;
        saves = new ArrayList<>(FlowDebugStore.get(project).sameVariable(of));
        saves.add(of);
        saves.sort(Comparator.comparingLong(s -> s.savedAt));
        for (int i = 1; i < saves.size(); i++) {
            int c = 0, a = 0, r = 0;
            for (FlowValueDiff.Row row : diff(saves.get(i - 1), saves.get(i))) {
                switch (row.kind()) {
                    case CHANGED -> c++;
                    case ADDED -> a++;
                    case REMOVED -> r++;
                    default -> { }
                }
            }
            steps.put(saves.get(i), new Step(c, a, r));
        }
        list = new JBList<>(saves);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        list.addListSelectionListener(e -> showChanges());
        compare = new AbstractAction("Compare with Previous") {
            @Override public void actionPerformed(ActionEvent e) { compareWithPrevious(); }
        };
        setTitle("Timeline of " + of.name + " at " + of.where());
        setOKButtonText("Close");
        changes.setEditable(false);
        changes.setContentType("text/html");
        changes.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        changes.setBorder(JBUI.Borders.empty(10, 12));
        init();
        list.setSelectedIndex(saves.size() - 1); // the latest: what changed last
    }

    /** What changed between two saves (the values as shown: without internals when they are hidden). */
    private static List<FlowValueDiff.Row> diff(FlowSnapshot before, FlowSnapshot after) {
        return FlowValueDiff.compare(shown(before.value), shown(after.value));
    }

    private static FlowValue shown(FlowValue v) {
        return FlowValueShape.hideInternals() ? FlowValueShape.withoutInternals(v) : v;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel p = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        JBLabel hint = new JBLabel("Every time this variable was saved at its line (any flow, any debug run), the oldest first. "
                + "Pick one to see what changed since the save before it.");
        hint.setForeground(UIUtil.getContextHelpForeground());
        p.add(hint, BorderLayout.NORTH);
        JBSplitter split = new JBSplitter(false, 0.42f);
        split.setFirstComponent(FlowScroll.wheel(new JBScrollPane(list)));
        split.setSecondComponent(FlowScroll.wheel(new JBScrollPane(changes)));
        p.add(split, BorderLayout.CENTER);
        p.setPreferredSize(new Dimension(JBUI.scale(900), JBUI.scale(480)));
        return p;
    }

    @Override
    protected Action[] createLeftSideActions() {
        return new Action[]{compare};
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction()};
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return list;
    }

    private void showChanges() {
        int i = list.getSelectedIndex();
        compare.setEnabled(i > 0);
        if (i < 0) {
            changes.setText("");
            return;
        }
        FlowSnapshot s = saves.get(i);
        StringBuilder h = new StringBuilder("<html><body style='font-family:sans-serif'>");
        h.append("<div style='font-size:110%;font-weight:bold'>").append(esc(s.name)).append(" · ").append(time(s.savedAt)).append("</div>");
        h.append("<div style='color:").append(hex(FlowTheme.FG_MUTED)).append(";margin-bottom:8px'>").append(esc(s.flowName))
                .append(" · ").append(esc(s.where())).append("</div>");
        if (i == 0) {
            h.append("<div>The first save of this variable here: nothing before it to compare with.</div>");
        } else {
            List<FlowValueDiff.Row> rows = diff(saves.get(i - 1), s);
            int shown = 0;
            for (FlowValueDiff.Row r : rows) {
                if (r.kind() == FlowValueDiff.Kind.SAME) continue;
                String mark, color;
                switch (r.kind()) {
                    case CHANGED -> { mark = "~"; color = "#C27C0E"; }
                    case ADDED -> { mark = "+"; color = "#1F8A3B"; }
                    default -> { mark = "−"; color = "#C9372C"; }
                }
                h.append("<div style='margin-top:3px'><b style='color:").append(color).append("'>").append(mark).append("</b> <b>")
                        .append(esc(r.label())).append("</b>: ");
                if (r.kind() == FlowValueDiff.Kind.CHANGED) {
                    h.append(esc(clip(r.left()))).append(" <span style='color:").append(hex(FlowTheme.FG_MUTED)).append("'>→</span> ")
                            .append(esc(clip(r.right())));
                } else {
                    h.append(esc(clip(r.kind() == FlowValueDiff.Kind.ADDED ? r.right() : r.left())));
                }
                h.append("</div>");
                if (++shown >= 300) {
                    h.append("<div style='color:").append(hex(FlowTheme.FG_MUTED)).append("'>… more: Compare with Previous shows them all</div>");
                    break;
                }
            }
            if (shown == 0) h.append("<div>The same data as the save before it.</div>");
        }
        changes.setText(h.append("</body></html>").toString());
        changes.setCaretPosition(0);
    }

    private void compareWithPrevious() {
        int i = list.getSelectedIndex();
        if (i <= 0) return;
        FlowSnapshot before = saves.get(i - 1), after = saves.get(i);
        FlowCompareDialog.show(project, "Compare " + after.name + " at " + after.where(), before,
                "Saved " + time(before.savedAt) + " · " + before.flowName, after, "Saved " + time(after.savedAt) + " · " + after.flowName);
    }

    private final class Renderer extends ColoredListCellRenderer<FlowSnapshot> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends FlowSnapshot> l, FlowSnapshot s, int index, boolean selected, boolean focus) {
            setBorder(JBUI.Borders.empty(5, 8));
            setIcon(AllIcons.Vcs.History);
            append(time(s.savedAt), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            append("   " + s.flowName, SimpleTextAttributes.GRAYED_ATTRIBUTES);
            Step st = steps.get(s);
            if (st == null) {
                append("   first save", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
            } else if (st.changed() + st.added() + st.removed() == 0) {
                append("   no change", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
            } else {
                append("   " + (st.changed() > 0 ? "~" + st.changed() + " " : "") + (st.added() > 0 ? "+" + st.added() + " " : "")
                        + (st.removed() > 0 ? "−" + st.removed() : ""), new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD,
                        new com.intellij.ui.JBColor(0xC27C0E, 0xE5A83B)));
            }
        }
    }

    private static String time(long at) {
        return new SimpleDateFormat("dd MMM HH:mm:ss").format(new Date(at));
    }

    private static String clip(@Nullable String s) {
        if (s == null) return "—";
        String t = FlowValueDiff.withoutIds(s);
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    private static String esc(@Nullable String s) {
        return s == null ? "" : StringUtil.escapeXmlEntities(s);
    }

    private static String hex(java.awt.Color c) {
        return String.format("#%06x", c.getRGB() & 0xFFFFFF);
    }
}
