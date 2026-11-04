package com.example.flowtracker.ui;

import com.intellij.icons.AllIcons;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Rounded button with hover and pressed looks, in one of three styles:
 * SEGMENT (part of a segmented switch, filled with the accent color when selected),
 * PRIMARY (always accent-filled) and OUTLINE (bordered, for normal actions; filled when selected,
 * which makes it an on/off toggle).
 * In compact mode only the icon is shown and the label moves to the tooltip.
 * <p>
 * With {@link #setDropDown} it is a split button: a ▾ part on its right runs another action (e.g. a menu of
 * variants), while the rest of the button runs the normal one.
 */
public final class FlowButton extends JComponent {
    public enum Style { SEGMENT, PRIMARY, OUTLINE }

    private final Style style;
    private String text;
    private @Nullable Icon icon;
    private String tooltip;
    private boolean selected;
    private boolean compact;
    private boolean hover;
    private boolean pressed;
    /** The mouse is over (or pressed on) the ▾ part of a split button. */
    private boolean overArrow;
    private @Nullable Runnable dropDown;
    private @Nullable String dropDownTip;
    private final List<ActionListener> listeners = new ArrayList<>();
    /** An outline button's own color (its border, icon and label), e.g. green for Start; null: the usual. */
    private @Nullable Color accent;

    public void setAccent(@Nullable Color accent) {
        this.accent = accent;
        repaint();
    }

