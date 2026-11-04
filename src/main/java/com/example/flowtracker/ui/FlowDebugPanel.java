package com.example.flowtracker.ui;

import com.example.flowtracker.compat.FlowCompat;
import com.example.flowtracker.copy.FlowCopy;
import com.example.flowtracker.debug.FlowDataRecorder;
import com.example.flowtracker.debug.FlowDebugCapture;
import com.example.flowtracker.debug.FlowValueReader;
import com.example.flowtracker.model.FlowDebugStore;
import com.example.flowtracker.model.FlowDebugTransfer;
import com.example.flowtracker.model.FlowSnapshot;
import com.example.flowtracker.model.FlowValue;
import com.example.flowtracker.model.FlowValueEdit;
import com.example.flowtracker.model.FlowValueSearch;
import com.example.flowtracker.model.FlowValueText;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.components.JBTextArea;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import com.intellij.xdebugger.frame.XStackFrame;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The "Debug Data" tab of the TraceWeave tool window: values saved from the debugger (right-click a variable in
 * the Variables view → Save to TraceWeave), grouped by flow and by line, with their fields. Find a name, key or
 * value in all of them (and in what the debugger shows now), compare a value with the same variable saved earlier
 * or with its current value, edit saved values (with a history to revert), and restore deleted ones from the
 * Recycle Bin.
 */
public final class FlowDebugPanel extends JPanel implements Disposable {
    /** The tab's name in the tool window. */
    public static final String TAB = "Debug Data";
    private static final JBColor EDITED = new JBColor(0xA86500, 0xF0B354);
    private static final SimpleTextAttributes MATCH = new SimpleTextAttributes(SimpleTextAttributes.STYLE_SEARCH_MATCH, null);

    private final Project project;
    private final FlowDebugStore store;
    private final Runnable storeListener = this::reload;

    private final SearchTextField search = new SearchTextField(false);
    private final JBCheckBox searchNow = new JBCheckBox("Also in the current debug session");
    private final Timer searchTimer = new Timer(350, e -> runSearch());

    private final Tree snapshots = new Tree();
    private final DefaultListModel<FlowValueSearch.Match> resultModel = new DefaultListModel<>();
    private final JBList<FlowValueSearch.Match> results = new JBList<>(resultModel);
    private final CardLayout leftCards = new CardLayout();
    private final JPanel left = new JPanel(leftCards);
    private final Tree values = new Tree();
    private final JBLabel valueTitle = new JBLabel();
    private final JBLabel status = new JBLabel();

    private static final String EXPRESSION_TIP = "Evaluate an expression where the debugger is paused (e.g. request.getSession() "
            + "or order.items) and save the result";
    private static final String NO_DEBUGGER_TIP = "Enable the Debugger mode to enable this function: start debugging and pause "
            + "at a breakpoint";
    private final FlowButton addExpression = button("Add Expression…", AllIcons.Debugger.EvaluateExpression, EXPRESSION_TIP,
            this::addExpression);
    /** Stop at Value: the debugger stops where a value is found (see FlowValueHunt). */
    private final FlowButton stopAtValue = button("Stop at Value…", AllIcons.Actions.Find,
            "Stop the debugger where a value shows up: in any thread, API answer or project, at any stop or line", this::stopAtValue);
    private final Runnable huntListener = () -> javax.swing.SwingUtilities.invokeLater(this::showHunt);
    private final FlowButton capture = button("Capture Data", FlowTheme.ICON_CAPTURE, "", this::toggleCapture);
    private final FlowDataRecorder recorder;
    private final Runnable recorderListener = this::updateButtons;
    /** Checks once a second whether a debug session is paused (Add Expression works only then). */
    private final Timer debuggerTimer = new Timer(1000, e -> updateDebuggerButtons());
    private final FlowButton importData = button("Import…", FlowTheme.ICON_IMPORT,
            "Add the saved values of a .flowdebug.json file (exported from Debug Data, e.g. by a teammate)", this::importData);
    private final FlowButton exportData = button("Export…", FlowTheme.ICON_EXPORT,
            "Save the selected values (or all of them) with their fields and edit history to a .flowdebug.json file",
            this::exportData);
    private final FlowButton compare = button("Compare", AllIcons.Actions.Diff, "Compare the selected value with the same variable saved "
            + "at this line before or with its value now, or compare two pieces of pasted data (Ctrl+D). Select two saved values (Ctrl+click) to compare them with each other", this::compareSelected);
    private final FlowButton flowsButton = button("Flow", FlowTheme.ICON_FLOWS,
            "The flow new values go to (the Flow tab's flows): switch, drag to reorder, new, rename, delete, export, import",
            this::flowsMenu);
    private final FlowButton settingsButton = button("Settings", FlowTheme.ICON_SETTINGS,
            "Layout (side by side or top and bottom) and what Capture Data saves", () -> settingsMenu(null));
    private final FlowButton binSettingsButton = button("Settings", FlowTheme.ICON_SETTINGS,
            "Layout (side by side or top and bottom)", () -> settingsMenu(null));
    private final com.example.flowtracker.model.FlowStore flows;
    private final java.util.function.BiConsumer<String, String> flowRenamed;
    /** Repaints in the Flow tab's colors when its theme (or IntelliJ's) changes. */
    private Runnable themeChanged = () -> {};
    private final Runnable flowsChanged;
    private String flowsSeen = "";
    private final JBSplitter split = new JBSplitter(false, 0.38f);
    private final FlowButton edit = button("Edit", AllIcons.Actions.Edit, "Change the selected field's name or value (F2; kept in its history)", this::editSelected);
    private final FlowButton newValue = button("New Value…", AllIcons.General.Add,
            "Add a saved value by hand: give it a name and paste or type its data (JSON becomes fields)", this::newValue);
    private final FlowButton resetValue = button("Reset", AllIcons.Actions.Rollback,
            "Back to the data as it was saved (kept in History, so it can be reverted too)", this::resetShown);
    private final javax.swing.JButton applyText = new javax.swing.JButton("Apply");
    private final javax.swing.JButton cancelText = new javax.swing.JButton("Cancel");
    private final JBLabel textHint = new JBLabel();
    private final JPanel textBar = new JPanel(new BorderLayout(JBUI.scale(8), 0));
    /** True while the JSON or Text view is being edited. */
    private boolean textEditing;
    /** True while the text is set by the panel (not typed). */
    private boolean settingText;
    private final FlowButton addField = button("Add Field", AllIcons.General.Add,
            "Add a field under the selected one (or under the value itself) (Insert)", this::addFieldToSelected);
    private final FlowButton removeField = button("Remove Field", AllIcons.General.Remove,
            "Remove the selected field (Delete; kept in the history, so Revert puts it back)", this::removeSelectedField);
    private final FlowButton history = button("History", AllIcons.Vcs.History, "Changes made to this saved value; revert any of them", this::showHistory);
    private final FlowButton delete = button("Delete", FlowTheme.ICON_DELETE, "Move the selected saved values to the Recycle Bin (Delete)", this::deleteSelected);
    private final FlowButton bin = button("Recycle Bin", FlowTheme.ICON_RECYCLE, "Show deleted values to restore them", this::toggleBin);
    private final FlowButton back = button("Back to Saved Values", AllIcons.Actions.Back, "Leave the Recycle Bin", this::toggleBin);
    private final JPanel buttons = new JPanel(new CardLayout());
    private final FlowButton restore = button("Restore", AllIcons.Actions.Rollback, "Put the selected values back", this::restoreSelected);
    private final FlowButton purge = button("Delete Permanently", FlowTheme.ICON_DELETE, "Delete the selected values for good", this::purgeSelected);

    /** How the selected value is shown: as the debugger's tree, as JSON, or as plain indented text. */
    private enum View {
        TREE("Tree", "The fields as the debugger shows them; double-click one to edit it"),
        JSON("JSON", "The value as JSON: objects and maps as objects, lists as arrays (instance ids left out)"),
        TEXT("Text", "The value as plain indented text: name = value {type}");
        final String label, tip;
        View(String label, String tip) { this.label = label; this.tip = tip; }
    }

    private final JPanel valueCards = new JPanel(new CardLayout());
    /** The JSON / Text view: an IntelliJ editor (colors, foldable blocks, Ctrl+F). */
    private final FlowCodeView asText;
    /** How the value is shown (never how it is saved): sorted by name, and only the fields that match the filter. */
    private com.example.flowtracker.model.FlowValueShape.Sort sort = com.example.flowtracker.model.FlowValueShape.Sort.AS_SAVED;
    private final SearchTextField valueFilter = new SearchTextField(false);
    private final Map<com.example.flowtracker.model.FlowValueShape.Sort, FlowButton> sortButtons =
            new java.util.EnumMap<>(com.example.flowtracker.model.FlowValueShape.Sort.class);
    private final Map<View, FlowButton> viewButtons = new java.util.EnumMap<>(View.class);
    private View view = View.TREE;
    private final FlowButton copyValue = button("Copy", FlowTheme.ICON_COPY, "Copy the value as shown (JSON or text; the tree copies as text)", this::copyShown);
    private final FlowButton exportValue = button("Save As…", FlowTheme.ICON_EXPORT, "Save this value as a .json or .txt file", this::exportShown);

    private boolean showingBin;
    /** What the value tree shows: a saved value, or (snapshot null) the current debug session's variables. */
    private @Nullable FlowSnapshot shownSnapshot;
    private @Nullable FlowValue shownRoot;
    /** The current debug session's variables, read for the last search that included them. */
    private @Nullable FlowValue nowValues;

    public FlowDebugPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.asText = new FlowCodeView(project, this);
        this.store = FlowDebugStore.get(project);
        this.recorder = FlowDataRecorder.get(project);
        this.flows = com.example.flowtracker.model.FlowStore.get(project);
        // the flows are the Flow tab's (new, deleted, moved flows show), but their names here are Debug Data's own:
        // a rename there keeps the name shown here (the values stay with the flow)
        flowRenamed = (old, now) -> store.flowRenamedInFlowTab(old, now);
        flowsChanged = () -> {
            showFlowName();
            String seen = flows.getFlowNames() + "#" + flows.getActiveFlowIndex();
            if (!seen.equals(flowsSeen)) {
                flowsSeen = seen;
                reload();
            }
        };
        flows.addRenameListener(flowRenamed);
        com.example.flowtracker.debug.FlowValueHunt.get(project).addListener(huntListener);
        flows.addListener(flowsChanged);
        searchTimer.setRepeats(false);
        capture.setDropDown("Where to capture: where the debugger stops, or every line it runs", this::chooseCaptureMode);
        compare.setDropDown("Compare with its value now or with an earlier save", this::compareMenu);

