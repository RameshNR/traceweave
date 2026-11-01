package com.example.flowtracker.ui;

import com.example.flowtracker.copy.FlowCopy;
import com.example.flowtracker.model.FlowDebugStore;
import com.example.flowtracker.model.FlowSnapshot;
import com.example.flowtracker.model.FlowValue;
import com.example.flowtracker.model.FlowValueDiff;
import com.example.flowtracker.model.FlowValueText;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.util.Disposer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

/**
 * Two debug values side by side: every field, map entry and element, matched by name (map entries by key, list
 * elements by value), what differs marked and colored (changed amber, only on the right green, only on the left
 * red), instance ids like @1141 left out. The JSON and Text views show both values in IntelliJ's diff viewer, in the
 * same window (which has the system's Minimize, Maximize and Close).
 */
public final class FlowCompareDialog {
    private static final Color CHANGED = new JBColor(0xFFF1D6, 0x3E3420);
    private static final Color ADDED = new JBColor(0xE3F5E1, 0x24382A);
    private static final Color REMOVED = new JBColor(0xFCE3E3, 0x40262A);
    private static final Color CHANGED_MARK = new JBColor(0xC27C0E, 0xE5A83B);
    private static final Color ADDED_MARK = new JBColor(0x2E8B3A, 0x6FC27B);
    private static final Color REMOVED_MARK = new JBColor(0xD64550, 0xE5636D);

    private final @Nullable Project project;
    private final String title;
    private FlowValue left, right;
    private List<FlowValueDiff.Row> all;
    private final List<FlowValueDiff.Row> shown = new ArrayList<>();
    private String leftTitle, rightTitle;
    private final JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
    private @Nullable JBTable table;
    private String view = "TREE";
    private final Model model = new Model();
    private final JBCheckBox onlyChanges = new JBCheckBox("Only what differs", true);
    private final JPanel cards = new JPanel(new CardLayout());
    private final java.util.Set<String> made = new java.util.HashSet<>();
    private final java.util.Map<String, FlowButton> viewButtons = new java.util.LinkedHashMap<>();
    private final Disposable disposable = Disposer.newDisposable("TraceWeave Compare");
    /** The saved values compared (null for a side that isn't saved, e.g. "now" or pasted data): edits go to them. */
    private @Nullable FlowSnapshot leftSnap, rightSnap;
    /** What was done in this window, for Undo, Redo and History: both sides after each step. */
    private record State(FlowValue left, FlowValue right, String label, long at) {}
    private final List<State> steps = new ArrayList<>();
    private int position;
    /** The editable texts of the JSON and Text views, and what they showed (to see what was edited). */
    private final java.util.Map<String, com.intellij.diff.contents.DocumentContent[]> docs = new java.util.HashMap<>();
    private final java.util.Map<String, String[]> shownTexts = new java.util.HashMap<>();
    private final javax.swing.JButton undo = new javax.swing.JButton("Undo");
    private final javax.swing.JButton redo = new javax.swing.JButton("Redo");
    private final javax.swing.JButton history = new javax.swing.JButton("History");
    private final javax.swing.JButton reset = new javax.swing.JButton("Reset");
    private final javax.swing.JButton saveEdits = new javax.swing.JButton("Save Edits");
    private final JBLabel editHint = new JBLabel();
    /** Counts the differences again a moment after the JSON or Text was edited (not saved yet). */
    private final javax.swing.Timer recount = new javax.swing.Timer(400, e -> recountEdits());
    private final com.intellij.ui.components.ActionLink seeChanges =
            new com.intellij.ui.components.ActionLink("See what changed", e -> { if (position > 0) showStep(position); });

    /**
     * @param leftTitle  e.g. "Saved 10:32 · Flow 1"
     * @param rightTitle e.g. "Now" or "Saved 10:45 · Flow 2"
     */
    public static void show(@Nullable Project project, String title, FlowValue left, String leftTitle, FlowValue right, String rightTitle) {
        show(project, title, null, left, leftTitle, null, right, rightTitle);
    }

    /** Two saved values: edits made here are saved to them (with their History). */
    public static void show(@Nullable Project project, String title, FlowSnapshot left, String leftTitle, FlowSnapshot right, String rightTitle) {
        show(project, title, left, left.value, leftTitle, right, right.value, rightTitle);
    }

