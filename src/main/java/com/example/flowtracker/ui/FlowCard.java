package com.example.flowtracker.ui;

import com.intellij.util.ui.JBUI;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * The rounded surface the Flow tab's list sits on (and Debug Data's list and value), over the panel background:
 * the look of the tool window's screens.
 */
public final class FlowCard extends JPanel {

    public FlowCard() {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(JBUI.Borders.empty(6));
    }

    /** A card around a component; a scroll pane in it loses its own border and shows the card's color. */
    public static FlowCard of(Component content) {
        FlowCard card = new FlowCard();
        if (content instanceof JScrollPane scroll) plain(scroll);
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    /** A scroll pane drawn on a card: no border, the card's color behind its content. */
    public static <T extends JScrollPane> T plain(T scroll) {
        scroll.setBorder(JBUI.Borders.empty());
        scroll.setViewportBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        Component view = scroll.getViewport().getView();
        if (view instanceof JComponent c) c.setBackground(FlowTheme.CARD_BG);
        return scroll;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = JBUI.scale(14);
            g2.setColor(FlowTheme.CARD_BG);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            g2.setColor(FlowTheme.CARD_BORDER);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, getHeight() - 1f, arc, arc));
        } finally {
            g2.dispose();
        }
    }
}
