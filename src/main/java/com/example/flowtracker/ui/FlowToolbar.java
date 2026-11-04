package com.example.flowtracker.ui;

import com.intellij.util.ui.JBUI;

import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Toolbar of button groups separated by thin dividers. It adapts to the available width:
 * first everything is shown with labels; if that does not fit, the action buttons drop their labels,
 * then all buttons do; if it still does not fit, groups wrap onto extra lines.
 */
public final class FlowToolbar extends JPanel {

    /**
     * A run of buttons. Segmented groups are drawn inside one shared rounded container; trailing groups sit at the
     * right end of the toolbar.
     */
    private record Group(List<FlowButton> buttons, boolean segmented, int compactLevel, boolean trailing) {}

    private final List<Group> groups = new ArrayList<>();
    /** Width left in the parent for what sits beside the toolbar (e.g. a filter box), when it shares a row. */
    private int reserve;

    /** True when the buttons fit on one line in this width (icons only where needed). */
    public boolean fitsOneLine(int width) {
        Insets in = getInsets();
        int w = width - in.left - in.right;
        fitTo(w);
        return singleLineWidth() <= w;
    }

    /** Leaves this much of the parent's width to what is beside the toolbar: it squeezes (icons only) before that. */
    public void reserve(int px) {
        reserve = px;
    }
    /** Painted decorations from the last layout pass. */
    private final List<Rectangle> segmentBoxes = new ArrayList<>();
    private final List<Rectangle> dividers = new ArrayList<>();

