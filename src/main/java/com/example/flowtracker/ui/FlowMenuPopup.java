package com.example.flowtracker.ui;

import com.example.flowtracker.model.FlowStore;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.EmptyIcon;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * The flow picker's popup: the flows (active one checked) in a list that can be reordered by dragging
 * (or Ctrl+Up / Ctrl+Down), then New / Rename / Delete and Export / Import. Copy All Flows follows the order.
 */
final class FlowMenuPopup {
    private FlowMenuPopup() {}

    /** An entry below the list. */
    record Item(String label, @Nullable Icon icon, boolean enabled, Runnable run) {}

    /** null = a separator. */
    static void show(FlowStore store, JComponent anchor, List<Item> items) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(FlowTheme.CARD_BG);
        panel.setBorder(JBUI.Borders.empty(8, 6, 6, 6));

        JLabel title = new JLabel("Flows");
        title.setFont(JBUI.Fonts.label().deriveFont(Font.BOLD));
        title.setForeground(FlowTheme.FG);
        title.setBorder(JBUI.Borders.empty(0, 8, 2, 8));
        panel.add(left(title));
        JLabel hint = new JLabel("Drag to reorder · Copy All Flows follows this order");
        hint.setFont(JBUI.Fonts.smallFont());
        hint.setForeground(FlowTheme.FG_MUTED);
        hint.setBorder(JBUI.Borders.empty(0, 8, 6, 8));
        panel.add(left(hint));

