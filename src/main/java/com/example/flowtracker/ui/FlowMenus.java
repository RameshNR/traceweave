package com.example.flowtracker.ui;

import com.intellij.util.ui.JBUI;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Dimension;

/** Small menus of the windows' buttons. */
public final class FlowMenus {
    private FlowMenus() {}

    /** A small menu above its button (for buttons at the bottom of a window), with room around each option. */
    public static void above(JComponent anchor, String[] labels, Runnable[] actions) {
        JPopupMenu menu = new JPopupMenu();
        menu.setBorder(JBUI.Borders.empty(4));
        for (int i = 0; i < labels.length; i++) {
            Runnable run = actions[i];
            JMenuItem item = new JMenuItem(labels[i]);
            item.setBorder(JBUI.Borders.empty(7, 14));
            item.addActionListener(e -> run.run());
            menu.add(item);
        }
        Dimension size = menu.getPreferredSize();
        menu.show(anchor, 0, -size.height - JBUI.scale(4));
    }
}
