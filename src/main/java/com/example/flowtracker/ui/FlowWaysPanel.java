package com.example.flowtracker.ui;

import com.example.flowtracker.code.FlowCallPath;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import org.jetbrains.annotations.Nullable;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The ways Connect the Flow found, on the Flow Diagram (above its zoom buttons). It stays there: open (hover a way to
 * see it drawn on the diagram, click it to add it) or minimized to a slim bar (a click opens it). Its buttons, like a
 * window's: Minimize (or Open) and Full Screen (the ways in a large window).
 */
public final class FlowWaysPanel extends JPanel {
    private static final JBColor ACCENT = FlowWaysList.NEW;
    /** Room under the card for its shadow (the card is drawn above it). */
    private static final int SHADOW = 4;

    private final FlowWaysList list;
    private final JPanel open = new JPanel(new BorderLayout(0, JBUI.scale(10)));
    private final JPanel bar = new JPanel(new BorderLayout(JBUI.scale(10), 0));
    private final Runnable onResize;
    private boolean minimized;
    /** True while (and right after) the panel was dragged: the release isn't a click. */
    private boolean dragged;
    /** Moves the panel on the diagram by so much (set by the diagram). */
    private @Nullable Consumer<java.awt.Point> onMove;

    /** Lets the panel be dragged: called with how far the mouse moved. */
    public void onMove(Consumer<java.awt.Point> move) {
        onMove = move;
    }

    /**
     * @param onHover      the way the mouse is over (null: none: the preview goes)
     * @param onPick       the way clicked (it is added)
     * @param onResize     the panel was minimized or opened (its place is laid out again)
     * @param onFullScreen Full Screen: the ways in the large window
     */
    public FlowWaysPanel(FlowCallPath.Ways ways, String from, String to, Function<FlowCallPath.Step, FlowWaysList.Where> where,
                         Consumer<FlowCallPath.Way> onHover, Consumer<FlowCallPath.Way> onPick, Runnable onResize,
                         Runnable onFullScreen) {
        super(new BorderLayout());
        this.onResize = onResize;
        setOpaque(false);
        int n = ways.ways().size();
        String count = n + (n == 1 ? " way" : " ways");

        // ---- open: WAYS · the title and hint · Minimize, Full Screen; the ways
        JBLabel tag = chip("WAYS");
        JBLabel title = new JBLabel("<html><b>" + esc(count) + "</b> from " + esc(from) + " to " + esc(to) + "</html>");
        title.setForeground(FlowTheme.FG);
        JBLabel hint = new JBLabel("Hover a way to see it on the diagram · click it to add it");
        hint.setFont(JBUI.Fonts.smallFont());
        hint.setForeground(FlowTheme.FG_MUTED);
        JPanel texts = new JPanel();
        texts.setOpaque(false);
        texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{tag, title, hint}) c.setAlignmentX(Component.LEFT_ALIGNMENT);
        texts.add(tag);
        texts.add(Box.createVerticalStrut(JBUI.scale(4)));
        texts.add(title);
        texts.add(Box.createVerticalStrut(JBUI.scale(2)));
        texts.add(hint);
        JPanel head = new JPanel(new BorderLayout(JBUI.scale(10), 0));
        head.setOpaque(false);
        head.add(texts, BorderLayout.CENTER);
        // the buttons at the top, level with the WAYS line (not in the middle of the title)
        JPanel topRight = new JPanel(new BorderLayout());
        topRight.setOpaque(false);
        topRight.setBorder(JBUI.Borders.emptyTop(-4));
        topRight.add(buttons(new IconButton(AllIcons.General.ChevronDown, "Minimize to a bar (a click on it opens it again)",
                () -> setMinimized(true)), new IconButton(AllIcons.General.FitContent, "Full screen: the ways in a large window",
                onFullScreen)), BorderLayout.NORTH);
        head.add(topRight, BorderLayout.EAST);
        list = new FlowWaysList(ways, where, "this tab");
        list.pickOnClick(true);
        list.onHover(onHover);
        list.onPick(onPick);
        open.setOpaque(false);
        open.setBorder(JBUI.Borders.empty(12, 18, 14, 12));
        open.add(head, BorderLayout.NORTH);
        open.add(list, BorderLayout.CENTER);

