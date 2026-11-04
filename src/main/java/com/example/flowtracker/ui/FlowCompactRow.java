package com.example.flowtracker.ui;

import javax.swing.JPanel;
import java.awt.LayoutManager;
import java.util.List;

/**
 * A row of buttons and other parts that shows buttons with their icons only (the label in the tooltip) when it is too
 * narrow for them all, like the toolbars: the buttons given first are squeezed first, and each one only when needed.
 */
final class FlowCompactRow extends JPanel {
    private List<FlowButton> squeeze = List.of();

    FlowCompactRow(LayoutManager layout) {
        super(layout);
        setOpaque(false);
        // a row that wraps (a FlowLayout) needs another height when its width changes
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                if (getLayout() instanceof java.awt.FlowLayout && getLayout().preferredLayoutSize(FlowCompactRow.this).height != getHeight()) {
                    javax.swing.SwingUtilities.invokeLater(FlowCompactRow.this::revalidate);
                }
            }
        });
    }

    /** Worked out each time for a row that wraps: its height depends on its width now. */
    @Override
    public java.awt.Dimension getPreferredSize() {
        return !isPreferredSizeSet() && getLayout() instanceof java.awt.FlowLayout ? getLayout().preferredLayoutSize(this) : super.getPreferredSize();
    }

    /** The width it needs on one line. */
    private int oneLineWidth() {
        if (!(getLayout() instanceof java.awt.FlowLayout f)) return getPreferredSize().width;
        java.awt.Insets in = getInsets();
        int w = in.left + in.right + f.getHgap();
        for (java.awt.Component c : getComponents()) if (c.isVisible()) w += c.getPreferredSize().width + f.getHgap();
        return w;
    }

    /** The buttons that may show their icons only, in the order they are squeezed. */
    void squeeze(FlowButton... buttons) {
        squeeze = List.of(buttons);
    }

    @Override
    public void doLayout() {
        fit();
        super.doLayout();
    }

    private void fit() {
        int width = getWidth();
        if (width <= 0 || squeeze.isEmpty()) return;
        for (FlowButton b : squeeze) compact(b, false);
        for (FlowButton b : squeeze) {
            if (oneLineWidth() <= width) return;
            compact(b, true);
        }
    }

    private static void compact(FlowButton b, boolean on) {
        if (b.isCompact() == on) return;
        b.setCompact(on);
        b.invalidate(); // its new size counts in the row's preferred width
    }
}