    /** @param leftSnap the saved value on the left, or null (then edits stay in this window); likewise rightSnap */
    public static void show(@Nullable Project project, String title, @Nullable FlowSnapshot leftSnap, FlowValue left, String leftTitle,
                            @Nullable FlowSnapshot rightSnap, FlowValue right, String rightTitle) {
        FlowCompareDialog d = new FlowCompareDialog(project, title, left, leftTitle, right, rightTitle);
        d.leftSnap = leftSnap;
        d.rightSnap = rightSnap;
        JPanel editing = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        d.undo.setToolTipText("Undo the last change made here (Ctrl+Z)");
        d.redo.setToolTipText("Redo it (Ctrl+Shift+Z)");
        d.history.setToolTipText("The changes made here; pick one to go back to it");
        d.reset.setToolTipText("Both sides back to the data as it was saved (can be undone)");
        d.saveEdits.setToolTipText("Save what you changed in the JSON or Text view (Ctrl+S)");
        d.undo.addActionListener(e -> d.undo());
        d.redo.addActionListener(e -> d.redo());
        d.history.addActionListener(e -> d.showHistory());
        d.reset.addActionListener(e -> d.reset());
        d.saveEdits.addActionListener(e -> d.saveTextEdits());
        editing.add(d.undo);
        editing.add(d.redo);
        editing.add(d.history);
        editing.add(d.reset);
        editing.add(d.saveEdits);
        d.editHint.setForeground(UIUtil.getContextHelpForeground());
        editing.add(d.editHint);
        d.seeChanges.setToolTipText("The last change, before and after, side by side");
        editing.add(d.seeChanges);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0));
        javax.swing.JButton swap = new javax.swing.JButton("⇄ Swap Old / New");
        swap.setToolTipText("Make the other side the new data (Ctrl+Shift+X): what was added shows as removed, and so on");
        swap.addActionListener(e -> d.swap());
        buttons.add(swap);
        javax.swing.JButton copy = new javax.swing.JButton("Copy Differences");
        javax.swing.JButton close = new javax.swing.JButton("Close");
        buttons.add(copy);
        buttons.add(close);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(editing, BorderLayout.WEST);
        bottom.add(buttons, BorderLayout.EAST);
        bottom.setBorder(JBUI.Borders.emptyTop(8));
        JPanel root = new JPanel(new BorderLayout());
        root.add(d.createCenterPanel(), BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        int menu = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        key(root, javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, menu), "undo", d::undo);
        key(root, javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, menu | java.awt.event.InputEvent.SHIFT_DOWN_MASK), "redo", d::redo);
        key(root, javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, menu), "save", d::saveTextEdits);
        d.updateEditButtons();
        root.setBorder(JBUI.Borders.empty(10, 12, 10, 12));
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_X,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | java.awt.event.InputEvent.SHIFT_DOWN_MASK), "traceweave.swap");
        root.getActionMap().put("traceweave.swap", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { d.swap(); }
        });
        FlowWindow window = new FlowWindow(project, "TraceWeave.Compare", title, root, null);
        window.whenClosed(() -> Disposer.dispose(d.disposable));
        d.window = window;
        copy.addActionListener(e -> FlowCopy.toClipboard(d.differencesText()));
        close.addActionListener(e -> window.close());
        window.show();
    }

    private @Nullable FlowWindow window;

    private static void key(JComponent root, javax.swing.KeyStroke stroke, String name, Runnable run) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, "traceweave." + name);
        root.getActionMap().put("traceweave." + name, new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { run.run(); }
        });
    }

    private FlowCompareDialog(@Nullable Project project, String title, FlowValue left, String leftTitle, FlowValue right, String rightTitle) {
        this.project = project;
        this.title = title;
        this.left = left;
        this.right = right;
        this.all = FlowValueDiff.compare(left, right);
        this.leftTitle = leftTitle;
        this.rightTitle = rightTitle;
        onlyChanges.addActionListener(e -> refill());
        recount.setRepeats(false);
        Disposer.register(disposable, recount::stop);
        steps.add(new State(left.copy(), right.copy(), "Opened", System.currentTimeMillis()));
        refill();
    }

    private JComponent createCenterPanel() {
        JBTable table = new JBTable(model);
        this.table = table;
        table.setShowGrid(false);
        table.setStriped(false);
        table.setRowHeight(JBUI.scale(26));
        table.getTableHeader().setReorderingAllowed(false);
        ColoredTableCellRenderer renderer = new ColoredTableCellRenderer() {
            @Override
            protected void customizeCellRenderer(@NotNull JTable t, @Nullable Object value, boolean selected, boolean focus, int row, int column) {
                FlowValueDiff.Row r = shown.get(row);
                if (!selected) setBackground(background(r.kind(), t.getBackground()));
                setBorder(JBUI.Borders.empty(0, 6));
                switch (column) {
                    case 0 -> {
                        append(mark(r.kind()), new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, markColor(r.kind())));
                        setToolTipText(describe(r.kind()));
                    }
                    case 1 -> {
                        setIpad(JBUI.insetsLeft(r.depth() * 16));
                        append(r.name(), r.depth() == 0 ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
                        if (r.type() != null && !r.type().isEmpty()) append("  " + r.type(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                        setToolTipText(r.label());
                    }
                    default -> {
                        setIpad(JBUI.emptyInsets());
                        String v = column == 2 ? r.left() : r.right();
                        boolean differs = r.kind() != FlowValueDiff.Kind.SAME;
                        if (v == null) append("—  not there", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                        else append(FlowValueDiff.withoutIds(v), differs ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
                        setToolTipText(v);
                    }
                }
            }
        };
        for (int i = 0; i < 4; i++) table.getColumnModel().getColumn(i).setCellRenderer(renderer);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int r = table.rowAtPoint(e.getPoint()), c = table.columnAtPoint(e.getPoint());
                if (r >= 0 && c >= 2) editCell(shown.get(r), c == 2);
            }
        });
        table.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(34));
        table.getColumnModel().getColumn(0).setMinWidth(JBUI.scale(34));
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(260));

        chips.setOpaque(false);
        fillChips();
        JBLabel hint = new JBLabel("Map entries are matched by key, list elements by value; instance ids like @1141 are ignored. "
                + "Double-click a value to change it; in JSON and Text, edit either side and Save Edits.");
        hint.setForeground(UIUtil.getContextHelpForeground());
        hint.setFont(JBUI.Fonts.smallFont());

        // Tree (field by field), JSON or Text (both in IntelliJ's diff viewer)
        FlowToolbar views = new FlowToolbar();
        List<FlowButton> segments = new ArrayList<>();
        for (String[] v : new String[][]{{"TREE", "Tree", "Every field side by side, what differs marked"},
                {"JSON", "JSON", "Both values as JSON (keys sorted, instance ids left out) in IntelliJ's diff viewer"},
                {"TEXT", "Text", "Both values as indented text in IntelliJ's diff viewer"}}) {
            FlowButton b = new FlowButton(v[1], null, FlowButton.Style.SEGMENT);
            b.setToolTipText(v[2]);
            b.addActionListener(e -> showView(v[0]));
            viewButtons.put(v[0], b);
            segments.add(b);
        }
        views.addGroup(true, 3, segments.toArray(new FlowButton[0]));

        JPanel top = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        JPanel row = new JPanel(new BorderLayout(JBUI.scale(12), 0));
        row.add(views, BorderLayout.WEST);
        row.add(chips, BorderLayout.CENTER);
        row.add(onlyChanges, BorderLayout.EAST);
        top.add(row, BorderLayout.NORTH);
        top.add(hint, BorderLayout.SOUTH);
        top.setBorder(JBUI.Borders.emptyBottom(8));

        cards.add(new JBScrollPane(table), "TREE");
        made.add("TREE");
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(top, BorderLayout.NORTH);
        panel.add(cards, BorderLayout.CENTER);
        showView("TREE");
        panel.setPreferredSize(new Dimension(JBUI.scale(960), JBUI.scale(560)));
        return panel;
    }

    private int count(FlowValueDiff.Kind kind) {
        int n = 0;
        for (FlowValueDiff.Row r : all) if (r.kind() == kind) n++;
        return n;
    }

    private static Color background(FlowValueDiff.Kind kind, Color usual) {
        return switch (kind) {
            case CHANGED -> CHANGED;
            case ADDED -> ADDED;
            case REMOVED -> REMOVED;
            default -> usual;
        };
    }

    private static String mark(FlowValueDiff.Kind kind) {
        return switch (kind) {
            case CHANGED -> "~";
            case ADDED -> "+";
            case REMOVED -> "−";
            default -> "";
        };
    }

    private static Color markColor(FlowValueDiff.Kind kind) {
        return switch (kind) {
            case CHANGED -> CHANGED_MARK;
            case ADDED -> ADDED_MARK;
            case REMOVED -> REMOVED_MARK;
            default -> UIUtil.getContextHelpForeground();
        };
    }

    private static @Nullable String describe(FlowValueDiff.Kind kind) {
        return switch (kind) {
            case CHANGED -> "Changed";
            case ADDED -> "Only in the new data (added)";
            case REMOVED -> "Only in the old data (removed)";
            default -> null;
        };
    }

    private void fillChips() {
        fillChips(all, false);
    }

    /** The counts of a comparison; {@code pending}: of edits not saved yet (typed or pasted in JSON / Text). */
    private void fillChips(List<FlowValueDiff.Row> rows, boolean pending) {
        chips.removeAll();
        int changed = 0, added = 0, removed = 0;
        for (FlowValueDiff.Row r : rows) {
            if (r.kind() == FlowValueDiff.Kind.CHANGED) changed++;
            if (r.kind() == FlowValueDiff.Kind.ADDED) added++;
            if (r.kind() == FlowValueDiff.Kind.REMOVED) removed++;
        }
        if (changed + added + removed == 0) {
            JBLabel same = new JBLabel("Same data on both sides", AllIcons.General.InspectionsOK, JBLabel.LEFT);
            same.setFont(JBUI.Fonts.label().asBold());
            chips.add(same);
        } else {
            chips.add(new Chip(changed + " changed", CHANGED, CHANGED_MARK));
            chips.add(new Chip(added + " added", ADDED, ADDED_MARK));
            chips.add(new Chip(removed + " removed", REMOVED, REMOVED_MARK));
        }
        if (pending) {
            JBLabel unsaved = new JBLabel("with your edits (not saved yet: Save Edits)");
            unsaved.setForeground(CHANGED_MARK);
            chips.add(unsaved);
        }
        chips.revalidate();
        chips.repaint();
    }

    /** The other side becomes the new data: everything compared again the other way round. */
    private void swap() {
        FlowValue v = left;
        left = right;
        right = v;
        FlowSnapshot sv = leftSnap;
        leftSnap = rightSnap;
        rightSnap = sv;
        for (int i = 0; i < steps.size(); i++) {
            State s = steps.get(i);
            steps.set(i, new State(s.right(), s.left(), s.label(), s.at()));
        }
        String t = leftTitle;
        leftTitle = rightTitle;
        rightTitle = t;
        all = FlowValueDiff.compare(left, right);
        fillChips();
        refill();
        if (table != null) {
            table.getColumnModel().getColumn(2).setHeaderValue(model.getColumnName(2));
            table.getColumnModel().getColumn(3).setHeaderValue(model.getColumnName(3));
            table.getTableHeader().repaint();
        }
        // the diff viewers are made again, the other way round
        for (String k : new String[]{"JSON", "TEXT"}) {
            if (made.remove(k)) {
                for (java.awt.Component c : cards.getComponents()) {
                    if (k.equals(c.getName())) cards.remove(c);
                }
            }
        }
        showView(view);
    }

    /** Shows the Tree, JSON or Text view (the diff viewers are made the first time they're shown). */
    private void showView(String view) {
        this.view = view;
        if (made.add(view)) {
            boolean json = view.equals("JSON");
            String a = json ? FlowValueText.valueJson(left, true) : FlowValueDiff.withoutIds(FlowValueText.toText(left));
            String b = json ? FlowValueText.valueJson(right, true) : FlowValueDiff.withoutIds(FlowValueText.toText(right));
            DiffContentFactory f = DiffContentFactory.getInstance();
            com.intellij.openapi.fileTypes.FileType type = FileTypeManager.getInstance().getFileTypeByExtension(json ? "json" : "txt");
            DiffRequestPanel diff = DiffManager.getInstance().createRequestPanel(project, disposable, null);
            com.intellij.diff.contents.DocumentContent da = f.createEditable(project, a, type), db = f.createEditable(project, b, type);
            docs.put(view, new com.intellij.diff.contents.DocumentContent[]{da, db});
            // typing or pasting: the counts follow at once (as a preview until Save Edits)
            com.intellij.openapi.editor.event.DocumentListener typed = new com.intellij.openapi.editor.event.DocumentListener() {
                @Override public void documentChanged(@NotNull com.intellij.openapi.editor.event.DocumentEvent e) { recount.restart(); }
            };
            da.getDocument().addDocumentListener(typed, disposable);
            db.getDocument().addDocumentListener(typed, disposable);
            shownTexts.put(view, new String[]{a, b});
            diff.setRequest(new SimpleDiffRequest(title, da, db, "Old · " + leftTitle, "New · " + rightTitle));
            diff.getComponent().setName(view);
            cards.add(diff.getComponent(), view);
        }
        ((CardLayout) cards.getLayout()).show(cards, view);
        viewButtons.forEach((k, b) -> b.setSelected(k.equals(view)));
        onlyChanges.setVisible(view.equals("TREE"));
        updateEditButtons();
    }

    // ---------- editing, undo, redo, history, reset ----------

    /** Tree: a value changed by double-click (a field with fields inside is changed as JSON). */
    private void editCell(FlowValueDiff.Row row, boolean onLeft) {
        String path = onLeft ? row.leftPath() : row.rightPath();
        if (path == null) {
            com.intellij.openapi.ui.Messages.showInfoMessage(cards, row.name() + " isn't in the " + (onLeft ? "old" : "new")
                    + " data. Add it in the JSON or Text view, then Save Edits.", "Compare");
            return;
        }
        FlowValue side = onLeft ? left : right;
        FlowValue node = FlowDebugStore.at(side, path);
        if (node == null) return;
        boolean deep = !node.children.isEmpty();
        String now = com.intellij.openapi.ui.Messages.showMultilineInputDialog(project,
                (deep ? "New value of " + row.label() + " (as JSON):" : "New value of " + row.label() + ":"),
                "Edit " + (onLeft ? "Old" : "New") + " Data", deep ? FlowValueText.valueJson(node, true) : node.value, null, null);
        if (now == null) return;
        FlowValue changed = side.copy();
        FlowValue target = FlowDebugStore.at(changed, path);
        if (target == null) return;
        if (deep) {
            FlowValue merged;
            try {
                merged = FlowValueText.applyJson(target, now);
            } catch (IllegalArgumentException ex) {
                com.intellij.openapi.ui.Messages.showErrorDialog(cards, ex.getMessage(), "Edit");
                return;
            }
            target.value = merged.value;
            target.children.clear();
            target.children.addAll(merged.children);
        } else {
            if (now.equals(target.value)) return;
            target.value = now;
            if (target.type.isEmpty()) target.type = FlowValueText.guessType(now);
        }
        fixEntryAbove(changed, path);
        String label = (onLeft ? "Old" : "New") + ": " + row.label() + " = " + FlowValueDiff.withoutIds(now.strip());
        change(onLeft ? changed : left.copy(), onLeft ? right.copy() : changed, label);
    }

    /** When the changed value is a map entry's value or key, the entry's own text follows it. */
    private static void fixEntryAbove(FlowValue root, String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return;
        FlowValue entry = FlowDebugStore.at(root, path.substring(0, dot));
        if (entry == null || !FlowValueText.isMapEntry(entry)) return;
        int up = path.substring(0, dot).lastIndexOf('.');
        FlowValueText.fixEntryText(entry, FlowDebugStore.at(root, up < 0 ? "" : path.substring(0, up)));
    }

    /** The counts with the edits typed in the JSON or Text view (while it can be read; otherwise they stay). */
    private void recountEdits() {
        if (view.equals("TREE")) return;
        com.intellij.diff.contents.DocumentContent[] d = docs.get(view);
        String[] was = shownTexts.get(view);
        if (d == null || was == null) return;
        String a = d[0].getDocument().getText(), b = d[1].getDocument().getText();
        boolean edited = !a.equals(was[0]) || !b.equals(was[1]);
        try {
            FlowValue nl = a.equals(was[0]) ? left : read(a, left);
            FlowValue nr = b.equals(was[1]) ? right : read(b, right);
            fillChips(FlowValueDiff.compare(nl, nr), edited);
        } catch (RuntimeException ex) {
            // not readable yet (still typing): the counts stay as they are
        }
    }

    /** JSON / Text: what was changed on either side is read back (maps stay maps, unchanged values keep their ids). */
    private void saveTextEdits() {
        if (view.equals("TREE")) return;
        com.intellij.diff.contents.DocumentContent[] d = docs.get(view);
        String[] was = shownTexts.get(view);
        if (d == null || was == null) return;
        String a = d[0].getDocument().getText(), b = d[1].getDocument().getText();
        if (a.equals(was[0]) && b.equals(was[1])) {
            editHint.setText("Nothing changed in the " + view.toLowerCase() + " yet.");
            return;
        }
        FlowValue nl, nr;
        try {
            nl = a.equals(was[0]) ? left.copy() : read(a, left);
            nr = b.equals(was[1]) ? right.copy() : read(b, right);
        } catch (IllegalArgumentException ex) {
            com.intellij.openapi.ui.Messages.showErrorDialog(cards, ex.getMessage() + "\n\nFix it and Save Edits again.", "Compare");
            return;
        }
        String sides = !a.equals(was[0]) && !b.equals(was[1]) ? "Old and New" : !a.equals(was[0]) ? "Old" : "New";
        change(nl, nr, sides + ": edited as " + (view.equals("JSON") ? "JSON" : "text"));
    }

    private FlowValue read(String text, FlowValue old) {
        if (view.equals("JSON")) return FlowValueText.applyJson(old, text);
        FlowValue v = FlowValueText.fromText(text);
        FlowValueText.keepIds(v, old);
        v.name = old.name;
        return v;
    }

    /** A change made here: saved to the saved values (with History) and kept as a step for Undo / Redo. */
    private void change(FlowValue newLeft, FlowValue newRight, String label) {
        if (FlowValueText.toText(newLeft).equals(FlowValueText.toText(left))
                && FlowValueText.toText(newRight).equals(FlowValueText.toText(right))) {
            return;
        }
        put(newLeft, newRight, "Compare: " + label);
        while (steps.size() > position + 1) steps.remove(steps.size() - 1);
        steps.add(new State(left.copy(), right.copy(), label, System.currentTimeMillis()));
        position = steps.size() - 1;
        editHint.setText(label + (leftSnap != null || rightSnap != null ? " (saved)" : ""));
        refreshAll();
    }

    /** Both sides as given: a saved side is saved (as one change in its History), the other just shown. */
    private void put(FlowValue newLeft, FlowValue newRight, String how) {
        FlowDebugStore store = project == null ? null : FlowDebugStore.get(project);
        if (!FlowValueText.toText(newLeft).equals(FlowValueText.toText(left))) {
            if (leftSnap != null && store != null) {
                store.replaceValue(leftSnap, newLeft.copy(), how);
                left = leftSnap.value;
            } else {
                left = newLeft;
            }
        }
        if (!FlowValueText.toText(newRight).equals(FlowValueText.toText(right))) {
            if (rightSnap != null && store != null) {
                store.replaceValue(rightSnap, newRight.copy(), how);
                right = rightSnap.value;
            } else {
                right = newRight;
            }
        }
    }

    private void goTo(int step, String how) {
        if (step < 0 || step >= steps.size() || step == position) return;
        State s = steps.get(step);
        put(s.left().copy(), s.right().copy(), "Compare: " + how);
        position = step;
        editHint.setText(how + ": " + s.label());
        refreshAll();
    }

    private void undo() {
        goTo(position - 1, "Undone");
    }

    private void redo() {
        goTo(position + 1, "Redone");
    }

    /** Both sides back to the data as it was saved (a saved value's first data, otherwise as it was opened). */
    private void reset() {
        FlowValue nl = leftSnap != null && leftSnap.original != null ? leftSnap.original.copy() : steps.get(0).left().copy();
        FlowValue nr = rightSnap != null && rightSnap.original != null ? rightSnap.original.copy() : steps.get(0).right().copy();
        change(nl, nr, "Reset to the saved data");
    }

    /** One line of the History: a step taken in this window, a saved change of one side, or a heading. */
    private record Item(String text, int step, @Nullable FlowSnapshot snap, @Nullable com.example.flowtracker.model.FlowValueEdit edit) {}

    /**
     * The History: the steps taken in this window (Go To goes back or forward to one), and each saved side's own
     * History from Debug Data (Revert undoes one change); Show Changes shows what a step or change did.
     */
    private void showHistory() {
        javax.swing.DefaultListModel<Item> m = new javax.swing.DefaultListModel<>();
        java.text.SimpleDateFormat time = new java.text.SimpleDateFormat("dd MMM HH:mm:ss");
        m.addElement(new Item("IN THIS WINDOW", -1, null, null));
        for (int i = 0; i < steps.size(); i++) {
            State st = steps.get(i);
            m.addElement(new Item((i == position ? "▶ " : "   ") + time.format(new java.util.Date(st.at())) + "   " + st.label(), i, null, null));
        }
        for (FlowSnapshot snap : new FlowSnapshot[]{leftSnap, rightSnap}) {
            if (snap == null || snap.edits.isEmpty()) continue;
            m.addElement(new Item((snap == leftSnap ? "OLD · " + leftTitle : "NEW · " + rightTitle).toUpperCase()
                    + " · SAVED CHANGES (DEBUG DATA HISTORY)", -1, null, null));
            List<com.example.flowtracker.model.FlowValueEdit> edits = new ArrayList<>(snap.edits);
            java.util.Collections.reverse(edits);
            for (com.example.flowtracker.model.FlowValueEdit e : edits) {
                String kind = e.kind == null ? "" : e.kind;
                String what = switch (kind) {
                    case "ADD" -> "added " + e.after;
                    case "REMOVE" -> "removed " + e.before;
                    case "NAME" -> "renamed " + e.before + " → " + e.after;
                    case "REPLACE" -> e.after;
                    default -> e.before + " → " + e.after;
                };
                m.addElement(new Item("   " + time.format(new java.util.Date(e.at)) + "   " + e.label + ": " + what, -1, snap, e));
            }
        }
        com.intellij.ui.components.JBList<Item> list = new com.intellij.ui.components.JBList<>(m);
        list.setCellRenderer(new com.intellij.ui.ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(@NotNull javax.swing.JList<? extends Item> l, Item it, int index, boolean selected, boolean focus) {
                boolean heading = it.step() < 0 && it.edit() == null;
                append(it.text(), heading ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, UIUtil.getContextHelpForeground())
                        : it.step() == position ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            }
        });
        list.setSelectedIndex(1 + position);
        javax.swing.JButton goTo = new javax.swing.JButton("Go To");
        javax.swing.JButton changes = new javax.swing.JButton("Show Changes");
        javax.swing.JButton revert = new javax.swing.JButton("Revert");
        goTo.setToolTipText("Both sides as they were after this step (it can be undone)");
        changes.setToolTipText("What this step or change did, side by side");
        revert.setToolTipText("Undo this saved change (kept in History as a new change)");
        Runnable enable = () -> {
            Item it = list.getSelectedValue();
            goTo.setEnabled(it != null && it.step() >= 0 && it.step() != position);
            changes.setEnabled(it != null && (it.step() > 0 || it.edit() != null));
            revert.setEnabled(it != null && it.edit() != null);
        };
        list.addListSelectionListener(e -> enable.run());
        enable.run();
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        actions.add(goTo);
        actions.add(changes);
        actions.add(revert);
        JPanel p = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        p.add(new JBLabel("▶ marks where you are. Go To goes back (or forward) to a step; Revert undoes a saved change; "
                + "Show Changes shows what it did."), BorderLayout.NORTH);
        p.add(new JBScrollPane(list), BorderLayout.CENTER);
        p.add(actions, BorderLayout.SOUTH);
        p.setPreferredSize(new Dimension(JBUI.scale(760), JBUI.scale(380)));
        com.intellij.openapi.ui.DialogBuilder b = new com.intellij.openapi.ui.DialogBuilder(cards);
        b.setTitle("Compare History");
        b.setCenterPanel(p);
        b.addCloseButton();
        goTo.addActionListener(e -> {
            Item it = list.getSelectedValue();
            if (it == null || it.step() < 0) return;
            goTo(it.step(), "Went back");
            b.getDialogWrapper().close(com.intellij.openapi.ui.DialogWrapper.OK_EXIT_CODE);
        });
        changes.addActionListener(e -> {
            Item it = list.getSelectedValue();
            if (it != null) showChanges(it);
        });
        revert.addActionListener(e -> {
            Item it = list.getSelectedValue();
            if (it == null || it.edit() == null || it.snap() == null || project == null) return;
            FlowDebugStore.get(project).revert(it.snap(), it.edit());
            afterSavedChange("Reverted: " + it.edit().label);
            b.getDialogWrapper().close(com.intellij.openapi.ui.DialogWrapper.OK_EXIT_CODE);
        });
        b.show();
    }

    /** What a step (or a saved change) did: before and after, side by side in IntelliJ's diff viewer. */
    private void showChanges(Item it) {
        if (it.edit() != null) {
            com.example.flowtracker.model.FlowValueEdit e = it.edit();
            String before = "REPLACE".equals(e.kind) && e.item != null ? FlowValueText.toText(e.item) : e.before;
            String after = "REPLACE".equals(e.kind) && it.snap() != null ? FlowValueText.toText(it.snap().value) + "\n(as it is now)" : e.after;
            FlowDiffs.show(project, "Change: " + e.label, "Before", FlowValueDiff.withoutIds(before), "After",
                    FlowValueDiff.withoutIds(after), "txt");
            return;
        }
        if (it.step() <= 0) return;
        showStep(it.step());
    }

    /** The changes of one step: each side it changed, before and after. */
    private void showStep(int step) {
        State was = steps.get(step - 1), now = steps.get(step);
        boolean l = !FlowValueText.toText(was.left()).equals(FlowValueText.toText(now.left()));
        boolean r = !FlowValueText.toText(was.right()).equals(FlowValueText.toText(now.right()));
        if (l) FlowDiffs.show(project, "What changed (Old): " + now.label(), "Old · before", FlowValueText.valueJson(was.left(), true),
                "Old · after", FlowValueText.valueJson(now.left(), true), "json");
        if (r) FlowDiffs.show(project, "What changed (New): " + now.label(), "New · before", FlowValueText.valueJson(was.right(), true),
                "New · after", FlowValueText.valueJson(now.right(), true), "json");
    }

    /** A saved value was changed outside the steps (Revert): both sides are read again and kept as a step. */
    private void afterSavedChange(String label) {
        if (leftSnap != null) left = leftSnap.value;
        if (rightSnap != null) right = rightSnap.value;
        while (steps.size() > position + 1) steps.remove(steps.size() - 1);
        steps.add(new State(left.copy(), right.copy(), label, System.currentTimeMillis()));
        position = steps.size() - 1;
        editHint.setText(label);
        refreshAll();
    }

    /** Everything compared and shown again after a change. */
    private void refreshAll() {
        all = FlowValueDiff.compare(left, right);
        fillChips();
        refill();
        for (String k : new String[]{"JSON", "TEXT"}) {
            if (made.remove(k)) {
                for (java.awt.Component c : cards.getComponents()) {
                    if (k.equals(c.getName())) cards.remove(c);
                }
                docs.remove(k);
                shownTexts.remove(k);
            }
        }
        showView(view);
    }

    private void updateEditButtons() {
        undo.setEnabled(position > 0);
        redo.setEnabled(position < steps.size() - 1);
        history.setEnabled(steps.size() > 1 || leftSnap != null && !leftSnap.edits.isEmpty()
                || rightSnap != null && !rightSnap.edits.isEmpty());
        seeChanges.setVisible(position > 0);
        saveEdits.setVisible(!view.equals("TREE"));
    }

    /** The rows that differ as text: "~ nightlyRates › \"goa\": 2500.0 → 2600.0". */
    private String differencesText() {
        StringBuilder sb = new StringBuilder(title).append('\n').append(leftTitle).append("  →  ").append(rightTitle).append('\n');
        for (FlowValueDiff.Row r : all) {
            if (r.kind() == FlowValueDiff.Kind.SAME) continue;
            sb.append(mark(r.kind())).append(' ').append(r.label()).append(": ")
                    .append(r.left() == null ? "(not there)" : FlowValueDiff.withoutIds(r.left())).append("  →  ")
                    .append(r.right() == null ? "(not there)" : FlowValueDiff.withoutIds(r.right())).append('\n');
        }
        return sb.toString();
    }

    /** All rows, or the ones that differ (with the rows above them, so each difference shows where it is). */
    private void refill() {
        shown.clear();
        if (!onlyChanges.isSelected()) {
            shown.addAll(all);
        } else {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).kind() == FlowValueDiff.Kind.SAME && !leadsToChange(i)) continue;
                shown.add(all.get(i));
            }
        }
        model.fireTableDataChanged();
    }

    /** True when a row is a parent of a row that differs. */
    private boolean leadsToChange(int i) {
        int depth = all.get(i).depth();
        for (int j = i + 1; j < all.size() && all.get(j).depth() > depth; j++) {
            if (all.get(j).kind() != FlowValueDiff.Kind.SAME) return true;
        }
        return false;
    }

    private final class Model extends AbstractTableModel {
        @Override public int getRowCount() { return shown.size(); }
        @Override public int getColumnCount() { return 4; }
        @Override public String getColumnName(int c) { return c == 0 ? "" : c == 1 ? "Name" : c == 2 ? "Old · " + leftTitle : "New · " + rightTitle; }
        @Override public Object getValueAt(int r, int c) { return shown.get(r); }
    }

    /** A rounded count label: "3 changed". */
    private static final class Chip extends JBLabel {
        private final Color fill, line;

        Chip(String text, Color fill, Color line) {
            super(text);
            this.fill = fill;
            this.line = line;
            setBorder(JBUI.Borders.empty(3, 10));
            setForeground(line);
            setFont(JBUI.Fonts.label().asBold());
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            g2.setColor(line);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
