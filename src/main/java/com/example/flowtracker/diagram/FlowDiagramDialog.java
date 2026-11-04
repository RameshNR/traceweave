package com.example.flowtracker.diagram;

import com.example.flowtracker.code.FlowCodeSearch;
import com.example.flowtracker.code.FlowLang;
import com.example.flowtracker.code.FlowSync;
import com.example.flowtracker.compat.FlowCompat;
import com.example.flowtracker.copy.FlowCopy;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowLook;
import com.example.flowtracker.model.FlowStore;
import com.example.flowtracker.model.FlowTag;
import com.example.flowtracker.ui.FlowButton;
import com.example.flowtracker.ui.FlowMenus;
import com.example.flowtracker.ui.FlowSettingsPopup;
import com.example.flowtracker.ui.FlowTheme;
import com.example.flowtracker.ui.FlowToolbar;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.InputValidatorEx;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * "Flow Diagram": the flow's classes and calls as an editable diagram.
 * <ul>
 *   <li><b>Add File</b> and <b>Add Function</b> pick classes (or JSP, JavaScript, TLD... files) and
 *       their methods (or functions, blocks) from the project's code (searched by name or by a
 *       pattern such as {@code *Cruise*.java}); a method's lines can be picked from its code.</li>
 *   <li><b>Custom Class / File</b> and <b>Custom Method / Function</b> add ones that aren't in the code yet (amber).</li>
 *   <li><b>Format</b> sets a box's colors, the text format (normal, bold, italic, color) of names and notes, the notes
 *       under class and method names, and a call's line (pattern, width, shape, arrow, color).</li>
 *   <li>Rename, delete, connect methods, move boxes, and copy the diagram (or its sequence diagram) as PNG.</li>
 * </ul>
 * Every change is listed in the History (kept while the IDE runs, also after Apply). Apply writes the diagram to
 * the flow as one undoable step; Cancel leaves the flow (and the History) as they were.
 */
public final class FlowDiagramDialog {

    /**
     * Opens the window for the active flow, once its calls have been analysed. One window per project: when it is
     * open (or minimized) already, it comes to the front instead, with its unsaved changes.
     */
    public static void open(Project project) {
        FlowDiagramHost host = FlowDiagramHost.of(project);
        if (host != null) {
            host.bringBack();
            FlowDiagramDialog open = host.pageOf(FlowStore.get(project).getActiveFlowName());
            if (open != null) {
                host.select(open);
                return;
            }
        }
        load(project, null);
    }

    /** Opens a flow in a tab of the window (it becomes the active flow). */
    static void openFlow(Project project, String flow) {
        FlowStore store = FlowStore.get(project);
        int i = store.indexOfFlow(flow);
        if (i < 0) return;
        if (i != store.getActiveFlowIndex()) store.switchFlow(i);
        FlowDiagramHost host = FlowDiagramHost.of(project);
        FlowDiagramDialog open = host == null ? null : host.pageOf(flow);
        if (open != null) {
            host.select(open);
            return;
        }
        load(project, null);
    }

    /**
     * Makes a page for the active flow (once its calls are known) and shows it: in place of {@code replace} (the same
     * flow reloaded after Apply or Cancel), else in a new tab (the window opens if needed).
     */
    private static void load(Project project, @Nullable FlowDiagramDialog replace) {
        // Clicks while it opens do nothing.
        Long since = OPENING.get(project);
        if (since != null && System.currentTimeMillis() - since < OPENING_TIMEOUT_MS) return;
        OPENING.put(project, System.currentTimeMillis());
        FlowStore store = FlowStore.get(project);
        List<FlowEntry> entries = store.getEntries();
        FlowSync.Calls known = store.getCalls();
        if (!known.links().isEmpty() || entries.size() < 2) {
            show(project, store, entries, known, replace); // the calls the panel found for these lines: no waiting
            return;
        }
        FlowSync.callsFor(project, List.of(entries), calls -> {
            if (project.isDisposed() || !store.getEntries().equals(entries)) { // changed meanwhile
                OPENING.remove(project);
                return;
            }
            show(project, store, entries, FlowSync.withManual(calls.get(0), entries), replace);
        });
    }

    /** Projects whose Flow Diagram is open or opening, since when (a stuck analysis frees it after a while). */
    private static final java.util.Map<Project, Long> OPENING = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long OPENING_TIMEOUT_MS = 60_000;

    private static void show(Project project, FlowStore store, List<FlowEntry> entries, FlowSync.Calls calls,
                             @Nullable FlowDiagramDialog replace) {
        try {
            FlowDiagramDialog d = new FlowDiagramDialog(project, store, entries, calls);
            d.build();
            FlowDiagramHost host = FlowDiagramHost.of(project);
            if (host == null) FlowDiagramHost.open(project, d);
            else if (replace != null) host.replace(replace, d); // reloaded after Apply or Cancel: the same tab
            else host.add(d);
            if (host != null) host.bringBack();
        } finally {
            OPENING.remove(project);
        }
    }

    // ---------- the window ----------

    /**
     * A window in the IDE's style (IntelliJ's own title bar, with Minimize, Maximize and Close) that doesn't block the
     * IDE. Close asks like Cancel when there are changes.
     */
    private @Nullable FlowDiagramHost host;
    private final JPanel root = new JPanel(new BorderLayout());

    JPanel root() {
        return root;
    }

    String flowName() {
        return flowName;
    }

    /** The page's toolbar (Select, Connect, Add File…, Settings): the window shows the selected tab's above the tabs. */
    private @Nullable JComponent pageToolbar;

    @Nullable JComponent toolbar() {
        return pageToolbar;
    }

    /** The flow was renamed (on its tab). */
    void renamed(String name) {
        flowName = name;
    }

    /** True when the diagram has changes that aren't applied. */
    boolean hasChanges() {
        return !applied && (!Objects.equals(diagram.signature(), openSignature) || history.position != openPosition
                || !diagram.sameAs(openDiagram));
    }

    boolean hasClasses() {
        return !diagram.boxes.isEmpty();
    }

    int classCount() {
        return diagram.boxes.size();
    }

    /** Apply without reloading (closing the tab right after). */
    boolean applyNow() {
        return applyChanges();
    }

    void attach(FlowDiagramHost host) {
        this.host = host;
    }

    /** The page is gone (the window closed, or another page took its place). */
    void disposePage() {
        if (disposed) return;
        disposed = true;
        Disposer.dispose(disposable);
    }
    private final com.intellij.openapi.Disposable disposable = Disposer.newDisposable("TraceWeave Flow Diagram");
    private boolean disposed;

    com.intellij.openapi.Disposable getDisposable() {
        return disposable;
    }

    boolean isDisposed() {
        return disposed;
    }

