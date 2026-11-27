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
import javax.swing.DropMode;
import javax.swing.TransferHandler;
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

    /** The saved values by flow and line: its indent a little tighter (the flows start near the card's edge). */
    private final Tree snapshots = new Tree() {
        @Override public void updateUI() {
            super.updateUI();
            if (getUI() instanceof javax.swing.plaf.basic.BasicTreeUI ui) {
                ui.setLeftChildIndent(JBUI.scale(4));
                ui.setRightChildIndent(JBUI.scale(10));
            }
        }
    };
    private final DefaultListModel<FlowValueSearch.Match> resultModel = new DefaultListModel<>();
    private final JBList<FlowValueSearch.Match> results = new JBList<>(resultModel);
    private final CardLayout leftCards = new CardLayout();
    private final JPanel left = new JPanel(leftCards);
    private final Tree values = new Tree();
    private final JBLabel valueTitle = new JBLabel();
    /** The value's header (icon, title, type badge): shown with a value. */
    private final JPanel valueHeader = new JPanel(new BorderLayout(JBUI.scale(10), 0));
    /** The shown value's type, as a pill on the right of its header ("BookingDto"). */
    private final JBLabel typeBadge = new JBLabel() {
        @Override
        protected void paintComponent(java.awt.Graphics g) {
            if (getText() == null || getText().isEmpty()) return;
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(BADGE_BG);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            g2.setColor(BADGE_FG);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    };
    /** The active flow and a line's current pass. */
    private static final java.awt.Color ACTIVE = new JBColor(0x2E8B3A, 0x6FC27B);
    private static final java.awt.Color BADGE_BG = new JBColor(0xEEE9FF, 0x2E2A4F), BADGE_FG = new JBColor(0x6B4FD8, 0xB4A5FF);
    /** Value colors, as the editor shows them: text, numbers, true / false / null. */
    private static final SimpleTextAttributes STRING_VALUE = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, new JBColor(0x067D17, 0x6AAB73));
    private static final SimpleTextAttributes NUMBER_VALUE = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, new JBColor(0x1750EB, 0x2AACB8));
    private static final SimpleTextAttributes KEYWORD_VALUE = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, new JBColor(0x0033B3, 0xCF8E6D));
    private static final SimpleTextAttributes EDITED_BADGE = new SimpleTextAttributes(new JBColor(0xFFF1D6, 0x4A3A16),
            new JBColor(0x9A5B00, 0xF2C14E), null, SimpleTextAttributes.STYLE_BOLD);
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
        FlowButton viewList = button("View Data", AllIcons.Actions.Preview,
                "Look at the picked value, line, group or flow (several: together) as a tree, JSON or text, in a window of its own",
                this::viewPicked);
        savedBar.addGroup(false, 1, compare, viewList, history);
        savedBar.addGroup(false, 1, delete, bin);
        savedBar.addGroup(false, 1, importData, exportData);
        savedBar.addGroup(false, 1, settingsButton, FlowShortcuts.button("Debug Data", FlowShortcuts.DEBUG_DATA));
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
        // Ctrl+click to leave a row out: the rows it is in (its line, group, flow) are left out too, since picking them
        // takes everything inside (Delete would delete what was left out)
        snapshots.addTreeSelectionListener(e -> {
            if (narrowing) return;
            List<TreePath> drop = new ArrayList<>();
            for (TreePath p : e.getPaths()) {
                if (e.isAddedPath(p)) return; // a new pick (a click on a row): nothing to narrow
                for (TreePath up = p.getParentPath(); up != null; up = up.getParentPath()) {
                    if (snapshots.isPathSelected(up)) drop.add(up);
                }
            }
            if (drop.isEmpty()) return;
            narrowing = true;
            try {
                snapshots.removeSelectionPaths(drop.toArray(new TreePath[0]));
            } finally {
                narrowing = false;
            }
        });
        // drag values (or a line, or a group) onto a flow, a group or a value to move them there
        snapshots.setDragEnabled(true);
        snapshots.setDropMode(DropMode.ON);
        snapshots.setTransferHandler(new ValuesDrop());
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
        viewButton.setToolTipText("How the value is shown: Tree, JSON or Text (▾)");
        viewButton.addActionListener(e -> viewMenu());
        viewButton.setDropDown("Show the value as a Tree, JSON or Text", this::viewMenu);
        views.addGroup(false, 2, viewButton, expandToggle);
        views.addGroup(false, 2, addField, edit, removeField, resetValue);
        views.addGroup(false, 2, exportValue);
        views.setBorder(JBUI.Borders.empty(0, 2, 4, 2));
        // the same toolbar: it wraps as one, so no button is left alone on a line
        FlowToolbar shape = views;
        FlowButton expandAll = button("Expand All", AllIcons.Actions.Expandall, "Open every block of the value", this::expandValue);
        FlowButton collapseAll = button("Collapse All", AllIcons.Actions.Collapseall,
                "Fold every block: the value's own fields show, each folded", this::collapseValue);
        expandToggle.addActionListener(e -> toggleExpand());
        values.addTreeExpansionListener(new javax.swing.event.TreeExpansionListener() {
            @Override public void treeExpanded(javax.swing.event.TreeExpansionEvent e) { updateExpandToggle(); }
            @Override public void treeCollapsed(javax.swing.event.TreeExpansionEvent e) { updateExpandToggle(); }
        });
        values.addTreeSelectionListener(e -> updateExpandToggle());
        updateExpandToggle();
        // (Expand / Collapse sits next to the view)
        List<FlowButton> sorts = new ArrayList<>();
        for (com.example.flowtracker.model.FlowValueShape.Sort so : com.example.flowtracker.model.FlowValueShape.Sort.values()) {
            FlowButton b = new FlowButton(so.label, null, FlowButton.Style.SEGMENT);
            b.setToolTipText(so == com.example.flowtracker.model.FlowValueShape.Sort.AS_SAVED ? "The fields in the order they were saved"
                    : "The fields sorted by name (lists keep their order); only how they are shown");
            b.addActionListener(e -> setSort(so));
            sortButtons.put(so, b);
            sorts.add(b);
        }
        sortButtons.get(sort).setSelected(true); // the order is picked in the value's Settings (⚙)
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
        // (Hide Internals is in the value's Settings)
        // at the end: Find shows (or hides) the field filter; Settings holds the fields' order
        findButton.setToolTipText("Filter the fields (name, value or type); click again to hide it (Ctrl+F)");
        findButton.addActionListener(e -> showFind(!filterRow.isVisible()));
        FlowButton valueSettings = button("Settings", FlowTheme.ICON_SETTINGS, "How the value is shown: the order of its fields, Hide Internals",
                () -> {});
        valueSettings.addActionListener(e -> {
            com.example.flowtracker.ui.FlowPopupMenu m = new com.example.flowtracker.ui.FlowPopupMenu().header("ORDER OF THE FIELDS");
            for (com.example.flowtracker.model.FlowValueShape.Sort so : com.example.flowtracker.model.FlowValueShape.Sort.values()) {
                m.item(so == sort ? AllIcons.Actions.Checked : AllIcons.ObjectBrowser.Sorted, so.label,
                        so == com.example.flowtracker.model.FlowValueShape.Sort.AS_SAVED ? "As they were saved" : "By name",
                        null, () -> setSort(so));
            }
            // Hide Internals: one setting for Debug Data, Compare, Compare Pasted Data and Stop at Value
            boolean hidden = com.example.flowtracker.model.FlowValueShape.hideInternals();
            m.separator().header("SHOW")
                    .item(hidden ? AllIcons.Actions.Checked : AllIcons.Actions.ToggleVisibility, "Hide Internals",
                            "Strings as text, no compiler fields", null, () -> {
                                com.example.flowtracker.model.FlowValueShape.setHideInternals(!hidden);
                                internals.setSelected(!hidden);
                                reshape();
                            });
            m.showBelowRight(valueSettings); // it ends where the button ends
        });
        FlowButton viewDetails = button("", AllIcons.Actions.Preview, "View Data: this value as a tree, JSON or text, in a window of its own",
                this::viewShown);
        lockButton.setToolTipText("Lock this value: nothing in it can be changed until it is unlocked");
        lockButton.addActionListener(e -> toggleLock());
        shape.addTrailingGroup(1, lockButton, viewDetails, findButton, valueSettings);
        valueFilter.getTextEditor().getEmptyText().setText("Filter the fields (name, value or type)");
        valueFilter.setToolTipText("Only the fields that have this text, with the fields around them (Ctrl+F)");
        valueFilter.addDocumentListener(new DocumentAdapter() {
            @Override protected void textChanged(@NotNull DocumentEvent e) { reshape(); }
        });
        filterRow.setLayout(new BorderLayout());
        filterRow.setOpaque(false);
        filterRow.add(valueFilter, BorderLayout.CENTER);
        filterRow.setBorder(JBUI.Borders.empty(0, 2, 6, 2));
        filterRow.setVisible(false); // Find shows it
        JPanel tools = valueTools;
        tools.setLayout(new BorderLayout());
        tools.setOpaque(false);
        tools.add(shape, BorderLayout.CENTER);
        tools.add(filterRow, BorderLayout.SOUTH);
        tools.setVisible(false); // shown with a value
        // the value's header: an icon, "booking at File.java:26 in Class.method()" over when / which flow / edited, its type
        JBLabel valueIcon = new JBLabel(AllIcons.Nodes.Class) {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(JBUI.CurrentTheme.ActionButton.hoverBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), JBUI.scale(10), JBUI.scale(10));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        valueIcon.setPreferredSize(new Dimension(JBUI.scale(34), JBUI.scale(34)));
        valueIcon.setHorizontalAlignment(JBLabel.CENTER);
        JPanel iconBox = new JPanel(new java.awt.GridBagLayout());
        iconBox.setOpaque(false);
        iconBox.add(valueIcon);
        valueHeader.setOpaque(false);
        valueHeader.add(iconBox, BorderLayout.WEST);
        valueHeader.add(valueTitle, BorderLayout.CENTER);
        JPanel badgeBox = new JPanel(new java.awt.GridBagLayout());
        badgeBox.setOpaque(false);
        badgeBox.add(typeBadge);
        valueHeader.add(badgeBox, BorderLayout.EAST);
        valueHeader.setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(FlowTheme.CARD_BORDER, 0, 0, 1, 0), JBUI.Borders.empty(6, 6, 8, 6)));
        valueHeader.setVisible(false);
        JPanel head = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        head.add(valueHeader, BorderLayout.CENTER);
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

    /** Lock / unlock the value shown (it shows its state). */
    private final FlowButton lockButton = new FlowButton("", AllIcons.Ide.Readwrite, FlowButton.Style.OUTLINE);

    /** True while rows are left out of the selection because one inside them was. */
    private boolean narrowing;

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
        // groups left from flows deleted before their groups went with them
        for (String f : store.groupFlows()) {
            if (!flows.getFlowNames().contains(f) && store.getSnapshots().stream().noneMatch(x -> x.flowName.equals(f))) store.forgetFlow(f);
        }
        com.example.flowtracker.debug.FlowEvaluatePins.sync(project);
        Group selectedRow = selectedGroup();
        FlowSnapshot selected = selectedSnapshot();
        List<String> expandedGroups = expandedGroupKeys();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        // flow -> group ("" = none) -> line -> values; a flow's groups first (as made), then the lines outside them
        Map<String, Map<String, Map<String, List<FlowSnapshot>>>> groups = new LinkedHashMap<>();
        if (!showingBin) for (String f : flows.getFlowNames()) groups.put(f, groupsOf(f));
        Map<String, FlowSnapshot> firstOfLine = new java.util.HashMap<>();
        for (FlowSnapshot s : showingBin ? store.getDeleted() : store.getSnapshots()) {
            String key = FlowDebugStore.passKey(s);
            String flow = s.flowName.isEmpty() ? "(no flow)" : s.flowName;
            String group = s.group == null ? "" : s.group;
            firstOfLine.putIfAbsent(group + "|" + key, s);
            groups.computeIfAbsent(flow, this::groupsOf)
                    .computeIfAbsent(group, k -> new LinkedHashMap<>()).computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        String active = flows.getActiveFlowName();
        // the row the next save at each line goes to: the newest pass of that line in the active flow
        current.clear();
        if (!showingBin) {
            java.util.Map<String, FlowSnapshot> newest = new java.util.HashMap<>();
            for (FlowSnapshot s : store.getSnapshots()) {
                if (!s.flowName.equals(active)) continue;
                newest.merge(FlowDebugStore.lineKey(s), s, (a, b) -> a.savedAt >= b.savedAt ? a : b);
            }
            newest.forEach((line, s) -> current.add((s.group == null ? "" : s.group) + "|" + FlowDebugStore.passKey(s)));
        }
        groups.forEach((flow, byGroup) -> {
            DefaultMutableTreeNode f = new DefaultMutableTreeNode(new Group(store.title(flow), true, flow, null, flow.equals(active), null));
            byGroup.forEach((group, lines) -> {
                if (group.isEmpty()) return; // after the groups
                DefaultMutableTreeNode g = new DefaultMutableTreeNode(new Group(group, false, flow, null, false, group));
                addLines(g, flow, group, lines, firstOfLine);
                f.add(g);
            });
            Map<String, List<FlowSnapshot>> loose = byGroup.get("");
            if (loose != null) addLines(f, flow, "", loose, firstOfLine);
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
                    && java.util.Objects.equals(g.lineKey(), selectedRow.lineKey())
                    && java.util.Objects.equals(g.group(), selectedRow.group()));
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

    /** A flow's groups (empty, in their order: the list shows them even without values). */
    private Map<String, Map<String, List<FlowSnapshot>>> groupsOf(String flow) {
        Map<String, Map<String, List<FlowSnapshot>>> byGroup = new LinkedHashMap<>();
        if (!showingBin) for (String g : store.groups(flow)) byGroup.put(g, new LinkedHashMap<>());
        return byGroup;
    }

    /** The rows the next save at their line goes to ("group|passKey"), in the active flow. */
    private static final java.util.Set<String> current = new java.util.HashSet<>();

    /** A line's rows (the line, its values) under a flow or a group. */
    private static void addLines(DefaultMutableTreeNode under, String flow, String group, Map<String, List<FlowSnapshot>> lines,
                                 Map<String, FlowSnapshot> firstOfLine) {
        // a line with several passes (a loop came back to it, or a pasted copy): "· pass 2", the first one saved is pass 1
        java.util.Map<String, List<String>> passes = new java.util.HashMap<>();
        for (String key : lines.keySet()) {
            passes.computeIfAbsent(FlowDebugStore.lineKey(firstOfLine.get(group + "|" + key)), k -> new ArrayList<>()).add(key);
        }
        lines.forEach((key, list) -> {
            FlowSnapshot s = firstOfLine.get(group + "|" + key);
            List<String> same = passes.get(FlowDebugStore.lineKey(s));
            String where = s.where() + (s.method().isEmpty() ? "" : "  " + s.method())
                    + (same.size() > 1 ? "  · pass " + (same.size() - same.indexOf(key)) : "");
            boolean latest = same.size() > 1 && current.contains(group + "|" + key);
            DefaultMutableTreeNode l = new DefaultMutableTreeNode(new Group(where, false, flow, key, latest, group));
            for (FlowSnapshot x : list) l.add(new DefaultMutableTreeNode(x));
            under.add(l);
        });
    }

    /**
     * A flow, a group of a flow, or a line (of a flow or a group) in the list.
     * @param lineKey the line's key (for its name), null for a flow or a group
     * @param group   the group (a group's own row, or the group a line is in: "" for none), null for a flow
     */
    private record Group(String text, boolean flow, String flowName, @Nullable String lineKey, boolean active, @Nullable String group) {
        /** True for a group's own row (not a flow, not a line). */
        boolean isGroup() {
            return !flow && lineKey == null;
        }
    }

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
        lineSnapshots = saved;
        valueTitle.setText("<html><b>" + saved.size() + (saved.size() == 1 ? " value" : " values") + "</b> at " + esc(first.where())
                + (first.method().isEmpty() ? "" : " in " + esc(first.method()))
                + "<br><span style='color:" + gray() + "'>Pick a field to edit it</span></html>");
        typeBadge.setText("");
        updateButtons();
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
        items.add(new FlowMenuPopup.Item("New Group…", AllIcons.Nodes.Folder, true, () -> newGroup(flow, false)));
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
        else if (g.isGroup()) renameGroup(g);
        else nameLine();
    }

    // ---------- groups (and dragging values) ----------

    /** New Group: an empty group in a flow (with values picked, they go in it). */
    private void newGroup(String flow, boolean takePicked) {
        String name = Messages.showInputDialog(project, "Name of the new group in “" + store.title(flow) + "”:", "New Group", null,
                uniqueGroup(flow), null);
        if (name == null || name.isBlank()) return;
        List<FlowSnapshot> picked = takePicked ? draggedValues() : List.of();
        if (!store.addGroup(flow, name) && !store.groups(flow).contains(name.strip())) return;
        if (!picked.isEmpty()) store.moveTo(picked, flow, name.strip(), null);
        status.setText("Added the group “" + name.strip() + "”" + (picked.isEmpty() ? ": drag values onto it." : " with "
                + picked.size() + (picked.size() == 1 ? " value." : " values.")));
    }

    private String uniqueGroup(String flow) {
        List<String> have = store.groups(flow);
        for (int i = 1; ; i++) if (!have.contains("Group " + i)) return "Group " + i;
    }

    private void renameGroup(@Nullable Group g) {
        if (g == null || !g.isGroup()) return;
        String now = Messages.showInputDialog(project, "New name of the group “" + g.group() + "”:", "Rename Group", null, g.group(), null);
        if (now == null || now.isBlank()) return;
        store.renameGroup(g.flowName(), g.group(), now);
    }

    private void ungroup(@Nullable Group g) {
        if (g == null || !g.isGroup()) return;
        store.deleteGroup(g.flowName(), g.group());
        status.setText("Ungrouped “" + g.group() + "”: its values stay in the flow.");
    }

    /** Move to Group: a group of the picked values' flow (or a new one, or none). */
    private void moveToGroup() {
        List<FlowSnapshot> picked = draggedValues();
        if (picked.isEmpty()) return;
        String flow = picked.get(0).flowName;
        List<String> names = new ArrayList<>(store.groups(flow));
        String none = "(no group)";
        names.add(0, none);
        String chosen = Messages.showEditableChooseDialog("Group for " + picked.size() + (picked.size() == 1 ? " value" : " values")
                        + " (pick one, or type a new name):", "Move to Group", null, names.toArray(new String[0]),
                picked.get(0).group == null || picked.get(0).group.isEmpty() ? none : picked.get(0).group, null);
        if (chosen == null) return;
        String group = chosen.equals(none) ? "" : chosen.strip();
        store.moveTo(picked, flow, group, null);
        status.setText("Moved " + picked.size() + (picked.size() == 1 ? " value" : " values") + (group.isEmpty() ? " out of its group." : " to “" + group + "”."));
    }

    /** The values picked in the list: values, and every value of a picked line or group (what a drag moves). */
    private List<FlowSnapshot> draggedValues() {
        List<FlowSnapshot> out = new ArrayList<>();
        if (leftShowsResults() || showingBin) return out;
        TreePath[] paths = snapshots.getSelectionPaths();
        if (paths == null) return out;
        for (TreePath p : paths) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) p.getLastPathComponent();
            if (n.getUserObject() instanceof Group g && g.flow()) continue; // a whole flow isn't dragged
            java.util.Enumeration<?> all = n.depthFirstEnumeration();
            while (all.hasMoreElements()) {
                if (((DefaultMutableTreeNode) all.nextElement()).getUserObject() instanceof FlowSnapshot s && !out.contains(s)) out.add(s);
            }
        }
        return out;
    }

    /** The group picked to drag, when only a group's row is (it moves as a group into another flow). */
    private @Nullable Group draggedGroup() {
        TreePath[] paths = snapshots.getSelectionPaths();
        if (paths == null || paths.length != 1) return null;
        return ((DefaultMutableTreeNode) paths[0].getLastPathComponent()).getUserObject() instanceof Group g && g.isGroup() ? g : null;
    }

    /** Drag values (or lines, or a group) onto a flow, a group, a line or a value: they move there. */
    private final class ValuesDrop extends TransferHandler {
        private final java.awt.datatransfer.DataFlavor flavor = new java.awt.datatransfer.DataFlavor(List.class, "TraceWeave values");
        private List<FlowSnapshot> dragging = List.of();
        private @Nullable Group draggingGroup;

        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override
        protected java.awt.datatransfer.Transferable createTransferable(JComponent c) {
            dragging = draggedValues();
            draggingGroup = draggedGroup();
            if (dragging.isEmpty() && draggingGroup == null) return null;
            return new java.awt.datatransfer.Transferable() {
                @Override public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() { return new java.awt.datatransfer.DataFlavor[]{flavor}; }
                @Override public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor f) { return flavor.equals(f); }
                @Override public Object getTransferData(java.awt.datatransfer.DataFlavor f) { return dragging; }
            };
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (!support.isDrop() || showingBin || !support.isDataFlavorSupported(flavor)) return false;
            return target(support) != null;
        }

        /** Where a drop lands: the flow and the group, and the value it goes before (or null). */
        private @Nullable Object[] target(TransferSupport support) {
            JTree.DropLocation at = (JTree.DropLocation) support.getDropLocation();
            TreePath p = at.getPath();
            if (p == null) return null;
            Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
            if (o instanceof Group g) {
                if (g.flow()) return new Object[]{g.flowName(), "", null};
                return new Object[]{g.flowName(), g.group() == null ? "" : g.group(), null};
            }
            if (o instanceof FlowSnapshot s) return new Object[]{s.flowName, s.group == null ? "" : s.group, s};
            return null;
        }

        @Override
        public boolean importData(TransferSupport support) {
            Object[] t = canImport(support) ? target(support) : null;
            if (t == null) return false;
            String flow = (String) t[0], group = (String) t[1];
            FlowSnapshot before = (FlowSnapshot) t[2];
            List<FlowSnapshot> moving = new ArrayList<>(dragging);
            Group g = draggingGroup;
            // a group dropped on another flow goes there as a group (with its name)
            if (g != null && group.isEmpty() && before == null && !flow.equals(g.flowName())) {
                List<FlowSnapshot> its = store.getSnapshots().stream()
                        .filter(s -> s.flowName.equals(g.flowName()) && g.group().equals(s.group)).toList();
                store.addGroup(flow, g.group());
                store.moveTo(its, flow, g.group(), null);
                store.deleteGroup(g.flowName(), g.group());
                status.setText("Moved the group “" + g.group() + "” to “" + store.title(flow) + "”.");
                return true;
            }
            if (moving.isEmpty()) return false;
            store.moveTo(moving, flow, group, before);
            status.setText("Moved " + moving.size() + (moving.size() == 1 ? " value" : " values") + " to “" + store.title(flow) + "”"
                    + (group.isEmpty() ? "." : " · " + group + "."));
            return true;
        }

        @Override
        protected void exportDone(JComponent source, java.awt.datatransfer.Transferable data, int action) {
            dragging = List.of();
            draggingGroup = null;
        }
    }

    private com.intellij.openapi.actionSystem.ActionGroup listMenu() {
        com.intellij.openapi.actionSystem.DefaultActionGroup g = new com.intellij.openapi.actionSystem.DefaultActionGroup();
        java.util.function.BooleanSupplier flowSel = () -> selectedGroup() != null && selectedGroup().flow() && !showingBin;
        java.util.function.BooleanSupplier lineSel = () -> selectedGroup() != null && selectedGroup().lineKey() != null && !showingBin;
        java.util.function.BooleanSupplier groupSel = () -> selectedGroup() != null && selectedGroup().isGroup() && !showingBin;
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
        g.add(menuItem("New Group…", null, AllIcons.Nodes.Folder, () -> newGroup(pickedFlow(), true), () -> !showingBin));
        g.add(menuItem("Move to Group…", null, AllIcons.Actions.Forward, this::moveToGroup, () -> !showingBin && !draggedValues().isEmpty()));
        g.add(menuItem("Rename Group…", "F2", AllIcons.Actions.Edit, () -> renameGroup(selectedGroup()), groupSel));
        g.add(menuItem("Move Group Up", null, AllIcons.Actions.MoveUp, () -> store.moveGroup(selectedGroup().flowName(), selectedGroup().group(), -1), groupSel));
        g.add(menuItem("Move Group Down", null, AllIcons.Actions.MoveDown, () -> store.moveGroup(selectedGroup().flowName(), selectedGroup().group(), 1), groupSel));
        g.add(menuItem("Ungroup (Values Stay)", null, AllIcons.Actions.Unselectall, () -> ungroup(selectedGroup()), groupSel));
        g.add(menuItem("Delete Group…", "Delete", FlowTheme.ICON_DELETE, () -> deleteGroup(selectedGroup()), groupSel));
        g.addSeparator();
        g.add(menuItem("New Flow…", null, AllIcons.General.Add, this::newFlow, () -> !showingBin));
        g.add(menuItem("Copy", "Ctrl+C", AllIcons.Actions.Copy, this::copyItems,
                () -> !showingBin && (!draggedValues().isEmpty() || !selectedFlows().isEmpty() || draggedGroup() != null)));
        g.add(menuItem("Paste", "Ctrl+V", AllIcons.Actions.MenuPaste, this::pasteItems, () -> !showingBin));
        g.add(menuItem("Duplicate", "Ctrl+Shift+D", AllIcons.Actions.Copy, this::duplicateItems,
                () -> !showingBin && (!draggedValues().isEmpty() || selectedFlows().size() == 1 || draggedGroup() != null)));
        g.add(menuItem("View Data…", null, AllIcons.Actions.Preview, this::viewPicked, () -> !pickedItems().isEmpty()));
        g.add(menuItem("Lock / Unlock", null, AllIcons.Ide.Readonly, this::toggleLock,
                () -> !showingBin && (!draggedValues().isEmpty() || !selectedFlows().isEmpty())));
        g.add(menuItem("Compare the Two Picked", "Ctrl+D", AllIcons.Actions.Diff, this::compareSelected,
                () -> !showingBin && pickedItems().size() == 2));
        g.add(menuItem("Copy Values as JSON", null, AllIcons.Actions.Copy, this::copySnapshots, () -> !selectedSnapshots().isEmpty()));
        g.add(menuItem("Along the Flow…", null, FlowTheme.ICON_FLOWS, this::alongTheFlow, () -> selectedSnapshot() != null && !showingBin));
        // the variable's saves at its line, over time, with what changed each time
        g.add(menuItem("Timeline of This Variable…", null, AllIcons.Vcs.History, () -> {
            FlowSnapshot s = selectedSnapshot();
            if (s != null) new FlowTimelineDialog(project, s).show();
        }, () -> selectedSnapshot() != null && !showingBin && !store.sameVariable(selectedSnapshot()).isEmpty()));
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
    private record Field(FlowValue value, String path, String key, boolean pinned, boolean edited) {}

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
        valueTools.setVisible(true);
        lineSnapshots = null; // a line's values set it again after this
        // edited: what differs from the data as first saved, field by field by name (an index moving after a removal, or
        // a Revert, isn't an edit)
        FlowValue first = s != null && s.original != null ? shown(s.original) : null;
        DefaultMutableTreeNode top = node(null, shown(root), "", com.example.flowtracker.model.FlowHuntStore.get(project).pinned(),
                first, first != null);
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
        String bar = " &nbsp;<span style='color:" + gray() + "'>|</span>&nbsp; ";
        valueTitle.setText(s == null ? "<html><b>Current debug session</b><br><span style='color:" + gray() + "'>Not saved</span></html>"
                : "<html><b>" + esc(s.name) + "</b> at " + esc(s.where()) + (s.method().isEmpty() ? "" : " in " + esc(s.method()))
                + "<br><span style='color:" + gray() + "'>Saved " + fullTime(s.savedAt) + bar + esc(store.title(s.flowName))
                + (!FlowDebugStore.isChanged(s) ? "" : bar + "Edited ✎") + (s.locked ? bar + "🔒 Locked" : "") + "</span></html>");
        String type = FlowJsonTree.simpleType(FlowJsonTree.typeOf(root));
        typeBadge.setText(type.isEmpty() || s == null && lineSnapshots != null ? "" : type);
        typeBadge.setBorder(JBUI.Borders.empty(3, 12));
        typeBadge.setForeground(BADGE_FG);
        typeBadge.setFont(JBUI.Fonts.label().asBold());
        valueHeader.setVisible(true);
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
    private static DefaultMutableTreeNode node(@Nullable FlowValue parent, FlowValue v, String path, java.util.Set<String> pinned,
                                               @Nullable FlowValue was, boolean compare) {
        String key = FlowJsonTree.pinKey(parent, v.name);
        boolean edited = compare && (was == null // added
                || v.children.isEmpty() != was.children.isEmpty()
                || v.children.isEmpty() && !java.util.Objects.equals(v.value, was.value));
        DefaultMutableTreeNode n = new DefaultMutableTreeNode(new Field(v, path, key, parent != null && pinned.contains(key), edited));
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < v.children.size(); i++) if (pinned.contains(FlowJsonTree.pinKey(v, v.children.get(i).name))) order.add(i);
        for (int i = 0; i < v.children.size(); i++) if (!order.contains(i)) order.add(i);
        for (int i : order) {
            FlowValue c = v.children.get(i), before = null;
            if (was != null) for (FlowValue w : was.children) if (w.name.equals(c.name)) before = w;
            n.add(node(v, c, path.isEmpty() ? String.valueOf(i) : path + "." + i, pinned, before, compare));
        }
        return n;
    }

    /** True when a point of the value tree is on its row's icon (the bookmark to click); the top row has none. */
    private boolean onValueIcon(java.awt.Point p, int row) {
        java.awt.Rectangle r = values.getRowBounds(row);
        if (r == null || p.x < r.x || p.x > r.x + JBUI.scale(20) || p.y < r.y || p.y >= r.y + r.height) return false;
        TreePath path = values.getPathForRow(row);
        // every field (the object's own fields too); only the value's top row has no bookmark
        return path != null && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof Field f
                && !f.path().isEmpty();
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

    /** The field selected in the value tree (not its top row), or null. */
    private @Nullable Field selectedField() {
        TreePath p = values.getSelectionPath();
        if (p == null || p.getPathCount() <= 1) return null;
        return ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof Field f && !f.path().isEmpty() ? f : null;
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
        typeBadge.setText("");
        valueHeader.setVisible(false);
        valueTools.setVisible(false); // nothing selected: no buttons, just the hint
    }

    /** The value's buttons and the field filter (hidden while no value is selected). */
    private final JPanel valueTools = new JPanel();

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

    /** Tree, JSON or Text: one button showing the view, its ▾ (or a click) picks another. */
    private final FlowButton viewButton = new FlowButton("Tree", AllIcons.Actions.ShowAsTree, FlowButton.Style.OUTLINE);

    private static javax.swing.Icon viewIcon(View v) {
        return switch (v) {
            case TREE -> AllIcons.Actions.ShowAsTree;
            case JSON -> AllIcons.FileTypes.Json;
            default -> AllIcons.FileTypes.Text;
        };
    }

    private void viewMenu() {
        com.example.flowtracker.ui.FlowPopupMenu m = new com.example.flowtracker.ui.FlowPopupMenu().header("SHOW THE VALUE AS");
        for (View v : View.values()) {
            String hint = switch (v) { // short: the menu stays narrow (the button's tooltip says more)
                case TREE -> "As the debugger shows it";
                case JSON -> "Objects, maps and lists";
                default -> "name = value {type}";
            };
            m.item(v == view ? AllIcons.Actions.Checked : viewIcon(v), v.label, hint, null, () -> setView(v));
        }
        m.showBelow(viewButton);
    }

    private void setView(View v) {
        if (textEditing) stopTextEdit();
        view = v;
        viewButtons.forEach((k, b) -> b.setSelected(k == v));
        viewButton.setText(v.label);
        viewButton.setIcon(viewIcon(v));
        updateExpandToggle();
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

    /** Compare with nothing picked: the Compare window with both sides to be given (pasted, opened or picked). */
    private void comparePastedData() {
        FlowCompareDialog.show(project, "Compare", null, new FlowValue("value"), FlowCompareDialog.AWAITING, null, new FlowValue("value"),
                FlowCompareDialog.AWAITING);
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
        key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, menu), "copyValues", this::copyItems, snapshots);
        key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, menu), "copyResults", this::copySnapshots, results);
        key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_V, menu), "pasteValues", this::pasteItems, snapshots);
        key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_D, menu | java.awt.event.InputEvent.SHIFT_DOWN_MASK),
                "duplicateValues", this::duplicateItems, snapshots);
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
        // a line's values: a field picked in one of them is edited in that value
        FlowSnapshot inLine = view == View.TREE && !showingBin && !shaping() ? lineValueOfSelection() : null;
        boolean lineShown = shownSnapshot == null && lineSnapshots != null && !showingBin && !textEditing;
        if (lineShown) {
            addField.setEnabled(true);
            addField.setToolTipText(inLine == null ? "Add a new value at this line (Insert)"
                    : "Add a field under the selected one, in its value (Insert)");
        } else {
            addField.setToolTipText("Add a field under the selected one (or under the value itself) (Insert)");
        }
        if (inLine != null) {
            edit.setEnabled(true);
            addField.setEnabled(true);
            resetValue.setEnabled(FlowDebugStore.isChanged(inLine));
        }
        edit.setToolTipText(view == View.TREE ? "Change the selected field's name or value (F2; kept in History)"
                : "Edit the whole value as " + (view == View.JSON ? "JSON" : "text") + ", then Apply (kept in History)");
        String fieldPath = selectedValuePath();
        removeField.setEnabled(editable && shownSnapshot == s && fieldPath != null && !fieldPath.isEmpty()
                || inLine != null && fieldPath != null && fieldPath.contains("."));
        newValue.setEnabled(!showingBin);
        history.setEnabled(saved && !s.edits.isEmpty());
        boolean locked = shownSnapshot != null && shownSnapshot.locked;
        lockButton.setIcon(locked ? AllIcons.Ide.Readonly : AllIcons.Ide.Readwrite);
        lockButton.setSelected(locked);
        lockButton.setToolTipText(locked ? "Locked: nothing in it can be changed. Click to unlock"
                : "Lock this value: nothing in it can be changed until it is unlocked");
        lockButton.setEnabled(shownSnapshot != null && !showingBin);
        delete.setEnabled(!showingBin && (!selectedSnapshots().isEmpty() || !selectedFlows().isEmpty() || !pickedGroups().isEmpty()));
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
        if (showingBin) return;
        List<Item> items = pickedItems();
        if (items.isEmpty()) { // the search's results: the values picked there
            for (FlowSnapshot x : selectedSnapshots()) items.add(new Item(label(x), x.value, x));
        }
        if (items.size() == 2) {
            Item a = items.get(0), b = items.get(1);
            if (a.single() != null && b.single() != null) {
                // two saved values: each other, the older one on the left (edits made there are saved to them)
                FlowSnapshot x = a.single(), y = b.single();
                if (x.savedAt > y.savedAt) { FlowSnapshot t = x; x = y; y = t; }
                FlowCompareDialog.show(project, "Compare " + (x.name.equals(y.name) ? x.name : x.name + " and " + y.name),
                        x, label(x), y, label(y));
            } else {
                // two lines, groups or flows (or one and a value): all their values, matched by name and line
                FlowCompareDialog.show(project, "Compare " + a.label() + " and " + b.label(), null, a.value(), a.label(), null, b.value(), b.label());
            }
            return;
        }
        if (items.isEmpty()) {
            comparePastedData();
            return;
        }
        // one thing (a value, or a line's, group's or flow's values together) or several together on the Old side;
        // the New side is pasted, opened from a file or picked
        Item one = items.size() == 1 ? items.get(0) : null;
        FlowValue left;
        String title;
        if (one != null) {
            left = one.value();
            title = one.label();
        } else {
            left = new FlowValue(items.size() + " items");
            for (Item it : items) {
                FlowValue v = it.value().copy();
                v.name = it.label();
                left.children.add(v);
            }
            title = items.size() + " items";
        }
        FlowSnapshot saved = one != null ? one.single() : null;
        FlowCompareDialog.show(project, "Compare " + (saved != null ? saved.name : title), saved, left, title, null,
                new FlowValue(left.name), FlowCompareDialog.AWAITING);
    }

    /** Something picked in the list, as one value to compare: a value, or a line's, group's or flow's values. */
    private record Item(String label, FlowValue value, @Nullable FlowSnapshot single) {}

    /** The picked rows as things to compare (a value inside a picked line, group or flow is part of it). */
    private List<Item> pickedItems() {
        List<Item> out = new ArrayList<>();
        TreePath[] paths = leftShowsResults() ? null : snapshots.getSelectionPaths();
        if (paths == null) return out;
        java.util.Arrays.sort(paths, java.util.Comparator.comparingInt(snapshots::getRowForPath));
        List<DefaultMutableTreeNode> picked = new ArrayList<>();
        for (TreePath p : paths) picked.add((DefaultMutableTreeNode) p.getLastPathComponent());
        for (DefaultMutableTreeNode n : picked) {
            boolean inside = false;
            for (DefaultMutableTreeNode other : picked) if (other != n && n.isNodeAncestor(other)) inside = true;
            if (inside) continue;
            Object o = n.getUserObject();
            if (o instanceof FlowSnapshot x) {
                out.add(new Item(label(x), x.value, x));
            } else if (o instanceof Group g) {
                String what = g.flow() ? store.title(g.flowName()) : g.isGroup() ? g.group() + " (" + store.title(g.flowName()) + ")" : g.text();
                out.add(new Item(what, combined(what, n), null));
            }
        }
        return out;
    }

    /**
     * A flow's, group's or line's values as one value: each value by its name and line (a second save of the same
     * one numbered), a flow's groups as objects of their own, so two flows or groups are matched value by value.
     */
    private static FlowValue combined(String name, DefaultMutableTreeNode n) {
        FlowValue all = new FlowValue(name);
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (int i = 0; i < n.getChildCount(); i++) {
            DefaultMutableTreeNode c = (DefaultMutableTreeNode) n.getChildAt(i);
            if (c.getUserObject() instanceof Group g && g.isGroup()) {
                all.children.add(combined(g.group(), c));
            } else if (c.getUserObject() instanceof Group) {
                for (int j = 0; j < c.getChildCount(); j++) addValue(all, (DefaultMutableTreeNode) c.getChildAt(j), seen);
            } else {
                addValue(all, c, seen);
            }
        }
        return all;
    }

    private static void addValue(FlowValue all, DefaultMutableTreeNode n, java.util.Map<String, Integer> seen) {
        if (!(n.getUserObject() instanceof FlowSnapshot x)) return;
        FlowValue v = x.value.copy();
        String key = x.name + " (" + x.where() + ")";
        int k = seen.merge(key, 1, Integer::sum);
        v.name = k == 1 ? key : key + " #" + k;
        all.children.add(v);
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
        if (!toOwnValue()) return;
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
        if (!toOwnValue()) return;
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
        com.intellij.openapi.Disposable editing = com.intellij.openapi.util.Disposer.newDisposable("TraceWeave field editor");
        FlowCodeView valueArea = new FlowCodeView(project, editing);
        boolean json = looksLikeJson(value);
        valueArea.setText(json ? pretty(value) : value, json);
        valueArea.setEditable(true);
        JPanel nameRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        JBLabel nameText = new JBLabel(nameLabel);
        nameText.setFont(JBUI.Fonts.label().asBold());
        nameRow.add(nameText, BorderLayout.WEST);
        nameRow.add(nameField, BorderLayout.CENTER);
        JPanel form = valueEditor(title, nameRow, valueArea, () -> nameField.getText().isBlank() ? "value" : nameField.getText().strip(), true,
                editing, null);
        try {
            if (!runWindow(title, form, nameEditable && name.isEmpty() ? nameField : valueArea.getContentComponent(), "Save", () -> null)) return null;
            return new String[]{nameField.getText().strip(), valueArea.getText()};
        } finally {
            com.intellij.openapi.util.Disposer.dispose(editing);
        }
    }

    private static String pretty(String json) {
        try {
            return new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()
                    .toJson(com.google.gson.JsonParser.parseString(json));
        } catch (RuntimeException notJson) {
            return json;
        }
    }

    /**
     * The Edit and Add Field windows' body, in the Compare window's look: a header card (with Maximize), a left rail
     * (JSON to type, Tree to look at and change with its right-click menu), what goes above the value (the name, a
     * field list) and a status line.
     */
    /**
     * Shows a form in a window of its own (the system's Minimize, Maximize and Close) and waits until it is closed.
     *
     * @param check before closing with OK: null when fine, else what is wrong (shown, the window stays)
     * @return true when closed with OK
     */
    private boolean runWindow(String title, JComponent form, @Nullable JComponent focus, String okLabel,
                              java.util.function.Supplier<String> check) {
        boolean[] ok = {false};
        FlowButton okButton = new FlowButton(okLabel, null, FlowButton.Style.PRIMARY);
        FlowButton cancel = new FlowButton("Cancel", null, FlowButton.Style.OUTLINE);
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, JBUI.scale(8), 0));
        buttons.add(okButton);
        buttons.add(cancel);
        JPanel root = new JPanel(new BorderLayout(0, JBUI.scale(10)));
        root.add(form, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        root.setBorder(JBUI.Borders.empty(10, 12));
        FlowWindow window = new FlowWindow(project, "TraceWeave.FieldEditor", title, root, null);
        java.awt.SecondaryLoop loop = java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        Runnable accept = () -> {
            String problem = check.get();
            if (problem != null) {
                Messages.showErrorDialog(root, problem, title);
                return;
            }
            ok[0] = true;
            window.close();
        };
        okButton.addActionListener(e -> accept.run());
        cancel.addActionListener(e -> window.close());
        root.registerKeyboardAction(e -> window.close(), javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        root.registerKeyboardAction(e -> accept.run(), javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), JComponent.WHEN_IN_FOCUSED_WINDOW);
        window.whenClosed(loop::exit);
        window.show();
        if (focus != null) javax.swing.SwingUtilities.invokeLater(focus::requestFocusInWindow);
        loop.enter(); // until the window is closed (the IDE keeps working meanwhile)
        return ok[0];
    }

    private JPanel valueEditor(String title, JComponent above, FlowCodeView valueArea, java.util.function.Supplier<String> rootName,
                               boolean withViews, com.intellij.openapi.Disposable editing, @Nullable JComponent splitTop) {
        FlowCodeView asTextView = new FlowCodeView(project, editing);
        asTextView.setEditable(false);
        JBLabel icon = new JBLabel(AllIcons.Nodes.Variable);
        icon.setHorizontalAlignment(JBLabel.CENTER);
        icon.setPreferredSize(new Dimension(JBUI.scale(34), JBUI.scale(34)));
        JBLabel heading = new JBLabel("<html><b>" + esc(title) + "</b><br><span style='color:" + gray() + "'>"
                + (shownSnapshot != null ? esc(shownSnapshot.name) + " at " + esc(shownSnapshot.where())
                + (shownSnapshot.method().isEmpty() ? "" : " in " + esc(shownSnapshot.method())) : "A new value")
                + "</span></html>");
        JPanel header = new JPanel(new BorderLayout(JBUI.scale(10), 0));
        header.add(icon, BorderLayout.WEST);
        header.add(heading, BorderLayout.CENTER);
        header.setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(FlowTheme.CARD_BORDER, 0, 0, 1, 0), JBUI.Borders.empty(2, 2, 10, 2)));

        com.intellij.ui.treeStructure.Tree tree = new com.intellij.ui.treeStructure.Tree(new DefaultTreeModel(new DefaultMutableTreeNode()));
        tree.setCellRenderer(new EvaluateRenderer());
        tree.setShowsRootHandles(true);
        tree.getEmptyText().setText("Not JSON: only an object or a list shows as a tree");
        tree.getEmptyText().appendSecondaryText("Right-click the value to add a field", SimpleTextAttributes.GRAYED_ATTRIBUTES, null);
        JPanel views = new JPanel(new CardLayout());
        views.add(valueArea.getComponent(), "JSON");
        views.add(FlowScroll.wheel(new JBScrollPane(tree)), "TREE");
        views.add(asTextView.getComponent(), "TEXT");
        views.setPreferredSize(new Dimension(JBUI.scale(680), JBUI.scale(400)));
        views.setBorder(JBUI.Borders.customLine(FlowTheme.CARD_BORDER, 1));

        JBLabel state = new JBLabel();
        state.setBorder(JBUI.Borders.emptyTop(8));
        Runnable check = () -> {
            String t = valueArea.getText().strip();
            if (t.isEmpty()) {
                state.setIcon(AllIcons.General.Information);
                state.setText("Empty: type text, or JSON for an object or a list (Tree: right-click to add fields)");
                return;
            }
            if (!looksLikeJson(t)) {
                state.setIcon(AllIcons.General.Information);
                state.setText("Text value · " + t.length() + " characters");
                return;
            }
            try {
                com.google.gson.JsonElement e = com.google.gson.JsonParser.parseString(t);
                int[] n = {0};
                count(e, n);
                state.setIcon(AllIcons.General.InspectionsOK);
                state.setText("<html><b>Valid JSON</b> &nbsp;<span style='color:" + gray() + "'>|</span>&nbsp; " + n[0]
                        + (n[0] == 1 ? " field" : " fields") + " &nbsp;<span style='color:" + gray() + "'>|</span>&nbsp; ~ "
                        + String.format("%.1f KB", t.getBytes(java.nio.charset.StandardCharsets.UTF_8).length / 1024.0) + "</html>");
            } catch (RuntimeException bad) {
                state.setIcon(AllIcons.General.Error);
                state.setText("Not valid JSON yet: " + String.valueOf(bad.getMessage()).replaceAll("^.*?: ", ""));
            }
        };
        valueArea.onUserEdit(check);
        check.run();

        // the tree's value: changed with the right-click menu, written back to the JSON at once
        FlowValue[] root = {null};
        Runnable showTree = () -> {
            java.util.Set<String> open = new java.util.HashSet<>();
            for (int r = 0; r < tree.getRowCount(); r++) if (tree.isExpanded(r)) open.add(namePath(tree.getPathForRow(r)));
            boolean first = root[0] == null || open.isEmpty();
            String text = valueArea.getText().strip();
            root[0] = text.isEmpty() ? new FlowValue(rootName.get()) : FlowValueText.fromJson(rootName.get(), text);
            if (root[0] != null && text.isEmpty()) root[0].type = "Object";
            tree.setModel(new DefaultTreeModel(root[0] == null ? new DefaultMutableTreeNode() : evaluateNode(root[0], true)));
            tree.setRootVisible(root[0] != null);
            if (root[0] == null) return;
            if (first) {
                tree.expandRow(0);
                for (int r = 1; r < Math.min(tree.getRowCount(), 40); r++) if (tree.getPathForRow(r).getPathCount() <= 2) tree.expandRow(r);
            } else {
                for (int r = 0; r < tree.getRowCount(); r++) if (open.contains(namePath(tree.getPathForRow(r)))) tree.expandRow(r);
            }
        };
        Runnable written = () -> {
            valueArea.setText(pretty(FlowValueText.valueJson(root[0], false)), true);
            check.run();
            showTree.run();
        };
        com.intellij.ui.PopupHandler.installPopupMenu(tree, treeEditMenu(tree, root, written), "TraceWeave.EditTree");
        key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE, 0), "editTreeRemove",
                () -> treeRemove(tree, root, written), tree);
        key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F2, 0), "editTreeEdit",
                () -> treeEdit(tree, root, written), tree);

        FlowCompareDialog.RailItem[] rail = new FlowCompareDialog.RailItem[3];
        java.util.function.IntConsumer pick = i -> {
            ((CardLayout) views.getLayout()).show(views, new String[]{"JSON", "TEXT", "TREE"}[i]);
            for (int k = 0; k < rail.length; k++) rail[k].setSelected(k == i);
        };
        rail[0] = new FlowCompareDialog.RailItem("JSON", AllIcons.FileTypes.Json, () -> pick.accept(0));
        rail[0].setToolTipText("Type the value: JSON for an object or a list, else text");
        FlowCompareDialog.RailItem textItem = new FlowCompareDialog.RailItem("Text", AllIcons.FileTypes.Text, () -> {
            String t = valueArea.getText();
            FlowValue v = FlowValueText.fromJson(rootName.get(), t);
            asTextView.setText(v == null ? t : FlowValueText.toText(v), false);
            pick.accept(1);
        });
        textItem.setToolTipText("The value as indented text (only to look at; type it in JSON)");
        rail[2] = new FlowCompareDialog.RailItem("Tree", AllIcons.Actions.ShowAsTree, () -> {
            showTree.run();
            pick.accept(2);
        });
        FlowCompareDialog.RailItem treeItem = rail[2];
        rail[1] = textItem;
        treeItem.setToolTipText("The value as the debugger shows it; right-click to add, edit, duplicate or remove a field");
        JPanel railPanel = new JPanel();
        railPanel.setLayout(new javax.swing.BoxLayout(railPanel, javax.swing.BoxLayout.Y_AXIS));
        railPanel.setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(FlowTheme.CARD_BORDER, 0, 0, 0, 1), JBUI.Borders.emptyRight(8)));
        for (FlowCompareDialog.RailItem r : rail) {
            r.setAlignmentX(0.5f);
            railPanel.add(r);
            railPanel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)));
        }
        railPanel.add(javax.swing.Box.createVerticalGlue());
        rail[0].setSelected(true);

        above.setBorder(JBUI.Borders.empty(10, 0, 8, 0));
        JPanel valuePart = new JPanel(new BorderLayout());
        valuePart.add(above, BorderLayout.NORTH);
        valuePart.add(views, BorderLayout.CENTER);
        valuePart.add(state, BorderLayout.SOUTH);
        JComponent body = valuePart;
        if (splitTop != null) { // e.g. Add Field's list of the class's fields: the divider between them can be moved
            com.intellij.ui.JBSplitter split = new com.intellij.ui.JBSplitter(true, "TraceWeave.addField.split", 0.4f);
            split.setFirstComponent(splitTop);
            split.setSecondComponent(valuePart);
            body = split;
        }
        body.setBorder(JBUI.Borders.emptyLeft(withViews ? 10 : 0));
        JPanel form = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        form.add(header, BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout());
        if (withViews) center.add(railPanel, BorderLayout.WEST); // Add Field: just the value (no JSON / Text / Tree)
        center.add(body, BorderLayout.CENTER);
        form.add(center, BorderLayout.CENTER);
        return form;
    }

    /** View Data from the list: the picked values, lines, groups or flows (several: together under one top). */
    private void viewPicked() {
        List<Item> items = pickedItems();
        if (items.isEmpty()) {
            status.setText("Pick a value, a line, a group or a flow, then View Data.");
            return;
        }
        if (items.size() == 1) {
            Item it = items.get(0);
            viewData(it.label(), it.single() != null ? "Saved " + fullTime(it.single().savedAt) + " · " + store.title(it.single().flowName) : "",
                    it.value(), it.single());
            return;
        }
        FlowValue all = new FlowValue(items.size() + " items");
        for (Item it : items) {
            FlowValue v = it.value().copy();
            v.name = it.label();
            all.children.add(v);
        }
        viewData(items.size() + " items", "", all, null);
    }

    /** View Data from the details: the value shown on the right (or a line's values). */
    private void viewShown() {
        if (shownRoot == null) return;
        FlowSnapshot s = shownSnapshot;
        viewData(s != null ? s.name + " at " + s.where() : shownRoot.name,
                s != null ? "Saved " + fullTime(s.savedAt) + " · " + store.title(s.flowName) : "", shownRoot, s);
    }

    /** A window to look at data (in the Compare window's look): JSON, Text or Tree on the left rail; it can be maximized. */
    private void viewData(String title, String subtitle, FlowValue value, @Nullable FlowSnapshot saved) {
        FlowValue v = (com.example.flowtracker.model.FlowValueShape.hideInternals()
                ? com.example.flowtracker.model.FlowValueShape.withoutInternals(value) : value).copy();
        com.intellij.openapi.Disposable viewing = com.intellij.openapi.util.Disposer.newDisposable("TraceWeave view data");
        FlowCodeView json = new FlowCodeView(project, viewing), text = new FlowCodeView(project, viewing);
        json.setText(pretty(FlowValueText.valueJson(v, false)), true);
        json.setEditable(false);
        text.setText(FlowValueText.toText(v), false);
        text.setEditable(false);
        com.intellij.ui.treeStructure.Tree tree = new com.intellij.ui.treeStructure.Tree(new DefaultTreeModel(evaluateNode(v, true)));
        tree.setCellRenderer(new EvaluateRenderer());
        tree.setShowsRootHandles(true);
        tree.expandRow(0);
        for (int r = 1; r < Math.min(tree.getRowCount(), 40); r++) if (tree.getPathForRow(r).getPathCount() <= 2) tree.expandRow(r);
        // a saved value (not locked) can be changed here: right-click in the Tree, then Save
        boolean editable = saved != null && !saved.locked && !showingBin;
        FlowValue[] root = {v};
        FlowButton saveButton = new FlowButton("Save", AllIcons.Actions.MenuSaveall, FlowButton.Style.PRIMARY);
        saveButton.setEnabled(false);
        saveButton.setVisible(editable);
        Runnable written = () -> {
            java.util.Set<String> open = new java.util.HashSet<>();
            for (int r = 0; r < tree.getRowCount(); r++) if (tree.isExpanded(r)) open.add(namePath(tree.getPathForRow(r)));
            tree.setModel(new DefaultTreeModel(evaluateNode(root[0], true)));
            for (int r = 0; r < tree.getRowCount(); r++) if (open.contains(namePath(tree.getPathForRow(r)))) tree.expandRow(r);
            json.setText(pretty(FlowValueText.valueJson(root[0], false)), true);
            text.setText(FlowValueText.toText(root[0]), false);
            saveButton.setEnabled(true);
        };
        if (editable) {
            com.intellij.ui.PopupHandler.installPopupMenu(tree, treeEditMenu(tree, root, written), "TraceWeave.ViewDataTree");
            key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE, 0), "viewTreeRemove", () -> treeRemove(tree, root, written), tree);
            key(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F2, 0), "viewTreeEdit", () -> treeEdit(tree, root, written), tree);
            saveButton.addActionListener(e -> {
                if (saved.locked) {
                    Messages.showInfoMessage(tree, saved.name + " is locked: unlock it to change it.", "View Data");
                    return;
                }
                FlowValue now = root[0].copy();
                now.name = saved.value.name;
                store.replaceValue(saved, now, "changed in View Data");
                saveButton.setEnabled(false);
                status.setText("Saved the changes to " + saved.name + " (History → Revert undoes them).");
            });
        }
        JPanel views = new JPanel(new CardLayout());
        views.add(FlowScroll.wheel(new JBScrollPane(tree)), "TREE");
        views.add(json.getComponent(), "JSON");
        views.add(text.getComponent(), "TEXT");
        views.setBorder(JBUI.Borders.customLine(FlowTheme.CARD_BORDER, 1));

        JBLabel icon = new JBLabel(AllIcons.Nodes.Variable);
        icon.setPreferredSize(new Dimension(JBUI.scale(34), JBUI.scale(34)));
        icon.setHorizontalAlignment(JBLabel.CENTER);
        JBLabel heading = new JBLabel("<html><b>" + esc(title) + "</b>" + (subtitle.isEmpty() ? "" : "<br><span style='color:" + gray() + "'>"
                + esc(subtitle) + "</span>") + "</html>");
        JPanel header = new JPanel(new BorderLayout(JBUI.scale(10), 0));
        header.add(icon, BorderLayout.WEST);
        header.add(heading, BorderLayout.CENTER);
        String type = FlowJsonTree.simpleType(FlowJsonTree.typeOf(v));
        if (!type.isEmpty() && !type.equals("Object")) {
            JBLabel badge = new JBLabel(type);
            badge.setForeground(BADGE_FG);
            badge.setFont(JBUI.Fonts.label().asBold());
            header.add(badge, BorderLayout.EAST);
        }
        header.setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(FlowTheme.CARD_BORDER, 0, 0, 1, 0), JBUI.Borders.empty(2, 2, 10, 2)));

        int[] n = {0};
        java.util.ArrayDeque<FlowValue> all = new java.util.ArrayDeque<>(v.children);
        while (!all.isEmpty()) {
            FlowValue c = all.pop();
            n[0]++;
            all.addAll(c.children);
        }
        String jsonText = json.getText();
        JBLabel state = new JBLabel("<html><b>" + n[0] + (n[0] == 1 ? " field" : " fields") + "</b> &nbsp;<span style='color:" + gray()
                + "'>|</span>&nbsp; ~ " + String.format("%.1f KB", jsonText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length / 1024.0)
                + (com.example.flowtracker.model.FlowValueShape.hideInternals() ? " &nbsp;<span style='color:" + gray() + "'>|</span>&nbsp; internals hidden" : "")
                + "</html>", AllIcons.General.InspectionsOK, JBLabel.LEFT);
        state.setBorder(JBUI.Borders.emptyTop(8));

        FlowCompareDialog.RailItem[] rail = new FlowCompareDialog.RailItem[3];
        String[] cards = {"TREE", "JSON", "TEXT"};
        java.util.function.IntConsumer pick = i -> {
            ((CardLayout) views.getLayout()).show(views, cards[i]);
            for (int k = 0; k < rail.length; k++) rail[k].setSelected(k == i);
        };
        rail[0] = new FlowCompareDialog.RailItem("Tree", AllIcons.Actions.ShowAsTree, () -> pick.accept(0));
        rail[1] = new FlowCompareDialog.RailItem("JSON", AllIcons.FileTypes.Json, () -> pick.accept(1));
        rail[2] = new FlowCompareDialog.RailItem("Text", AllIcons.FileTypes.Text, () -> pick.accept(2));
        JPanel railPanel = new JPanel();
        railPanel.setLayout(new javax.swing.BoxLayout(railPanel, javax.swing.BoxLayout.Y_AXIS));
        railPanel.setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(FlowTheme.CARD_BORDER, 0, 0, 0, 1), JBUI.Borders.emptyRight(8)));
        for (FlowCompareDialog.RailItem r : rail) {
            r.setAlignmentX(0.5f);
            railPanel.add(r);
            railPanel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)));
        }
        railPanel.add(javax.swing.Box.createVerticalGlue());
        pick.accept(0);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(state, BorderLayout.CENTER);
        if (editable) {
            JBLabel hint = new JBLabel("Tree: right-click to add, edit, duplicate or remove ");
            hint.setForeground(UIUtil.getContextHelpForeground());
            JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, JBUI.scale(8), 0));
            right.add(hint);
            right.add(saveButton);
            bottom.add(right, BorderLayout.EAST);
        } else if (saved != null && saved.locked) {
            JBLabel locked = new JBLabel("🔒 Locked: unlock it to change it");
            locked.setForeground(UIUtil.getContextHelpForeground());
            bottom.add(locked, BorderLayout.EAST);
        }
        JPanel body = new JPanel(new BorderLayout());
        body.add(views, BorderLayout.CENTER);
        body.add(bottom, BorderLayout.SOUTH);
        body.setBorder(JBUI.Borders.emptyLeft(10));
        JPanel center = new JPanel(new BorderLayout());
        center.add(railPanel, BorderLayout.WEST);
        center.add(body, BorderLayout.CENTER);
        JPanel page = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        page.add(header, BorderLayout.NORTH);
        page.add(center, BorderLayout.CENTER);
        page.setBorder(JBUI.Borders.empty(10, 8, 10, 12));
        page.setPreferredSize(new Dimension(JBUI.scale(820), JBUI.scale(620)));
        // a window of its own (the system's Minimize, Maximize and Close)
        FlowWindow window = new FlowWindow(project, "TraceWeave.ViewData", "View Data · " + title, page, null);
        window.whenClosed(() -> com.intellij.openapi.util.Disposer.dispose(viewing));
        window.show();
    }

    /** "booking/priceLines/0": a row's place by names (to open the same rows again after a change). */
    private static String namePath(TreePath p) {
        StringBuilder sb = new StringBuilder();
        for (Object o : p.getPath()) {
            if (((DefaultMutableTreeNode) o).getUserObject() instanceof Object[] row) sb.append('/').append(((FlowValue) row[0]).name);
        }
        return sb.toString();
    }

    /** The value of a tree row and the value it is in (null for the top). */
    private static @Nullable FlowValue[] rowOf(JTree tree) {
        TreePath p = tree.getSelectionPath();
        if (p == null || !(((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof Object[] row)) return null;
        FlowValue parent = null;
        TreePath up = p.getParentPath();
        if (up != null && ((DefaultMutableTreeNode) up.getLastPathComponent()).getUserObject() instanceof Object[] pr) parent = (FlowValue) pr[0];
        return new FlowValue[]{(FlowValue) row[0], parent};
    }

    private com.intellij.openapi.actionSystem.ActionGroup treeEditMenu(JTree tree, FlowValue[] root, Runnable written) {
        com.intellij.openapi.actionSystem.DefaultActionGroup g = new com.intellij.openapi.actionSystem.DefaultActionGroup();
        java.util.function.BooleanSupplier any = () -> root[0] != null && rowOf(tree) != null;
        java.util.function.BooleanSupplier inner = () -> any.getAsBoolean() && rowOf(tree)[1] != null;
        g.add(menuItem("Add Field…", null, AllIcons.General.Add, () -> treeAdd(tree, root, written), () -> root[0] != null));
        g.add(menuItem("Edit…", "F2", AllIcons.Actions.Edit, () -> treeEdit(tree, root, written), inner));
        g.add(menuItem("Duplicate", null, AllIcons.Actions.Copy, () -> treeDuplicate(tree, root, written), inner));
        g.add(menuItem("Remove", "Delete", AllIcons.General.Remove, () -> treeRemove(tree, root, written), inner));
        g.addSeparator();
        g.add(menuItem("Expand All", null, AllIcons.Actions.Expandall, () -> TreeUtil.expandAll(tree), () -> true));
        g.add(menuItem("Collapse All", null, AllIcons.Actions.Collapseall, () -> TreeUtil.collapseAll(tree, 1), () -> true));
        return g;
    }

    /** A value typed in: JSON (an object, a list, a number, true / false / null, "text") as it is, other text as text. */
    private static FlowValue typed(String name, String text) {
        FlowValue v = FlowValueText.fromJson(name, text);
        if (v != null) return v;
        v = new FlowValue(name);
        String t = text.strip();
        boolean literal = t.matches("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?") || t.equals("true") || t.equals("false") || t.equals("null")
                || t.length() > 1 && t.startsWith("\"") && t.endsWith("\"");
        v.value = literal ? t : new com.google.gson.Gson().toJson(t);
        v.type = FlowValueText.guessType(t);
        return v;
    }

    /** A value as text to edit: JSON for an object or a list, else its text. */
    private static String editText(FlowValue v) {
        return v.children.isEmpty() ? (v.value == null ? "" : v.value) : FlowValueText.valueJson(v, false);
    }

    /** A list's elements named 0, 1, 2… again (or [0], [1]…) after one was added or taken away. */
    private static void renumber(FlowValue list) {
        boolean brackets = !list.children.isEmpty() && list.children.get(0).name.startsWith("[");
        for (int i = 0; i < list.children.size(); i++) list.children.get(i).name = brackets ? "[" + i + "]" : String.valueOf(i);
    }

    /** Add Field: into the picked object or list (a picked simple value: next to it), in this same window's look. */
    private void treeAdd(JTree tree, FlowValue[] root, Runnable written) {
        FlowValue[] r = rowOf(tree);
        FlowValue target = r == null ? root[0] : r[0].children.isEmpty() && r[1] != null && !FlowValueText.isList(r[0])
                && !"Object".equals(r[0].type) ? r[1] : r[0];
        boolean list = FlowValueText.isList(target);
        String[] f = list ? fieldDialog("Add Element to " + target.name, "Index:", nextIndex(target), "", false)
                : fieldDialog("Add Field to " + target.name, "Name:", "", "", true);
        if (f == null || !list && f[0].isEmpty()) return;
        if (!list) for (FlowValue c : target.children) {
            if (c.name.equals(f[0])) {
                Messages.showErrorDialog(tree, "“" + f[0] + "” is already in " + target.name + ": edit it instead (F2).", "Add Field");
                return;
            }
        }
        target.children.add(typed(list ? nextIndex(target) : f[0], f[1]));
        if (list) renumber(target);
        if (target.value != null && target.value.startsWith("size = ")) target.value = "size = " + target.children.size();
        written.run();
    }

    private void treeEdit(JTree tree, FlowValue[] root, Runnable written) {
        FlowValue[] r = rowOf(tree);
        if (r == null || r[1] == null) return;
        boolean inList = FlowValueText.isList(r[1]);
        String[] f = fieldDialog("Edit " + r[0].name, inList ? "Index:" : "Name:", r[0].name, editText(r[0]), !inList);
        if (f == null) return;
        FlowValue now = typed(inList || f[0].isEmpty() ? r[0].name : f[0], f[1]);
        int at = r[1].children.indexOf(r[0]);
        if (at >= 0) r[1].children.set(at, now);
        written.run();
    }

    private void treeDuplicate(JTree tree, FlowValue[] root, Runnable written) {
        FlowValue[] r = rowOf(tree);
        if (r == null || r[1] == null) return;
        FlowValue copy = r[0].copy();
        if (!FlowValueText.isList(r[1])) {
            java.util.Set<String> names = new java.util.HashSet<>();
            for (FlowValue c : r[1].children) names.add(c.name);
            String name = r[0].name + "Copy";
            for (int i = 2; names.contains(name); i++) name = r[0].name + "Copy" + i;
            copy.name = name;
        }
        r[1].children.add(r[1].children.indexOf(r[0]) + 1, copy);
        if (FlowValueText.isList(r[1])) renumber(r[1]);
        if (r[1].value != null && r[1].value.startsWith("size = ")) r[1].value = "size = " + r[1].children.size();
        written.run();
    }

    private void treeRemove(JTree tree, FlowValue[] root, Runnable written) {
        FlowValue[] r = rowOf(tree);
        if (r == null || r[1] == null) return;
        boolean list = FlowValueText.isList(r[1]);
        r[1].children.remove(r[0]);
        if (list) renumber(r[1]);
        if (r[1].value != null && r[1].value.startsWith("size = ")) r[1].value = "size = " + r[1].children.size();
        written.run();
    }

    /** How many fields and elements a JSON value has (for the Edit window's status). */
    private static void count(com.google.gson.JsonElement e, int[] n) {
        if (e.isJsonObject()) {
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> x : e.getAsJsonObject().entrySet()) {
                n[0]++;
                count(x.getValue(), n);
            }
        } else if (e.isJsonArray()) {
            for (com.google.gson.JsonElement x : e.getAsJsonArray()) {
                n[0]++;
                count(x, n);
            }
        }
    }

    /** A value's tree for the Edit window (the top row is the value itself). */
    private static DefaultMutableTreeNode evaluateNode(FlowValue v, boolean top) {
        DefaultMutableTreeNode n = new DefaultMutableTreeNode(new Object[]{v, top});
        for (FlowValue c : v.children) n.add(evaluateNode(c, false));
        return n;
    }

    /** Rows as the debugger's Variables / Evaluate show them: (f) name = {Type} value, in the editor's colors. */
    private static final class EvaluateRenderer extends ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
            if (!(((DefaultMutableTreeNode) value).getUserObject() instanceof Object[] o)) return;
            FlowValue v = (FlowValue) o[0];
            boolean top = (Boolean) o[1];
            boolean element = v.name.matches("\\[?\\d+]?");
            setIcon(FlowVariablesLook.icon(top));
            append(element ? v.name.replaceAll("[\\[\\]]", "") : v.name, SimpleTextAttributes.REGULAR_ATTRIBUTES);
            append(" = ", SimpleTextAttributes.REGULAR_ATTRIBUTES);
            String text = FlowVariablesLook.text(v.value, v.type), type = FlowVariablesLook.type(v.type);
            if (!v.children.isEmpty() && !type.isEmpty()) append(type + (text.isEmpty() ? "" : " "), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            append(text, valueColor(text));
        }
    }

    private void addFieldToSelected() {
        if (shownSnapshot == null && lineSnapshots != null && lineValueOfSelection() == null) {
            newValueAt(lineSnapshots.get(0)); // the line itself (or nothing) picked: a new value at that line
            return;
        }
        if (!toOwnValue()) return;
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
        com.intellij.openapi.Disposable editing = com.intellij.openapi.util.Disposer.newDisposable("TraceWeave add field");
        FlowCodeView valueArea = new FlowCodeView(project, editing);
        valueArea.setText("", true);
        valueArea.setEditable(true);
        JBLabel typeLabel = new JBLabel(" ");
        typeLabel.setForeground(UIUtil.getContextHelpForeground());
        String[] chosenType = {""};
        List<com.example.flowtracker.code.FlowClassFields.Field> ordered = new ArrayList<>();
        for (com.example.flowtracker.code.FlowClassFields.Field fl : fields) if (!there.contains(fl.name())) ordered.add(fl);
        int firstSaved = ordered.size();
        for (com.example.flowtracker.code.FlowClassFields.Field fl : fields) if (there.contains(fl.name())) ordered.add(fl);
        JBList<com.example.flowtracker.code.FlowClassFields.Field> list = new JBList<>(ordered);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ColoredListCellRenderer<com.example.flowtracker.code.FlowClassFields.Field> row = new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends com.example.flowtracker.code.FlowClassFields.Field> l,
                                                 com.example.flowtracker.code.FlowClassFields.Field fl, int index, boolean selected, boolean focus) {
                setIcon(fl.inherited() ? AllIcons.Hierarchy.Supertypes : AllIcons.Nodes.Field);
                boolean saved = there.contains(fl.name());
                // not saved yet: green (they can be added); saved ones: gray, after a gap
                append(fl.name(), saved ? SimpleTextAttributes.GRAYED_ATTRIBUTES : new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, ACTIVE));
                append("  " + fl.type(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                if (fl.isStatic()) append("  static", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                append(fl.inherited() ? "   inherited from " + fl.owner() : "   own (" + fl.owner() + ")",
                        fl.inherited() ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, JBColor.namedColor("Label.infoForeground", JBColor.GRAY))
                                : new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, EDITED));
                if (saved) append("   already saved", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
            }
        };
        list.setCellRenderer((l, fl, index, selected, focus) -> {
            java.awt.Component c = row.getListCellRendererComponent(l, fl, index, selected, focus);
            if (index != 0 && index != firstSaved) return c;
            JPanel p = new JPanel(new BorderLayout());
            p.setOpaque(false);
            com.intellij.ui.TitledSeparator title2 = new com.intellij.ui.TitledSeparator(index == 0 && firstSaved > 0
                    ? "Not saved yet (" + firstSaved + ")" : "Already saved (" + (ordered.size() - firstSaved) + ")");
            if (index != 0) title2.setBorder(JBUI.Borders.emptyTop(10)); // a gap before the saved ones
            p.add(title2, BorderLayout.NORTH);
            p.add(c, BorderLayout.CENTER);
            return p;
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
            valueArea.requestFocus();
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

        JPanel pick = null;
        if (!fields.isEmpty()) {
            pick = new JPanel(new BorderLayout(0, JBUI.scale(4)));
            pick.add(new JBLabel("Fields of " + type + " in the code (not saved yet first); pick one or type any name:"), BorderLayout.NORTH);
            pick.add(FlowScroll.wheel(new JBScrollPane(list)), BorderLayout.CENTER);
            pick.setBorder(JBUI.Borders.emptyTop(10));
        }
        JPanel nameRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        JBLabel nameText = new JBLabel("Name:");
        nameText.setFont(JBUI.Fonts.label().asBold());
        nameRow.add(nameText, BorderLayout.WEST);
        nameRow.add(nameField, BorderLayout.CENTER);
        nameRow.add(typeLabel, BorderLayout.SOUTH);
        JPanel form = valueEditor(title, nameRow, valueArea, () -> nameField.getText().isBlank() ? "value" : nameField.getText().strip(), false,
                editing, pick);
        // a field that is already there can't be added again (edit it instead)
        try {
            if (!runWindow(title, form, fields.isEmpty() ? nameField : list, "Add", () -> {
                String name = nameField.getText().strip();
                if (name.isEmpty()) return "Give the field a name (or pick one from the list).";
                if (there.contains(name)) return "“" + name + "” is already in " + target.name + ". Edit it instead (F2).";
                return null;
            })) {
                return null;
            }
            return new String[]{nameField.getText().strip(), valueArea.getText(), chosenType[0]};
        } finally {
            com.intellij.openapi.util.Disposer.dispose(editing);
        }
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
        if (!toOwnValue()) return;
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
    /** A value without what a language puts inside it (when Hide Internals is on), also inside text that holds JSON. */
    private static FlowValue clean(FlowValue v) {
        if (!com.example.flowtracker.model.FlowValueShape.hideInternals()) return v;
        FlowValue c = com.example.flowtracker.model.FlowValueShape.withoutInternals(v);
        if (c.children.isEmpty() && c.value != null && c.value.strip().startsWith("{")) {
            c = c.copy();
            c.value = cleanText(c.value);
        }
        return c;
    }

    /** Text that holds a value's internals as JSON ({"coder": 0, "value": [83, 65…]}): what it stands for ("SA…"). */
    private static String cleanText(String text) {
        String t = text.strip();
        FlowValue parsed = FlowValueText.fromJson("v", t);
        if (parsed == null || !com.example.flowtracker.model.FlowValueShape.hasInternals(parsed)) return text;
        FlowValue c = com.example.flowtracker.model.FlowValueShape.withoutInternals(parsed);
        return c.children.isEmpty() ? c.value : FlowValueText.valueJson(c, false).strip();
    }

    private void copyField(CopyAs as) {
        if (shownRoot == null) return;
        // what the tree shows (with Hide Internals: a String's text, not its bytes, coder and hash)
        List<FlowValue> picked = new ArrayList<>();
        TreePath[] rows = values.getSelectionPaths();
        if (rows != null) {
            for (TreePath p : rows) {
                if (((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof Field f) picked.add(clean(f.value()));
            }
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
    /** What Copy took: the values, whether their groups go with them (a whole group or flow), and what was copied. */
    private record Clip(List<FlowSnapshot> values, boolean keepGroups, List<String> emptyGroups, String what, String text,
                        Kind kind, List<String> flows, @Nullable Group group) {}

    /** What was copied: values (they go back into their line), lines (new rows), a group (a new group), flows (new flows). */
    private enum Kind { VALUES, LINES, GROUP, FLOWS }

    /** True when a line's row is picked (not only values). */
    private boolean linePicked() {
        TreePath[] paths = snapshots.getSelectionPaths();
        if (paths == null) return false;
        for (TreePath p : paths) {
            if (((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof Group g && g.lineKey() != null) return true;
        }
        return false;
    }

    /** The last Copy here (Paste uses it while the clipboard still holds what it put there). */
    private @Nullable Clip clip;

    /** Copy: the picked values, lines, groups or flows (and, as JSON, to the clipboard). */
    private void copyItems() {
        List<FlowSnapshot> values = draggedValues();
        List<String> flowsPicked = selectedFlows();
        List<String> empty = new ArrayList<>();
        boolean keep = !flowsPicked.isEmpty() || draggedGroup() != null;
        for (String f : flowsPicked) {
            for (FlowSnapshot x : store.getSnapshots()) if (x.flowName.equals(f) && !values.contains(x)) values.add(x);
            for (String g : store.groups(f)) empty.add(g);
        }
        Group g = draggedGroup();
        if (g != null) empty.add(g.group());
        if (values.isEmpty() && empty.isEmpty()) return;
        String what = !flowsPicked.isEmpty() ? (flowsPicked.size() == 1 ? "the flow “" + store.title(flowsPicked.get(0)) + "”" : flowsPicked.size() + " flows")
                : g != null ? "the group “" + g.group() + "”" : values.size() == 1 ? values.get(0).name : values.size() + " values";
        String text = values.size() == 1 ? FlowValueText.toJson(values.get(0).value, true) : jsonOf(values);
        FlowCopy.toClipboard(text);
        Kind kind = !flowsPicked.isEmpty() ? Kind.FLOWS : g != null ? Kind.GROUP : linePicked() ? Kind.LINES : Kind.VALUES;
        clip = new Clip(new ArrayList<>(values), keep, empty, what, text, kind, flowsPicked, g);
        status.setText("Copied " + what + ". Paste (Ctrl+V) on a flow or a group puts a copy there; elsewhere it is JSON.");
    }

    private static String jsonOf(List<FlowSnapshot> list) {
        FlowValue all = new FlowValue("values");
        for (FlowSnapshot s : list) {
            FlowValue v = s.value.copy();
            v.name = s.name + " @ " + s.where();
            all.children.add(v);
        }
        return FlowValueText.valueJson(all, false);
    }

    /** Where Paste and Duplicate put copies: the picked flow (and group), else the active flow. */
    private String[] pasteTarget() {
        Group g = selectedGroup();
        if (g != null) return new String[]{g.flowName(), g.flow() ? null : g.group()};
        FlowSnapshot s = selectedSnapshot();
        if (s != null) return new String[]{s.flowName, s.group == null ? "" : s.group};
        return new String[]{flows.getActiveFlowName(), null};
    }

    /** Paste: copies of what was copied here, or values from a Debug Data export on the clipboard, into the picked flow / group. */
    private void pasteItems() {
        if (showingBin) return;
        String board = com.intellij.openapi.ide.CopyPasteManager.getInstance().getContents(java.awt.datatransfer.DataFlavor.stringFlavor);
        List<FlowSnapshot> from;
        boolean keep;
        List<String> empty;
        String what;
        if (clip != null && board != null && board.equals(clip.text())) {
            Clip c = clip;
            if (c.kind() == Kind.FLOWS) { // each flow as a new flow ("Flow 1 copy")
                for (String f : c.flows()) duplicateFlow(f);
                return;
            }
            String[] t = pasteTarget();
            if (c.kind() == Kind.GROUP && c.group() != null) { // a new group ("Group 1 copy") with copies of its values
                String name = copyName(t[0], c.group().group());
                store.addGroup(t[0], name);
                paste(c.values(), t[0], name, false, List.of(), true);
                status.setText("Pasted the group as “" + name + "” in “" + store.title(t[0]) + "”.");
                return;
            }
            boolean lines = c.kind() == Kind.LINES;
            paste(c.values(), t[0], t[1], false, List.of(), lines);
            status.setText("Pasted " + c.what() + (lines ? " as new rows" : "") + " into “" + store.title(t[0]) + "”"
                    + (t[1] == null || t[1].isEmpty() ? "." : " · " + t[1] + "."));
            return;
        }
        { // not copied here: a Debug Data export's JSON on the clipboard
            try {
                from = board == null ? List.of() : com.example.flowtracker.model.FlowDebugTransfer.fromJson(board, List.of());
            } catch (java.io.IOException notOurs) {
                status.setText("Nothing to paste: copy values, a group or a flow here first (or a Debug Data export's JSON).");
                return;
            }
            keep = true;
            empty = List.of();
            what = from.size() + (from.size() == 1 ? " value" : " values");
        }
        String[] t = pasteTarget();
        paste(from, t[0], t[1], keep, empty, true);
        status.setText("Pasted a copy of " + what + " into “" + store.title(t[0]) + "”" + (t[1] == null || t[1].isEmpty() ? "." : " · " + t[1] + "."));
    }

    /**
     * Copies of values into a flow: into {@code group} when given, else each in its own group ({@code keepGroups}) or none;
     * {@code renamed} maps a group's name to the copy's (Duplicate's "Group 1 copy").
     */
    private List<FlowSnapshot> paste(List<FlowSnapshot> from, String flow, @Nullable String group, boolean keepGroups, List<String> emptyGroups,
                                     boolean newRows) {
        java.util.Map<String, String> renamed = null;
        List<FlowSnapshot> copies;
        try {
            copies = com.example.flowtracker.model.FlowDebugTransfer.fromJson(
                    com.example.flowtracker.model.FlowDebugTransfer.toJson(from), store.getSnapshots());
        } catch (java.io.IOException e) {
            return List.of();
        }
        // each copied line is a row of its own (a new pass), not more values in the original's row
        java.util.Map<String, String> newPass = new java.util.HashMap<>();
        for (FlowSnapshot c : copies) {
            // a copied value goes back into its line's row; a copied line (group, flow) is a new row with all its values
            if (newRows) c.pass = newPass.computeIfAbsent(FlowDebugStore.passKey(c), k -> java.util.UUID.randomUUID().toString());
            String g = group != null ? group : keepGroups && c.group != null ? c.group : "";
            if (renamed != null && renamed.containsKey(g)) g = renamed.get(g);
            c.flowName = flow;
            c.group = g;
            c.id = java.util.UUID.randomUUID().toString();
        }
        if (group == null && keepGroups) {
            for (String g : emptyGroups) store.addGroup(flow, renamed != null && renamed.containsKey(g) ? renamed.get(g) : g);
        }
        store.addAll(copies);
        return copies;
    }

    /** Duplicate: a copy next to the original (a value or line in its group, a group as "… copy", a flow as a new flow). */
    private void duplicateItems() {
        if (showingBin) return;
        List<String> flowsPicked = selectedFlows();
        if (!flowsPicked.isEmpty()) {
            for (String f : flowsPicked) duplicateFlow(f);
            return;
        }
        Group g = draggedGroup();
        if (g != null) {
            String name = copyName(g.flowName(), g.group());
            store.addGroup(g.flowName(), name);
            List<FlowSnapshot> values = store.getSnapshots().stream()
                    .filter(x -> x.flowName.equals(g.flowName()) && g.group().equals(x.group)).toList();
            paste(values, g.flowName(), name, false, List.of(), true);
            status.setText("Duplicated the group as “" + name + "”.");
            return;
        }
        List<FlowSnapshot> values = draggedValues();
        if (values.isEmpty()) return;
        boolean lines = linePicked();
        // a value next to itself in its line's row; a line as a new row with all its values
        paste(values, values.get(0).flowName, null, true, List.of(), lines);
        status.setText("Duplicated " + (values.size() == 1 ? values.get(0).name : values.size() + " values") + (lines ? " as new rows." : "."));
    }

    /** A flow's copy: a new flow ("Flow 1 copy") with its Flow-tab lines, its groups and its values; it becomes the active flow. */
    private void duplicateFlow(String flow) {
        List<com.example.flowtracker.model.FlowEntry> lines = new ArrayList<>();
        for (com.example.flowtracker.model.FlowEntry e : flows.getEntriesOf(flow)) lines.add(e.copy());
        flows.newFlow(store.title(flow) + " copy", lines);
        String now = flows.getActiveFlowName();
        store.forgetFlow(now); // nothing left from a deleted flow of that name
        List<FlowSnapshot> values = store.getSnapshots().stream().filter(x -> x.flowName.equals(flow)).toList();
        paste(values, now, null, true, store.groups(flow), true);
        status.setText("Copied the flow as “" + now + "” (its lines, groups and " + values.size() + (values.size() == 1 ? " value)." : " values)."));
    }

    /** "Group 1 copy" (or "… copy 2") not taken yet in the flow. */
    private String copyName(String flow, String group) {
        List<String> have = store.groups(flow);
        String name = group + " copy";
        for (int i = 2; have.contains(name); i++) name = group + " copy " + i;
        return name;
    }

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
        // the field's bookmark: first in its object (in every saved value of that type, and in Stop at Value)
        g.add(new com.intellij.openapi.project.DumbAwareAction() {
            @Override public void actionPerformed(@NotNull com.intellij.openapi.actionSystem.AnActionEvent e) {
                Field f = selectedField();
                if (f != null) toggleBookmark(f);
            }

            @Override public void update(@NotNull com.intellij.openapi.actionSystem.AnActionEvent e) {
                Field f = selectedField();
                e.getPresentation().setEnabledAndVisible(f != null);
                if (f == null) return;
                e.getPresentation().setText(f.pinned() ? "Remove Bookmark" : "Bookmark: to the Top");
                e.getPresentation().setDescription(f.pinned() ? "It goes back to its place" : "First in its object, in every saved value of that type");
                e.getPresentation().setIcon(new FlowJsonTree.BookmarkIcon(f.pinned()));
            }

            @Override public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
                return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
            }
        });
        g.addSeparator();
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
        return shownSnapshot != null && !showingBin && !shaping() && !shownSnapshot.locked;
    }

    /** Lock or unlock: the picked values (also every value of a picked line, group or flow), or the one shown. */
    private void toggleLock() {
        List<FlowSnapshot> list = draggedValues();
        for (String f : selectedFlows()) for (FlowSnapshot x : store.getSnapshots()) if (x.flowName.equals(f) && !list.contains(x)) list.add(x);
        if (list.isEmpty() && shownSnapshot != null) list.add(shownSnapshot);
        if (list.isEmpty()) return;
        boolean lock = list.stream().anyMatch(x -> !x.locked);
        store.setLocked(list, lock);
        status.setText((lock ? "Locked " : "Unlocked ") + (list.size() == 1 ? list.get(0).name : list.size() + " values")
                + (lock ? ": they can't be changed until unlocked." : "."));
        updateButtons();
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
        if (shaping()) {
            status.setText("The value is sorted or filtered: show it As Saved with no filter to edit it.");
            return true;
        }
        withoutInternalsToEdit();
        return false;
    }

    /**
     * A value saved with internals (a String's bytes…), edited while they are hidden: its data becomes the value as
     * shown (one step in its History, so Revert brings the raw data back). Then the edit works on what is shown:
     * "BK-1042", not its bytes, and the fields' places are the ones on screen.
     */
    private void withoutInternalsToEdit() {
        FlowSnapshot s = shownSnapshot;
        if (s == null || !com.example.flowtracker.model.FlowValueShape.hideInternals()
                || !com.example.flowtracker.model.FlowValueShape.hasInternals(s.value)) return;
        List<String> picked = selectedValuePaths();
        store.replaceValue(s, com.example.flowtracker.model.FlowValueShape.withoutInternals(s.value).copy(), "internals left out");
        showValue(s, s.value, picked.isEmpty() ? null : picked.get(0));
    }

    /** The values of the line shown together (a line picked in the list), or null. */
    private @Nullable List<FlowSnapshot> lineSnapshots;

    /**
     * A field picked in a line's values: that value is shown on its own, with the field selected, so Add, Edit,
     * Remove and Reset work on it. False when there is none (nothing to edit).
     */
    private boolean toOwnValue() {
        if (shownSnapshot != null) return true;
        List<FlowSnapshot> line = lineSnapshots;
        String p = selectedValuePath();
        if (line == null || p == null || p.isEmpty()) return false;
        int dot = p.indexOf('.');
        int i;
        try {
            i = Integer.parseInt(dot < 0 ? p : p.substring(0, dot));
        } catch (NumberFormatException e) {
            return false;
        }
        if (i < 0 || i >= line.size()) return false;
        FlowSnapshot s = line.get(i);
        select(s);
        showValue(s, s.value, dot < 0 ? null : p.substring(dot + 1));
        return shownSnapshot == s;
    }

    /** The saved value the selected field of a line's values belongs to, or null. */
    private @Nullable FlowSnapshot lineValueOfSelection() {
        List<FlowSnapshot> line = lineSnapshots;
        String p = selectedValuePath();
        if (shownSnapshot != null || line == null || p == null || p.isEmpty()) return null;
        int dot = p.indexOf('.');
        try {
            int i = Integer.parseInt(dot < 0 ? p : p.substring(0, dot));
            return i >= 0 && i < line.size() ? line.get(i) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Expand or Collapse: the selected field (with what is inside it), or (none selected) the whole value. */
    private final FlowButton expandToggle = new FlowButton("", AllIcons.Actions.Expandall, FlowButton.Style.OUTLINE);
    /** Without a field selected (and in JSON / Text): the whole value was opened last (the next click folds it). */
    private boolean allOpen;

    /** The selected field when it has fields inside it (else null: the button works on the whole value). */
    private @Nullable TreePath expandTarget() {
        if (view != View.TREE) return null;
        TreePath p = values.getSelectionPath();
        return p != null && !((DefaultMutableTreeNode) p.getLastPathComponent()).isLeaf() ? p : null;
    }

    private void updateExpandToggle() {
        TreePath p = expandTarget();
        boolean collapse = p != null ? values.isExpanded(p) : allOpen;
        String what = p != null ? "“" + ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() + "”" : "the whole value";
        if (p != null && ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof Field f) what = "“" + f.value().name + "”";
        expandToggle.setIcon(collapse ? AllIcons.Actions.Collapseall : AllIcons.Actions.Expandall);
        expandToggle.setToolTipText((collapse ? "Collapse " : "Expand ") + what
                + (p != null ? " (with what is inside it)" : "") + ". Select a field to open or fold just it.");
    }

    private void toggleExpand() {
        TreePath p = expandTarget();
        if (p != null) {
            if (values.isExpanded(p)) {
                values.collapsePath(p);
            } else {
                expandUnder((DefaultMutableTreeNode) p.getLastPathComponent());
            }
        } else {
            if (allOpen) collapseValue();
            else expandValue();
            allOpen = !allOpen;
        }
        updateExpandToggle();
    }

    /** Opens a field and every field inside it. */
    private void expandUnder(DefaultMutableTreeNode n) {
        values.expandPath(new TreePath(n.getPath()));
        for (int i = 0; i < n.getChildCount(); i++) {
            DefaultMutableTreeNode c = (DefaultMutableTreeNode) n.getChildAt(i);
            if (!c.isLeaf()) expandUnder(c);
        }
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
            showFind(true);
        }
    }

    /** Find on the value's toolbar: shows (or hides) the field filter. Hidden, it filters nothing. */
    private final FlowButton findButton = new FlowButton("Find", AllIcons.Actions.Find, FlowButton.Style.OUTLINE);
    private final JPanel filterRow = new JPanel();

    private void showFind(boolean show) {
        filterRow.setVisible(show);
        findButton.setSelected(show);
        if (show) {
            valueFilter.requestFocusInWindow();
            valueFilter.selectText();
        } else if (!valueFilter.getText().isEmpty()) {
            valueFilter.setText(""); // hidden: every field shows again
        }
        valueTools.revalidate();
        valueTools.repaint();
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

    /** A value added by hand at a line: its file, line, class, method and flow are the line's. */
    private void newValueAt(FlowSnapshot like) {
        String[] f = fieldDialog("New Value at " + like.where(), "", "", true);
        if (f == null || f[0].isEmpty()) return;
        FlowSnapshot s = new FlowSnapshot();
        s.name = f[0];
        s.flowName = like.flowName;
        s.fileUrl = like.fileUrl;
        s.fileName = like.fileName;
        s.className = like.className;
        s.methodName = like.methodName;
        s.line = like.line;
        FlowValue v = FlowValueText.fromJson(f[0], f[1]);
        if (v == null) {
            v = new FlowValue(f[0]);
            v.value = f[1].strip();
        }
        v.type = "added by hand";
        s.value = v;
        store.add(s);
        select(s);
        status.setText("Added " + s.name + " at " + like.where() + ". Add, edit or remove its fields on the right.");
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
        // what else is picked, outside the picked flows (they take everything in them)
        List<Group> groupsPicked = new ArrayList<>(pickedGroups());
        groupsPicked.removeIf(g -> flowsPicked.contains(g.flowName()));
        List<FlowSnapshot> list = new ArrayList<>(selectedSnapshots());
        list.removeIf(s -> flowsPicked.contains(s.flowName));
        if (!flowsPicked.isEmpty()) { // flows selected: the flows go (an empty one without asking)
            deleteFlowsNow(flowsPicked);
            if (list.isEmpty() && groupsPicked.isEmpty()) return;
        }
        if (list.isEmpty() && groupsPicked.isEmpty()) return;
        String group = selectedGroupName();
        if (group != null && !list.isEmpty() && Messages.showYesNoDialog(project, "Delete " + list.size() + (list.size() == 1 ? " value" : " values")
                + (groupsPicked.isEmpty() ? " of “" + group + "”" : " and " + (groupsPicked.size() == 1 ? "the group “" + groupsPicked.get(0).group() + "”"
                : groupsPicked.size() + " groups")) + "? The values go to the Recycle Bin.", "Delete", "Delete", "Cancel",
                Messages.getQuestionIcon()) != Messages.YES) {
            return;
        }
        if (!list.isEmpty()) store.delete(list);
        for (Group g : groupsPicked) store.deleteGroup(g.flowName(), g.group());
        status.setText("Moved " + list.size() + (list.size() == 1 ? " value" : " values") + " to the Recycle Bin"
                + (groupsPicked.isEmpty() ? "." : groupsPicked.size() == 1 ? " and deleted the group “" + groupsPicked.get(0).group() + "”."
                : " and deleted " + groupsPicked.size() + " groups."));
    }

    /** The groups' own rows picked in the list. */
    private List<Group> pickedGroups() {
        List<Group> out = new ArrayList<>();
        TreePath[] paths = leftShowsResults() ? null : snapshots.getSelectionPaths();
        if (paths != null) {
            for (TreePath p : paths) {
                if (((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof Group g && g.isGroup()) out.add(g);
            }
        }
        return out;
    }

    /** Delete Group: an empty group goes at once; one with values asks, then its values go to the Recycle Bin. */
    private void deleteGroup(@Nullable Group g) {
        if (g == null || !g.isGroup()) return;
        List<FlowSnapshot> its = store.getSnapshots().stream()
                .filter(x -> x.flowName.equals(g.flowName()) && g.group().equals(x.group)).toList();
        if (!its.isEmpty() && Messages.showYesNoDialog(project, "Delete the group “" + g.group() + "” and its " + its.size()
                        + (its.size() == 1 ? " value" : " values") + "? The values go to the Recycle Bin.", "Delete Group",
                "Delete", "Cancel", Messages.getQuestionIcon()) != Messages.YES) {
            return;
        }
        store.delete(its);
        store.deleteGroup(g.flowName(), g.group());
        status.setText("Deleted the group “" + g.group() + "”" + (its.isEmpty() ? "." : " (" + its.size() + (its.size() == 1 ? " value" : " values")
                + " to the Recycle Bin)."));
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
            if (o instanceof Group g && g.isGroup()) {
                setIcon(AllIcons.Nodes.Folder);
                append(g.text(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                int n = 0;
                java.util.Enumeration<?> all = ((DefaultMutableTreeNode) value).depthFirstEnumeration();
                while (all.hasMoreElements()) if (((DefaultMutableTreeNode) all.nextElement()).getUserObject() instanceof FlowSnapshot) n++;
                append("   " + (n == 0 ? "empty · drag values here" : n + (n == 1 ? " value" : " values")), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                return;
            }
            if (o instanceof Group g) {
                setIcon(g.flow() ? FlowTheme.ICON_FLOWS : AllIcons.Debugger.Db_set_breakpoint);
                // the active flow (new values go there) and a line's current pass (the next save there joins it) in green
                boolean on = g.active() && !showingBin;
                append(g.text(), g.flow() ? (on ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, ACTIVE) : SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        : on ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, ACTIVE) : SimpleTextAttributes.REGULAR_ATTRIBUTES);
                if (on && !g.flow()) append("   ● current pass", new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, ACTIVE));
                if (g.flow()) {
                    if (((DefaultMutableTreeNode) value).getChildCount() == 0) append("   no values yet", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                    if (g.active() && !showingBin) append("   ● new values go here", new SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, EDITED));
                } else if (g.lineKey() != null) {
                    String name = store.getLineName(g.lineKey());
                    if (!name.isEmpty()) append("  —  " + name, new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, EDITED));
                }
            } else if (o instanceof FlowSnapshot s) {
                setIcon(AllIcons.Debugger.Watch);
                append(s.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                if (!s.value.type.isEmpty()) append("  " + s.value.type, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append("    " + time(showingBin ? s.deletedAt : s.savedAt), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                if (FlowDebugStore.isChanged(s)) append("  edited", new SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, EDITED));
                if (s.locked) append("  🔒 locked", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
            }
        }
    }

    private static String gray() {
        java.awt.Color c = UIUtil.getContextHelpForeground();
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }

    private static String esc(String t) {
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** "04 Nov 15:47:20". */
    private static String fullTime(long millis) {
        return new java.text.SimpleDateFormat("dd MMM HH:mm:ss").format(new java.util.Date(millis));
    }

    /** A value's text in the editor's colors: "text" green, 12 blue, true / false / null as keywords. */
    private static SimpleTextAttributes valueColor(String text) {
        String t = text.strip();
        if (t.startsWith("\"") || t.startsWith("'")) return STRING_VALUE;
        if (t.equals("true") || t.equals("false") || t.equals("null")) return KEYWORD_VALUE;
        if (t.matches("-?\\d[\\d_]*(\\.\\d+)?([eE][-+]?\\d+)?[lLfFdD]?")) return NUMBER_VALUE;
        return SimpleTextAttributes.REGULAR_ATTRIBUTES;
    }

    private final class ValueRenderer extends ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
            Object o = ((DefaultMutableTreeNode) value).getUserObject();
            if (!(o instanceof Field f)) return;
            String q = search.getText();
            FlowValue v = f.value();
            // As the debugger's Variables / Evaluate show it: "cities = {ArrayList@63648} size = 1", "cityName = null".
            // Bookmarked: a blue bookmark; the mouse on the icon: a bookmark to click.
            boolean top = f.path().isEmpty();
            boolean element = v.name.matches("\\[?\\d+]?");
            setIcon(f.pinned() ? new FlowJsonTree.BookmarkIcon(true) : row == valueHover ? new FlowJsonTree.BookmarkIcon(false)
                    : FlowVariablesLook.icon(top));
            append(element ? v.name.replaceAll("[\\[\\]]", "") : v.name, FlowValueSearch.contains(v.name, q) ? MATCH
                    : SimpleTextAttributes.REGULAR_ATTRIBUTES);
            append(" = ", SimpleTextAttributes.REGULAR_ATTRIBUTES);
            boolean edited = f.edited();
            String text = FlowVariablesLook.text(v.value, v.type);
            if (v.children.isEmpty() && text.startsWith("{") && com.example.flowtracker.model.FlowValueShape.hideInternals()) text = cleanText(text);
            if (!v.children.isEmpty()) { // {Type} only before what has fields, as IntelliJ shows it
                String type = FlowVariablesLook.type(v.type);
                if (!type.isEmpty()) append(type + (text.isEmpty() ? "" : " "), FlowValueSearch.contains(v.type, q) ? MATCH
                        : SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
            append(text, FlowValueSearch.contains(text, q) ? MATCH
                    : edited ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, EDITED) : valueColor(text));
            if (edited && !top) append("  ✎ edited", new SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, EDITED));
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
