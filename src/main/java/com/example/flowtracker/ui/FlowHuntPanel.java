package com.example.flowtracker.ui;

import com.example.flowtracker.debug.FlowDataRecorder;
import com.example.flowtracker.debug.FlowValueHunt;
import com.example.flowtracker.model.FlowDebugStore;
import com.example.flowtracker.model.FlowHuntStore;
import com.example.flowtracker.model.FlowValueSearch;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogBuilder;
import com.intellij.openapi.ui.InputValidatorEx;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.ClassUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.content.Content;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The Stop at Value tab: the records (hunts for values, and watches on a field or object), each started and stopped
 * on its own, and every place each one stopped the debugger, with the details of that stop (where, which thread, what
 * matched, and the variables there). Add, edit, start / stop, rename and delete from the toolbar, a right click or
 * the keyboard.
 */
public final class FlowHuntPanel extends JPanel implements Disposable {
    public static final String TAB = "Stop at Value";

    private final Project project;
    private final FlowHuntStore store;
    private final FlowValueHunt hunting;
    private final DefaultListModel<FlowHuntStore.Hunt> huntModel = new DefaultListModel<>();
    private final JBList<FlowHuntStore.Hunt> hunts = new JBList<>(huntModel);
    private final StopsModel stopsModel = new StopsModel();
    private final JBTable stops = new JBTable(stopsModel);
    /** The stops shown: filtered by the text, the thread and the file picked (and sorted by a column). */
    private final javax.swing.table.TableRowSorter<StopsModel> sorter = new javax.swing.table.TableRowSorter<>(stopsModel);
    private final com.intellij.ui.SearchTextField stopFilter = new com.intellij.ui.SearchTextField(false);
    private final FlowButton threadFilter = new FlowButton("All threads", AllIcons.Debugger.Threads, FlowButton.Style.OUTLINE);
    private final FlowButton fileFilter = new FlowButton("All files", AllIcons.FileTypes.Any_type, FlowButton.Style.OUTLINE);
    private final JBLabel shownCount = new JBLabel();
    private final FlowButton toDebugData = new FlowButton("To Debug Data", AllIcons.Actions.MenuSaveall, FlowButton.Style.OUTLINE);
    /** The stops' order: the newest first (the latest find at the top) or the oldest first; remembered. */
    private final FlowButton order = new FlowButton("", AllIcons.ObjectBrowser.Sorted, FlowButton.Style.OUTLINE);
    private boolean newestFirst = com.intellij.ide.util.PropertiesComponent.getInstance().getBoolean("TraceWeave.StopAtValue.newestFirst", true);
    private @Nullable String threadPicked, filePicked;
    private final JBLabel title = new JBLabel(), subtitle = new JBLabel(), status = new JBLabel();
    /** The selected record's values: each switched on or off, removed; and + Add value. */
    private final JPanel valueChips = new JPanel(new com.intellij.util.ui.WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4)));
    private final FlowButton add = new FlowButton("Add Stop at Value", AllIcons.General.Add, FlowButton.Style.PRIMARY);
    private final FlowButton startStop = new FlowButton("Start", AllIcons.Actions.Execute, FlowButton.Style.OUTLINE);
    private static final java.awt.Color START_GREEN = new com.intellij.ui.JBColor(new java.awt.Color(0x1F8A3B), new java.awt.Color(0x5FB865));
    private static final java.awt.Color STOP_RED = new com.intellij.ui.JBColor(new java.awt.Color(0xC9372C), new java.awt.Color(0xF0625A));
    private final FlowButton edit = new FlowButton("Edit", AllIcons.Actions.Edit, FlowButton.Style.OUTLINE);
    private final FlowButton rename = new FlowButton("Rename", FlowTheme.ICON_NOTE, FlowButton.Style.OUTLINE);
    private final FlowButton delete = new FlowButton("Delete", FlowTheme.ICON_DELETE, FlowButton.Style.OUTLINE);
    private final FlowButton binButton = new FlowButton("Recycle Bin", FlowTheme.ICON_RECYCLE, FlowButton.Style.OUTLINE);
    /** A stop's details: when, where, thread, what matched, and the variables there. */
    private final JPanel details = new JPanel(new CardLayout());
    private final JBLabel stopWhen = new JBLabel(), stopWhere = new JBLabel(), stopMethod = new JBLabel(), stopThread = new JBLabel();
    private final JBTextArea stopFound = new JBTextArea();
    private final FlowCodeView snapshot;
    private final JBLabel snapshotNote = new JBLabel();
    private final Runnable changed = () -> SwingUtilities.invokeLater(this::reload);

    public FlowHuntPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.store = FlowHuntStore.get(project);
        this.hunting = FlowValueHunt.get(project);
        this.snapshot = new FlowCodeView(project, this);
        snapshotTree.setPins(store::pinned, key -> {
            store.togglePin(key);
            snapshotTree.refresh();
        });
        FlowValueHunt.setShower(FlowHuntPanel::showHunt);

        // ---- the toolbar
        add.setToolTipText("Stop the debugger where a value shows up (Ctrl+N); ▾ to watch a field or object instead");
        add.addActionListener(e -> addValueHunt(project));
        add.setDropDown("Stop at Value, or Watch a Field or Object", () -> addMenu(new FlowPopupMenu().header("ADD")).showBelow(add));
        startStop.addActionListener(e -> startOrStopSelected());
        edit.setToolTipText("Change the selected record's values and how it looks (Enter)");
        edit.addActionListener(e -> editSelected());
        rename.setToolTipText("Rename the selected record (F2)");
        rename.addActionListener(e -> renameSelected());
        delete.setToolTipText("Delete the selected records and the places they stopped (Delete)");
        delete.addActionListener(e -> deleteSelected());
        FlowToolbar bar = new FlowToolbar();
        bar.addGroup(false, 2, add);
        bar.addGroup(false, 1, startStop, edit, rename, delete);
        binButton.setToolTipText("Records and stops deleted: restore them, or delete them for good");
        binButton.addActionListener(e -> new FlowHuntBinDialog(project).show());
        bar.addTrailingGroup(1, binButton);
        bar.setBorder(JBUI.Borders.emptyBottom(10));

        // ---- the records
        hunts.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        hunts.setCellRenderer(new HuntRenderer());
        hunts.setExpandableItemsEnabled(false); // no floating copy of a long card over the next section: it wraps instead
        hunts.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                hunts.setFixedCellHeight(10); // the cards wrap to the new width: their heights are worked out again
                hunts.setFixedCellHeight(-1);
            }
        });
        hunts.setBackground(FlowTheme.CARD_BG);
        hunts.getEmptyText().setText("Nothing to stop at yet");
        hunts.getEmptyText().appendSecondaryText("Add Stop at Value (Ctrl+N), or watch a field or object",
                com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES, null);
        hunts.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !rebuilding) showSelected();
        });
        hunts.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) editSelected();
            }
            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int i = hunts.locationToIndex(e.getPoint());
                if (i >= 0 && !hunts.isSelectedIndex(i)) hunts.setSelectedIndex(i);
                huntMenu().show(hunts, e.getX(), e.getY());
            }
        });

        // ---- the stops of the selected record
        stops.setShowGrid(false);
        stops.setRowHeight(JBUI.scale(26));
        stops.setBackground(FlowTheme.CARD_BG);
        stops.getEmptyText().setText("It hasn't stopped anywhere yet");
        // "#" with room for the sort arrow beside it (the table is sorted by it: newest or oldest first)
        javax.swing.table.TableColumn number = stops.getColumnModel().getColumn(0);
        number.setMinWidth(JBUI.scale(56));
        number.setMaxWidth(JBUI.scale(64));
        number.setPreferredWidth(JBUI.scale(58));
        javax.swing.table.DefaultTableCellRenderer centered = new javax.swing.table.DefaultTableCellRenderer();
        centered.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        number.setCellRenderer(centered);
        stops.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        stops.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showStop();
        });
        stops.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) openStop();
            }
            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = stops.rowAtPoint(e.getPoint());
                if (row >= 0 && !stops.isRowSelected(row)) stops.setRowSelectionInterval(row, row);
                stopMenu().show(stops, e.getX(), e.getY());
            }
        });

        title.setFont(JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD, JBUI.Fonts.label().getSize2D() + 1f));
        subtitle.setForeground(FlowTheme.FG_MUTED);
        // long names and values are cut ("…") instead of keeping the tab wide
        for (JComponent c : new JComponent[]{title, subtitle, status}) c.setMinimumSize(new Dimension(0, 0));
        JPanel head = new JPanel(new BorderLayout(0, JBUI.scale(2)));
        head.setOpaque(false);
        head.add(title, BorderLayout.NORTH);
        head.add(subtitle, BorderLayout.CENTER);
        valueChips.setOpaque(false);
        valueChips.setBorder(JBUI.Borders.empty(6, 0, 2, 0));
        head.add(valueChips, BorderLayout.SOUTH);
        head.setBorder(JBUI.Borders.empty(4, 6, 8, 6));

        JBSplitter stopsSplit = new JBSplitter(true, 0.42f);
        stopsSplit.setHonorComponentsMinimumSize(false);
        JPanel stopsArea = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        stopsArea.setOpaque(false);
        stopsArea.add(buildFilters(), BorderLayout.NORTH);
        stopsArea.add(FlowCard.plain(FlowScroll.wheel(new JBScrollPane(stops))), BorderLayout.CENTER);
        stopsSplit.setFirstComponent(stopsArea);
        stopsSplit.setSecondComponent(buildDetails());
        JPanel right = new JPanel(new BorderLayout());
        right.setOpaque(false);
        right.add(head, BorderLayout.NORTH);
        right.add(stopsSplit, BorderLayout.CENTER);

        JBSplitter split = new JBSplitter(false, 0.32f);
        split.setHonorComponentsMinimumSize(false);
        split.setFirstComponent(FlowCard.of(FlowScroll.wheel(new JBScrollPane(hunts))));
        split.setSecondComponent(FlowCard.of(right));
        split.setDividerWidth(JBUI.scale(8));
        split.setOpaque(false);
        split.getDivider().setOpaque(false);

        status.setForeground(UIUtil.getContextHelpForeground());
        status.setBorder(JBUI.Borders.empty(6, 4, 0, 4));
        setBackground(FlowTheme.PANEL_BG);
        setBorder(JBUI.Borders.empty(10));
        add(bar, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        installKeys();
        store.addListener(changed);
        hunting.addListener(changed);
        reload();
    }

    // ---------- keys and menus ----------

    private void installKeys() {
        key(this, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, KeyStroke.getKeyStroke(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK),
                "add", () -> addValueHunt(project));
        key(this, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, 0), "add2", () -> addValueHunt(project));
        key(hunts, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "edit", this::editSelected);
        key(hunts, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK), "edit2", this::editSelected);
        key(hunts, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "startStop", this::startOrStopSelected);
        key(hunts, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "rename", this::renameSelected);
        key(hunts, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete", this::deleteSelected);
        key(stops, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open", this::openStop);
        key(stops, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteStops", this::deleteStops);
        key(stops, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                "toDebugData", this::copyToDebugData);
    }

    private static void key(JComponent c, int when, KeyStroke k, String name, Runnable r) {
        c.getInputMap(when).put(k, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        });
    }

    /** The two ways to add: Stop at Value and Watch a Field or Object. */
    private FlowPopupMenu addMenu(FlowPopupMenu m) {
        return m.item(AllIcons.Actions.Find, "Stop at Value…", "Stop where a value shows up, anywhere in the code", "Ctrl+N",
                        () -> addValueHunt(project))
                .item(AllIcons.Debugger.Db_field_breakpoint, "Watch a Field or Object…", "Stop where a field is set, changes or becomes null",
                        null, () -> addWatch(project, null));
    }

    private FlowPopupMenu huntMenu() {
        FlowPopupMenu m = new FlowPopupMenu();
        List<FlowHuntStore.Hunt> picked = hunts.getSelectedValuesList();
        FlowHuntStore.Hunt h = picked.size() == 1 ? picked.get(0) : null;
        if (h != null) {
            boolean on = isRunning(h);
            m.header(StringUtil.shortenTextWithEllipsis(h.name, 44, 0).toUpperCase());
            m.item(on ? AllIcons.Actions.Suspend : AllIcons.Actions.Execute, on ? "Stop" : "Start",
                    on ? "Stop looking; the record and its stops stay" : "Look for it now (the record looking now stops)", "Space",
                    this::startOrStopSelected);
            m.item(AllIcons.Actions.Edit, "Edit…", h.isWatch() ? "The field and when to stop" : "The values and how to look", "Enter",
                    this::editSelected);
            m.item(FlowTheme.ICON_NOTE, "Rename…", null, "F2", this::renameSelected);
            m.separator();
        } else if (!picked.isEmpty()) {
            m.header(picked.size() + " RECORDS");
        }
        if (!picked.isEmpty()) {
            int stopsGone = picked.stream().mapToInt(x -> x.stops.size()).sum();
            m.danger(FlowTheme.ICON_DELETE, picked.size() == 1 ? "Delete" : "Delete " + picked.size() + " Records",
                    stopsGone == 0 ? null : "With " + stopsGone + (stopsGone == 1 ? " stop" : " stops"), "Delete", this::deleteSelected);
            m.separator();
        }
        return addMenu(m.header("ADD"));
    }

    private FlowPopupMenu stopMenu() {
        FlowPopupMenu m = new FlowPopupMenu();
        int n = stops.getSelectedRowCount();
        FlowHuntStore.Stop s = selectedStop();
        if (s != null) {
            m.header(("Stop #" + (modelRow(stops.getSelectedRow()) + 1) + " · " + s.fileName + ":" + s.line).toUpperCase());
            m.item(AllIcons.Actions.EditSource, "Open Line", s.method.isEmpty() ? null : s.method, "Enter", this::openStop);
            m.item(FlowTheme.ICON_COPY, "Copy Details", "When, where, thread, what matched and the variables", null, () -> copyStop(s));
            m.separator();
        } else if (n > 1) {
            m.header(n + " STOPS");
        }
        if (n > 0) {
            String flow = com.example.flowtracker.model.FlowStore.get(project).getActiveFlowName();
            m.item(AllIcons.Actions.MenuSaveall, n == 1 ? "Copy to Debug Data" : "Copy " + n + " Stops to Debug Data",
                    "Their variables, into the flow “" + flow + "”", "Ctrl+Shift+D", this::copyToDebugData);
            m.separator();
            m.danger(FlowTheme.ICON_DELETE, n == 1 ? "Delete Stop" : "Delete " + n + " Stops", null, "Delete", this::deleteStops);
        }
        return m;
    }

    /** A stop's details as text, on the clipboard. */
    private void copyStop(FlowHuntStore.Stop s) {
        String text = "When: " + new SimpleDateFormat("dd MMM yyyy HH:mm:ss").format(new Date(s.at)) + "\n"
                + "Where: " + s.fileName + ":" + s.line + (s.method.isEmpty() ? "" : "  " + s.method) + "\n"
                + "Thread: " + s.thread + "\n"
                + "Found:\n  " + String.join("\n  ", s.matches) + "\n"
                + (s.snapshot.isEmpty() ? "" : "Variables:\n" + s.snapshot + "\n");
        com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(new java.awt.datatransfer.StringSelection(text));
    }

    // ---------- adding, editing, starting ----------

    /** Starts Stop at Value (asking for the values and how), or stops every record looking. */
    public static void startOrStop(Project project) {
        FlowValueHunt hunt = FlowValueHunt.get(project);
        if (hunt.isHunting()) hunt.stop();
        else addValueHunt(project);
    }

    /** Add Stop at Value: a new record (or the one that looks for the same values), started. */
    public static void addValueHunt(Project project) {
        valueDialog(project, null, "");
    }

    /** Add Stop at Value with a value typed in already (e.g. the text selected in the editor). */
    public static void addValueHunt(Project project, String value) {
        valueDialog(project, null, value);
    }

    private static boolean isRunning(Project project, FlowHuntStore.Hunt h) {
        return FlowValueHunt.get(project).isRunning(h);
    }

    private boolean isRunning(FlowHuntStore.Hunt h) {
        return isRunning(project, h);
    }

    /** Starts a record; says why when it can't. */
    private static void start(Project project, FlowHuntStore.Hunt h) {
        FlowDataRecorder recorder = FlowDataRecorder.get(project);
        if (!FlowValueHunt.Mode.of(h.mode).waits() && recorder.isCapturing() && FlowDebugStore.get(project).isCaptureEveryLine()) {
            recorder.stop(); // both would step the debugger
        }
        String error = FlowValueHunt.get(project).start(h);
        if (error != null) {
            Messages.showInfoMessage(project, error, "Stop at Value");
            return;
        }
        showHunt(project, h.id);
    }

    /**
     * The values and how to look (a new record, or {@code existing} edited). A new one that looks for what a record
     * looks for already starts that record instead.
     */
    private static void valueDialog(Project project, @Nullable FlowHuntStore.Hunt existing, String initial) {
        FlowValueHunt hunt = FlowValueHunt.get(project);
        FlowHuntStore store = FlowHuntStore.get(project);
        FlowHuntStore.Hunt base = existing;
        JBTextArea values = new JBTextArea(4, 40);
        values.setFont(JBUI.Fonts.create(java.awt.Font.MONOSPACED, JBUI.Fonts.label().getSize()));
        values.getEmptyText().setText("One value per line, e.g. BK-1042, asha@example.com or /order.*Id/");
        if (base != null) values.setText(String.join("\n", base.values));
        else if (!initial.isBlank()) values.setText(initial.strip());
        JBRadioButton any = new JBRadioButton("Any of them", base == null || !base.all);
        JBRadioButton both = new JBRadioButton("All of them, in the same stop", base != null && base.all);
        ButtonGroup g1 = new ButtonGroup();
        g1.add(any);
        g1.add(both);
        JPanel match = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0));
        match.add(new JBLabel("Stop where it finds:"));
        match.add(any);
        match.add(both);

        FlowValueHunt.Mode selected = base != null ? FlowValueHunt.Mode.of(base.mode) : FlowValueHunt.Mode.ANYWHERE;
        ButtonGroup g2 = new ButtonGroup();
        JPanel modes = new JPanel();
        modes.setLayout(new BoxLayout(modes, BoxLayout.Y_AXIS));
        Map<FlowValueHunt.Mode, JBRadioButton> radios = new EnumMap<>(FlowValueHunt.Mode.class);
        JBCheckBox keep = new JBCheckBox("Keep looking after a find: every find is listed (resume the debugger to go on)",
                base == null || base.keepGoing);
        for (FlowValueHunt.Mode m : FlowValueHunt.Mode.forValues()) {
            JBRadioButton r = new JBRadioButton(m.label, m == selected);
            r.addActionListener(e -> keep.setEnabled(m.waits()));
            g2.add(r);
            radios.put(m, r);
            JBLabel tip = new JBLabel("<html><div style='width:440px'>" + m.tip + "</div></html>");
            tip.setForeground(UIUtil.getContextHelpForeground());
            tip.setBorder(JBUI.Borders.empty(0, 24, 8, 0));
            modes.add(r);
            modes.add(tip);
        }
        keep.setEnabled(selected.waits());
        keep.setBorder(JBUI.Borders.empty(0, 24, 6, 0));
        modes.add(keep);

        JBTextField name = new JBTextField(base != null ? base.name : store.nextName(false));
        name.getEmptyText().setText(store.nextName(false));
        JPanel nameRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        nameRow.add(new JBLabel("Name:"), BorderLayout.WEST);
        nameRow.add(name, BorderLayout.CENTER);
        nameRow.setBorder(JBUI.Borders.emptyBottom(10));
        JPanel form = new JPanel(new BorderLayout(0, JBUI.scale(10)));
        JPanel top = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        JPanel heading = new JPanel(new BorderLayout());
        heading.add(nameRow, BorderLayout.NORTH);
        top.add(heading, BorderLayout.NORTH);
        heading.add(new JBLabel("Stop the debugger where these values are. A value is found when a variable's name, type or value "
                + "contains it (\"ll\" finds \"Hello world\"), or it matches a /regex/:"), BorderLayout.CENTER);
        top.add(FlowScroll.wheel(new JBScrollPane(values)), BorderLayout.CENTER);
        top.add(match, BorderLayout.SOUTH);
        form.add(top, BorderLayout.NORTH);
        form.add(modes, BorderLayout.CENTER);
        form.add(notes("Start the app in Debug first (every module you want to look in, e.g. the web app and its API). "
                + "The first two ways need no breakpoints; the last ones need your breakpoints or a paused debugger. "
                + "Variables are searched 4 levels deep (fields, map entries, list elements)."), BorderLayout.SOUTH);

        DialogBuilder b = new DialogBuilder(project);
        b.setTitle(existing == null ? "Add Stop at Value" : "Edit " + existing.name);
        b.setCenterPanel(form);
        b.setPreferredFocusComponent(values);
        b.addOkAction().setText(existing == null ? "Add and Start" : "Save");
        b.addCancelAction();
        if (!b.showAndGet()) return;
        List<String> vals = FlowValueHunt.values(values.getText());
        for (String v : vals) {
            String error = FlowValueSearch.error(v);
            if (error != null) {
                Messages.showInfoMessage(project, error + " (" + v + ")", "Stop at Value");
                return;
            }
        }
        if (vals.isEmpty()) {
            Messages.showInfoMessage(project, "Type the value (or values, one per line) to find.", "Stop at Value");
            return;
        }
        FlowValueHunt.Mode mode = FlowValueHunt.Mode.ANYWHERE;
        for (var e : radios.entrySet()) if (e.getValue().isSelected()) mode = e.getKey();
        save(project, store, existing, name.getText(), vals, both.isSelected(), mode.name(), keep.isSelected(), "", "", hunt);
    }

    /**
     * Saves a dialog's choices: an edited record (restarted when it was looking), or a new one (or the record that
     * looks for the same, with these choices), started.
     */
    private static void save(Project project, FlowHuntStore store, @Nullable FlowHuntStore.Hunt existing, String name, List<String> vals,
                             boolean all, String mode, boolean keep, String target, String when, FlowValueHunt hunt) {
        if (existing != null) {
            boolean wasRunning = isRunning(project, existing);
            if (wasRunning) hunt.stop(existing); // it looks again with the new settings
            store.update(existing, vals, all, mode, keep, target, when);
            if (!name.isBlank() && !name.strip().equals(existing.name)) store.rename(existing, name);
            if (wasRunning) start(project, existing);
            else showHunt(project, existing.id);
            return;
        }
        FlowHuntStore.Hunt same = store.same(vals, mode, target, when);
        if (same != null) {
            store.update(same, vals, all, mode, keep, target, when);
            if (!isRunning(project, same)) start(project, same);
            else showHunt(project, same.id);
            return;
        }
        start(project, store.create(name, vals, all, mode, keep, target, when));
    }

    /** Watch a Field or Object: which field (or object), and when to stop (a new record, or {@code existing} edited). */
    static void addWatch(Project project, @Nullable FlowHuntStore.Hunt existing) {
        addWatch(project, existing, existing != null ? existing.targetText() : fieldAtCaret(project));
    }

    /** @param initial the field typed in at first, e.g. "BookingDto.status" (from the editor's right-click menu) */
    public static void addWatch(Project project, @Nullable FlowHuntStore.Hunt existing, String initial) {
        addWatch(project, existing, initial, existing != null && existing.isDeep());
    }

    /** @param deep the object's data is ticked at first (a local variable's object, from the editor's right-click menu) */
    public static void addWatch(Project project, @Nullable FlowHuntStore.Hunt existing, String initial, boolean deep) {
        FlowWatchDialog d = new FlowWatchDialog(project, existing, initial, deep);
        if (!d.showAndGet()) return;
        String resolved = d.resolved();
        if (resolved.startsWith("!")) return;
        save(project, FlowHuntStore.get(project), existing, d.name(), d.values(), false, FlowValueHunt.Mode.FIELD.name(), d.keepGoing(),
                resolved, d.when(), FlowValueHunt.get(project));
    }

    /**
     * "Class.field" or "Class" (a short or full name) as the watch's target: the class's JVM name and "#field" (the
     * class that declares it); "!why" when it can't be found.
     */
    static String resolveTarget(Project project, String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) return "!Type the field (Class.field) or the class to watch.";
        java.util.regex.Matcher lm = LOCAL.matcher(t);
        if (lm.matches()) return resolveLocal(project, lm.group(1), lm.group(2), lm.group(3), lm.group(4));
        java.util.regex.Matcher mm = METHOD.matcher(t);
        if (mm.matches()) {
            PsiClass c = findClass(project, mm.group(1));
            if (c == null) return "!No class " + mm.group(1) + " in the project.";
            if (c.findMethodsByName(mm.group(2), false).length == 0) return "!" + c.getName() + " has no method " + mm.group(2) + "().";
            return "!Pick a variable of " + mm.group(2) + "() (or its return value) in the list below.";
        }
        PsiClass whole = findClass(project, t);
        if (whole != null) return com.example.flowtracker.compat.FlowCompat.jvmName(whole) == null ? "!" + t + " can't be watched." : com.example.flowtracker.compat.FlowCompat.jvmName(whole);
        int dot = t.lastIndexOf('.');
        if (dot <= 0) return "!No class " + t + " in the project.";
        PsiClass cls = findClass(project, t.substring(0, dot));
        if (cls == null) return "!No class " + t.substring(0, dot) + " in the project.";
        PsiField f = cls.findFieldByName(t.substring(dot + 1), true);
        if (f == null) return "!" + cls.getName() + " has no field " + t.substring(dot + 1) + ".";
        PsiClass owner = f.getContainingClass() == null ? cls : f.getContainingClass();
        String jvm = com.example.flowtracker.compat.FlowCompat.jvmName(owner);
        return jvm == null ? "!" + t + " can't be watched." : jvm + "#" + f.getName();
    }

    /** "BookingWorkflow.book()": a method, to pick one of its variables. */
    static final java.util.regex.Pattern METHOD = java.util.regex.Pattern.compile("([^()]+)\\.([\\w$]+)\\(\\)");

    /**
     * A method's parameters, local variables and (when it returns something) "return", each as {name, type, kind}
     * with kind "parameter", "variable" or "return"; overloads together, each name once. Empty when there is none.
     */
    static List<String[]> methodVariables(Project project, String className, String methodName) {
        PsiClass cls = findClass(project, className);
        List<String[]> out = new ArrayList<>();
        if (cls == null) return out;
        java.util.Set<String> names = new java.util.HashSet<>();
        String returns = null;
        for (com.intellij.psi.PsiMethod m : cls.findMethodsByName(methodName, false)) {
            if (m.getBody() == null) continue;
            for (com.intellij.psi.PsiParameter p : m.getParameterList().getParameters()) {
                if (names.add(p.getName())) out.add(new String[]{p.getName(), p.getType().getPresentableText(), "parameter"});
            }
            for (com.intellij.psi.PsiLocalVariable l : PsiTreeUtil.findChildrenOfType(m.getBody(), com.intellij.psi.PsiLocalVariable.class)) {
                if (names.add(l.getName())) out.add(new String[]{l.getName(), l.getType().getPresentableText(), "variable"});
            }
            if (returns == null && m.getReturnType() != null && !com.intellij.psi.PsiTypes.voidType().equals(m.getReturnType())) {
                returns = m.getReturnType().getPresentableText();
            }
        }
        if (returns != null) out.add(new String[]{"return", returns, "return"});
        return out;
    }

    /** "BookingWorkflow.book().booking": a local variable (or a parameter) of a method; "….return": what it returns. */
    /**
     * "BookingWorkflow.book().booking", and a path into the object it holds after it: ".email", ".getEmail()",
     * ".setEmail()" (a getter or setter is its property: the field), ".traveller.name"…
     */
    static final java.util.regex.Pattern LOCAL = java.util.regex.Pattern.compile(
            "([^()]+)\\.([\\w$]+)\\(\\)\\.([\\w$]+)((?:\\.[\\w$]+(?:\\(\\))?)*)");

    /** A path's piece as a field's name: "email", "getEmail()" / "setEmail()" / "isActive()" → "email" / "active". */
    static String property(String piece) {
        String p = piece.endsWith("()") ? piece.substring(0, piece.length() - 2) : piece;
        if (!piece.endsWith("()")) return p;
        for (String pre : new String[]{"get", "set", "is"}) {
            if (p.length() > pre.length() && p.startsWith(pre) && Character.isUpperCase(p.charAt(pre.length()))) {
                String rest = p.substring(pre.length());
                return rest.length() > 1 && Character.isUpperCase(rest.charAt(1)) ? rest : Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return p;
    }

    /** The type a method's variable (or "return") has; null when there is none. */
    static @Nullable com.intellij.psi.PsiType variableType(Project project, String className, String methodName, String variable) {
        PsiClass cls = findClass(project, className);
        if (cls == null) return null;
        for (com.intellij.psi.PsiMethod m : cls.findMethodsByName(methodName, false)) {
            if (m.getBody() == null) continue;
            if (variable.equals("return")) {
                if (m.getReturnType() != null && !com.intellij.psi.PsiTypes.voidType().equals(m.getReturnType())) return m.getReturnType();
                continue;
            }
            for (com.intellij.psi.PsiParameter p : m.getParameterList().getParameters()) if (p.getName().equals(variable)) return p.getType();
            for (com.intellij.psi.PsiLocalVariable l : PsiTreeUtil.findChildrenOfType(m.getBody(), com.intellij.psi.PsiLocalVariable.class)) {
                if (l.getName().equals(variable)) return l.getType();
            }
        }
        return null;
    }

    /**
     * Walks a path (pieces as typed) from a type: the fields' names ("email", "traveller.name"), or "!why" when a
     * piece isn't a field of the object there. {@code into} gets the class the path ends in (null: not a class).
     */
    static String walk(@Nullable com.intellij.psi.PsiType type, List<String> pieces, @Nullable PsiClass[] into) {
        List<String> names = new ArrayList<>();
        com.intellij.psi.PsiType at = type;
        for (String piece : pieces) {
            PsiClass k = at instanceof com.intellij.psi.PsiClassType ct ? ct.resolve() : null;
            if (k == null) return "!" + (names.isEmpty() ? "It" : String.join(".", names)) + " holds no object with fields (" + piece + ").";
            String name = property(piece);
            PsiField f = k.findFieldByName(name, true);
            if (f == null) return "!" + k.getName() + " has no field " + name + (piece.endsWith("()") ? " (for " + piece + ")" : "") + ".";
            names.add(f.getName());
            at = f.getType();
        }
        if (into != null && into.length > 0) into[0] = at instanceof com.intellij.psi.PsiClassType ct ? ct.resolve() : null;
        return String.join(".", names);
    }

    /** The pieces of a path as typed: ".booking.getEmail()" → [booking, getEmail()] (empty pieces kept: still typing). */
    static List<String> pieces(String path) {
        if (path == null || path.isEmpty()) return new ArrayList<>();
        String p = path.startsWith(".") ? path.substring(1) : path;
        return new ArrayList<>(java.util.Arrays.asList(p.split("\\.", -1)));
    }

    /** {@link #resolveLocal(Project, String, String, String, String)} without a path. */
    static String resolveLocal(Project project, String className, String methodName, String variable) {
        return resolveLocal(project, className, methodName, variable, "");
    }

    /** The variable's target, and "#path" (the fields' names) when a path into its object comes after it. */
    static String resolveLocal(Project project, String className, String methodName, String variable, @Nullable String path) {
        String base = resolveVariable(project, className, methodName, variable);
        if (base.startsWith("!") || path == null || path.isEmpty()) return base;
        String fields = walk(variableType(project, className, methodName, variable), pieces(path), null);
        return fields.startsWith("!") ? fields : base + "#" + fields;
    }

    /**
     * A method's local variable or parameter as the watch's target: "~jvmClass#method#variable#from-to", the lines
     * (1-based) it is known on: from where it is declared to the end of its block (a parameter: the whole method).
     */
    private static String resolveVariable(Project project, String className, String methodName, String variable) {
        PsiClass cls = findClass(project, className);
        if (cls == null) return "!No class " + className + " in the project.";
        com.intellij.psi.PsiMethod[] methods = cls.findMethodsByName(methodName, false);
        if (methods.length == 0) return "!" + cls.getName() + " has no method " + methodName + "().";
        for (com.intellij.psi.PsiMethod m : methods) {
            if (m.getBody() == null) continue;
            if (variable.equals("return")) { // what it returns: looked at as it returns
                if (m.getReturnType() == null || com.intellij.psi.PsiTypes.voidType().equals(m.getReturnType())) continue;
                com.intellij.psi.PsiFile file = m.getContainingFile();
                com.intellij.openapi.editor.Document doc = file == null ? null : PsiDocumentManager.getInstance(project).getDocument(file);
                PsiClass owner = m.getContainingClass() == null ? cls : m.getContainingClass();
                String jvm = com.example.flowtracker.compat.FlowCompat.jvmName(owner);
                if (doc == null || jvm == null) return "!" + className + "." + methodName + "() can't be watched.";
                return "~" + jvm + "#" + methodName + "#return#" + (doc.getLineNumber(m.getBody().getTextRange().getStartOffset()) + 1)
                        + "-" + (doc.getLineNumber(m.getBody().getTextRange().getEndOffset()) + 1);
            }
            com.intellij.psi.PsiVariable v = null;
            for (com.intellij.psi.PsiParameter p : m.getParameterList().getParameters()) if (p.getName().equals(variable)) v = p;
            if (v == null) {
                for (com.intellij.psi.PsiLocalVariable l : PsiTreeUtil.findChildrenOfType(m.getBody(), com.intellij.psi.PsiLocalVariable.class)) {
                    if (l.getName().equals(variable)) { v = l; break; }
                }
            }
            if (v == null) continue;
            com.intellij.psi.PsiFile file = m.getContainingFile();
            com.intellij.openapi.editor.Document doc = file == null ? null : PsiDocumentManager.getInstance(project).getDocument(file);
            if (doc == null) return "!" + className + "." + methodName + "() can't be watched.";
            PsiElement scope = v instanceof com.intellij.psi.PsiParameter ? m.getBody()
                    : PsiTreeUtil.getParentOfType(v, com.intellij.psi.PsiCodeBlock.class);
            if (scope == null) scope = m.getBody();
            int from = doc.getLineNumber((v instanceof com.intellij.psi.PsiParameter ? m.getBody() : v).getTextRange().getStartOffset()) + 1;
            int to = doc.getLineNumber(scope.getTextRange().getEndOffset()) + 1;
            PsiClass owner = m.getContainingClass() == null ? cls : m.getContainingClass();
            String jvm = com.example.flowtracker.compat.FlowCompat.jvmName(owner);
            if (jvm == null) return "!" + className + "." + methodName + "() can't be watched.";
            return "~" + jvm + "#" + methodName + "#" + variable + "#" + from + "-" + to;
        }
        return "!" + cls.getName() + "." + methodName + "() has no variable or parameter " + variable + ".";
    }

    static @Nullable PsiClass findClass(Project project, String name) {
        if (name == null || name.isBlank()) return null;
        PsiClass k = JavaPsiFacade.getInstance(project).findClass(name, GlobalSearchScope.allScope(project));
        if (k != null) return k;
        PsiClass[] byShort = PsiShortNamesCache.getInstance(project).getClassesByName(name, GlobalSearchScope.projectScope(project));
        return byShort.length > 0 ? byShort[0] : null;
    }

    /** The field (or class) at the editor's caret, as "Class.field" ("" when there is none). */
    static String fieldAtCaret(Project project) {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) return "";
        return com.example.flowtracker.compat.FlowCompat.read(() -> {
            PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
            PsiElement at = file == null ? null : file.findElementAt(editor.getCaretModel().getOffset());
            if (at == null) return "";
            PsiField f = PsiTreeUtil.getParentOfType(at, PsiField.class, false);
            if (f == null && at.getParent() instanceof com.intellij.psi.PsiReferenceExpression r && r.resolve() instanceof PsiField rf) f = rf;
            if (f != null && f.getContainingClass() != null) return f.getContainingClass().getName() + "." + f.getName();
            PsiClass k = PsiTreeUtil.getParentOfType(at, PsiClass.class, false);
            return k == null || k.getName() == null ? "" : k.getName();
        });
    }

    private static JBLabel notes(String text) {
        JBLabel note = new JBLabel("<html><div style='width:480px'>" + text + "</div></html>");
        note.setForeground(UIUtil.getContextHelpForeground());
        if (FlowValueHunt.debugSessions() == 0) {
            note.setText("<html><div style='width:480px'><b>" + FlowValueHunt.NO_SESSION + "</b> You can add it now: it begins "
                    + "when the app starts in Debug.<br><br>" + text + "</div></html>");
        }
        return note;
    }

    private void startOrStopSelected() {
        FlowHuntStore.Hunt h = hunts.getSelectedValue();
        if (h == null) return;
        if (isRunning(h)) hunting.stop(h);
        else start(project, h);
    }

    private void editSelected() {
        FlowHuntStore.Hunt h = hunts.getSelectedValue();
        if (h == null) return;
        if (h.isWatch()) addWatch(project, h);
        else valueDialog(project, h, "");
    }

    /** The Stop at Value tab with a record selected (and its last stop). */
    static void showHunt(Project project, String huntId) {
        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow("TraceWeave");
        if (tw == null) return;
        for (Content c : tw.getContentManager().getContents()) {
            if (c.getComponent() instanceof FlowHuntPanel panel) {
                tw.getContentManager().setSelectedContent(c);
                tw.activate(() -> panel.select(huntId));
                return;
            }
        }
    }

    // ---------- the records and their stops ----------

    /** True while the records list is rebuilt: its selection comes and goes, the stops and their details stay. */
    private boolean rebuilding;

    private void reload() {
        List<String> picked = new ArrayList<>();
        for (FlowHuntStore.Hunt h : hunts.getSelectedValuesList()) picked.add(h.id);
        rebuilding = true;
        try {
            huntModel.clear();
            for (FlowHuntStore.Hunt h : store.hunts()) huntModel.addElement(h);
            List<Integer> again = new ArrayList<>();
            for (int i = 0; i < huntModel.size(); i++) if (picked.contains(huntModel.get(i).id)) again.add(i);
            if (!again.isEmpty()) hunts.setSelectedIndices(again.stream().mapToInt(Integer::intValue).toArray());
            else if (!huntModel.isEmpty() && hunts.getSelectedIndex() < 0) hunts.setSelectedIndex(0);
        } finally {
            rebuilding = false;
        }

        List<FlowHuntStore.Hunt> looking = hunting.runningHunts();
        boolean on = !looking.isEmpty();
        boolean noSession = on && looking.stream().allMatch(hunting::isWaiting);
        status.setIcon(noSession ? AllIcons.General.Warning : null);
        status.setForeground(noSession ? FlowTheme.WARNING : UIUtil.getContextHelpForeground());
        if (noSession) {
            status.setText(FlowValueHunt.debugSessions() == 0 ? FlowValueHunt.NO_SESSION
                    : "Waiting for the debugger to connect to the app…");
        } else if (looking.size() == 1) {
            FlowHuntStore.Hunt h = looking.get(0);
            FlowValueHunt.Mode m = FlowValueHunt.Mode.of(h.mode);
            int checked = hunting.checked(h);
            status.setText(h.name + ": " + (h.isWatch() ? "watching " : "looking for ") + h.valuesText() + " (" + m.label.toLowerCase()
                    + "), " + checked + " checked so far, " + h.stops.size() + (h.stops.size() == 1 ? " stop." : " stops.")
                    + (checked == 0 && m == FlowValueHunt.Mode.WHEREVER_IT_STOPS
                    ? " It checks when the debugger pauses: add a breakpoint, or hunt anywhere in the code." : ""));
        } else if (on) {
            int stopsNow = looking.stream().mapToInt(x -> x.stops.size()).sum();
            status.setText(looking.size() + " records looking: " + String.join(", ", looking.stream().map(x -> x.name).toList())
                    + " · " + stopsNow + (stopsNow == 1 ? " stop." : " stops."));
        } else {
            status.setText(huntModel.size() + (huntModel.size() == 1 ? " record" : " records")
                    + ". Ctrl+N add · Enter edit · Space start / stop · F2 rename · Delete delete · right-click for more.");
        }
        status.setToolTipText(status.getText());
        int binned = store.bin().size();
        binButton.setText(binned == 0 ? "Recycle Bin" : "Recycle Bin (" + binned + ")");
        showSelected();
    }

    private void select(String huntId) {
        for (int i = 0; i < huntModel.size(); i++) {
            if (huntModel.get(i).id.equals(huntId)) {
                hunts.setSelectedIndex(i);
                hunts.ensureIndexIsVisible(i);
                int last = stopsModel.getRowCount() - 1;
                int view = last < 0 ? -1 : stops.convertRowIndexToView(last);
                if (view >= 0) {
                    stops.setRowSelectionInterval(view, view);
                    stops.scrollRectToVisible(stops.getCellRect(view, 0, true));
                }
                return;
            }
        }
    }

    private void showSelected() {
        FlowHuntStore.Hunt h = hunts.getSelectedValue();
        List<Integer> keep = selectedModelRows();
        boolean same = stopsModel.hunt == h;
        if (!same) { // another record: its own threads and files
            threadPicked = null;
            filePicked = null;
        }
        stopsModel.hunt = h;
        stopsModel.fireTableDataChanged();
        applyFilter();
        if (same) {
            for (int r : keep) {
                int view = r < stopsModel.getRowCount() ? stops.convertRowIndexToView(r) : -1;
                if (view >= 0) stops.addRowSelectionInterval(view, view);
            }
        }
        List<FlowHuntStore.Hunt> picked = hunts.getSelectedValuesList();
        boolean one = picked.size() == 1;
        boolean running = h != null && isRunning(h);
        startStop.setEnabled(one);
        startStop.setText(running ? "Stop" : "Start");
        startStop.setIcon(running ? AllIcons.Actions.Suspend : AllIcons.Actions.Execute);
        startStop.setAccent(running ? STOP_RED : START_GREEN);
        startStop.setToolTipText(running ? (hunting.isWaiting(h) ? "It starts looking when the app runs in Debug. " : "")
                + "Stop looking (Space): the record and its stops stay"
                : "Start the selected record (Space): the others looking go on");
        edit.setEnabled(one);
        rename.setEnabled(one);
        delete.setEnabled(!picked.isEmpty());
        showValues(h);
        if (h == null) {
            title.setText("");
            subtitle.setText("");
        } else {
            title.setText(h.name);
            String what = h.isWatch() ? "Watch " + h.targetText() + (h.isDeep() ? " and its data" : "") + " · " + whenLabel(h)
                    : (h.all ? "All of: " : "Any of: ") + String.join(", ", h.active()) + "  ·  " + FlowValueHunt.Mode.of(h.mode).label;
            subtitle.setText(what + "  ·  " + (running ? (hunting.isWaiting(h) ? "waiting for Debug" : "looking now")
                    : "added " + time(h.startedAt)) + "  ·  "
                    + h.stops.size() + (h.stops.size() == 1 ? " stop" : " stops"));
            subtitle.setToolTipText(subtitle.getText());
        }
        showStop();
    }

    private static String whenLabel(FlowHuntStore.Hunt h) {
        return switch (h.when) {
            case "SET" -> "whenever it is set";
            case "NULL" -> "when it is set to null";
            case "VALUE" -> "when it is set to " + String.join(" | ", h.active());
            default -> "when its value changes";
        };
    }

    /** The record's values as chips: a switch (looked for or not), the value, ✕; then + Add value. */
    private void showValues(@Nullable FlowHuntStore.Hunt h) {
        valueChips.removeAll();
        boolean shown = h != null && (!h.isWatch() || "VALUE".equals(h.when));
        if (shown) {
            boolean running = isRunning(h);
            for (String v : h.values) valueChips.add(new ValueChip(h, v, !h.off.contains(v), running));
            JBLabel addValue = new JBLabel("+ Add value");
            addValue.setForeground(FlowTheme.ACCENT);
            addValue.setBorder(JBUI.Borders.empty(4, 6));
            addValue.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addValue.setToolTipText(running ? "Look for one more value from now on" : "Add a value to this record");
            addValue.addMouseListener(new MouseAdapter() {
                @Override public void mouseReleased(MouseEvent e) { addValue(h); }
            });
            valueChips.add(addValue);
        }
        valueChips.setVisible(shown);
        valueChips.revalidate();
        valueChips.repaint();
    }

    private void addValue(FlowHuntStore.Hunt h) {
        String v = Messages.showInputDialog(project, "Value to look for (a name, type or value that contains it, or a /regex/):",
                "Add Value", null, "", new InputValidatorEx() {
                    @Override public @Nullable String getErrorText(@NotNull String input) {
                        if (input.isBlank()) return "Type a value";
                        if (h.values.contains(input.strip())) return "The record has it already";
                        return FlowValueSearch.error(input.strip());
                    }
                    @Override public boolean checkInput(@NotNull String input) { return getErrorText(input) == null; }
                    @Override public boolean canClose(@NotNull String input) { return checkInput(input); }
                });
        if (v != null) store.addValue(h, v);
    }

    // ---------- a stop's details ----------

    private JComponent buildDetails() {
        JBLabel empty = new JBLabel("Pick a stop to see its details: where, which thread, what matched, and the variables there");
        empty.setForeground(FlowTheme.FG_MUTED);
        empty.setHorizontalAlignment(JBLabel.CENTER);
        details.add(empty, "empty");

        JPanel info = new JPanel(new GridBagLayout());
        info.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = JBUI.insets(2, 6, 2, 10);
        String[] names = {"When", "Where", "Method", "Thread", "Found"};
        JComponent[] values = {stopWhen, stopWhere, stopMethod, stopThread, stopFound};
        stopWhere.setForeground(FlowTheme.ACCENT);
        stopWhere.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        stopWhere.setToolTipText("Open this line (Enter)");
        stopWhere.addMouseListener(new MouseAdapter() {
            @Override public void mouseReleased(MouseEvent e) { openStop(); }
        });
        stopFound.setEditable(false);
        stopFound.setOpaque(false);
        stopFound.setLineWrap(true);
        stopFound.setWrapStyleWord(true);
        stopFound.setFont(JBUI.Fonts.create(java.awt.Font.MONOSPACED, JBUI.Fonts.label().getSize()));
        for (int i = 0; i < names.length; i++) {
            JBLabel n = new JBLabel(names[i]);
            n.setForeground(FlowTheme.FG_MUTED);
            c.gridx = 0;
            c.gridy = i;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            info.add(n, c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            values[i].setMinimumSize(new Dimension(0, 0));
            info.add(values[i], c);
        }

        FlowButton expand = new FlowButton("", AllIcons.Actions.Expandall, FlowButton.Style.OUTLINE);
        expand.setToolTipText("Open every block");
        expand.addActionListener(e -> { if (asTree) snapshotTree.expandAll(); else snapshot.expandAll(); });
        FlowButton collapse = new FlowButton("", AllIcons.Actions.Collapseall, FlowButton.Style.OUTLINE);
        collapse.setToolTipText("Fold every block");
        collapse.addActionListener(e -> { if (asTree) snapshotTree.collapseAll(); else snapshot.collapseAll(); });
        FlowButton internals = new FlowButton("", AllIcons.Actions.ToggleVisibility, FlowButton.Style.OUTLINE);
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
            shownSnapshot = null; // shown again, with or without them
            showStop();
        });
        FlowButton find = new FlowButton("", AllIcons.Actions.Find, FlowButton.Style.OUTLINE);
        find.setToolTipText("Find in the variables (in the tree: just type)");
        find.addActionListener(e -> { if (asTree) snapshotTree.find(); else snapshot.find(); });
        // Tree (like the debugger's variables) or JSON
        FlowButton treeView = new FlowButton("Tree", AllIcons.Actions.ShowAsTree, FlowButton.Style.SEGMENT);
        FlowButton jsonView = new FlowButton("JSON", AllIcons.FileTypes.Json, FlowButton.Style.SEGMENT);
        treeView.setToolTipText("The variables as a tree, like the debugger shows them");
        jsonView.setToolTipText("The variables as JSON: fold, find");
        Runnable views = () -> {
            treeView.setSelected(asTree);
            jsonView.setSelected(!asTree);
            ((CardLayout) varsCards.getLayout()).show(varsCards, asTree ? "tree" : "json");
            com.intellij.ide.util.PropertiesComponent.getInstance().setValue("TraceWeave.StopAtValue.json", !asTree);
        };
        treeView.addActionListener(e -> { asTree = true; views.run(); });
        jsonView.addActionListener(e -> { asTree = false; views.run(); });
        varsCards.setOpaque(false);
        varsCards.add(snapshotTree.component(), "tree");
        varsCards.add(snapshot.getComponent(), "json");
        views.run();
        JBLabel varsTitle = new JBLabel("Variables at that stop");
        varsTitle.setFont(JBUI.Fonts.label().asBold());
        snapshotNote.setForeground(FlowTheme.FG_MUTED);
        FlowCompactRow varsHead = new FlowCompactRow(new BorderLayout(JBUI.scale(8), 0));
        varsHead.squeeze(jsonView, treeView);
        JPanel varsTitles = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        varsTitles.setOpaque(false);
        varsTitles.add(varsTitle);
        varsTitles.add(snapshotNote);
        varsHead.add(varsTitles, BorderLayout.CENTER);
        JPanel varsButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0));
        varsButtons.setOpaque(false);
        varsButtons.add(treeView);
        varsButtons.add(jsonView);
        varsButtons.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8)));
        varsButtons.add(internals);
        varsButtons.add(expand);
        varsButtons.add(collapse);
        varsButtons.add(find);
        varsHead.add(varsButtons, BorderLayout.EAST);
        varsHead.setBorder(JBUI.Borders.empty(8, 6, 4, 6));
        JPanel vars = new JPanel(new BorderLayout());
        vars.setOpaque(false);
        vars.add(varsHead, BorderLayout.NORTH);
        vars.add(varsCards, BorderLayout.CENTER);

        JPanel content = new JPanel(new BorderLayout());
        content.setOpaque(false);
        info.setBorder(JBUI.Borders.empty(8, 0, 0, 0));
        content.add(info, BorderLayout.NORTH);
        content.add(vars, BorderLayout.CENTER);
        details.add(content, "stop");
        details.setOpaque(false);
        return details;
    }

    private @Nullable FlowHuntStore.Stop selectedStop() {
        FlowHuntStore.Hunt h = stopsModel.hunt;
        int row = modelRow(stops.getSelectedRow());
        return h == null || row < 0 || row >= h.stops.size() || stops.getSelectedRowCount() != 1 ? null : h.stops.get(row);
    }

    /** The selected stop's details (one stop selected), or a hint. */
    private void showStop() {
        int picked = stops.getSelectedRowCount();
        toDebugData.setEnabled(picked > 0);
        toDebugData.setText(picked > 1 ? "To Debug Data (" + picked + ")" : "To Debug Data");
        FlowHuntStore.Stop s = selectedStop();
        CardLayout cards = (CardLayout) details.getLayout();
        if (s == null) {
            cards.show(details, "empty");
            shownSnapshot = null;
            return;
        }
        stopWhen.setText(new SimpleDateFormat("dd MMM yyyy HH:mm:ss").format(new Date(s.at)));
        stopWhere.setText("<html><u>" + StringUtil.escapeXmlEntities(s.fileName + ":" + s.line) + "</u></html>");
        stopMethod.setText(s.method.isEmpty() ? "—" : s.method);
        stopThread.setText(s.thread.isEmpty() ? "—" : s.thread);
        stopFound.setText(String.join("\n", s.matches));
        if (shownSnapshot != s || !s.snapshot.equals(shownText)) {
            shownSnapshot = s;
            shownText = s.snapshot;
            boolean hide = com.example.flowtracker.model.FlowValueShape.hideInternals();
            String json = hide ? com.example.flowtracker.model.FlowValueShape.withoutInternalsJson(s.snapshot) : s.snapshot;
            snapshot.setText(json.isEmpty() ? "" : json, true);
            if (s.variables != null) {
                snapshotTree.setValue(hide ? com.example.flowtracker.model.FlowValueShape.withoutInternals(s.variables) : s.variables);
            } else {
                snapshotTree.setJson(json); // a stop saved before the variables were kept
            }
        }
        snapshotNote.setText(s.snapshot.isEmpty() ? "(not read: the debugger went on before they could be)" : "");
        cards.show(details, "stop");
    }

    /** A stop's variables as a tree (or as JSON, {@link #snapshot}); the choice is remembered. */
    private final FlowJsonTree snapshotTree = new FlowJsonTree();
    private final JPanel varsCards = new JPanel(new CardLayout());
    private boolean asTree = !com.intellij.ide.util.PropertiesComponent.getInstance().getBoolean("TraceWeave.StopAtValue.json", false);
    private @Nullable FlowHuntStore.Stop shownSnapshot;
    private String shownText = "";

    private void openStop() {
        FlowHuntStore.Hunt h = stopsModel.hunt;
        int row = modelRow(stops.getSelectedRow());
        if (h == null || row < 0 || row >= h.stops.size()) return;
        FlowHuntStore.Stop s = h.stops.get(row);
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(s.fileUrl);
        if (vf != null) new OpenFileDescriptor(project, vf, Math.max(0, s.line - 1), 0).navigate(true);
    }

    private void deleteStops() {
        FlowHuntStore.Hunt h = stopsModel.hunt;
        List<Integer> rows = selectedModelRows();
        if (h == null || rows.isEmpty()) return;
        List<FlowHuntStore.Stop> gone = new ArrayList<>();
        for (int r : rows) if (r < h.stops.size()) gone.add(h.stops.get(r));
        store.removeStops(h, gone);
        status.setText((gone.size() == 1 ? "1 stop" : gone.size() + " stops") + " moved to the Recycle Bin (it can be restored there).");
    }

    // ---------- filtering the stops ----------

    /** The search box and the Thread / File buttons, at the top right of the stops. */
    private JComponent buildFilters() {
        stops.setRowSorter(sorter);
        sorter.setComparator(0, java.util.Comparator.comparingInt((Object o) -> Integer.parseInt(String.valueOf(o))));
        stopFilter.getTextEditor().getEmptyText().setText("Filter: where, thread, value…");
        stopFilter.addDocumentListener(new com.intellij.ui.DocumentAdapter() {
            @Override protected void textChanged(@NotNull javax.swing.event.DocumentEvent e) { applyFilter(); }
        });
        order.setToolTipText("The order of the stops (a column's header sorts by it too)");
        order.addActionListener(e -> new FlowPopupMenu().header("ORDER")
                .item(newestFirst ? AllIcons.Actions.Checked : AllIcons.ObjectBrowser.SortByType, "Newest First",
                        "The latest find at the top", null, () -> setOrder(true))
                .item(!newestFirst ? AllIcons.Actions.Checked : AllIcons.ObjectBrowser.SortByType, "Oldest First",
                        "In the order they were found", null, () -> setOrder(false))
                .showBelow(order));
        setOrder(newestFirst);
        // a click on a column's header sorts too: the button follows it (# and When: newest / oldest first)
        sorter.addRowSorterListener(e -> {
            if (e.getType() == javax.swing.event.RowSorterEvent.Type.SORT_ORDER_CHANGED) orderFollowsHeader();
        });
        // "When" sorts by the time it stopped (its text is only the day and the time)
        sorter.setComparator(1, java.util.Comparator.comparingLong((Object o) -> o instanceof When w ? w.at() : 0L));
        threadFilter.setToolTipText("Only the stops in one thread");
        threadFilter.addActionListener(e -> pickFrom(threadFilter, "THREAD", AllIcons.Debugger.Threads, "All threads",
                values(s -> s.thread), () -> threadPicked, v -> threadPicked = v));
        fileFilter.setToolTipText("Only the stops in one file");
        fileFilter.addActionListener(e -> pickFrom(fileFilter, "FILE", AllIcons.FileTypes.Any_type, "All files",
                values(s -> s.fileName), () -> filePicked, v -> filePicked = v));
        shownCount.setForeground(FlowTheme.FG_MUTED);
        shownCount.setFont(JBUI.Fonts.smallFont());
        stopFilter.setPreferredSize(new Dimension(JBUI.scale(220), stopFilter.getPreferredSize().height));
        // one row that shows icons only when it is narrow, then wraps onto more lines (nothing on top of anything)
        FlowCompactRow bar = new FlowCompactRow(new com.intellij.util.ui.WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4)));
        bar.squeeze(fileFilter, threadFilter, order, toDebugData);
        JBLabel title = new JBLabel("Stops");
        title.setFont(JBUI.Fonts.label().asBold());
        title.setBorder(JBUI.Borders.empty(0, 0, 0, 4));
        toDebugData.setToolTipText("Copy the selected stops' variables to Debug Data, into the active flow (Ctrl+Shift+D)");
        toDebugData.addActionListener(e -> copyToDebugData());
        bar.add(title);
        bar.add(toDebugData);
        bar.add(shownCount);
        bar.add(stopFilter);
        bar.add(order);
        bar.add(threadFilter);
        bar.add(fileFilter);
        return bar;
    }

    /** The Order button after a header was clicked: Newest / Oldest first for "#" and "When", else "Sorted by …". */
    private void orderFollowsHeader() {
        List<? extends javax.swing.RowSorter.SortKey> keys = sorter.getSortKeys();
        if (keys.isEmpty()) return;
        javax.swing.RowSorter.SortKey key = keys.get(0);
        if (key.getColumn() <= 1) {
            boolean newest = key.getSortOrder() == javax.swing.SortOrder.DESCENDING;
            if (newest != newestFirst) {
                newestFirst = newest;
                com.intellij.ide.util.PropertiesComponent.getInstance().setValue("TraceWeave.StopAtValue.newestFirst", newest, true);
            }
            order.setText(newest ? "Newest first" : "Oldest first");
        } else {
            order.setText("Sorted by " + stopsModel.getColumnName(key.getColumn())
                    + (key.getSortOrder() == javax.swing.SortOrder.DESCENDING ? " ↓" : " ↑"));
        }
    }

    /** Sorts the stops by when they were found: the newest first, or the oldest first (remembered). */
    private void setOrder(boolean newest) {
        newestFirst = newest;
        com.intellij.ide.util.PropertiesComponent.getInstance().setValue("TraceWeave.StopAtValue.newestFirst", newest, true);
        sorter.setSortKeys(List.of(new javax.swing.RowSorter.SortKey(0, newest ? javax.swing.SortOrder.DESCENDING : javax.swing.SortOrder.ASCENDING)));
        order.setText(newest ? "Newest first" : "Oldest first");
    }

    /** The different values of the record's stops (threads, files), in order. */
    private List<String> values(java.util.function.Function<FlowHuntStore.Stop, String> of) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        FlowHuntStore.Hunt h = stopsModel.hunt;
        if (h != null) for (FlowHuntStore.Stop s : h.stops) if (of.apply(s) != null && !of.apply(s).isBlank()) out.add(of.apply(s));
        return new ArrayList<>(out);
    }

    /** A menu under a filter button: all, or one of the values (the picked one ticked). */
    private void pickFrom(FlowButton under, String header, javax.swing.Icon icon, String all, List<String> values,
                          java.util.function.Supplier<String> picked, java.util.function.Consumer<String> pick) {
        FlowPopupMenu m = new FlowPopupMenu().header(header);
        m.item(picked.get() == null ? AllIcons.Actions.Checked : null, all, null, null, () -> { pick.accept(null); applyFilter(); });
        if (!values.isEmpty()) m.separator();
        for (String v : values) {
            long n = stopsModel.hunt == null ? 0 : stopsModel.hunt.stops.stream()
                    .filter(s -> v.equals(header.equals("THREAD") ? s.thread : s.fileName)).count();
            m.item(v.equals(picked.get()) ? AllIcons.Actions.Checked : icon, StringUtil.shortenTextWithEllipsis(v, 50, 10),
                    n + (n == 1 ? " stop" : " stops"), null, () -> { pick.accept(v); applyFilter(); });
        }
        m.showBelow(under);
    }

    /** Shows the stops that match the text and the thread and file picked; the buttons and the count follow. */
    private void applyFilter() {
        String text = stopFilter.getText().strip().toLowerCase(java.util.Locale.ROOT);
        String thread = threadPicked, file = filePicked;
        FlowHuntStore.Hunt h = stopsModel.hunt;
        sorter.setRowFilter(text.isEmpty() && thread == null && file == null || h == null ? null : new javax.swing.RowFilter<>() {
            @Override public boolean include(Entry<? extends StopsModel, ? extends Integer> e) {
                int row = e.getIdentifier();
                if (row >= h.stops.size()) return false;
                FlowHuntStore.Stop s = h.stops.get(row);
                if (thread != null && !thread.equals(s.thread)) return false;
                if (file != null && !file.equals(s.fileName)) return false;
                if (text.isEmpty()) return true;
                String all = (time(s.at) + " " + s.where() + " " + s.thread + " " + String.join(" ", s.matches)).toLowerCase(java.util.Locale.ROOT);
                for (String word : text.split("\\s+")) if (!all.contains(word)) return false;
                return true;
            }
        });
        threadFilter.setText(thread == null ? "All threads" : StringUtil.shortenTextWithEllipsis(thread, 22, 0));
        fileFilter.setText(file == null ? "All files" : StringUtil.shortenTextWithEllipsis(file, 22, 0));
        int all = stopsModel.getRowCount(), shown = stops.getRowCount();
        shownCount.setText(all == 0 ? "" : shown == all ? all + (all == 1 ? " stop" : " stops") : shown + " of " + all);
        stops.getEmptyText().setText(all == 0 ? "It hasn't stopped anywhere yet" : "No stop matches the filter");
    }

    private int modelRow(int viewRow) {
        return viewRow < 0 || viewRow >= stops.getRowCount() ? -1 : stops.convertRowIndexToModel(viewRow);
    }

    private List<Integer> selectedModelRows() {
        List<Integer> out = new ArrayList<>();
        for (int v : stops.getSelectedRows()) {
            int m = modelRow(v);
            if (m >= 0) out.add(m);
        }
        return out;
    }

    /**
     * Copies the selected stops' variables to Debug Data, into the active flow: one saved value for each variable of
     * each stop, at the stop's line and method, dated when it stopped. A stop copied before isn't copied again.
     */
    private void copyToDebugData() {
        FlowHuntStore.Hunt h = stopsModel.hunt;
        List<Integer> rows = selectedModelRows();
        if (h == null || rows.isEmpty()) return;
        com.example.flowtracker.model.FlowDebugStore debug = com.example.flowtracker.model.FlowDebugStore.get(project);
        String flow = com.example.flowtracker.model.FlowStore.get(project).getActiveFlowName();
        List<com.example.flowtracker.model.FlowSnapshot> saved = new ArrayList<>();
        int without = 0, already = 0;
        for (int r : rows) {
            if (r >= h.stops.size()) continue;
            FlowHuntStore.Stop stop = h.stops.get(r);
            if (stop.variables == null || stop.variables.children.isEmpty()) {
                without++;
                continue;
            }
            int paren = stop.method.indexOf('(');
            String owner = paren < 0 ? stop.method : stop.method.substring(0, paren);
            int dot = owner.lastIndexOf('.');
            for (com.example.flowtracker.model.FlowValue v : stop.variables.children) {
                com.example.flowtracker.model.FlowSnapshot snap = new com.example.flowtracker.model.FlowSnapshot();
                snap.id = java.util.UUID.randomUUID().toString();
                snap.name = v.name;
                snap.flowName = flow;
                snap.fileUrl = stop.fileUrl;
                snap.fileName = stop.fileName;
                snap.line = stop.line;
                snap.className = dot < 0 ? "" : owner.substring(0, dot);
                snap.methodName = dot < 0 ? stop.method : stop.method.substring(dot + 1);
                snap.savedAt = stop.at;
                snap.value = v.copy();
                boolean copiedBefore = debug.sameVariable(snap).stream()
                        .anyMatch(o -> o.savedAt == snap.savedAt && flow.equals(o.flowName));
                if (copiedBefore) {
                    already++;
                    continue;
                }
                saved.add(snap);
            }
        }
        if (!saved.isEmpty()) debug.addAll(saved);
        StringBuilder msg = new StringBuilder(saved.isEmpty() ? "Nothing new to copy" : "Copied " + saved.size()
                + (saved.size() == 1 ? " value" : " values") + " to Debug Data, flow “" + flow + "”");
        if (already > 0) msg.append(" · ").append(already).append(" copied before");
        if (without > 0) msg.append(" · ").append(without).append(without == 1 ? " stop" : " stops")
                .append(" without variables (found before they were kept)");
        status.setText(msg + ".");
        if (!saved.isEmpty()) {
            com.intellij.notification.Notification n = com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup("TraceWeave").createNotification(msg + ".", com.intellij.notification.NotificationType.INFORMATION);
            com.example.flowtracker.model.FlowSnapshot first = saved.get(0);
            n.addAction(com.intellij.notification.NotificationAction.createSimpleExpiring("Show in Debug Data",
                    () -> FlowDebugPanel.showLineOf(project, first.fileUrl, first.line)));
            n.notify(project);
        }
    }

    private void renameSelected() {
        FlowHuntStore.Hunt h = hunts.getSelectedValue();
        if (h == null) return;
        String name = Messages.showInputDialog(project, "New name:", "Rename", null, h.name, null);
        if (name != null) store.rename(h, name);
    }

    private void deleteSelected() {
        List<FlowHuntStore.Hunt> picked = hunts.getSelectedValuesList();
        if (picked.isEmpty()) return;
        for (FlowHuntStore.Hunt h : picked) if (isRunning(h)) hunting.stop(h);
        store.delete(picked);
        status.setText((picked.size() == 1 ? "“" + picked.get(0).name + "”" : picked.size() + " records")
                + " moved to the Recycle Bin, with " + (picked.size() == 1 ? "its" : "their") + " stops (it can be restored there).");
    }

    private static String time(long at) {
        return new SimpleDateFormat("dd MMM HH:mm:ss").format(new Date(at));
    }

    /** One value of a record: on (looked for) or off, and ✕ to remove it. */
    private final class ValueChip extends JPanel {
        private final boolean on;

        ValueChip(FlowHuntStore.Hunt h, String value, boolean on, boolean running) {
            super(new GridBagLayout());
            this.on = on;
            setOpaque(false);
            setBorder(JBUI.Borders.empty(2, 6, 2, 8));
            JBCheckBox box = new JBCheckBox();
            box.setOpaque(false);
            box.setBorder(JBUI.Borders.empty());
            box.setSelected(on);
            box.setToolTipText(on ? "Looked for" + (running ? " now" : "") + ": click to stop looking for it"
                    : "Not looked for: click to look for it" + (running ? " from now on" : ""));
            box.addActionListener(e -> store.setOn(h, value, box.isSelected()));
            JBLabel text = new JBLabel(StringUtil.shortenTextWithEllipsis(value, 40, 0));
            text.setFont(JBUI.Fonts.label());
            text.setForeground(on ? FlowTheme.FG : FlowTheme.FG_MUTED);
            text.setToolTipText(value);
            JBLabel remove = new JBLabel(AllIcons.Actions.Close);
            remove.setToolTipText("Remove this value");
            remove.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            remove.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { remove.setIcon(AllIcons.Actions.CloseHovered); }
                @Override public void mouseExited(MouseEvent e) { remove.setIcon(AllIcons.Actions.Close); }
                @Override public void mouseReleased(MouseEvent e) {
                    if (h.values.size() == 1) {
                        Messages.showInfoMessage(project, "A record needs a value: switch it off, or delete the record.", "Remove Value");
                        return;
                    }
                    store.removeValue(h, value);
                }
            });
            GridBagConstraints c = new GridBagConstraints();
            c.anchor = GridBagConstraints.CENTER;
            c.insets = JBUI.insetsRight(6);
            add(box, c);
            add(text, c);
            c.insets = JBUI.emptyInsets();
            add(remove, c);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(d.width + JBUI.scale(2), Math.max(d.height, JBUI.scale(30)) + JBUI.scale(2));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // the outline half a pixel inside, so no edge of it is cut off
                float w = getWidth() - 2f, hgt = getHeight() - 2f, arc = hgt;
                java.awt.geom.RoundRectangle2D shape = new java.awt.geom.RoundRectangle2D.Float(1f, 1f, w, hgt, arc, arc);
                g2.setColor(on ? FlowTheme.SELECTION : FlowTheme.CARD_BG);
                g2.fill(shape);
                g2.setStroke(new java.awt.BasicStroke(1f));
                g2.setColor(on ? FlowTheme.SELECTION_BAR : FlowTheme.CARD_BORDER);
                g2.draw(new java.awt.geom.RoundRectangle2D.Float(1.5f, 1.5f, w - 1f, hgt - 1f, arc, arc));
            } finally {
                g2.dispose();
            }
        }
    }

    /** A record in the list: its name, what it looks for, and how it went. */
    private final class HuntRenderer extends JPanel implements ListCellRenderer<FlowHuntStore.Hunt> {
        private final JBLabel name = new JBLabel(), what = new JBLabel(), how = new JBLabel();
        private boolean selected;

        HuntRenderer() {
            super(new BorderLayout(0, JBUI.scale(2)));
            setOpaque(false);
            setBorder(JBUI.Borders.empty(8, 12));
            name.setFont(JBUI.Fonts.label().asBold());
            what.setFont(JBUI.Fonts.create(java.awt.Font.MONOSPACED, JBUI.Fonts.smallFont().getSize()));
            how.setFont(JBUI.Fonts.smallFont());
            add(name, BorderLayout.NORTH);
            add(what, BorderLayout.CENTER);
            add(how, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends FlowHuntStore.Hunt> list, FlowHuntStore.Hunt h, int index,
                                                      boolean isSelected, boolean focus) {
            selected = isSelected;
            name.setIcon(h.isWatch() ? AllIcons.Debugger.Db_field_breakpoint : AllIcons.Actions.Find);
            // the text wraps to the list's width (nothing crosses into the next section)
            int width = Math.max(JBUI.scale(80), list.getWidth() - JBUI.scale(32));
            name.setText(wrapped(h.name, width, true));
            name.setForeground(FlowTheme.FG);
            what.setText(wrapped(StringUtil.shortenTextWithEllipsis(h.isWatch() ? h.targetText() + " · " + whenLabel(h) : h.valuesText(), 200, 0),
                    width, false));
            what.setForeground(FlowTheme.FG);
            boolean on = isRunning(h);
            int n = h.stops.size();
            boolean waiting = on && hunting.isWaiting(h);
            String state = waiting ? "◌ Waiting for Debug… · " + n + (n == 1 ? " stop" : " stops")
                    : on ? "● Looking… " + hunting.checked(h) + " checked · " + n + (n == 1 ? " stop" : " stops")
                    : n > 0 ? "✔ " + n + (n == 1 ? " stop" : " stops")
                    : "NOT_FOUND".equals(h.status) ? "✕ Not found · " + h.checked + " checked" : "Stopped";
            how.setText(wrapped(state + "  ·  " + time(h.startedAt), width, false));
            how.setForeground(waiting ? FlowTheme.WARNING : on ? FlowTheme.ACCENT : n > 0 ? new JBColor(0x1F8F4E, 0x5FB878)
                    : "NOT_FOUND".equals(h.status) ? FlowTheme.WARNING : FlowTheme.FG_MUTED);
            return this;
        }

        /** Text that wraps at a width (pixels): long names and values go onto more lines. */
        private static String wrapped(String text, int width, boolean bold) {
            String t = StringUtil.escapeXmlEntities(text);
            return "<html><div style='width:" + Math.round(width / com.intellij.ui.scale.JBUIScale.scale(1f)) + "px'>" + (bold ? "<b>" + t + "</b>" : t) + "</div></html>";
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (!selected) return;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = JBUI.scale(10), m = JBUI.scale(3);
                g2.setColor(FlowTheme.SELECTION);
                g2.fillRoundRect(m, m / 2, getWidth() - 2 * m, getHeight() - m, arc, arc);
                g2.setColor(FlowTheme.SELECTION_BAR);
                g2.fillRect(m, m / 2 + arc / 2, JBUI.scale(3), getHeight() - m - arc);
            } finally {
                g2.dispose();
            }
        }
    }

    /** When a stop was: shown as its day and time, sorted by the moment. */
    private record When(long at) {
        @Override public String toString() { return time(at); }
    }

    /** The places a record stopped. */
    private static final class StopsModel extends AbstractTableModel {
        @Nullable FlowHuntStore.Hunt hunt;
        private final String[] columns = {"#", "When", "Where", "Thread", "Found"};

        @Override public int getRowCount() { return hunt == null ? 0 : hunt.stops.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int c) { return columns[c]; }

        @Override
        public Object getValueAt(int row, int col) {
            FlowHuntStore.Stop s = hunt.stops.get(row);
            return switch (col) {
                case 0 -> String.valueOf(row + 1);
                case 1 -> new When(s.at);
                case 2 -> s.where();
                case 3 -> s.thread;
                default -> s.matches.isEmpty() ? "" : s.matches.get(0) + (s.matches.size() > 1 ? "  (+" + (s.matches.size() - 1) + ")" : "");
            };
        }
    }

    @Override
    public void dispose() {
        store.removeListener(changed);
        hunting.removeListener(changed);
    }
}