        search.getTextEditor().getEmptyText().setText("Find a name, key, type or value (e.g. cart, 4711 or /order.*Id/)");
        search.addDocumentListener(new DocumentAdapter() {
            @Override protected void textChanged(@NotNull DocumentEvent e) { searchTimer.restart(); }
        });
        searchNow.setToolTipText("Read the variables of the current stack frame (a few levels deep, e.g. the session's "
                + "attributes) and search them too");
        searchNow.addActionListener(e -> {
            nowValues = null;
            runSearch();
        });
        JPanel searchRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        // as in the Flow tab: the Flow button keeps its own height (not stretched to the search box's)
        JPanel flowBox = new JPanel(new java.awt.GridBagLayout());
        flowBox.setOpaque(false);
        flowBox.add(flowsButton);
        searchRow.add(flowBox, BorderLayout.WEST);
        searchRow.add(search, BorderLayout.CENTER);
        // the search option and Layout (where the list and the value go) at the top right
        JPanel searchEnd = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0));
        searchEnd.add(searchNow);

        searchRow.add(searchEnd, BorderLayout.EAST);

        // Two toolbars that wrap and shrink with the tool window like the Flow tab's: saved values, and the Recycle Bin.
        FlowToolbar savedBar = new FlowToolbar();
        savedBar.addGroup(false, 1, capture, stopAtValue, addExpression, newValue);
        savedBar.addGroup(false, 1, compare, history);
        savedBar.addGroup(false, 1, delete, bin);
        savedBar.addGroup(false, 1, importData, exportData);
        savedBar.addGroup(false, 1, settingsButton);
        FlowToolbar binBar = new FlowToolbar();
        binBar.addGroup(false, 1, back);
        binBar.addGroup(false, 1, restore, purge);
        binBar.addGroup(false, 1, binSettingsButton);
        buttons.add(savedBar, "saved");
        buttons.add(binBar, "bin");
        JPanel top = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        top.add(searchRow, BorderLayout.NORTH);
        top.add(buttons, BorderLayout.CENTER);
        top.setBorder(JBUI.Borders.empty(6, 6, 6, 6));

        snapshots.setRootVisible(false);
        snapshots.setShowsRootHandles(true);
        snapshots.setCellRenderer(new SnapshotRenderer());
        snapshots.getEmptyText().setText("No saved debug values yet");
        snapshots.getEmptyText().appendSecondaryText("While debugging, right-click a variable in the Variables view → Save to TraceWeave",
                SimpleTextAttributes.GRAYED_ATTRIBUTES, null);
        snapshots.addTreeSelectionListener(e -> {
            FlowSnapshot s = selectedSnapshot();
            if (s != null) showValue(s, s.value, null);
            else showLineValues();
            selectFlowEverywhere();
            updateButtons();
        });
        results.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        results.setCellRenderer(new MatchRenderer());
        results.addListSelectionListener(e -> {
            FlowValueSearch.Match m = results.getSelectedValue();
            if (m != null && !e.getValueIsAdjusting()) showValue(m.snapshot(), m.root(), m.path());
            updateButtons();
        });
        left.add(FlowCard.plain(FlowScroll.wheel(new JBScrollPane(snapshots))), "tree");
        left.add(FlowCard.plain(FlowScroll.wheel(new JBScrollPane(results))), "results");

        values.setCellRenderer(new ValueRenderer());
        values.getEmptyText().setText("Select a saved value to see its fields");
        values.addTreeSelectionListener(e -> updateButtons());
        values.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) { valuesFocusedLast = true; updateButtons(); }
        });
        java.awt.event.FocusAdapter listFocus = new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) { valuesFocusedLast = false; updateButtons(); }
        };
        snapshots.addFocusListener(listFocus);
        results.addFocusListener(listFocus);
        com.intellij.ui.PopupHandler.installPopupMenu(values, valueMenu(), "TraceWeave.DebugValue");
        values.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && shownSnapshot != null && selectedValuePath() != null) editSelected();
            }

            // the button down on a field's icon: bookmark it (to the top of its parent), or take the bookmark off
            @Override public void mousePressed(MouseEvent e) {
                if (!javax.swing.SwingUtilities.isLeftMouseButton(e) || e.getClickCount() != 1) return;
                int row = values.getClosestRowForLocation(e.getX(), e.getY());
                if (row < 0 || !onValueIcon(e.getPoint(), row)) return;
                if (((DefaultMutableTreeNode) values.getPathForRow(row).getLastPathComponent()).getUserObject() instanceof Field f) {
                    toggleBookmark(f);
                    e.consume();
                }
            }

            @Override public void mouseExited(MouseEvent e) { valueHover(-1); }
        });
        values.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int row = values.getRowForLocation(e.getX(), e.getY());
                boolean on = row >= 0 && onValueIcon(e.getPoint(), row);
                valueHover(on ? row : -1);
                values.setCursor(java.awt.Cursor.getPredefinedCursor(on ? java.awt.Cursor.HAND_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
                Field f = on && ((DefaultMutableTreeNode) values.getPathForRow(row).getLastPathComponent()).getUserObject() instanceof Field x ? x : null;
                values.setToolTipText(f == null ? null : f.pinned() ? "Bookmarked: click to put it back in its place"
                        : "Bookmark: to the top of its parent (here and in Stop at Value)");
            }
        });
        valueTitle.setBorder(JBUI.Borders.empty(2, 4, 4, 4));
        asText.setEditable(false);
        asText.onUserEdit(() -> {
            if (!settingText && !textEditing && asText.isEditable()) startTextEdit();
        });
        valueCards.add(FlowCard.plain(FlowScroll.wheel(new JBScrollPane(values))), View.TREE.name());
        JPanel textCard = new JPanel(new BorderLayout());
        textCard.add(asText.getComponent(), BorderLayout.CENTER);
        JPanel textButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0));
        textButtons.add(applyText);
        textButtons.add(cancelText);
        textHint.setForeground(UIUtil.getContextHelpForeground());
        textBar.add(textHint, BorderLayout.CENTER);
        textBar.add(textButtons, BorderLayout.EAST);
        textBar.setBorder(JBUI.Borders.empty(6, 4, 2, 4));
        textBar.setVisible(false);
        textCard.add(textBar, BorderLayout.SOUTH);
        applyText.setToolTipText("Save the edited data (Ctrl+Enter); kept in History");
        cancelText.setToolTipText("Leave the editing without saving (Esc)");
        applyText.addActionListener(e -> applyTextEdit());
        cancelText.addActionListener(e -> stopTextEdit());
        int menuKey = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, menuKey), "applyText", () -> {
            if (textEditing) applyTextEdit();
        }, asText.getContentComponent());
        key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "cancelText", () -> {
            if (textEditing) stopTextEdit();
        }, asText.getContentComponent());
        valueCards.add(textCard, View.JSON.name());
        // A toolbar that wraps onto more lines (and then shows icons only) when the pane is narrow.
        FlowToolbar views = new FlowToolbar();
        List<FlowButton> segments = new ArrayList<>();
        for (View v : View.values()) {
            FlowButton b = new FlowButton(v.label, switch (v) {
                case TREE -> AllIcons.Actions.ShowAsTree;
                case JSON -> AllIcons.FileTypes.Json;
                default -> AllIcons.FileTypes.Text;
            }, FlowButton.Style.SEGMENT);
            b.setToolTipText(v.tip);
            b.addActionListener(e -> setView(v));
            viewButtons.put(v, b);
            segments.add(b);
        }
        views.addGroup(true, 2, segments.toArray(new FlowButton[0]));
        views.addGroup(false, 2, addField, edit, removeField, resetValue);
        views.addGroup(false, 2, copyValue, exportValue);
        views.setBorder(JBUI.Borders.empty(0, 2, 4, 2));
        FlowToolbar shape = new FlowToolbar();
        FlowButton expandAll = button("Expand All", AllIcons.Actions.Expandall, "Open every block of the value", this::expandValue);
        FlowButton collapseAll = button("Collapse All", AllIcons.Actions.Collapseall,
                "Fold every block: the value's own fields show, each folded", this::collapseValue);
        shape.addGroup(false, 0, expandAll, collapseAll);
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
        FlowButton internals = new FlowButton("Hide Internals", AllIcons.Actions.ToggleVisibility, FlowButton.Style.SEGMENT);
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
            reshape();
        });
        shape.addGroup(true, 1, internals);
        valueFilter.getTextEditor().getEmptyText().setText("Filter the fields (name, value or type)");
        valueFilter.setToolTipText("Only the fields that have this text, with the fields around them (Ctrl+F)");
        valueFilter.addDocumentListener(new DocumentAdapter() {
            @Override protected void textChanged(@NotNull DocumentEvent e) { reshape(); }
        });
        JPanel shapeRow = new JPanel(new ShapeRowLayout(shape, valueFilter));
        shapeRow.setOpaque(false);
        shapeRow.add(shape);
        shapeRow.add(valueFilter);
        shapeRow.setBorder(JBUI.Borders.empty(0, 2, 6, 2));
        shapeRow.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { // side by side, or the filter under
                if (shapeRow.getLayout().preferredLayoutSize(shapeRow).height != shapeRow.getHeight()) {
                    javax.swing.SwingUtilities.invokeLater(shapeRow::revalidate);
                }
            }
        });
        JPanel tools = new JPanel(new BorderLayout());
        tools.setOpaque(false);
        tools.add(views, BorderLayout.NORTH);
        tools.add(shapeRow, BorderLayout.SOUTH);
        JPanel head = new JPanel(new BorderLayout());
        head.add(valueTitle, BorderLayout.CENTER);
        head.add(tools, BorderLayout.SOUTH);
        JPanel right = new JPanel(new BorderLayout());
        right.add(head, BorderLayout.NORTH);
        right.add(valueCards, BorderLayout.CENTER);
        // Ctrl+F in the value: find in its JSON / text, or filter the tree's fields
        key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                "findInValue", this::findInValue, right);
        setView(View.TREE);

        // the Flow tab's look: the panel background, the list and the value each on a rounded card
        FlowCard leftCard = FlowCard.of(left);
        FlowCard rightCard = FlowCard.of(right);
        for (JComponent c : List.<JComponent>of(top, searchRow, searchEnd, searchNow, buttons, savedBar, binBar, left, right, head,
                views, valueCards, textCard, textBar, textButtons, status, split)) {
            c.setOpaque(false);
        }
        split.setFirstComponent(leftCard);
        split.setSecondComponent(rightCard);
        split.setDividerWidth(JBUI.scale(8));
        split.getDivider().setOpaque(false);
        // Both sides can be made small (the toolbars wrap and the title is cut), so the divider moves either way.
        left.setMinimumSize(new Dimension(JBUI.scale(80), JBUI.scale(60)));
        right.setMinimumSize(new Dimension(JBUI.scale(80), JBUI.scale(60)));
        valueTitle.setMinimumSize(new Dimension(0, 0));

        status.setForeground(UIUtil.getContextHelpForeground());
        status.setBorder(JBUI.Borders.empty(4, 6));
        setBackground(FlowTheme.PANEL_BG);
        setBorder(JBUI.Borders.empty(10));
        top.setBorder(JBUI.Borders.emptyBottom(10));
        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        // "Use IntelliJ Theme" switched: the colors are looked up when painting, so a repaint shows the new ones
        // (the lists' card color is set on them, so it is set again)
        themeChanged = () -> {
            for (JComponent c : List.<JComponent>of(snapshots, results, values)) c.setBackground(FlowTheme.CARD_BG);
            repaint();
        };
        com.example.flowtracker.model.FlowLook.get().addListener(themeChanged);
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { applyLayout(); }
        });
        keys();
        add(status, BorderLayout.SOUTH);

        store.addListener(storeListener);
        recorder.addListener(recorderListener);
        debuggerTimer.start();
        Disposer.register(this, () -> {
            searchTimer.stop();
            debuggerTimer.stop();
        });
        showFlowName();
        reload();
    }

    /**
     * The buttons that shape the value (expand, sort, Java internals) and the filter box: side by side while the
     * buttons fit on one line next to a filter box of a useful width, else the filter box on a line of its own under
     * them, as wide as the pane. Nothing is stretched or cut.
     */
    private static final class ShapeRowLayout implements java.awt.LayoutManager {
        private final FlowToolbar bar;
        private final JComponent filter;

        ShapeRowLayout(FlowToolbar bar, JComponent filter) {
            this.bar = bar;
            this.filter = filter;
        }

        private static int gap() { return JBUI.scale(8); }
        private static int filterMin() { return JBUI.scale(180); }

        /** The inner width, and whether both fit on one line in it (the toolbar is told what is left for it). */
        private boolean side(java.awt.Container parent) {
            java.awt.Insets in = parent.getInsets();
            int w = parent.getWidth() - in.left - in.right;
            if (w <= 0) return true;
            boolean side = bar.fitsOneLine(w - filterMin() - gap());
            bar.reserve(side ? filterMin() + gap() : 0);
            return side;
        }

        @Override public void addLayoutComponent(String name, java.awt.Component comp) {}
        @Override public void removeLayoutComponent(java.awt.Component comp) {}

        @Override
        public Dimension preferredLayoutSize(java.awt.Container parent) {
            java.awt.Insets in = parent.getInsets();
            boolean side = side(parent);
            Dimension b = bar.getPreferredSize(), f = filter.getPreferredSize();
            int h = side ? Math.max(b.height, f.height) : b.height + gap() / 2 + f.height;
            return new Dimension(b.width + (side ? gap() + filterMin() : 0) + in.left + in.right, h + in.top + in.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(java.awt.Container parent) {
            return new Dimension(0, preferredLayoutSize(parent).height);
        }

        @Override
        public void layoutContainer(java.awt.Container parent) {
            java.awt.Insets in = parent.getInsets();
            int x = in.left, y = in.top, w = parent.getWidth() - in.left - in.right;
            boolean side = side(parent);
            Dimension b = bar.getPreferredSize(), f = filter.getPreferredSize();
            if (side) {
                int h = Math.max(b.height, f.height);
                bar.setBounds(x, y + (h - b.height) / 2, b.width, b.height);
                int fx = x + b.width + gap();
                filter.setBounds(fx, y + (h - f.height) / 2, Math.max(0, x + w - fx), f.height);
            } else {
                bar.setBounds(x, y, Math.min(w, b.width), b.height);
                filter.setBounds(x, y + b.height + gap() / 2, w, f.height);
            }
        }
    }

    private static FlowButton button(String text, javax.swing.Icon icon, String tip, Runnable action) {
        FlowButton b = new FlowButton(text, icon, FlowButton.Style.OUTLINE);
        b.setToolTipText(tip);
        b.addActionListener(e -> action.run());
        return b;
    }

    // ---------- opening from elsewhere ----------

    /** Shows the Debug Data tab with a saved value selected (e.g. right after Save to TraceWeave). */
    public static void reveal(Project project, @Nullable FlowSnapshot snapshot) {
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("TraceWeave");
        if (window == null) return;
        window.show(() -> {
            Content content = window.getContentManager().findContent(TAB);
            if (content == null) return;
            window.getContentManager().setSelectedContent(content);
            if (snapshot != null && content.getComponent() instanceof FlowDebugPanel panel) panel.select(snapshot);
        });
    }

    void select(FlowSnapshot s) {
        if (showingBin) toggleBin();
        search.setText("");
        leftCards.show(left, "tree");
        DefaultMutableTreeNode node = TreeUtil.findNodeWithObject((DefaultMutableTreeNode) snapshots.getModel().getRoot(), s);
        if (node != null) TreeUtil.selectNode(snapshots, node);
    }

    // ---------- the saved values ----------

    /** Rebuilds the list of saved (or deleted) values: by flow, then by line. */
    /** A flow (or one of its lines or values) picked here: it becomes the selected flow in the Flow tab too. */
    private void selectFlowEverywhere() {
        if (reloading || showingBin || leftShowsResults()) return;
        TreePath[] paths = snapshots.getSelectionPaths();
        if (paths == null || paths.length != 1) return;
        String flow = pickedFlow();
        int i = flows.getFlowNames().indexOf(flow);
        if (i >= 0 && i != flows.getActiveFlowIndex()) flows.switchFlow(i);
    }

    /** True while the list is rebuilt (its selection changes then are not the user's). */
    private boolean reloading;

    private void reload() {
        reloading = true;
        try {
            reloadList();
        } finally {
            reloading = false;
        }
    }

    private void reloadList() {
        Group selectedRow = selectedGroup();
        FlowSnapshot selected = selectedSnapshot();
        List<String> expandedGroups = expandedGroupKeys();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        Map<String, Map<String, List<FlowSnapshot>>> groups = new LinkedHashMap<>();
        if (!showingBin) for (String f : flows.getFlowNames()) groups.put(f, new LinkedHashMap<>());
        Map<String, FlowSnapshot> firstOfLine = new java.util.HashMap<>();
        for (FlowSnapshot s : showingBin ? store.getDeleted() : store.getSnapshots()) {
            String key = FlowDebugStore.lineKey(s);
            firstOfLine.putIfAbsent(key, s);
            groups.computeIfAbsent(s.flowName.isEmpty() ? "(no flow)" : s.flowName, k -> new LinkedHashMap<>())
                    .computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        String active = flows.getActiveFlowName();
        groups.forEach((flow, lines) -> {
            DefaultMutableTreeNode f = new DefaultMutableTreeNode(new Group(store.title(flow), true, flow, null, flow.equals(active)));
            lines.forEach((key, list) -> {
                FlowSnapshot s = firstOfLine.get(key);
                String where = s.where() + (s.method().isEmpty() ? "" : "  " + s.method());
                DefaultMutableTreeNode l = new DefaultMutableTreeNode(new Group(where, false, flow, key, false));
                for (FlowSnapshot x : list) l.add(new DefaultMutableTreeNode(x));
                f.add(l);
            });
            root.add(f);
        });
        snapshots.setModel(new DefaultTreeModel(root));
        TreeUtil.expandAll(snapshots);
        flowsSeen = flows.getFlowNames() + "#" + flows.getActiveFlowIndex();
        if (selected != null) {
            DefaultMutableTreeNode node = TreeUtil.findNodeWithObject(root, selected);
            if (node != null) TreeUtil.selectNode(snapshots, node);
        } else if (selectedRow != null) { // the flow (or line) row stays selected
            DefaultMutableTreeNode node = TreeUtil.findNode(root, n -> n.getUserObject() instanceof Group g
                    && g.flow() == selectedRow.flow() && g.flowName().equals(selectedRow.flowName())
                    && java.util.Objects.equals(g.lineKey(), selectedRow.lineKey()));
            if (node != null) TreeUtil.selectNode(snapshots, node);
        }
        if (shownSnapshot != null && selectedSnapshot() == null && results.getSelectedValue() == null) clearValue();
        else if (shownSnapshot != null) showValue(shownSnapshot, shownSnapshot.value, selectedValuePath());
        int n = showingBin ? store.getDeleted().size() : store.getSnapshots().size();
        status.setText(showingBin ? "Recycle Bin: " + n + (n == 1 ? " deleted value" : " deleted values") + ". Restore puts them back."
                : n == 0 ? "While debugging, right-click a variable in the Variables view → Save to TraceWeave."
                : n + (n == 1 ? " saved value" : " saved values") + ". Double-click a field to edit it.");
        if (!search.getText().isBlank()) runSearch();
        updateButtons();
    }

    /**
     * A flow, or a line of a flow, in the list.
     * @param lineKey the line's key (for its name), null for a flow
     */
    private record Group(String text, boolean flow, String flowName, @Nullable String lineKey, boolean active) {}

    /**
     * A line picked in the list (not one of its values): every value saved there, together in one tree (read-only:
     * a value of its own is edited when it is picked).
     */
    private void showLineValues() {
        Object picked = snapshots.getLastSelectedPathComponent();
        if (!(picked instanceof DefaultMutableTreeNode node) || !(node.getUserObject() instanceof Group g) || g.lineKey() == null) return;
        List<FlowSnapshot> saved = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            if (((DefaultMutableTreeNode) node.getChildAt(i)).getUserObject() instanceof FlowSnapshot x) saved.add(x);
        }
        if (saved.isEmpty()) return;
        FlowSnapshot first = saved.get(0);
        FlowValue all = new FlowValue(first.where());
        for (FlowSnapshot x : saved) {
            FlowValue v = x.value.copy();
            v.name = x.name;
            all.children.add(v);
        }
        showValue(null, all, null);
        valueTitle.setText("<html><b>" + saved.size() + (saved.size() == 1 ? " value" : " values") + "</b> at " + first.where()
                + (first.method().isEmpty() ? "" : " in " + first.method())
                + " · pick one in the list to edit it</html>");
    }

    private List<String> expandedGroupKeys() {
        return List.of(); // every group is shown open
    }

    private @Nullable Group selectedGroup() {
        TreePath p = snapshots.getSelectionPath();
        Object o = p == null ? null : ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        return o instanceof Group g ? g : null;
    }

    // ---------- flows (the Flow tab's) and line names ----------

    /** The flow picked in the list (a flow, one of its lines or values), else the active one. */
    private String pickedFlow() {
        Group g = selectedGroup();
        if (g != null) return g.flowName();
        FlowSnapshot s = selectedSnapshot();
        return s != null && !s.flowName.isEmpty() ? s.flowName : flows.getActiveFlowName();
    }

    /** The flow picker: the Flow tab's flows popup (click to switch, drag to reorder), then this flow's actions. */
    private void flowsMenu() {
        String flow = flows.getActiveFlowName();
        List<FlowMenuPopup.Item> items = new ArrayList<>();
        items.add(new FlowMenuPopup.Item("New Flow…", AllIcons.General.Add, true, this::newFlow));
        items.add(new FlowMenuPopup.Item("Rename Flow…", AllIcons.Actions.Edit, true, () -> renameFlow(flow)));
        items.add(new FlowMenuPopup.Item("Delete Flow…", FlowTheme.ICON_DELETE, true, () -> deleteFlow(flow)));
        items.add(new FlowMenuPopup.Item("Delete Flows…", FlowTheme.ICON_DELETE, true, this::deleteFlows));
        items.add(null);
        items.add(new FlowMenuPopup.Item("Export the Flow's Values…", FlowTheme.ICON_EXPORT, true, () -> exportFlow(flow)));
        items.add(new FlowMenuPopup.Item("Import Values into the Flow…", FlowTheme.ICON_IMPORT, true, () -> importInto(flow)));
        FlowMenuPopup.show(flows, flowsButton, items, store::title);
    }

    private void showFlowName() {
        flowsButton.setText(com.intellij.openapi.util.text.StringUtil.shortenTextWithEllipsis(store.title(flows.getActiveFlowName()), 24, 0) + "  ▾");
    }

    /** Settings: where the list and the value go, and (for saved values) what Capture Data saves. */
    private void settingsMenu(@Nullable FlowButton anchor) {
        Map<String, List<FlowSettingsPopup.Choice>> sections = new LinkedHashMap<>();
        sections.put("LAYOUT", List.of(
                new FlowSettingsPopup.Choice(FlowTheme.ICON_LAYOUT, "Auto", "Side by side, or one above the other when the window is narrow.",
                        () -> store.getLayout().equals("AUTO"), () -> setLayout("AUTO")),
                new FlowSettingsPopup.Choice(AllIcons.Actions.SplitVertically, "Side by side", null,
                        () -> store.getLayout().equals("SIDE"), () -> setLayout("SIDE")),
                new FlowSettingsPopup.Choice(AllIcons.Actions.SplitHorizontally, "Top and bottom", null,
                        () -> store.getLayout().equals("STACKED"), () -> setLayout("STACKED"))));
        if (!showingBin) {
            sections.put("CAPTURE DATA: WHEN", List.of(
                    new FlowSettingsPopup.Choice(AllIcons.Debugger.Db_set_breakpoint, "Where the debugger stops", null,
                            () -> !store.isCaptureEveryLine(), () -> store.setCaptureEveryLine(false)),
                    new FlowSettingsPopup.Choice(AllIcons.Actions.TraceInto, "Every line it runs", "Up to the next breakpoint.",
                            store::isCaptureEveryLine, () -> store.setCaptureEveryLine(true))));
            sections.put("CAPTURE DATA: WHAT", List.of(
                    new FlowSettingsPopup.Choice(AllIcons.Nodes.Variable, "All variables of the method", null,
                            () -> !store.isCaptureLineOnly(), () -> store.setCaptureLineOnly(false)),
                    new FlowSettingsPopup.Choice(AllIcons.Debugger.Frame, "Only the line's variables", null,
                            store::isCaptureLineOnly, () -> store.setCaptureLineOnly(true))));
        }
        FlowSettingsPopup.showChoices(anchor != null ? anchor : showingBin ? binSettingsButton : settingsButton, "Settings", sections, null, false);
    }

    private void newFlow() {
        String name = Messages.showInputDialog(project, "Name of the new flow:", "New Flow", null, flows.uniqueName("Flow"), null);
        if (name == null || name.isBlank()) return;
        flows.newFlow(name, List.of());
        status.setText("Added the flow “" + flows.getActiveFlowName() + "”: new values go there (and lines in the Flow tab).");
    }

    private void renameFlow(String flow) {
        int index = flows.getFlowNames().indexOf(flow);
        String shown = store.title(flow);
        String name = Messages.showInputDialog(project, "New name of the flow “" + shown + "” in Debug Data (the Flow tab keeps its name):",
                "Rename Flow", null, shown, null);
        if (name == null || name.isBlank() || name.strip().equals(shown)) return;
        if (index >= 0) {
            store.setTitle(flow, name); // Debug Data's name only: the Flow tab's flow keeps its name
        } else {
            store.renameFlow(flow, name.strip()); // values of a flow the Flow tab doesn't have (e.g. imported)
        }
        reload();
        showFlowName();
    }

    private void moveFlow(String flow, int by) {
        int index = flows.getFlowNames().indexOf(flow);
        if (index < 0) return;
        flows.moveFlow(index, Math.max(0, Math.min(flows.getFlowNames().size() - 1, index + by)));
    }

    private void deleteFlow(String flow) {
        deleteFlowsNow(List.of(flow));
    }

    /** The flows selected on the left (their rows, not their lines or values). */
    private List<String> selectedFlows() {
        List<String> out = new ArrayList<>();
        if (leftShowsResults() || showingBin) return out;
        TreePath[] paths = snapshots.getSelectionPaths();
        if (paths == null) return out;
        for (TreePath p : paths) {
            if (((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof Group g && g.flow()
                    && !out.contains(g.flowName())) {
                out.add(g.flowName());
            }
        }
        return out;
    }

    /**
     * Deletes flows here and in the Flow tab together: with nothing in either, at once; else one confirmation (this
     * screen's values first, then the Flow tab's lines).
     */
    private void deleteFlowsNow(List<String> picked) {
        String one = picked.size() == 1 ? store.title(picked.get(0)) : null;
        // (values of a flow the Flow tab doesn't have, e.g. imported ones, go too)
        if (!FlowDeleting.delete(project, picked, FlowDeleting.From.DEBUG_DATA)) return;
        status.setText(one != null ? "Deleted the flow “" + one + "” (here and in the Flow tab)."
                : "Deleted " + picked.size() + " flows (here and in the Flow tab).");
    }

    /** Delete Flows: several (or all) flows at once, here and in the Flow tab. */
    private void deleteFlows() {
        List<String> picked = FlowDeleteFlows.ask(project, flows.getFlowNames(),
                f -> FlowDeleting.details(project, f, FlowDeleting.From.DEBUG_DATA), "They are deleted in the Flow tab too.");
        if (picked == null || picked.isEmpty()) return;
        deleteFlowsNow(picked);
    }

    private void exportFlow(String flow) {
        List<FlowSnapshot> values = store.getSnapshots().stream().filter(s -> s.flowName.equals(flow)).toList();
        if (values.isEmpty()) {
            status.setText("“" + flow + "” has no saved values to export yet.");
            return;
        }
        exportValues(values, flow);
    }

    private void importInto(String flow) {
        List<FlowSnapshot> added = importFile();
        if (added == null || added.isEmpty()) return;
        store.moveToFlow(added, flow);
        status.setText("Imported " + added.size() + (added.size() == 1 ? " value" : " values") + " into “" + flow + "”.");
        select(added.get(0));
    }

    /** Names the selected line (after its file, method and line, which always stay). */
    private void nameLine() {
        Group g = selectedGroup();
        if (g == null || g.lineKey() == null) return;
        String now = Messages.showInputDialog(project, "A name for " + g.text() + " (shown after it; the file, method and line stay):",
                "Name Line", null, store.getLineName(g.lineKey()), null);
        if (now == null) return;
        store.setLineName(g.lineKey(), now);
    }

    /** F2 in the list: rename the selected flow, or name the selected line. */
    private void renameSelectedGroup() {
        Group g = selectedGroup();
        if (g == null) return;
        if (g.flow()) renameFlow(g.flowName());
        else nameLine();
    }

    private com.intellij.openapi.actionSystem.ActionGroup listMenu() {
        com.intellij.openapi.actionSystem.DefaultActionGroup g = new com.intellij.openapi.actionSystem.DefaultActionGroup();
        java.util.function.BooleanSupplier flowSel = () -> selectedGroup() != null && selectedGroup().flow() && !showingBin;
        java.util.function.BooleanSupplier lineSel = () -> selectedGroup() != null && !selectedGroup().flow() && !showingBin;
        g.add(menuItem("Rename Flow…", "F2", AllIcons.Actions.Edit, () -> renameFlow(pickedFlow()), flowSel));
        g.add(menuItem("New Values Go Here", null, FlowTheme.ICON_FLOWS, () -> {
            int i = flows.getFlowNames().indexOf(pickedFlow());
            if (i >= 0) flows.switchFlow(i);
        }, flowSel));
        g.add(menuItem("Move Up", null, AllIcons.Actions.MoveUp, () -> moveFlow(pickedFlow(), -1), flowSel));
        g.add(menuItem("Move Down", null, AllIcons.Actions.MoveDown, () -> moveFlow(pickedFlow(), 1), flowSel));
        g.add(menuItem("Export Flow's Values…", null, FlowTheme.ICON_EXPORT, () -> exportFlow(pickedFlow()), flowSel));
        g.add(menuItem("Import into Flow…", null, FlowTheme.ICON_IMPORT, () -> importInto(pickedFlow()), flowSel));
        g.add(menuItem("Delete Flow…", null, FlowTheme.ICON_DELETE, () -> deleteFlow(pickedFlow()), flowSel));
        g.addSeparator();
        g.add(menuItem("Name This Line…", "F2", AllIcons.Actions.Edit, this::nameLine, lineSel));
        g.add(menuItem("Clear Line Name", null, null, () -> {
            Group s = selectedGroup();
            if (s != null && s.lineKey() != null) store.setLineName(s.lineKey(), "");
        }, () -> lineSel.getAsBoolean() && !store.getLineName(selectedGroup().lineKey()).isEmpty()));
        g.addSeparator();
        g.add(menuItem("New Flow…", null, AllIcons.General.Add, this::newFlow, () -> !showingBin));
        g.add(menuItem("Copy Values as JSON", "Ctrl+C", AllIcons.Actions.Copy, this::copySnapshots, () -> !selectedSnapshots().isEmpty()));
        g.add(menuItem("Along the Flow…", null, FlowTheme.ICON_FLOWS, this::alongTheFlow, () -> selectedSnapshot() != null && !showingBin));
        return g;
    }

    private @Nullable FlowSnapshot selectedSnapshot() {
        if (leftShowsResults()) {
            FlowValueSearch.Match m = results.getSelectedValue();
            return m == null ? null : m.snapshot();
        }
        TreePath p = snapshots.getSelectionPath();
        Object o = p == null ? null : ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        return o instanceof FlowSnapshot s ? s : null;
    }

    private List<FlowSnapshot> selectedSnapshots() {
        List<FlowSnapshot> out = new ArrayList<>();
        if (leftShowsResults()) {
            for (FlowValueSearch.Match m : results.getSelectedValuesList()) {
                if (m.snapshot() != null && !out.contains(m.snapshot())) out.add(m.snapshot());
            }
            return out;
        }
        TreePath[] paths = snapshots.getSelectionPaths();
        if (paths == null) return out;
        for (TreePath p : paths) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) p.getLastPathComponent();
            if (n.getUserObject() instanceof FlowSnapshot s) out.add(s);
            else for (DefaultMutableTreeNode leaf = n.getFirstLeaf(); leaf != null && n.isNodeDescendant(leaf); leaf = leaf.getNextLeaf()) {
                if (leaf.getUserObject() instanceof FlowSnapshot s && !out.contains(s)) out.add(s);
            }
        }
        return out;
    }

    private boolean leftShowsResults() {
        return !search.getText().isBlank();
    }

    // ---------- a value's fields ----------

    /** A field in the value tree: the value, and where it is (child indexes from the root). */
    /**
     * A field of the value tree: its value, its path (indexes as saved: edits use it), and its bookmark key
     * ("Type.field", as in Stop at Value's tree: a bookmark is the same in both).
     */
    private record Field(FlowValue value, String path, String key, boolean pinned) {}

    /** The row whose icon the mouse is on (it shows as a bookmark to click), -1 for none. */
    private int valueHover = -1;

    private void showValue(@Nullable FlowSnapshot s, FlowValue root, @Nullable String reveal) {
        // The same value again (after an edit, a delete, or a click on it): what was open and selected stays so.
        boolean same = shownRoot != null && (s != null ? s == shownSnapshot : shownSnapshot == null && root == shownRoot);
        java.util.Set<String> open = new java.util.HashSet<>(), picked = new java.util.LinkedHashSet<>();
        if (same) {
            DefaultMutableTreeNode oldTop = (DefaultMutableTreeNode) values.getModel().getRoot();
            java.util.Enumeration<TreePath> expanded = values.getExpandedDescendants(new TreePath(oldTop.getPath()));
            while (expanded != null && expanded.hasMoreElements()) {
                Object o = ((DefaultMutableTreeNode) expanded.nextElement().getLastPathComponent()).getUserObject();
                if (o instanceof Field f) open.add(f.path());
            }
            picked.addAll(selectedValuePaths());
        }
        java.awt.Rectangle seen = values.getVisibleRect();
        shownSnapshot = s;
        shownRoot = root;
        DefaultMutableTreeNode top = node(null, shown(root), "", com.example.flowtracker.model.FlowHuntStore.get(project).pinned());
        values.setModel(new DefaultTreeModel(top));
        values.expandPath(new TreePath(top.getPath()));
        if (!valueFilter.getText().isBlank()) TreeUtil.expandAll(values); // the matches show
        if (same) {
            List<TreePath> select = new ArrayList<>();
            java.util.Enumeration<javax.swing.tree.TreeNode> all = top.breadthFirstEnumeration();
            while (all.hasMoreElements()) {
                DefaultMutableTreeNode n = (DefaultMutableTreeNode) all.nextElement();
                if (!(n.getUserObject() instanceof Field f)) continue;
                if (open.contains(f.path())) values.expandPath(new TreePath(n.getPath()));
                if (picked.contains(f.path())) select.add(new TreePath(n.getPath()));
            }
            if (!select.isEmpty()) {
                values.setSelectionPaths(select.toArray(new TreePath[0]));
                reveal = null;
            }
            values.scrollRectToVisible(seen);
        }
        refreshText();
        valueTitle.setText(s == null ? "Current debug session (not saved)"
                : "<html><b>" + s.name + "</b> at " + s.where() + (s.method().isEmpty() ? "" : " in " + s.method())
                + " · saved " + time(s.savedAt) + " in " + s.flowName + (s.edits.isEmpty() ? "" : " · edited") + "</html>");
        if (reveal != null) {
            DefaultMutableTreeNode n = top;
            if (!reveal.isEmpty()) {
                // by path (a bookmarked field is shown first, but keeps its path)
                String want = "";
                for (String part : reveal.split("\\.")) {
                    want = want.isEmpty() ? part : want + "." + part;
                    DefaultMutableTreeNode next = null;
                    for (int c = 0; c < n.getChildCount(); c++) {
                        DefaultMutableTreeNode child = (DefaultMutableTreeNode) n.getChildAt(c);
                        if (child.getUserObject() instanceof Field f && f.path().equals(want)) next = child;
                    }
                    if (next == null) break;
                    n = next;
                }
            }
            TreeUtil.selectNode(values, n);
        }
        updateButtons();
    }

    /** A field and its children; bookmarked children first (each keeps its saved path). */
    private static DefaultMutableTreeNode node(@Nullable FlowValue parent, FlowValue v, String path, java.util.Set<String> pinned) {
        String key = FlowJsonTree.pinKey(parent, v.name);
        DefaultMutableTreeNode n = new DefaultMutableTreeNode(new Field(v, path, key, parent != null && pinned.contains(key)));
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < v.children.size(); i++) if (pinned.contains(FlowJsonTree.pinKey(v, v.children.get(i).name))) order.add(i);
        for (int i = 0; i < v.children.size(); i++) if (!order.contains(i)) order.add(i);
        for (int i : order) n.add(node(v, v.children.get(i), path.isEmpty() ? String.valueOf(i) : path + "." + i, pinned));
        return n;
    }

    /** True when a point of the value tree is on its row's icon (the bookmark to click); the top row has none. */
    private boolean onValueIcon(java.awt.Point p, int row) {
        java.awt.Rectangle r = values.getRowBounds(row);
        if (r == null || p.x < r.x || p.x > r.x + JBUI.scale(20) || p.y < r.y || p.y >= r.y + r.height) return false;
        TreePath path = values.getPathForRow(row);
        return path != null && path.getPathCount() > 2 && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof Field;
    }

    private void valueHover(int row) {
        if (row == valueHover) return;
        int was = valueHover;
        valueHover = row;
        for (int r : new int[]{was, row}) {
            java.awt.Rectangle b = r < 0 ? null : values.getRowBounds(r);
            if (b != null) values.repaint(b);
        }
    }

    /** Bookmarks a field (first in its parent, here and in Stop at Value), or takes the bookmark off. */
    private void toggleBookmark(Field f) {
        com.example.flowtracker.model.FlowHuntStore.get(project).togglePin(f.key());
        if (shownRoot != null) showValue(shownSnapshot, shownRoot, null);
    }

    private void clearValue() {
        shownSnapshot = null;
        shownRoot = null;
        if (textEditing) stopTextEdit();
        settingText = true;
        asText.setText("", view == View.JSON);
        settingText = false;
        asText.setEditable(false);
        values.setModel(new DefaultTreeModel(new DefaultMutableTreeNode()));
        valueTitle.setText("");
    }

    /** Every field selected in the value tree (Ctrl+click / Shift+click), in tree order. */
    private List<String> selectedValuePaths() {
        List<String> out = new ArrayList<>();
        int[] rows = values.getSelectionRows();
        if (rows == null) return out;
        java.util.Arrays.sort(rows);
        for (int r : rows) {
            TreePath p = values.getPathForRow(r);
            Object o = p == null ? null : ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
            if (o instanceof Field f) out.add(f.path());
        }
        return out;
    }

    private @Nullable String selectedValuePath() {
        TreePath p = values.getSelectionPath();
        Object o = p == null ? null : ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        return o instanceof Field f ? f.path() : null;
    }

    // ---------- JSON / text, copy and export ----------

    private void setView(View v) {
        if (textEditing) stopTextEdit();
        view = v;
        viewButtons.forEach((k, b) -> b.setSelected(k == v));
        ((CardLayout) valueCards.getLayout()).show(valueCards, v == View.TREE ? View.TREE.name() : View.JSON.name());
        refreshText();
    }

    private void refreshText() {
        if (view == View.TREE || textEditing) return;
        // a saved value can be edited right here: typing shows Apply / Cancel (not while it is sorted or filtered)
        asText.setEditable(shownSnapshot != null && !showingBin && !shaping());
        if (shownRoot == null) return;
        settingText = true;
        try {
            FlowValue v = shown(shownRoot);
            asText.setText(view == View.JSON ? FlowValueText.toJson(v) : FlowValueText.toText(v), view == View.JSON);
        } finally {
            settingText = false;
        }
    }

    /** The shown value as text: JSON in the JSON view, plain text otherwise. */
    private @Nullable String shownText() {
        if (shownRoot == null) return null;
        FlowValue v = shown(shownRoot);
        return view == View.JSON ? FlowValueText.toJson(v) : FlowValueText.toText(v);
    }

    private void copyShown() {
        String text = shownText();
        if (text == null) return;
        FlowCopy.toClipboard(text);
        status.setText("Copied " + shownRoot.name + (view == View.JSON ? " as JSON." : " as text."));
    }

    private void exportShown() {
        String text = shownText();
        if (text == null) return;
        boolean json = view == View.JSON;
        String ext = json ? "json" : "txt";
        String base = (shownSnapshot != null ? shownSnapshot.name + "-" + shownSnapshot.where() : shownRoot.name)
                .replaceAll("[^\\w.-]+", "_");
        VirtualFileWrapper target = FileChooserFactory.getInstance()
                .createSaveFileDialog(FlowCompat.saverDescriptor("Export Debug Value", "Save the value as " + (json ? "JSON" : "text"), ext), project)
                .save(com.example.flowtracker.model.FlowProjects.prefixed(exportPrefix(shownSnapshot == null ? List.of() : List.of(shownSnapshot)),
                        base + "." + ext));
        if (target == null) return;
        try {
            java.nio.file.Files.writeString(target.getFile().toPath(), text, java.nio.charset.StandardCharsets.UTF_8);
            status.setText("Exported " + shownRoot.name + " to " + target.getFile().getName() + ".");
        } catch (java.io.IOException ex) {
            Messages.showErrorDialog(project, "Couldn't write the file: " + ex.getMessage(), "Export Debug Value");
        }
    }

    private void comparePastedData() {
        FlowPasteCompareDialog.open(project);
    }

    // ---------- Capture Data ----------

    private void toggleCapture() {
        recorder.toggle();
        status.setText(recorder.isCapturing()
                ? "Capture Data is on: " + (store.isCaptureEveryLine()
                        ? "the variables of every line the debugger runs are saved (it steps on by itself)."
                        : "the variables are saved wherever the debugger stops.")
                : "Capture Data is off.");
    }

    private void chooseCaptureMode() {
        Map<String, List<FlowSettingsPopup.Choice>> sections = new LinkedHashMap<>();
        sections.put("WHEN", List.of(
                new FlowSettingsPopup.Choice(AllIcons.Debugger.Db_set_breakpoint, "Where the debugger stops",
                        "At breakpoints and at each step you take.", () -> !store.isCaptureEveryLine(),
                        () -> store.setCaptureEveryLine(false)),
                new FlowSettingsPopup.Choice(AllIcons.Actions.TraceInto, "Every line it runs",
                        "From a stop on, it steps line by line by itself up to the next breakpoint (where it stops), saving "
                                + "what changed on each line (up to " + FlowDataRecorder.MAX_STEPS + " lines a run).",
                        store::isCaptureEveryLine, () -> store.setCaptureEveryLine(true))));
        sections.put("WHAT", List.of(
                new FlowSettingsPopup.Choice(AllIcons.Nodes.Variable, "All variables of the method",
                        "Every variable (and this), each saved on its own.", () -> !store.isCaptureLineOnly(),
                        () -> store.setCaptureLineOnly(false)),
                new FlowSettingsPopup.Choice(AllIcons.Debugger.Frame, "Only the line's variables",
                        "Only the variables (and this' fields) used on the line where it is.", store::isCaptureLineOnly,
                        () -> store.setCaptureLineOnly(true))));
        FlowSettingsPopup.showChoices(capture, "Capture Data", sections,
                "Turn Capture Data on, then debug: each variable is saved on its own, grouped by line, so it can be compared.", false);
    }

    // ---------- layout and keys ----------

    private void setLayout(String layout) {
        store.setLayout(layout);
        applyLayout();
    }

    /** Above each other ("top and bottom") when chosen, or in Auto when the tab is too narrow for both side by side. */
    private void applyLayout() {
        String layout = store.getLayout();
        boolean stacked = layout.equals("STACKED") || layout.equals("AUTO") && getWidth() > 0 && getWidth() < JBUI.scale(560);
        if (split.getOrientation() != stacked) {
            split.setOrientation(stacked);
            split.setProportion(stacked ? 0.4f : 0.38f);
        }
    }

    /** Delete, Ctrl+D Compare, F2 Edit, Ctrl+F Find, in the tab. */
    private void keys() {
        int menu = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE, 0), "delete", () -> {
            if (showingBin) purgeSelected(); else deleteSelected();
        }, snapshots, results);
        key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_D, menu), "compare", this::compareSelected, this);
        key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE, 0), "removeField", this::removeSelectedField, values);
        key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_INSERT, 0), "addField", this::addFieldToSelected, values);
        key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, menu), "copyField", () -> copyField(CopyAs.VALUE), values);
        key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, menu), "copyValues", this::copySnapshots, snapshots, results);
        key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F2, 0), "renameGroup", this::renameSelectedGroup, snapshots);
        com.intellij.ui.PopupHandler.installPopupMenu(snapshots, listMenu(), "TraceWeave.DebugList");
        key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, menu), "pasteField", this::pasteIntoSelected, values);
        key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F2, 0), "edit", this::editSelected, this);
        key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F, menu), "find",
                () -> search.getTextEditor().requestFocusInWindow(), this);
    }

    /**
     * A shortcut while the focus is in a component (or anywhere in this panel when it is the panel). Registered as an
     * IDE action of that component: plain Swing key bindings never get keys the IDE's keymap uses (Delete, Ctrl+C,
     * Ctrl+V, F2, Ctrl+D, Ctrl+F...), the IDE takes them first.
     */
    private void key(javax.swing.KeyStroke stroke, String name, Runnable run, JComponent... on) {
        for (JComponent c : on) {
            com.intellij.openapi.actionSystem.AnAction action = com.intellij.openapi.project.DumbAwareAction.create(e -> run.run());
            action.registerCustomShortcutSet(new com.intellij.openapi.actionSystem.CustomShortcutSet(stroke), c, this);
        }
    }

    /** Add Expression needs a paused debug session; says so when there is none. */
    private void updateDebuggerButtons() {
        boolean paused = !project.isDisposed() && FlowDebugCapture.currentFrame(project) != null;
        addExpression.setEnabled(paused && !showingBin);
        addExpression.setToolTipText(paused ? EXPRESSION_TIP : NO_DEBUGGER_TIP);
    }

    // ---------- import / export of saved values ----------

    /** The projects saved values come from, as the start of an exported file's name (e.g. carSearch_hotelBooking). */
    private String exportPrefix(List<FlowSnapshot> list) {
        List<String> names = new ArrayList<>();
        for (FlowSnapshot s : list) names.add(com.example.flowtracker.model.FlowProjects.ofUrl(project, s.fileUrl));
        return com.example.flowtracker.model.FlowProjects.exportPrefix(project, names);
    }

    /** Export: the selected values (or all of them) to a .flowdebug.json file. */
    private void exportData() {
        List<FlowSnapshot> list = selectedSnapshots();
        if (list.isEmpty()) list = store.getSnapshots();
        if (list.isEmpty()) return;
        exportValues(list, list.size() == 1 ? list.get(0).name : "debug-data");
    }

    private void exportValues(List<FlowSnapshot> list, String fileName) {
        String name = fileName.replaceAll("[^\\w.-]+", "_");
        VirtualFileWrapper target = FileChooserFactory.getInstance()
                .createSaveFileDialog(FlowCompat.saverDescriptor("Export Debug Data",
                        "Save " + list.size() + (list.size() == 1 ? " value" : " values") + " with their fields and edit history", "json"), project)
                .save(com.example.flowtracker.model.FlowProjects.prefixed(exportPrefix(list), name + FlowDebugTransfer.EXTENSION));
        if (target == null) return;
        try {
            FlowDebugTransfer.write(list, target.getFile().toPath());
            status.setText("Exported " + list.size() + (list.size() == 1 ? " value" : " values") + " to " + target.getFile().getName() + ".");
        } catch (java.io.IOException ex) {
            Messages.showErrorDialog(project, "Couldn't write the file: " + ex.getMessage(), "Export Debug Data");
        }
    }

    /** Import: the values of a .flowdebug.json file, added to the saved ones (nothing is replaced). */
    private void importData() {
        List<FlowSnapshot> added = importFile();
        if (added == null) return;
        status.setText("Imported " + added.size() + (added.size() == 1 ? " value" : " values")
                + ". They are compared and searched like your own.");
        if (!added.isEmpty()) select(added.get(0));
    }

    /** Asks for a .flowdebug.json file and adds its values (null when cancelled or unreadable). */
    private @Nullable List<FlowSnapshot> importFile() {
        com.intellij.openapi.vfs.VirtualFile file = com.intellij.openapi.fileChooser.FileChooser.chooseFile(
                com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFileDescriptor("json")
                        .withTitle("Import Debug Data").withDescription("Choose a .flowdebug.json file exported from Debug Data"),
                project, null);
        if (file == null) return null;
        try {
            List<FlowSnapshot> added = FlowDebugTransfer.read(java.nio.file.Path.of(file.getPath()), store.getSnapshots());
            store.addAll(added);
            return added;
        } catch (java.io.IOException ex) {
            Messages.showErrorDialog(project, "Couldn't import " + file.getName() + ": " + ex.getMessage(), "Import Debug Data");
            return null;
        }
    }

    /** Add Expression: evaluated where the debugger is paused, then saved like a right-clicked variable. */
    private void addExpression() {
        if (FlowDebugCapture.currentFrame(project) == null) {
            updateDebuggerButtons();
            status.setText(NO_DEBUGGER_TIP + ".");
            return;
        }
        String expression = Messages.showInputDialog(project, "Expression to evaluate and save (e.g. request.getSession() or order.items):",
                "Add Expression", null);
        if (expression != null && !expression.isBlank()) FlowDebugCapture.evaluateAndSave(project, expression);
    }

    // ---------- search ----------

    private void runSearch() {
        String q = search.getText();
        if (q.isBlank()) {
            leftCards.show(left, "tree");
            resultModel.clear();
            updateButtons();
            return;
        }
        leftCards.show(left, "results");
        String error = FlowValueSearch.error(q);
        Pattern p = FlowValueSearch.pattern(q);
        resultModel.clear();
        if (error != null || p == null) {
            status.setText(error != null ? error : "");
            return;
        }
        List<FlowValueSearch.Match> found = FlowValueSearch.findInSnapshots(showingBin ? store.getDeleted() : store.getSnapshots(), p, 500);
        found.forEach(resultModel::addElement);
        status.setText(found.size() + (found.size() >= 500 ? "+ matches" : found.size() == 1 ? " match" : " matches")
                + " in the saved values" + (searchNow.isSelected() ? "; reading the current debug session…" : "."));
        if (!searchNow.isSelected()) return;
        XStackFrame frame = currentFrame();
        if (frame == null) {
            status.setText(status.getText().replace("; reading the current debug session…", ". No debug session is paused right now."));
            return;
        }
        if (nowValues != null) {
            addNowMatches(p, q);
            return;
        }
        FlowValueReader.readFrame(frame, 3, root -> {
            nowValues = root;
            if (search.getText().equals(q)) addNowMatches(p, q);
        });
    }

    private void addNowMatches(Pattern p, String q) {
        if (nowValues == null) return;
        List<FlowValueSearch.Match> now = FlowValueSearch.find(null, nowValues, p, 500, new ArrayList<>());
        now.forEach(resultModel::addElement);
        int saved = resultModel.size() - now.size();
        status.setText(saved + " in the saved values, " + now.size() + " in the current debug session"
                + (now.isEmpty() ? "" : " (marked “now”)") + ".");
    }

    private @Nullable XStackFrame currentFrame() {
        return FlowDebugCapture.currentFrame(project);
    }

    // ---------- actions ----------

    private void updateButtons() {
        FlowSnapshot s = selectedSnapshot();
        boolean saved = s != null && !showingBin;
        boolean editable = saved && !shaping();
        compare.setEnabled(!showingBin);
        capture.setSelected(recorder.isCapturing());
        capture.setText(recorder.isCapturing() ? "Capturing…" : "Capture Data");
        capture.setToolTipText(recorder.isCapturing()
                ? "Capture Data is on (" + (store.isCaptureEveryLine() ? "every line the debugger runs" : "where the debugger stops")
                        + "). Click to turn it off."
                : "Save the variables automatically while debugging: " + (store.isCaptureEveryLine()
                        ? "at every line the debugger runs" : "wherever the debugger stops") + " (▾ to choose)");
        updateDebuggerButtons();
        edit.setEnabled(editable && shownSnapshot == s && selectedValuePath() != null);
        addField.setEnabled(editable && shownSnapshot == s && !textEditing);
        resetValue.setEnabled(editable && shownSnapshot == s && FlowDebugStore.isChanged(s));
        edit.setEnabled(editable && shownSnapshot == s && !textEditing && (view != View.TREE || selectedValuePath() != null));
        edit.setToolTipText(view == View.TREE ? "Change the selected field's name or value (F2; kept in History)"
                : "Edit the whole value as " + (view == View.JSON ? "JSON" : "text") + ", then Apply (kept in History)");
        String fieldPath = selectedValuePath();
        removeField.setEnabled(editable && shownSnapshot == s && fieldPath != null && !fieldPath.isEmpty());
        newValue.setEnabled(!showingBin);
        history.setEnabled(saved && !s.edits.isEmpty());
        delete.setEnabled(!showingBin && (!selectedSnapshots().isEmpty() || !selectedFlows().isEmpty()));
        String fieldPath2 = selectedValuePath();
        delete.setToolTipText(valuesFocusedLast && fieldPath2 != null && !fieldPath2.isEmpty() && canEdit()
                ? "Remove the selected field (Delete; History → Revert puts it back)"
                : "Move the selected saved values to the Recycle Bin (Delete)");
        bin.setSelected(showingBin);
        ((CardLayout) buttons.getLayout()).show(buttons, showingBin ? "bin" : "saved");
        exportData.setEnabled(!store.getSnapshots().isEmpty());
        exportData.setToolTipText(selectedSnapshots().isEmpty()
                ? "Save all saved values (with their fields and edit history) to a .flowdebug.json file"
                : "Save the " + selectedSnapshots().size() + " selected value(s) (with their fields and edit history) to a .flowdebug.json file");
        copyValue.setEnabled(shownRoot != null);
        exportValue.setEnabled(shownRoot != null);

        restore.setEnabled(showingBin && !selectedSnapshots().isEmpty());
        purge.setEnabled(showingBin && !selectedSnapshots().isEmpty());

    }

    /**
     * Compare: the selected saved value on the left and an empty right side to paste the other value into (with
     * nothing selected, both sides are pasted). The ▾ compares with the value now or an earlier save.
     */
    private void compareSelected() {
        List<FlowSnapshot> two = selectedSnapshots();
        if (two.size() == 2 && !showingBin) {
            // Two saved values selected (Ctrl+click): compare them with each other, the older one on the left.
            FlowSnapshot a = two.get(0), b = two.get(1);
            if (a.savedAt > b.savedAt) { FlowSnapshot t = a; a = b; b = t; }
            FlowCompareDialog.show(project, "Compare " + (a.name.equals(b.name) ? a.name : a.name + " and " + b.name),
                    a, label(a), b, label(b));
            return;
        }
        FlowSnapshot s = selectedSnapshot();
        if (s == null || showingBin) {
            comparePastedData();
            return;
        }
        FlowPasteCompareDialog.open(project, s.name + " · " + s.where() + " · " + time(s.savedAt), s.value);
    }

    private static String label(FlowSnapshot s) {
        return s.name + " · " + s.where() + " · " + time(s.savedAt);
    }

    /** Compare with: the same variable now (when the debugger is paused), the same variable saved before at this line. */
    private void compareMenu() {
        FlowSnapshot s = selectedSnapshot();
        if (s == null || showingBin) {
            comparePastedData();
            return;
        }
        Map<String, List<FlowSettingsPopup.Choice>> sections = new LinkedHashMap<>();
        List<FlowSettingsPopup.Choice> now = new ArrayList<>();
        XStackFrame frame = currentFrame();
        if (frame != null) {
            now.add(new FlowSettingsPopup.Choice(AllIcons.Debugger.Frame, "Its value now", "Reads " + s.name + " in the paused debug session.",
                    () -> false, () -> FlowValueReader.readVariable(frame, s.name, v -> {
                        if (v == null) {
                            Messages.showInfoMessage(project, "The current stack frame has no variable “" + s.name + "”.", "Compare");
                            return;
                        }
                        FlowCompareDialog.show(project, "Compare " + s.name, s, s.value, "Saved " + time(s.savedAt) + " · " + s.flowName,
                                null, v, "Now");
                    })));
            sections.put("CURRENT DEBUG SESSION", now);
        }
        List<FlowSettingsPopup.Choice> others = new ArrayList<>();
        for (FlowSnapshot o : store.sameVariable(s)) {
            others.add(new FlowSettingsPopup.Choice(AllIcons.Nodes.Variable, "Saved " + time(o.savedAt) + " · " + o.flowName,
                    o.edits.isEmpty() ? null : "edited", () -> false,
                    () -> FlowCompareDialog.show(project, "Compare " + s.name + " at " + s.where(), o,
                            "Saved " + time(o.savedAt) + " · " + o.flowName, s, "Saved " + time(s.savedAt) + " · " + s.flowName)));
        }
        if (!others.isEmpty()) sections.put("SAVED BEFORE AT " + s.where().toUpperCase(), others);
        sections.put("ALONG THE FLOW", List.of(new FlowSettingsPopup.Choice(FlowTheme.ICON_FLOWS, "Along the Flow…",
                "“" + s.name + "” at each line of its flow, and what changed from line to line.", () -> false, this::alongTheFlow)));
        sections.put("OTHER DATA", List.of(new FlowSettingsPopup.Choice(AllIcons.Actions.MenuPaste, "Pasted data…",
                "The saved value on the left; paste the other one on the right.", () -> false, this::compareSelected)));
        FlowSettingsPopup.showChoices(compare, "Compare " + s.name + " With", sections, null, true);
    }

    private void editSelected() {
        FlowSnapshot s = shownSnapshot;
        if (s == null || showingBin) return;
        if (blockedByShape()) return;
        if (view != View.TREE) {
            startTextEdit();
            return;
        }
        String path = selectedValuePath();
        if (path == null) return;
        FlowValue v = FlowDebugStore.at(s.value, path);
        if (v == null) return;
        if (FlowValueText.isMapEntry(v)) {
            // a map entry: its key and its value (the entry's own text follows them)
            int ki = indexOf(v, "key"), vi = indexOf(v, "value");
            FlowValue key = v.children.get(ki), value = v.children.get(vi);
            String[] now = fieldDialog("Edit Entry " + FlowDebugStore.label(s.value, path), "Key:", unquote(key.value),
                    value.children.isEmpty() ? value.value : FlowValueText.valueJson(value, true).strip(), true);
            if (now == null || now[0].isEmpty()) return;
            store.editValue(s, path + "." + ki, key.value.strip().startsWith("\"") ? "\"" + now[0] + "\"" : now[0]);
            setFieldValue(s, path + "." + vi, value, now[1]);
            return;
        }
        String[] now = fieldDialog("Edit " + FlowDebugStore.label(s.value, path), "Name:", v.name,
                v.children.isEmpty() ? v.value : FlowValueText.valueJson(v, true).strip(), !path.isEmpty());
        if (now == null) return;
        if (!path.isEmpty()) store.editName(s, path, now[0]);
        setFieldValue(s, path, v, now[1]);
    }

    /** A field's new value: plain text for a simple value; JSON (also for an object or list) is read into fields. */
    private void setFieldValue(FlowSnapshot s, String path, FlowValue was, String text) {
        if (was.children.isEmpty() && !looksLikeJson(text)) {
            store.editValue(s, path, text);
            return;
        }
        FlowValue current = FlowDebugStore.at(s.value, path);
        if (current == null) return;
        FlowValue merged;
        try {
            merged = FlowValueText.applyJson(current, text);
        } catch (IllegalArgumentException ex) {
            if (was.children.isEmpty()) store.editValue(s, path, text);
            else Messages.showErrorDialog(this, ex.getMessage(), "Edit");
            return;
        }
        FlowValue root = s.value.copy();
        FlowValue target = FlowDebugStore.at(root, path);
        if (target == null) return;
        target.type = merged.type.isEmpty() ? target.type : merged.type;
        target.value = merged.value;
        target.children.clear();
        target.children.addAll(merged.children);
        store.replaceValue(s, root, "edited " + FlowDebugStore.label(s.value, path) + " as JSON");
    }

    private static boolean looksLikeJson(String text) {
        String t = text.strip();
        return t.startsWith("{") && t.endsWith("}") || t.startsWith("[") && t.endsWith("]");
    }

    private static int indexOf(FlowValue v, String name) {
        for (int i = 0; i < v.children.size(); i++) if (v.children.get(i).name.equals(name)) return i;
        return -1;
    }

    private static String unquote(String s) {
        String v = s.strip();
        return v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"") ? v.substring(1, v.length() - 1) : v;
    }

    // ---------- editing as JSON or text ----------

    private void startTextEdit() {
        if (shownSnapshot == null || showingBin || view == View.TREE || shaping()) return;
        textEditing = true;
        asText.setEditable(true);
        if (!asText.hasFocus()) asText.requestFocus();
        textHint.setText(view == View.JSON
                ? "Editing as JSON: maps stay maps, unchanged values keep what the debugger showed."
                : "Editing as text: name = value  {type}, four spaces deeper for each level.");
        textBar.setVisible(true);
        updateButtons();
    }

    private void stopTextEdit() {
        textEditing = false;
        textBar.setVisible(false);
        refreshText();
        updateButtons();
    }

    private void applyTextEdit() {
        FlowSnapshot s = shownSnapshot;
        if (s == null) return;
        FlowValue now;
        try {
            now = view == View.JSON ? FlowValueText.applyJson(s.value, asText.getText()) : FlowValueText.fromText(asText.getText());
        } catch (IllegalArgumentException ex) {
            Messages.showErrorDialog(this, ex.getMessage() + "\n\nFix it and Apply again, or Cancel.",
                    view == View.JSON ? "Edit as JSON" : "Edit as Text");
            return;
        }
        String how = view == View.JSON ? "edited as JSON" : "edited as text";
        textEditing = false;
        textBar.setVisible(false);
        store.replaceValue(s, now, how);
        status.setText("Saved the edited data (" + how + "). History → Revert undoes it; Reset goes back to the saved data.");
        refreshText();
        updateButtons();
    }

    private void resetShown() {
        FlowSnapshot s = shownSnapshot;
        if (s == null || showingBin || !FlowDebugStore.isChanged(s)) return;
        if (textEditing) stopTextEdit();
        store.reset(s);
        status.setText("Back to the data as it was saved (History → Revert undoes the reset).");
    }

    // ---------- adding, removing and copying fields ----------

    /** A small form with a name and a value; null when cancelled. */
    private @Nullable String[] fieldDialog(String title, String name, String value, boolean nameEditable) {
        return fieldDialog(title, "Name:", name, value, nameEditable);
    }

    private @Nullable String[] fieldDialog(String title, String nameLabel, String name, String value, boolean nameEditable) {
        com.intellij.ui.components.JBTextField nameField = new com.intellij.ui.components.JBTextField(name, 30);
        nameField.setEditable(nameEditable);
        JBTextArea valueArea = new JBTextArea(value, 6, 40);
        valueArea.setFont(JBUI.Fonts.create(java.awt.Font.MONOSPACED, JBUI.Fonts.label().getSize()));
        JPanel form = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        JPanel top = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        top.add(new JBLabel(nameLabel), BorderLayout.WEST);
        top.add(nameField, BorderLayout.CENTER);
        form.add(top, BorderLayout.NORTH);
        JPanel mid = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        mid.add(new JBLabel("Value (text, or JSON for an object or a list):"), BorderLayout.NORTH);
        mid.add(FlowScroll.wheel(new JBScrollPane(valueArea)), BorderLayout.CENTER);
        form.add(mid, BorderLayout.CENTER);
        com.intellij.openapi.ui.DialogBuilder b = new com.intellij.openapi.ui.DialogBuilder(project);
        b.setTitle(title);
        b.setCenterPanel(form);
        b.setPreferredFocusComponent(nameEditable && name.isEmpty() ? nameField : valueArea);
        if (b.show() != DialogWrapper.OK_EXIT_CODE) return null;
        return new String[]{nameField.getText().strip(), valueArea.getText()};
    }

    private void addFieldToSelected() {
        FlowSnapshot s = shownSnapshot;
        if (s == null || showingBin) return;
        if (blockedByShape()) return;
        String parent = selectedValuePath();
        if (parent == null) parent = "";
        FlowValue target = FlowDebugStore.at(s.value, parent);
        // a map entry or a simple value: add next to it, in what holds it
        while (target != null && !parent.isEmpty() && (FlowValueText.isMapEntry(target) || target.children.isEmpty()
                && !FlowValueText.isMap(target) && !FlowValueText.isList(target))) {
            int dot = parent.lastIndexOf('.');
            parent = dot < 0 ? "" : parent.substring(0, dot);
            target = FlowDebugStore.at(s.value, parent);
        }
        if (target == null) return;
        boolean map = FlowValueText.isMap(target), list = !map && FlowValueText.isList(target);
        String where = FlowDebugStore.label(s.value, parent);
        String[] f = map ? fieldDialog("Add Entry to " + where, "Key:", "", "", true)
                : list ? fieldDialog("Add Element to " + where, "Index:", nextIndex(target), "", false)
                : objectFieldDialog("Add Field to " + where, target);
        if (f == null || !list && f[0].isEmpty()) return;
        if (map) {
            for (FlowValue c : target.children) {
                if (FlowValueText.isMapEntry(c) && unquote(c.children.get(indexOf(c, "key")).value).equals(unquote(f[0]))) {
                    Messages.showErrorDialog(this, "The key “" + unquote(f[0]) + "” is already in " + target.name
                            + ". Edit its entry instead (F2).", "Add Entry");
                    return;
                }
            }
        }
        FlowValue value = FlowValueText.fromJson(map ? "value" : list ? nextIndex(target) : f[0], f[1]);
        if (value == null) {
            value = new FlowValue(map ? "value" : list ? nextIndex(target) : f[0]);
            value.value = f[1].strip();
        }
        if (!map && !list && f.length > 2 && !f[2].isEmpty()) value.type = f[2]; // the field's type in the code
        if (list) {
            for (FlowValue c : target.children) if (value.type.isEmpty() && c.children.isEmpty() == value.children.isEmpty()) value.type = c.type;
        }
        FlowValueText.typeNew(value);
        FlowValue add = value;
        if (map) {
            boolean quoted = target.children.stream().filter(FlowValueText::isMapEntry)
                    .anyMatch(c -> c.children.get(indexOf(c, "key")).value.strip().startsWith("\""))
                    || target.children.isEmpty() && !f[0].matches("-?\\d+");
            FlowValue key = new FlowValue("key");
            key.value = quoted ? "\"" + f[0] + "\"" : f[0];
            add = new FlowValue(String.valueOf(target.children.size()));
            value.type = "";
            FlowValueText.entriesLike(target, add, key, value); // e.g. HashMap$Node, String, Integer like the others
            add.value = key.value + " -> " + value.value;
            add.children.add(key);
            add.children.add(value);
        }
        String path = store.addField(s, parent, add);
        status.setText("Added " + (map ? f[0] : add.name) + " to " + where + " (History → Revert removes it again).");
        selectField(path);
    }

    /**
     * Add Field to an object: its class's fields from the code (its own, then the ones it inherits, marked with the
     * class they come from; the ones already saved marked too), or any name. Returns name, value and type.
     */
    private @Nullable String[] objectFieldDialog(String title, FlowValue target) {
        String type = (target.type.isEmpty() ? target.value.replaceAll("^\\{([\\w.$]+)@\\d+}$", "$1") : target.type)
                .replaceAll("@\\d+", "");
        List<com.example.flowtracker.code.FlowClassFields.Field> fields = com.example.flowtracker.code.FlowClassFields.of(project, type);
        java.util.Set<String> there = new java.util.HashSet<>();
        for (FlowValue c : target.children) there.add(c.name);
        com.intellij.ui.components.JBTextField nameField = new com.intellij.ui.components.JBTextField(30);
        JBTextArea valueArea = new JBTextArea("", 5, 40);
        valueArea.setFont(JBUI.Fonts.create(java.awt.Font.MONOSPACED, JBUI.Fonts.label().getSize()));
        JBLabel typeLabel = new JBLabel(" ");
        typeLabel.setForeground(UIUtil.getContextHelpForeground());
        String[] chosenType = {""};
        JBList<com.example.flowtracker.code.FlowClassFields.Field> list = new JBList<>(fields);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends com.example.flowtracker.code.FlowClassFields.Field> l,
                                                 com.example.flowtracker.code.FlowClassFields.Field fl, int index, boolean selected, boolean focus) {
                setIcon(fl.inherited() ? AllIcons.Hierarchy.Supertypes : AllIcons.Nodes.Field);
                boolean saved = there.contains(fl.name());
                append(fl.name(), saved ? SimpleTextAttributes.GRAYED_ATTRIBUTES : SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                append("  " + fl.type(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                if (fl.isStatic()) append("  static", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                append(fl.inherited() ? "   inherited from " + fl.owner() : "   own (" + fl.owner() + ")",
                        fl.inherited() ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, JBColor.namedColor("Label.infoForeground", JBColor.GRAY))
                                : new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, EDITED));
                if (saved) append("   already saved", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
            }
        });
        list.addListSelectionListener(e -> {
            com.example.flowtracker.code.FlowClassFields.Field fl = list.getSelectedValue();
            if (fl == null) return;
            if (there.contains(fl.name())) {
                typeLabel.setText(fl.name() + " is already saved: pick another field, or edit it (F2) in the tree.");
                return;
            }
            nameField.setText(fl.name());
            chosenType[0] = fl.type();
            typeLabel.setText("Type: " + fl.type() + (fl.inherited() ? " (inherited from " + fl.owner() + ")" : ""));
            valueArea.requestFocusInWindow();
        });
        nameField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override protected void textChanged(@NotNull DocumentEvent e) {
                com.example.flowtracker.code.FlowClassFields.Field fl = list.getSelectedValue();
                if (fl != null && !fl.name().equals(nameField.getText().strip())) {
                    list.clearSelection();
                    chosenType[0] = "";
                    typeLabel.setText(" ");
                }
            }
        });

        JPanel form = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        if (!fields.isEmpty()) {
            JPanel pick = new JPanel(new BorderLayout(0, JBUI.scale(4)));
            pick.add(new JBLabel("Fields of " + type + " in the code (own first, then inherited); pick one or type any name:"),
                    BorderLayout.NORTH);
            JBScrollPane scroll = FlowScroll.wheel(new JBScrollPane(list));
            scroll.setPreferredSize(new Dimension(JBUI.scale(520), JBUI.scale(170)));
            pick.add(scroll, BorderLayout.CENTER);
            form.add(pick, BorderLayout.NORTH);
        }
        JPanel fieldsPanel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        JPanel nameRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        nameRow.add(new JBLabel("Name:"), BorderLayout.WEST);
        nameRow.add(nameField, BorderLayout.CENTER);
        nameRow.add(typeLabel, BorderLayout.SOUTH);
        fieldsPanel.add(nameRow, BorderLayout.NORTH);
        JPanel valueRow = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        valueRow.add(new JBLabel("Value (text, or JSON for an object or a list, e.g. {\"city\": \"Goa\"}):"), BorderLayout.NORTH);
        valueRow.add(FlowScroll.wheel(new JBScrollPane(valueArea)), BorderLayout.CENTER);
        fieldsPanel.add(valueRow, BorderLayout.CENTER);
        form.add(fieldsPanel, BorderLayout.CENTER);
        com.intellij.openapi.ui.DialogBuilder b = new com.intellij.openapi.ui.DialogBuilder(project);
        b.setTitle(title);
        b.setCenterPanel(form);
        b.setPreferredFocusComponent(fields.isEmpty() ? nameField : list);
        // a field that is already there can't be added again (edit it instead)
        b.setOkOperation(() -> {
            String name = nameField.getText().strip();
            if (name.isEmpty()) {
                Messages.showErrorDialog(form, "Give the field a name (or pick one from the list).", title);
            } else if (there.contains(name)) {
                Messages.showErrorDialog(form, "“" + name + "” is already in " + target.name + ". Edit it instead (F2).", title);
            } else {
                b.getDialogWrapper().close(DialogWrapper.OK_EXIT_CODE);
            }
        });
        b.addOkAction();
        b.addCancelAction();
        if (b.show() != DialogWrapper.OK_EXIT_CODE) return null;
        return new String[]{nameField.getText().strip(), valueArea.getText(), chosenType[0]};
    }

    private static String nextIndex(FlowValue list) {
        int n = list.children.size();
        boolean brackets = !list.children.isEmpty() && list.children.get(0).name.startsWith("[");
        return brackets ? "[" + n + "]" : String.valueOf(n);
    }

    /**
     * Removes the selected fields (several with Ctrl+click), each kept in History; a field inside another selected one
     * goes with it. The last ones go first, so the others' places stay right.
     */
    private void removeSelectedField() {
        FlowSnapshot s = shownSnapshot;
        if (s == null || showingBin) return;
        if (blockedByShape()) return;
        List<String> paths = new ArrayList<>();
        for (String p : selectedValuePaths()) {
            if (p.isEmpty()) continue;
            boolean inside = false;
            for (String q : selectedValuePaths()) inside |= !q.isEmpty() && !q.equals(p) && p.startsWith(q + ".");
            if (!inside) paths.add(p);
        }
        if (paths.isEmpty()) return;
        paths.sort((a, b) -> {
            String[] x = a.split("\\."), y = b.split("\\.");
            for (int i = 0; i < Math.min(x.length, y.length); i++) {
                int c = Integer.compare(Integer.parseInt(y[i]), Integer.parseInt(x[i]));
                if (c != 0) return c;
            }
            return Integer.compare(y.length, x.length);
        });
        String first = FlowDebugStore.label(s.value, paths.get(paths.size() - 1));
        values.clearSelection();
        for (String p : paths) store.removeField(s, p);
        status.setText((paths.size() == 1 ? "Removed " + first : "Removed " + paths.size() + " fields")
                + " (History → Revert puts each back).");
    }

    private void selectField(String path) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) values.getModel().getRoot();
        java.util.Enumeration<javax.swing.tree.TreeNode> all = root.depthFirstEnumeration();
        while (all.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) all.nextElement();
            if (n.getUserObject() instanceof Field f && f.path().equals(path)) {
                TreeUtil.selectNode(values, n);
                return;
            }
        }
    }

    private enum CopyAs { VALUE, LINE, JSON, CHILDREN }

    /** Copies the selected field: its value, "name = value", or the field (with what is in it) as JSON. */
    private void copyField(CopyAs as) {
        if (shownRoot == null) return;
        List<FlowValue> picked = new ArrayList<>();
        for (String p : selectedValuePaths()) {
            FlowValue v = FlowDebugStore.at(shownRoot, p);
            if (v != null) picked.add(v);
        }
        if (picked.isEmpty()) return;
        StringBuilder text = new StringBuilder();
        if (as == CopyAs.JSON && picked.size() > 1) {
            // several fields: one JSON object with each of them
            FlowValue all = new FlowValue("selection");
            for (FlowValue v : picked) all.children.add(v.copy());
            text.append(FlowValueText.valueJson(all, false));
        } else {
            for (FlowValue v : picked) {
                if (text.length() > 0) text.append('\n');
                text.append(switch (as) {
                    case VALUE -> v.children.isEmpty() ? v.value : FlowValueText.valueJson(v, true).strip();
                    case LINE -> v.name + " = " + v.value;
                    case JSON -> FlowValueText.toJson(v, true).strip();
                    case CHILDREN -> FlowValueText.valueJson(v, true).strip(); // only what is inside it
                });
            }
        }
        FlowCopy.toClipboard(text.toString());
        status.setText("Copied " + (picked.size() == 1 ? picked.get(0).name : picked.size() + " fields")
                + (as == CopyAs.JSON ? " as JSON." : "."));
    }

    /**
     * Paste into the selected field: JSON adds its fields (or entries, or list elements) to it and updates the ones it
     * already has, the rest stays (so "Copy as JSON" of a field, pasted on its parent, updates that field; "Copy
     * Children as JSON" pasted on a field updates what is inside it). Plain text becomes a simple field's value.
     * Kept in History.
     */
    private void pasteIntoSelected() {
        FlowSnapshot s = shownSnapshot;
        String path = selectedValuePath();
        if (s == null || path == null || showingBin) return;
        if (blockedByShape()) return;
        String text;
        try {
            Object data = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            text = data instanceof String t ? t.strip() : "";
        } catch (Exception ex) {
            text = "";
        }
        if (text.isEmpty()) {
            status.setText("Nothing to paste: copy a value or some JSON first.");
            return;
        }
        FlowValue target = FlowDebugStore.at(s.value, path);
        if (target == null) return;
        String label = FlowDebugStore.label(s.value, path);
        com.google.gson.JsonElement pasted;
        try {
            pasted = looksLikeJson(text) ? com.google.gson.JsonParser.parseString(text) : null;
        } catch (RuntimeException ex) {
            pasted = null;
        }
        if (pasted == null) {
            if (!target.children.isEmpty()) {
                Messages.showErrorDialog(this, label + " holds fields: paste JSON into it (e.g. {\"goa\": 10}), or select one of "
                        + "its fields to paste a value.", "Paste");
                return;
            }
            store.editValue(s, path, text);
            status.setText("Pasted into " + label + " (History → Revert undoes it).");
            return;
        }
        // what is there now, with the pasted fields added or put in place
        com.google.gson.JsonElement now;
        try {
            now = target.children.isEmpty() ? null : com.google.gson.JsonParser.parseString(FlowValueText.valueJson(target, false));
        } catch (RuntimeException ex) {
            now = null;
        }
        com.google.gson.JsonElement merged = pasted;
        if (now != null && now.isJsonObject() && pasted.isJsonObject()) {
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : pasted.getAsJsonObject().entrySet()) {
                now.getAsJsonObject().add(e.getKey(), e.getValue());
            }
            merged = now;
        } else if (now != null && now.isJsonArray() && pasted.isJsonArray()) {
            now.getAsJsonArray().addAll(pasted.getAsJsonArray());
            merged = now;
        } else if (now != null && now.isJsonArray() && !pasted.isJsonArray()) {
            now.getAsJsonArray().add(pasted);
            merged = now;
        }
        FlowValue result;
        try {
            result = FlowValueText.applyJson(target, merged.toString());
        } catch (IllegalArgumentException ex) {
            Messages.showErrorDialog(this, ex.getMessage(), "Paste");
            return;
        }
        FlowValue root = s.value.copy();
        FlowValue place = FlowDebugStore.at(root, path);
        if (place == null) return;
        place.value = result.value;
        if (place.type.isEmpty()) place.type = result.type;
        place.children.clear();
        place.children.addAll(result.children);
        store.replaceValue(s, root, "pasted into " + label);
        status.setText("Pasted into " + label + " (History → Revert undoes it).");
    }

    /** Ctrl+C on the list: the selected saved values (several with Ctrl+click) as JSON, each under its name. */
    private void copySnapshots() {
        List<FlowSnapshot> list = selectedSnapshots();
        if (list.isEmpty()) return;
        FlowValue all = new FlowValue("values");
        for (FlowSnapshot s : list) {
            FlowValue v = s.value.copy();
            v.name = s.name + " @ " + s.where();
            all.children.add(v);
        }
        FlowCopy.toClipboard(list.size() == 1 ? FlowValueText.toJson(list.get(0).value, true) : FlowValueText.valueJson(all, false));
        status.setText("Copied " + (list.size() == 1 ? list.get(0).name : list.size() + " values") + " as JSON.");
    }

    private com.intellij.openapi.actionSystem.ActionGroup valueMenu() {
        com.intellij.openapi.actionSystem.DefaultActionGroup g = new com.intellij.openapi.actionSystem.DefaultActionGroup();
        g.add(menuItem("Copy Value", "Ctrl+C", AllIcons.Actions.Copy, () -> copyField(CopyAs.VALUE), () -> selectedValuePath() != null));
        g.add(menuItem("Copy “name = value”", null, null, () -> copyField(CopyAs.LINE), () -> selectedValuePath() != null));
        g.add(menuItem("Copy as JSON", null, null, () -> copyField(CopyAs.JSON), () -> selectedValuePath() != null));
        g.add(menuItem("Copy Children as JSON", null, null, () -> copyField(CopyAs.CHILDREN), () -> selectedValuePath() != null));
        g.add(menuItem("Paste", "Ctrl+V", AllIcons.Actions.MenuPaste, this::pasteIntoSelected, () -> canEdit() && selectedValuePath() != null));
        g.addSeparator();
        g.add(menuItem("Edit…", "F2", AllIcons.Actions.Edit, this::editSelected, () -> canEdit() && selectedValuePath() != null));
        g.add(menuItem("Add Field…", "Insert", AllIcons.General.Add, this::addFieldToSelected, this::canEdit));
        g.add(menuItem("Remove Field", "Delete", AllIcons.General.Remove, this::removeSelectedField, () -> {
            String p = selectedValuePath();
            return canEdit() && p != null && !p.isEmpty();
        }));
        return g;
    }

    private boolean canEdit() {
        return shownSnapshot != null && !showingBin && !shaping();
    }

    // ---------- sort, filter, expand and collapse the value (only how it is shown) ----------

    /** True when the value is shown sorted or filtered (then it can't be edited: clear them first). */
    private boolean shaping() {
        return !com.example.flowtracker.model.FlowValueShape.plain(sort, valueFilter.getText());
    }

    /** The value as it is shown: sorted and filtered (a copy), or the value itself. */
    private FlowValue shown(FlowValue root) {
        FlowValue v = com.example.flowtracker.model.FlowValueShape.hideInternals()
                ? com.example.flowtracker.model.FlowValueShape.withoutInternals(root) : root;
        return shaping() ? com.example.flowtracker.model.FlowValueShape.apply(v, sort, valueFilter.getText()) : v;
    }

    private void setSort(com.example.flowtracker.model.FlowValueShape.Sort s) {
        sort = s;
        sortButtons.forEach((k, b) -> b.setSelected(k == s));
        reshape();
    }

    /** The value again, sorted and filtered as now chosen. */
    private void reshape() {
        if (textEditing) stopTextEdit();
        if (shownRoot != null) showValue(shownSnapshot, shownRoot, null);
        if (shaping()) status.setText("Shown " + (sort != com.example.flowtracker.model.FlowValueShape.Sort.AS_SAVED ? "sorted " + sort.label : "")
                + (valueFilter.getText().isBlank() ? "" : (sort != com.example.flowtracker.model.FlowValueShape.Sort.AS_SAVED ? ", " : "")
                + "filtered by “" + valueFilter.getText().strip() + "”") + ". To edit the value, show it as saved (no filter, As Saved).");
        updateButtons();
    }

    /** True (and says why) when the value is sorted or filtered and so can't be edited. */
    private boolean blockedByShape() {
        if (!shaping()) return false;
        status.setText("The value is sorted or filtered: show it As Saved with no filter to edit it.");
        return true;
    }

    private void expandValue() {
        if (view == View.TREE) TreeUtil.expandAll(values);
        else asText.expandAll();
    }

    private void collapseValue() {
        if (view == View.TREE) {
            TreeUtil.collapseAll(values, 1);
        } else {
            asText.collapseAll();
        }
    }

    /** Ctrl+F in the value pane: IntelliJ's find bar in the JSON / text, the filter in the tree. */
    private void findInValue() {
        if (view != View.TREE) {
            asText.find();
        } else {
            valueFilter.requestFocusInWindow();
            valueFilter.selectText();
        }
    }

    private static com.intellij.openapi.actionSystem.AnAction menuItem(String text, @Nullable String keys, @Nullable javax.swing.Icon icon,
                                                                        Runnable run, java.util.function.BooleanSupplier enabled) {
        return new com.intellij.openapi.project.DumbAwareAction(keys == null ? text : text + "  (" + keys + ")", null, icon) {
            @Override public void actionPerformed(@NotNull com.intellij.openapi.actionSystem.AnActionEvent e) { run.run(); }
            @Override public void update(@NotNull com.intellij.openapi.actionSystem.AnActionEvent e) {
                e.getPresentation().setEnabled(enabled.getAsBoolean());
            }
        };
    }

    /** New Value: a saved value made by hand (JSON is read into fields; other text is kept as its value). */
    private void newValue() {
        String[] f = fieldDialog("New Saved Value", "", "", true);
        if (f == null || f[0].isEmpty()) return;
        FlowSnapshot s = new FlowSnapshot();
        s.name = f[0];
        s.flowName = com.example.flowtracker.model.FlowStore.get(project).getActiveFlowName();
        FlowValue v = FlowValueText.fromJson(f[0], f[1]);
        if (v == null) {
            v = new FlowValue(f[0]);
            v.value = f[1].strip();
        }
        v.type = "added by hand";
        s.value = v;
        store.add(s);
        select(s);
        status.setText("Added " + s.name + ". Add, edit or remove its fields on the right.");
    }

    private void showHistory() {
        FlowSnapshot s = selectedSnapshot();
        if (s == null || s.edits.isEmpty()) return;
        new HistoryDialog(s).show();
    }

    /**
     * Delete: the field selected on the right when that is where you clicked last (it can be reverted from History);
     * otherwise the saved values selected on the left. A whole line or flow (all its values) is deleted only after
     * asking.
     */
    private void deleteSelected() {
        String field = selectedValuePath();
        if (valuesFocusedLast && field != null && !field.isEmpty() && canEdit()) {
            removeSelectedField();
            return;
        }
        List<String> flowsPicked = selectedFlows();
        if (!flowsPicked.isEmpty()) { // flows selected: the flows go (an empty one without asking)
            deleteFlowsNow(flowsPicked);
            return;
        }
        List<FlowSnapshot> list = selectedSnapshots();
        if (list.isEmpty()) return;
        String group = selectedGroupName();
        if (group != null && Messages.showYesNoDialog(project, "Delete all " + list.size() + (list.size() == 1 ? " value" : " values")
                + " of “" + group + "”? They go to the Recycle Bin.", "Delete", "Delete All", "Cancel", Messages.getQuestionIcon())
                != Messages.YES) {
            return;
        }
        store.delete(list);
        status.setText("Moved " + list.size() + (list.size() == 1 ? " value" : " values") + " to the Recycle Bin.");
    }

    /** The line or flow selected on the left (its values are all deleted together), or null when values are selected. */
    private @Nullable String selectedGroupName() {
        if (leftShowsResults()) return null;
        TreePath[] paths = snapshots.getSelectionPaths();
        if (paths == null) return null;
        for (TreePath p : paths) {
            Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
            if (o instanceof Group g) return g.text();
        }
        return null;
    }

    /** True when the value tree (on the right) was clicked after the list (on the left). */
    private boolean valuesFocusedLast;

    private void toggleBin() {
        showingBin = !showingBin;
        clearValue();
        reload();
    }

    private void restoreSelected() {
        List<FlowSnapshot> list = selectedSnapshots();
        store.restore(list);
    }

    private void purgeSelected() {
        List<FlowSnapshot> list = selectedSnapshots();
        if (list.isEmpty()) return;
        if (Messages.showYesNoDialog(project, "Delete " + list.size() + (list.size() == 1 ? " value" : " values")
                + " for good? This can't be undone.", "Recycle Bin", Messages.getWarningIcon()) == Messages.YES) {
            store.purge(list);
        }
    }

    /** The selected value's variable at each line of its flow, in the flow's order (see {@link FlowValueTrail}). */
    private void alongTheFlow() {
        FlowSnapshot s = selectedSnapshot();
        if (s == null) return;
        List<com.example.flowtracker.model.FlowValueTrail.Stop> stops =
                com.example.flowtracker.model.FlowValueTrail.along(store.getSnapshots(), s, flows.getEntriesOf(s.flowName));
        if (stops.size() < 2) {
            status.setText("“" + s.name + "” is saved at one line of “" + store.title(s.flowName) + "” only: save it at other lines "
                    + "(or turn on Capture Data) to see how it changes along the flow.");
            return;
        }
        FlowTrailDialog.show(project, s.name, "“" + store.title(s.flowName) + "”", stops);
    }

    // ---------- Stop at Value ----------

    /** Starts (or stops) Stop at Value; its history is in the Stop at Value tab. */
    private void stopAtValue() {
        FlowHuntPanel.startOrStop(project);
        showHunt();
    }

    /** The Stop at Value button follows the hunt. */
    private void showHunt() {
        com.example.flowtracker.debug.FlowValueHunt hunt = com.example.flowtracker.debug.FlowValueHunt.get(project);
        java.util.List<com.example.flowtracker.model.FlowHuntStore.Hunt> looking = hunt.runningHunts();
        boolean on = !looking.isEmpty();
        stopAtValue.setSelected(on);
        stopAtValue.setText(!on ? "Stop at Value…" : looking.size() == 1
                ? "Hunting “" + com.intellij.openapi.util.text.StringUtil.shortenTextWithEllipsis(looking.get(0).name, 14, 0) + "”"
                : "Hunting " + looking.size());
        stopAtValue.setToolTipText(on ? "Looking: " + String.join(", ", looking.stream().map(h -> h.name).toList())
                + ". Click to stop them all; the Stop at Value tab has every stop."
                : "Stop the debugger where a value shows up: in any thread, API answer or project, at any stop or line");
    }

    /** Debug Data with a line's saved values selected (from the Flow tab: Show Saved Values). */
    static void showLineOf(Project project, String fileUrl, int line) {
        com.intellij.openapi.wm.ToolWindow tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("TraceWeave");
        if (tw == null) return;
        for (com.intellij.ui.content.Content c : tw.getContentManager().getContents()) {
            if (c.getComponent() instanceof FlowDebugPanel panel) {
                tw.getContentManager().setSelectedContent(c);
                tw.activate(() -> panel.showLine(fileUrl, line));
                return;
            }
        }
    }

    /** Selects a line's saved values in the list (leaving the Recycle Bin and the search results). */
    private void showLine(String fileUrl, int line) {
        if (showingBin) toggleBin();
        if (!search.getText().isBlank()) search.setText("");
        reload();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) snapshots.getModel().getRoot();
        DefaultMutableTreeNode node = TreeUtil.findNode(root, n -> n.getUserObject() instanceof FlowSnapshot s
                && s.line == line && s.fileUrl.equals(fileUrl));
        if (node == null) {
            status.setText("No saved values at that line any more.");
            return;
        }
        DefaultMutableTreeNode lineNode = node.getParent() instanceof DefaultMutableTreeNode p ? p : node;
        TreeUtil.selectNode(snapshots, lineNode);
        snapshots.scrollPathToVisible(new TreePath(lineNode.getPath()));
        snapshots.requestFocusInWindow();
    }

    static String time(long millis) {
        return new SimpleDateFormat("dd MMM HH:mm:ss").format(new Date(millis));
    }

    @Override
    public void dispose() {
        com.example.flowtracker.model.FlowLook.get().removeListener(themeChanged);
        flows.removeRenameListener(flowRenamed);
        com.example.flowtracker.debug.FlowValueHunt.get(project).removeListener(huntListener);
        flows.removeListener(flowsChanged);
        store.removeListener(storeListener);
        recorder.removeListener(recorderListener);
        debuggerTimer.stop();
    }

    // ---------- renderers ----------

    private final class SnapshotRenderer extends ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
            Object o = ((DefaultMutableTreeNode) value).getUserObject();
            if (o instanceof Group g) {
                setIcon(g.flow() ? FlowTheme.ICON_FLOWS : AllIcons.Debugger.Db_set_breakpoint);
                append(g.text(), g.flow() ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
                if (g.flow()) {
                    if (((DefaultMutableTreeNode) value).getChildCount() == 0) append("   no values yet", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                    if (g.active() && !showingBin) append("   ● new values go here", new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, EDITED));
                } else if (g.lineKey() != null) {
                    String name = store.getLineName(g.lineKey());
                    if (!name.isEmpty()) append("  —  " + name, new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, EDITED));
                }
            } else if (o instanceof FlowSnapshot s) {
                setIcon(AllIcons.Nodes.Variable);
                append(s.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                if (!s.value.type.isEmpty()) append("  " + s.value.type, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append("  " + time(showingBin ? s.deletedAt : s.savedAt), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                if (!s.edits.isEmpty()) append("  edited", new SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, EDITED));
            }
        }
    }

    private final class ValueRenderer extends ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
            Object o = ((DefaultMutableTreeNode) value).getUserObject();
            if (!(o instanceof Field f)) return;
            String q = search.getText();
            FlowValue v = f.value();
            // bookmarked: a blue bookmark; the mouse on the icon: a bookmark to click
            setIcon(f.pinned() ? new FlowJsonTree.BookmarkIcon(true) : row == valueHover ? new FlowJsonTree.BookmarkIcon(false)
                    : v.children.isEmpty() ? AllIcons.Debugger.Value : AllIcons.Nodes.Variable);
            append(v.name, FlowValueSearch.contains(v.name, q) ? MATCH : SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            append(" = ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
            boolean edited = false;
            if (shownSnapshot != null) for (FlowValueEdit e : shownSnapshot.edits) edited |= e.path.equals(f.path());
            append(v.value, FlowValueSearch.contains(v.value, q) ? MATCH
                    : edited ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, EDITED) : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            if (edited) append("  ✎ edited", new SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, EDITED));
            if (!v.type.isEmpty()) append("  {" + v.type + "}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
        }
    }

    private static final class MatchRenderer extends ColoredListCellRenderer<FlowValueSearch.Match> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends FlowValueSearch.Match> list, FlowValueSearch.Match m,
                                             int index, boolean selected, boolean focus) {
            setIcon(m.snapshot() == null ? AllIcons.Debugger.Frame : AllIcons.Nodes.Variable);
            append(m.label(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            append(" = " + m.value().value, SimpleTextAttributes.REGULAR_ATTRIBUTES);
            if (m.snapshot() == null) append("   now", new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.BLUE));
            else append("   " + m.snapshot().where() + " · " + m.snapshot().flowName + " · " + time(m.snapshot().savedAt),
                    SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
        }
    }

    // ---------- edit history ----------

    /** The changes made to a saved value, newest first; Revert puts a value back (as a new change). */
    private final class HistoryDialog extends DialogWrapper {
        private final FlowSnapshot snapshot;
        private final JBList<FlowValueEdit> list;

        HistoryDialog(FlowSnapshot snapshot) {
            super(project, false);
            this.snapshot = snapshot;
            List<FlowValueEdit> edits = new ArrayList<>(snapshot.edits);
            java.util.Collections.reverse(edits);
            list = new JBList<>(edits);
            setTitle("History of " + snapshot.name + " (" + snapshot.where() + ")");
            setOKButtonText("Revert");
            setCancelButtonText("Close");
            init();
            list.addListSelectionListener(e -> setOKActionEnabled(list.getSelectedValue() != null));
            setOKActionEnabled(false);
        }

        @Override
        protected JComponent createCenterPanel() {
            list.setCellRenderer(new ColoredListCellRenderer<>() {
                @Override
                protected void customizeCellRenderer(@NotNull JList<? extends FlowValueEdit> l, FlowValueEdit e, int index, boolean selected, boolean focus) {
                    String kind = e.kind == null ? "" : e.kind;
                    setIcon(kind.equals("ADD") ? AllIcons.General.Add : kind.equals("REMOVE") ? AllIcons.General.Remove : AllIcons.Actions.Edit);
                    append(time(e.at) + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    append(e.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    if (kind.equals("NAME")) append("  renamed", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                    if (kind.equals("ADD")) append("  added", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                    if (kind.equals("REMOVE")) append("  removed", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                    if (kind.equals("REPLACE")) {
                        append("  " + e.after, new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, EDITED));
                        return;
                    }
                    append(":  " + (e.before.isEmpty() ? "—" : e.before), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    append("  →  ", SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    append(e.after, new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, EDITED));
                }
            });
            JPanel p = new JPanel(new BorderLayout(0, JBUI.scale(6)));
            p.add(new JBLabel("Select a change and click Revert to undo it: the old value or name back, an added field removed, a removed one back."), BorderLayout.NORTH);
            p.add(FlowScroll.wheel(new JBScrollPane(list)), BorderLayout.CENTER);
            p.setPreferredSize(new Dimension(JBUI.scale(640), JBUI.scale(320)));
            return p;
        }

        @Override
        protected void doOKAction() {
            FlowValueEdit e = list.getSelectedValue();
            if (e != null) store.revert(snapshot, e);
            super.doOKAction();
        }
    }
}