        DefaultListModel<FlowStore.Flow> model = new DefaultListModel<>();
        int[] dropLine = {-1}; // y of the drop indicator while dragging, -1 = none
        JBList<FlowStore.Flow> list = new JBList<>(model) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (dropLine[0] < 0) return;
                g.setColor(FlowTheme.DROP_LINE);
                g.fillRect(JBUI.scale(4), dropLine[0] - 1, getWidth() - JBUI.scale(8), 2);
            }
        };
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(FlowTheme.CARD_BG);
        list.setCellRenderer(new FlowRenderer(store));
        Runnable reload = () -> {
            model.clear();
            store.getAllFlows().forEach(model::addElement);
            list.setSelectedIndex(store.getActiveFlowIndex());
        };
        reload.run();
        panel.add(left(list));

        JBPopup[] popup = new JBPopup[1];
        installDrag(list, store, dropLine, index -> {
            popup[0].cancel();
            store.switchFlow(index);
        });
        // Keyboard: Enter shows the flow, Ctrl+Up / Ctrl+Down move it.
        list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "flowShow");
        list.getActionMap().put("flowShow", action(() -> {
            int i = list.getSelectedIndex();
            popup[0].cancel();
            if (i >= 0) store.switchFlow(i);
        }));
        list.getInputMap().put(KeyStroke.getKeyStroke("ctrl UP"), "flowUp");
        list.getActionMap().put("flowUp", action(() -> move(list, store, -1)));
        list.getInputMap().put(KeyStroke.getKeyStroke("ctrl DOWN"), "flowDown");
        list.getActionMap().put("flowDown", action(() -> move(list, store, +1)));

        for (Item item : items) {
            if (item == null) {
                panel.add(left(separator()));
                continue;
            }
            panel.add(left(itemRow(item, () -> {
                popup[0].cancel();
                item.run().run();
            })));
        }

        popup[0] = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, list)
                .setRequestFocus(true)
                .setCancelOnClickOutside(true)
                .setMovable(false)
                .setResizable(false)
                .createPopup();
        // Keep the list in step while open (a move is saved right away).
        Runnable sync = () -> SwingUtilities.invokeLater(reload);
        store.addListener(sync);
        Disposer.register(popup[0], () -> store.removeListener(sync));
        // Left edge on the button's left edge, a small gap below it.
        popup[0].show(new RelativePoint(anchor, new Point(0, anchor.getHeight() + FlowTheme.popupGap())));
    }

    private static void move(JBList<FlowStore.Flow> list, FlowStore store, int delta) {
        int from = list.getSelectedIndex();
        int to = from + delta;
        if (from < 0 || to < 0 || to >= list.getModel().getSize()) return;
        store.moveFlow(from, to);
        list.setSelectedIndex(to);
    }

    /** Press and drag a row to move it; a click without dragging shows that flow. */
    private static void installDrag(JBList<FlowStore.Flow> list, FlowStore store, int[] dropLine,
                                    java.util.function.IntConsumer onClick) {
        int[] pressed = {-1};
        Point[] pressPoint = {null};
        boolean[] dragging = {false};
        int[] insertAt = {-1};
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                pressed[0] = rowAt(list, e.getPoint());
                pressPoint[0] = e.getPoint();
                dragging[0] = false;
                if (pressed[0] >= 0) list.setSelectedIndex(pressed[0]);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (pressed[0] < 0) return;
                if (!dragging[0]) {
                    if (pressPoint[0].distance(e.getPoint()) < 5) return;
                    dragging[0] = true;
                    list.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
                // Insert before the row whose upper half holds the mouse, else after the last row.
                int count = list.getModel().getSize();
                insertAt[0] = count;
                dropLine[0] = -1;
                for (int i = 0; i < count; i++) {
                    Rectangle b = list.getCellBounds(i, i);
                    if (e.getY() < b.y + b.height / 2) {
                        insertAt[0] = i;
                        dropLine[0] = b.y;
                        break;
                    }
                    dropLine[0] = b.y + b.height;
                }
                list.repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                int from = pressed[0];
                boolean wasDragging = dragging[0];
                pressed[0] = -1;
                dragging[0] = false;
                dropLine[0] = -1;
                list.setCursor(Cursor.getDefaultCursor());
                list.repaint();
                if (from < 0) return;
                if (!wasDragging) {
                    if (rowAt(list, e.getPoint()) == from) onClick.accept(from);
                    return;
                }
                int to = insertAt[0] > from ? insertAt[0] - 1 : insertAt[0];
                if (to != from) {
                    store.moveFlow(from, to);
                    list.setSelectedIndex(to);
                }
            }
        };
        list.addMouseListener(mouse);
        list.addMouseMotionListener(mouse);
    }

    private static int rowAt(JList<?> list, Point p) {
        int i = list.locationToIndex(p);
        if (i < 0) return -1;
        Rectangle b = list.getCellBounds(i, i);
        return b != null && b.contains(p) ? i : -1;
    }

    /** A flow row: check mark for the active flow, name, line count, and a drag handle. */
    private static final class FlowRenderer extends JPanel implements ListCellRenderer<FlowStore.Flow> {
        private final FlowStore store;
        private final JLabel name = new JLabel();
        private final JLabel count = new JLabel();
        private boolean selected;

        FlowRenderer(FlowStore store) {
            super(new BorderLayout(JBUI.scale(8), 0));
            this.store = store;
            setBorder(JBUI.Borders.empty(5, 8, 5, 26)); // room on the right for the handle
            name.setIconTextGap(JBUI.scale(6));
            count.setFont(JBUI.Fonts.smallFont());
            add(name, BorderLayout.CENTER);
            add(count, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends FlowStore.Flow> list, FlowStore.Flow flow,
                                                      int index, boolean isSelected, boolean hasFocus) {
            selected = isSelected;
            boolean active = index == store.getActiveFlowIndex();
            name.setText(flow.name);
            name.setIcon(active ? AllIcons.Actions.Checked : EmptyIcon.ICON_16);
            name.setFont(JBUI.Fonts.label().deriveFont(active ? Font.BOLD : Font.PLAIN));
            name.setForeground(FlowTheme.FG);
            int n = flow.entries.size();
            count.setText(n == 1 ? "1 line" : n + " lines");
            count.setForeground(FlowTheme.FG_MUTED);
            setOpaque(false);
            return this;
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(Math.max(d.width, JBUI.scale(240)), d.height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (selected) {
                    g2.setColor(FlowTheme.SELECTION);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), JBUI.scale(8), JBUI.scale(8));
                }
                // Drag handle: two columns of three dots.
                g2.setColor(FlowTheme.FG_MUTED);
                int d = JBUI.scale(3), gap = JBUI.scale(4);
                int x = getWidth() - JBUI.scale(18), y = (getHeight() - (3 * d + 2 * gap)) / 2;
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 2; c++) g2.fillOval(x + c * (d + gap), y + r * (d + gap), d, d);
                }
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    private static JComponent itemRow(Item item, Runnable onClick) {
        JLabel label = new JLabel(item.label(), item.icon() == null ? EmptyIcon.ICON_16
                : FlowTheme.tinted(item.icon(), item.enabled() ? FlowTheme.FG : FlowTheme.FG_DISABLED), JLabel.LEFT);
        label.setIconTextGap(JBUI.scale(8));
        label.setForeground(item.enabled() ? FlowTheme.FG : FlowTheme.FG_DISABLED);
        JPanel row = new JPanel(new BorderLayout()) {
            boolean hover;
            {
                if (item.enabled()) {
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    addMouseListener(new MouseAdapter() {
                        @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                        @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                        @Override public void mouseReleased(MouseEvent e) { if (contains(e.getPoint())) onClick.run(); }
                    });
                }
            }

            @Override
            protected void paintComponent(Graphics g) {
                if (!hover) return;
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(FlowTheme.BTN_HOVER);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), JBUI.scale(8), JBUI.scale(8));
                } finally {
                    g2.dispose();
                }
            }
        };
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(JBUI.scale(5), JBUI.scale(8), JBUI.scale(5), JBUI.scale(8)));
        row.add(label, BorderLayout.CENTER);
        return row;
    }

    private static JComponent separator() {
        JPanel line = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(FlowTheme.DIVIDER);
                g.fillRect(JBUI.scale(6), getHeight() / 2, getWidth() - JBUI.scale(12), 1);
            }
        };
        line.setOpaque(false);
        line.setPreferredSize(new Dimension(1, JBUI.scale(9)));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(9)));
        return line;
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Every row, the flow list included, stretches to the popup's full width.
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                c instanceof JBList ? Integer.MAX_VALUE : c.getPreferredSize().height));
        return c;
    }

    private static AbstractAction action(Runnable r) {
        return new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        };
    }
}
