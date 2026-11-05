package com.example.flowtracker.ui;

import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BooleanSupplier;

/**
 * A menu in the plugin's style: a header, rows with an icon, a title, a grey hint under it and the key on the right,
 * thin separators, and red rows for deleting. The mouse or ↑ ↓ pick a row; a click or Enter runs it.
 */
public final class FlowPopupMenu {
    private record Item(@Nullable Icon icon, String title, @Nullable String hint, @Nullable String key, boolean danger,
                        boolean enabled, Runnable run) {}
    private record Header(String text) {}
    private static final Object SEPARATOR = new Object();

    private final DefaultListModel<Object> rows = new DefaultListModel<>();

    public FlowPopupMenu header(String text) {
        rows.addElement(new Header(text));
        return this;
    }

    public FlowPopupMenu item(@Nullable Icon icon, String title, @Nullable String hint, @Nullable String key, Runnable run) {
        return item(icon, title, hint, key, true, run);
    }

    public FlowPopupMenu item(@Nullable Icon icon, String title, @Nullable String hint, @Nullable String key, boolean enabled, Runnable run) {
        rows.addElement(new Item(icon, title, hint, key, false, enabled, run));
        return this;
    }

    /** A row that deletes something: in red. */
    public FlowPopupMenu danger(@Nullable Icon icon, String title, @Nullable String hint, @Nullable String key, Runnable run) {
        rows.addElement(new Item(icon, title, hint, key, true, true, run));
        return this;
    }

    public FlowPopupMenu separator() {
        if (!rows.isEmpty() && rows.lastElement() != SEPARATOR) rows.addElement(SEPARATOR);
        return this;
    }