    public FlowButton(String text, @Nullable Icon icon, Style style) {
        this.text = text;
        this.icon = icon;
        this.style = style;
        setFont(JBUI.Fonts.label());
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
            @Override public void mouseExited(MouseEvent e) { hover = false; pressed = false; overArrow = false; repaint(); }

            @Override
            public void mousePressed(MouseEvent e) {
                if (isEnabled() && SwingUtilities.isLeftMouseButton(e)) {
                    pressed = true;
                    overArrow = inArrow(e.getX());
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                boolean fire = pressed && isEnabled() && contains(e.getPoint());
                boolean arrow = overArrow && inArrow(e.getX());
                pressed = false;
                repaint();
                if (fire && arrow && dropDown != null) dropDown.run();
                else if (fire && !arrow) fireAction();
            }
        });
        addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                boolean a = inArrow(e.getX());
                if (a != overArrow) {
                    overArrow = a;
                    FlowButton.super.setToolTipText(a && dropDownTip != null ? dropDownTip : currentTooltip());
                    repaint();
                }
            }
        });
    }

    /**
     * Makes this a split button: clicking the ▾ part on the right runs {@code action} (it gets this button, e.g.
     * to show a menu under it) instead of the button's action.
     */
    public void setDropDown(String tip, Runnable action) {
        dropDown = action;
        dropDownTip = tip;
        revalidate();
        repaint();
    }

    private int arrowWidth() {
        return dropDown == null ? 0 : JBUI.scale(22);
    }

    private boolean inArrow(int x) {
        return dropDown != null && x >= getWidth() - arrowWidth();
    }

    public void addActionListener(ActionListener l) { listeners.add(l); }

    public void setText(String text) {
        this.text = text;
        updateTooltip();
        revalidate();
        repaint();
    }

    public void setIcon(@Nullable Icon icon) {
        this.icon = icon;
        revalidate();
        repaint();
    }

    @Override
    public void setToolTipText(String text) {
        tooltip = text;
        updateTooltip();
    }

    /** In compact mode the label is not visible, so the tooltip starts with it. */
    private void updateTooltip() {
        super.setToolTipText(currentTooltip());
    }

    private String currentTooltip() {
        return compact ? (tooltip == null ? text : text + " — " + tooltip) : tooltip;
    }

    boolean isSelected() { return selected; }

    public void setSelected(boolean selected) {
        if (this.selected == selected) return;
        this.selected = selected;
        repaint();
    }

    boolean isCompact() { return compact; }

    /** Called by the toolbar layout; changes the preferred size without revalidating. */
    void setCompact(boolean compact) {
        if (this.compact == compact || icon == null) return;
        this.compact = compact;
        updateTooltip();
        repaint();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        repaint();
    }

    private void fireAction() {
        ActionEvent ev = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, text);
        for (ActionListener l : new ArrayList<>(listeners)) l.actionPerformed(ev);
    }

    private boolean showsText() { return icon == null || !compact && text != null && !text.isEmpty(); }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        int h = Math.max(JBUI.scale(30), fm.getHeight() + JBUI.scale(12));
        if (!showsText()) return new Dimension(Math.max(h, icon.getIconWidth() + JBUI.scale(16)) + arrowWidth(), h);
        int w = JBUI.scale(14) * 2 + fm.stringWidth(text);
        if (icon != null) w += icon.getIconWidth() + JBUI.scale(8);
        return new Dimension(w + arrowWidth(), h);
    }

    @Override public Dimension getMinimumSize() { return getPreferredSize(); }
    @Override public Dimension getMaximumSize() { return getPreferredSize(); }

    private boolean filled() {
        return style == Style.PRIMARY || selected; // a selected SEGMENT or OUTLINE button is "on"
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int arc = JBUI.scale(10);
            boolean on = isEnabled();
            boolean filled = filled();

            Color bg = null;
            if (filled) {
                bg = !on ? FlowTheme.BTN_BG : pressed ? FlowTheme.ACCENT_PRESSED
                        : hover ? FlowTheme.ACCENT_HOVER : FlowTheme.ACCENT;
            } else if (style == Style.OUTLINE && accent != null && on) {
                bg = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), pressed ? 70 : hover ? 50 : 26);
            } else if (style == Style.OUTLINE) {
                bg = on && pressed ? FlowTheme.BTN_PRESSED : on && hover ? FlowTheme.BTN_HOVER : FlowTheme.BTN_BG;
            } else if (on && (hover || pressed)) {
                bg = pressed ? FlowTheme.BTN_PRESSED : FlowTheme.BTN_HOVER; // segment: transparent until hovered
            }
            if (dropDown != null && on && !filled && style == Style.OUTLINE && (hover || pressed)) {
                // Split button: only the part under the mouse looks hovered / pressed.
                Color part = pressed ? FlowTheme.BTN_PRESSED : FlowTheme.BTN_HOVER;
                g2.setColor(FlowTheme.BTN_BG);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, arc, arc));
                Shape old = g2.getClip();
                int split = w - arrowWidth();
                g2.clipRect(overArrow ? split : 0, 0, overArrow ? arrowWidth() : split, h);
                g2.setColor(part);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, arc, arc));
                g2.setClip(old);
                bg = null;
            }
            if (bg != null) {
                g2.setColor(bg);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, arc, arc));
            }
            if (style == Style.OUTLINE && !filled) {
                // Stroke centered half a pixel inside, so no edge of the border is clipped.
                g2.setColor(!on ? FlowTheme.BTN_BORDER_DISABLED : accent != null ? accent : FlowTheme.BTN_BORDER);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, arc, arc));
            }

            Color fg = !on ? FlowTheme.FG_DISABLED : filled ? Color.WHITE : accent != null ? accent : FlowTheme.FG;
            if (dropDown != null) {
                int split = w - arrowWidth();
                g2.setColor(on ? FlowTheme.BTN_BORDER : FlowTheme.BTN_BORDER_DISABLED);
                g2.fillRect(split, JBUI.scale(6), JBUI.scale(1), h - JBUI.scale(12));
                Icon chevron = FlowTheme.tinted(AllIcons.General.ChevronDown, fg);
                chevron.paintIcon(this, g2, split + (arrowWidth() - chevron.getIconWidth()) / 2, (h - chevron.getIconHeight()) / 2);
                w = split; // the label and icon are centered in the rest
            }
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int gap = JBUI.scale(8);
            boolean showText = showsText();
            int contentW = (showText ? fm.stringWidth(text) : 0)
                    + (icon != null ? icon.getIconWidth() + (showText ? gap : 0) : 0);
            int x = (w - contentW) / 2;

            if (icon != null) {
                Icon i = FlowTheme.tinted(icon, fg);
                i.paintIcon(this, g2, x, (h - i.getIconHeight()) / 2);
                x += icon.getIconWidth() + gap;
            }
            if (showText) {
                g2.setColor(fg);
                g2.drawString(text, x, (h - fm.getHeight()) / 2 + fm.getAscent());
            }
        } finally {
            g2.dispose();
        }
    }
}
