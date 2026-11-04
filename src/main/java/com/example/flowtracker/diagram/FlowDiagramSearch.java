package com.example.flowtracker.diagram;

import com.example.flowtracker.ui.FlowTheme;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.ScrollingUtil;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Find a class or method in the Flow Diagram (Ctrl+F, or the search button by the zoom buttons): a field and the
 * diagram's classes and methods under it (10 rows, the rest by scrolling), narrowed as you type. Enter or a click
 * selects it in the diagram.
 */
final class FlowDiagramSearch {
    /** A class (method null) or one of its methods. */
    record Item(FlowDiagram.Box box, @Nullable FlowDiagram.Method method) {
        String text() {
            return method == null ? box.name : box.name + "." + method.name;
        }
    }

    static final int ROWS = 10;

    private FlowDiagramSearch() {
    }

    /** Everything that can be found: each class, then its methods. */
    static List<Item> items(FlowDiagram d) {
        List<Item> out = new ArrayList<>();
        for (FlowDiagram.Box b : d.boxes) {
            out.add(new Item(b, null));
            for (FlowDiagram.Method m : b.methods) out.add(new Item(b, m));
        }
        return out;
    }

    /**
     * The items for a typed text: a class whose name (or file) has it, with all its methods; a method whose name (or
     * "Class.method") has it. Case is ignored; spaces split it into words that must all be there.
     */
    static List<Item> filter(List<Item> all, String typed) {
        String[] words = typed.strip().toLowerCase(Locale.ROOT).split("\\s+");
        if (typed.isBlank()) return all;
        List<Item> out = new ArrayList<>();
        for (Item it : all) {
            String cls = (it.box().name + " " + it.box().fileName).toLowerCase(Locale.ROOT);
            String text = it.method() == null ? cls : cls + " " + it.text().toLowerCase(Locale.ROOT) + " " + it.method().name.toLowerCase(Locale.ROOT);
            if (all(text, words)) out.add(it);
        }
        return out;
    }

    private static boolean all(String text, String[] words) {
        for (String w : words) if (!w.isEmpty() && !text.contains(w)) return false;
        return true;
    }

    /**
     * Shows the search.
     *
     * @param owner  the drawing area (the popup shows at its top, in the middle) when {@code button} is null
     * @param button the search button (the popup shows above it), or null
     */
    static void show(JComponent owner, @Nullable JComponent button, FlowDiagram d, Consumer<Item> pick) {
        show(owner, button, "Find in the diagram", "Class or method name", items(d), pick);
    }

    /**
     * The methods a method can be connected to (Connect the Flow to…): each with its class and project, searched as
     * you type, shown in the middle of the drawing.
     */
    static void showConnectTargets(JComponent owner, String title, FlowDiagram d, FlowDiagram.Method from, Consumer<Item> pick) {
        List<Item> all = new ArrayList<>();
        for (FlowDiagram.Box b : d.boxes) {
            if (b.isUnknown()) continue;
            for (FlowDiagram.Method m : b.methods) if (m != from) all.add(new Item(b, m));
        }
        show(owner, null, title, "Method or class name", all, pick);
    }

    private static void show(JComponent owner, @Nullable JComponent button, String titleText, String placeholder, List<Item> all,
                             Consumer<Item> pick) {
        DefaultListModel<Item> model = new DefaultListModel<>();
        JBList<Item> list = new JBList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(ROWS);
        list.setFixedCellHeight(JBUI.scale(28));
        list.setCellRenderer(new Renderer());
        list.getEmptyText().setText(all.isEmpty() ? "The diagram has no classes yet" : "No class or method has that");
        JBScrollPane scroll = com.example.flowtracker.ui.FlowScroll.wheel(new JBScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER));
        scroll.setBorder(JBUI.Borders.customLineTop(FlowTheme.CARD_BORDER));

        SearchTextField field = new SearchTextField(false);
        field.getTextEditor().getEmptyText().setText(placeholder);
        JBLabel count = new JBLabel();
        count.setForeground(FlowTheme.FG_MUTED);
        count.setFont(JBUI.Fonts.smallFont());
        Runnable refilter = () -> {
            List<Item> shown = filter(all, field.getText());
            model.clear();
            for (Item it : shown) model.addElement(it);
            if (!shown.isEmpty()) list.setSelectedIndex(0);
            count.setText(field.getText().isBlank() ? all.size() + (all.size() == 1 ? " item" : " items")
                    : shown.size() + " of " + all.size());
        };
        refilter.run();