    /** Shows it with its top left corner at (x, y) of {@code owner}. */
    public void show(Component owner, int x, int y) {
        if (!rows.isEmpty() && rows.lastElement() == SEPARATOR) rows.removeElementAt(rows.size() - 1);
        JBList<Object> list = new JBList<>(rows);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        list.setBackground(FlowTheme.CARD_BG);
        list.setBorder(JBUI.Borders.empty(4));
        list.setExpandableItemsEnabled(false);
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(FlowTheme.CARD_BG);
        panel.add(list, BorderLayout.CENTER);
        Dimension pref = list.getPreferredSize();
        panel.setPreferredSize(new Dimension(Math.max(JBUI.scale(260), pref.width + JBUI.scale(8)), pref.height + JBUI.scale(2)));
        if (rightAligned) x = owner.getWidth() - panel.getPreferredSize().width; // its right edge at the owner's right edge

        JBPopup popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, list)
                .setRequestFocus(true)
                .setFocusable(true)
                .setCancelOnClickOutside(true)
                .setCancelKeyEnabled(true)
                .createPopup();
        BooleanSupplier runSelected = () -> {
            if (!(list.getSelectedValue() instanceof Item it) || !it.enabled()) return false;
            popup.closeOk(null);
            SwingUtilities.invokeLater(it.run());
            return true;
        };
        list.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint()) && rows.get(i) instanceof Item it && it.enabled()) {
                    list.setSelectedIndex(i);
                } else {
                    list.clearSelection();
                }
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseExited(MouseEvent e) { list.clearSelection(); }
            @Override public void mouseReleased(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) {
                    list.setSelectedIndex(i);
                    runSelected.getAsBoolean();
                }
            }
        });
        list.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> { move(list, 1); e.consume(); }
                    case KeyEvent.VK_UP -> { move(list, -1); e.consume(); }
                    case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> { runSelected.getAsBoolean(); e.consume(); }
                    default -> { }
                }
            }
        });
        popup.show(new RelativePoint(owner, new Point(x, y)));
        // the popup's window may sit off the point asked for (its border, the screen's scale): moved so the menu
        // itself starts exactly there (under a button: at its left edge, or ending at its right edge)
        int wantX = x, wantY = y;
        SwingUtilities.invokeLater(() -> {
            if (popup.isDisposed() || !panel.isShowing() || !owner.isShowing()) return;
            Point want = owner.getLocationOnScreen();
            want.translate(wantX, wantY);
            Point got = panel.getLocationOnScreen();
            if (got.equals(want)) return;
            Point at = popup.getLocationOnScreen();
            popup.setLocation(new Point(at.x + want.x - got.x, at.y + want.y - got.y));
        });
    }

    /** Below a button (with a small gap), lined up with its left edge. */
    public void showBelow(JComponent button) {
        show(button, 0, button.getHeight() + JBUI.scale(6));
    }

    /** Lined up with the owner's right edge (set by {@link #showBelowRight}). */
    private boolean rightAligned;

    /** Below a button, ending at its right edge (e.g. a button at the right end of a toolbar). */
    public void showBelowRight(JComponent button) {
        rightAligned = true;
        show(button, 0, button.getHeight() + JBUI.scale(6));
    }

    /** The next row that can be run, up or down (round the end). */
    private void move(JBList<Object> list, int step) {
        int n = rows.size();
        int i = list.getSelectedIndex();
        for (int k = 0; k < n; k++) {
            i = ((i < 0 ? (step > 0 ? -1 : 0) : i) + step + n) % n;
            if (rows.get(i) instanceof Item it && it.enabled()) {
                list.setSelectedIndex(i);
                list.ensureIndexIsVisible(i);
                return;
            }
        }
    }

    private static final class Renderer implements ListCellRenderer<Object> {
        private final Row row = new Row();
        private final JPanel line = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(FlowTheme.CARD_BG);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(FlowTheme.CARD_BORDER);
                g.fillRect(JBUI.scale(8), getHeight() / 2, getWidth() - JBUI.scale(16), Math.max(1, JBUI.scale(1)));
            }
        };
        private final JBLabel header = new JBLabel();

        Renderer() {
            line.setPreferredSize(new Dimension(1, JBUI.scale(9)));
            header.setFont(JBUI.Fonts.smallFont().asBold());
            header.setForeground(FlowTheme.FG_MUTED);
            header.setBorder(JBUI.Borders.empty(6, 10, 4, 10));
            header.setOpaque(true);
            header.setBackground(FlowTheme.CARD_BG);
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            if (value == SEPARATOR) return line;
            if (value instanceof Header h) {
                header.setText(h.text());
                return header;
            }
            row.set((Item) value, selected);
            return row;
        }
    }

    /** A row: icon, title (and hint under it), key on the right; a soft rounded background when picked. */
    private static final class Row extends JPanel {
        private final JBLabel icon = new JBLabel(), title = new JBLabel(), hint = new JBLabel(), key = new JBLabel();
        private boolean picked;

        Row() {
            super(new BorderLayout(JBUI.scale(10), 0));
            setOpaque(false);
            setBorder(JBUI.Borders.empty(5, 10, 5, 12));
            icon.setPreferredSize(new Dimension(JBUI.scale(16), JBUI.scale(16)));
            hint.setFont(JBUI.Fonts.smallFont());
            hint.setForeground(FlowTheme.FG_MUTED);
            key.setFont(JBUI.Fonts.smallFont());
            key.setForeground(FlowTheme.FG_MUTED);
            JPanel texts = new JPanel(new BorderLayout(0, JBUI.scale(1)));
            texts.setOpaque(false);
            texts.add(title, BorderLayout.NORTH);
            texts.add(hint, BorderLayout.CENTER);
            JPanel iconBox = new JPanel(new java.awt.GridBagLayout());
            iconBox.setOpaque(false);
            iconBox.add(icon);
            add(iconBox, BorderLayout.WEST);
            add(texts, BorderLayout.CENTER);
            add(key, BorderLayout.EAST);
        }

        void set(Item it, boolean selected) {
            picked = selected && it.enabled();
            icon.setIcon(it.icon());
            title.setText(it.title());
            title.setForeground(!it.enabled() ? FlowTheme.FG_MUTED : it.danger() ? FlowTheme.WARNING : FlowTheme.FG);
            hint.setText(it.hint() == null ? "" : it.hint());
            hint.setVisible(it.hint() != null && !it.hint().isEmpty());
            key.setText(it.key() == null ? "" : it.key());
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (!picked) return;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = JBUI.scale(8);
                g2.setColor(FlowTheme.SELECTION);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            } finally {
                g2.dispose();
            }
        }
    }
}
