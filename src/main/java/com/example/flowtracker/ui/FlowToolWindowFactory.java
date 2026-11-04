package com.example.flowtracker.ui;

import com.example.flowtracker.code.FlowLang;
import com.example.flowtracker.code.FlowSync;
import com.example.flowtracker.compat.FlowCompat;
import com.example.flowtracker.copy.CodeHighlighter;
import com.example.flowtracker.copy.FlowCopy;
import com.example.flowtracker.copy.FlowFormat;
import com.example.flowtracker.diagram.FlowDiagramDialog;
import com.example.flowtracker.editor.FlowRecorder;
import com.example.flowtracker.editor.FlowWalk;
import com.example.flowtracker.model.FlowCheck;
import com.example.flowtracker.model.FlowDebugStore;
import com.example.flowtracker.model.FlowSnapshot;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowLook;
import com.example.flowtracker.model.FlowProjects;
import com.example.flowtracker.model.FlowStore;
import com.example.flowtracker.model.FlowTag;
import com.example.flowtracker.model.FlowTransfer;
import com.intellij.icons.AllIcons;
import com.intellij.ide.CopyProvider;
import com.intellij.ide.DeleteProvider;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPopupMenu;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.plaf.basic.BasicTreeUI;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.GridBagLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GradientPaint;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class FlowToolWindowFactory implements ToolWindowFactory, DumbAware {

    /** Tree node payloads. Level 1 = FileNode, level 2 = MethodNode, level 3 = FlowEntry (flat view: level 1). */
    private record FileNode(String url, String name, String project) {}
    private record MethodNode(String fileUrl, String label) {}

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        FlowStore store = FlowStore.get(project);

        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        DefaultTreeModel model = new DefaultTreeModel(root);

        // Y position of the drop indicator while a row is being dragged (-1 = none).
        int[] dropLineY = {-1};
        // Row under the mouse (-1 = none) and which arrow of it is under the mouse (-1 up, +1 down, 0 none).
        int[] hoverRow = {-1};
        int[] hoverAction = {-1}; // RowAction ordinal under the mouse, -1 = none
        // Search box text: only lines that match it are shown ("" = all).
        String[] filter = {""};

        RowRenderer renderer = new RowRenderer();

        JTree tree = new JTree(model) {
            @Override
            public void updateUI() {
                setUI(new FlowTreeUI());
                setRowHeight(0); // every row reports its own height
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(FlowTheme.CARD_BG);
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    paintDecorations(this, g2, hoverRow[0]);
                } finally {
                    g2.dispose();
                }
                super.paintComponent(g);
                Graphics2D top = (Graphics2D) g.create();
                try {
                    if (dropLineY[0] >= 0) {
                        top.setColor(FlowTheme.DROP_LINE);
                        top.fillRect(JBUI.scale(4), dropLineY[0] - 1, getWidth() - JBUI.scale(8), 2);
                    } else {
                        paintBar(this, top, hoverRow[0], hoverAction[0]);
                    }
                    if (root.getChildCount() == 0) {
                        paintEmptyText(this, top, filter[0].isEmpty() || store.getEntries().isEmpty()
                                ? "Hover a line in a source file and click +"
                                : "No lines match \u201c" + filter[0] + "\u201d");
                    }
                } finally {
                    top.dispose();
                }
            }

            @Override
            public String getToolTipText(MouseEvent e) {
                BarHit hit = barHit(this, e.getPoint(), hoverRow);
                if (hit != null) return hit.action.label;
                int row = rowAt(this, e.getPoint());
                TreePath path = row < 0 ? null : getPathForRow(row);
                if (path == null) return null;
                Object uo = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                FlowCheck.Problem problem = uo instanceof FlowEntry fe ? renderer.problems.get(fe) : null;
                if (problem == null) return uo instanceof FlowEntry fe ? savedValuesTip(project, fe) : null;
                return "<html>" + StringUtil.escapeXmlEntities(problem.message())
                        + (problem.kind() == FlowCheck.Kind.MISSING_FILE ? ""
                        : "<br><small>Right-click \u2192 Re-link to Code</small>") + "</html>";
            }
        };
        ToolTipManager.sharedInstance().registerComponent(tree);
        // lines with values saved in Debug Data get a dot next to their number (hover it for the values)
        FlowDebugStore debugStore = FlowDebugStore.get(project);
        Runnable revalue = () -> {
            renderer.valued.clear();
            for (FlowSnapshot s : debugStore.getSnapshots()) renderer.valued.add(s.fileUrl + "#" + s.line);
            tree.repaint();
        };
        revalue.run();
        debugStore.addListener(revalue);
        Disposer.register(toolWindow.getDisposable(), () -> debugStore.removeListener(revalue));
        tree.setOpaque(false);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setAutoscrolls(true);
        tree.setBorder(JBUI.Borders.empty(6, 2, 6, 6));
        tree.setCellRenderer(renderer);

        // Payload of the node to select again after the tree is rebuilt (keeps a moved node selected).
        AtomicReference<Object> reselect = new AtomicReference<>();

        // ----- buttons -----
        FlowButton groupedView = new FlowButton("Grouped View", FlowTheme.ICON_GROUPED, FlowButton.Style.SEGMENT);
        groupedView.setToolTipText("Group lines by file and method");
        groupedView.addActionListener(e -> store.setFlatView(false));

        FlowButton flatView = new FlowButton("Flat View", FlowTheme.ICON_LIST, FlowButton.Style.SEGMENT);
        flatView.setToolTipText("One flat sequence in click order, with step numbers");
        flatView.addActionListener(e -> store.setFlatView(true));

        // Where new lines go: toggles between adding at the bottom and at the top.
        FlowButton addMode = new FlowButton("Add Bottom", FlowTheme.ICON_DOWN, FlowButton.Style.PRIMARY);
        addMode.addActionListener(e -> store.setAddAtTop(!store.isAddAtTop()));

        // Same switch as "Sync Files" in Settings. Moving anything by hand turns it off; turning it back on
        // re-sorts the lines into call order.
        FlowButton syncButton = new FlowButton("Sync", FlowTheme.ICON_SYNC, FlowButton.Style.OUTLINE);
        syncButton.addActionListener(e -> store.setSyncFiles(!store.isSyncFiles())); // the refresh re-sorts

        // Record: every line where the debugger stops is added, in execution order.
        FlowRecorder recorder = FlowRecorder.get(project);
        FlowButton record = new FlowButton("Record", FlowTheme.ICON_RECORD, FlowButton.Style.OUTLINE);
        record.addActionListener(e -> recorder.toggle());

        // Walk-through: open each saved line in the editor in turn (also Alt+[ / Alt+]).
        FlowButton prevStep = new FlowButton("Previous Step", FlowTheme.ICON_PREV, FlowButton.Style.OUTLINE);
        prevStep.setToolTipText("Previous step: open the previous line in the editor (Alt+[)");
        prevStep.addActionListener(e -> FlowWalk.step(project, -1));
        FlowButton nextStep = new FlowButton("Next Step", FlowTheme.ICON_NEXT, FlowButton.Style.OUTLINE);
        nextStep.setToolTipText("Next step: open the next line in the editor (Alt+])");
        nextStep.addActionListener(e -> FlowWalk.step(project, +1));

        FlowButton copy = new FlowButton("Copy", FlowTheme.ICON_COPY, FlowButton.Style.OUTLINE);
        copy.setToolTipText("Copy everything (Ctrl+C copies the selected lines)");
        Runnable copyAll = () -> {
            if (store.isCopyAllFlows()) FlowCopy.copyAllFlows(project);
            else copyToClipboard(textFor(store, visible(store, filter[0])));
        };
        copy.addActionListener(e -> copyAll.run());
        copy.setDropDown("More ways to copy: the Original Log only", () -> {
            Map<String, List<FlowSettingsPopup.Choice>> sections = new LinkedHashMap<>();
            sections.put("", List.of(
                    new FlowSettingsPopup.Choice(FlowTheme.ICON_COPY, "Copy",
                            store.isCopyMarkdown() ? "The Markdown document (Settings: Copy as Markdown)." : "The plain text of this view.",
                            () -> false, copyAll),
                    new FlowSettingsPopup.Choice(FlowTheme.ICON_LIST, "Copy Original Log",
                            (store.isNestedCallLog() ? "Each call nested under the line that makes it" : "The plain list of this view")
                                    + " (what the Markdown copy ends with), as plain text.",
                            () -> false, () -> FlowCopy.copyOriginalLog(project, visible(store, filter[0]))),
                    new FlowSettingsPopup.Choice(FlowTheme.ICON_EXPORT, "Export as HTML Page\u2026",
                            "One page with the diagram and the lines" + (store.isCopyAllFlows() ? " of every flow" : "")
                                    + ", to open in any browser or send to someone without the plugin.",
                            () -> false, () -> FlowCopy.exportHtml(project, visible(store, filter[0])))));
            FlowSettingsPopup.showChoices(copy, "Copy", sections,
                    store.isCopyAllFlows() ? "Copy All Flows is on: every flow is copied." : null, true);
        });

        // Open the flow as a Markdown file with the preview (all flows when Copy All Flows is on).
        FlowButton openMarkdown = new FlowButton("Open Markdown", FlowTheme.ICON_MARKDOWN, FlowButton.Style.OUTLINE);
        openMarkdown.addActionListener(e -> FlowCopy.openMarkdown(project, visible(store, filter[0])));

        // The flow's classes and calls as an editable diagram (also copied as PNG images from there).
        FlowButton diagram = new FlowButton("Flow Diagram", FlowTheme.ICON_DIAGRAM, FlowButton.Style.OUTLINE);
        diagram.setToolTipText("Open the flow as a diagram: add, rename and remove classes and methods, connect calls, "
                + "and copy it (or its sequence diagram) as a PNG image");
        diagram.addActionListener(e -> FlowDiagramDialog.open(project));

        FlowButton delete = new FlowButton("Delete", FlowTheme.ICON_DELETE, FlowButton.Style.OUTLINE);
        delete.setToolTipText("Delete the selected lines");
        delete.setEnabled(false);
        delete.addActionListener(e -> store.remove(selectedEntries(tree, store)));

        FlowButton clear = new FlowButton("Clear", FlowTheme.ICON_CLEAR, FlowButton.Style.OUTLINE);
        clear.setToolTipText("Remove everything");
        clear.addActionListener(e -> store.clear());

        // Short note on the selected line (works in both views).
        FlowButton note = new FlowButton("Note", FlowTheme.ICON_NOTE, FlowButton.Style.OUTLINE);
        note.setToolTipText("Add or edit a short note on the selected line");
        note.setEnabled(false);
        Runnable editNote = () -> {
            TreePath[] sel = tree.getSelectionPaths();
            if (sel == null || sel.length != 1) return;
            Object uo = ((DefaultMutableTreeNode) sel[0].getLastPathComponent()).getUserObject();
            if (!(uo instanceof FlowEntry entry)) return;
            String input = Messages.showInputDialog(project,
                    "Short note for this line (leave empty to remove it):",
                    "TraceWeave Note", null, entry.note == null ? "" : entry.note, null);
            if (input != null) {
                reselect.set(entry);
                store.setNote(entry, input.trim());
            }
        };
        note.addActionListener(e -> editNote.run());

        // Undo / redo of added and deleted lines (from the editor gutter, the panel, or Clear).
        FlowButton undo = new FlowButton("Undo", FlowTheme.ICON_UNDO, FlowButton.Style.OUTLINE);
        undo.addActionListener(e -> store.undo());
        FlowButton redo = new FlowButton("Redo", FlowTheme.ICON_REDO, FlowButton.Style.OUTLINE);
        redo.addActionListener(e -> store.redo());

        // Settings: options that change how lines are ordered and copied.
        FlowButton settings = new FlowButton("Settings", FlowTheme.ICON_SETTINGS, FlowButton.Style.OUTLINE);
        settings.addActionListener(e -> FlowSettingsPopup.show(project, store, settings));

        // Named flows: switch, create, rename, delete, export and import.
        FlowButton flowMenu = new FlowButton(store.getActiveFlowName(), FlowTheme.ICON_FLOWS, FlowButton.Style.OUTLINE);
        flowMenu.setToolTipText("Flows: switch, new, rename, delete, export and import");
        flowMenu.addActionListener(e -> showFlowMenu(project, store, flowMenu));

        SearchTextField search = new SearchTextField(false);
        search.getTextEditor().getEmptyText().setText("Filter lines (code, file, method, note, tag)");

        Runnable updateButtons = () -> {
            delete.setEnabled(!tree.isSelectionEmpty());
            TreePath[] sel = tree.getSelectionPaths();
            note.setEnabled(sel != null && sel.length == 1
                    && ((DefaultMutableTreeNode) sel[0].getLastPathComponent()).getUserObject() instanceof FlowEntry);
        };

        Runnable syncMode = () -> {
            boolean top = store.isAddAtTop();
            addMode.setText(top ? "Add Top" : "Add Bottom");
            addMode.setIcon(top ? FlowTheme.ICON_UP : FlowTheme.ICON_DOWN);
            settings.setToolTipText("<html>Settings<br>Sync Files: " + onOff(store.isSyncFiles())
                    + "<br>Optimize Flat View Log: " + onOff(store.isOptimizeFlatLog())
                    + "<br>Copy as Markdown: " + onOff(store.isCopyMarkdown())
                    + "<br>Sequence Diagram: " + onOff(store.isSequenceDiagram())
                    + "<br>Copy All Flows: " + onOff(store.isCopyAllFlows())
                    + "<br>Nest Calls in Original Log: " + onOff(store.isNestedCallLog()) + "</html>");
            flowMenu.setText(StringUtil.shortenTextWithEllipsis(store.getActiveFlowName(), 24, 0) + "  \u25BE");
            openMarkdown.setToolTipText(store.isCopyAllFlows()
                    ? "Open all flows as a Markdown file with the preview (Copy All Flows is on)"
                    : "Open this flow as a Markdown file with the preview: diagrams, line tables and the Original Log");
            copy.setToolTipText(store.isCopyAllFlows()
                    ? "Copy all flows (Copy All Flows is on in Settings; Ctrl+C copies the selected lines)"
                    : filter[0].isEmpty()
                    ? "Copy everything (Ctrl+C copies the selected lines)"
                    : "Copy the lines that match the filter (Ctrl+C copies the selected lines)");
            addMode.setToolTipText(top
                    ? "New lines are added at the top. Click to add at the bottom instead."
                    : "New lines are added at the bottom. Click to add at the top instead.");
            undo.setEnabled(store.canUndo());
            undo.setToolTipText(store.canUndo() ? store.undoLabel() : "nothing to undo");
            redo.setEnabled(store.canRedo());
            redo.setToolTipText(store.canRedo() ? store.redoLabel() : "nothing to redo");
            syncButton.setSelected(store.isSyncFiles());
            syncButton.setToolTipText(store.isSyncFiles()
                    ? "Sync Files is on: lines are kept in call order. Click to turn it off."
                    : "Sync Files is off. Click to put the lines in call order again.");
            groupedView.setSelected(!store.isFlatView());
            flatView.setSelected(store.isFlatView());
            updateButtons.run();
        };

        tree.addTreeSelectionListener(e -> {
            updateButtons.run();
            // Selecting one line makes it the walk-through's current step.
            TreePath[] sel = tree.getSelectionPaths();
            if (sel != null && sel.length == 1
                    && ((DefaultMutableTreeNode) sel[0].getLastPathComponent()).getUserObject() instanceof FlowEntry fe) {
                store.setWalkEntry(fe);
            }
        });

        // Lines that no longer match the code get a warning (re-checked shortly after any edit).
        Runnable recheck = () -> {
            renderer.problems.clear();
            for (FlowEntry e : store.getEntries()) {
                FlowCheck.Problem problem = FlowCheck.check(e);
                if (problem != null) renderer.problems.put(e, problem);
            }
        };
        Timer recheckLater = new Timer(400, e -> {
            recheck.run();
            ((FlowTreeUI) tree.getUI()).invalidateSizes();
            tree.repaint();
        });
        recheckLater.setRepeats(false);
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                VirtualFile vf = FileDocumentManager.getInstance().getFile(event.getDocument());
                if (FlowLang.isSupported(vf)) recheckLater.restart(); // ignore consoles and other files
            }
        }, toolWindow.getDisposable());
        Disposer.register(toolWindow.getDisposable(), recheckLater::stop);

        // ----- rebuild the tree whenever the store changes -----
        Runnable refresh = () -> SwingUtilities.invokeLater(() -> {
            // Re-analyse calls (and re-sort when Sync Files is on); runs in the background.
            FlowSync.apply(project);
            syncMode.run();
            Object want = reselect.getAndSet(null);
            DefaultMutableTreeNode wantNode = null;

            boolean flat = store.isFlatView();
            renderer.flat = flat;
            // Entries saved before project names were recorded get one now.
            FlowProjects.backfill(project, store.getEntries()); // edits the stored entries in place
            renderer.showProjectInFlat = FlowFormat.spansProjects(store.getEntries());
            recheck.run();
            tree.setShowsRootHandles(!flat);
            hoverRow[0] = -1;
            hoverAction[0] = -1;
            List<FlowEntry> shown = visible(store, filter[0]);

            root.removeAllChildren();
            if (flat) {
                // One simple list in stored order: every line can move freely.
                for (FlowEntry e : shown) {
                    DefaultMutableTreeNode lineNode = new DefaultMutableTreeNode(e);
                    if (matches(want, lineNode)) wantNode = lineNode;
                    root.add(lineNode);
                }
            } else {
                for (FlowFormat.FileGroup f : FlowFormat.group(shown)) {
                    DefaultMutableTreeNode fileNode =
                            new DefaultMutableTreeNode(new FileNode(f.fileUrl(), f.fileName(), f.projectName()));
                    if (matches(want, fileNode)) wantNode = fileNode;
                    for (Map.Entry<String, List<FlowEntry>> m : f.methods().entrySet()) {
                        DefaultMutableTreeNode methodNode =
                                new DefaultMutableTreeNode(new MethodNode(f.fileUrl(), m.getKey()));
                        if (matches(want, methodNode)) wantNode = methodNode;
                        for (FlowEntry e : m.getValue()) {
                            DefaultMutableTreeNode lineNode = new DefaultMutableTreeNode(e);
                            if (matches(want, lineNode)) wantNode = lineNode;
                            methodNode.add(lineNode);
                        }
                        fileNode.add(methodNode);
                    }
                    root.add(fileNode);
                }
            }
            model.reload();
            TreeUtil.expandAll(tree);
            if (wantNode != null) {
                // Path is computed now that the node is attached to the root.
                TreePath path = new TreePath(wantNode.getPath());
                tree.setSelectionPath(path);
                tree.scrollPathToVisible(path);
            }
        });
        store.addListener(refresh);
        Disposer.register(toolWindow.getDisposable(), () -> store.removeListener(refresh));
        syncMode.run();
        refresh.run();

        search.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull javax.swing.event.DocumentEvent e) {
                filter[0] = search.getText().strip();
                refresh.run();
            }
        });

        // The walk-through moved (Next / Previous Step): select its line here too.
        Runnable followWalk = () -> SwingUtilities.invokeLater(() -> {
            FlowEntry step = store.getWalkEntry();
            if (step == null) return;
            Enumeration<?> nodes = root.preorderEnumeration();
            while (nodes.hasMoreElements()) {
                DefaultMutableTreeNode n = (DefaultMutableTreeNode) nodes.nextElement();
                if (n.getUserObject() != step) continue;
                TreePath path = new TreePath(n.getPath());
                if (!path.equals(tree.getSelectionPath())) tree.setSelectionPath(path);
                tree.scrollPathToVisible(path);
                return;
            }
        });
        store.addWalkListener(followWalk);
        Disposer.register(toolWindow.getDisposable(), () -> store.removeWalkListener(followWalk));

        Runnable recordMode = () -> SwingUtilities.invokeLater(() -> {
            boolean on = recorder.isRecording();
            record.setSelected(on);
            record.setText(on ? "Recording" : "Record");
            record.setToolTipText(on
                    ? "Recording: every line where the debugger stops is added. Click to stop."
                    : "Record: add every line where the debugger stops (breakpoints and steps), in the "
                    + "order it runs. Turns Sync off.");
        });
        recorder.addListener(recordMode);
        Disposer.register(toolWindow.getDisposable(), () -> recorder.removeListener(recordMode));
        recordMode.run();

        // ----- clicking anywhere on a row selects it (Swing alone only reacts on the text itself) -----
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int row = rowAt(tree, e.getPoint());
                if (row < 0) return;
                Rectangle b = tree.getRowBounds(row);
                if (e.getX() >= b.x && e.getX() < b.x + b.width) return; // handled by the tree itself
                TreePath path = tree.getPathForRow(row);
                boolean onChevron = e.getX() >= b.x - JBUI.scale(24) && e.getX() < b.x
                        && !((DefaultMutableTreeNode) path.getLastPathComponent()).isLeaf();
                if (onChevron) return; // expand / collapse
                tree.requestFocusInWindow();
                if (SwingUtilities.isRightMouseButton(e) && tree.isRowSelected(row)) return;
                if (e.isControlDown() || e.isMetaDown()) {
                    if (tree.isRowSelected(row)) tree.removeSelectionRow(row);
                    else tree.addSelectionRow(row);
                } else if (e.isShiftDown() && tree.getLeadSelectionRow() >= 0) {
                    tree.setSelectionInterval(tree.getLeadSelectionRow(), row);
                } else {
                    tree.setSelectionRow(row);
                }
            }
        });

        // ----- right-click: move, copy, note, jump, delete for the clicked row -----
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }

            private void maybeShow(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = rowAt(tree, e.getPoint());
                if (row < 0) return;
                if (!tree.isRowSelected(row)) tree.setSelectionRow(row);
                showContextMenu(project, store, tree, root, reselect, editNote, renderer.problems, e);
            }
        });

        // ----- mouse: arrows, drag to reorder, double-click a line to jump to it -----
        DefaultMutableTreeNode[] pressed = new DefaultMutableTreeNode[1];
        Point[] pressPoint = new Point[1];
        boolean[] dragging = {false};
        int[] dropInsertAt = {-1};
        int[] lastHoverRow = {-1};

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                pressed[0] = null;
                dragging[0] = false;
                if (!SwingUtilities.isLeftMouseButton(e)) return;

                BarHit hit = barHit(tree, e.getPoint(), hoverRow);
                if (hit != null) {
                    switch (hit.action) {
                        case UP -> moveNode(store, reselect, root, hit.node, -1);
                        case DOWN -> moveNode(store, reselect, root, hit.node, +1);
                        case COPY -> copyToClipboard(textFor(store, entriesOf(hit.node, store)));
                        case DELETE -> store.remove(entriesOf(hit.node, store));
                        case NOTE -> {
                            tree.setSelectionPath(new TreePath(hit.node.getPath()));
                            editNote.run();
                        }
                    }
                    e.consume();
                    return;
                }
                if (onBar(tree, e.getPoint(), hoverRow)) return; // disabled button or gap: no drag
                pressed[0] = nodeAtY(tree, e.getPoint());
                pressPoint[0] = e.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                DefaultMutableTreeNode node = pressed[0];
                if (node == null) return;
                Point p = e.getPoint();
                if (!dragging[0]) {
                    if (pressPoint[0].distance(p) < 6) return; // not a drag yet
                    dragging[0] = true;
                    tree.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
                tree.scrollRectToVisible(new Rectangle(p.x, p.y - 12, 1, 24));
                DropTarget target = computeDrop(tree, node, p.y);
                dropInsertAt[0] = target.insertAt;
                dropLineY[0] = target.lineY;
                tree.repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                DefaultMutableTreeNode node = pressed[0];
                boolean wasDragging = dragging[0];
                int insertAt = dropInsertAt[0];

                pressed[0] = null;
                dragging[0] = false;
                dropInsertAt[0] = -1;
                dropLineY[0] = -1;
                tree.setCursor(Cursor.getDefaultCursor());
                tree.repaint();

                if (!wasDragging || node == null || insertAt < 0) return;
                DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
                if (parent == null) return;
                int from = parent.getIndex(node);
                int to = insertAt > from ? insertAt - 1 : insertAt;
                if (to == from) return;
                parent.remove(node);
                parent.insert(node, to);
                commit(store, reselect, root, node);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || onBar(tree, e.getPoint(), hoverRow)) return;
                Object node = tree.getLastSelectedPathComponent();
                if (node instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof FlowEntry entry) {
                    navigate(project, entry);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int row = rowAt(tree, e.getPoint());
                hoverRow[0] = row;
                BarHit hit = row < 0 ? null : barHit(tree, e.getPoint(), hoverRow);
                int action = hit == null ? -1 : hit.action.ordinal();
                boolean changed = action != hoverAction[0] || row != lastHoverRow[0];
                hoverAction[0] = action;
                lastHoverRow[0] = row;
                tree.setCursor(hit != null
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        : Cursor.getDefaultCursor());
                if (changed) tree.repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (dragging[0]) return;
                hoverRow[0] = -1;
                hoverAction[0] = -1;
                lastHoverRow[0] = -1;
                tree.setCursor(Cursor.getDefaultCursor());
                tree.repaint();
            }
        };
        tree.addMouseListener(mouse);
        tree.addMouseMotionListener(mouse);

        // ----- Ctrl+C and Delete key (IDE's standard copy / delete actions) -----
        CopyProvider copyProvider = new CopyProvider() {
            @Override
            public void performCopy(@NotNull DataContext dataContext) {
                copyToClipboard(textFor(store, selectedEntries(tree, store)));
            }
            @Override
            public boolean isCopyEnabled(@NotNull DataContext dataContext) {
                return !tree.isSelectionEmpty();
            }
            @Override
            public boolean isCopyVisible(@NotNull DataContext dataContext) {
                return true;
            }
            public ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };

        DeleteProvider deleteProvider = new DeleteProvider() {
            @Override
            public void deleteElement(@NotNull DataContext dataContext) {
                store.remove(selectedEntries(tree, store));
            }
            @Override
            public boolean canDeleteElement(@NotNull DataContext dataContext) {
                return !tree.isSelectionEmpty();
            }
            public ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        };

        // View switch | where new lines go | actions. Squeezes to icons and wraps when narrow.
        FlowToolbar toolbar = new FlowToolbar();
        toolbar.addGroup(true, 2, groupedView, flatView);
        toolbar.addGroup(false, 2, addMode, syncButton, record);
        toolbar.addGroup(false, 0, undo, redo); // always icon-only
        toolbar.addGroup(false, 0, prevStep, nextStep); // always icon-only
        toolbar.addGroup(false, 1, copy, openMarkdown, diagram, delete, clear, note);
        toolbar.addGroup(false, 0, settings); // always icon-only
        toolbar.setBorder(JBUI.Borders.emptyBottom(10));

        // Flow picker | search box, above the toolbar.
        JPanel flowBox = new JPanel(new GridBagLayout()); // keeps the button at its own height
        flowBox.setOpaque(false);
        flowBox.add(flowMenu);
        JPanel header = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        header.setOpaque(false);
        header.setBorder(JBUI.Borders.emptyBottom(8));
        header.add(flowBox, BorderLayout.WEST);
        header.add(search, BorderLayout.CENTER);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);
        top.add(toolbar, BorderLayout.CENTER);

        JBScrollPane scroll = FlowScroll.wheel(new JBScrollPane(tree));
        scroll.setBorder(JBUI.Borders.empty());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        // The arrows stick to the visible right edge, so scrolling must repaint instead of shifting pixels.
        scroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);

        FlowCard card = new FlowCard();
        card.add(scroll, BorderLayout.CENTER);

        RootPanel rootPanel = new RootPanel(copyProvider, deleteProvider);
        rootPanel.setBackground(FlowTheme.PANEL_BG);
        rootPanel.setBorder(JBUI.Borders.empty(10));
        rootPanel.add(top, BorderLayout.NORTH);
        rootPanel.add(card, BorderLayout.CENTER);
        // Use IntelliJ Theme switched: the colors are looked up when painting, so a repaint shows the new ones.
        Runnable repaint = rootPanel::repaint;
        FlowLook.get().addListener(repaint);
        Disposer.register(toolWindow.getDisposable(), () -> FlowLook.get().removeListener(repaint));

        Content content = ContentFactory.getInstance().createContent(rootPanel, "Flow", false);
        toolWindow.getContentManager().addContent(content);

        // Debug values saved from the debugger (Variables view → Save to TraceWeave): find, compare, edit.
        FlowDebugPanel debugData = new FlowDebugPanel(project);
        Content debugContent = ContentFactory.getInstance().createContent(debugData, FlowDebugPanel.TAB, false);
        debugContent.setDisposer(debugData);
        toolWindow.getContentManager().addContent(debugContent);

        // Stop at Value: the debugger stops where values are found; the history of every hunt and stop
        FlowHuntPanel hunts = new FlowHuntPanel(project);
        Content huntContent = ContentFactory.getInstance().createContent(hunts, FlowHuntPanel.TAB, false);
        huntContent.setDisposer(hunts);
        toolWindow.getContentManager().addContent(huntContent);
    }

    // ================= tree look =================

    /** Row heights and code-block spacing (unscaled). */
    private static final int FILE_H = 34;
    private static final int METHOD_H = 30;
    private static final int LINE_H = 26;
    /** Space above a code block, inner padding, and space below it. */
    private static final int BOX_GAP_TOP = 2;
    private static final int BOX_PAD = 5;
    private static final int BOX_GAP_BOTTOM = 8;
    /** How far a code block reaches to the left of its line rows. */
    private static final int BOX_LEFT = 6;
    private static final int CHILD_INDENT_LEFT = 10;
    private static final int CHILD_INDENT_RIGHT = 14;

    /** Plain Swing tree UI so every pixel (selection, guides, code blocks) is painted by this class. */
    private static final class FlowTreeUI extends BasicTreeUI {
        @Override
        protected void installDefaults() {
            super.installDefaults();
            setLeftChildIndent(JBUI.scale(CHILD_INDENT_LEFT));
            setRightChildIndent(JBUI.scale(CHILD_INDENT_RIGHT));
            setExpandedIcon(AllIcons.General.ChevronDown);
            setCollapsedIcon(AllIcons.General.ChevronRight);
        }

        // Guide lines are drawn by paintDecorations instead.
        @Override protected void paintHorizontalPartOfLeg(Graphics g, Rectangle clip, Insets insets, Rectangle bounds,
                                                          TreePath path, int row, boolean expanded,
                                                          boolean hasBeenExpanded, boolean leaf) {}
        @Override protected void paintVerticalPartOfLeg(Graphics g, Rectangle clip, Insets insets, TreePath path) {}

        /** Measures every row again (a row's width changed without the tree being rebuilt). */
        void invalidateSizes() {
            if (treeState != null) treeState.invalidateSizes();
            updateSize();
        }
    }

    private static boolean isEntry(DefaultMutableTreeNode n) {
        return n.getUserObject() instanceof FlowEntry;
    }

    /** Extra space above a line row: the first line of a code block makes room for the block's top edge. */
    private static int padTop(DefaultMutableTreeNode n) {
        if (!isEntry(n) || n.getParent() == null || n.getParent().getIndex(n) != 0) return 0;
        return JBUI.scale(BOX_GAP_TOP + BOX_PAD);
    }

    private static int padBottom(DefaultMutableTreeNode n) {
        if (!isEntry(n) || n.getParent() == null) return 0;
        if (n.getParent().getIndex(n) != n.getParent().getChildCount() - 1) return 0;
        return JBUI.scale(BOX_PAD + BOX_GAP_BOTTOM);
    }

    /** Width of the line-number column inside a code block, measured from the line row's x. */
    private static int gutterWidth(FontMetrics codeMetrics) {
        return Math.max(JBUI.scale(34), codeMetrics.stringWidth("0000") + JBUI.scale(8));
    }

    /** Chevron center x for a row, matching where BasicTreeUI paints the expand icon. */
    private static int chevronX(Rectangle rowBounds) {
        return rowBounds.x - JBUI.scale(CHILD_INDENT_RIGHT) + 1;
    }

    private static @Nullable Rectangle boundsOf(JTree tree, DefaultMutableTreeNode n) {
        return tree.getPathBounds(new TreePath(n.getPath()));
    }

    /** Rounded rectangle around all line rows of one parent (a method, or the root in flat view). */
    private static @Nullable Rectangle codeBox(JTree tree, DefaultMutableTreeNode parent) {
        if (parent.getChildCount() == 0) return null;
        Rectangle first = boundsOf(tree, (DefaultMutableTreeNode) parent.getFirstChild());
        Rectangle last = boundsOf(tree, (DefaultMutableTreeNode) parent.getLastChild());
        if (first == null || last == null) return null;
        int x = first.x - JBUI.scale(BOX_LEFT);
        int y = first.y + JBUI.scale(BOX_GAP_TOP);
        int right = tree.getWidth() - JBUI.scale(4);
        int bottom = last.y + last.height - JBUI.scale(BOX_GAP_BOTTOM);
        return new Rectangle(x, y, Math.max(0, right - x), Math.max(0, bottom - y));
    }

    /** Background layer under the rows: guide lines, code blocks, hover and selection. */
    private static void paintDecorations(JTree tree, Graphics2D g, int hoverRow) {
        int arc = JBUI.scale(10);
        FontMetrics codeFm = tree.getFontMetrics(FlowTheme.codeFont());
        int gutter = gutterWidth(codeFm);
        g.setStroke(new BasicStroke(JBUIScale.scale(1f)));

        for (int row = 0; row < tree.getRowCount(); row++) {
            TreePath path = tree.getPathForRow(row);
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            Rectangle b = tree.getRowBounds(row);
            if (b == null) continue;
            Object o = node.getUserObject();

            // Guides: a line down from each expanded file, and an elbow from each method into its code block.
            if (o instanceof FileNode && tree.isExpanded(path) && node.getChildCount() > 0) {
                Rectangle span = spanOf(tree, node);
                if (span != null) {
                    int x = chevronX(b);
                    g.setColor(FlowTheme.GUIDE);
                    g.drawLine(x, b.y + b.height - JBUI.scale(6), x,
                            span.y + span.height - JBUI.scale(BOX_GAP_BOTTOM) - JBUI.scale(4));
                }
            }
            if (o instanceof MethodNode && tree.isExpanded(path) && node.getChildCount() > 0) {
                Rectangle box = codeBox(tree, node);
                Rectangle firstLine = boundsOf(tree, (DefaultMutableTreeNode) node.getFirstChild());
                if (box != null && firstLine != null) {
                    int x = chevronX(b);
                    int cy = firstLine.y + padTop((DefaultMutableTreeNode) node.getFirstChild())
                            + JBUI.scale(LINE_H) / 2;
                    int r = JBUI.scale(5);
                    Path2D elbow = new Path2D.Float();
                    elbow.moveTo(x, b.y + b.height - JBUI.scale(6));
                    elbow.lineTo(x, cy - r);
                    elbow.quadTo(x, cy, x + r, cy);
                    elbow.lineTo(box.x, cy);
                    g.setColor(FlowTheme.GUIDE);
                    g.draw(elbow);
                }
            }

            // Code block: drawn once, at its first line row.
            if (isEntry(node) && node.getParent().getIndex(node) == 0) {
                DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
                Rectangle box = codeBox(tree, parent);
                if (box != null) {
                    g.setColor(FlowTheme.CODE_BG);
                    g.fillRoundRect(box.x, box.y, box.width, box.height, arc, arc);
                    // Line-number column separator.
                    g.setColor(FlowTheme.CODE_BORDER);
                    int sx = b.x + gutter;
                    g.drawLine(sx, box.y + 1, sx, box.y + box.height - 2);
                    g.draw(new RoundRectangle2D.Float(box.x + 0.5f, box.y + 0.5f, box.width - 1f, box.height - 1f, arc, arc));
                }
            }

            boolean selected = tree.isRowSelected(row);
            if (!selected && row != hoverRow) continue;
            if (isEntry(node)) {
                Rectangle box = codeBox(tree, (DefaultMutableTreeNode) node.getParent());
                if (box == null) continue;
                Shape old = g.getClip();
                g.clip(new RoundRectangle2D.Float(box.x, box.y, box.width, box.height, arc, arc));
                int y = b.y + padTop(node);
                int h = JBUI.scale(LINE_H);
                g.setColor(selected ? FlowTheme.SELECTION : FlowTheme.ROW_HOVER);
                g.fillRect(box.x, y, box.width, h);
                if (selected) {
                    g.setColor(FlowTheme.SELECTION_BAR);
                    g.fillRect(box.x, y, JBUI.scale(3), h);
                }
                g.setClip(old);
            } else {
                int x = JBUI.scale(2);
                int w = tree.getWidth() - JBUI.scale(6);
                RoundRectangle2D shape = new RoundRectangle2D.Float(x, b.y + 1, w, b.height - 2, arc, arc);
                g.setColor(selected ? FlowTheme.SELECTION : FlowTheme.ROW_HOVER);
                g.fill(shape);
                if (selected) {
                    Shape old = g.getClip();
                    g.clip(shape);
                    g.setColor(FlowTheme.SELECTION_BAR);
                    g.fillRect(x, b.y + 1, JBUI.scale(3), b.height - 2);
                    g.setClip(old);
                }
            }
        }
    }

    private static void paintEmptyText(JTree tree, Graphics2D g, String text) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(JBUI.Fonts.label());
        FontMetrics fm = g.getFontMetrics();
        g.setColor(FlowTheme.FG_MUTED);
        int x = Math.max(JBUI.scale(8), (tree.getWidth() - fm.stringWidth(text)) / 2);
        g.drawString(text, x, JBUI.scale(40));
    }

    // ================= moving nodes =================

    private static boolean matches(@Nullable Object want, DefaultMutableTreeNode node) {
        return want != null && want.equals(node.getUserObject());
    }

    /** Moves a node up (-1) or down (+1) among its siblings. */
    private static void moveNode(FlowStore store, AtomicReference<Object> reselect,
                                 DefaultMutableTreeNode root, DefaultMutableTreeNode node, int delta) {
        DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
        if (parent == null) return;
        int from = parent.getIndex(node);
        int to = from + delta;
        if (to < 0 || to >= parent.getChildCount()) return; // already first / last
        parent.remove(node);
        parent.insert(node, to);
        commit(store, reselect, root, node);
    }

    /**
     * The tree structure is now in the new order: save it as the new flat order. A manual move means the
     * user wants their own order, so Sync Files is turned off (it would otherwise undo the move).
     */
    private static void commit(FlowStore store, AtomicReference<Object> reselect,
                               DefaultMutableTreeNode root, DefaultMutableTreeNode moved) {
        reselect.set(moved.getUserObject());
        store.setManualOrder(mergeOrder(store.getEntries(), flatten(root)));
    }

    /**
     * The full order with the shown lines replaced by their new order. While a filter hides some lines,
     * those keep their places and the shown lines are reordered among the remaining positions.
     */
    private static List<FlowEntry> mergeOrder(List<FlowEntry> all, List<FlowEntry> shownInNewOrder) {
        if (shownInNewOrder.size() == all.size()) return shownInNewOrder;
        Set<FlowEntry> shown = new HashSet<>(shownInNewOrder);
        Iterator<FlowEntry> next = shownInNewOrder.iterator();
        List<FlowEntry> out = new ArrayList<>(all.size());
        for (FlowEntry e : all) out.add(shown.contains(e) && next.hasNext() ? next.next() : e);
        return out;
    }

    /** The lines that match the search box text, in stored order. */
    private static List<FlowEntry> visible(FlowStore store, String filter) {
        if (filter.isEmpty()) return store.getEntries();
        String q = filter.toLowerCase(Locale.ROOT);
        return store.getEntries().stream().filter(e -> matchesFilter(e, q)).toList();
    }

    private static boolean matchesFilter(FlowEntry e, String q) {
        FlowTag tag = FlowTag.of(e);
        return has(e.text, q) || has(e.fileName, q) || has(e.className, q) || has(e.methodName, q)
                || has(e.note, q) || has(e.projectName, q) || String.valueOf(e.line).equals(q)
                || (tag != null && (has(tag.id, q) || has(tag.label, q)));
    }

    private static boolean has(String s, String q) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(q);
    }

    private static List<FlowEntry> flatten(DefaultMutableTreeNode root) {
        List<FlowEntry> out = new ArrayList<>();
        Enumeration<?> nodes = root.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) nodes.nextElement();
            if (n.getUserObject() instanceof FlowEntry e) out.add(e);
        }
        return out;
    }

    // ================= drag & drop (own implementation) =================

    private record DropTarget(int insertAt, int lineY) {}

    /** The (non-root) node whose row contains the point's y, or null. */
    private static @Nullable DefaultMutableTreeNode nodeAtY(JTree tree, Point p) {
        int row = tree.getClosestRowForLocation(p.x, p.y);
        if (row < 0) return null;
        Rectangle b = tree.getRowBounds(row);
        if (b == null || p.y < b.y || p.y >= b.y + b.height) return null;
        TreePath path = tree.getPathForRow(row);
        if (path == null) return null;
        if (path.getLastPathComponent() instanceof DefaultMutableTreeNode n && n.getParent() != null) return n;
        return null;
    }

    /**
     * Drop position among the dragged node's siblings for a mouse at height y: insert before the first
     * sibling whose upper half contains y, otherwise at the end. lineY is where the indicator is drawn.
     */
    private static DropTarget computeDrop(JTree tree, DefaultMutableTreeNode dragged, int y) {
        DefaultMutableTreeNode parent = (DefaultMutableTreeNode) dragged.getParent();
        int count = parent.getChildCount();
        int insertAt = count;
        int lineY = -1;
        for (int i = 0; i < count; i++) {
            Rectangle span = spanOf(tree, (DefaultMutableTreeNode) parent.getChildAt(i));
            if (span == null) continue;
            if (y < span.y + span.height / 2) return new DropTarget(i, span.y);
            lineY = span.y + span.height;
            insertAt = i + 1;
        }
        return new DropTarget(insertAt, lineY);
    }

    /** Vertical extent of a node's row plus all its visible descendants. */
    private static @Nullable Rectangle spanOf(JTree tree, DefaultMutableTreeNode node) {
        Rectangle top = tree.getPathBounds(new TreePath(node.getPath()));
        if (top == null) return null;
        DefaultMutableTreeNode last = node;
        while (last.getChildCount() > 0 && tree.isExpanded(new TreePath(last.getPath()))) {
            last = (DefaultMutableTreeNode) last.getLastChild();
        }
        Rectangle bottom = tree.getPathBounds(new TreePath(last.getPath()));
        if (bottom == null) bottom = top;
        return new Rectangle(0, top.y, 1, bottom.y + bottom.height - top.y);
    }

    // ================= hover action bar =================

    /** Buttons of the bar shown on the hovered row. */
    private enum RowAction {
        UP("Move up", FlowTheme.ICON_UP),
        DOWN("Move down", FlowTheme.ICON_DOWN),
        COPY("Copy", FlowTheme.ICON_COPY),
        NOTE("Add or edit note", FlowTheme.ICON_NOTE),
        DELETE("Delete", FlowTheme.ICON_DELETE);

        final String label;
        final Icon icon;

        RowAction(String label, Icon icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    /**
     * Geometry of the bar: [up down] | [copy note] | [delete], drawn as one small rounded pill. Up / down only
     * appear when the row has siblings to swap with, Note only on code lines. When the row's content fits in
     * the visible area the bar sits directly after it; when the content runs past the visible right edge, the
     * bar sticks to that edge (over a fade) so it can be clicked without scrolling.
     */
    private static final class Bar {
        static int button() { return JBUI.scale(22); }
        static int pad() { return JBUI.scale(2); }
        static int gap() { return JBUI.scale(2); }
        static int dividerSpace() { return JBUI.scale(9); }
        static int height() { return pad() * 2 + button(); }

        static int width(int buttons, int dividers) {
            return pad() * 2 + buttons * button() + Math.max(0, buttons - 1) * gap()
                    + dividers * (dividerSpace() - gap());
        }

        /** Space kept free after every row so the widest bar fits there when scrolled all the way right. */
        static int reservedWidth() { return width(RowAction.values().length, 2) + JBUI.scale(16); }
    }

    private record BarButton(RowAction action, Rectangle bounds, boolean enabled) {}

    /** The bar for one row; pinned = stuck to the visible right edge over the content; dividerXs = divider lines. */
    private record HoverBar(DefaultMutableTreeNode node, Rectangle pill, boolean pinned,
                            List<BarButton> buttons, List<Integer> dividerXs) {}

    private static @Nullable HoverBar hoverBar(JTree tree, int row) {
        if (row < 0 || row >= tree.getRowCount()) return null;
        Rectangle b = tree.getRowBounds(row);
        TreePath path = tree.getPathForRow(row);
        if (b == null || path == null) return null;
        if (!(path.getLastPathComponent() instanceof DefaultMutableTreeNode node) || node.getParent() == null) {
            return null;
        }

        // Which buttons this row needs.
        DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
        int index = parent.getIndex(node);
        boolean movable = parent.getChildCount() > 1;
        List<RowAction> actions = new ArrayList<>();
        if (movable) {
            actions.add(RowAction.UP);
            actions.add(RowAction.DOWN);
        }
        actions.add(RowAction.COPY);
        if (isEntry(node)) actions.add(RowAction.NOTE);
        actions.add(RowAction.DELETE);
        int width = Bar.width(actions.size(), movable ? 2 : 1);

        // Same content width the renderer measures for this row.
        RowRenderer r = (RowRenderer) tree.getCellRenderer().getTreeCellRendererComponent(
                tree, node, tree.isRowSelected(row), tree.isExpanded(row), node.isLeaf(), row, false);
        int contentEnd = b.x + r.contentWidth() + JBUI.scale(8);
        int top = b.y + padTop(node);
        int contentH = b.height - padTop(node) - padBottom(node);
        int y = top + (contentH - Bar.height()) / 2;

        Rectangle visible = tree.getVisibleRect();
        int rightLimit = visible.x + visible.width - JBUI.scale(8);
        boolean pinned = contentEnd + width > rightLimit;
        int x = pinned ? rightLimit - width : contentEnd;

        List<BarButton> buttons = new ArrayList<>();
        int bx = x + Bar.pad();
        List<Integer> dividerXs = new ArrayList<>();
        for (RowAction a : actions) {
            if ((a == RowAction.COPY && movable) || a == RowAction.DELETE) {
                dividerXs.add(bx - Bar.gap() + Bar.dividerSpace() / 2);
                bx += Bar.dividerSpace() - Bar.gap();
            }
            boolean enabled = switch (a) {
                case UP -> index > 0;
                case DOWN -> index < parent.getChildCount() - 1;
                default -> true;
            };
            buttons.add(new BarButton(a, new Rectangle(bx, y + Bar.pad(), Bar.button(), Bar.button()), enabled));
            bx += Bar.button() + Bar.gap();
        }
        return new HoverBar(node, new Rectangle(x, y, width, Bar.height()), pinned, buttons, dividerXs);
    }

    private record BarHit(DefaultMutableTreeNode node, RowAction action) {}

    /** The row whose vertical extent contains the point, or -1. */
    private static int rowAt(JTree tree, Point p) {
        int row = tree.getClosestRowForLocation(p.x, p.y);
        if (row < 0) return -1;
        Rectangle b = tree.getRowBounds(row);
        if (b == null || p.y < b.y || p.y >= b.y + b.height) return -1;
        return row;
    }

    /** Which enabled bar button (if any) is under the mouse. The bar only exists on the hovered row. */
    private static @Nullable BarHit barHit(JTree tree, Point p, int[] hoverRow) {
        int row = rowAt(tree, p);
        if (row < 0 || row != hoverRow[0]) return null;
        HoverBar bar = hoverBar(tree, row);
        if (bar == null) return null;
        for (BarButton btn : bar.buttons) {
            if (btn.enabled && btn.bounds.contains(p)) return new BarHit(bar.node, btn.action);
        }
        return null;
    }

    /** True when the point is anywhere on the hovered row's bar (clicks there never start a drag). */
    private static boolean onBar(JTree tree, Point p, int[] hoverRow) {
        HoverBar bar = rowAt(tree, p) == hoverRow[0] ? hoverBar(tree, hoverRow[0]) : null;
        return bar != null && bar.pill.contains(p);
    }

    /** Draws the bar for the hovered row on top of everything else. */
    private static void paintBar(JTree tree, Graphics2D g, int hoverRow, int hoverAction) {
        HoverBar bar = hoverBar(tree, hoverRow);
        if (bar == null) return;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Rectangle p = bar.pill;
        int arc = JBUI.scale(8);

        if (bar.pinned) {
            // Fade the code out under the bar so it reads as floating above the row.
            Rectangle b = tree.getRowBounds(hoverRow);
            int top = b.y + padTop(bar.node);
            int h = b.height - padTop(bar.node) - padBottom(bar.node);
            Color base = isEntry(bar.node) ? FlowTheme.CODE_BG : FlowTheme.CARD_BG;
            if (tree.isRowSelected(hoverRow)) base = FlowTheme.SELECTION;
            int fade = JBUI.scale(28);
            Color clear = new Color(base.getRed(), base.getGreen(), base.getBlue(), 0);
            g.setPaint(new GradientPaint(p.x - fade, 0, clear, p.x - JBUI.scale(4), 0, base));
            g.fillRect(p.x - fade, top, fade, h);
            g.setColor(base);
            g.fillRect(p.x - JBUI.scale(4), top, p.width + JBUI.scale(4), h);
            // Soft shadow under the floating bar.
            g.setColor(FlowTheme.ARROWS_SHADOW);
            g.fillRoundRect(p.x - 1, p.y + 1, p.width + 2, p.height + 2, arc + 2, arc + 2);
        }

        g.setColor(FlowTheme.ARROWS_BG);
        g.fillRoundRect(p.x, p.y, p.width, p.height, arc, arc);
        g.setColor(FlowTheme.ARROWS_BORDER);
        g.draw(new RoundRectangle2D.Float(p.x + 0.5f, p.y + 0.5f, p.width - 1f, p.height - 1f, arc, arc));
        int inset = JBUI.scale(6);
        for (int dividerX : bar.dividerXs) {
            g.fillRect(dividerX, p.y + inset, JBUI.scale(1), p.height - inset * 2);
        }

        for (BarButton btn : bar.buttons) {
            paintBarButton(tree, g, btn, btn.action.ordinal() == hoverAction);
        }
    }

    private static void paintBarButton(JTree tree, Graphics2D g, BarButton btn, boolean hovered) {
        Rectangle r = btn.bounds;
        boolean active = btn.enabled && hovered;
        if (active) {
            g.setColor(btn.action == RowAction.DELETE ? FlowTheme.DANGER : FlowTheme.ARROW_HOVER);
            g.fillRoundRect(r.x, r.y, r.width, r.height, JBUI.scale(6), JBUI.scale(6));
        }
        Color fg = !btn.enabled ? FlowTheme.ARROW_FG_DISABLED : active ? FlowTheme.ARROW_FG_HOVER : FlowTheme.ARROW_FG;
        Icon i = FlowTheme.tinted(btn.action.icon, fg);
        i.paintIcon(tree, g, r.x + (r.width - i.getIconWidth()) / 2, r.y + (r.height - i.getIconHeight()) / 2);
    }

    /** Every saved line under a node (the node itself if it is a line), in stored order. */
    private static List<FlowEntry> entriesOf(DefaultMutableTreeNode node, FlowStore store) {
        Set<FlowEntry> picked = new LinkedHashSet<>();
        collect(node, picked);
        return store.getEntries().stream().filter(picked::contains).toList();
    }

    /**
     * Paints file / method / line rows. Line rows show their number in the code block's gutter and the
     * code with syntax colors. The hover action bar is painted by the tree on top (see paintBar).
     */
    private static final class RowRenderer extends JComponent implements TreeCellRenderer {
        boolean flat;
        /** Flat View names the project on each line only when lines come from more than one project. */
        boolean showProjectInFlat;
        /** Saved lines that no longer match the code (see FlowCheck), refreshed by the tool window. */
        final Map<FlowEntry, FlowCheck.Problem> problems = new IdentityHashMap<>();
        /** Lines ("file url#line") with values saved in Debug Data. */
        final Set<String> valued = new HashSet<>();

        private Object value;
        private int padTop;
        private int padBottom;
        private int step;
        private Font labelFont;
        private Font fileFont;
        private Font codeFont;
        private Font tagFont;

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            this.value = node.getUserObject();
            labelFont = JBUI.Fonts.label();
            fileFont = labelFont.deriveFont(Font.BOLD, labelFont.getSize2D() + 1f);
            codeFont = FlowTheme.codeFont();
            tagFont = labelFont.deriveFont(labelFont.getSize2D() - 1f);

            DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
            step = (parent == null ? 0 : parent.getIndex(node)) + 1;
            padTop = padTop(node);
            padBottom = padBottom(node);
            return this;
        }

        private int contentHeight() {
            if (value instanceof FileNode) return JBUI.scale(FILE_H);
            if (value instanceof MethodNode) return JBUI.scale(METHOD_H);
            return JBUI.scale(LINE_H);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(contentWidth() + Bar.reservedWidth(), padTop + contentHeight() + padBottom);
        }

        int contentWidth() {
            if (value instanceof FileNode f) {
                return iconSpace() + getFontMetrics(fileFont).stringWidth(f.name()) + tagSpace(f.project());
            }
            if (value instanceof MethodNode m) {
                return iconSpace() + getFontMetrics(labelFont).stringWidth(m.label());
            }
            if (value instanceof FlowEntry e) {
                FontMetrics code = getFontMetrics(codeFont);
                int w = codeStart(code) + code.stringWidth(e.text.strip());
                if (flat) w += getFontMetrics(labelFont.deriveFont(Font.BOLD)).stringWidth(flatPrefix(e));
                if (flat && showProjectInFlat) w += tagSpace(e.projectName) + JBUI.scale(10);
                FlowTag tag = FlowTag.of(e);
                if (tag != null) w += tagSpace(tag.label);
                FlowCheck.Problem problem = problems.get(e);
                if (problem != null) {
                    w += JBUI.scale(10) + FlowTheme.ICON_WARNING.getIconWidth() + JBUI.scale(4)
                            + getFontMetrics(tagFont.deriveFont(Font.ITALIC)).stringWidth(problemText(problem));
                }
                for (String c : connections(e)) w += tagSpace(c);
                if (hasNote(e)) w += getFontMetrics(labelFont.deriveFont(Font.ITALIC)).stringWidth(noteText(e));
                return w;
            }
            return 0;
        }

        private static int iconSpace() {
            return AllIcons.Nodes.Method.getIconWidth() + JBUI.scale(8);
        }

        private static boolean hasText(String s) {
            return s != null && !s.isBlank();
        }

        /** Gap before the tag plus the tag itself (0 when there is no project name). */
        private int tagSpace(String project) {
            if (!hasText(project)) return 0;
            return JBUI.scale(10) + getFontMetrics(tagFont).stringWidth(project) + JBUI.scale(16);
        }

        /** Small rounded label with the project name; returns the x after it. */
        private int paintTag(Graphics2D g, String project, int x, int y0, int h0) {
            return paintPill(g, project, FlowTheme.TAG_BG, FlowTheme.TAG_FG, x, y0, h0);
        }

        /** Small rounded label (project name or colour tag); returns the x after it. */
        private int paintPill(Graphics2D g, String text, Color bg, Color fg, int x, int y0, int h0) {
            if (!hasText(text)) return x;
            x += JBUI.scale(10);
            g.setFont(tagFont);
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(text) + JBUI.scale(16);
            int h = fm.getHeight() + JBUI.scale(4);
            int y = y0 + (h0 - h) / 2;
            g.setColor(bg);
            g.fillRoundRect(x, y, w, h, JBUI.scale(8), JBUI.scale(8));
            g.setColor(fg);
            g.drawString(text, x + JBUI.scale(8), baseline(fm, y0, h0));
            return x + w;
        }

        /** Warning icon and a short reason after a line that no longer matches the code. */
        private int paintProblem(Graphics2D g, FlowCheck.Problem problem, int x, int y0, int h0) {
            x += JBUI.scale(10);
            Icon icon = FlowTheme.tinted(FlowTheme.ICON_WARNING, FlowTheme.WARNING);
            icon.paintIcon(this, g, x, y0 + (h0 - icon.getIconHeight()) / 2);
            x += icon.getIconWidth() + JBUI.scale(4);
            g.setFont(tagFont.deriveFont(Font.ITALIC));
            g.setColor(FlowTheme.WARNING);
            String text = problemText(problem);
            g.drawString(text, x, baseline(g.getFontMetrics(), y0, h0));
            return x + g.getFontMetrics().stringWidth(text);
        }

        private static String problemText(FlowCheck.Problem p) {
            return switch (p.kind()) {
                case MISSING_FILE -> "file missing";
                case MOVED -> "moved to line " + p.movedTo();
                case CHANGED -> "code changed";
            };
        }

        private static int codeStart(FontMetrics code) {
            return gutterWidth(code) + JBUI.scale(12);
        }

        private static String flatPrefix(FlowEntry e) {
            return FlowFormat.flatLabel(e) + "   ";
        }

        /** "→ search()" for each method this line is connected to by hand. */
        private static List<String> connections(FlowEntry e) {
            if (e.calls.isEmpty()) return List.of();
            List<String> out = new ArrayList<>();
            for (String key : e.calls) out.add("\u2192 " + key.substring(key.lastIndexOf('#') + 1));
            return out;
        }

        private static boolean hasNote(FlowEntry e) {
            return e.note != null && !e.note.isBlank();
        }

        private static String noteText(FlowEntry e) {
            return "   ← " + e.note;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int y0 = padTop;
                int h0 = contentHeight();

                if (value instanceof FileNode f) {
                    paintIconAndText(g2, fileIcon(f.name()), f.name(), fileFont, FlowTheme.FG, y0, h0);
                    paintTag(g2, f.project(), iconSpace() + getFontMetrics(fileFont).stringWidth(f.name()), y0, h0);
                } else if (value instanceof MethodNode m) {
                    paintIconAndText(g2, AllIcons.Nodes.Method, m.label(), labelFont, FlowTheme.FG, y0, h0);
                } else if (value instanceof FlowEntry e) {
                    paintEntry(g2, e, y0, h0);
                }
            } finally {
                g2.dispose();
            }
        }

        /** The IDE's icon for the file type (Java, Kotlin, ...). */
        private static Icon fileIcon(String fileName) {
            FileType type = FileTypeManager.getInstance().getFileTypeByFileName(fileName);
            Icon icon = type.getIcon();
            return icon != null ? icon : AllIcons.FileTypes.Java;
        }

        private void paintIconAndText(Graphics2D g, Icon icon, String text, Font font, java.awt.Color color,
                                      int y0, int h0) {
            icon.paintIcon(this, g, 0, y0 + (h0 - icon.getIconHeight()) / 2);
            g.setFont(font);
            g.setColor(color);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, iconSpace(), baseline(fm, y0, h0));
        }

        private void paintEntry(Graphics2D g, FlowEntry e, int y0, int h0) {
            FontMetrics code = getFontMetrics(codeFont);
            int gutter = gutterWidth(code);

            // Line number (grouped) or step number (flat), right-aligned in the gutter.
            String number = flat ? String.valueOf(step) : FlowFormat.lineNumber(e);
            FlowCheck.Problem problem = problems.get(e);
            FlowTag tag = FlowTag.of(e);
            if (tag != null) { // a bar in the tag's color at the row's start: visible without scrolling
                g.setColor(tag.fg);
                g.fillRoundRect(0, y0 + JBUI.scale(2), JBUI.scale(3), h0 - JBUI.scale(4), JBUI.scale(3), JBUI.scale(3));
            }
            g.setFont(codeFont);
            g.setColor(problem != null ? FlowTheme.WARNING : FlowTheme.LINE_NO);
            g.drawString(number, gutter - JBUI.scale(10) - code.stringWidth(number), baseline(code, y0, h0));
            if (valued.contains(e.fileUrl + "#" + e.line)) { // values saved here in Debug Data
                int d = JBUI.scale(6);
                g.setColor(FlowTheme.ACCENT);
                g.fillOval(gutter - JBUI.scale(10) - code.stringWidth(number) - d - JBUI.scale(4), y0 + (h0 - d) / 2, d, d);
            }

            int x = codeStart(code);
            if (flat) {
                Font bold = labelFont.deriveFont(Font.BOLD);
                g.setFont(bold);
                g.setColor(FlowTheme.FG_MUTED);
                String prefix = flatPrefix(e);
                g.drawString(prefix, x, baseline(g.getFontMetrics(), y0, h0));
                x += g.getFontMetrics().stringWidth(prefix);
                if (showProjectInFlat) x = paintTag(g, e.projectName, x - JBUI.scale(10), y0, h0) + JBUI.scale(10);
            }

            // The tag comes first, before the code, so a long line doesn't push it out of sight.
            if (tag != null) x = paintPill(g, tag.label, tag.bg, tag.fg, x - JBUI.scale(10), y0, h0) + JBUI.scale(6);

            g.setFont(codeFont);
            int by = baseline(code, y0, h0);
            for (CodeHighlighter.Span span : CodeHighlighter.highlight(e.text.strip(), FlowLang.kind(e.fileName))) {
                g.setColor(span.color());
                g.drawString(span.text(), x, by);
                x += code.stringWidth(span.text());
            }

            if (problem != null) x = paintProblem(g, problem, x, y0, h0);
            for (String c : connections(e)) x = paintPill(g, c, FlowTheme.TAG_BG, FlowTheme.ACCENT, x, y0, h0);

            if (hasNote(e)) {
                Font italic = labelFont.deriveFont(Font.ITALIC);
                g.setFont(italic);
                g.setColor(FlowTheme.FG_MUTED);
                g.drawString(noteText(e), x, baseline(g.getFontMetrics(), y0, h0));
            }
        }

        private static int baseline(FontMetrics fm, int y0, int h0) {
            return y0 + (h0 - fm.getHeight()) / 2 + fm.getAscent();
        }
    }

    // ================= selection / clipboard =================

    /** Selected line entries; selecting a file or method node selects every line under it. */
    /**
     * Connect: the calls that lead from one line to the other (A → B → C → D → E) are found in the code, and the
     * lines that make them are put between the two, as one step Undo takes back. Tries the other direction too.
     */
    static void connectLines(Project project, FlowStore store, FlowEntry first, FlowEntry second) {
        if (com.intellij.openapi.project.DumbService.isDumb(project)) {
            notify(project, "The IDE is still indexing: connect the lines when it is done.", NotificationType.INFORMATION);
            return;
        }
        FlowConnecting.connect(project, store, first, second);
    }

    /** A line's values saved in Debug Data, for its tooltip (null when it has none). */
    private static @Nullable String savedValuesTip(Project project, FlowEntry e) {
        List<FlowSnapshot> here = FlowDebugStore.get(project).getSnapshots().stream()
                .filter(s -> s.line == e.line && s.fileUrl.equals(e.fileUrl)).toList();
        if (here.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("<html><b>Saved debug values at this line</b>");
        int shown = 0;
        for (FlowSnapshot s : here) {
            if (shown++ == 5) break;
            String value = s.value == null || s.value.value == null ? "" : s.value.value;
            if (value.length() > 60) value = value.substring(0, 60) + "…";
            sb.append("<br>• <b>").append(StringUtil.escapeXmlEntities(s.name)).append("</b> = ")
                    .append(StringUtil.escapeXmlEntities(value)).append(" <small>· ").append(FlowDebugPanel.time(s.savedAt))
                    .append(s.flowName.isEmpty() ? "" : " · " + StringUtil.escapeXmlEntities(s.flowName)).append("</small>");
        }
        if (here.size() > 5) sb.append("<br>… and ").append(here.size() - 5).append(" more");
        return sb.append("<br><small>Right-click \u2192 Show Saved Values</small></html>").toString();
    }

    private static List<FlowEntry> selectedEntries(JTree tree, FlowStore store) {
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null) return Collections.emptyList();

        Set<FlowEntry> picked = new LinkedHashSet<>();
        for (TreePath p : paths) {
            collect((DefaultMutableTreeNode) p.getLastPathComponent(), picked);
        }
        // Keep the order in which the lines are stored.
        return store.getEntries().stream().filter(picked::contains).toList();
    }

    private static void collect(DefaultMutableTreeNode node, Set<FlowEntry> out) {
        if (node.getUserObject() instanceof FlowEntry e) out.add(e);
        Enumeration<?> children = node.children();
        while (children.hasMoreElements()) {
            collect((DefaultMutableTreeNode) children.nextElement(), out);
        }
    }

    private static String onOff(boolean on) {
        return on ? "<b>on</b>" : "off";
    }

    private static void navigate(Project project, FlowEntry entry) {
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(entry.fileUrl);
        if (vf != null) new OpenFileDescriptor(project, vf, Math.max(0, entry.line - 1), 0).navigate(true);
    }

    /** What a file / method / line row is called in menu labels. */
    private static String kindOf(Object userObject) {
        if (userObject instanceof FileNode) return "File";
        if (userObject instanceof MethodNode) return "Method";
        return "Line";
    }

    /** Context menu for the selected row(s), shown with the IDE's own menu style. */
    private static void showContextMenu(Project project, FlowStore store, JTree tree, DefaultMutableTreeNode root,
                                        AtomicReference<Object> reselect, Runnable editNote,
                                        Map<FlowEntry, FlowCheck.Problem> problems, MouseEvent e) {
        TreePath[] sel = tree.getSelectionPaths();
        if (sel == null || sel.length == 0) return;
        DefaultMutableTreeNode single = sel.length == 1 ? (DefaultMutableTreeNode) sel[0].getLastPathComponent() : null;
        DefaultMutableTreeNode parent = single == null ? null : (DefaultMutableTreeNode) single.getParent();
        int index = parent == null ? -1 : parent.getIndex(single);
        String what = single == null ? "Selection" : kindOf(single.getUserObject());
        FlowEntry line = single != null && single.getUserObject() instanceof FlowEntry fe ? fe : null;

        DefaultActionGroup group = new DefaultActionGroup();
        group.add(menuAction("Move " + what + " Up", AllIcons.Actions.MoveUp, index > 0,
                () -> moveNode(store, reselect, root, single, -1)));
        group.add(menuAction("Move " + what + " Down", AllIcons.Actions.MoveDown,
                parent != null && index < parent.getChildCount() - 1,
                () -> moveNode(store, reselect, root, single, +1)));
        group.addSeparator();
        group.add(menuAction("Copy " + what, AllIcons.Actions.Copy, true,
                () -> copyToClipboard(textFor(store, selectedEntries(tree, store)))));
        List<FlowEntry> picked = selectedEntries(tree, store);
        if (picked.size() == 2) {
            group.add(menuAction("Connect the Flow Between These Lines", FlowTheme.ICON_CONNECT, true,
                    () -> connectLines(project, store, picked.get(0), picked.get(1))));
        }
        if (line != null) {
            group.add(menuAction(line.note == null || line.note.isBlank() ? "Add Note\u2026" : "Edit Note\u2026",
                    AllIcons.Actions.Edit, true, editNote));
            group.add(menuAction("Jump to Source", AllIcons.Actions.EditSource, true, () -> navigate(project, line)));
            group.add(menuAction("Walk from Here", FlowTheme.ICON_NEXT, true, () -> FlowWalk.show(project, line)));
            long values = FlowDebugStore.get(project).getSnapshots().stream()
                    .filter(s -> s.line == line.line && s.fileUrl.equals(line.fileUrl)).count();
            if (values > 0) {
                group.add(menuAction("Show Saved Values (" + values + ")", FlowTheme.ICON_CAPTURE, true,
                        () -> FlowDebugPanel.showLineOf(project, line.fileUrl, line.line)));
            }
            group.add(connectMenu(store, line, reselect));
        }

        // Colour tag for every selected line.
        DefaultActionGroup tags = DefaultActionGroup.createPopupGroup(() -> "Tag");
        tags.getTemplatePresentation().setIcon(FlowTheme.ICON_TAG);
        for (FlowTag t : FlowTag.values()) {
            tags.add(menuAction(t.emoji + "  " + t.label, null, true,
                    () -> store.setTag(selectedEntries(tree, store), t)));
        }
        tags.addSeparator();
        tags.add(menuAction("No Tag", null, true, () -> store.setTag(selectedEntries(tree, store), null)));
        group.add(tags);

        // Lines that no longer match the code.
        FlowCheck.Problem problem = line == null ? null : problems.get(line);
        if (problem != null && problem.kind() != FlowCheck.Kind.MISSING_FILE) {
            String label = problem.kind() == FlowCheck.Kind.MOVED
                    ? "Re-link to Code (line " + problem.movedTo() + ")"
                    : "Re-link to Code (use the current code of line " + line.line + ")";
            group.add(menuAction(label, FlowTheme.ICON_WARNING, true, () -> {
                reselect.set(line);
                if (FlowCheck.relink(line, problem)) store.notifyChanged();
            }));
        }
        List<FlowEntry> moved = problems.entrySet().stream()
                .filter(p -> p.getValue().kind() == FlowCheck.Kind.MOVED).map(Map.Entry::getKey).toList();
        if (moved.size() > 1 || (moved.size() == 1 && moved.get(0) != line)) {
            group.add(menuAction("Re-link All Moved Lines (" + moved.size() + ")", FlowTheme.ICON_WARNING, true, () -> {
                for (FlowEntry m : moved) FlowCheck.relink(m, problems.get(m));
                store.notifyChanged();
            }));
        }
        group.addSeparator();
        group.add(menuAction("Delete " + what, AllIcons.Actions.GC, true,
                () -> store.remove(selectedEntries(tree, store))));

        ActionPopupMenu menu = ActionManager.getInstance().createActionPopupMenu("FlowTrackerPopup", group);
        menu.getComponent().show(tree, e.getX(), e.getY());
    }

    /**
     * The flow picker's menu: the flows (active one checked, drag to reorder), then New / Rename / Delete
     * and Export / Import.
     */
    private static void showFlowMenu(Project project, FlowStore store, JComponent anchor) {
        List<String> names = store.getFlowNames();
        List<FlowMenuPopup.Item> items = new ArrayList<>();
        items.add(new FlowMenuPopup.Item("New Flow\u2026", AllIcons.General.Add, true, () -> {
            String name = askFlowName(project, "New Flow", "Flow " + (names.size() + 1) + ": ");
            if (name != null) store.newFlow(name, List.of());
        }));
        items.add(new FlowMenuPopup.Item("Rename Flow\u2026", AllIcons.Actions.Edit, true, () -> {
            String current = store.getActiveFlowName();
            // A default name ("Flow 2") gets the "Flow 2: " prefix to type after; a named flow is shown as is.
            String start = current.matches("Flow \\d+") ? current + ": " : current;
            String name = askFlowName(project, "Rename Flow", start);
            if (name != null) store.renameFlow(name);
        }));
        // deleted in the Flow tab and Debug Data together (empty: at once; else one confirmation for both)
        items.add(new FlowMenuPopup.Item("Delete Flow", AllIcons.Actions.GC, true,
                () -> FlowDeleting.delete(project, List.of(store.getActiveFlowName()), FlowDeleting.From.FLOW_TAB)));
        items.add(new FlowMenuPopup.Item("Delete Flows\u2026", FlowTheme.ICON_DELETE, true, () -> {
            List<String> picked = FlowDeleteFlows.ask(project, store.getFlowNames(),
                    f -> FlowDeleting.details(project, f, FlowDeleting.From.FLOW_TAB), "They are deleted in Debug Data too.");
            if (picked != null && !picked.isEmpty()) FlowDeleting.delete(project, picked, FlowDeleting.From.FLOW_TAB);
        }));
        items.add(null);
        items.add(new FlowMenuPopup.Item("Export Flow\u2026", FlowTheme.ICON_EXPORT, !store.getEntries().isEmpty(),
                () -> exportFlow(project, store)));
        items.add(new FlowMenuPopup.Item("Import Flow\u2026", FlowTheme.ICON_IMPORT, true, () -> importFlow(project, store)));
        FlowMenuPopup.show(store, anchor, items);
    }

    /**
     * Asks for a flow name, starting from e.g. "Flow 3: " with the caret at the end (nothing selected), so
     * the name is typed after the prefix; the prefix can be deleted too. Returns null when cancelled or empty.
     */
    private static @Nullable String askFlowName(Project project, String title, String start) {
        String input = Messages.showInputDialog(project,
                "<html>Flow name, for example <b>Flow 1: Checkout bug</b>.<br>"
                        + "Type the name after the prefix, or delete the prefix if you don't want it.</html>",
                title, null, start, null, new TextRange(start.length(), start.length()));
        return normalizeFlowName(input);
    }

    /** Trims the name; a prefix left without a name ("Flow 3:") becomes "Flow 3"; blank means no name. */
    public static @Nullable String normalizeFlowName(@Nullable String input) {
        if (input == null) return null;
        String name = input.strip();
        if (name.endsWith(":")) name = name.substring(0, name.length() - 1).strip();
        return name.isEmpty() ? null : name;
    }

    private static @Nullable VirtualFile projectDir(Project project) {
        return project.getBasePath() == null ? null : LocalFileSystem.getInstance().findFileByPath(project.getBasePath());
    }

    /** Saves the active flow as a .flow.json file (paths relative to the project, so teammates can open it). */
    private static void exportFlow(Project project, FlowStore store) {
        FileSaverDescriptor descriptor = FlowCompat.saverDescriptor("Export Flow",
                "Save this flow as a file you can commit or send to a teammate", "json");
        VirtualFileWrapper target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
                .save(projectDir(project), com.example.flowtracker.model.FlowProjects.prefixed(
                        com.example.flowtracker.model.FlowProjects.exportPrefix(project,
                                store.getEntries().stream().map(e -> e.projectName).toList()),
                        FlowTransfer.fileNameFor(store.getActiveFlowName())));
        if (target == null) return;
        try {
            FlowTransfer.write(project, store.getActiveFlowName(), store.getEntries(), store.getDiagramLooks(),
                    target.getFile().toPath());
            // Let the IDE see the new file: an asynchronous refresh (a synchronous one on the EDT is a slow, write-access operation).
            LocalFileSystem.getInstance().refreshNioFiles(java.util.List.of(target.getFile().toPath()), true, false, null);
            notify(project, "Exported \u201c" + store.getActiveFlowName() + "\u201d to " + target.getFile().getName(),
                    NotificationType.INFORMATION);
        } catch (IOException ex) {
            Messages.showErrorDialog(project, "Couldn't write the file: " + ex.getMessage(), "Export Flow");
        }
    }

    /** Reads a .flow.json file into a new flow and shows it. */
    private static void importFlow(Project project, FlowStore store) {
        VirtualFile file = FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFileDescriptor("json")
                        .withTitle("Import Flow").withDescription("Choose a .flow.json file exported from TraceWeave"),
                project, projectDir(project));
        if (file == null) return;
        try {
            FlowTransfer.Imported flow = FlowTransfer.read(project, Path.of(file.getPath()));
            store.newFlow(flow.name(), flow.entries(), flow.looks());
            String text = "Imported \u201c" + store.getActiveFlowName() + "\u201d (" + flow.entries().size() + " lines)";
            if (flow.missing() > 0) {
                notify(project, text + ". " + flow.missing() + " of them point to files that aren't in this project; "
                        + "they're marked \u201cfile missing\u201d.", NotificationType.WARNING);
            } else {
                notify(project, text, NotificationType.INFORMATION);
            }
        } catch (IOException ex) {
            Messages.showErrorDialog(project, ex.getMessage(), "Import Flow");
        }
    }

    private static void notify(Project project, String text, NotificationType type) {
        FlowCopy.notify(project, text, type);
    }

    /**
     * "Connect Call To": connect this line by hand to a method it calls, for calls the code doesn't show
     * (e.g. an API request handled in another project). Lists the flow's other methods, connected ones
     * checked; picking one again removes the connection.
     */
    private static AnAction connectMenu(FlowStore store, FlowEntry line, AtomicReference<Object> reselect) {
        DefaultActionGroup menu = DefaultActionGroup.createPopupGroup(() -> "Connect Call To");
        menu.getTemplatePresentation().setIcon(FlowTheme.ICON_NEXT);
        String own = FlowSync.methodKey(line);
        Set<String> listed = new LinkedHashSet<>();
        for (FlowEntry e : store.getEntries()) {
            String key = FlowSync.methodKey(e);
            if (key.equals(own) || !listed.add(key)) continue;
            String label = FlowFormat.methodLabel(e) + "  \u2014  " + e.fileName
                    + (e.projectName == null || e.projectName.isBlank() ? "" : "  (" + e.projectName + ")");
            menu.add(menuAction(label, line.calls.contains(key) ? AllIcons.Actions.Checked : null, true, () -> {
                reselect.set(line);
                store.toggleConnection(line, key);
            }));
        }
        if (listed.isEmpty()) menu.add(menuAction("Add lines of the called method first", null, false, () -> {}));
        if (!line.calls.isEmpty()) {
            menu.addSeparator();
            menu.add(menuAction("Remove Connections from This Line", AllIcons.Actions.GC, true, () -> {
                reselect.set(line);
                store.clearConnections(line);
            }));
        }
        return menu;
    }

    private static AnAction menuAction(String text, @Nullable Icon icon, boolean enabled, Runnable run) {
        return new DumbAwareAction(text, null, icon) {
            @Override public void actionPerformed(@NotNull AnActionEvent e) { run.run(); }
            @Override public void update(@NotNull AnActionEvent e) { e.getPresentation().setEnabled(enabled); }
            @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
        };
    }

    /** Text for copying the given lines in the current view and settings. */
    private static String textFor(FlowStore store, List<FlowEntry> picked) {
        return FlowCopy.textFor(store, picked);
    }

    private static void copyToClipboard(String text) {
        FlowCopy.toClipboard(text);
    }

    /**
     * Panel that tells the IDE which Copy / Delete handlers to use when the tree has focus. DataProvider works in every
     * supported IDE (2023.1 and later); newer IDEs also have UiDataProvider, but still support this one.
     */
    private static final class RootPanel extends JPanel implements DataProvider {
        private final CopyProvider copyProvider;
        private final DeleteProvider deleteProvider;

        RootPanel(CopyProvider copyProvider, DeleteProvider deleteProvider) {
            super(new BorderLayout());
            this.copyProvider = copyProvider;
            this.deleteProvider = deleteProvider;
        }

        @Override
        public @Nullable Object getData(@NotNull String dataId) {
            if (PlatformDataKeys.COPY_PROVIDER.is(dataId)) return copyProvider;
            if (PlatformDataKeys.DELETE_ELEMENT_PROVIDER.is(dataId)) return deleteProvider;
            return null;
        }
    }
}
