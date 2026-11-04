package com.example.flowtracker.ui;

import com.intellij.util.ui.JBUI;

import javax.swing.JScrollPane;

/**
 * The mouse wheel in the plugin's lists, tables, trees and drawings: a notch moves about a line (a view that isn't
 * a list or a table, like the Flow Diagram's drawing, moved a pixel or two a notch before).
 */
public final class FlowScroll {
    /** How far a wheel notch (or an arrow of the scroll bar) moves. */
    static final int STEP = 20;

    private FlowScroll() {
    }

    /** The scroll pane, with a wheel that moves about a line a notch, up and down and sideways. */
    public static <T extends JScrollPane> T wheel(T scroll) {
        scroll.setWheelScrollingEnabled(true);
        scroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(STEP));
        scroll.getHorizontalScrollBar().setUnitIncrement(JBUI.scale(STEP));
        return scroll;
    }
}