        JPanel head = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        head.setOpaque(false);
        JBLabel title = new JBLabel("Type to narrow · ↑ ↓ · Enter");
        title.setForeground(FlowTheme.FG_MUTED);
        title.setFont(JBUI.Fonts.smallFont());
        head.add(title, BorderLayout.WEST);
        head.add(count, BorderLayout.EAST);
        head.setBorder(JBUI.Borders.empty(8, 10, 4, 10));
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(head, BorderLayout.NORTH);
        JPanel fieldBox = new JPanel(new BorderLayout());
        fieldBox.setOpaque(false);
        fieldBox.setBorder(JBUI.Borders.empty(0, 8, 8, 8));
        fieldBox.add(field, BorderLayout.CENTER);
        top.add(fieldBox, BorderLayout.CENTER);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(top, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        int rows = Math.max(3, Math.min(ROWS, all.size()));
        panel.setPreferredSize(new Dimension(JBUI.scale(440), top.getPreferredSize().height + rows * JBUI.scale(28) + JBUI.scale(6)));

        JBPopup popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, field.getTextEditor())
                .setRequestFocus(true)
                .setFocusable(true)
                .setCancelOnClickOutside(true)
                .setCancelKeyEnabled(true)
                .setMovable(true)
                .setResizable(true)
                .setTitle(titleText) // a title bar to drag it by
                .createPopup();
        Runnable choose = () -> {
            Item it = list.getSelectedValue();
            if (it == null) return;
            popup.closeOk(null);
            pick.accept(it);
        };
        field.addDocumentListener(new com.intellij.ui.DocumentAdapter() {
            @Override protected void textChanged(@NotNull DocumentEvent e) { refilter.run(); }
        });
        field.getTextEditor().addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_ENTER -> { choose.run(); e.consume(); }
                    case KeyEvent.VK_DOWN -> { ScrollingUtil.moveDown(list, 0); e.consume(); }
                    case KeyEvent.VK_UP -> { ScrollingUtil.moveUp(list, 0); e.consume(); }
                    case KeyEvent.VK_PAGE_DOWN -> { ScrollingUtil.movePageDown(list); e.consume(); }
                    case KeyEvent.VK_PAGE_UP -> { ScrollingUtil.movePageUp(list); e.consume(); }
                    default -> { }
                }
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseReleased(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i < 0 || !list.getCellBounds(i, i).contains(e.getPoint())) return;
                list.setSelectedIndex(i);
                choose.run();
            }
        });

        Dimension size = panel.getPreferredSize();
        if (button != null && button.isShowing()) {
            popup.show(new RelativePoint(button, new Point(button.getWidth() - size.width, -size.height - JBUI.scale(6))));
        } else {
            popup.show(new RelativePoint(owner, new Point(Math.max(0, (owner.getWidth() - size.width) / 2), JBUI.scale(12))));
        }
    }

    /** A class (its icon, name, file) or a method (indented, its name and class). */
    private static final class Renderer extends ColoredListCellRenderer<Item> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends Item> list, Item it, int index, boolean selected, boolean focus) {
            setBorder(JBUI.Borders.empty(0, it.method() == null ? 10 : 30, 0, 10));
            if (it.method() == null) {
                setIcon(AllIcons.Nodes.Class);
                append(it.box().name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                String file = it.box().fileLine();
                int n = it.box().methods.size();
                append("   " + (file.isEmpty() ? "" : file + "  ·  ") + n + (n == 1 ? " method" : " methods"),
                        SimpleTextAttributes.GRAYED_ATTRIBUTES);
            } else {
                setIcon(AllIcons.Nodes.Method);
                append(it.method().name, SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append("   " + it.box().name, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                String project = it.box().project();
                if (!project.isEmpty()) append("  ·  " + project, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                int n = it.method().lineCount();
                if (n > 0) append("  ·  " + n + (n == 1 ? " line" : " lines"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
            }
        }
    }
}