    public FlowToolbar() {
        setOpaque(false);
        setLayout(new Layout());
        // narrower or wider: it may need more lines (or fewer), so what holds it gives it the height again
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                if (getLayout().preferredLayoutSize(FlowToolbar.this).height != getHeight()) {
                    javax.swing.SwingUtilities.invokeLater(FlowToolbar.this::revalidate);
                }
            }
        });
    }

    /** Worked out each time (not kept): it depends on the width the toolbar has now, and wraps to fit it. */
    @Override
    public Dimension getPreferredSize() {
        return isPreferredSizeSet() ? super.getPreferredSize() : getLayout().preferredLayoutSize(this);
    }

    /**
     * @param compactLevel the first squeeze level at which this group shows icons only
     *                     (1 = squeezed early, 2 = only when nothing else helps)
     */
    public void addGroup(boolean segmented, int compactLevel, FlowButton... buttons) {
        groups.add(new Group(List.of(buttons), segmented, compactLevel, false));
        for (FlowButton b : buttons) add(b);
    }

    /** A group placed at the right end of the toolbar (after the others, right-aligned). */
    public void addTrailingGroup(int compactLevel, FlowButton... buttons) {
        groups.add(new Group(List.of(buttons), false, compactLevel, true));
        for (FlowButton b : buttons) add(b);
    }

    private static int gap() { return JBUI.scale(6); }
    private static int segmentPad() { return JBUI.scale(3); }
    private static int dividerSpace() { return JBUI.scale(21); }
    private static int lineGap() { return JBUI.scale(8); }

    private void applyLevel(int level) {
        for (Group g : groups) {
            for (FlowButton b : g.buttons) b.setCompact(level >= g.compactLevel);
        }
    }

    private int groupWidth(Group g) {
        int w = 0;
        for (FlowButton b : g.buttons) w += b.getPreferredSize().width;
        int inner = g.segmented ? JBUI.scale(2) : gap();
        w += inner * Math.max(0, g.buttons.size() - 1);
        if (g.segmented) w += segmentPad() * 2;
        return w;
    }

    private int groupHeight(Group g) {
        int h = 0;
        for (FlowButton b : g.buttons) h = Math.max(h, b.getPreferredSize().height);
        // Plain groups get the same padding so they sit centered next to the taller segmented container.
        return h + segmentPad() * 2;
    }

    private int singleLineWidth() {
        int w = 0;
        for (int i = 0; i < groups.size(); i++) {
            if (i > 0) w += dividerSpace();
            w += groupWidth(groups.get(i));
        }
        return w;
    }

    /** Picks the least squeezed level that fits on one line (or the most squeezed one). */
    private void fitTo(int width) {
        for (int level = 0; level <= 2; level++) {
            applyLevel(level);
            if (singleLineWidth() <= width) return;
        }
    }

    /**
     * Places the groups for the given inner width, wrapping where needed.
     * When place is false only the resulting height is computed.
     */
    private int arrange(int left, int top, int width, boolean place) {
        if (place) {
            segmentBoxes.clear();
            dividers.clear();
        }
        int x = 0, y = 0, lineH = 0;
        List<Group> trailing = new ArrayList<>();
        for (Group g : groups) {
            if (g.trailing) {
                trailing.add(g);
                continue;
            }
            int gw = groupWidth(g), gh = groupHeight(g);
            if (x > 0 && x + dividerSpace() + gw > width) {
                y += lineH + lineGap();
                x = 0;
                lineH = 0;
            } else if (x > 0) {
                if (place) {
                    dividers.add(new Rectangle(left + x + dividerSpace() / 2, top + y, 1, 0));
                }
                x += dividerSpace();
            }
            if (place) placeGroup(g, left + x, top + y, gh);
            x += gw;
            lineH = Math.max(lineH, gh);
        }
        // The trailing groups: right-aligned on the last line, or on a line of their own when they don't fit.
        if (!trailing.isEmpty()) {
            int tw = 0, th = 0;
            for (Group g : trailing) {
                tw += (tw > 0 ? dividerSpace() : 0) + groupWidth(g);
                th = Math.max(th, groupHeight(g));
            }
            if (x > 0 && x + dividerSpace() + tw > width) {
                y += lineH + lineGap();
                x = 0;
                lineH = 0;
            }
            int tx = Math.max(x > 0 ? x + dividerSpace() : 0, width - tw);
            boolean divide = x > 0; // a divider before the first one only when other groups share its line
            for (Group g : trailing) {
                if (place) {
                    if (divide) dividers.add(new Rectangle(left + tx - dividerSpace() / 2, top + y, 1, 0));
                    divide = true;
                    placeGroup(g, left + tx, top + y, groupHeight(g));
                }
                tx += groupWidth(g) + dividerSpace();
            }
            lineH = Math.max(lineH, th);
        }
        // Dividers span the height of the line they sit on; every line here has the same height.
        if (place) for (Rectangle d : dividers) d.height = lineH;
        return y + lineH;
    }

    private void placeGroup(Group g, int gx, int gy, int gh) {
        if (g.segmented) segmentBoxes.add(new Rectangle(gx, gy, groupWidth(g), gh));
        int bx = gx + (g.segmented ? segmentPad() : 0);
        int by = gy + segmentPad();
        int inner = g.segmented ? JBUI.scale(2) : gap();
        for (FlowButton b : g.buttons) {
            Dimension d = b.getPreferredSize();
            b.setBounds(bx, by, d.width, d.height);
            bx += d.width + inner;
        }
    }

    /** Width the toolbar will get: the parent decides it, so use the parent's width when known. */
    private int availableWidth() {
        Container parent = getParent();
        Insets in = getInsets();
        int w;
        if (parent != null && parent.getWidth() > 0) {
            Insets pin = parent.getInsets();
            w = parent.getWidth() - pin.left - pin.right;
        } else {
            w = getWidth();
        }
        return w - in.left - in.right - (parent != null && parent.getWidth() > 0 ? reserve : 0);
    }

    private final class Layout implements LayoutManager {
        @Override public void addLayoutComponent(String name, Component comp) {}
        @Override public void removeLayoutComponent(Component comp) {}

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets in = getInsets();
            int width = availableWidth();
            if (width <= 0) {
                applyLevel(0);
                width = singleLineWidth();
            } else {
                fitTo(width);
            }
            return new Dimension(Math.min(width, singleLineWidth()) + in.left + in.right,
                    arrange(0, 0, width, false) + in.top + in.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets in = getInsets();
            int width = getWidth() - in.left - in.right;
            fitTo(width);
            arrange(in.left, in.top, width, true);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = JBUI.scale(12);
            for (Rectangle r : segmentBoxes) {
                g2.setColor(FlowTheme.SEGMENT_BG);
                g2.fillRoundRect(r.x, r.y, r.width, r.height, arc, arc);
                g2.setColor(FlowTheme.BTN_BORDER);
                g2.draw(new RoundRectangle2D.Float(r.x + 0.5f, r.y + 0.5f, r.width - 1f, r.height - 1f, arc, arc));
            }
            g2.setColor(FlowTheme.DIVIDER);
            int inset = JBUI.scale(6);
            for (Rectangle d : dividers) {
                g2.fillRect(d.x, d.y + inset, JBUI.scale(1), Math.max(0, d.height - inset * 2));
            }
        } finally {
            g2.dispose();
        }
    }
}
