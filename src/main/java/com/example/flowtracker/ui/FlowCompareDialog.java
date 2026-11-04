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
    /** The counts: they wrap onto another line when the window is narrow instead of being cut off. */
    private final JPanel chips = new JPanel(new com.intellij.util.ui.WrapLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4)));
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
    private final FlowButton undo = new FlowButton("Undo", FlowTheme.ICON_UNDO, FlowButton.Style.OUTLINE);
    private final FlowButton redo = new FlowButton("Redo", FlowTheme.ICON_REDO, FlowButton.Style.OUTLINE);
    private final FlowButton history = new FlowButton("History", com.intellij.icons.AllIcons.Vcs.History, FlowButton.Style.OUTLINE);
    private final FlowButton reset = new FlowButton("Reset", com.intellij.icons.AllIcons.Actions.Rollback, FlowButton.Style.OUTLINE);
    private final FlowButton saveEdits = new FlowButton("Save Edits", com.intellij.icons.AllIcons.Actions.MenuSaveall, FlowButton.Style.PRIMARY);
    /** How the values are shown (never how they are saved): sorted by name, only the fields that match the filter. */
    private com.example.flowtracker.model.FlowValueShape.Sort sort = com.example.flowtracker.model.FlowValueShape.Sort.AS_SAVED;
    private final com.intellij.ui.SearchTextField filter = new com.intellij.ui.SearchTextField(false);
    private final java.util.Map<com.example.flowtracker.model.FlowValueShape.Sort, FlowButton> sortButtons = new java.util.EnumMap<>(com.example.flowtracker.model.FlowValueShape.Sort.class);
    /** Tree rows folded (by where they are) and the rows that have fields under them. */
    private final java.util.Set<String> collapsed = new java.util.HashSet<>(), parents = new java.util.HashSet<>();
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
        FlowWindow[] window = {null};
        Built built = build(project, title, leftSnap, left, leftTitle, rightSnap, right, rightTitle, "Close", () -> window[0].close());
        window[0] = new FlowWindow(project, "TraceWeave.Compare", title, built.root(), null);
        window[0].whenClosed(() -> Disposer.dispose(built.disposable()));
        window[0].show();
    }

    /** The Compare view, to put in a window; dispose {@code disposable} when it is taken away. */
    public record Built(JComponent root, Disposable disposable) {}

    /**
     * The Compare view without a window of its own (e.g. in the Compare Pasted Data window).
     *
     * @param closeLabel the label of the button that closes it ("Close", "Back to Edit")
     */
    public static Built build(@Nullable Project project, String title, @Nullable FlowSnapshot leftSnap, FlowValue left, String leftTitle,
                              @Nullable FlowSnapshot rightSnap, FlowValue right, String rightTitle, String closeLabel, Runnable onClose) {
        FlowCompareDialog d = new FlowCompareDialog(project, title, left, leftTitle, right, rightTitle);
        d.leftSnap = leftSnap;
        d.rightSnap = rightSnap;
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
        d.editHint.setForeground(UIUtil.getContextHelpForeground());
        d.seeChanges.setToolTipText("The last change, before and after, side by side");
        FlowButton swap = new FlowButton("Swap Old / New", com.intellij.icons.AllIcons.Diff.ArrowLeftRight, FlowButton.Style.OUTLINE);
        swap.setToolTipText("Make the other side the new data (Ctrl+Shift+X): what was added shows as removed, and so on");
        swap.addActionListener(e -> d.swap());
        FlowButton openFile = new FlowButton("Open File…", FlowTheme.ICON_IMPORT, FlowButton.Style.OUTLINE);
        openFile.setToolTipText("Compare with a file: put a .json or .flowdebug.json file on the Old or the New side");
        openFile.addActionListener(e -> d.fileMenu(openFile));
        FlowButton export = new FlowButton("Export…", FlowTheme.ICON_EXPORT, FlowButton.Style.OUTLINE);
        export.setToolTipText("Save the Old data, the New data or the differences to a file");
        export.addActionListener(e -> d.exportMenu(export));
        FlowButton copy = new FlowButton("Copy Differences", FlowTheme.ICON_COPY, FlowButton.Style.OUTLINE);
        copy.setToolTipText("Copy the list of differences as text");
        FlowButton close = new FlowButton(closeLabel, null, FlowButton.Style.OUTLINE);
        // the editing buttons on the left, the files and Close on the right; they wrap (then show icons only) when narrow
        FlowToolbar bar = new FlowToolbar();
        bar.addGroup(false, 0, d.undo, d.redo);
        bar.addGroup(false, 1, d.history, d.reset);
        bar.addGroup(false, 2, d.saveEdits);
        bar.addTrailingGroup(1, swap);
        bar.addTrailingGroup(1, openFile, export, copy);
        bar.addTrailingGroup(2, close);
        JPanel note = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        note.add(d.editHint);
        note.add(d.seeChanges);
        JPanel bottom = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        bottom.add(note, BorderLayout.NORTH);
        bottom.add(bar, BorderLayout.CENTER);
        bottom.setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(FlowTheme.CARD_BORDER, 1, 0, 0, 0), JBUI.Borders.emptyTop(8)));
        JPanel root = new JPanel(new BorderLayout());
        root.add(d.createCenterPanel(), BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        int menu = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        key(root, javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, menu), "undo", d::undo);
        com.intellij.openapi.project.DumbAwareAction.create(e -> d.find()).registerCustomShortcutSet(
                new com.intellij.openapi.actionSystem.CustomShortcutSet(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F, menu)),
                root, d.disposable);
        key(root, javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, menu | java.awt.event.InputEvent.SHIFT_DOWN_MASK), "redo", d::redo);
        key(root, javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, menu), "save", d::saveTextEdits);
        d.updateEditButtons();
        root.setBorder(JBUI.Borders.empty(10, 12, 10, 12));
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_X,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | java.awt.event.InputEvent.SHIFT_DOWN_MASK), "traceweave.swap");
        root.getActionMap().put("traceweave.swap", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { d.swap(); }
        });
        copy.addActionListener(e -> FlowCopy.toClipboard(d.differencesText()));
        close.addActionListener(e -> onClose.run());
        return new Built(root, d.disposable);
    }

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
        this.all = compareShown(); // as shown: as saved, without Java internals when they are hidden
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
                        if (parents.contains(r.label())) {
                            append(collapsed.contains(r.label()) ? "▸ " : "▾ ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                        } else {
                            append("   ", SimpleTextAttributes.REGULAR_ATTRIBUTES);
                        }
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
                if (e.getClickCount() == 1 && table.columnAtPoint(e.getPoint()) == 1) {
                    int r = table.rowAtPoint(e.getPoint());
                    String label = r < 0 ? null : shown.get(r).label();
                    if (label != null && parents.contains(label) && e.getX() - table.getCellRect(r, 1, false).x
                            < JBUI.scale(16) * (shown.get(r).depth() + 1) + JBUI.scale(8)) { // the ▸ / ▾ in front of the name
                        if (!collapsed.remove(label)) collapsed.add(label);
                        refill();
                    }
                    return;
                }
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
        FlowToolbar shape = new FlowToolbar();
        FlowButton expand = new FlowButton("Expand All", com.intellij.icons.AllIcons.Actions.Expandall, FlowButton.Style.OUTLINE);
        expand.setToolTipText("Open every field (Tree) or block (JSON, Text)");
        expand.addActionListener(e -> expandAll());
        FlowButton collapse = new FlowButton("Collapse All", com.intellij.icons.AllIcons.Actions.Collapseall, FlowButton.Style.OUTLINE);
        collapse.setToolTipText("Fold every field (Tree) or block (JSON, Text): the values' own fields show; click ▸ to open one");
        collapse.addActionListener(e -> collapseAll());
        shape.addGroup(false, 0, expand, collapse);
        List<FlowButton> sorts = new ArrayList<>();
        for (com.example.flowtracker.model.FlowValueShape.Sort so : com.example.flowtracker.model.FlowValueShape.Sort.values()) {
            FlowButton b = new FlowButton(so.label, null, FlowButton.Style.SEGMENT);
            b.setToolTipText(so == com.example.flowtracker.model.FlowValueShape.Sort.AS_SAVED ? "The fields in the order they were saved"
                    : "The fields sorted by name (lists keep their order); only how they are shown");
            b.addActionListener(e -> setSort(so));
            sortButtons.put(so, b);
            sorts.add(b);
        }
        shape.addGroup(true, 3, sorts.toArray(new FlowButton[0]));
        sortButtons.get(sort).setSelected(true);
        FlowButton internals = new FlowButton("Hide Internals", com.intellij.icons.AllIcons.Actions.ToggleVisibility, FlowButton.Style.SEGMENT);
        internals.setSelected(com.example.flowtracker.model.FlowValueShape.hideInternals());
        internals.setToolTipText("<html><b>Hide Internals</b>: what a language or its tools put inside a value, left out (only how it is shown).<br>"
                + "Java, Kotlin, Scala, Groovy: a String's bytes, coder and hash as its text; an enum as its name; "
                + "BigDecimal, ArrayList and lazy values as what they hold;<br>compiler and framework fields "
                + "(this$0, $outer, x$delegate, $$_hibernate_interceptor, metaClass, modCount…).<br>"
                + ".NET: $id / $type / $ref, {$values} as its list, &lt;Name&gt;k__BackingField as Name. "
                + "Python and JavaScript: __dunder__ names, __proto__, __typename, py/… keys.<br>"
                + "One setting for Debug Data, Compare, Compare Pasted Data and Stop at Value.</html>");
        internals.addActionListener(e -> {
            com.example.flowtracker.model.FlowValueShape.setHideInternals(!com.example.flowtracker.model.FlowValueShape.hideInternals());
            internals.setSelected(com.example.flowtracker.model.FlowValueShape.hideInternals());
            refreshAll();
        });
        shape.addGroup(true, 1, internals);
        filter.getTextEditor().getEmptyText().setText("Filter the fields (name, value or type) · Ctrl+F");
        filter.addDocumentListener(new com.intellij.ui.DocumentAdapter() {
            @Override protected void textChanged(@NotNull javax.swing.event.DocumentEvent e) { refreshAll(); }
        });
        JPanel shapeRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        shapeRow.add(shape, BorderLayout.WEST);
        shapeRow.add(filter, BorderLayout.CENTER);
        JPanel rows = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        rows.add(row, BorderLayout.NORTH);
        rows.add(shapeRow, BorderLayout.SOUTH);
        top.add(rows, BorderLayout.NORTH);
        top.add(hint, BorderLayout.SOUTH);
        top.setBorder(JBUI.Borders.emptyBottom(8));

        cards.add(FlowScroll.wheel(new JBScrollPane(table)), "TREE");
        made.add("TREE");
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(top, BorderLayout.NORTH);
        JPanel viewsAndSides = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        sides.setOpaque(false);
        fillSides();
        viewsAndSides.add(sides, BorderLayout.NORTH);
        viewsAndSides.add(cards, BorderLayout.CENTER);
        panel.add(viewsAndSides, BorderLayout.CENTER);
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
        fillSides();
    }

    /** Which side is which, over every view (Tree, JSON, Text): OLD on the left, NEW on the right. */
    private final JPanel sides = new JPanel(new java.awt.GridLayout(1, 2, JBUI.scale(12), 0));

    private void fillSides() {
        sides.removeAll();
        JPanel l = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JPanel r = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        l.setOpaque(false);
        r.setOpaque(false);
        l.add(new Chip("OLD  ·  " + leftTitle, REMOVED, REMOVED_MARK));
        r.add(new Chip("NEW  ·  " + rightTitle, ADDED, ADDED_MARK));
        sides.add(l);
        sides.add(r);
        sides.revalidate();
        sides.repaint();
    }

    private static String hex(Color c) {
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
        all = compareShown();
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
            FlowValue l = shown(left), r = shown(right);
            String a = json ? FlowValueText.valueJson(l, sort == com.example.flowtracker.model.FlowValueShape.Sort.AS_SAVED) : FlowValueDiff.withoutIds(FlowValueText.toText(l));
            String b = json ? FlowValueText.valueJson(r, sort == com.example.flowtracker.model.FlowValueShape.Sort.AS_SAVED) : FlowValueDiff.withoutIds(FlowValueText.toText(r));
            DiffContentFactory f = DiffContentFactory.getInstance();
            com.intellij.openapi.fileTypes.FileType type = FileTypeManager.getInstance().getFileTypeByExtension(json ? "json" : "txt");
            DiffRequestPanel diff = DiffManager.getInstance().createRequestPanel(project, disposable, null);
            // sorted or filtered: only to look at (edits are made to the data as saved)
            com.intellij.diff.contents.DocumentContent da = shaping() ? f.create(project, a, type) : f.createEditable(project, a, type);
            com.intellij.diff.contents.DocumentContent db = shaping() ? f.create(project, b, type) : f.createEditable(project, b, type);
            docs.put(view, new com.intellij.diff.contents.DocumentContent[]{da, db});
            // typing or pasting: the counts follow at once (as a preview until Save Edits)
            com.intellij.openapi.editor.event.DocumentListener typed = new com.intellij.openapi.editor.event.DocumentListener() {
                @Override public void documentChanged(@NotNull com.intellij.openapi.editor.event.DocumentEvent e) { recount.restart(); }
            };
            da.getDocument().addDocumentListener(typed, disposable);
            db.getDocument().addDocumentListener(typed, disposable);
            shownTexts.put(view, new String[]{a, b});
            diff.setRequest(new SimpleDiffRequest(title, da, db, "OLD  ·  " + leftTitle, "NEW  ·  " + rightTitle));
            diff.getComponent().setName(view);
            cards.add(diff.getComponent(), view);
            // every { } / [ ] block foldable with the gutter's arrows, once the viewer has made its editors
            // (again once the diff is worked out: it folds unchanged lines of its own then)
            for (int delay : new int[]{300, 1200, 2500}) {
                javax.swing.Timer foldable = new javax.swing.Timer(delay, e -> foldViewers(false));
                foldable.setRepeats(false);
                foldable.start();
            }
        }
        ((CardLayout) cards.getLayout()).show(cards, view);
        viewButtons.forEach((k, b) -> b.setSelected(k.equals(view)));
        onlyChanges.setVisible(view.equals("TREE"));
        updateEditButtons();
    }

    // ---------- editing, undo, redo, history, reset ----------

    /** Tree: a value changed by double-click (a field with fields inside is changed as JSON). */
    private void editCell(FlowValueDiff.Row row, boolean onLeft) {
        if (shaping()) {
            com.intellij.openapi.ui.Messages.showInfoMessage(cards, "The values are sorted or filtered: show them As Saved with no "
                    + "filter to change them.", "Compare");
            return;
        }
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
        if (view.equals("TREE") || shaping()) return;
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
        p.add(FlowScroll.wheel(new JBScrollPane(list)), BorderLayout.CENTER);
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

    // ---------- files ----------

    private void fileMenu(JComponent anchor) {
        menuAbove(anchor, new String[]{"As the Old Data…", "As the New Data…"},
                new Runnable[]{() -> openFile(true), () -> openFile(false)});
    }

    /** A small menu above its button (the buttons are at the bottom of the window), with room around each option. */
    private static void menuAbove(JComponent anchor, String[] labels, Runnable[] actions) {
        FlowMenus.above(anchor, labels, actions);
    }

    /**
     * A file on one side: JSON (read into fields), or a .flowdebug.json export (one of its saved values). The side
     * is then the file's data (it isn't a saved value any more: edits stay in this window).
     */
    private void openFile(boolean onLeft) {
        com.intellij.openapi.vfs.VirtualFile file = com.intellij.openapi.fileChooser.FileChooser.chooseFile(
                com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFileDescriptor("json") // .json and .flowdebug.json
                        .withTitle("Compare with a File").withDescription("A .json file, or a .flowdebug.json file exported from Debug Data"),
                project, null);
        if (file == null) return;
        String text;
        try {
            text = new String(file.contentsToByteArray(), file.getCharset());
        } catch (java.io.IOException ex) {
            com.intellij.openapi.ui.Messages.showErrorDialog(cards, "Couldn't read " + file.getName() + ": " + ex.getMessage(), "Open File");
            return;
        }
        FlowValue value = null;
        String name = file.getName();
        try {
            List<FlowSnapshot> saved = com.example.flowtracker.model.FlowDebugTransfer.fromJson(text, List.of());
            if (!saved.isEmpty()) {
                FlowSnapshot pick = saved.get(0);
                if (saved.size() > 1) {
                    // several saved values in the file: pick one, then it goes on the side
                    String fileName = file.getName();
                    com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createPopupChooserBuilder(saved)
                            .setTitle(fileName + ": which saved value?")
                            .setRenderer(new com.intellij.ui.SimpleListCellRenderer<FlowSnapshot>() {
                                @Override public void customize(@NotNull javax.swing.JList<? extends FlowSnapshot> l, FlowSnapshot s, int i,
                                                                boolean sel, boolean focus) {
                                    setText(s.name + "  ·  " + s.where());
                                }
                            })
                            .setItemChosenCallback(s -> putOnSide(onLeft, s.value, fileName + " · " + s.name, fileName))
                            .createPopup().showInCenterOf(cards);
                    return;
                }
                value = pick.value;
                name = file.getName() + " · " + pick.name;
            }
        } catch (java.io.IOException | RuntimeException notDebugData) {
            // not an export of Debug Data: read it as JSON
        }
        if (value == null) value = FlowValueText.fromJson(onLeft ? left.name : right.name, text);
        if (value == null) {
            com.intellij.openapi.ui.Messages.showErrorDialog(cards, file.getName() + " isn't JSON. To compare other text (XML, a log), "
                    + "use Compare with nothing selected in Debug Data and paste it.", "Open File");
            return;
        }
        putOnSide(onLeft, value, name, file.getName());
    }

    /** The file's data on one side (it isn't a saved value any more: edits stay in this window). */
    private void putOnSide(boolean onLeft, FlowValue value, String name, String fileName) {
        value = value.copy();
        value.name = onLeft ? left.name : right.name;
        if (onLeft) {
            leftSnap = null;
            leftTitle = name;
        } else {
            rightSnap = null;
            rightTitle = name;
        }
        if (table != null) {
            table.getColumnModel().getColumn(2).setHeaderValue(model.getColumnName(2));
            table.getColumnModel().getColumn(3).setHeaderValue(model.getColumnName(3));
            table.getTableHeader().repaint();
        }
        change(onLeft ? value : left.copy(), onLeft ? right.copy() : value, (onLeft ? "Old" : "New") + ": opened " + fileName);
    }

    private void exportMenu(JComponent anchor) {
        menuAbove(anchor, new String[]{"Old Data as JSON…", "New Data as JSON…", "The Differences as Text…",
                        "Both Sides and the Differences…"},
                new Runnable[]{() -> export("old"), () -> export("new"), () -> export("diff"), () -> export("all")});
    }

    /** Saves a side, the differences, or all three (named after the project and what is compared). */
    private void export(String what) {
        if (project == null) return;
        String base = title.replaceFirst("^Compare ", "").replaceAll("[^\\w.-]+", "_");
        String prefix = com.example.flowtracker.model.FlowProjects.exportPrefix(project, projectsCompared());
        if (what.equals("all")) {
            com.intellij.openapi.vfs.VirtualFile dir = com.intellij.openapi.fileChooser.FileChooser.chooseFile(
                    com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFolderDescriptor()
                            .withTitle("Export the Comparison").withDescription("Pick a folder: three files are saved there"),
                    project, null);
            if (dir == null) return;
            java.nio.file.Path folder = java.nio.file.Path.of(dir.getPath());
            try {
                write(folder.resolve(com.example.flowtracker.model.FlowProjects.prefixed(prefix, base + "-old.json")), FlowValueText.valueJson(left, true));
                write(folder.resolve(com.example.flowtracker.model.FlowProjects.prefixed(prefix, base + "-new.json")), FlowValueText.valueJson(right, true));
                write(folder.resolve(com.example.flowtracker.model.FlowProjects.prefixed(prefix, base + "-differences.txt")), differencesText());
                editHint.setText("Saved the old and new data and the differences in " + dir.getName() + ".");
            } catch (java.io.IOException ex) {
                com.intellij.openapi.ui.Messages.showErrorDialog(cards, "Couldn't write the files: " + ex.getMessage(), "Export");
            }
            return;
        }
        boolean diff = what.equals("diff");
        String ext = diff ? "txt" : "json";
        String file = base + (what.equals("old") ? "-old" : what.equals("new") ? "-new" : "-differences") + "." + ext;
        com.intellij.openapi.vfs.VirtualFileWrapper target = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
                .createSaveFileDialog(com.example.flowtracker.compat.FlowCompat.saverDescriptor("Export",
                        diff ? "Save the differences as text" : "Save this side as JSON", ext), project)
                .save(com.example.flowtracker.model.FlowProjects.prefixed(prefix, file));
        if (target == null) return;
        try {
            write(target.getFile().toPath(), diff ? differencesText() : FlowValueText.valueJson(what.equals("old") ? left : right, true));
            editHint.setText("Saved " + target.getFile().getName() + ".");
        } catch (java.io.IOException ex) {
            com.intellij.openapi.ui.Messages.showErrorDialog(cards, "Couldn't write the file: " + ex.getMessage(), "Export");
        }
    }

    private static void write(java.nio.file.Path path, String text) throws java.io.IOException {
        java.nio.file.Files.writeString(path, text, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The projects of the compared saved values (for the exported files' names). */
    private List<String> projectsCompared() {
        List<String> out = new ArrayList<>();
        for (FlowSnapshot s : new FlowSnapshot[]{leftSnap, rightSnap}) {
            if (s != null && project != null) out.add(com.example.flowtracker.model.FlowProjects.ofUrl(project, s.fileUrl));
        }
        return out;
    }

    /** Everything compared and shown again after a change. */
    private void refreshAll() {
        all = compareShown();
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
        saveEdits.setVisible(!view.equals("TREE") && !shaping());
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
        parents.clear();
        for (int i = 0; i + 1 < all.size(); i++) if (all.get(i + 1).depth() > all.get(i).depth()) parents.add(all.get(i).label());
        int hideUnder = Integer.MAX_VALUE; // the rows under a folded row are hidden
        for (int i = 0; i < all.size(); i++) {
            FlowValueDiff.Row r = all.get(i);
            if (r.depth() > hideUnder) continue;
            hideUnder = Integer.MAX_VALUE;
            if (onlyChanges.isSelected() && r.kind() == FlowValueDiff.Kind.SAME && !leadsToChange(i)) continue;
            shown.add(r);
            if (collapsed.contains(r.label()) && parents.contains(r.label())) hideUnder = r.depth();
        }
        model.fireTableDataChanged();
    }

    // ---------- sort, filter, expand and collapse (only how the values are shown) ----------

    private boolean shaping() {
        // sorted, filtered or without Java internals: a copy is shown, so it is only to look at
        return !com.example.flowtracker.model.FlowValueShape.plain(sort, filter.getText()) || hidingInternals();
    }

    private FlowValue shown(FlowValue v) {
        FlowValue w = hidingInternals() ? com.example.flowtracker.model.FlowValueShape.withoutInternals(v) : v;
        return shaping() ? com.example.flowtracker.model.FlowValueShape.apply(w, sort, filter.getText()) : w;
    }

    /** True when Java internals are left out of what is shown (the setting on, and either value has some). */
    private boolean hidingInternals() {
        return com.example.flowtracker.model.FlowValueShape.hideInternals()
                && (com.example.flowtracker.model.FlowValueShape.hasInternals(left) || com.example.flowtracker.model.FlowValueShape.hasInternals(right));
    }

    /** The two values compared as they are shown (sorted, filtered). */
    private List<FlowValueDiff.Row> compareShown() {
        return FlowValueDiff.compare(shown(left), shown(right));
    }

    private void setSort(com.example.flowtracker.model.FlowValueShape.Sort s) {
        sort = s;
        sortButtons.forEach((k, b) -> b.setSelected(k == s));
        refreshAll();
    }

    /** Every row open (Tree), or every block (JSON, Text). */
    private void expandAll() {
        collapsed.clear();
        refill();
        foldViewers(false);
    }

    /** The values' own fields only, each folded (Tree), or every block but the outermost (JSON, Text). */
    private void collapseAll() {
        collapsed.clear();
        for (FlowValueDiff.Row r : all) if (r.depth() >= 1 && parents.contains(r.label())) collapsed.add(r.label());
        refill();
        foldViewers(true);
    }

    /** Folds (or opens) the { } / [ ] blocks of the JSON and Text views' editors. */
    private void foldViewers(boolean collapse) {
        if (view.equals("TREE")) return;
        java.awt.Component card = null;
        for (java.awt.Component c : cards.getComponents()) if (view.equals(c.getName())) card = c;
        if (card == null) return;
        for (com.intellij.openapi.editor.Editor ed : com.intellij.openapi.editor.EditorFactory.getInstance().getAllEditors()) {
            if (javax.swing.SwingUtilities.isDescendingFrom(ed.getComponent(), card)) FlowCodeView.fold(ed, view.equals("JSON"), collapse);
        }
    }

    /** Ctrl+F: IntelliJ's find bar in the JSON / Text view's editor that has the focus, else the filter. */
    private void find() {
        if (!view.equals("TREE")) {
            for (com.intellij.openapi.editor.Editor ed : com.intellij.openapi.editor.EditorFactory.getInstance().getAllEditors()) {
                java.awt.Component owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (owner != null && javax.swing.SwingUtilities.isDescendingFrom(owner, ed.getComponent()) && project != null) {
                    com.intellij.find.EditorSearchSession.start(ed, project);
                    return;
                }
            }
        }
        filter.requestFocusInWindow();
        filter.selectText();
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
        @Override public String getColumnName(int c) {
            if (c == 0) return "";
            if (c == 1) return "Name";
            return c == 2 ? "<html><b><font color='" + hex(REMOVED_MARK) + "'>OLD</font></b> &nbsp;" + esc(leftTitle) + "</html>"
                    : "<html><b><font color='" + hex(ADDED_MARK) + "'>NEW</font></b> &nbsp;" + esc(rightTitle) + "</html>";
        }
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
