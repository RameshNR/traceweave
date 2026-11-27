package com.example.flowtracker.ui;

import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import javax.swing.JComponent;
import java.awt.Point;

/** A window's keyboard shortcuts, in a popup under its "?" button (or a Settings row). */
public final class FlowShortcuts {
    private FlowShortcuts() {
    }

    public static final String[][] FLOW = {
            {"Ctrl+Alt+Shift+A", "Add the caret line (or the selection) to the flow, in the editor"},
            {"Alt+] / Alt+[", "Next / previous step of the walk-through, anywhere in the IDE"},
            {"Ctrl+C", "Copy the selected lines"},
            {"Delete", "Delete the selected lines (they go to the Recycle Bin)"},
            {"Ctrl+Up / Ctrl+Down", "Move the selected flow up / down, in the Flows popup"},
    };

    public static final String[][] DEBUG_DATA = {
            {"Ctrl+D", "Compare the selected value(s)"},
            {"Ctrl+F", "Find in the value (the field filter, or the JSON / text's search)"},
            {"Insert", "Add a field under the selected one"},
            {"F2", "Edit the selected field; rename a flow or line in the list"},
            {"Delete", "Remove the selected field, or move the selected values to the Recycle Bin"},
            {"Ctrl+C / Ctrl+V", "Copy the selected values or field / paste into a field"},
            {"Ctrl+Enter / Esc", "Apply / cancel an edit of the JSON or text"},
    };

    public static final String[][] STOP_AT_VALUE = {
            {"Ctrl+N / Insert", "Add Stop at Value"},
            {"Space", "Start or stop the selected record"},
            {"Enter / Ctrl+E", "Edit the selected record"},
            {"F2", "Rename the selected record"},
            {"Delete", "Delete the selected record(s) or stop(s): they go to the Recycle Bin"},
            {"Enter (in the stops)", "Open the stop's line (and the debugger's frame when it is still paused there)"},
            {"Ctrl+Shift+D", "Copy the selected stops' variables to Debug Data"},
    };

    public static final String[][] FLOW_DIAGRAM = {
            {"Ctrl+Z / Ctrl+Y", "Undo / redo"},
            {"F2 / Delete", "Rename / delete the selected class, method or call"},
            {"Ctrl+A", "Select every class"},
            {"Ctrl+C / Ctrl+X / Ctrl+V", "Copy, cut and paste classes (also into another tab)"},
            {"Ctrl+F", "Find a class or method"},
            {"Ctrl+Plus / Ctrl+Minus / Ctrl+0", "Zoom in / out / to 100% (or Ctrl + mouse wheel)"},
            {"Ctrl+click Close", "Close all the tabs (each with changes asks once)"},
    };

    public static final String[][] COMPARE = {
            {"Ctrl+S", "Save edits"},
            {"Ctrl+Z / Ctrl+Shift+Z", "Undo / redo"},
            {"Ctrl+Shift+X", "Swap Old and New"},
            {"Ctrl+F", "Filter the fields"},
    };

    /** The shortcuts in a popup under (or above) the component. */
    public static void show(JComponent under, String title, String[][] keys) {
        StringBuilder h = new StringBuilder("<html><div style='padding:2px 4px'><b>").append(StringUtil.escapeXmlEntities(title))
                .append("</b><table cellpadding='3' style='margin-top:6px'>");
        for (String[] k : keys) {
            String key = SystemInfo.isMac ? k[0].replace("Ctrl+", "⌘").replace("Alt+", "⌥").replace("Shift+", "⇧") : k[0];
            h.append("<tr><td style='white-space:nowrap'><code><b>").append(StringUtil.escapeXmlEntities(key)).append("</b></code></td><td>")
                    .append(StringUtil.escapeXmlEntities(k[1])).append("</td></tr>");
        }
        h.append("</table></div></html>");
        JBLabel label = new JBLabel(h.toString());
        label.setBorder(JBUI.Borders.empty(10, 12));
        JBPopupFactory.getInstance().createComponentPopupBuilder(label, null).setRequestFocus(true)
                .setCancelOnClickOutside(true).createPopup().show(new RelativePoint(under, new Point(0, under.getHeight() + JBUI.scale(6))));
    }

    /** A "?" button that shows them. */
    public static FlowButton button(String title, String[][] keys) {
        FlowButton b = new FlowButton("", com.intellij.icons.AllIcons.Actions.Help, FlowButton.Style.OUTLINE);
        b.setToolTipText("Keyboard shortcuts");
        b.addActionListener(e -> show(b, title, keys));
        return b;
    }
}
