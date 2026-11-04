package com.example.flowtracker.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.AsyncProcessIcon;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.geom.RoundRectangle2D;

/**
 * "Working on it" over an area (e.g. the Flow Diagram's drawing): the area dimmed, and a card in the middle with a
 * spinner, a title, a line that says how far it is, and Cancel. It takes the mouse while shown, so nothing under it
 * is clicked by mistake. Put it on top of the area (e.g. a JLayeredPane's modal layer) at the area's size.
 */
public final class FlowBusyOverlay extends JPanel {
    private final JBLabel title = new JBLabel("", SwingConstants.CENTER);
    private final JBLabel detail = new JBLabel("", SwingConstants.CENTER);
    private final JBLabel hint = new JBLabel("", SwingConstants.CENTER);
    private final JButton cancel = new JButton("Cancel");
    private final AsyncProcessIcon.Big spinner = new AsyncProcessIcon.Big("TraceWeave busy");
    private @Nullable Runnable onCancel;

    public FlowBusyOverlay(Disposable parent) {
        super(new GridBagLayout());
        setOpaque(false);
        setVisible(false);
        Disposer.register(parent, spinner);
        title.setFont(JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD, JBUI.Fonts.label().getSize2D() + 2f));
        title.setForeground(FlowTheme.FG);
        detail.setForeground(FlowTheme.FG);
        hint.setForeground(FlowTheme.FG_MUTED);
        hint.setFont(JBUI.Fonts.smallFont());
        cancel.addActionListener(e -> {
            cancel.setEnabled(false);
            cancel.setText("Stopping…");
            if (onCancel != null) onCancel.run();
        });

        JPanel card = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int arc = JBUI.scale(16);
                    g2.setColor(new JBColor(new Color(0, 0, 0, 28), new Color(0, 0, 0, 90)));
                    g2.fillRoundRect(JBUI.scale(2), JBUI.scale(4), getWidth() - JBUI.scale(4), getHeight() - JBUI.scale(4), arc, arc);
                    g2.setColor(FlowTheme.CARD_BG);
                    g2.fillRoundRect(0, 0, getWidth() - JBUI.scale(2), getHeight() - JBUI.scale(4), arc, arc);
                    g2.setColor(FlowTheme.CARD_BORDER);
                    g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - JBUI.scale(2) - 1f, getHeight() - JBUI.scale(4) - 1f, arc, arc));
                } finally {
                    g2.dispose();
                }
            }
        };
        card.setOpaque(false);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(JBUI.scale(22), JBUI.scale(32), JBUI.scale(22), JBUI.scale(32)));
        for (JComponent c : new JComponent[]{spinner, title, detail, hint, cancel}) c.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(spinner);
        card.add(Box.createVerticalStrut(JBUI.scale(14)));
        card.add(title);
        card.add(Box.createVerticalStrut(JBUI.scale(6)));
        card.add(detail);
        card.add(Box.createVerticalStrut(JBUI.scale(4)));
        card.add(hint);
        card.add(Box.createVerticalStrut(JBUI.scale(16)));
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setOpaque(false);
        buttons.add(cancel, BorderLayout.CENTER);
        buttons.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(buttons);
        add(card);
        // the mouse stays here while it is shown
        addMouseListener(new MouseAdapter() {});
        addMouseMotionListener(new MouseAdapter() {});
        addMouseWheelListener(e -> {});
    }

    /** Shows it. @param onCancel what Cancel does (null: no Cancel button) */
    public void start(String titleText, String detailText, String hintText, @Nullable Runnable onCancel) {
        this.onCancel = onCancel;
        title.setText(titleText);
        detail.setText(detailText);
        hint.setText(hintText);
        hint.setVisible(!hintText.isEmpty());
        cancel.setVisible(onCancel != null);
        cancel.setEnabled(true);
        cancel.setText("Cancel");
        spinner.resume();
        setVisible(true);
        revalidate();
        repaint();
    }

    /** How far it is (e.g. "120 methods read · 2 ways found"). */
    public void progress(String detailText) {
        detail.setText(detailText);
    }

    public void stop() {
        spinner.suspend();
        setVisible(false);
        onCancel = null;
    }

    public boolean running() {
        return isVisible();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
            g2.setColor(FlowTheme.PANEL_BG);
            g2.fillRect(0, 0, getWidth(), getHeight());
        } finally {
            g2.dispose();
        }
        super.paintComponent(g);
    }
}
