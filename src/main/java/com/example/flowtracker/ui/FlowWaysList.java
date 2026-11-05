package com.example.flowtracker.ui;

import com.example.flowtracker.code.FlowCallPath;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The ways Connect the Flow found, as cards, with what is already there marked (the tab's classes, or the flow's):
 * each step of a way is <b>● on the tab</b> (green) or <b>✚ new</b> (violet). Filters: All, Through the tab (every class of
 * the way is there already), With new classes, and a text filter. Tells which way the mouse is over, which is
 * selected and which is picked (double-click or Enter).
 */
public final class FlowWaysList extends JPanel {

    /** Where a step of a way is: its method is there already, its class is (the method isn't), or it is new. */
    public enum Where { METHOD_THERE, CLASS_THERE, NEW }

    static final JBColor THERE = new JBColor(0x1F8F4E, 0x5FB878), NEW = new JBColor(0x7B4FD6, 0xB08CFF);

    private final List<FlowCallPath.Way> all;
    private final Function<FlowCallPath.Step, Where> where;
    private final DefaultListModel<FlowCallPath.Way> model = new DefaultListModel<>();
    /** As wide as its column, never wider (a card that is wider wraps its chain instead of spilling over). */
    private final JBList<FlowCallPath.Way> list = new JBList<>(model) {
        @Override public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        // The wheel moves smoothly through the cards: a list scrolls a whole row a notch (a card is tall), or
        // snaps to a card's edge; this moves a fixed step, and a page is what the list shows.
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return JBUI.scale(24);
        }