    /**
     * Lays the page out: the diagram, and Close · Save · Reset at the bottom right. Close and Save have a ▾ menu
     * (above them) for all the tabs: Close All, Don't Save and Close All, Save and Close All; Save All.
     */
    private void build() {
        FlowButton close = new FlowButton("Close", null, FlowButton.Style.OUTLINE);
        FlowButton save = new FlowButton("Save", null, FlowButton.Style.PRIMARY);
        FlowButton reset = new FlowButton("Reset", null, FlowButton.Style.OUTLINE);
        close.setToolTipText("Close this tab (asks first when it has changes not saved); ▾ for all the tabs");
        save.setToolTipText("Save the diagram to its flow; the tab stays open. ▾ Save All: every tab");
        reset.setToolTipText("Drop the changes made since the last Save; the tab stays open");
        close.addActionListener(e -> doCancelAction());
        save.addActionListener(e -> doOKAction());
        reset.addActionListener(e -> dropChanges());
        close.setDropDown("Close all the tabs", () -> FlowMenus.above(close,
                new String[]{"Close All", "Don't Save and Close All", "Save and Close All"},
                new Runnable[]{
                        () -> { if (host != null) host.closeAll(); },
                        () -> { if (host != null) host.closeAllWithoutSaving(); },
                        () -> { if (host != null) host.saveAndCloseAll(); }}));
        save.setDropDown("Save every tab", () -> FlowMenus.above(save, new String[]{"Save All"},
                new Runnable[]{() -> { if (host != null) host.saveAll(); }}));
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, JBUI.scale(8), 0));
        buttons.add(close);
        buttons.add(save);
        buttons.add(reset);
        buttons.setBorder(JBUI.Borders.emptyTop(8));
        root.add(createCenterPanel(), BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        root.setBorder(JBUI.Borders.empty(10, 12, 10, 12));
    }

    /** The page is on screen. */
    void shown() {
        refresh();
        // once the tab is on screen (asked before that, the focus stayed on the tab strip and Ctrl+V went nowhere)
        javax.swing.SwingUtilities.invokeLater(canvas::requestFocusInWindow);
    }

    private void close() {
        if (host != null) host.remove(this);
    }

    private enum Tool { SELECT, CONNECT }

    private final Project project;
    private final FlowStore store;
    /** The flow's lines the page is on (those at its opening, then as the flow changed while it was open). */
    private List<FlowEntry> entriesAtOpen;
    private String flowName;
    private final FlowDiagramPainter painter = new FlowDiagramPainter(JBUI.Fonts.label());

    /** This window's copy of the flow's history; stored back on Apply. */
    private final FlowDiagram.History history;
    private final int openPosition;
    private String openSignature;
    /** The diagram as the window opened it (to tell whether anything was done). */
    private FlowDiagram openDiagram;
    private FlowDiagram diagram;
    private Tool tool = Tool.SELECT;
    private FlowDiagramPainter.Selection selection = FlowDiagramPainter.Selection.NONE;

    private final Canvas canvas = new Canvas();
    /** The selected method's code, under the drawing area. */
    private @Nullable FlowDiagramCode code;
    private final DefaultListModel<FlowDiagram.History.Step> historyModel = new DefaultListModel<>();
    private final JBList<FlowDiagram.History.Step> historyList = new JBList<>(historyModel);
    private final JBLabel status = new JBLabel();
    private final JBLabel historyTitle = new JBLabel();
    private FlowButton selectTool, connectTool, addMethod, rename, delete, undo, redo, settings, format;
    private final FlowLook look = FlowLook.get();
    private Runnable lookChanged = () -> {};
    /** Light / dark switch in the top right corner of the drawing area. */
    private final FlowButton backgroundToggle = new FlowButton("", FlowTheme.ICON_DARK, FlowButton.Style.OUTLINE);
    /** Zoom buttons in the bottom right corner of the drawing area, and the zoom they set (1 = 100 %). */
    private final JPanel zoomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0));
    private FlowButton zoomIn, zoomOut, zoomReset, searchButton;
    private double zoom = 1;
    private static final double[] ZOOMS = {0.25, 0.33, 0.5, 0.67, 0.75, 0.9, 1, 1.1, 1.25, 1.5, 1.75, 2, 2.5, 3};
    /** Space kept between boxes when they are moved apart. */
    private static final double BOX_GAP = 24;

    private FlowDiagramDialog(Project project, FlowStore store, List<FlowEntry> entries, FlowSync.Calls calls) {
        this.project = project;
        this.store = store;
        this.entriesAtOpen = entries;
        this.flowName = store.getActiveFlowName();

        FlowDiagram.History stored = store.getDiagramHistory();
        history = stored == null ? new FlowDiagram.History() : stored.copy();
        FlowDiagram fresh = FlowDiagram.of(entries, calls.links());
        painter.autoLayout(fresh);
        String label;
        if (history.steps.isEmpty()) {
            label = "Open “" + flowName + "”";
        } else {
            fresh.keepPositions(history.current().diagram());
            label = history.continues(entries) ? "Reopen" : "Reopen (the flow changed outside the diagram)";
        }
        // Positions, colors, formats, notes and lines saved with the flow (they follow the toolbar's Undo too).
        fresh.applyLooks(store.getDiagramLooks());
        fresh.spreadOut(painter.measure(), BOX_GAP, null); // e.g. a box grew since it was placed
        history.open(label, fresh, entries);
        openPosition = history.position;
        diagram = history.current().diagram().copy();
        openSignature = diagram.signature();
        openDiagram = diagram.copy();

        backgroundToggle.addActionListener(e -> toggleBackground());
        Disposer.register(getDisposable(), () -> look.removeListener(lookChanged));
        store.addListener(flowListener);
        Disposer.register(getDisposable(), () -> store.removeListener(flowListener));
    }

    // ---------- the flow changed while the page is open ----------

    /** Lines added to the flow (or removed, undone…) in the Flow window show here at once. */
    private final Runnable flowListener = () -> javax.swing.SwingUtilities.invokeLater(this::flowChanged);

    private void flowChanged() {
        if (disposed || applied || project.isDisposed() || !store.getFlowNames().contains(flowName)) return;
        List<FlowEntry> now = store.getEntriesOf(flowName);
        if (now.equals(entriesAtOpen)) return;
        boolean active = flowName.equals(store.getActiveFlowName());
        syncTo(now, active ? store.getCalls().links() : FlowSync.withManual(FlowSync.Calls.NONE, now).links(), true);
        // the calls the new lines make (found in the background): their lines between the classes
        FlowSync.callsFor(project, List.of(now), result -> {
            if (disposed || applied || !now.equals(entriesAtOpen)) return;
            syncTo(now, FlowSync.withManual(result.get(0), now).links(), false);
        });
    }

    /**
     * Brings the page (and each step of its history, so Undo and Save still work) to the flow's lines now: the
     * classes and methods of new lines are added, the ones whose lines are all gone leave.
     *
     * @param tell true to say in the status line what came in or went
     */
    private void syncTo(List<FlowEntry> now, java.util.Set<String> calls, boolean tell) {
        java.util.Set<FlowEntry> before = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        before.addAll(entriesAtOpen);
        java.util.Set<FlowEntry> nowSet = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        nowSet.addAll(now);
        List<FlowEntry> added = new ArrayList<>();
        for (FlowEntry e : now) if (!before.contains(e)) added.add(e);
        java.util.Set<FlowEntry> removed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (FlowEntry e : entriesAtOpen) if (!nowSet.contains(e)) removed.add(e);

        int start = Math.max(diagram.lastId(), openDiagram.lastId());
        for (FlowDiagram.History.Step s : history.steps) start = Math.max(start, s.diagram().lastId());
        FlowDiagram.IdPool pool = new FlowDiagram.IdPool(start);
        FlowDiagram.Measure measure = painter.measure();
        List<FlowEntry> currentBase = rebase(history.current().base(), now, added, removed);
        for (int i = 0; i < history.steps.size(); i++) {
            FlowDiagram.History.Step s = history.steps.get(i);
            List<FlowEntry> base = rebase(s.base(), now, added, removed);
            history.steps.set(i, new FlowDiagram.History.Step(s.label(),
                    s.diagram().synced(base, added, calls, pool, measure, BOX_GAP), base, s.applied(), s.open()));
        }
        diagram = diagram.synced(currentBase, added, calls, pool, measure, BOX_GAP);
        openDiagram = openDiagram.synced(currentBase, added, calls, pool, measure, BOX_GAP);
        openSignature = openDiagram.signature();
        entriesAtOpen = now;
        if (waysPanel != null && (diagram.method(waysOf) == null)) hideWays();
        if (selection != FlowDiagramPainter.Selection.NONE && !stillThere(selection)) selection = FlowDiagramPainter.Selection.NONE;
        refresh();
        if (!tell) return;
        java.util.Set<String> in = new java.util.LinkedHashSet<>(), out = new java.util.LinkedHashSet<>();
        for (FlowEntry e : added) in.add(FlowDiagram.className(e) + "." + com.example.flowtracker.copy.FlowFormat.methodName(e));
        for (FlowEntry e : removed) out.add(FlowDiagram.className(e) + "." + com.example.flowtracker.copy.FlowFormat.methodName(e));
        String msg = (in.isEmpty() ? "" : "From the flow: " + String.join(", ", in) + ". ")
                + (out.isEmpty() ? "" : "Left the flow: " + String.join(", ", out) + ".");
        if (!msg.isBlank()) say(msg.strip());
    }

    /** True while the diagram still has what the selection points at. */
    private boolean stillThere(FlowDiagramPainter.Selection sel) {
        if (sel.methodId() != null) return diagram.method(sel.methodId()) != null;
        if (sel.boxId() != null) return diagram.box(sel.boxId()) != null;
        return sel.link() == null || diagram.links.contains(sel.link());
    }

    /** A step's lines with the flow's change: in the flow's order, without the removed lines, with the added ones. */
    private static List<FlowEntry> rebase(List<FlowEntry> base, List<FlowEntry> now, List<FlowEntry> added,
                                          java.util.Set<FlowEntry> removed) {
        java.util.Set<FlowEntry> in = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        in.addAll(base);
        in.addAll(added);
        java.util.Set<FlowEntry> nowSet = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        nowSet.addAll(now);
        List<FlowEntry> out = new ArrayList<>();
        for (FlowEntry e : now) if (in.contains(e)) out.add(e);
        for (FlowEntry e : base) if (!nowSet.contains(e) && !removed.contains(e)) out.add(e); // an earlier step's own lines
        return out;
    }

    // ---------- layout ----------

    private JComponent createCenterPanel() {
        selectTool = button("Select", FlowTheme.ICON_SELECT, FlowButton.Style.SEGMENT,
                "Select and move classes, methods and calls. Double-click a class name to change its class, its methods to add a method.",
                () -> setTool(Tool.SELECT));
        connectTool = button("Connect", FlowTheme.ICON_CONNECT, FlowButton.Style.SEGMENT,
                "Drag from a method to the method it calls", () -> setTool(Tool.CONNECT));
        FlowButton addClass = button("Add File", FlowTheme.ICON_CLASS, FlowButton.Style.OUTLINE,
                "Add a file of the project (Java, Kotlin, C#, Go, Python, JavaScript, JSP, XML…) or a library's .class, then "
                        + "pick its class when it has several. Search by name, pattern (*Cruise*.java) or /regex/. A file "
                        + "that isn't in the project becomes a custom class.",
                () -> addKnownClass(null));
        addMethod = button("Add Function", FlowTheme.ICON_METHOD, FlowButton.Style.OUTLINE,
                "Add a method, function or block of the selected class or file and pick its lines; a name it doesn't have "
                        + "becomes a custom method", this::addKnownMethod);
        format = button("Format", FlowTheme.ICON_FORMAT, FlowButton.Style.OUTLINE,
                "Format the selection: a box's colors, bold / italic / colored names, the notes under class and "
                        + "method names, or a call's line", this::formatSelection);
        rename = button("Rename", FlowTheme.ICON_NOTE, FlowButton.Style.OUTLINE,
                "Rename the selected class or method (F2)", this::renameSelection);
        delete = button("Delete", FlowTheme.ICON_DELETE, FlowButton.Style.OUTLINE,
                "Delete the selected class, method or call (Delete)", this::deleteSelection);
        undo = button("Undo", FlowTheme.ICON_UNDO, FlowButton.Style.OUTLINE, null, this::undo);
        redo = button("Redo", FlowTheme.ICON_REDO, FlowButton.Style.OUTLINE, null, this::redo);
        FlowButton layout = button("Auto Layout", FlowTheme.ICON_LAYOUT, FlowButton.Style.OUTLINE,
                "Arrange the classes in call order, left to right; lines and notes moved by hand find their own way again", this::autoLayout);
        FlowButton copyPng = button("Copy PNG", FlowTheme.ICON_IMAGE, FlowButton.Style.OUTLINE,
                "Copy this diagram as a PNG image", () -> copyClassImage(look.getImageBackground()));
        copyPng.setDropDown("Copy with a light or dark background", () -> backgroundMenu(copyPng, this::copyClassImage));
        FlowButton copySequence = button("Copy Sequence PNG", FlowTheme.ICON_SEQUENCE, FlowButton.Style.OUTLINE,
                "Copy the calls as a sequence diagram, as a PNG image", () -> copySequenceImage(look.getImageBackground()));
        copySequence.setDropDown("Copy with a light or dark background", () -> backgroundMenu(copySequence, this::copySequenceImage));
        settings = button("Settings", FlowTheme.ICON_SETTINGS, FlowButton.Style.OUTLINE,
                "Backgrounds of this window and of the copied images", () -> settingsMenu(settings));
        updateCopyTips(copyPng, copySequence);
        painter.linesOverBoxes = look.isLinesOverBoxes();
        look.addListener(lookChanged = () -> {
            updateCopyTips(copyPng, copySequence);
            painter.linesOverBoxes = look.isLinesOverBoxes();
            root.repaint();
        });

        FlowToolbar toolbar = new FlowToolbar();
        toolbar.addGroup(true, 2, selectTool, connectTool);
        toolbar.addGroup(false, 2, addClass, addMethod);
        toolbar.addGroup(false, 1, rename, delete);
        FlowButton resetTheme = button("Reset Theme", FlowTheme.ICON_THEME, FlowButton.Style.OUTLINE,
                "Give every class, method and line the default look again (notes, calls and positions stay)", this::resetTheme);
        toolbar.addGroup(false, 1, format, resetTheme);
        toolbar.addGroup(false, 0, undo, redo);
        toolbar.addGroup(false, 1, layout);
        // Copying and the window's settings sit at the end of the toolbar.
        toolbar.addTrailingGroup(1, copyPng, copySequence);
        toolbar.addTrailingGroup(1, settings);
        toolbar.setBorder(JBUI.Borders.emptyBottom(8));

        JBScrollPane scroll = com.example.flowtracker.ui.FlowScroll.wheel(new JBScrollPane(canvas));
        scroll.setBorder(JBUI.Borders.customLine(FlowTheme.CARD_BORDER));
        busy = new com.example.flowtracker.ui.FlowBusyOverlay(disposable);
        // The light / dark switch floats over the top right corner of the drawing area.
        JLayeredPane drawing = new JLayeredPane() {
            @Override
            public void doLayout() {
                scroll.setBounds(0, 0, getWidth(), getHeight());
                Dimension d = backgroundToggle.getPreferredSize();
                int inset = JBUI.scale(10) + (scroll.getVerticalScrollBar().isVisible() ? scroll.getVerticalScrollBar().getWidth() : 0);
                backgroundToggle.setBounds(getWidth() - d.width - inset, JBUI.scale(10), d.width, d.height);
                Dimension z = zoomBar.getPreferredSize();
                int bottom = JBUI.scale(10) + (scroll.getHorizontalScrollBar().isVisible() ? scroll.getHorizontalScrollBar().getHeight() : 0);
                zoomBar.setBounds(getWidth() - z.width - inset, getHeight() - z.height - bottom, z.width, z.height);
                busy.setBounds(0, 0, getWidth(), getHeight());
                if (waysPanel != null) { // the ways: above the zoom buttons, or where they were dragged to
                    Dimension w = waysPanel.getPreferredSize();
                    int width = Math.min(w.width, getWidth() - 2 * inset), zoomTop = getHeight() - z.height - bottom;
                    int height = waysPanel.isMinimized() ? w.height : Math.max(JBUI.scale(160), Math.min(w.height, zoomTop - JBUI.scale(20)));
                    int x = getWidth() - width - inset, y = Math.max(JBUI.scale(10), zoomTop - height - JBUI.scale(10));
                    if (waysAt != null) { // kept on the drawing
                        x = Math.max(0, Math.min(waysAt.x, getWidth() - width));
                        y = Math.max(0, Math.min(waysAt.y, getHeight() - height));
                    }
                    waysPanel.setBounds(x, y, width, height);
                }
            }

            @Override
            public Dimension getPreferredSize() {
                return scroll.getPreferredSize();
            }
        };
        drawing.add(scroll, JLayeredPane.DEFAULT_LAYER);
        drawing.add(backgroundToggle, JLayeredPane.PALETTE_LAYER);
        zoomOut = button("", FlowTheme.ICON_ZOOM_OUT, FlowButton.Style.OUTLINE, "Zoom out (Ctrl+Minus, or Ctrl+mouse wheel)", () -> zoomBy(-1, null));
        zoomReset = button("100%", null, FlowButton.Style.OUTLINE, "Back to 100% (Ctrl+0)", () -> setZoom(1, null));
        zoomIn = button("", FlowTheme.ICON_ZOOM_IN, FlowButton.Style.OUTLINE, "Zoom in (Ctrl+Plus, or Ctrl+mouse wheel)", () -> zoomBy(1, null));
        searchButton = button("", com.intellij.icons.AllIcons.Actions.Search, FlowButton.Style.OUTLINE,
                "Find a class or method (Ctrl+F)", () -> openSearch(true));
        zoomBar.setOpaque(false);
        zoomBar.add(searchButton);
        zoomBar.add(zoomOut);
        zoomBar.add(zoomReset);
        zoomBar.add(zoomIn);
        drawing.add(zoomBar, JLayeredPane.PALETTE_LAYER);
        drawing.add(busy, JLayeredPane.MODAL_LAYER); // "looking for the ways" over the drawing
        drawingPane = drawing;
        updateZoomBar();
        updateBackgroundToggle();

        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyList.setCellRenderer(new HistoryRenderer());
        historyList.addListSelectionListener(e -> {
            int i = historyList.getSelectedIndex();
            if (!e.getValueIsAdjusting() && i >= 0 && i != history.position) jumpTo(i);
        });
        JPanel side = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        historyTitle.setFont(JBUI.Fonts.label().asBold());
        side.add(historyTitle, BorderLayout.NORTH);
        side.add(com.example.flowtracker.ui.FlowScroll.wheel(new JBScrollPane(historyList, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)), BorderLayout.CENTER);
        JBLabel hint = new JBLabel("<html>Click a step to go back to it; Save uses the selected step. "
                + "✔ marks applied steps. The History stays while the IDE runs.</html>");
        hint.setForeground(FlowTheme.FG_MUTED);
        hint.setFont(JBUI.Fonts.smallFont());
        side.add(hint, BorderLayout.SOUTH);
        side.setBorder(JBUI.Borders.emptyLeft(8));

        code = new FlowDiagramCode(project);
        Disposer.register(getDisposable(), code);
        code.setLineAdder(this::addLineFromCode);
        code.setLineRemover(this::removeLineFromCode);
        code.setFlowLines(this::flowLinesOf);
        code.setVisible(false); // shown while a method is selected
        JBSplitter drawingAndCode = new JBSplitter(true, 0.68f);
        drawingAndCode.setFirstComponent(drawing);
        drawingAndCode.setSecondComponent(code);

        JBSplitter split = new JBSplitter(false, 0.76f);
        split.setFirstComponent(drawingAndCode);
        split.setSecondComponent(side);

        status.setForeground(FlowTheme.FG_MUTED);
        status.setBorder(JBUI.Borders.emptyTop(6));

        JPanel panel = new JPanel(new BorderLayout());
        pageToolbar = toolbar; // shown by the window above its tabs
        panel.add(split, BorderLayout.CENTER);
        panel.add(status, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(JBUI.scale(1180), JBUI.scale(820)));
        installKeys(panel);
        return panel;
    }

    private static FlowButton button(String text, @Nullable Icon icon, FlowButton.Style style, @Nullable String tip, Runnable action) {
        FlowButton b = new FlowButton(text, icon, style);
        if (tip != null) b.setToolTipText(tip);
        b.addActionListener(e -> action.run());
        return b;
    }

    private void installKeys(JComponent root) {
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo", this::undo);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "minimizeWays", () -> { if (waysPanel != null) waysPanel.setMinimized(true); });
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo", this::redo);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "redo2", this::redo);
        bind(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete", this::deleteSelection);
        bind(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "delete2", this::deleteSelection);
        bind(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "rename", this::renameSelection);
        bind(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copy", () -> copyPicked(false));
        bind(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK), "cut", () -> copyPicked(true));
        bind(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), "paste", this::pasteClipboard);
        bind(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK), "selectAll", this::selectAll);
        // the same anywhere in the window (e.g. right after a tab was made, with the focus on the tabs), but not while
        // typing in a field or the code panel, which have their own copy and paste
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copyHere", () -> { if (!typing()) copyPicked(false); });
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK), "cutHere", () -> { if (!typing()) copyPicked(true); });
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), "pasteHere", () -> { if (!typing()) pasteClipboard(); });
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK), "selectAllHere", () -> { if (!typing()) selectAll(); });
        bind(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK), "zoomIn", () -> zoomBy(1, null));
        bind(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, InputEvent.CTRL_DOWN_MASK), "zoomIn2", () -> zoomBy(1, null));
        bind(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_ADD, InputEvent.CTRL_DOWN_MASK), "zoomIn3", () -> zoomBy(1, null));
        bind(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK), "zoomOut", () -> zoomBy(-1, null));
        bind(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, InputEvent.CTRL_DOWN_MASK), "zoomOut2", () -> zoomBy(-1, null));
        bind(canvas, KeyStroke.getKeyStroke(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK), "zoomReset", () -> setZoom(1, null));
        // Ctrl+F: find a class or method (the IDE's own Ctrl+F stays in the code panel and the fields)
        com.intellij.openapi.project.DumbAwareAction.create(e -> { if (!typing()) openSearch(false); })
                .registerCustomShortcutSet(new com.intellij.openapi.actionSystem.CustomShortcutSet(
                        KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK)), root, disposable);
    }

    /** Find a class or method: above the search button, or (Ctrl+F) at the top of the drawing. */
    private void openSearch(boolean fromButton) {
        if (drawingPane == null) return;
        FlowDiagramSearch.show(drawingPane, fromButton ? searchButton : null, diagram, it -> {
            FlowDiagram.Box box = diagram.box(it.box().id);
            if (box == null) return;
            FlowDiagram.Method m = it.method() == null ? null : diagram.method(it.method().id);
            select(box, m, null);
            reveal(box, m);
            say("Found " + (m == null ? box.name : label(m)) + ".");
            javax.swing.SwingUtilities.invokeLater(canvas::requestFocusInWindow);
        });
    }

    /** Scrolls the drawing so a class (or one of its methods) is in the middle. */
    private void reveal(FlowDiagram.Box box, @Nullable FlowDiagram.Method m) {
        Rectangle2D r = m == null ? painter.bounds(box) : painter.rowBounds(box, Math.max(0, box.methods.indexOf(m)));
        double z = zoom;
        java.awt.Rectangle visible = canvas.getVisibleRect();
        int cx = (int) (r.getCenterX() * z), cy = (int) (r.getCenterY() * z);
        canvas.scrollRectToVisible(new java.awt.Rectangle(cx - visible.width / 2, cy - visible.height / 2, visible.width, visible.height));
        canvas.repaint();
    }

    /** True while the focus is in a text field or the code panel (they copy and paste text). */
    private static boolean typing() {
        java.awt.Component owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (owner instanceof javax.swing.text.JTextComponent) return true;
        for (com.intellij.openapi.editor.Editor ed : com.intellij.openapi.editor.EditorFactory.getInstance().getAllEditors()) {
            if (owner != null && javax.swing.SwingUtilities.isDescendingFrom(owner, ed.getComponent())) return true;
        }
        return false;
    }

    private static void bind(JComponent c, KeyStroke key, String name, Runnable action) {
        int when = c instanceof Canvas ? JComponent.WHEN_FOCUSED : JComponent.WHEN_IN_FOCUSED_WINDOW;
        c.getInputMap(when).put(key, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    // ---------- history ----------

    /**
     * Records a change made to the diagram (dropping the steps that were undone). When it left the diagram as it
     * was (e.g. Auto Layout clicked again), nothing is added to the History.
     */
    private void changed(String label) {
        // Boxes never cover each other: the one just edited or moved stays, the others make room.
        diagram.spreadOut(painter.measure(), BOX_GAP, selectedBox());
        if (!history.change(label, diagram)) say("Nothing changed: “" + label + "” leaves the diagram as it is.");
        refresh();
    }

    private void undo() {
        if (history.position > 0) jumpTo(history.position - 1);
    }

    private void redo() {
        if (history.position < history.steps.size() - 1) jumpTo(history.position + 1);
    }

    private void jumpTo(int index) {
        history.position = index;
        diagram = history.current().diagram().copy();
        // Keep the selection only when it still exists.
        if (selection.methodId() != null && diagram.method(selection.methodId()) == null
                || selection.boxId() != null && diagram.box(selection.boxId()) == null
                || selection.link() != null && !diagram.links.contains(selection.link())) {
            selection = FlowDiagramPainter.Selection.NONE;
        }
        refresh();
    }

    /** Updates buttons, the History list and the canvas after any change. */
    private void refresh() {
        selectTool.setSelected(tool == Tool.SELECT);
        connectTool.setSelected(tool == Tool.CONNECT);
        FlowDiagram.Box box = selectedBox();
        addMethod.setEnabled(box != null);
        rename.setEnabled(box != null);
        delete.setEnabled(box != null || selection.link() != null && selection.link().manual());
        format.setEnabled(box != null || selection.link() != null);
        int pos = history.position, last = history.steps.size() - 1;
        undo.setEnabled(pos > 0);
        undo.setToolTipText(pos > 0 ? "Undo: " + history.current().label() + " (Ctrl+Z)" : "nothing to undo");
        redo.setEnabled(pos < last);
        redo.setToolTipText(pos < last ? "Redo: " + history.steps.get(pos + 1).label() + " (Ctrl+Y)" : "nothing to redo");

        if (!sameHistory()) {
            historyModel.clear();
            for (FlowDiagram.History.Step s : history.steps) historyModel.addElement(s);
        }
        if (historyList.getSelectedIndex() != pos) historyList.setSelectedIndex(pos);
        historyList.ensureIndexIsVisible(pos);
        historyList.repaint();
        int steps = history.steps.size() - 1;
        historyTitle.setText("History" + (steps <= 0 ? "" : " (" + steps + (steps == 1 ? " step)" : " steps)")));
        if (status.getText().isEmpty()) showToolHint();
        updateCode();
        canvas.revalidate();
        canvas.repaint();
    }

    /** The code panel follows the selected method. */
    private void updateCode() {
        if (code == null) return;
        FlowDiagram.Method m = selectedMethod();
        FlowDiagram.Box b = m == null ? null : diagram.boxOf(m.id);
        boolean visible = m != null && b != null;
        if (code.isVisible() != visible) {
            code.setVisible(visible);
            code.getParent().revalidate();
        }
        if (!visible) return;
        String name = b.name + "." + m.name;
        if (b.isUnknown() || m.unknown && m.picked.isEmpty()) {
            code.showMessage(name, name + " is custom: it has no code yet.");
            return;
        }
        code.showMethod(name, b.fileName, b.fileUrl, codeLines(m));
    }

    // ---------- the calls between two methods ----------

    /** Connect the Flow to…: pick the method the flow should reach; the calls in between are looked up in the code. */
    private void pickPathEnd(FlowDiagram.Method from) {
        List<FlowDiagram.Method> others = new ArrayList<>();
        for (FlowDiagram.Box b : diagram.boxes) for (FlowDiagram.Method m : b.methods) if (m != from && !b.isUnknown()) others.add(m);
        PATH_LOG.info("Connect the Flow to…: from " + label(from) + ", " + others.size() + " methods to pick");
        if (others.isEmpty()) {
            tell("Add the class and method the flow should reach first (Add File / Add Function), "
                    + "then right-click " + label(from) + " → Connect the Flow to…", false);
            return;
        }
        FlowDiagramSearch.showConnectTargets(drawingPane != null ? drawingPane : canvas, "Connect " + label(from) + " to…",
                diagram, from, it -> {
                    FlowDiagram.Method to = it.method() == null ? null : diagram.method(it.method().id);
                    if (to != null) findPath(from, to);
                });
    }

    /** A flow line of a method: its first line in the flow (or picked here). */
    private @Nullable FlowEntry firstLine(FlowDiagram.Method m) {
        FlowDiagram.Box b = diagram.boxOf(m.id);
        List<Integer> lines = codeLines(m);
        if (b == null || lines.isEmpty()) return null;
        return FlowCompat.read(() -> FlowCodeSearch.lineEntry(project, b.fileUrl, lines.get(0)));
    }

    /**
     * Looks up the code from one method to another (in the background) and adds it to the diagram. Each of the first
     * method's flow lines is tried as the start (what it starts is followed); the closest way wins.
     */
    private void findPath(FlowDiagram.Method from, FlowDiagram.Method to) {
        FlowDiagram.Box fb = diagram.boxOf(from.id);
        List<FlowEntry> starts = new ArrayList<>();
        if (fb != null) {
            for (int line : codeLines(from)) {
                FlowEntry s = FlowCompat.read(() -> FlowCodeSearch.lineEntry(project, fb.fileUrl, line));
                if (s != null) starts.add(s);
            }
        }
        FlowEntry e = firstLine(to);
        PATH_LOG.info("Connect " + label(from) + " → " + label(to) + ": " + starts.size() + " start lines, end " + e);
        if (starts.isEmpty() || e == null) {
            tell("Both methods need lines in the code to connect them (" + label(from) + ": " + starts.size()
                    + " lines, " + label(to) + ": " + (e == null ? "none" : "1") + ").", false);
            return;
        }
        if (busy.running()) return; // one search at a time
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        busy.start("Looking for the ways from " + label(from) + " to " + label(to), "Reading the code…",
                "Following the calls (up to " + 12 + " deep) for up to " + com.example.flowtracker.code.FlowCallPath.MAX_WAYS + " ways",
                () -> stop.set(true));
        say("Looking for the ways from " + label(from) + " to " + label(to) + "…");
        new com.intellij.openapi.progress.Task.Backgroundable(project, "Finding the ways between two methods", true) {
            private com.example.flowtracker.code.FlowCallPath.Ways found;

            @Override
            public void run(@NotNull com.intellij.openapi.progress.ProgressIndicator indicator) {
                // a method connected to a method: all of it is looked at, following what its flow lines work with
                com.example.flowtracker.code.FlowCallPath.Progress progress = new com.example.flowtracker.code.FlowCallPath.Progress() {
                    @Override public boolean cancelled() {
                        return stop.get() || indicator.isCanceled();
                    }

                    @Override public void read(int methods, int ways) {
                        String text = methods + (methods == 1 ? " method" : " methods") + " read · " + ways
                                + (ways == 1 ? " way" : " ways") + " found";
                        indicator.setText2(text);
                        javax.swing.SwingUtilities.invokeLater(() -> busy.progress(text));
                    }
                };
                found = FlowCompat.read(() -> com.example.flowtracker.code.FlowCallPath.ways(project, starts.get(0), e, starts,
                        com.example.flowtracker.code.FlowCallPath.MAX_WAYS, progress));
                PATH_LOG.info("  " + found.message() + " " + found.ways().stream().map(w -> w.chain()).toList());
            }

            @Override
            public void onSuccess() {
                busy.stop();
                if (isDisposed()) return;
                if (found == null || found.ways().isEmpty()) {
                    tell(found == null ? "No way was found." : found.message(), false);
                    return;
                }
                // the ways in a large window first; afterwards, selecting the method shows them as a bar in the tab
                // (open it: hover a way to see it on the diagram, click it to add it)
                Found f = new Found(found, from.id, to.id);
                waysFor.put(from.id, f);
                List<com.example.flowtracker.code.FlowCallPath.Way> all = found.ways();
                com.example.flowtracker.code.FlowCallPath.Way pick = com.example.flowtracker.ui.FlowWaysDialog.choose(project, canvas, found,
                        label(from), label(to), "Add This Way", FlowDiagramDialog.this::whereOnTab, "this tab", FlowDiagramDialog.this::wayOnTab,
                        w -> previewWay(f, w)); // the way under the mouse, drawn on the diagram behind the window
                if (pick != null) {
                    addPath(from, to, pick.lines(), all.size() == 1 ? found.message()
                            : "Added way " + (all.indexOf(pick) + 1) + " of " + all.size() + ": " + pick.chain() + ".");
                } else {
                    say(found.message() + " The ways stay in the bar above the zoom buttons.");
                }
                showWays(from.id, true); // from now on: the bar on the diagram (a click opens it)
            }

            @Override
            public void onCancel() {
                busy.stop();
                if (!isDisposed()) say("Stopped looking for the ways.");
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                busy.stop();
                PATH_LOG.warn("Connect the Flow failed", error);
                if (!isDisposed()) tell("Couldn't read the code between them: " + error, false);
            }
        }.queue();
    }

    /** "Looking for the ways…" over the drawing while Connect the Flow reads the code. */
    private com.example.flowtracker.ui.FlowBusyOverlay busy;

    /** The calls' lines become methods (boxes added where needed), connected from {@code from} to {@code to}. */
    private void addPath(FlowDiagram.Method from, FlowDiagram.Method to, List<FlowEntry> lines, String message) {
        int added = addWay(from, to, lines, false);
        changed("Connect " + label(from) + " to " + label(to));
        tell(message + (added == 0 ? "" : " Added " + added + (added == 1 ? " line" : " lines") + " (Save saves them to the flow)."), true);
    }

    /** A way preview: violet (new boxes, methods and calls), different from everything else on the diagram. */
    private static final com.intellij.ui.JBColor PREVIEW = new com.intellij.ui.JBColor(0x7B4FD6, 0xB08CFF);
    private static final com.intellij.ui.JBColor PREVIEW_FILL = new com.intellij.ui.JBColor(0xF1EBFF, 0x2B2340);

    /**
     * Adds a way's lines to the diagram: boxes and methods where needed, connected from {@code from} to {@code to},
     * laid out left to right. As a preview, what it adds is drawn in violet (dashed, thick calls).
     *
     * @return how many lines were added
     */
    private int addWay(FlowDiagram.Method from, FlowDiagram.Method to, List<FlowEntry> lines, boolean preview) {
        FlowDiagram.Box start = diagram.boxOf(from.id);
        double x = start == null ? 40 : start.x + painter.width(start) + 80, y = start == null ? 40 : start.y + 60;
        FlowDiagram.Method prev = from;
        int added = 0;
        for (FlowEntry e : lines) {
            String name = e.methodName == null || e.methodName.isEmpty() ? "[fields]" : e.methodName;
            FlowDiagram.Box box = null;
            for (FlowDiagram.Box b : diagram.boxes) {
                if (e.fileUrl.equals(b.fileUrl) && (box == null || b.origClass.equals(e.className))) box = b;
            }
            if (box == null) {
                box = diagram.addKnownClass(e.fileUrl, e.fileName, e.projectName == null ? "" : e.projectName, e.className, null, x, y);
                x += 60;
                y += 40;
                if (preview) {
                    box.border = PREVIEW;
                    box.fill = PREVIEW_FILL;
                    box.headerFill = PREVIEW_FILL;
                }
            }
            FlowDiagram.Method m = null;
            for (FlowDiagram.Method x2 : box.methods) if (x2.name.equals(name)) m = x2;
            if (m == null || !codeLines(m).contains(e.line - 1)) {
                m = diagram.addKnownMethod(box, name, List.of(e));
                added++;
                if (preview) m.format = new FlowDiagram.Format(true, false, false, false, PREVIEW);
            }
            if (m != prev) previewCall(prev, m, preview);
            prev = m;
        }
        if (prev != to) previewCall(prev, to, preview);
        diagram.autoLayout(painter.measure(), 180, 50, 40); // the new way reads left to right
        return added;
    }

    /** A call the code makes (solid); in a preview, a new one is violet, dashed and thick. */
    private void previewCall(FlowDiagram.Method from, FlowDiagram.Method to, boolean preview) {
        boolean made = diagram.connect(from.id, to.id, false);
        if (made && preview) {
            diagram.setLine(new FlowDiagram.Link(from.id, to.id, false, null), new FlowDiagram.Line(FlowDiagram.LinePattern.DASHED,
                    FlowDiagram.LineWidth.THICK, FlowDiagram.LineShape.CURVED, FlowDiagram.ArrowHead.FILLED, PREVIEW));
        }
    }

    // ---------- the ways panel: hover to preview, click to add ----------

    /** Ways found from a method to another (kept per method: clicking it shows them again). */
    private record Found(com.example.flowtracker.code.FlowCallPath.Ways ways, String fromId, String toId) {}
    private final java.util.Map<String, Found> waysFor = new java.util.HashMap<>();
    private @Nullable com.example.flowtracker.ui.FlowWaysPanel waysPanel;
    private @Nullable JLayeredPane drawingPane;
    /** The diagram as it was before a way was previewed (null: no preview). */
    private @Nullable FlowDiagram previewBase;

    /** Which method the ways panel is for. */
    private @Nullable String waysOf;

    /**
     * The ways found from a method, above the zoom buttons: open (right after the search), or minimized to a bar (when
     * the method is selected again; a click opens it).
     */
    private void showWays(String fromId, boolean minimized) {
        Found f = waysFor.get(fromId);
        FlowDiagram.Method from = f == null ? null : diagram.method(f.fromId()), to = f == null ? null : diagram.method(f.toId());
        if (f == null || from == null || to == null || drawingPane == null) {
            waysFor.remove(fromId);
            return;
        }
        hideWays();
        waysPanel = new com.example.flowtracker.ui.FlowWaysPanel(f.ways(), label(from), label(to), this::whereOnTab,
                w -> previewWay(f, w), w -> applyWay(f, w), () -> {
                    clearPreview(); // minimized or opened: no preview left on the diagram
                    if (drawingPane != null) {
                        drawingPane.revalidate();
                        drawingPane.repaint();
                    }
                }, () -> fullScreenWays(f));
        waysOf = fromId;
        waysPanel.setThere(this::wayOnTab);
        waysPanel.onMove(by -> {
            if (waysPanel == null || drawingPane == null) return;
            java.awt.Point at = waysAt != null ? waysAt : waysPanel.getLocation();
            waysAt = new java.awt.Point(at.x + by.x, at.y + by.y);
            drawingPane.doLayout();
            drawingPane.repaint();
        });
        if (minimized) waysPanel.setMinimized(true);
        drawingPane.add(waysPanel, JLayeredPane.POPUP_LAYER);
        drawingPane.revalidate();
        drawingPane.repaint();
    }

    /** Where the ways panel was dragged to (null: its own place, above the zoom buttons); it goes back when hidden. */
    private @Nullable java.awt.Point waysAt;

    private void hideWays() {
        waysAt = null;
        clearPreview();
        if (waysPanel != null && drawingPane != null) {
            drawingPane.remove(waysPanel);
            drawingPane.repaint();
        }
        waysPanel = null;
        waysOf = null;
    }

    /**
     * True when a way is on this tab already: each of its methods is there, and each calls the next one (a way
     * through methods the tab has for other calls isn't). The way shown on hover doesn't count.
     */
    private boolean wayOnTab(com.example.flowtracker.code.FlowCallPath.Way w) {
        FlowDiagram d = previewBase != null ? previewBase : diagram;
        List<com.example.flowtracker.code.FlowCallPath.Step> steps = w.steps();
        if (steps.size() < 2) return false;
        FlowDiagram.Method prev = methodOnTab(d, steps.get(0));
        for (int i = 1; i < steps.size(); i++) {
            FlowDiagram.Method next = methodOnTab(d, steps.get(i));
            if (prev == null || next == null) return false;
            boolean linked = false;
            for (FlowDiagram.Link l : d.links) linked |= l.from().equals(prev.id) && l.to().equals(next.id);
            if (!linked) return false;
            prev = next;
        }
        return true;
    }

    private @Nullable FlowDiagram.Method methodOnTab(FlowDiagram d, com.example.flowtracker.code.FlowCallPath.Step s) {
        for (FlowDiagram.Box b : d.boxes) {
            if (FlowDiagramTabs.isLink(b) || !(b.name.equals(s.className()) || simpleName(b.origClass).equals(s.className()))) continue;
            for (FlowDiagram.Method m : b.methods) if (baseName(m.name).equals(s.methodName())) return m;
        }
        return null;
    }

    /** Where a way's step is on this tab: its method is there, its class is, or it is new. */
    private com.example.flowtracker.ui.FlowWaysList.Where whereOnTab(com.example.flowtracker.code.FlowCallPath.Step s) {
        boolean cls = false;
        for (FlowDiagram.Box b : (previewBase != null ? previewBase : diagram).boxes) {
            if (FlowDiagramTabs.isLink(b) || !(b.name.equals(s.className()) || simpleName(b.origClass).equals(s.className()))) continue;
            cls = true;
            for (FlowDiagram.Method m : b.methods) {
                if (baseName(m.name).equals(s.methodName())) return com.example.flowtracker.ui.FlowWaysList.Where.METHOD_THERE;
            }
        }
        return cls ? com.example.flowtracker.ui.FlowWaysList.Where.CLASS_THERE : com.example.flowtracker.ui.FlowWaysList.Where.NEW;
    }

    /** The mouse is over a way: it is drawn on the diagram (violet), on a copy; null: back to the diagram as it is. */
    private void previewWay(Found f, @Nullable com.example.flowtracker.code.FlowCallPath.Way w) {
        if (w == null) {
            clearPreview();
            return;
        }
        if (previewBase == null) previewBase = diagram;
        diagram = previewBase.copy();
        FlowDiagram.Method from = diagram.method(f.fromId()), to = diagram.method(f.toId());
        if (from != null && to != null) addWay(from, to, w.lines(), true);
        refresh();
    }

    private void clearPreview() {
        if (previewBase == null) return;
        diagram = previewBase;
        previewBase = null;
        refresh();
    }

    /** The ways in the large window (Full Screen); the way picked there is added. */
    private void fullScreenWays(Found f) {
        hideWays();
        FlowDiagram.Method from = diagram.method(f.fromId()), to = diagram.method(f.toId());
        if (from == null || to == null) return;
        com.example.flowtracker.code.FlowCallPath.Way pick = com.example.flowtracker.ui.FlowWaysDialog.choose(project, canvas, f.ways(),
                label(from), label(to), "Add This Way", this::whereOnTab, "this tab", this::wayOnTab, w -> previewWay(f, w));
        if (pick != null) applyWay(f, pick);
        else showWays(f.fromId(), true);
    }

    /**
     * A click on a method with ways: its ways in the bar. A click anywhere else (another class or method, the empty
     * canvas) puts the minimized bar away: it belongs to its method; an open panel stays until it is minimized.
     */
    private void waysOnClick(FlowDiagramPainter.Hit hit) {
        if (previewBase != null) return;
        FlowDiagram.Method method = hit.method();
        if (method != null && waysFor.containsKey(method.id)) {
            if (waysPanel == null || !method.id.equals(waysOf)) showWays(method.id, true);
            return;
        }
        if (waysPanel == null || !waysPanel.isMinimized()) return;
        // the class box of its own method (its name, its other rows) keeps it; any other class, method or space doesn't
        FlowDiagram.Box own = waysOf == null ? null : diagram.boxOf(waysOf);
        boolean sameBox = own != null && hit.box() == own && hit.method() == null;
        if (!sameBox && hit.link() == null) hideWays();
    }

    /** A way clicked: it is added (one step of the History). */
    private void applyWay(Found f, com.example.flowtracker.code.FlowCallPath.Way w) {
        java.awt.Point keep = waysAt; // where it was dragged to stays (only hiding it puts it back)
        hideWays();
        FlowDiagram.Method from = diagram.method(f.fromId()), to = diagram.method(f.toId());
        if (from == null || to == null) return;
        List<com.example.flowtracker.code.FlowCallPath.Way> all = f.ways().ways();
        addPath(from, to, w.lines(), all.size() == 1 ? f.ways().message()
                : "Added way " + (all.indexOf(w) + 1) + " of " + all.size() + ": " + w.chain() + ".");
        waysAt = keep;
        showWays(f.fromId(), true); // back to the bar
    }

    /** The flow's lines of a file (0-based): the flow's own and the ones picked here. */
    private List<Integer> flowLinesOf(String url) {
        java.util.TreeSet<Integer> lines = new java.util.TreeSet<>();
        Set<String> dropped = new HashSet<>();
        for (FlowDiagram.Box b : diagram.boxes) for (FlowDiagram.Method m : b.methods) dropped.addAll(m.dropped);
        for (FlowEntry e : entriesAtOpen) {
            if (e.line > 0 && url.equals(e.fileUrl) && !dropped.contains(e.fileUrl + "#" + e.line)) lines.add(e.line - 1);
        }
        for (FlowDiagram.Box b : diagram.boxes) {
            for (FlowDiagram.Method m : b.methods) for (FlowEntry e : m.picked) if (e.line > 0 && url.equals(e.fileUrl)) lines.add(e.line - 1);
        }
        return new ArrayList<>(lines);
    }

    /**
     * "+" next to a line number in the code view: the line joins the flow, in its method's box (as Add Function would
     * add it; Apply saves it). A file without a box in the diagram has to be added first (Add File).
     */
    private void addLineFromCode(String url, int line) {
        if (flowLinesOf(url).contains(line)) {
            say("Line " + (line + 1) + " is in the flow already.");
            return;
        }
        FlowEntry e = FlowCompat.read(() -> FlowCodeSearch.entry(project, url, line));
        if (e == null) {
            say("That line can't be added (it isn't code of the project).");
            return;
        }
        FlowDiagram.Box box = null;
        for (FlowDiagram.Box b : diagram.boxes) {
            if (!url.equals(b.fileUrl)) continue;
            if (box == null || b.origClass.equals(e.className)) box = b;
        }
        if (box == null) {
            say(e.fileName + " has no box in this diagram yet: add it with Add File, then add the line.");
            return;
        }
        String name = e.methodName == null || e.methodName.isEmpty() ? "[fields]" : e.methodName;
        FlowDiagram.Method m = diagram.addKnownMethod(box, name, List.of(e));
        select(box, m, null);
        changed("Add line " + (line + 1) + " to " + box.name + "." + m.name);
        say("Added line " + (line + 1) + " to " + box.name + "." + m.name + " (Save saves it to the flow).");
    }

    /**
     * "−" next to a flow line in the code view: the line leaves its method (Apply takes it out of the flow). Not when
     * the line makes a call that is drawn to another box, or when it is the last line of a method that is connected
     * to other boxes: then the reason is returned (shown under the code).
     */
    private @Nullable String removeLineFromCode(String url, int line) {
        FlowDiagram.Box box = null;
        FlowDiagram.Method m = null;
        for (FlowDiagram.Box b : diagram.boxes) {
            if (!url.equals(b.fileUrl) || FlowDiagramTabs.isLink(b)) continue;
            for (FlowDiagram.Method x : b.methods) {
                if (codeLines(x).contains(line)) {
                    box = b;
                    m = x;
                }
            }
        }
        if (m == null) return "Line " + (line + 1) + " isn't a line of a method in this diagram.";
        FlowDiagram.Method method = m;
        FlowDiagram.Box home = box;
        // the calls this line makes, as "Class.method"
        Set<String> calls = callsOnLine(url, line);
        for (FlowDiagram.Link l : diagram.links) {
            if (!l.from().equals(method.id)) continue;
            FlowDiagram.Method t = diagram.method(l.to());
            FlowDiagram.Box tb = diagram.boxOf(l.to());
            if (t == null || tb == null || tb == home) continue;
            String name = baseName(t.name);
            if (calls.contains(simpleName(tb.origClass) + "." + name) || calls.contains(tb.name + "." + name)) {
                return "Line " + (line + 1) + " can't be taken out: it calls " + tb.name + "." + t.name + ", which is connected "
                        + "to the " + tb.name + " box. Remove that call (or the " + tb.name + " box) first.";
            }
        }
        if (codeLines(method).size() == 1) {
            for (FlowDiagram.Link l : diagram.links) {
                boolean out = l.from().equals(method.id), in = l.to().equals(method.id);
                if (!out && !in) continue;
                FlowDiagram.Box other = diagram.boxOf(out ? l.to() : l.from());
                if (other == null || other == home) continue;
                return "Line " + (line + 1) + " can't be taken out: it is the last line of " + label(method) + ", which is connected "
                        + (out ? "to " : "from ") + label(diagram.method(out ? l.to() : l.from()))
                        + ". Remove that connection (or the method) first.";
            }
        }
        FlowEntry picked = null;
        for (FlowEntry e : method.picked) if (e.line == line + 1 && url.equals(e.fileUrl)) picked = e;
        if (picked != null) method.picked.remove(picked);
        else method.dropped.add(url + "#" + (line + 1));
        changed("Remove line " + (line + 1) + " from " + label(method));
        say("Line " + (line + 1) + " is taken out of " + label(method) + " (Save saves it; Undo brings it back).");
        return null;
    }

    /** The methods the code of a line calls ("Class.method"), with the rest of its statement when it goes on. */
    private Set<String> callsOnLine(String url, int line) {
        Set<String> out = FlowCompat.read(() -> {
            Set<String> found = new HashSet<>();
            com.intellij.openapi.vfs.VirtualFile vf = com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(url);
            com.intellij.openapi.editor.Document doc = vf == null ? null
                    : com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf);
            com.intellij.psi.PsiFile psi = vf == null ? null : com.intellij.psi.PsiManager.getInstance(project).findFile(vf);
            if (doc == null || psi == null || line < 0 || line >= doc.getLineCount()) return found;
            String text = doc.getText(new com.intellij.openapi.util.TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line)));
            int at = doc.getLineStartOffset(line) + (text.length() - text.stripLeading().length());
            com.intellij.psi.PsiElement el = psi.findElementAt(at);
            com.intellij.psi.PsiStatement st = com.intellij.psi.util.PsiTreeUtil.getParentOfType(el, com.intellij.psi.PsiStatement.class, false);
            com.intellij.psi.PsiElement scope = st;
            if (st == null || doc.getLineNumber(st.getTextRange().getStartOffset()) != line) {
                // a field, or a line inside a longer statement: only that line's code
                scope = com.intellij.psi.util.PsiTreeUtil.getParentOfType(el, com.intellij.psi.PsiField.class, false);
            }
            if (scope == null) return found;
            for (com.intellij.psi.PsiCall c : com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(scope, com.intellij.psi.PsiCall.class)) {
                // the statement's own calls (an if's condition, not the calls in its body)
                if (st != null && scope == st
                        && com.intellij.psi.util.PsiTreeUtil.getParentOfType(c, com.intellij.psi.PsiStatement.class, true) != st) continue;
                com.intellij.psi.PsiMethod target = c.resolveMethod();
                com.intellij.psi.PsiClass cls = target == null ? null : target.getContainingClass();
                if (cls != null) found.add(cls.getName() + "." + target.getName());
            }
            return found;
        });
        return out == null ? Set.of() : out;
    }

    private static String baseName(String method) {
        int p = method.indexOf('(');
        return (p < 0 ? method : method.substring(0, p)).strip();
    }

    private static String simpleName(String cls) {
        String s = cls == null ? "" : cls;
        return s.substring(Math.max(s.lastIndexOf('.'), s.lastIndexOf('$')) + 1);
    }

    /** The method's lines in the flow and the ones picked for it here (0-based, in file order). */
    private List<Integer> codeLines(FlowDiagram.Method m) {
        java.util.TreeSet<Integer> lines = new java.util.TreeSet<>();
        if (m.origKey != null) {
            for (FlowEntry e : entriesAtOpen) {
                if (e.line > 0 && m.origKey.equals(FlowSync.methodKey(e)) && !m.dropped.contains(e.fileUrl + "#" + e.line)) {
                    lines.add(e.line - 1);
                }
            }
        }
        for (FlowEntry e : m.picked) if (e.line > 0) lines.add(e.line - 1);
        return new ArrayList<>(lines);
    }

    private boolean sameHistory() {
        if (historyModel.size() != history.steps.size()) return false;
        for (int i = 0; i < history.steps.size(); i++) if (historyModel.get(i) != history.steps.get(i)) return false;
        return true;
    }

    private final class HistoryRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            FlowDiagram.History.Step s = (FlowDiagram.History.Step) value;
            String text = (s.open() ? "" : index + ". ") + s.label() + (s.applied() ? "  ✔ applied" : "");
            l.setText(text);
            l.setToolTipText(text);
            l.setBorder(JBUI.Borders.empty(3, 6));
            if (s.open()) l.setFont(l.getFont().deriveFont(Font.BOLD));
            if (index > history.position && !selected) {
                l.setForeground(FlowTheme.FG_DISABLED); // undone: Redo brings it back
                l.setFont(l.getFont().deriveFont(Font.ITALIC));
            } else if (s.applied() && !selected) {
                l.setForeground(ADDED);
            }
            return l;
        }
    }

    // ---------- tools and edits ----------

    private void setTool(Tool t) {
        tool = t;
        canvas.draftFrom = null;
        canvas.setCursor(Cursor.getPredefinedCursor(t == Tool.SELECT ? Cursor.DEFAULT_CURSOR : Cursor.CROSSHAIR_CURSOR));
        status.setText("");
        refresh();
    }

    private void showToolHint() {
        status.setText(tool == Tool.CONNECT
                ? "Drag from a method to the method it calls. Dashed arrows are connected by hand; solid ones were found in the code."
                : "Double-click a class name to pick another class, its methods to add a method, an empty spot to add a class. "
                + "Custom classes and methods (not in the code) are amber. Right-click for more.");
    }

    private void say(String text) {
        status.setText(text);
    }

    private static final com.intellij.openapi.diagnostic.Logger PATH_LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance("TraceWeave.ConnectFlow");

    /** A message that must be seen (the status line too): a balloon at the top of the diagram for a few seconds. */
    private void tell(String text, boolean ok) {
        say(text);
        if (!canvas.isShowing()) return;
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(text),
                        ok ? com.intellij.openapi.ui.MessageType.INFO : com.intellij.openapi.ui.MessageType.WARNING, null)
                .setFadeoutTime(ok ? 4000 : 8000)
                .setHideOnClickOutside(true)
                .createBalloon()
                .show(new com.intellij.ui.awt.RelativePoint(canvas, new java.awt.Point(canvas.getVisibleRect().x + canvas.getVisibleRect().width / 2, canvas.getVisibleRect().y + JBUI.scale(30))),
                        com.intellij.openapi.ui.popup.Balloon.Position.below);
    }

    // ---------- several classes: select, copy, cut, paste, move to another tab ----------

    /** Classes picked with Ctrl+click (besides the selection), by box id. */
    private final java.util.LinkedHashSet<String> multi = new java.util.LinkedHashSet<>();


    /** Copy, paste and moves between tabs, without the window (the window adds History steps and messages). */
    private final FlowDiagramTabs tabs = new FlowDiagramTabs(() -> diagram, painter, this::methodEntries);

    FlowDiagramTabs tabs() {
        return tabs;
    }

    /** The classes selected: the Ctrl+clicked ones and the selected one. */
    private List<FlowDiagram.Box> pickedBoxes() {
        List<FlowDiagram.Box> out = new ArrayList<>();
        for (String id : multi) {
            FlowDiagram.Box b = diagram.box(id);
            if (b != null) out.add(b);
        }
        FlowDiagram.Box sel = selectedBox();
        if (sel != null && !out.contains(sel)) out.add(sel);
        return out;
    }

    /** A method's lines: the flow's own and the ones picked here (copies). */
    private List<FlowEntry> methodEntries(FlowDiagram.Method m) {
        List<FlowEntry> out = new ArrayList<>();
        if (m.origKey != null) for (FlowEntry e : entriesAtOpen) if (m.origKey.equals(FlowSync.methodKey(e))) out.add(e.copy());
        for (FlowEntry e : m.picked) out.add(e.copy());
        return out;
    }

    /** Every class of this tab (to move them to another tab when it closes). */
    FlowDiagramTabs.Clip copyAll() {
        return tabs.copyAll();
    }

    void paste(FlowDiagramTabs.Clip clip, String label) {
        paste(clip, label, FlowDiagramTabs.Place.RIGHT);
    }

    /** Pastes copied classes (a step of this tab's History; Save saves their lines to its flow). Returns the boxes made. */
    List<FlowDiagram.Box> paste(FlowDiagramTabs.Clip clip, String label, FlowDiagramTabs.Place where) {
        FlowDiagramTabs.Pasted p = tabs.paste(clip, where);
        if (p.boxes().isEmpty()) return p.boxes();
        pickOnly(p.boxes());
        int n = clip.boxes().size(), kept = p.kept();
        changed(label + " (" + n + (n == 1 ? " class)" : " classes)"));
        say("Pasted " + n + (n == 1 ? " class" : " classes")
                + (kept == 0 ? "" : ", with " + kept + (kept == 1 ? " call" : " calls") + " to classes here")
                + ". Save saves them to “" + flowName + "”.");
        return p.boxes();
    }

    /** The boxes become the Ctrl+click selection. */
    private void pickOnly(List<FlowDiagram.Box> boxes) {
        multi.clear();
        for (FlowDiagram.Box b : boxes) multi.add(b.id);
    }

    private void copyPicked(boolean cut) {
        List<FlowDiagram.Box> boxes = pickedBoxes();
        if (boxes.isEmpty()) {
            say("Select classes first (Ctrl+click to pick several, Ctrl+A for all).");
            return;
        }
        FlowDiagramHost.clipboard = tabs.copyOf(boxes);
        if (cut) removeBoxes(boxes, "Cut");
        say((cut ? "Cut " : "Copied ") + boxes.size() + (boxes.size() == 1 ? " class" : " classes")
                + ". Ctrl+V pastes them here or in another tab.");
    }

    private void pasteClipboard() {
        if (FlowDiagramHost.clipboard == null) {
            say("Nothing copied yet: select classes and press Ctrl+C (or Ctrl+X).");
            return;
        }
        paste(FlowDiagramHost.clipboard, "Paste");
    }

    private void removeBoxes(List<FlowDiagram.Box> boxes, String what) {
        for (FlowDiagram.Box b : boxes) diagram.removeClass(b);
        multi.clear();
        selection = FlowDiagramPainter.Selection.NONE;
        changed(what + " " + boxes.size() + (boxes.size() == 1 ? " class" : " classes"));
    }

    /** Copy (or move) the selected classes to another tab. */
    private void sendTo(FlowDiagramDialog target, boolean move) {
        List<FlowDiagram.Box> boxes = pickedBoxes();
        if (boxes.isEmpty() || host == null) return;
        send(boxes, target, move, false);
        if (!move) host.select(target); // a move shows the combined tab it opens
    }

    /**
     * The classes go to (or are copied to) another tab, with link boxes on both sides where they are connected to
     * classes that stay (see {@link FlowDiagramTabs}); one History step in each tab. Moving also opens a tab with
     * both combined.
     *
     * @param fresh the target is a new tab (it is laid out after)
     */
    private void send(List<FlowDiagram.Box> boxes, FlowDiagramDialog target, boolean move, boolean fresh) {
        int n = boxes.size();
        String classes = n + (n == 1 ? " class" : " classes");
        FlowDiagramTabs.Sent sent = FlowDiagramTabs.send(tabs, flowName, target.tabs, target.flowName(), boxes, move, fresh);
        if (move) {
            multi.clear();
            selection = FlowDiagramPainter.Selection.NONE;
            changed("Move " + classes + " to " + target.flowName());
        }
        target.pickOnly(sent.pasted());
        target.changed((move ? "Moved from " : "Copied from ") + flowName + " (" + classes + ")");
        target.say((move ? "Moved " : "Copied ") + classes + " from “" + flowName + "”"
                + (sent.across().isEmpty() ? "." : ", with link boxes for the calls to its classes."));
        if (move && host != null && !sent.across().isEmpty()) host.combine(this, target);
    }

    /** Where a class moved here stood in the tab it came from (null when it wasn't moved here). */
    @Nullable double[] homeOf(String className) {
        return tabs.homeOf(className);
    }

    void changedAfterCombine(String label) {
        changed(label);
    }

    /**
     * This tab shows two tabs combined (see {@link FlowDiagramTabs#combine}), then the calls the code really makes
     * between their classes are drawn and it is laid out again.
     */
    void combineFrom(FlowDiagramDialog a, FlowDiagramDialog b) {
        tabs.combine(a.tabs, a.flowName(), b.tabs, b.flowName());
        multi.clear();
        changed("Connect " + a.flowName() + " and " + b.flowName());
        connectFromCode(() -> {
            tabs.layOut();
            changed("Auto layout");
        });
    }

    /**
     * Draws the calls the code really makes between this tab's methods (read from the code in the background, as
     * when a flow opens; whatever was copied, cut or pasted): a call is drawn only where the code makes it.
     */
    void connectFromCode(Runnable after) {
        List<FlowEntry> all = new ArrayList<>();
        java.util.Map<String, FlowDiagram.Method> byKey = new java.util.HashMap<>();
        for (FlowDiagram.Box b : diagram.boxes) {
            if (FlowDiagramTabs.isLink(b)) continue;
            for (FlowDiagram.Method m : b.methods) {
                for (FlowEntry e : methodEntries(m)) {
                    all.add(e);
                    byKey.putIfAbsent(FlowSync.methodKey(e), m);
                }
            }
        }
        if (all.size() < 2) {
            after.run();
            return;
        }
        say("Looking in the code for the calls between the classes…");
        FlowSync.callsFor(project, List.of(all), found -> {
            if (isDisposed()) return;
            int n = 0;
            FlowDiagramTabs.CallLook code = new FlowDiagramTabs.CallLook("", FlowDiagram.NoteStyle.DEFAULT, FlowDiagram.Line.DEFAULT, false);
            for (String link : found.get(0).links()) {
                String[] ends = link.split(" -> ", 2);
                if (ends.length < 2) continue;
                FlowDiagram.Method from = byKey.get(ends[0]), to = byKey.get(ends[1]);
                if (from == null || to == null || from == to) continue;
                boolean there = false;
                for (FlowDiagram.Link l : diagram.links) there |= l.from().equals(from.id) && l.to().equals(to.id);
                if (!there) {
                    tabs.connect(from, to, code);
                    n++;
                }
            }
            say(n == 0 ? "The calls between the classes are drawn." : "Found " + n + (n == 1 ? " call" : " calls") + " in the code.");
            after.run();
        });
    }

    /** Move / Copy to a new tab: a new flow (named after this one) with the classes, laid out in full view. */
    private void sendToNewTab(boolean move) {
        List<FlowDiagram.Box> boxes = pickedBoxes();
        if (boxes.isEmpty() || host == null) return;
        String name = store.uniqueName(flowName + " – " + (boxes.size() == 1 ? boxes.get(0).name : boxes.size() + " classes"));
        FlowDiagramHost h = host;
        String from = flowName;
        store.newFlow(name, List.of());
        String made = store.getActiveFlowName();
        h.madeHere(made);
        openFlow(project, made);
        FlowDiagramDialog target = h.pageOf(made);
        if (target == null) return;
        h.activate(this);
        send(boxes, target, move, true);
        if (!move) h.select(target); // a move shows the combined tab it opens
    }

    /** Lays every class out and shows them all (zoom to fit). */
    void fitAll() {
        painter.autoLayout(diagram);
        changed("Auto layout");
        Rectangle2D all = painter.extent(diagram);
        java.awt.Rectangle view = canvas.getVisibleRect();
        if (all.getWidth() > 0 && view.width > 0) {
            double z = Math.min(1, Math.min(view.width / (all.getWidth() + 40), view.height / (all.getHeight() + 40)));
            setZoom(Math.max(0.33, z), null);
        }
    }

    private void selectAll() {
        multi.clear();
        for (FlowDiagram.Box b : diagram.boxes) multi.add(b.id);
        canvas.repaint();
        say(multi.size() + " classes selected: Ctrl+C / Ctrl+X copy or cut them, Delete removes them.");
    }

    private @Nullable FlowDiagram.Box selectedBox() {
        if (selection.methodId() != null) return diagram.boxOf(selection.methodId());
        return selection.boxId() == null ? null : diagram.box(selection.boxId());
    }

    private @Nullable FlowDiagram.Method selectedMethod() {
        return selection.methodId() == null ? null : diagram.method(selection.methodId());
    }

    private void select(@Nullable FlowDiagram.Box box, @Nullable FlowDiagram.Method method, @Nullable FlowDiagram.Link link) {
        selection = new FlowDiagramPainter.Selection(box == null ? null : box.id, method == null ? null : method.id, link);
        status.setText("");
        if (link != null) {
            FlowDiagram.Method from = diagram.method(link.from()), to = diagram.method(link.to());
            say((link.manual() ? "Connected by hand: " : "Found in the code (can't be removed here): ")
                    + label(from) + " → " + label(to));
        }
        refresh();
    }

    private String label(@Nullable FlowDiagram.Method m) {
        if (m == null) return "?";
        FlowDiagram.Box b = diagram.boxOf(m.id);
        return (b == null ? "" : b.name + ".") + m.name;
    }

    private @Nullable String ask(String message, String title, String initial, java.util.function.Function<String, String> check) {
        return Messages.showInputDialog(canvas, message, title, null, initial, new InputValidatorEx() {
            @Override public @Nullable String getErrorText(@NotNull String input) { return check.apply(input); }
            @Override public boolean checkInput(@NotNull String input) { return check.apply(input) == null; }
            @Override public boolean canClose(@NotNull String input) { return checkInput(input); }
        });
    }

    /** Where a new box goes when no spot was clicked: right of the others, at the top. */
    private Point2D freeSpot() {
        double right = 0;
        for (FlowDiagram.Box b : diagram.boxes) right = Math.max(right, b.x + painter.width(b));
        return new Point2D.Double(diagram.boxes.isEmpty() ? 24 : right + 60, 24);
    }

    /**
     * Add Class: a class picked from the project (searched by name, pattern or regex). A name no class of the
     * project has is added as a custom class.
     */
    private void addKnownClass(@Nullable Point2D at) {
        FlowCodePicker.ClassChoice choice = FlowCodePicker.pickClass(project, canvas, "Add File", "",
                n -> diagram.checkClassName(null, n));
        if (choice == null) return;
        if (choice.isCustom()) {
            addUnknownClass(choice.customName(), choice.customFile(), at);
            return;
        }
        FlowCodeSearch.ClassHit hit = choice.hit();
        FlowEntry header = FlowCompat.read(() -> FlowCodeSearch.entry(project, hit.url(), hit.declLine()));
        // A file's lines name their class themselves (e.g. a TLD's short name): use what its first line says.
        String cls = hit.isFile() && header != null ? header.className : hit.className();
        FlowDiagram.Box existing = diagram.knownBox(hit.url(), cls);
        if (existing != null) {
            select(existing, null, null);
            say(existing.name + " is in the diagram already. Use Add Function to add its methods.");
            return;
        }
        Point2D p = at != null ? at : freeSpot();
        FlowDiagram.Box b = diagram.addKnownClass(hit.url(), hit.fileName(), hit.projectName(), cls, header,
                Math.max(0, p.getX()), Math.max(0, p.getY()));
        select(b, null, null);
        changed("Add class " + b.name);
        say("Added " + b.name + " (" + hit.path() + "). Use Add Function to pick its methods and lines.");
    }

    /**
     * Add Method: a method of the selected class, with the lines picked from its code. A name the class doesn't
     * have (and any method of a custom class) is added as a custom method.
     */
    private void addKnownMethod() {
        FlowDiagram.Box b = selectedBox();
        if (b == null) return;
        if (b.isUnknown()) {
            addUnknownMethod(b);
            return;
        }
        FlowCodeSearch.ClassHit cls = new FlowCodeSearch.ClassHit(b.fileUrl, b.fileName, "", javaClass(b), b.projectName, 0);
        FlowCodePicker.MethodChoice choice = FlowCodePicker.pickMethod(project, canvas, cls, n -> diagram.checkMethodName(b, null, n));
        if (choice == null) return;
        if (choice.isCustom()) {
            FlowDiagram.Method m = diagram.addUnknownMethod(b, choice.customName());
            select(b, m, null);
            changed("Add custom method " + b.name + "." + m.name);
            return;
        }
        FlowCodeSearch.MethodHit method = choice.hit();
        List<Integer> lines = FlowCodePicker.pickLines(project, canvas, cls, method, savedLines(b.fileUrl));
        if (lines == null) return;
        List<FlowEntry> entries = FlowCompat.read(() -> {
            List<FlowEntry> out = new ArrayList<>();
            for (int line : lines) {
                FlowEntry e = FlowCodeSearch.entry(project, b.fileUrl, line);
                if (e != null) out.add(e);
            }
            return out;
        });
        if (entries.isEmpty()) {
            say("Those lines are in the flow already.");
            return;
        }
        FlowDiagram.Method m = diagram.addKnownMethod(b, method.name(), entries);
        select(b, m, null);
        changed("Add method " + b.name + "." + m.name + " (" + entries.size() + (entries.size() == 1 ? " line)" : " lines)"));
        say("Added " + entries.size() + (entries.size() == 1 ? " line" : " lines") + " of " + m.name
                + ". The lines aren't drawn here; they are listed in the panel and the Markdown copy.");
    }

    /** The class name to look a box's methods up by ("-" for a file read as text). */
    private static String javaClass(FlowDiagram.Box b) {
        return FlowLang.usesJavaPsi(b.fileName) || FlowLang.isKotlin(b.fileName) ? b.origClass : "-";
    }

    /** 0-based lines of a file that the flow or this diagram has already. */
    private Set<Integer> savedLines(String url) {
        Set<Integer> out = new HashSet<>();
        for (FlowEntry e : store.getEntries()) if (url.equals(e.fileUrl) && !FlowDiagram.isDiagramOnly(e)) out.add(e.line - 1);
        for (FlowDiagram.Box b : diagram.boxes) {
            for (FlowDiagram.Method m : b.methods) for (FlowEntry e : m.picked) if (url.equals(e.fileUrl)) out.add(e.line - 1);
        }
        return out;
    }

    private void addUnknownClass(String name, String fileName, @Nullable Point2D at) {
        Point2D p = at != null ? at : freeSpot();
        FlowDiagram.Box b = diagram.addUnknownClass(name, fileName, Math.max(0, p.getX()), Math.max(0, p.getY()));
        select(b, null, null);
        changed("Add custom class " + b.name);
        say("Added custom class " + b.name + " (" + b.fileName + "). Add methods with Add Function; right-click to "
                + "rename it or change its file, or double-click its name to pick a class of the project instead.");
    }

    /** A custom method (not in the code), asked by name. */
    private void addUnknownMethod(FlowDiagram.Box b) {
        String name = ask("Name of the method (" + b.name + " is a custom class, so it is a custom method):", "Add Function", "",
                n -> diagram.checkMethodName(b, null, n));
        if (name == null) return;
        FlowDiagram.Method m = diagram.addUnknownMethod(b, name);
        select(b, m, null);
        changed("Add custom method " + b.name + "." + m.name);
    }

    private void renameSelection() {
        FlowDiagram.Box b = selectedBox();
        if (b == null) return;
        FlowDiagram.Method m = selectedMethod();
        if (m != null) {
            String name = ask("New name of " + b.name + "." + m.name + ":", "Rename Method",
                    m.name.substring(0, Math.max(0, m.name.indexOf('('))), n -> diagram.checkMethodName(b, m, n));
            if (name == null || FlowDiagram.methodName(name).equals(m.name)) return;
            String old = m.name;
            diagram.renameMethod(m, name);
            changed("Rename " + b.name + "." + old + " to " + m.name);
            if (!b.isUnknown()) say("Renamed in TraceWeave only: your source code doesn't change.");
        } else {
            // The classes of the project matching the new name are listed as it is typed; a name none has
            // makes the box a custom class.
            pickClassFor(b, "Rename Class", b.name, "Rename class " + b.name + " to ");
        }
    }

    /**
     * Lets the user pick the class a box shows: a class of the project takes its place, a name no class has
     * makes it a custom class.
     */
    private void pickClassFor(FlowDiagram.Box b, String title, String query, String labelPrefix) {
        String boxId = b.id;
        FlowCodePicker.ClassChoice choice = FlowCodePicker.pickClass(project, canvas, title, query,
                n -> n.strip().equals(b.name) && b.isUnknown() ? null : diagram.checkClassName(b, n));
        FlowDiagram.Box box = diagram.box(boxId);
        if (choice == null || box == null) return;
        if (choice.isCustom()) renamedToCustom(box, choice.customName().strip(), choice.customFile());
        else replaceBox(box, choice.hit(), labelPrefix);
    }

    /** A class renamed to a name no class of the project has: the box becomes (or stays) a custom class. */
    private void renamedToCustom(FlowDiagram.Box b, String name, @Nullable String fileName) {
        String old = b.name;
        if (b.isUnknown() && name.equals(old)) return;
        String error = diagram.checkClassName(b, name);
        if (error != null) {
            say(error);
            return;
        }
        if (b.isUnknown()) {
            diagram.renameClass(b, name);
            changed("Rename custom class " + old + " to " + name);
            say("Renamed. There is no class " + name + " in the project, so it stays a custom class.");
            return;
        }
        FlowDiagram.Box custom = diagram.replaceWithCustomClass(b, name, fileName == null ? name : fileName);
        select(custom, null, null);
        changed("Rename class " + old + " to " + name + " (custom)");
        say("There is no class " + name + " in the project: it is a custom class now (not in the code). "
                + "Its methods are custom methods; their code lines leave the flow on Save.");
    }

    /** Tags a method in the diagram; its lines get the tag on Apply (one undoable History step). */
    private void tagMethod(FlowDiagram.Method m, String tagId) {
        FlowDiagram.Box b = diagram.boxOf(m.id);
        diagram.setMethodTag(m, tagId);
        FlowTag t = FlowTag.byId(tagId);
        changed((t == null ? "No tag on " : "Tag " + t.label + ": ") + (b == null ? "" : b.name + ".") + m.name);
        say(t == null ? "The tag is removed from the method's lines on Save."
                : "On Save, every line of " + m.name + " is tagged " + t.label + " in the TraceWeave window.");
    }

    /** Change File Name (custom classes): the file the custom class will be in. */
    private void changeCustomFile(FlowDiagram.Box b) {
        javax.swing.JTextField file = new javax.swing.JTextField(b.fileName, 30);
        java.util.Set<String> projects = new java.util.TreeSet<>();
        projects.add(project.getName());
        for (FlowDiagram.Box x : diagram.boxes) if (x.projectName != null && !x.projectName.isBlank()) projects.add(x.projectName);
        com.intellij.openapi.ui.ComboBox<String> projectBox = new com.intellij.openapi.ui.ComboBox<>(projects.toArray(new String[0]));
        projectBox.setEditable(true);
        projectBox.setSelectedItem(b.projectName == null ? "" : b.projectName);
        JPanel form = com.intellij.util.ui.FormBuilder.createFormBuilder()
                .addLabeledComponent("File name:", file)
                .addLabeledComponent("Project:", projectBox)
                .addComponentToRightColumn(new com.intellij.ui.components.JBLabel(
                        "<html><small>The project (or module) the custom class is meant for, shown in its box. Leave it empty for none.</small></html>"))
                .getPanel();
        com.intellij.openapi.ui.DialogBuilder dialog = new com.intellij.openapi.ui.DialogBuilder(canvas);
        dialog.setTitle("File and Project of " + b.name);
        dialog.setCenterPanel(form);
        dialog.setPreferredFocusComponent(file);
        if (!dialog.showAndGet()) return;
        String newFile = file.getText().isBlank() ? b.name : file.getText().strip();
        Object p = projectBox.getEditor().getItem();
        String newProject = p == null ? "" : p.toString().strip();
        if (newFile.equals(b.fileName) && newProject.equals(b.projectName == null ? "" : b.projectName)) return;
        diagram.setCustomFile(b, newFile, newProject);
        changed("File of " + b.name + ": " + b.fileName + (newProject.isEmpty() ? "" : " (" + newProject + ")"));
    }

    /** Change Class (double-click a class name): another class of the project (or a custom one) takes the box's place. */
    private void changeClass(FlowDiagram.Box b) {
        pickClassFor(b, "Change File", b.isUnknown() ? b.name : "", "Change class " + b.name + " to ");
    }

    /** Puts a class of the project in a box's place; its methods become custom methods of that class. */
    private void replaceBox(FlowDiagram.Box b, FlowCodeSearch.ClassHit hit, String labelPrefix) {
        FlowEntry header = FlowCompat.read(() -> FlowCodeSearch.entry(project, hit.url(), hit.declLine()));
        String cls = hit.isFile() && header != null ? header.className : hit.className();
        if (!b.isUnknown() && b.fileUrl.equals(hit.url()) && b.origClass.equals(cls)) {
            say("That is the class this box shows already.");
            return;
        }
        int methods = b.methods.size();
        FlowDiagram.Box nb = diagram.replaceWithKnownClass(b, hit.url(), hit.fileName(), hit.projectName(), cls, header);
        if (nb == null) {
            say(hit.name() + " is in the diagram already, in another box.");
            return;
        }
        select(nb, null, null);
        changed(labelPrefix + nb.name);
        say(methods == 0 ? nb.name + " (" + hit.path() + ") takes its place. Double-click its methods area to add a method."
                : nb.name + " takes its place. Its " + methods + (methods == 1 ? " method is a custom method" : " methods are custom methods")
                + " of " + nb.name + " now: delete what it doesn't have, or double-click to add its real methods.");
    }

    private void deleteSelection() {
        if (multi.size() > 1 || !multi.isEmpty() && selection.methodId() == null && selection.link() == null) {
            removeBoxes(pickedBoxes(), "Remove");
            say("Removed lines go to the Recycle Bin when you apply.");
            return;
        }
        FlowDiagram.Link link = selection.link();
        if (link != null) {
            if (!link.manual()) {
                say("This call was found in the code, so it can't be removed here.");
                return;
            }
            String text = label(diagram.method(link.from())) + " → " + label(diagram.method(link.to()));
            diagram.disconnect(link);
            selection = FlowDiagramPainter.Selection.NONE;
            changed("Remove call " + text);
            return;
        }
        FlowDiagram.Box b = selectedBox();
        if (b == null) return;
        FlowDiagram.Method m = selectedMethod();
        selection = FlowDiagramPainter.Selection.NONE;
        if (m != null) {
            diagram.removeMethod(m);
            changed("Remove method " + b.name + "." + m.name);
        } else {
            diagram.removeClass(b);
            changed("Remove class " + b.name);
        }
        say("Removed lines go to the Recycle Bin when you apply.");
    }

    /**
     * Format: the selected call's line, or the selected method's or class box's look and note. Changes go to the
     * History like any other.
     */
    private void formatSelection() {
        FlowDiagram.Link link = selection.link();
        if (link != null) {
            String text = label(diagram.method(link.from())) + " → " + label(diagram.method(link.to()));
            FlowDiagramFormat.LineChoice choice = FlowDiagramFormat.editLine(project, canvas, text, diagram.line(link), link.manual(),
                    diagram.callNote(link), diagram.noteStyle(link), canvasPalette());
            if (choice == null) return;
            diagram.setCallNote(link, choice.note()); // the note is this call's only, even with "every call"
            diagram.setNoteStyle(link, choice.noteStyle());
            if (choice.everyLine()) {
                for (FlowDiagram.Link l : diagram.links) diagram.setLine(l, choice.line());
                changed("Line of every call");
            } else {
                diagram.setLine(link, choice.line());
                changed("Line of " + text);
            }
            return;
        }
        FlowDiagram.Box b = selectedBox();
        if (b == null) {
            say("Select a class, a method or a call to format it (or to write a note under a class or method name).");
            return;
        }
        FlowDiagram.Method m = selectedMethod();
        if (m != null) {
            String old = m.note;
            if (!FlowDiagramFormat.editMethod(project, canvas, b, m, canvasPalette())) return;
            changed((!old.equals(m.note) ? "Note on " : "Format ") + b.name + "." + m.name);
        } else {
            String old = b.note;
            if (!FlowDiagramFormat.editBox(project, canvas, b, canvasPalette())) return;
            changed((!old.equals(b.note) ? "Note on " : "Format ") + b.name);
        }
    }

    /** Reset Theme: the default look for everything (one History step, so Undo brings the formats back). */
    private void resetTheme() {
        FlowDiagram before = diagram.copy();
        diagram.resetLooks();
        if (diagram.sameAs(before)) {
            say("Nothing to reset: every class, method and line has the default look already.");
            return;
        }
        changed("Reset theme");
        say("Every class, method and line has the default look again. Notes, calls and positions stay; Undo (Ctrl+Z) brings the formats back.");
    }

    private void connect(FlowDiagram.Method from, FlowDiagram.Method to) {
        if (from == to) {
            say("A method can't call itself here: drop on another method.");
            return;
        }
        if (!diagram.connect(from.id, to.id)) {
            say(label(from) + " already calls " + label(to) + ".");
            return;
        }
        FlowDiagram.Link l = diagram.links.get(diagram.links.size() - 1);
        selection = new FlowDiagramPainter.Selection(null, null, l);
        changed("Connect " + label(from) + " → " + label(to));
    }

    /** Auto Layout: the classes in call order, and every line (and note) back to finding its own way. */
    private void autoLayout() {
        int byHand = diagram.routes.size() + diagram.notePlaces.size();
        diagram.routes.clear();
        diagram.notePlaces.clear();
        painter.autoLayout(diagram);
        changed("Auto layout");
        if (byHand > 0) say("Auto Layout: lines and notes moved by hand find their own way again (Undo brings them back).");
    }

    // ---------- copy as PNG ----------

    private void copyClassImage(FlowLook.Background background) {
        copyImage(painter.classImage(diagram, flowName + " – Flow Diagram", imagePalette(background)), "the flow diagram", background);
    }

    private void copySequenceImage(FlowLook.Background background) {
        copyImage(painter.sequenceImage(diagram, flowName + " – Sequence Diagram", imagePalette(background)), "the sequence diagram", background);
    }

    private static FlowDiagramPainter.Palette imagePalette(FlowLook.Background background) {
        return background == FlowLook.Background.DARK ? FlowDiagramPainter.DARK : FlowDiagramPainter.LIGHT;
    }

    private void copyImage(BufferedImage image, String what, FlowLook.Background background) {
        CopyPasteManager.getInstance().setContents(new ImageSelection(image));
        say("Copied " + what + " as a PNG image with a " + (background == FlowLook.Background.DARK ? "dark" : "light")
                + " background (" + image.getWidth() + " × " + image.getHeight() + "). Paste it into a document, chat or image editor.");
    }

    /** The ▾ of a copy button: copy with a light or a dark background (the default one is checked). */
    private void backgroundMenu(FlowButton under, java.util.function.Consumer<FlowLook.Background> copy) {
        Map<String, List<FlowSettingsPopup.Choice>> sections = new LinkedHashMap<>();
        sections.put("BACKGROUND", List.of(
                new FlowSettingsPopup.Choice(FlowTheme.ICON_LIGHT, "Copy with Light Background", "White, for documents and light chats.",
                        () -> look.getImageBackground() == FlowLook.Background.LIGHT, () -> copy.accept(FlowLook.Background.LIGHT)),
                new FlowSettingsPopup.Choice(FlowTheme.ICON_DARK, "Copy with Dark Background", "For dark documents, slides and chats.",
                        () -> look.getImageBackground() == FlowLook.Background.DARK, () -> copy.accept(FlowLook.Background.DARK))));
        FlowSettingsPopup.showChoices(under, "Copy Image", sections,
                "✔ marks the button's default. Change it in this window's Settings.", true);
    }

    /** The window's Settings: the default background of copied images, and the drawing area's background. */
    private void settingsMenu(FlowButton under) {
        Map<String, List<FlowSettingsPopup.Choice>> sections = new LinkedHashMap<>();
        sections.put("COPY PNG / COPY SEQUENCE PNG", List.of(
                new FlowSettingsPopup.Choice(FlowTheme.ICON_LIGHT, "Light Background", "White images: what the copy buttons give by default.",
                        () -> look.getImageBackground() == FlowLook.Background.LIGHT, () -> look.setImageBackground(FlowLook.Background.LIGHT)),
                new FlowSettingsPopup.Choice(FlowTheme.ICON_DARK, "Dark Background", "Dark images.",
                        () -> look.getImageBackground() == FlowLook.Background.DARK, () -> look.setImageBackground(FlowLook.Background.DARK))));
        sections.put("DIAGRAM WINDOW", List.of(
                new FlowSettingsPopup.Choice(FlowTheme.ICON_THEME, look.isUseIdeTheme() ? "Same as IntelliJ Theme" : "Same as TraceWeave",
                        "Follows the light or dark theme.",
                        () -> look.getDiagramBackground() == FlowLook.Background.THEME, () -> look.setDiagramBackground(FlowLook.Background.THEME)),
                new FlowSettingsPopup.Choice(FlowTheme.ICON_LIGHT, "Light Background", null,
                        () -> look.getDiagramBackground() == FlowLook.Background.LIGHT, () -> look.setDiagramBackground(FlowLook.Background.LIGHT)),
                new FlowSettingsPopup.Choice(FlowTheme.ICON_DARK, "Dark Background", null,
                        () -> look.getDiagramBackground() == FlowLook.Background.DARK, () -> look.setDiagramBackground(FlowLook.Background.DARK))));
        sections.put("DRAGGING LINES", List.of(
                new FlowSettingsPopup.Choice(FlowTheme.ICON_CONNECT, "Keep Lines Off the Classes",
                        "A dragged line goes to the nearest free place: it never runs across a class.",
                        () -> !look.isLinesOverBoxes(), () -> look.setLinesOverBoxes(false)),
                new FlowSettingsPopup.Choice(FlowTheme.ICON_CONNECT, "Let Lines Cross the Classes",
                        "A dragged line goes exactly where you drop it, over classes too.",
                        look::isLinesOverBoxes, () -> look.setLinesOverBoxes(true))));
        FlowSettingsPopup.showChoices(under, "Flow Diagram Settings", sections,
                "Remembered for all projects. The Dark / Light switch in the drawing area's corner flips the window's background.", false);
    }

    private void updateCopyTips(FlowButton copyPng, FlowButton copySequence) {
        String bg = look.getImageBackground() == FlowLook.Background.DARK ? "dark" : "light";
        copyPng.setToolTipText("Copy this diagram as a PNG image with a " + bg + " background (▾: pick the background)");
        copySequence.setToolTipText("Copy the calls as a sequence diagram, as a PNG image with a " + bg + " background (▾: pick the background)");
        updateBackgroundToggle();
    }

    /** True when the drawing area is dark now (with THEME: when the theme's background is dark). */
    private boolean darkCanvas() {
        return switch (look.getDiagramBackground()) {
            case LIGHT -> false;
            case DARK -> true;
            case THEME -> ColorUtil.isDark(FlowTheme.PANEL_BG);
        };
    }

    // ---------- zoom ----------

    /** One zoom step in or out ({@code direction} 1 or -1), keeping {@code anchor} (a point of the viewport) in place. */
    private void zoomBy(int direction, @Nullable Point anchor) {
        double next = zoom;
        if (direction > 0) {
            for (double z : ZOOMS) if (z > zoom + 1e-6) { next = z; break; }
        } else {
            for (int i = ZOOMS.length - 1; i >= 0; i--) if (ZOOMS[i] < zoom - 1e-6) { next = ZOOMS[i]; break; }
        }
        setZoom(next, anchor);
    }

    /** Zooms the drawing area; the diagram point under {@code anchor} (default: the middle) stays there. */
    private void setZoom(double z, @Nullable Point anchor) {
        z = Math.max(ZOOMS[0], Math.min(ZOOMS[ZOOMS.length - 1], z));
        if (Math.abs(z - zoom) < 1e-6) return;
        JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, canvas);
        Point a = anchor != null ? anchor : viewport == null ? new Point() : new Point(viewport.getWidth() / 2, viewport.getHeight() / 2);
        Point pos = viewport == null ? new Point() : viewport.getViewPosition();
        double x = (pos.x + a.x) / canvas.zoom(), y = (pos.y + a.y) / canvas.zoom();
        zoom = z;
        updateZoomBar();
        canvas.setSize(canvas.getPreferredSize());
        canvas.revalidate();
        if (viewport != null) {
            int vx = (int) Math.round(x * canvas.zoom()) - a.x, vy = (int) Math.round(y * canvas.zoom()) - a.y;
            vx = Math.max(0, Math.min(vx, canvas.getWidth() - viewport.getWidth()));
            vy = Math.max(0, Math.min(vy, canvas.getHeight() - viewport.getHeight()));
            viewport.setViewPosition(new Point(vx, vy));
        }
        canvas.repaint();
        canvas.requestFocusInWindow();
    }

    private void updateZoomBar() {
        zoomReset.setText(Math.round(zoom * 100) + "%");
        zoomIn.setEnabled(zoom < ZOOMS[ZOOMS.length - 1] - 1e-6);
        zoomOut.setEnabled(zoom > ZOOMS[0] + 1e-6);
        zoomBar.revalidate();
        if (zoomBar.getParent() != null) zoomBar.getParent().doLayout();
        zoomBar.repaint();
    }

    /** The corner switch: from light to dark and back (it leaves "same as the theme"). */
    private void toggleBackground() {
        look.setDiagramBackground(darkCanvas() ? FlowLook.Background.LIGHT : FlowLook.Background.DARK);
        canvas.requestFocusInWindow();
    }

    private void updateBackgroundToggle() {
        boolean dark = darkCanvas();
        backgroundToggle.setIcon(dark ? FlowTheme.ICON_LIGHT : FlowTheme.ICON_DARK);
        backgroundToggle.setText(dark ? "Light" : "Dark");
        backgroundToggle.setToolTipText("Switch the diagram to a " + (dark ? "light" : "dark") + " background (Settings has more)");
        backgroundToggle.repaint();
    }

    /** The colors the drawing area uses: the theme's, or always light / dark (Settings). */
    private FlowDiagramPainter.Palette canvasPalette() {
        return switch (look.getDiagramBackground()) {
            case LIGHT -> FlowDiagramPainter.LIGHT;
            case DARK -> FlowDiagramPainter.DARK;
            case THEME -> themed();
        };
    }

    /** An image on the clipboard (pasted as PNG by most apps). */
    private record ImageSelection(Image image) implements Transferable {
        @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
        @Override public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.imageFlavor.equals(flavor); }

        @Override
        public @NotNull Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
            return image;
        }
    }

    // ---------- apply / cancel ----------

    private void doOKAction() {
        if (applyChanges(true)) load(project, this); // the tab stays, with the flow as it is now
    }

    /** Save All: saves this tab without asking, and shows it again from its flow. False when it couldn't be saved. */
    boolean saveForAll() {
        if (!hasChanges()) return true;
        if (!applyChanges(false)) return false;
        load(project, this);
        return true;
    }

    /** Save and Close All: saves this tab without asking (it closes after). False when it couldn't be saved. */
    boolean saveBeforeClosing() {
        return !hasChanges() || applyChanges(false);
    }

    /** Don't Save and Close All: its changes are dropped without asking. */
    void dropWithoutAsking() {
        applied = true;
    }

    private boolean applyChanges() {
        return applyChanges(true);
    }

    /**
     * Save: writes the diagram to its flow. False when nothing was written (asked not to, or the flow changed).
     *
     * @param ask true to ask first (Save); false when every tab is saved (Save All, Save and Close All)
     */
    private boolean applyChanges(boolean ask) {
        if (host != null) host.activate(this); // the tab's flow is the one written
        List<FlowEntry> now = store.getEntries();
        if (!now.equals(entriesAtOpen)) {
            Messages.showWarningDialog(canvas, "The flow changed while the diagram was open. Close the tab and open the flow again.",
                    "Flow Diagram");
            return false;
        }
        // The current step may be one from before an earlier Apply: it applies to the lines it was made on.
        FlowDiagram.History.Step step = history.current();
        history.steps.set(history.position, new FlowDiagram.History.Step(step.label(), diagram.copy(), step.base(),
                step.applied(), step.open())); // keeps box positions moved since the last change
        FlowDiagram.Result result = diagram.apply(step.base(), now);
        List<FlowDiagramLook> looks = diagram.looks();
        boolean linesChanged = !result.entries().equals(now);
        boolean looksChanged = !FlowDiagram.sameLooks(looks, store.getDiagramLooks());
        boolean edited = history.position != openPosition || !diagram.sameAs(openDiagram);
        if (linesChanged || looksChanged && edited) {
            String label = applyLabel();
            String what = linesChanged ? "Save the changes to " + flowName + "? Its lines are updated"
                    + (result.removed().isEmpty() ? "" : " (" + result.removed().size()
                    + (result.removed().size() == 1 ? " line goes" : " lines go") + " to the Recycle Bin)") + "; Undo in the flow takes it back."
                    : "Save the new look of the diagram to " + flowName + "?";
            if (ask && Messages.showDialog(canvas, what + "\n\n" + label, "Save Changes", new String[]{"Save", "Keep Editing"}, 0,
                    Messages.getQuestionIcon()) != 0) return false;
            store.applyDiagram(result.entries(), result.removed(), label, looks);
            history.markApplied(store.getEntries());
            FlowCopy.notify(project, label + " applied" + (result.removed().isEmpty() ? ""
                    : "; " + result.removed().size() + (result.removed().size() == 1 ? " line" : " lines")
                    + " moved to the Recycle Bin"), NotificationType.INFORMATION);
        } else if (looksChanged) {
            store.setDiagramLooks(looks); // e.g. the first layout of the diagram: nothing to undo
        }
        store.setDiagramHistory(history);
        applied = true; // the page is replaced: nothing to discard
        return true;
    }

    /** True once Apply saved the diagram (or Cancel was confirmed): closing then doesn't ask again. */
    private boolean applied;

    private String applyLabel() {
        int n = history.position - openPosition;
        if (n == 1) return "Flow Diagram: " + history.current().label();
        if (n > 1) return "Flow Diagram (" + n + " changes)";
        return "Flow Diagram: back to “" + history.current().label() + "”";
    }

    /** Cancel: back to the flow as it was last applied (after asking); the window stays open. */
    private void dropChanges() {
        boolean changes = !Objects.equals(diagram.signature(), openSignature) || history.position != openPosition
                || !diagram.sameAs(openDiagram);
        if (!changes) {
            say("Nothing to reset: the diagram is as it was last saved.");
            return;
        }
        if (Messages.showDialog(canvas, "Drop the changes made since the last Save? The diagram goes back to the flow as it is.",
                "Reset Changes", new String[]{"Reset", "Keep Editing"}, 1, Messages.getQuestionIcon()) != 0) {
            return;
        }
        applied = true; // nothing to ask about when the page is replaced
        if (host != null) host.activate(this);
        load(project, this);
    }

    /** Close: closes this tab (asking first; the window closes with its last tab). */
    private void doCancelAction() {
        if (host != null) host.closePage(this);
    }

    /** True when there is nothing to lose, or the user chose to discard the changes. */
    boolean mayClose() {
        if (applied) return true;
        if (!Objects.equals(diagram.signature(), openSignature) || history.position != openPosition || !diagram.sameAs(openDiagram)) {
            return Messages.showDialog(canvas, "Discard the changes made in the diagram? The flow stays as it was.",
                    "Flow Diagram", new String[]{"Discard", "Keep Editing"}, 1, null) == 0;
        }
        return true;
    }

    // ---------- canvas ----------

    /** The drawing area. Works in diagram units, scaled to the IDE's UI scale. */
    private final class Canvas extends JComponent {
        private @Nullable FlowDiagram.Box dragBox;
        private double dragDx, dragDy, dragStartX, dragStartY;
        @Nullable FlowDiagram.Method draftFrom;
        private @Nullable Point2D draftTo;
        /** While the view is dragged: where the mouse started (on screen) and the view position then. */
        private @Nullable Point panFrom, panView;
        /** While a line is dragged: the call, its lanes, the lane moved, and its route before (null: routed by itself). */
        private @Nullable FlowDiagram.Link dragLink;
        private List<Double> dragLanes = List.of();
        private int dragLane = -1;
        private @Nullable List<Double> routeBefore;
        /** While a note is dragged along its line: the call, and where the note was before (null: its own place). */
        private @Nullable FlowDiagram.Link dragNote;
        private @Nullable Double noteBefore;

        Canvas() {
            setFocusable(true);
            setOpaque(true);
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { pressed(e); }
                @Override public void mouseDragged(MouseEvent e) { dragged(e); }
                @Override public void mouseReleased(MouseEvent e) { released(e); }
                @Override public void mouseMoved(MouseEvent e) { hover(e); }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            // Ctrl + wheel zooms around the mouse; the plain wheel scrolls as usual.
            addMouseWheelListener(e -> {
                if (e.isControlDown()) {
                    Point inViewport = SwingUtilities.convertPoint(this, e.getPoint(), getParent());
                    zoomBy(e.getWheelRotation() < 0 ? 1 : -1, inViewport);
                } else {
                    Component scroll = SwingUtilities.getAncestorOfClass(JBScrollPane.class, this);
                    if (scroll != null) scroll.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, scroll));
                }
            });
        }

        /** Diagram units to pixels: the IDE's UI scale times the window's zoom. */
        private double zoom() {
            return JBUIScale.scale(1f) * zoom;
        }

        private Point2D at(MouseEvent e) {
            return new Point2D.Double(e.getX() / zoom(), e.getY() / zoom());
        }

        private void pressed(MouseEvent e) {
            requestFocusInWindow();
            Point2D p = at(e);
            FlowDiagramPainter.Hit hit = painter.hit(diagram, p);
            if (e.isPopupTrigger()) { menu(e, hit, p); return; }
            if (SwingUtilities.isMiddleMouseButton(e)) { startPan(e); return; } // the middle button pans anywhere
            if (!SwingUtilities.isLeftMouseButton(e)) return;
            if (e.getClickCount() == 1 && tool == Tool.SELECT) waysOnClick(hit); // its ways, as a bar (empty space: the bar goes)

            if (e.getClickCount() == 2 && tool == Tool.SELECT) {
                FlowDiagram.Box box = hit.box();
                if (box != null && painter.inHeader(box, p)) {          // class name: pick another class
                    select(box, null, null);
                    changeClass(box);
                } else if (box != null) {                               // methods area: add a method
                    select(box, hit.method(), null);
                    addKnownMethod();
                } else if (hit.link() == null) {
                    addKnownClass(p);
                }
                return;
            }
            if (tool == Tool.CONNECT) {
                if (hit.method() != null) {
                    draftFrom = hit.method();
                    draftTo = p;
                    select(hit.box(), hit.method(), null);
                } else if (hit.box() == null && hit.link() == null) {
                    startPan(e); // empty space: drag the view
                } else {
                    say("Start on a method row and drag to the method it calls.");
                }
                return;
            }
            if ((e.isControlDown() || e.isMetaDown()) && hit.box() != null) { // pick several classes
                FlowDiagram.Box sel = selectedBox();
                if (multi.isEmpty() && sel != null) multi.add(sel.id);
                if (!multi.remove(hit.box().id)) multi.add(hit.box().id);
                repaint();
                say(multi.size() + (multi.size() == 1 ? " class" : " classes") + " selected: Ctrl+C / Ctrl+X, Delete, "
                        + "or right-click → Move to Tab.");
                return;
            }
            multi.clear();
            FlowDiagram.Link picked = selection.link();
            if (hit.box() == null && picked != null && diagram.links.contains(picked)
                    && painter.laneAt(diagram, picked, painter.lanesOf(diagram, picked), p) >= 0) {
                hit = new FlowDiagramPainter.Hit(null, null, picked); // the selected line, not one running close by
            }
            select(hit.box(), hit.method(), hit.link());
            if (hit.box() == null && hit.link() != null) {
                Rectangle2D note = painter.callNoteBounds(diagram, hit.link());
                if (note != null && note.contains(p)) { // a note: it slides along its own line
                    dragNote = hit.link();
                    noteBefore = diagram.notePlace(dragNote);
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    return;
                }
                if (startLineDrag(hit.link(), p)) return;
            }
            if (hit.box() != null) {
                dragBox = hit.box();
                dragDx = p.getX() - dragBox.x;
                dragDy = p.getY() - dragBox.y;
                dragStartX = dragBox.x;
                dragStartY = dragBox.y;
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            } else if (hit.link() == null) {
                startPan(e); // empty space: drag the view
            }
        }

        /** Starts dragging the view: the diagram follows the mouse. */
        private void startPan(MouseEvent e) {
            JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
            if (viewport == null) return;
            panFrom = e.getLocationOnScreen();
            panView = viewport.getViewPosition();
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        private void pan(MouseEvent e) {
            JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
            if (viewport == null || panFrom == null || panView == null) return;
            Point now = e.getLocationOnScreen();
            int x = panView.x - (now.x - panFrom.x), y = panView.y - (now.y - panFrom.y);
            x = Math.max(0, Math.min(x, getWidth() - viewport.getWidth()));
            y = Math.max(0, Math.min(y, getHeight() - viewport.getHeight()));
            viewport.setViewPosition(new Point(x, y));
        }

        /**
         * Pressed on a call's line (not on its note): its middle pieces can be dragged, a horizontal one up or down, a
         * vertical one left or right; the line keeps that way from then on (right-click → Reset Line Route).
         */
        private boolean startLineDrag(FlowDiagram.Link link, Point2D p) {
            Rectangle2D note = painter.callNoteBounds(diagram, link);
            if (note != null && note.contains(p)) return false;
            List<Double> lanes = painter.lanesOf(diagram, link);
            int k = painter.laneAt(diagram, link, lanes, p);
            if (k < 0) return false;
            dragLink = link;
            dragLanes = new ArrayList<>(lanes);
            dragLane = k;
            routeBefore = diagram.route(link);
            setCursor(Cursor.getPredefinedCursor(k % 2 == 0 ? Cursor.E_RESIZE_CURSOR : Cursor.N_RESIZE_CURSOR));
            return true;
        }

        /** Over a line's middle piece: the cursor shows it can be dragged. */
        private void hover(MouseEvent e) {
            if (tool != Tool.SELECT || dragBox != null || dragLink != null || panFrom != null) return;
            Point2D p = at(e);
            FlowDiagramPainter.Hit hit = painter.hit(diagram, p);
            int cursor = Cursor.DEFAULT_CURSOR;
            if (hit.box() == null && hit.link() != null) {
                Rectangle2D note = painter.callNoteBounds(diagram, hit.link());
                if (note == null || !note.contains(p)) {
                    int k = painter.laneAt(diagram, hit.link(), painter.lanesOf(diagram, hit.link()), p);
                    if (k >= 0) cursor = k % 2 == 0 ? Cursor.E_RESIZE_CURSOR : Cursor.N_RESIZE_CURSOR;
                }
            }
            if (getCursor().getType() != cursor) setCursor(Cursor.getPredefinedCursor(cursor));
        }

        private void dragged(MouseEvent e) {
            if (panFrom != null) { pan(e); return; }
            Point2D p = at(e);
            if (dragNote != null) {
                diagram.setNotePlace(dragNote, painter.nearestAlong(diagram, dragNote, p));
                repaint();
                return;
            }
            if (dragLink != null) {
                // never through a class: the nearest free place to where the mouse is
                Double v = painter.clearLane(diagram, dragLink, dragLanes, dragLane, Math.max(0, dragLane % 2 == 0 ? p.getX() : p.getY()));
                if (v != null) {
                    dragLanes.set(dragLane, v);
                    diagram.setRoute(dragLink, dragLanes);
                    revalidate();
                    repaint();
                }
                return;
            }
            if (dragBox != null) {
                dragBox.x = Math.max(0, p.getX() - dragDx);
                dragBox.y = Math.max(0, p.getY() - dragDy);
                revalidate();
                repaint();
            } else if (draftFrom != null) {
                draftTo = p;
                repaint();
            }
        }

        private void released(MouseEvent e) {
            if (panFrom != null) {
                panFrom = null;
                panView = null;
                setCursor(Cursor.getDefaultCursor());
                return;
            }
            Point2D p = at(e);
            if (dragNote != null) {
                FlowDiagram.Link moved = dragNote;
                dragNote = null;
                setCursor(Cursor.getDefaultCursor());
                if (!java.util.Objects.equals(diagram.notePlace(moved), noteBefore)) changed("Move note");
                return;
            }
            if (dragLink != null) {
                FlowDiagram.Link moved = dragLink;
                dragLink = null;
                setCursor(Cursor.getDefaultCursor());
                List<Double> now = diagram.route(moved);
                if (now != null && !now.equals(routeBefore)) {
                    FlowDiagram.Method from = diagram.method(moved.from()), to = diagram.method(moved.to());
                    changed("Move line " + label(from) + " → " + label(to));
                    say("The line keeps this way. Right-click it → Reset Line Route to let it find its own again.");
                }
                return;
            }
            if (e.isPopupTrigger()) { menu(e, painter.hit(diagram, p), p); return; }
            if (dragBox != null) {
                FlowDiagram.Box moved = dragBox;
                dragBox = null;
                setCursor(Cursor.getDefaultCursor());
                if (moved.x != dragStartX || moved.y != dragStartY) changed("Move " + moved.name);
            } else if (draftFrom != null) {
                FlowDiagram.Method from = draftFrom;
                draftFrom = null;
                draftTo = null;
                FlowDiagramPainter.Hit hit = painter.hit(diagram, p);
                if (hit.method() != null) connect(from, hit.method());
                else say("Drop on a method row to connect " + label(from) + " to it.");
                repaint();
            }
        }

        private void menu(MouseEvent e, FlowDiagramPainter.Hit hit, Point2D p) {
            JPopupMenu menu = new JPopupMenu();
            if (hit.link() != null) {
                select(null, null, hit.link());
                item(menu, "Format Line…", FlowTheme.ICON_FORMAT, FlowDiagramDialog.this::formatSelection);
                FlowDiagram.Link link = hit.link();
                JMenuItem reset = item(menu, "Reset Line Route", FlowTheme.ICON_LAYOUT, () -> {
                    diagram.setRoute(link, null);
                    diagram.setNotePlace(link, null);
                    changed("Reset line route");
                    repaint();
                });
                reset.setEnabled(diagram.route(link) != null || diagram.notePlace(link) != null);
                reset.setToolTipText(diagram.route(link) != null ? "Let the line find its own way again"
                        : "Drag the line to give it a way of your own");
                JMenuItem remove = item(menu, "Delete Call", FlowTheme.ICON_DELETE, FlowDiagramDialog.this::deleteSelection);
                remove.setEnabled(hit.link().manual());
                if (!hit.link().manual()) remove.setToolTipText("Found in the code");
            } else if (hit.box() != null) {
                FlowDiagram.Box box = hit.box();
                select(box, hit.method(), null);
                if (hit.method() != null) {
                    item(menu, "Format Method (and Note)…", FlowTheme.ICON_FORMAT, FlowDiagramDialog.this::formatSelection);
                    item(menu, "Rename Method…", FlowTheme.ICON_NOTE, FlowDiagramDialog.this::renameSelection);
                    menu.add(tagMenu(hit.method()));
                    item(menu, "Delete Method", FlowTheme.ICON_DELETE, FlowDiagramDialog.this::deleteSelection);
                    item(menu, "Connect From Here…", FlowTheme.ICON_CONNECT, () -> {
                        setTool(Tool.CONNECT);
                        say("Now drag from " + label(hit.method()) + " to the method it calls.");
                    });
                    item(menu, "Connect the Flow to…", FlowTheme.ICON_CONNECT, () -> pickPathEnd(hit.method()));
                    menu.addSeparator();
                }
                item(menu, "Format Box (and Note)…", FlowTheme.ICON_FORMAT, () -> {
                    select(box, null, null);
                    formatSelection();
                });
                menu.addSeparator();
                item(menu, "Add Function…", FlowTheme.ICON_METHOD, FlowDiagramDialog.this::addKnownMethod);
                item(menu, "Change File…", FlowTheme.ICON_CLASS, () -> changeClass(box));
                if (box.isUnknown()) item(menu, "File and Project…", FlowTheme.ICON_NOTE, () -> changeCustomFile(box));
                item(menu, "Rename Class…", FlowTheme.ICON_NOTE, () -> {
                    select(box, null, null);
                    renameSelection();
                });
                item(menu, "Delete Class", FlowTheme.ICON_DELETE, () -> {
                    select(box, null, null);
                    deleteSelection();
                });
            } else {
                select(null, null, null);
                item(menu, "Add File Here…", FlowTheme.ICON_CLASS, () -> addKnownClass(p));
                item(menu, "Auto Layout", FlowTheme.ICON_LAYOUT, FlowDiagramDialog.this::autoLayout);
            }
            if (hit.box() != null || !multi.isEmpty() || FlowDiagramHost.clipboard != null) {
                menu.addSeparator();
                if (hit.box() != null && !multi.contains(hit.box().id) && !multi.isEmpty()) multi.add(hit.box().id);
                int n = pickedBoxes().size();
                String what = n <= 1 ? "Class" : n + " Classes";
                if (n > 0) {
                    item(menu, "Copy " + what, AllIcons.Actions.Copy, () -> copyPicked(false));
                    item(menu, "Cut " + what, AllIcons.Actions.MenuCut, () -> copyPicked(true));
                }
                if (FlowDiagramHost.clipboard != null) item(menu, "Paste", AllIcons.Actions.MenuPaste, FlowDiagramDialog.this::pasteClipboard);
                if (n > 0 && host != null) {
                    javax.swing.JMenu move = submenu("Move " + what + " to Tab", AllIcons.Actions.Forward);
                    javax.swing.JMenu copy = submenu("Copy " + what + " to Tab", AllIcons.Actions.Copy);
                    for (FlowDiagramDialog other : host.pages()) {
                        if (other == FlowDiagramDialog.this) continue;
                        subItem(move, other.flowName(), FlowTheme.ICON_FLOWS, () -> sendTo(other, true));
                        subItem(copy, other.flowName(), FlowTheme.ICON_FLOWS, () -> sendTo(other, false));
                    }
                    if (move.getItemCount() > 0) {
                        move.addSeparator();
                        copy.addSeparator();
                    }
                    subItem(move, "New Tab…", AllIcons.General.Add, () -> sendToNewTab(true));
                    subItem(copy, "New Tab…", AllIcons.General.Add, () -> sendToNewTab(false));
                    menu.add(move);
                    menu.add(copy);
                }
            }
            menu.show(this, e.getX(), e.getY());
        }

        /** Tag ▸ Bug / Important / Question / No Tag: given to all the method's lines on Save. */
        private javax.swing.JMenu tagMenu(FlowDiagram.Method m) {
            javax.swing.JMenu tags = new javax.swing.JMenu("Tag");
            tags.setIcon(FlowTheme.ICON_TAG);
            tags.setBorder(JBUI.Borders.empty(5, 8, 5, 14));
            tags.setIconTextGap(JBUI.scale(8));
            tags.getPopupMenu().setBorder(JBUI.Borders.empty(4, 2));
            // each tag: a ✔ for the method's tag, the tag's mark and name in its color, room around it
            for (FlowTag t : FlowTag.values()) {
                boolean on = t.id.equals(m.tag);
                JMenuItem it = new JMenuItem("<html>" + t.emoji + "&nbsp;&nbsp;" + (on ? "<b>" + t.label + "</b>" : t.label) + "</html>",
                        on ? AllIcons.Actions.Checked : com.intellij.util.ui.EmptyIcon.ICON_16);
                it.setForeground(t.fg);
                it.setIconTextGap(JBUI.scale(8));
                it.setBorder(JBUI.Borders.empty(7, 10, 7, 18));
                it.setToolTipText(on ? "The method's tag" : "Tag every line of the method " + t.label + " (on Save)");
                it.addActionListener(ev -> tagMethod(m, t.id));
                tags.add(it);
            }
            tags.addSeparator();
            JMenuItem none = new JMenuItem("No Tag", m.tag.isEmpty() ? AllIcons.Actions.Checked : com.intellij.util.ui.EmptyIcon.ICON_16);
            none.setIconTextGap(JBUI.scale(8));
            none.setBorder(JBUI.Borders.empty(7, 10, 7, 18));
            none.setEnabled(!m.tag.isEmpty());
            none.addActionListener(ev -> tagMethod(m, ""));
            tags.add(none);
            return tags;
        }

        private JMenuItem item(JPopupMenu menu, String text, Icon icon, Runnable action) {
            JMenuItem it = new JMenuItem(text, icon);
            it.setBorder(JBUI.Borders.empty(5, 8, 5, 14));
            it.setIconTextGap(JBUI.scale(8));
            it.addActionListener(ev -> action.run());
            menu.add(it);
            return it;
        }

        /** A submenu with the same look as the menu's items. */
        private javax.swing.JMenu submenu(String text, Icon icon) {
            javax.swing.JMenu m = new javax.swing.JMenu(text);
            m.setIcon(icon);
            m.setBorder(JBUI.Borders.empty(5, 8, 5, 14));
            m.setIconTextGap(JBUI.scale(8));
            return m;
        }

        private JMenuItem subItem(javax.swing.JMenu m, String text, @Nullable Icon icon, Runnable action) {
            JMenuItem it = new JMenuItem(text, icon);
            it.setBorder(JBUI.Borders.empty(5, 8, 5, 14));
            it.setIconTextGap(JBUI.scale(8));
            it.addActionListener(ev -> action.run());
            m.add(it);
            return it;
        }

        @Override
        public Dimension getPreferredSize() {
            Rectangle2D e = painter.extent(diagram);
            double z = zoom();
            return new Dimension((int) ((Math.max(e.getMaxX(), 600) + 240) * z), (int) ((Math.max(e.getMaxY(), 400) + 200) * z));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            try {
                FlowDiagramPainter.Palette palette = canvasPalette();
                g.setColor(palette.background());
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(FlowDiagramPainter.mix(palette.lifeline(), palette.background(), 0.6)); // dot grid
                int step = (int) Math.max(4, Math.round(20 * zoom()));
                for (int x = step; x < getWidth(); x += step) {
                    for (int y = step; y < getHeight(); y += step) g.fillRect(x, y, 1, 1);
                }
                g.scale(zoom(), zoom());
                painter.paint(g, diagram, palette, selection);
                FlowDiagram.Link line = selection.link();
                if (line != null && tool == Tool.SELECT && diagram.links.contains(line)) { // handles to drag it by
                    java.awt.Stroke was = g.getStroke();
                    g.setStroke(new java.awt.BasicStroke(1.6f));
                    for (Point2D h : painter.laneHandles(diagram, line)) {
                        java.awt.geom.Ellipse2D dot = new java.awt.geom.Ellipse2D.Double(h.getX() - 5, h.getY() - 5, 10, 10);
                        g.setColor(palette.background());
                        g.fill(dot);
                        g.setColor(palette.selection());
                        g.draw(dot);
                    }
                    g.setStroke(was);
                }
                if (!multi.isEmpty()) { // the classes picked with Ctrl+click
                    g.setColor(palette.selection());
                    g.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND, 10,
                            new float[]{6, 4}, 0));
                    for (String id : multi) {
                        FlowDiagram.Box b = diagram.box(id);
                        if (b == null) continue;
                        Rectangle2D r = painter.bounds(b);
                        g.draw(new java.awt.geom.RoundRectangle2D.Double(r.getX() - 5, r.getY() - 5, r.getWidth() + 10, r.getHeight() + 10, 16, 16));
                    }
                }
                if (draftFrom != null && draftTo != null && diagram.boxOf(draftFrom.id) != null) {
                    FlowDiagram.Box b = diagram.boxOf(draftFrom.id);
                    Rectangle2D r = painter.rowBounds(b, Math.max(0, b.methods.indexOf(draftFrom)));
                    painter.paintDraft(g, new Point2D.Double(r.getMaxX(), r.getCenterY()), draftTo, palette);
                }
                if (diagram.boxes.isEmpty()) {
                    g.setFont(JBUI.Fonts.label().deriveFont(13f));
                    g.setColor(palette.muted());
                    g.drawString("This flow has no classes yet. Click Add File to pick one from the project.", 40, 60);
                }
            } finally {
                g.dispose();
            }
        }
    }

    private static final Color ADDED = new JBColor(0x208A3C, 0x5FB865);

    /** The IDE theme's colors (they follow light and dark themes). */
    private static FlowDiagramPainter.Palette themed() {
        return new FlowDiagramPainter.Palette(FlowTheme.PANEL_BG, FlowTheme.CARD_BG, FlowTheme.CARD_BORDER,
                FlowTheme.TAG_BG, FlowTheme.FG, FlowTheme.FG_MUTED, FlowTheme.CODE_FG, ADDED, FlowTheme.WARNING,
                FlowTheme.FG_MUTED, FlowTheme.ACCENT, FlowTheme.SELECTION_BAR, FlowTheme.CODE_BG, FlowTheme.CODE_BORDER,
                FlowTheme.GUIDE);
    }
}