        // ---- minimized: one line with the number of ways and where they go; a click (or Open) opens it
        JBLabel icon = new JBLabel(FlowTheme.ICON_CONNECT);
        JBLabel number = new JBLabel(count);
        number.setFont(JBUI.Fonts.label().asBold());
        number.setForeground(FlowTheme.FG);
        JBLabel route = new JBLabel(StringUtil.shortenTextWithEllipsis(from + "  →  " + to, 48, 16));
        route.setForeground(FlowTheme.FG_MUTED);
        route.setMinimumSize(new Dimension(0, 0)); // cut with "…", never on two lines
        String tip = count + " from " + from + " to " + to + ": click to see them";
        for (JComponent c : new JComponent[]{bar, icon, number, route}) c.setToolTipText(tip);
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.X_AXIS));
        text.add(icon);
        text.add(Box.createHorizontalStrut(JBUI.scale(10)));
        text.add(number);
        text.add(Box.createHorizontalStrut(JBUI.scale(8)));
        text.add(route);
        bar.setOpaque(false);
        bar.setBorder(JBUI.Borders.empty(0, 16, SHADOW, 6));
        bar.add(center(text, true), BorderLayout.CENTER);
        bar.add(buttons(new IconButton(AllIcons.General.ChevronUp, "Open the ways", () -> setMinimized(false)),
                new IconButton(AllIcons.General.FitContent, "Full screen: the ways in a large window", onFullScreen)), BorderLayout.EAST);
        bar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter openOnClick = new MouseAdapter() {
            @Override public void mouseReleased(MouseEvent e) {
                if (dragged) return; // it was moved, not clicked
                if (javax.swing.SwingUtilities.isLeftMouseButton(e) && e.getComponent().contains(e.getPoint())) {
                    javax.swing.SwingUtilities.invokeLater(() -> setMinimized(false));
                }
            }
        };
        for (JComponent c : new JComponent[]{bar, text, icon, number, route}) c.addMouseListener(openOnClick);

        // dragged by its header (open) or by the bar (minimized): it moves on the diagram
        MouseAdapter drag = new MouseAdapter() {
            private @Nullable java.awt.Point from;

            @Override public void mousePressed(MouseEvent e) {
                if (!javax.swing.SwingUtilities.isLeftMouseButton(e)) return;
                from = e.getLocationOnScreen();
                dragged = false;
            }

            @Override public void mouseDragged(MouseEvent e) {
                java.awt.Point was = from;
                if (was == null) return;
                java.awt.Point now = e.getLocationOnScreen();
                int dx = now.x - was.x, dy = now.y - was.y;
                if (!dragged && Math.abs(dx) + Math.abs(dy) < JBUI.scale(4)) return; // a click that shook a little
                dragged = true;
                from = now;
                if (onMove != null) onMove.accept(new java.awt.Point(dx, dy));
            }

            @Override public void mouseReleased(MouseEvent e) {
                from = null;
            }
        };
        for (JComponent c : new JComponent[]{head, texts, tag, title, hint, bar, text, icon, number, route}) {
            c.addMouseListener(drag);
            c.addMouseMotionListener(drag);
        }
        for (JComponent c : new JComponent[]{head, texts, tag, title, hint}) c.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));

        add(open, BorderLayout.CENTER);
        addMouseListener(new MouseAdapter() {}); // clicks stay on the panel
    }

    /** A small label in the accent color ("WAYS"). */
    private static JBLabel chip(String text) {
        JBLabel l = new JBLabel(text);
        l.setFont(JBUI.Fonts.smallFont().asBold());
        l.setForeground(ACCENT);
        return l;
    }

    /** Buttons side by side after a thin line, centred up and down in their space. */
    private static JPanel buttons(IconButton... bs) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.add(new Divider());
        row.add(Box.createHorizontalStrut(JBUI.scale(4)));
        for (int i = 0; i < bs.length; i++) {
            if (i > 0) row.add(Box.createHorizontalStrut(JBUI.scale(2)));
            row.add(bs[i]);
        }
        return center(row, false);
    }

    /** @param fill true: it takes the width it gets (text cut with "…"), left-aligned */
    private static JPanel center(JComponent c, boolean fill) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        java.awt.GridBagConstraints g = new java.awt.GridBagConstraints();
        g.anchor = java.awt.GridBagConstraints.WEST;
        g.weightx = fill ? 1 : 0;
        g.fill = fill ? java.awt.GridBagConstraints.HORIZONTAL : java.awt.GridBagConstraints.NONE;
        p.add(c, g);
        return p;
    }

    /** A short upright line between the text and the buttons. */
    private static final class Divider extends JComponent {
        Divider() { setAlignmentY(Component.CENTER_ALIGNMENT); }
        @Override public Dimension getPreferredSize() { return new Dimension(JBUI.scale(9), JBUI.scale(18)); }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        @Override protected void paintComponent(Graphics g) {
            g.setColor(FlowTheme.CARD_BORDER);
            int x = getWidth() / 2;
            g.fillRect(x, 0, Math.max(1, JBUI.scale(1)), getHeight());
        }
    }

    private static String esc(String s) {
        return StringUtil.escapeXmlEntities(s);
    }

    /** How to tell a way that is on the tab already (it is hidden, or shown greyed out). */
    public void setThere(java.util.function.Predicate<FlowCallPath.Way> test) {
        list.setThere(test);
    }

    public boolean isMinimized() {
        return minimized;
    }

    /** Minimized: only the bar (the preview goes); open: the ways. */
    public void setMinimized(boolean min) {
        if (min == minimized) return;
        minimized = min;
        if (min) list.waysList().clearSelection();
        removeAll();
        add(min ? bar : open, BorderLayout.CENTER);
        revalidate();
        repaint();
        onResize.run();
    }

    @Override
    public Dimension getPreferredSize() {
        if (minimized) {
            Dimension d = bar.getPreferredSize();
            return new Dimension(Math.min(JBUI.scale(460), d.width + JBUI.scale(6)), JBUI.scale(44));
        }
        return new Dimension(JBUI.scale(480), JBUI.scale(400));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth() - JBUI.scale(3), h = getHeight() - JBUI.scale(4);
            int arc = minimized ? h : JBUI.scale(14);
            // a soft shadow, the card, a quiet border and the accent stripe on the left
            g2.setColor(new JBColor(new Color(0, 0, 0, 22), new Color(0, 0, 0, 90)));
            g2.fillRoundRect(JBUI.scale(2), JBUI.scale(3), w, h, arc, arc);
            g2.setColor(FlowTheme.CARD_BG);
            g2.fillRoundRect(0, 0, w, h, arc, arc);
            g2.setColor(FlowTheme.CARD_BORDER);
            g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
            g2.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, w, h, arc, arc));
            g2.setColor(ACCENT);
            g2.fillRect(0, 0, JBUI.scale(minimized ? 5 : 4), h);
        } finally {
            g2.dispose();
        }
    }

    /** A small round icon button: a soft circle under the mouse. */
    private static final class IconButton extends JComponent {
        private final Icon icon;
        private boolean hover;

        IconButton(Icon icon, String tip, Runnable run) {
            this.icon = icon;
            setToolTipText(tip);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setAlignmentY(Component.CENTER_ALIGNMENT);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { e.consume(); }
                // released over the button (a click that moved a little still counts): the circle goes, then it acts
                @Override public void mouseReleased(MouseEvent e) {
                    e.consume();
                    if (!javax.swing.SwingUtilities.isLeftMouseButton(e) || !contains(e.getPoint())) return;
                    hover = false;
                    repaint();
                    javax.swing.SwingUtilities.invokeLater(run);
                }
            });
        }

        @Override public Dimension getPreferredSize() { return new Dimension(JBUI.scale(30), JBUI.scale(30)); }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int d = Math.min(getWidth(), getHeight());
                int x = (getWidth() - d) / 2, y = (getHeight() - d) / 2;
                g2.setColor(hover ? FlowTheme.BTN_HOVER : new JBColor(new Color(0, 0, 0, 0), new Color(0, 0, 0, 0)));
                g2.fillOval(x, y, d - 1, d - 1);
                if (hover) {
                    g2.setColor(FlowTheme.CARD_BORDER);
                    g2.drawOval(x, y, d - 1, d - 1);
                }
                icon.paintIcon(this, g2, (getWidth() - icon.getIconWidth()) / 2, (getHeight() - icon.getIconHeight()) / 2);
            } finally {
                g2.dispose();
            }
        }
    }
}