        @Override public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return Math.max(JBUI.scale(24), (orientation == javax.swing.SwingConstants.VERTICAL ? visible.height : visible.width) - JBUI.scale(24));
        }

        /** A press on the empty space under the cards keeps the way picked (and its details): the list just gets the focus. */
        @Override protected void processMouseEvent(MouseEvent e) {
            if (e.getID() == MouseEvent.MOUSE_PRESSED && locationToIndex(e.getPoint()) < 0) {
                requestFocusInWindow();
                return;
            }
            super.processMouseEvent(e);
        }

        /** Only a card itself: a click on the empty space under the last one selects nothing (not the last one). */
        @Override public int locationToIndex(java.awt.Point p) {
            int i = super.locationToIndex(p);
            return i >= 0 && getCellBounds(i, i) != null && getCellBounds(i, i).contains(p) ? i : -1;
        }
    };
    private final SearchTextField text = new SearchTextField(false);
    private final FlowButton allButton, thereButton, newButton;
    private int filter; // 0 all, 1 through what is there, 2 with new classes
    /** The order of the cards, and whether ways already on the tab are left out. */
    private Sort sort = Sort.RECOMMENDED;
    /** By default the ways already on the tab are left out (the menu shows them again). */
    private boolean hideThere = true;
    /** Whether a way is there already (its calls are on the tab); by default: every method of it is. */
    private @Nullable java.util.function.Predicate<FlowCallPath.Way> thereTest;
    private final FlowButton sortButton = new FlowButton("Recommended", com.intellij.icons.AllIcons.ObjectBrowser.Sorted, FlowButton.Style.OUTLINE);

    /** How the cards are ordered. */
    enum Sort {
        RECOMMENDED("Recommended", "The order they were found: the closest way first"),
        FEWEST_CALLS("Fewest calls", "The shortest ways first"),
        MOST_CALLS("Most calls", "The longest ways first"),
        FEWEST_NEW("Fewest new classes", "The ways that use most of what is there first"),
        MOST_NEW("Most new classes", "The ways that bring the most classes first");
        final String label, hint;
        Sort(String label, String hint) { this.label = label; this.hint = hint; }
    }
    private int hover = -1;
    private @Nullable javax.swing.JViewport viewport;
    private Consumer<FlowCallPath.Way> onHover = w -> {};
    private Consumer<FlowCallPath.Way> onPick = w -> {};

    /** @param there what is there, for the filter's label ("this tab", "the flow") */
    public FlowWaysList(FlowCallPath.Ways ways, Function<FlowCallPath.Step, Where> where, String there) {
        super(new BorderLayout(0, JBUI.scale(6)));
        setOpaque(false);
        this.all = ways.ways();
        this.where = where;
        this.thereText = there;
        allButton = new FlowButton("All", null, FlowButton.Style.SEGMENT);
        thereButton = new FlowButton("Through " + there, null, FlowButton.Style.SEGMENT);
        newButton = new FlowButton("With new classes", null, FlowButton.Style.SEGMENT);
        counts();
        allButton.setToolTipText("Every way found");
        thereButton.setToolTipText("Only the ways whose classes are all on " + there + " already");
        newButton.setToolTipText("Only the ways that bring classes " + there + " doesn't have yet");
        allButton.addActionListener(e -> setFilter(0));
        thereButton.addActionListener(e -> setFilter(1));
        newButton.addActionListener(e -> setFilter(2));
        FlowToolbar filters = new FlowToolbar();
        filters.addGroup(true, 3, allButton, thereButton, newButton);
        text.getTextEditor().getEmptyText().setText("Filter by class or method");
        text.addDocumentListener(new DocumentAdapter() {
            @Override protected void textChanged(@NotNull DocumentEvent e) { fill(); }
        });
        sortButton.setToolTipText("Order the ways, or hide the ones already on " + there);
        sortButton.addActionListener(e -> {
            FlowPopupMenu m = new FlowPopupMenu().header("ORDER");
            for (Sort o : Sort.values()) {
                m.item(o == sort ? com.intellij.icons.AllIcons.Actions.Checked : com.intellij.icons.AllIcons.ObjectBrowser.SortByType,
                        o.label, o.hint, null, () -> { sort = o; fill(); });
            }
            m.separator().header("SHOW");
            m.item(hideThere ? com.intellij.icons.AllIcons.Actions.Checked : com.intellij.icons.AllIcons.Actions.Show,
                    "Hide Ways Already on " + StringUtil.capitalizeWords(there, true), "The ways whose every method is there already",
                    null, () -> { hideThere = !hideThere; fill(); });
            m.showBelow(sortButton);
        });
        JPanel search = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        search.setOpaque(false);
        search.add(text, BorderLayout.CENTER);
        search.add(sortButton, BorderLayout.EAST);
        JPanel top = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        top.setOpaque(false);
        top.add(filters, BorderLayout.NORTH);
        top.add(search, BorderLayout.CENTER);
        JBLabel legend = new JBLabel("<html><span style='color:" + hex(THERE) + "'>●</span> on " + there
                + " &nbsp; <span style='color:" + hex(NEW) + "'>✚</span> new</html>");
        legend.setFont(JBUI.Fonts.smallFont());
        legend.setForeground(FlowTheme.FG_MUTED);
        top.add(legend, BorderLayout.SOUTH);

        // a way already on the tab can't be picked (nothing to add): it isn't selected
        list.setSelectionModel(new javax.swing.DefaultListSelectionModel() {
            @Override public void setSelectionInterval(int a, int b) {
                if (b >= 0 && b < model.size() && isThere(model.get(b))) return;
                super.setSelectionInterval(a, b);
            }

            @Override public void addSelectionInterval(int a, int b) {
                setSelectionInterval(a, b);
            }
        });
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setExpandableItemsEnabled(false); // no floating copy of a long card under the mouse (it looked split)
        list.setCellRenderer(new Card());
        list.setBackground(FlowTheme.CARD_BG);
        list.getEmptyText().setText("No way matches the filter");
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && !list.getCellBounds(i, i).contains(e.getPoint())) i = -1;
                boolean there = i >= 0 && isThere(model.get(i));
                list.setToolTipText(there ? "This way is on the tab already: every method of it is there" : null);
                setHover(there ? -1 : i); // nothing to preview: it is there
            }

            @Override public void mouseExited(MouseEvent e) {
                setHover(-1);
            }

            @Override public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint()); // -1 on the empty space under the cards
                if (i < 0 || isThere(model.get(i))) return; // the empty space, or a way that is there already
                if (e.getClickCount() == (pickOnClick ? 1 : 2)) onPick.accept(model.get(i));
            }
        };
        list.addMouseListener(mouse);
        list.addMouseMotionListener(mouse);
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && hover < 0) previewNow(); }); // picked with the keys
        JBScrollPane scroll = FlowScroll.wheel(new JBScrollPane(list, javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER));
        // the wheel scrolls about a line of a card at a time (the cards are tall: the list's own step is a few pixels)
        scroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(28));
        scroll.setWheelScrollingEnabled(true);
        // the cards are as wide as the list shows (their chains wrap), so measure them again when it is resized
        scroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                list.setFixedCellHeight(10);
                list.setFixedCellHeight(-1);
                list.revalidate();
                list.repaint();
            }
        });
        this.viewport = scroll.getViewport();
        scroll.setBorder(JBUI.Borders.customLine(FlowTheme.CARD_BORDER));
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        setFilter(0);
    }

    private final String thereText;

    /** The filters' counts, from the ways there are now. */
    private void counts() {
        long through = all.stream().filter(w -> newClasses(w) == 0).count();
        allButton.setText("All " + all.size());
        thereButton.setText("Through " + thereText + " " + through);
        newButton.setText("With new classes " + (all.size() - through));
    }

    /** The ways list changed (a search added ways while it is shown): counts and cards again, the pick kept. */
    public void refresh() {
        counts();
        fill();
    }

    /** One click takes a way (the diagram's panel); else a double-click (the window). */
    private boolean pickOnClick;

    public void pickOnClick(boolean on) {
        pickOnClick = on;
    }

    public void onHover(Consumer<FlowCallPath.Way> c) {
        onHover = c;
    }

    public void onPick(Consumer<FlowCallPath.Way> c) {
        onPick = c;
    }

    public JBList<FlowCallPath.Way> waysList() {
        return list;
    }

    /** The way's place among all the ways found (for "Way n"). */
    public int number(FlowCallPath.Way w) {
        return all.indexOf(w) + 1;
    }

    private void setHover(int i) {
        if (i == hover) return;
        hover = i;
        list.repaint();
        previewNow();
    }

    /**
     * Shows the way the mouse is over; when it is over none, the way picked in the list (a window: a click picks a
     * way, the preview stays on it until another is hovered), else nothing.
     */
    private void previewNow() {
        if (hover >= 0 && hover < model.size()) onHover.accept(model.get(hover));
        else onHover.accept(pickOnClick ? null : list.getSelectedValue());
    }

    private void setFilter(int f) {
        filter = f;
        allButton.setSelected(f == 0);
        thereButton.setSelected(f == 1);
        newButton.setSelected(f == 2);
        fill();
    }

    private void fill() {
        FlowCallPath.Way picked = list.getSelectedValue();
        model.clear();
        String q = text.getText().strip().toLowerCase(Locale.ROOT);
        List<FlowCallPath.Way> shown = new java.util.ArrayList<>();
        for (FlowCallPath.Way w : all) {
            int fresh = newClasses(w);
            if (filter == 1 && fresh > 0 || filter == 2 && fresh == 0) continue;
            if (hideThere && isThere(w)) continue;
            if (!q.isEmpty() && !w.chain().toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(w);
        }
        java.util.Comparator<FlowCallPath.Way> by = switch (sort) {
            case FEWEST_CALLS -> java.util.Comparator.comparingInt(FlowCallPath.Way::calls);
            case MOST_CALLS -> java.util.Comparator.comparingInt(FlowCallPath.Way::calls).reversed();
            case FEWEST_NEW -> java.util.Comparator.comparingInt(this::newClasses);
            case MOST_NEW -> java.util.Comparator.comparingInt(this::newClasses).reversed();
            default -> null;
        };
        if (by != null) shown.sort(by.thenComparingInt(all::indexOf));
        // the ways already on the tab last: they can't be added
        shown.sort(java.util.Comparator.comparingInt(w -> isThere(w) ? 1 : 0));
        shown.forEach(model::addElement);
        long hidden = hideThere ? all.stream().filter(this::isThere).count() : 0;
        sortButton.setText(sort.label + (hidden > 0 ? " · " + hidden + " hidden" : ""));
        list.getEmptyText().setText(hidden > 0 && model.isEmpty() ? "Every way is on " + "this tab already (the order menu shows them)"
                : "No way matches the filter");
        if (picked != null && model.contains(picked)) list.setSelectedValue(picked, true);
        else {
            for (int i = 0; i < model.size(); i++) {
                if (!isThere(model.get(i))) {
                    list.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    /** True when the way is there already (nothing to add): by the test given, else every method of it is there. */
    public boolean isThere(FlowCallPath.Way w) {
        java.util.function.Predicate<FlowCallPath.Way> t = thereTest;
        if (t != null) return t.test(w);
        return !w.steps().isEmpty() && w.steps().stream().allMatch(s -> where.apply(s) == Where.METHOD_THERE);
    }

    /** How to tell a way that is there already, e.g. every call of it is on the tab (the list is filled again). */
    public void setThere(java.util.function.Predicate<FlowCallPath.Way> test) {
        thereTest = test;
        fill();
    }

    /** How many classes of the way aren't there yet. */
    public int newClasses(FlowCallPath.Way w) {
        return (int) w.steps().stream().filter(s -> where.apply(s) == Where.NEW).map(FlowCallPath.Step::className).distinct().count();
    }

    /** The chain with each step marked: ● there (green), ✚ new (violet). */
    public String chainHtml(FlowCallPath.Way w) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < w.steps().size(); i++) {
            FlowCallPath.Step s = w.steps().get(i);
            if (i > 0) sb.append(" <b>→</b> ");
            Where at = where.apply(s);
            sb.append("<span style='color:").append(hex(at == Where.NEW ? NEW : THERE)).append("'>")
                    .append(at == Where.NEW ? "✚" : "●").append("</span> ").append(StringUtil.escapeXmlEntities(s.label()));
        }
        return sb.toString();
    }

    static String hex(Color c) {
        return "#" + ColorUtil.toHex(c);
    }

    /** A way as a card: its number, Recommended, the chain (marked), calls, lines, new classes, what it carries. */
    private final class Card extends JPanel implements ListCellRenderer<FlowCallPath.Way> {
        private final JBLabel number = new JBLabel(), badge = new JBLabel(), chain = new JBLabel(), meta = new JBLabel();
        private boolean selected, hovered, there;

        Card() {
            super(new BorderLayout(0, JBUI.scale(3)));
            setOpaque(false);
            setBorder(JBUI.Borders.empty(9, 13, 9, 11));
            number.setFont(JBUI.Fonts.label().asBold());
            badge.setFont(JBUI.Fonts.smallFont().asBold());
            meta.setFont(JBUI.Fonts.smallFont());
            JPanel first = new JPanel(new BorderLayout(JBUI.scale(8), 0));
            first.setOpaque(false);
            first.add(number, BorderLayout.WEST);
            first.add(badge, BorderLayout.CENTER);
            add(first, BorderLayout.NORTH);
            add(chain, BorderLayout.CENTER);
            add(meta, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends FlowCallPath.Way> l, FlowCallPath.Way w, int index,
                                                      boolean isSelected, boolean focus) {
            selected = isSelected;
            hovered = index == hover;
            there = isThere(w);
            int n = number(w);
            number.setText("Way " + n);
            number.setForeground(there ? FlowTheme.FG_MUTED : FlowTheme.FG);
            badge.setText(there ? "ALREADY ON THIS TAB" : n == 1 ? "RECOMMENDED" : "");
            badge.setForeground(there ? FlowTheme.FG_MUTED : THERE);
            int shown = viewport != null && viewport.getWidth() > 0 ? viewport.getWidth() : l.getWidth();
            // the chain's width in the HTML's own units (it scales them itself), so it wraps inside the card
            int width = Math.max(140, (int) ((shown - JBUI.scale(56)) / com.intellij.ui.scale.JBUIScale.scale(1f)));
            chain.setText("<html><div style='width:" + width + "px'>" + chainHtml(w) + "</div></html>");
            chain.setForeground(FlowTheme.FG);
            chain.setEnabled(!there);
            int fresh = newClasses(w), calls = w.calls(), lines = w.lines().size();
            String via = w.steps().stream().map(FlowCallPath.Step::via).filter(v -> !v.isEmpty()).findFirst().orElse("");
            meta.setText(calls + (calls == 1 ? " call" : " calls") + " · " + lines + (lines == 1 ? " line" : " lines")
                    + " · " + (fresh == 0 ? "all on it already" : fresh + (fresh == 1 ? " new class" : " new classes"))
                    + (w.carries().isEmpty() ? "" : " · carries " + String.join(", ", w.carries())) + (via.isEmpty() ? "" : " · " + via));
            meta.setForeground(FlowTheme.FG_MUTED);
            return this;
        }

        @Override
        public void paint(Graphics g) {
            if (!there) {
                super.paint(g);
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.5f));
                super.paint(g2);
            } finally {
                g2.dispose();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = JBUI.scale(12), m = JBUI.scale(4);
                if (there) g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.55f));
                g2.setColor(selected ? FlowTheme.SELECTION : hovered ? FlowTheme.ROW_HOVER : FlowTheme.CARD_BG);
                g2.fillRoundRect(m, m / 2, getWidth() - 2 * m, getHeight() - m, arc, arc);
                g2.setColor(hovered ? NEW : selected ? FlowTheme.SELECTION_BAR : FlowTheme.CARD_BORDER);
                g2.drawRoundRect(m, m / 2, getWidth() - 2 * m - 1, getHeight() - m - 1, arc, arc);
                if (selected || hovered) {
                    g2.setColor(hovered ? NEW : FlowTheme.SELECTION_BAR);
                    g2.fillRect(m, m / 2 + arc / 2, JBUI.scale(3), getHeight() - m - arc);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
