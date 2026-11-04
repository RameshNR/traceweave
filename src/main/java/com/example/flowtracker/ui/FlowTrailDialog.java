package com.example.flowtracker.ui;

import com.example.flowtracker.model.FlowSnapshot;
import com.example.flowtracker.model.FlowValueTrail;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * "Along the Flow": a variable's saved values at each line of its flow, in the flow's order, with what changed from
 * the line before. Compare with Before (or a double-click) opens the two in the Compare window.
 */
final class FlowTrailDialog extends DialogWrapper {
    private static final JBColor SAME = new JBColor(0x6C707E, 0x8B93A3), CHANGED = new JBColor(0xB26A00, 0xE0A33A);

    private final @Nullable Project project;
    private final List<FlowValueTrail.Stop> stops;
    private final String variable, flow;
    private final JBTable table;

    static void show(@Nullable Project project, String variable, String flow, List<FlowValueTrail.Stop> stops) {
        new FlowTrailDialog(project, variable, flow, stops).show();
    }

    private FlowTrailDialog(@Nullable Project project, String variable, String flow, List<FlowValueTrail.Stop> stops) {
        super(project, false);
        this.project = project;
        this.stops = stops;
        this.variable = variable;
        this.flow = flow;
        table = new JBTable(new Model());
        setTitle("“" + variable + "” Along the Flow");
        setModal(false);
        setOKButtonText("Close");
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        JBLabel head = new JBLabel("“" + variable + "” at " + stops.size() + (stops.size() == 1 ? " line" : " lines") + " of " + flow);
        head.setFont(JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD, JBUI.Fonts.label().getSize2D() + 2f));
        JBLabel sub = new JBLabel("In the flow's order; each row says what changed since the row before (instance ids like @1141 "
                + "aren't counted). Double-click a row to compare it with the one before.");
        sub.setForeground(UIUtil.getContextHelpForeground());
        JPanel top = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        top.add(head, BorderLayout.NORTH);
        top.add(sub, BorderLayout.CENTER);
        top.setBorder(JBUI.Borders.emptyBottom(10));

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(JBUI.scale(26));
        table.setShowGrid(false);
        table.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(50));
        table.getColumnModel().getColumn(4).setPreferredWidth(JBUI.scale(190));
        table.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                FlowValueTrail.Stop s = stops.get(row);
                if (!sel) setForeground(row == 0 || s.same() ? SAME : CHANGED);
                return this;
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) compareWithBefore();
            }
        });
        if (stops.size() > 1) table.setRowSelectionInterval(1, 1);
        JBScrollPane scroll = FlowScroll.wheel(new JBScrollPane(table));
        JPanel p = new JPanel(new BorderLayout());
        p.add(top, BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        p.setPreferredSize(new Dimension(JBUI.scale(900), JBUI.scale(380)));
        return p;
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{new DialogWrapperAction("Compare with Before") {
            @Override protected void doAction(ActionEvent e) {
                compareWithBefore();
            }
        }, getOKAction()};
    }

    private void compareWithBefore() {
        int row = table.getSelectedRow();
        if (row <= 0) return;
        FlowSnapshot before = stops.get(row - 1).value(), now = stops.get(row).value();
        FlowCompareDialog.show(project, variable + " along " + flow, before, where(before), now, where(now));
    }

    private static String where(FlowSnapshot s) {
        return s.fileName + ":" + s.line + " · " + FlowDebugPanel.time(s.savedAt);
    }

    private final class Model extends AbstractTableModel {
        private final String[] columns = {"Step", "Line", "Saved", "Value", "Since the line before"};

        @Override public int getRowCount() { return stops.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int c) { return columns[c]; }

        @Override
        public Object getValueAt(int row, int col) {
            FlowValueTrail.Stop s = stops.get(row);
            FlowSnapshot v = s.value();
            return switch (col) {
                case 0 -> s.step() == 0 ? "–" : String.valueOf(s.step());
                case 1 -> v.fileName + ":" + v.line + (v.methodName.isEmpty() ? "" : "  " + v.methodName);
                case 2 -> FlowDebugPanel.time(v.savedAt);
                case 3 -> v.value == null ? "" : v.value.value;
                default -> row == 0 ? "first" : s.same() ? "the same"
                        : String.join(", ", List.of(s.changed() + " changed", s.added() + " added", s.removed() + " removed"));
            };
        }
    }
}
