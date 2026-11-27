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
    /** The characters of a changed value that differ: what was there (Old) and what is there now (New). */
    private static final SimpleTextAttributes OLD_PART = new SimpleTextAttributes(new JBColor(0xFFCDD2, 0x6B2F33),
            new JBColor(0x8E1B1B, 0xFFB4B4), null, SimpleTextAttributes.STYLE_BOLD);
    private static final SimpleTextAttributes NEW_PART = new SimpleTextAttributes(new JBColor(0xC8F0D0, 0x2C5A36),
            new JBColor(0x14612A, 0xB6F2C2), null, SimpleTextAttributes.STYLE_BOLD);
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
    private String view = com.intellij.ide.util.PropertiesComponent.getInstance().getValue(OPEN_IN, "JSON");
    /** The New side's title while it is still to be given (one saved value compared with what is pasted, opened or picked). */
    public static final String AWAITING = "to compare with: paste it, open a file or pick a saved value";
    /** True until the New side is given: the window shows the Old value and a place to paste the other. */
    private boolean awaitingRight, awaitingLeft;
    private @Nullable FlowCodeView pasteArea;
    /** What is typed or pasted in a side's box and not used yet (kept when the cards are made again). */
    private String leftDraft = "", rightDraft = "";
    private static final String OPEN_IN = "TraceWeave.compare.openIn", UNIFIED = "TraceWeave.compare.unified",
            FAVORITES = "TraceWeave.compare.favorites";
    /** Which data is compared: the saved data (with edits), as first saved, or with text that holds JSON opened up. */
    private enum Source { SAVED, ORIGINAL, FORMATTED }
    private Source source = Source.SAVED;
    /** The JSON and Text diffs: side by side (Compare View) or as one text (Unified View). */
    private boolean unified = com.intellij.ide.util.PropertiesComponent.getInstance().getBoolean(UNIFIED, false);
    private final FlowButton layoutButton = new FlowButton("", null, FlowButton.Style.OUTLINE);
    private final FlowButton sourceButton = new FlowButton("", null, FlowButton.Style.PRIMARY);
    private final FlowButton sortButton = new FlowButton("", null, FlowButton.Style.OUTLINE);
    /** The last diff view (JSON or Text): the rail's Compare goes back to it. */
    private String diffView = "JSON";
    /** The status bar: how the comparison came out, the counts and the sizes. */
    private final JBLabel statusLine = new JBLabel();
    /** The rail's items (Compare, Tree), selected with the view. */
    private final java.util.Map<String, RailItem> railItems = new java.util.LinkedHashMap<>();
    private final Model model = new Model();
    private final JBCheckBox onlyChanges = new JBCheckBox("Only what differs", false); // the Tree shows all the data
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
    private final FlowButton reset = new FlowButton("Reset", com.intellij.icons.AllIcons.Actions.Restart, FlowButton.Style.OUTLINE);
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
        d.awaitingRight = AWAITING.equals(rightTitle);
        d.awaitingLeft = AWAITING.equals(leftTitle);
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
        FlowButton close = new FlowButton(closeLabel, AllIcons.Actions.Close, FlowButton.Style.PRIMARY);
        // the status only; Undo, Redo, Reset and Save Edits are at the top, Swap / Open File / Export / Copy Diff in
        // Settings, and the window's own × closes it (Compare Pasted Data keeps its Back to Edit here)
        FlowToolbar bar = new FlowToolbar();
        boolean ownButton = !closeLabel.equals("Close");
        if (ownButton) bar.addTrailingGroup(2, close);
        JPanel note = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        note.setOpaque(false);
        note.add(d.editHint);
        note.add(d.seeChanges);
        JPanel status = new JPanel(new BorderLayout(JBUI.scale(12), 0));
        status.setOpaque(false);
        status.add(d.statusLine, BorderLayout.WEST);
        status.add(note, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        bottom.add(status, BorderLayout.NORTH);
        if (ownButton) bottom.add(bar, BorderLayout.CENTER);
        bottom.setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(FlowTheme.CARD_BORDER, 1, 0, 0, 0), JBUI.Borders.emptyTop(8)));
        JPanel main = new JPanel(new BorderLayout());
        main.add(d.createCenterPanel(), BorderLayout.CENTER);
        main.add(bottom, BorderLayout.SOUTH);
        main.setBorder(JBUI.Borders.emptyLeft(10));
        JPanel root = new JPanel(new BorderLayout());
        root.add(d.rail(), BorderLayout.WEST);
        root.add(main, BorderLayout.CENTER);
        int menu = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        key(root, javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, menu), "undo", d::undo);
        com.intellij.openapi.project.DumbAwareAction.create(e -> d.find()).registerCustomShortcutSet(
                new com.intellij.openapi.actionSystem.CustomShortcutSet(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F, menu)),
                root, d.disposable);
        key(root, javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, menu | java.awt.event.InputEvent.SHIFT_DOWN_MASK), "redo", d::redo);
        key(root, javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, menu), "save", d::saveTextEdits);
        d.updateEditButtons();
        root.setBorder(JBUI.Borders.empty(10, 8, 10, 12));
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_X,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | java.awt.event.InputEvent.SHIFT_DOWN_MASK), "traceweave.swap");
        root.getActionMap().put("traceweave.swap", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { d.swap(); }
        });
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
                        // as the debugger's Variables / Evaluate: ▾ (f) name, the top value with the watch icon
                        setIpad(JBUI.insetsLeft(r.depth() * 16));
                        boolean parent = parents.contains(r.label());
                        com.intellij.ui.RowIcon icon = new com.intellij.ui.RowIcon(2);
                        icon.setIcon(parent ? (collapsed.contains(r.label()) ? AllIcons.General.ArrowRight : AllIcons.General.ArrowDown)
                                : com.intellij.util.ui.EmptyIcon.ICON_16, 0);
                        boolean element = r.name().matches("\\[?\\d+]?");
                        icon.setIcon(FlowVariablesLook.icon(r.depth() == 0), 1);
                        setIcon(icon);
                        setIconTextGap(JBUI.scale(6));
                        append(element ? r.name().replaceAll("[\\[\\]]", "") : r.name(),
                                SimpleTextAttributes.REGULAR_ATTRIBUTES);
                        setToolTipText(r.label());
                    }
                    default -> {
                        setIpad(JBUI.emptyInsets());
                        String v = column == 2 ? r.left() : r.right();
                        boolean differs = r.kind() != FlowValueDiff.Kind.SAME;
                        // {Type@id} in gray before an object's value, as the debugger shows it
                        // {Type} only before what has fields, as IntelliJ shows it
                        if (v != null && parents.contains(r.label())) {
                            String type = FlowVariablesLook.type(r.type());
                            v = FlowVariablesLook.text(v, r.type());
                            if (!type.isEmpty()) append(type + (v.isEmpty() ? "" : " "), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                        }
                        if (v == null) {
                            append("—  not there", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                        } else if (r.kind() == FlowValueDiff.Kind.CHANGED && r.left() != null && r.right() != null) {
                            // only the characters that differ are marked: BK-104[2] → BK-104[3]
                            String a = FlowValueDiff.withoutIds(r.left()), b = FlowValueDiff.withoutIds(r.right());
                            String text = column == 2 ? a : b;
                            int[] span = FlowValueDiff.differingSpan(a, b);
                            int from = span[0], to = text.length() - span[1];
                            if (from > 0) append(text.substring(0, from), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                            if (to > from) append(text.substring(from, to), column == 2 ? OLD_PART : NEW_PART);
                            if (to < text.length()) append(text.substring(Math.max(from, to)), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                        } else {
                            String text = FlowValueDiff.withoutIds(v);
                            SimpleTextAttributes color = valueColor(text);
                            append(text, differs ? color.derive(SimpleTextAttributes.STYLE_BOLD, null, null, null) : color);
                        }
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

        // the views (JSON, Text, Tree) are on the left rail only; here how they look
        layoutButton.setToolTipText("Compare View: the two sides next to each other. Unified View: one text, what was removed and added in between");
        Runnable layoutMenu = () -> dropdown(layoutButton, new String[]{"Compare View", "Unified View"},
                new javax.swing.Icon[]{AllIcons.Actions.Diff, AllIcons.Actions.ListChanges}, unified ? 1 : 0, i -> setUnified(i == 1));
        layoutButton.addActionListener(e -> layoutMenu.run());
        layoutButton.setDropDown("Compare View or Unified View", layoutMenu);
        sourceButton.setToolTipText("As Saved: the data with your edits. Original: as it was first saved, before any edit. "
                + "Formatted: text that holds JSON shown as its fields");
        Runnable sourceMenu = () -> dropdown(sourceButton, new String[]{"As Saved", "Original", "Formatted"}, null,
                source.ordinal(), i -> setSource(Source.values()[i]));
        sourceButton.addActionListener(e -> sourceMenu.run());
        sourceButton.setDropDown("As Saved, Original or Formatted", sourceMenu);
        sortButton.setToolTipText("The fields in their saved order, or sorted by name (lists keep their order); only how they are shown");
        Runnable sortMenu = () -> dropdown(sortButton, new String[]{"Saved order", "A → Z", "Z → A"}, null,
                sort.ordinal(), i -> setSort(com.example.flowtracker.model.FlowValueShape.Sort.values()[i]));
        sortButton.addActionListener(e -> sortMenu.run());
        sortButton.setDropDown("Saved order, A → Z or Z → A", sortMenu);
        showDropdowns();

        JBCheckBox internals = new JBCheckBox("Hide Internals", com.example.flowtracker.model.FlowValueShape.hideInternals());
        internals.setOpaque(false);
        internals.setToolTipText("<html><b>Hide Internals</b>: what a language or its tools put inside a value, left out (only how it is shown).<br>"
                + "Java, Kotlin, Scala, Groovy: a String's bytes, coder and hash as its text; an enum as its name; "
                + "BigDecimal, ArrayList and lazy values as what they hold;<br>compiler and framework fields "
                + "(this$0, $outer, x$delegate, $$_hibernate_interceptor, metaClass, modCount…).<br>"
                + ".NET: $id / $type / $ref, {$values} as its list, &lt;Name&gt;k__BackingField as Name. "
                + "Python and JavaScript: __dunder__ names, __proto__, __typename, py/… keys.<br>"
                + "One setting for Debug Data, Compare, Compare Pasted Data and Stop at Value.</html>");
        internals.addActionListener(e -> {
            com.example.flowtracker.model.FlowValueShape.setHideInternals(internals.isSelected());
            refreshAll();
        });
        JBCheckBox whitespace = new JBCheckBox("Ignore Whitespace", com.example.flowtracker.model.FlowValueShape.ignoreWhitespace());
        whitespace.setOpaque(false);
        whitespace.setToolTipText("A value or line that differs only in spaces, tabs or line ends isn't shown as changed "
                + "(here and in the other diffs)");
        whitespace.addActionListener(e -> {
            com.example.flowtracker.model.FlowValueShape.setIgnoreWhitespace(whitespace.isSelected());
            refreshAll();
        });
        filter.getTextEditor().getEmptyText().setText("Search in the values (Ctrl + F)");
        filter.addDocumentListener(new com.intellij.ui.DocumentAdapter() {
            @Override protected void textChanged(@NotNull javax.swing.event.DocumentEvent e) { refreshAll(); }
        });
        filter.setMinimumSize(new Dimension(JBUI.scale(140), filter.getPreferredSize().height));
        filter.setPreferredSize(new Dimension(JBUI.scale(300), filter.getPreferredSize().height));

        FlowButton more = new FlowButton("", AllIcons.Actions.More, FlowButton.Style.OUTLINE);
        more.setToolTipText("More: expand or collapse everything, keyboard shortcuts");
        more.addActionListener(e -> moreMenu(more));

        chips.setLayout(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        JPanel top = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.gridy = 0;
        c.anchor = java.awt.GridBagConstraints.WEST;
        c.insets = JBUI.insetsRight(10);
        FlowToolbar showing = new FlowToolbar();
        FlowButton allRows = new FlowButton("All", null, FlowButton.Style.SEGMENT);
        allRows.setToolTipText("Every field, what differs marked");
        FlowButton diffRows = new FlowButton("Differences", null, FlowButton.Style.SEGMENT);
        diffRows.setToolTipText("Only the fields that differ (with the objects they are in)");
        allRows.setSelected(!onlyChanges.isSelected());
        diffRows.setSelected(onlyChanges.isSelected());
        allRows.addActionListener(e -> {
            onlyChanges.setSelected(false);
            allRows.setSelected(true);
            diffRows.setSelected(false);
            refreshAll();
        });
        diffRows.addActionListener(e -> {
            onlyChanges.setSelected(true);
            allRows.setSelected(false);
            diffRows.setSelected(true);
            refreshAll();
        });
        showing.addGroup(true, 3, allRows, diffRows);
        for (JComponent part : new JComponent[]{showing, layoutButton, divider(), sourceButton, sortButton, divider(),
                internals, whitespace}) {
            top.add(part, c);
        }
        c.weightx = 1;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        undo.setText("");
        redo.setText("");
        reset.setText("");
        FlowToolbar edits = new FlowToolbar();
        edits.addGroup(false, 0, undo, redo, reset);
        edits.addGroup(false, 1, saveEdits);
        c.weightx = 0;
        c.fill = java.awt.GridBagConstraints.NONE;
        top.add(edits, c);
        c.weightx = 1;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        top.add(filter, c);
        c.weightx = 0;
        c.fill = java.awt.GridBagConstraints.NONE;
        c.insets = JBUI.insetsLeft(0);
        top.add(more, c);
        JPanel head = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        head.add(top, BorderLayout.NORTH);
        sides.setOpaque(false);
        fillSides();
        head.add(sides, BorderLayout.CENTER);
        head.setBorder(JBUI.Borders.emptyBottom(8));

        cards.add(FlowScroll.wheel(new JBScrollPane(table)), "TREE");
        if (awaitingRight || awaitingLeft) {
            JComponent start = startPanel();
            start.setName("START");
            cards.add(start, "START");
        }
        made.add("TREE");
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(head, BorderLayout.NORTH);
        panel.add(cards, BorderLayout.CENTER);
        showView(view);
        panel.setPreferredSize(new Dimension(JBUI.scale(1100), JBUI.scale(640)));
        return panel;
    }

    private static final SimpleTextAttributes STRING_VALUE = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, new JBColor(0x067D17, 0x6AAB73));
    private static final SimpleTextAttributes NUMBER_VALUE = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, new JBColor(0x1750EB, 0x2AACB8));
    private static final SimpleTextAttributes KEYWORD_VALUE = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, new JBColor(0x0033B3, 0xCF8E6D));

    /** A value's text in the editor's colors: "text" green, 12 blue, true / false / null as keywords. */
    private static SimpleTextAttributes valueColor(String text) {
        String t = text.strip();
        if (t.startsWith("\"") || t.startsWith("'")) return STRING_VALUE;
        if (t.equals("true") || t.equals("false") || t.equals("null")) return KEYWORD_VALUE;
        if (t.matches("-?\\d[\\d_]*(\\.\\d+)?([eE][-+]?\\d+)?[lLfFdD]?")) return NUMBER_VALUE;
        return SimpleTextAttributes.REGULAR_ATTRIBUTES;
    }

    /** A copy with only the fields at these paths (child indexes, "0.2"), the objects they are in, and what is inside them. */
    static FlowValue onlyThese(FlowValue v, java.util.Set<String> paths) {
        FlowValue out = new FlowValue(v.name);
        out.type = v.type;
        out.value = v.value;
        keep(v, out, "", paths);
        return out;
    }

    private static void keep(FlowValue from, FlowValue to, String path, java.util.Set<String> paths) {
        for (int i = 0; i < from.children.size(); i++) {
            String p = path.isEmpty() ? String.valueOf(i) : path + "." + i;
            FlowValue c = from.children.get(i);
            boolean whole = false, leads = false;
            for (String k : paths) {
                if (p.equals(k) || p.startsWith(k + ".")) whole = true;
                else if (k.startsWith(p + ".")) leads = true;
            }
            if (whole) {
                to.children.add(c.copy());
            } else if (leads) {
                FlowValue part = new FlowValue(c.name);
                part.type = c.type;
                part.value = c.value;
                keep(c, part, p, paths);
                to.children.add(part);
            }
        }
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

    /** Which side is which, over every view (Tree, JSON, Text): OLD on the left, NEW on the right; click one to change it. */
    private final JPanel sides = new JPanel(new java.awt.GridBagLayout());

    private void fillSides() {
        sides.removeAll();
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.gridy = 0;
        sides.add(new SideCard(true), c);
        c.weightx = 0;
        c.insets = JBUI.insets(0, 10);
        FlowButton swap = new FlowButton("", AllIcons.Actions.SwapPanels, FlowButton.Style.OUTLINE);
        swap.setToolTipText("Swap Old / New (Ctrl+Shift+X)");
        swap.addActionListener(e -> swap());
        sides.add(swap, c);
        c.weightx = 1;
        c.insets = JBUI.emptyInsets();
        sides.add(new SideCard(false), c);
        sides.revalidate();
        sides.repaint();
    }

    /** A thin line between groups of the top bar. */
    private static JComponent divider() {
        JPanel line = new JPanel();
        line.setBackground(FlowTheme.CARD_BORDER);
        line.setPreferredSize(new Dimension(JBUI.scale(1), JBUI.scale(22)));
        return line;
    }

    /** OLD or NEW: what is on that side (its place and when it was saved); click to put another saved value or a file there. */
    private final class SideCard extends JPanel {
        private final boolean onLeft;
        private boolean hover;

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(JBUI.scale(100), super.getPreferredSize().height); // the two share the width equally
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        SideCard(boolean onLeft) {
            super(new BorderLayout(JBUI.scale(10), 0));
            this.onLeft = onLeft;
            setOpaque(false);
            setBorder(JBUI.Borders.empty(8, 12));
            Color mark = onLeft ? REMOVED_MARK : ADDED_MARK;
            JBLabel what = new JBLabel(onLeft ? "OLD" : "NEW", AllIcons.FileTypes.Json, JBLabel.LEFT);
            what.setForeground(mark);
            what.setFont(JBUI.Fonts.label().asBold());
            JBLabel name = new JBLabel(onLeft ? leftTitle : rightTitle);
            name.setFont(JBUI.Fonts.label().asBold());
            JBLabel arrow = new JBLabel(AllIcons.General.ChevronDown);
            add(what, BorderLayout.WEST);
            add(name, BorderLayout.CENTER);
            add(arrow, BorderLayout.EAST);
            setToolTipText("Put another saved value, or a file, on the " + (onLeft ? "Old" : "New") + " side");
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) { sideMenu(SideCard.this, onLeft); }
                @Override public void mouseEntered(java.awt.event.MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(java.awt.event.MouseEvent e) { hover = false; repaint(); }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = JBUI.scale(10);
            g2.setColor(onLeft ? REMOVED : ADDED);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            g2.setColor(onLeft ? REMOVED_MARK : ADDED_MARK);
            g2.setStroke(new java.awt.BasicStroke(hover ? 2f : 1.2f));
            g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, arc, arc);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** What goes on a side: a saved value, or a file ({@code onLeft} null: ask which side too). */
    private void sideMenu(JComponent anchor, @Nullable Boolean onLeft) {
        List<String> items = new ArrayList<>();
        List<javax.swing.Icon> icons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (boolean l : onLeft == null ? new boolean[]{true, false} : new boolean[]{onLeft}) {
            String side = l ? "Old" : "New";
            if (project != null) {
                items.add("A Saved Value as " + side + "…");
                icons.add(AllIcons.Debugger.Watch);
                actions.add(() -> pickSaved(l, anchor));
            }
            items.add("A File as " + side + "…  (.json, .flowdebug.json)");
            icons.add(AllIcons.FileTypes.Json);
            actions.add(() -> openFile(l));
            items.add("Paste from Clipboard as " + side);
            icons.add(AllIcons.Actions.MenuPaste);
            actions.add(() -> pasteOnSide(l));
        }
        dropdown(anchor, items.toArray(new String[0]), icons.toArray(new javax.swing.Icon[0]), -1, i -> actions.get(i).run());
    }

    /** The clipboard on a side: JSON as its fields, other text (XML, toString(), a log line) as one value. */
    private void pasteOnSide(boolean onLeft) {
        String text = com.intellij.openapi.ide.CopyPasteManager.getInstance().getContents(java.awt.datatransfer.DataFlavor.stringFlavor);
        if (text == null || text.isBlank()) {
            editHint.setText("The clipboard is empty: copy the data to compare first.");
            return;
        }
        String name = onLeft ? left.name : right.name;
        FlowValue v = com.example.flowtracker.model.FlowDataParse.parse(name, text);
        if (v == null) {
            v = new FlowValue(name);
            v.value = text.strip();
        }
        putOnSide(onLeft, v, "Pasted · " + new java.text.SimpleDateFormat("d MMM HH:mm:ss").format(new java.util.Date()), "the clipboard");
        fillSides();
    }

    /** Puts another saved value on a side (the same variable's other saves first); the window starts again from there. */
    private void pickSaved(boolean onLeft, JComponent anchor) {
        if (project == null) return;
        FlowSnapshot same = onLeft ? (rightSnap != null ? rightSnap : leftSnap) : (leftSnap != null ? leftSnap : rightSnap);
        List<FlowSnapshot> all = new ArrayList<>(FlowDebugStore.get(project).getSnapshots());
        all.sort(java.util.Comparator.comparing((FlowSnapshot x) -> same == null || !x.name.equals(same.name))
                .thenComparing(x -> -x.savedAt));
        if (all.isEmpty()) {
            editHint.setText("No saved values yet: open a file instead.");
            return;
        }
        java.text.SimpleDateFormat at = new java.text.SimpleDateFormat("d MMM HH:mm:ss");
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createPopupChooserBuilder(all)
                .setTitle(onLeft ? "Old Data" : "New Data")
                .setRenderer(new com.intellij.ui.ColoredListCellRenderer<FlowSnapshot>() {
                    @Override protected void customizeCellRenderer(@NotNull javax.swing.JList<? extends FlowSnapshot> l, FlowSnapshot x, int i,
                                                                   boolean sel, boolean focus) {
                        setIcon(AllIcons.Nodes.Variable);
                        append(x.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                        append("  " + x.where(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                        append("  " + at.format(new java.util.Date(x.savedAt)) + " · " + x.flowName, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    }
                })
                .setNamerForFiltering(x -> x.name + " " + x.where() + " " + x.flowName)
                .setItemChosenCallback(x -> putSaved(onLeft, x))
                .createPopup().showUnderneathOf(anchor);
    }

    /** A saved value on a side; the window starts again from there (Undo never writes one value into another). */
    private void putSaved(boolean onLeft, FlowSnapshot x) {
        java.text.SimpleDateFormat at = new java.text.SimpleDateFormat("d MMM HH:mm:ss");
        String t = x.name + "  ·  " + x.where() + "  ·  " + at.format(new java.util.Date(x.savedAt));
        if (onLeft) {
            leftSnap = x;
            left = x.value;
            leftTitle = t;
        } else {
            rightSnap = x;
            right = x.value;
            rightTitle = t;
        }
        steps.clear();
        steps.add(new State(left.copy(), right.copy(), "Opened " + x.name + " at " + x.where(), System.currentTimeMillis()));
        position = 0;
        editHint.setText((onLeft ? "Old" : "New") + ": " + x.name + " at " + x.where() + ".");
        if (table != null) {
            table.getColumnModel().getColumn(2).setHeaderValue(model.getColumnName(2));
            table.getColumnModel().getColumn(3).setHeaderValue(model.getColumnName(3));
            table.getTableHeader().repaint();
        }
        refreshAll();
        updateEditButtons();
        sideGiven(onLeft);
    }

    /** The left rail: the views (JSON, Text, Tree), then History, Favorites and Settings. */
    private JComponent rail() {
        JPanel rail = new JPanel();
        rail.setLayout(new javax.swing.BoxLayout(rail, javax.swing.BoxLayout.Y_AXIS));
        rail.setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(FlowTheme.CARD_BORDER, 0, 0, 0, 1), JBUI.Borders.empty(0, 0, 0, 8)));
        RailItem json = new RailItem("JSON", AllIcons.FileTypes.Json, () -> showView("JSON"));
        json.setToolTipText("Both sides as JSON (instance ids left out) in IntelliJ's diff viewer; edit either side, then Save Edits");
        RailItem text = new RailItem("Text", AllIcons.FileTypes.Text, () -> showView("TEXT"));
        text.setToolTipText("Both sides as indented text in IntelliJ's diff viewer");
        RailItem tree = new RailItem("Tree", AllIcons.Actions.ShowAsTree, () -> showView("TREE"));
        tree.setToolTipText("Field by field in a table, what differs marked; double-click a value to change it");
        RailItem past = new RailItem("History", AllIcons.Vcs.History, this::showHistory);
        past.setToolTipText("The changes made here; pick one to go back to it");
        RailItem favorites = new RailItem("Favorites", AllIcons.Nodes.Favorite, null);
        favorites.setToolTipText("Comparisons you keep: add this one, or open one again");
        favorites.onClick(() -> favoritesMenu(favorites));
        RailItem settings = new RailItem("Settings", AllIcons.General.Settings, null);
        settings.setToolTipText("Swap, open a file, copy or export the differences; which view Compare opens in, what the Tree shows");
        settings.onClick(() -> settingsMenu(settings));
        railItems.put("JSON", json);
        railItems.put("TEXT", text);
        railItems.put("TREE", tree);
        for (RailItem i : new RailItem[]{json, text, tree, past, favorites, settings}) {
            i.setAlignmentX(0.5f);
            rail.add(i);
            rail.add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)));
        }
        rail.add(javax.swing.Box.createVerticalGlue());
        railItems.forEach((k, b) -> b.setSelected(k.equals(view)));
        return rail;
    }

    // ---------- the top's dropdowns, full screen, More ----------

    private void showDropdowns() {
        layoutButton.setText(unified ? "Unified View" : "Compare View");
        layoutButton.setIcon(unified ? AllIcons.Actions.ListChanges : AllIcons.Actions.Diff);
        sourceButton.setText(new String[]{"As Saved", "Original", "Formatted"}[source.ordinal()]);
        sortButton.setText("Sort: " + new String[]{"Saved order", "A → Z", "Z → A"}[sort.ordinal()]);
        for (FlowButton b : new FlowButton[]{layoutButton, sourceButton, sortButton}) {
            b.revalidate();
            b.repaint();
        }
    }

    /** A list under its button, the chosen item ticked. */
    private static void dropdown(JComponent anchor, String[] items, javax.swing.Icon[] icons, int chosen,
                                 java.util.function.IntConsumer pick) {
        FlowPopupMenu m = new FlowPopupMenu();
        for (int i = 0; i < items.length; i++) {
            int at = i;
            m.item(i == chosen ? AllIcons.Actions.Checked : icons != null ? icons[i] : com.intellij.util.ui.EmptyIcon.ICON_16,
                    items[i], null, null, () -> pick.accept(at));
        }
        m.showBelow(anchor);
    }

    private void setUnified(boolean on) {
        unified = on;
        com.intellij.ide.util.PropertiesComponent.getInstance().setValue(UNIFIED, on, false);
        showDropdowns();
        refreshAll();
    }

    private void setSource(Source s) {
        if (s == Source.ORIGINAL && (leftSnap == null || leftSnap.original == null) && (rightSnap == null || rightSnap.original == null)) {
            editHint.setText("Neither side was edited: the original is the saved data.");
        }
        source = s;
        showDropdowns();
        refreshAll();
    }

    /** The data a side shows: as saved, as first saved (before edits), or with text holding JSON opened up. */
    private FlowValue base(FlowValue v) {
        if (source == Source.ORIGINAL) {
            if (v == left && leftSnap != null && leftSnap.original != null) return leftSnap.original;
            if (v == right && rightSnap != null && rightSnap.original != null) return rightSnap.original;
        }
        if (source == Source.FORMATTED) return formatted(v);
        return v;
    }

    /** A copy where a text that holds JSON ("{\"a\": 1}") is its fields. */
    static FlowValue formatted(FlowValue v) {
        FlowValue out = v.copy();
        open(out);
        return out;
    }

    private static void open(FlowValue v) {
        if (v.children.isEmpty() && v.value != null) {
            String t = v.value.strip();
            if (t.length() > 1 && t.startsWith("\"") && t.endsWith("\"")) {
                try {
                    t = com.google.gson.JsonParser.parseString(t).getAsString().strip();
                } catch (RuntimeException notAString) {
                    return;
                }
            }
            if (t.startsWith("{") || t.startsWith("[")) {
                FlowValue parsed = FlowValueText.fromJson(v.name, t);
                if (parsed != null && !parsed.children.isEmpty()) {
                    v.children.addAll(parsed.children);
                    v.value = parsed.value;
                    if (v.type == null || v.type.isEmpty() || v.type.equals("String")) v.type = parsed.type;
                }
            }
            return;
        }
        for (FlowValue c : v.children) open(c);
    }

    private java.awt.Rectangle before; // the window's size before full screen (null: not full screen)

    /** The window over the whole screen, and back. */
    private void toggleFullScreen(FlowButton button) {
        java.awt.Window w = javax.swing.SwingUtilities.getWindowAncestor(cards);
        if (w == null) return;
        if (w instanceof java.awt.Frame f) {
            boolean max = (f.getExtendedState() & java.awt.Frame.MAXIMIZED_BOTH) == java.awt.Frame.MAXIMIZED_BOTH;
            f.setExtendedState(max ? java.awt.Frame.NORMAL : java.awt.Frame.MAXIMIZED_BOTH);
            button.setIcon(max ? AllIcons.General.ExpandComponent : AllIcons.General.CollapseComponent);
            return;
        }
        if (before != null) {
            w.setBounds(before);
            before = null;
            button.setIcon(AllIcons.General.ExpandComponent);
            return;
        }
        before = w.getBounds();
        java.awt.GraphicsConfiguration gc = w.getGraphicsConfiguration();
        java.awt.Rectangle screen = gc.getBounds();
        java.awt.Insets in = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc);
        w.setBounds(screen.x + in.left, screen.y + in.top, screen.width - in.left - in.right, screen.height - in.top - in.bottom);
        button.setIcon(AllIcons.General.CollapseComponent);
    }

    private void moreMenu(JComponent anchor) {
        String[] items = {"Expand All", "Collapse All", "Keyboard Shortcuts"};
        javax.swing.Icon[] icons = {AllIcons.Actions.Expandall, AllIcons.Actions.Collapseall, AllIcons.General.ContextHelp};
        Runnable[] run = {this::expandAll, this::collapseAll, () -> FlowShortcuts.show(anchor, "Compare", FlowShortcuts.COMPARE)};
        dropdown(anchor, items, icons, -1, i -> run[i].run());
    }

    // ---------- Favorites and Settings (the rail) ----------

    private List<String> favorites() {
        List<String> all = com.intellij.ide.util.PropertiesComponent.getInstance(project).getList(FAVORITES);
        return all == null ? new ArrayList<>() : new ArrayList<>(all);
    }

    /** Favorites: keep this comparison (two saved values), or open a kept one. */
    private void favoritesMenu(JComponent anchor) {
        if (project == null) return;
        List<String> kept = favorites();
        String mine = leftSnap != null && rightSnap != null ? leftSnap.id + "|" + rightSnap.id : null;
        java.util.Map<String, FlowSnapshot> byId = new java.util.HashMap<>();
        for (FlowSnapshot x : FlowDebugStore.get(project).getSnapshots()) byId.put(x.id, x);
        kept.removeIf(k -> {
            String[] ids = k.split("\\|");
            return ids.length != 2 || !byId.containsKey(ids[0]) || !byId.containsKey(ids[1]); // a value deleted since
        });
        java.text.SimpleDateFormat at = new java.text.SimpleDateFormat("d MMM HH:mm:ss");
        java.util.Map<String, List<FlowSettingsPopup.Choice>> sections = new java.util.LinkedHashMap<>();
        FlowSettingsPopup.Choice thisOne;
        if (mine == null) {
            thisOne = new FlowSettingsPopup.Choice(AllIcons.General.Information, "Only two saved values can be kept",
                    "Pick a saved value for Old and for New", () -> false, () -> {});
        } else if (kept.contains(mine)) {
            thisOne = new FlowSettingsPopup.Choice(AllIcons.Nodes.Favorite, "Remove This Comparison", null, () -> false, () -> {
                kept.remove(mine);
                com.intellij.ide.util.PropertiesComponent.getInstance(project).setList(FAVORITES, kept);
                editHint.setText("Removed from Favorites.");
            });
        } else {
            thisOne = new FlowSettingsPopup.Choice(AllIcons.Nodes.NotFavoriteOnHover, "Add This Comparison", null, () -> false, () -> {
                kept.add(0, mine);
                com.intellij.ide.util.PropertiesComponent.getInstance(project).setList(FAVORITES, kept);
                editHint.setText("Added to Favorites.");
            });
        }
        sections.put("THIS COMPARISON", List.of(thisOne));
        List<FlowSettingsPopup.Choice> saved = new ArrayList<>();
        for (String k : kept) {
            String[] ids = k.split(java.util.regex.Pattern.quote("|"));
            FlowSnapshot a = byId.get(ids[0]), b = byId.get(ids[1]);
            String what = a.name.equals(b.name) ? a.name : a.name + " ⇄ " + b.name;
            String where = a.where().equals(b.where()) ? a.where() : a.where() + " ⇄ " + b.where();
            saved.add(new FlowSettingsPopup.Choice(AllIcons.Actions.Diff, what + "  ·  " + where,
                    at.format(new java.util.Date(a.savedAt)) + "  ⇄  " + at.format(new java.util.Date(b.savedAt)),
                    () -> k.equals(mine), () -> {
                        putSaved(true, a);
                        putSaved(false, b);
                    }));
        }
        if (saved.isEmpty()) {
            saved.add(new FlowSettingsPopup.Choice(AllIcons.General.Note, "None yet", "Add a comparison to open it again later",
                    () -> false, () -> {}));
        }
        sections.put("KEPT", saved);
        FlowSettingsPopup.showChoices(anchor, "Favorites", sections, null, true, true);
    }

    private void settingsMenu(JComponent anchor) {
        java.util.Map<String, List<FlowSettingsPopup.Choice>> sections = new java.util.LinkedHashMap<>();
        com.intellij.ide.util.PropertiesComponent props = com.intellij.ide.util.PropertiesComponent.getInstance();
        List<FlowSettingsPopup.Choice> open = new ArrayList<>();
        for (String[] v : new String[][]{{"JSON", "JSON"}, {"TEXT", "Text"}, {"TREE", "Tree"}}) {
            open.add(new FlowSettingsPopup.Choice(v[0].equals("TREE") ? AllIcons.Actions.ShowAsTree : v[0].equals("JSON")
                    ? AllIcons.FileTypes.Json : AllIcons.FileTypes.Text, v[1], null,
                    () -> props.getValue(OPEN_IN, "JSON").equals(v[0]), () -> props.setValue(OPEN_IN, v[0], "JSON")));
        }
        sections.put("ACTIONS", List.of(
                new FlowSettingsPopup.Choice(AllIcons.Actions.SwapPanels, "Swap Old / New", "Ctrl+Shift+X", () -> false, this::swap),
                new FlowSettingsPopup.Choice(FlowTheme.ICON_IMPORT, "Open a File as Old…", null, () -> false, () -> openFile(true)),
                new FlowSettingsPopup.Choice(FlowTheme.ICON_IMPORT, "Open a File as New…", null, () -> false, () -> openFile(false)),
                new FlowSettingsPopup.Choice(FlowTheme.ICON_COPY, "Copy Diff", "The differences as text", () -> false,
                        () -> {
                            FlowCopy.toClipboard(differencesText());
                            editHint.setText("Copied the differences.");
                        })));
        sections.put("EXPORT", List.of(
                new FlowSettingsPopup.Choice(FlowTheme.ICON_EXPORT, "Old Data as JSON…", null, () -> false, () -> export("old")),
                new FlowSettingsPopup.Choice(FlowTheme.ICON_EXPORT, "New Data as JSON…", null, () -> false, () -> export("new")),
                new FlowSettingsPopup.Choice(FlowTheme.ICON_EXPORT, "The Differences as Text…", null, () -> false, () -> export("diff")),
                new FlowSettingsPopup.Choice(FlowTheme.ICON_EXPORT, "Both Sides and the Differences…", null, () -> false, () -> export("all"))));
        sections.put("COMPARE OPENS IN", open);
        FlowSettingsPopup.showChoices(anchor, "Settings", sections, null, true, true);
    }

    /** An item of the rail: its icon over its name; the selected one filled with the accent. */
    /** An item of a left rail (Compare, the Edit window). */
    static final class RailItem extends JPanel {
        private boolean selected, hover;

        private @Nullable Runnable run;

        void onClick(Runnable r) {
            run = r;
        }

        RailItem(String text, javax.swing.Icon icon, @Nullable Runnable run) {
            super(new BorderLayout(0, JBUI.scale(3)));
            this.run = run;
            setOpaque(false);
            setBorder(JBUI.Borders.empty(8, 6));
            JBLabel i = new JBLabel(icon);
            JBLabel t = new JBLabel(text, JBLabel.CENTER);
            t.setFont(JBUI.Fonts.smallFont());
            add(i, BorderLayout.CENTER);
            add(t, BorderLayout.SOUTH);
            Dimension d = new Dimension(JBUI.scale(68), JBUI.scale(58));
            setPreferredSize(d);
            setMaximumSize(d);
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) { if (RailItem.this.run != null) RailItem.this.run.run(); }
                @Override public void mouseEntered(java.awt.event.MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(java.awt.event.MouseEvent e) { hover = false; repaint(); }
            });
        }

        void setSelected(boolean on) {
            selected = on;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (selected || hover) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(selected ? JBUI.CurrentTheme.List.Selection.background(true) : JBUI.CurrentTheme.ActionButton.hoverBackground());
                int arc = JBUI.scale(10);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** About how big a side is as JSON, e.g. "2.4 KB". */
    private static String size(FlowValue v) {
        int bytes = FlowValueText.valueJson(v, true).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return bytes < 1024 ? bytes + " B" : bytes < 1024 * 1024 ? String.format("%.1f KB", bytes / 1024.0)
                : String.format("%.1f MB", bytes / (1024.0 * 1024));
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
            chips.add(new Chip(changed + " Changed", CHANGED, CHANGED_MARK));
            chips.add(new Chip(added + " Added", ADDED, ADDED_MARK));
            chips.add(new Chip(removed + " Removed", REMOVED, REMOVED_MARK));
        }
        String sep = "  <span style='color:" + hex(UIUtil.getContextHelpForeground()) + "'>|</span>  ";
        int n = changed + added + removed;
        statusLine.setIcon(pending ? AllIcons.General.Modified : AllIcons.General.InspectionsOK);
        statusLine.setText("<html><b>" + (pending ? "Edited, not saved yet" : n == 0 ? "Same data" : "Comparison Complete") + "</b>"
                + sep + changed + (changed == 1 ? " difference" : " differences") + sep + added + " added" + sep + removed + " removed"
                + sep + "~ " + size(left) + " → " + size(right) + "</html>");
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
        if (awaitingRight || awaitingLeft) { // a side isn't given yet: the given side (in this view) and the place to paste the other
            for (java.awt.Component c : cards.getComponents()) if ("START".equals(c.getName())) cards.remove(c);
            JComponent start = startPanel();
            start.setName("START");
            cards.add(start, "START");
            cards.revalidate();
            ((CardLayout) cards.getLayout()).show(cards, "START");
            railItems.forEach((k, b) -> b.setSelected(k.equals(view)));
            return;
        }
        if (made.add(view)) {
            boolean json = view.equals("JSON");
            FlowValue l = shown(left), r = shown(right);
            if (onlyChanges.isSelected()) {
                java.util.Set<String> keepLeft = new java.util.HashSet<>(), keepRight = new java.util.HashSet<>();
                for (FlowValueDiff.Row row : all) {
                    if (row.kind() == FlowValueDiff.Kind.SAME) continue;
                    if (row.leftPath() != null) keepLeft.add(row.leftPath());
                    if (row.rightPath() != null) keepRight.add(row.rightPath());
                }
                l = onlyThese(l, keepLeft);
                r = onlyThese(r, keepRight);
            }
            String a = json ? FlowValueText.valueJson(l, sort == com.example.flowtracker.model.FlowValueShape.Sort.AS_SAVED) : FlowValueDiff.withoutIds(FlowValueText.toText(l));
            String b = json ? FlowValueText.valueJson(r, sort == com.example.flowtracker.model.FlowValueShape.Sort.AS_SAVED) : FlowValueDiff.withoutIds(FlowValueText.toText(r));
            if (com.example.flowtracker.model.FlowValueShape.ignoreWhitespace()) { // lines that differ only in spaces: alike
                a = com.example.flowtracker.model.FlowValueShape.forDiff(a);
                b = com.example.flowtracker.model.FlowValueShape.forDiff(b);
            }
            DiffContentFactory f = DiffContentFactory.getInstance();
            com.intellij.openapi.fileTypes.FileType type = FileTypeManager.getInstance().getFileTypeByExtension(json ? "json" : "txt");
            DiffRequestPanel diff = DiffManager.getInstance().createRequestPanel(project, disposable, null);
            // sorted or filtered: only to look at (edits are made to the data as saved)
            boolean readOnly = shaping() || onlyChanges.isSelected();
            com.intellij.diff.contents.DocumentContent da = readOnly ? f.create(project, a, type) : f.createEditable(project, a, type);
            com.intellij.diff.contents.DocumentContent db = readOnly ? f.create(project, b, type) : f.createEditable(project, b, type);
            docs.put(view, new com.intellij.diff.contents.DocumentContent[]{da, db});
            // typing or pasting: the counts follow at once (as a preview until Save Edits)
            com.intellij.openapi.editor.event.DocumentListener typed = new com.intellij.openapi.editor.event.DocumentListener() {
                @Override public void documentChanged(@NotNull com.intellij.openapi.editor.event.DocumentEvent e) { recount.restart(); }
            };
            da.getDocument().addDocumentListener(typed, disposable);
            db.getDocument().addDocumentListener(typed, disposable);
            shownTexts.put(view, new String[]{a, b});
            SimpleDiffRequest request = new SimpleDiffRequest(title, da, db, "OLD  ·  " + leftTitle, "NEW  ·  " + rightTitle);
            request.putUserData(com.intellij.diff.util.DiffUserDataKeysEx.FORCE_DIFF_TOOL, unified
                    ? com.intellij.diff.tools.fragmented.UnifiedDiffTool.INSTANCE : com.intellij.diff.tools.simple.SimpleDiffTool.INSTANCE);
            // IntelliJ's diff ignores whitespace too (indentation, line ends) when the setting is on
            request.putUserData(com.intellij.diff.util.DiffUserDataKeys.PLACE, "TraceWeave.Compare");
            com.intellij.diff.tools.util.base.TextDiffSettingsHolder.TextDiffSettings.getSettings("TraceWeave.Compare").setIgnorePolicy(
                    com.example.flowtracker.model.FlowValueShape.ignoreWhitespace()
                            ? com.intellij.diff.tools.util.base.IgnorePolicy.IGNORE_WHITESPACES
                            : com.intellij.diff.tools.util.base.IgnorePolicy.DEFAULT);
            diff.setRequest(request);
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
        if (!view.equals("TREE")) diffView = view;
        viewButtons.forEach((k, b) -> b.setSelected(k.equals(view)));
        railItems.forEach((k, b) -> b.setSelected(k.equals(view)));
        layoutButton.setEnabled(!view.equals("TREE"));
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
        if (value == null) value = com.example.flowtracker.model.FlowDataParse.parse(onLeft ? left.name : right.name, text);
        if (value == null) {
            com.intellij.openapi.ui.Messages.showErrorDialog(cards, file.getName() + " isn't JSON. To compare other text (XML, a log), "
                    + "use Compare with nothing selected in Debug Data and paste it.", "Open File");
            return;
        }
        putOnSide(onLeft, value, name, file.getName());
    }

    /** The file's data on one side (it isn't a saved value any more: edits stay in this window). */
    /** Each side: its value to read, or (still to be given) a card to paste it, open a file or pick a saved value. */
    private JComponent startPanel() {
        com.intellij.ui.JBSplitter split = new com.intellij.ui.JBSplitter(false, 0.5f);
        split.setFirstComponent(awaitingLeft ? dataCard(true) : sideView(true));
        split.setSecondComponent(awaitingRight ? dataCard(false) : sideView(false));
        split.setBorder(JBUI.Borders.emptyTop(4));
        return split;
    }

    /** A side that is given: its value as JSON, Text or Tree (the rail's view), to read. */
    private JComponent sideView(boolean onLeft) {
        FlowValue v = shown(onLeft ? left : right);
        JComponent body;
        if (view.equals("TREE")) {
            javax.swing.tree.DefaultMutableTreeNode top = treeOf(v, true);
            com.intellij.ui.treeStructure.Tree tree = new com.intellij.ui.treeStructure.Tree(new javax.swing.tree.DefaultTreeModel(top));
            tree.setShowsRootHandles(true);
            tree.setCellRenderer(new com.intellij.ui.ColoredTreeCellRenderer() {
                @Override
                public void customizeCellRenderer(@NotNull javax.swing.JTree t, Object value, boolean selected, boolean expanded, boolean leaf,
                                                  int row, boolean focus) {
                    if (!(((javax.swing.tree.DefaultMutableTreeNode) value).getUserObject() instanceof Object[] o)) return;
                    FlowValue x = (FlowValue) o[0];
                    setIcon(FlowVariablesLook.icon((Boolean) o[1]));
                    append(x.name.replaceAll("^\\[(\\d+)]$", "$1"), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    append(" = ", SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    String text = FlowVariablesLook.text(x.value, x.type), type = FlowVariablesLook.type(x.type);
                    if (!x.children.isEmpty() && !type.isEmpty()) append(type + (text.isEmpty() ? "" : " "), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    append(text, valueColor(text));
                }
            });
            tree.expandRow(0);
            for (int r = 1; r < Math.min(tree.getRowCount(), 40); r++) if (tree.getPathForRow(r).getPathCount() <= 2) tree.expandRow(r);
            body = FlowScroll.wheel(new JBScrollPane(tree));
        } else {
            FlowCodeView code = new FlowCodeView(project, disposable);
            boolean json = !view.equals("TEXT");
            code.setText(json ? FlowValueText.valueJson(v, false) : FlowValueText.toText(v), json);
            code.setEditable(false);
            body = code.getComponent();
        }
        JBLabel title = new JBLabel((onLeft ? "OLD" : "NEW") + "  ·  " + (onLeft ? leftTitle : rightTitle), AllIcons.FileTypes.Json, JBLabel.LEFT);
        title.setForeground(onLeft ? REMOVED_MARK : ADDED_MARK);
        title.setFont(JBUI.Fonts.label().asBold());
        JPanel p = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        p.add(title, BorderLayout.NORTH);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    private static javax.swing.tree.DefaultMutableTreeNode treeOf(FlowValue v, boolean top) {
        javax.swing.tree.DefaultMutableTreeNode n = new javax.swing.tree.DefaultMutableTreeNode(new Object[]{v, top});
        for (FlowValue c : v.children) n.add(treeOf(c, false));
        return n;
    }

    /** What pasted data is: JSON, TraceWeave's text, a toString() (each read as fields), XML, or other text. */
    static String detect(String text) {
        String t = text.strip();
        if (t.isEmpty()) return "";
        String kind = com.example.flowtracker.model.FlowDataParse.kind(t);
        if (!kind.isEmpty()) {
            FlowValue v = com.example.flowtracker.model.FlowDataParse.parse("v", t);
            int n = 0;
            java.util.ArrayDeque<FlowValue> all = new java.util.ArrayDeque<>(v == null ? List.of() : v.children);
            while (!all.isEmpty()) {
                FlowValue c = all.pop();
                n++;
                all.addAll(c.children);
            }
            return kind + " · " + n + (n == 1 ? " field" : " fields") + " (compared field by field)";
        }
        if (t.startsWith("<") && t.endsWith(">")) return "XML (compared as text, line by line)";
        if (t.startsWith("{") || t.startsWith("[")) return "Not valid JSON yet: it is compared as text";
        return "Text (compared line by line)";
    }

    /** "Add Data to Compare": paste the clipboard, open a file, pick a saved value, or type / paste it here. */
    private JComponent dataCard(boolean onLeft) {
        FlowCodeView paste = new FlowCodeView(project, disposable);
        String draft = onLeft ? leftDraft : rightDraft;
        paste.setText(draft, draft.isBlank() || detect(draft).startsWith("JSON"));
        paste.setEditable(true);
        if (!onLeft || !awaitingRight) pasteArea = paste;

        JBLabel big = new JBLabel(com.intellij.util.IconUtil.scale(AllIcons.FileTypes.Json, null, 3f));
        JBLabel side = new JBLabel(onLeft ? "OLD" : "NEW");
        side.setForeground(onLeft ? REMOVED_MARK : ADDED_MARK);
        side.setFont(JBUI.Fonts.label().asBold());
        JBLabel title = new JBLabel("Add Data to Compare");
        title.setFont(JBUI.Fonts.label().biggerOn(6f).asBold());
        JBLabel hint = new JBLabel("Paste or type the data " + (onLeft ? "to compare" : "to compare with")
                + ": JSON, XML, toString() or any text (detected by itself)");
        hint.setForeground(UIUtil.getContextHelpForeground());
        FlowButton fromClipboard = new FlowButton("Paste Clipboard", AllIcons.Actions.MenuPaste, FlowButton.Style.PRIMARY);
        fromClipboard.setToolTipText("Put the clipboard on this side");
        FlowButton fromFile = new FlowButton("Open File…", FlowTheme.ICON_IMPORT, FlowButton.Style.OUTLINE);
        fromFile.setToolTipText("A .json file, or a .flowdebug.json file exported from Debug Data");
        FlowButton fromSaved = new FlowButton("A Saved Value…", AllIcons.Debugger.Watch, FlowButton.Style.OUTLINE);
        fromSaved.setToolTipText("A saved value from Debug Data");
        FlowButton use = new FlowButton("Use This Data", AllIcons.Actions.Diff, FlowButton.Style.PRIMARY);
        use.setToolTipText("Put what is typed or pasted here on this side (Ctrl+Enter)");
        use.setEnabled(false);
        JBLabel detected = new JBLabel("Detected: " + (draft.isBlank() ? "nothing yet" : detect(draft)));
        detected.setForeground(UIUtil.getContextHelpForeground());
        use.setEnabled(!draft.isBlank());
        paste.onUserEdit(() -> {
            if (onLeft) leftDraft = paste.getText();
            else rightDraft = paste.getText();
            use.setEnabled(!paste.getText().isBlank());
            String what = detect(paste.getText());
            paste.setJson(what.isEmpty() || what.startsWith("JSON") || what.startsWith("Not valid JSON")); // colors as it is
            detected.setText("Detected: " + (what.isEmpty() ? "nothing yet" : what));
            detected.setIcon(what.startsWith("JSON") ? AllIcons.FileTypes.Json : what.startsWith("XML") ? AllIcons.FileTypes.Xml
                    : what.isEmpty() ? null : AllIcons.FileTypes.Text);
        });
        Runnable go = () -> {
            String text = paste.getText();
            if (text.isBlank()) return;
            String name = onLeft ? left.name : right.name;
            FlowValue v = com.example.flowtracker.model.FlowDataParse.parse(name, text);
            if (v == null) {
                v = new FlowValue(name);
                v.value = text.strip();
            }
            putOnSide(onLeft, v, "Pasted · " + new java.text.SimpleDateFormat("d MMM HH:mm:ss").format(new java.util.Date()), "pasted data");
        };
        use.addActionListener(e -> go.run());
        fromClipboard.addActionListener(e -> {
            String text = com.intellij.openapi.ide.CopyPasteManager.getInstance().getContents(java.awt.datatransfer.DataFlavor.stringFlavor);
            if (text == null || text.isBlank()) {
                editHint.setText("The clipboard is empty: copy the data to compare first.");
                return;
            }
            if (onLeft) leftDraft = text;
            else rightDraft = text;
            paste.setText(text, detect(text).startsWith("JSON"));
            detected.setText("Detected: " + detect(text));
            go.run();
        });
        fromFile.addActionListener(e -> openFile(onLeft));
        fromSaved.addActionListener(e -> pickSaved(onLeft, fromSaved));
        paste.getContentComponent().registerKeyboardAction(e -> go.run(), javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), JComponent.WHEN_FOCUSED);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, JBUI.scale(12), 0));
        buttons.setOpaque(false);
        buttons.add(fromClipboard);
        buttons.add(fromFile);
        buttons.add(fromSaved);
        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new javax.swing.BoxLayout(head, javax.swing.BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[]{big, side, title, hint, buttons}) {
            c.setAlignmentX(0.5f);
            head.add(c);
            head.add(javax.swing.Box.createVerticalStrut(JBUI.scale(c == big ? 10 : c == buttons ? 18 : 6)));
        }
        JPanel useRow = new JPanel(new BorderLayout());
        useRow.setOpaque(false);
        useRow.add(detected, BorderLayout.WEST);
        useRow.add(use, BorderLayout.EAST);
        JComponent editor = paste.getComponent();
        editor.setBorder(new com.intellij.ui.RoundedLineBorder(FlowTheme.CARD_BORDER, JBUI.scale(10), 1));
        JPanel card = new JPanel(new BorderLayout(0, JBUI.scale(10)));
        card.add(head, BorderLayout.NORTH);
        card.add(editor, BorderLayout.CENTER);
        card.add(useRow, BorderLayout.SOUTH);
        card.setBorder(JBUI.Borders.compound(new com.intellij.ui.RoundedLineBorder(onLeft ? REMOVED_MARK : ADDED_MARK, JBUI.scale(14), 1),
                JBUI.Borders.empty(24, 28, 20, 28)));
        JPanel outer = new JPanel(new BorderLayout());
        outer.add(card, BorderLayout.CENTER);
        outer.setBorder(JBUI.Borders.empty(4, onLeft ? 0 : 6, 4, onLeft ? 6 : 0));
        if (onLeft || !awaitingLeft) javax.swing.SwingUtilities.invokeLater(paste::requestFocus);
        return outer;
    }

    /** A side was given: once both are, the comparison shows; until then the other side still asks for its data. */
    private void sideGiven(boolean onLeft) {
        if (onLeft ? !awaitingLeft : !awaitingRight) return;
        if (onLeft) awaitingLeft = false;
        else awaitingRight = false;
        for (java.awt.Component c : cards.getComponents()) if ("START".equals(c.getName())) cards.remove(c);
        if (awaitingLeft || awaitingRight) {
            JComponent start = startPanel();
            start.setName("START");
            cards.add(start, "START");
        }
        if (!awaitingLeft && !awaitingRight && !view.equals("TREE") && !view.equals("TEXT")) view = "JSON";
        refreshAll(); // compared again, the sides' cards and the view shown
        updateEditButtons();
    }

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
        if (onLeft ? awaitingLeft : awaitingRight) {
            // a side still to be given: its value now, and Undo / History start from here
            if (onLeft) left = value;
            else right = value;
            steps.clear();
            steps.add(new State(left.copy(), right.copy(), "Opened", System.currentTimeMillis()));
            position = 0;
            sideGiven(onLeft);
            return;
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
        return !com.example.flowtracker.model.FlowValueShape.plain(sort, filter.getText()) || hidingInternals() || source != Source.SAVED;
    }

    private FlowValue shown(FlowValue v) {
        v = base(v);
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
        List<FlowValueDiff.Row> rows = FlowValueDiff.compare(shown(left), shown(right));
        if (!com.example.flowtracker.model.FlowValueShape.ignoreWhitespace()) return rows;
        // a value that differs only in whitespace is the same
        List<FlowValueDiff.Row> out = new java.util.ArrayList<>(rows.size());
        for (FlowValueDiff.Row r : rows) {
            boolean alike = r.kind() == FlowValueDiff.Kind.CHANGED && r.left() != null && r.right() != null
                    && com.example.flowtracker.model.FlowValueShape.sameIgnoringWhitespace(r.left(), r.right());
            out.add(alike ? new FlowValueDiff.Row(r.label(), r.name(), r.left(), r.right(), r.type(), r.depth(), FlowValueDiff.Kind.SAME,
                    r.leftPath(), r.rightPath()) : r);
        }
        return out;
    }

    private void setSort(com.example.flowtracker.model.FlowValueShape.Sort s) {
        sort = s;
        showDropdowns();
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
            if (!javax.swing.SwingUtilities.isDescendingFrom(ed.getComponent(), card)) continue;
            FlowCodeView.fold(ed, view.equals("JSON"), collapse);
            if (view.equals("TEXT")) FlowTextColors.apply(ed);
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
