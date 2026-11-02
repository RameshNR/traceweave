package com.example.flowtracker.diagram;

import com.example.flowtracker.code.FlowCodeSearch;
import com.example.flowtracker.ui.FlowButton;
import com.example.flowtracker.ui.FlowTheme;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.FoldingModel;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;
import java.util.TreeSet;

/**
 * The code panel under the Flow Diagram: the selected method's lines in the flow (the rest of its file folded
 * away), or with View Full Code the whole file at that method. A read-only editor with the IDE's colors; the
 * flow's lines are highlighted either way.
 */
final class FlowDiagramCode extends JPanel implements Disposable {
    private static final JBColor FLOW_LINE = new JBColor(0xE3EDFF, 0x25324D);

    private final Project project;
    private final JBLabel title = new JBLabel();
    private final JBLabel info = new JBLabel();
    private final FlowButton full = new FlowButton("View Full Code", FlowTheme.ICON_CODE, FlowButton.Style.OUTLINE);
    private final JPanel body = new JPanel(new BorderLayout());
    private final JBLabel placeholder = new JBLabel("", SwingConstants.CENTER);
    private @Nullable EditorEx viewer;
    private @Nullable String viewerUrl;
    private boolean showFull;
    /** What is shown now (to skip repeated updates). */
    private String shown = "";

    private String url = "";
    private String fileName = "";
    private List<Integer> lines = List.of();
    /** The line to bring into view (0-based; -1: the method's first line). */
    private int focus = -1;

    /** Adds a line of the shown file to the flow (0-based); set by the diagram. */
    private java.util.function.BiConsumer<String, Integer> lineAdder = (u, l) -> {};
    /** Takes a flow line out of its method (0-based); returns why it can't, or null when it was taken out. */
    private java.util.function.BiFunction<String, Integer, String> lineRemover = (u, l) -> null;
    /** Why the last change in the code view wasn't made: shown under the code. */
    private final JBLabel warning = new JBLabel();
    /** The flow's lines of a file (0-based), to highlight a file reached by Ctrl+click. */
    private java.util.function.Function<String, List<Integer>> flowLines = u -> List.of();
    /** Where Ctrl+click came from: Back returns there. */
    private record Place(String heading, String fileName, String url, List<Integer> lines, boolean full, int focus) {}
    private final java.util.ArrayDeque<Place> back = new java.util.ArrayDeque<>();
    private final FlowButton backButton = new FlowButton("Back", com.intellij.icons.AllIcons.Actions.Back, FlowButton.Style.OUTLINE);
    /** The "+" shown on the line number under the mouse. */
    private @Nullable com.intellij.openapi.editor.markup.RangeHighlighter plus;

    void setLineAdder(java.util.function.BiConsumer<String, Integer> adder) {
        lineAdder = adder;
    }

    void setLineRemover(java.util.function.BiFunction<String, Integer, String> remover) {
        lineRemover = remover;
    }

    /** A warning under the code (null hides it). */
    void warn(@Nullable String text) {
        warning.setText(text == null ? "" : "<html>" + com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(text) + "</html>");
        warning.setVisible(text != null);
        revalidate();
        repaint();
    }

    void setFlowLines(java.util.function.Function<String, List<Integer>> lines) {
        flowLines = lines;
    }

    FlowDiagramCode(Project project) {
        super(new BorderLayout(0, JBUI.scale(4)));
        this.project = project;
        title.setFont(JBUI.Fonts.label().asBold());
        info.setForeground(FlowTheme.FG_MUTED);
        placeholder.setForeground(FlowTheme.FG_MUTED);
        full.addActionListener(e -> {
            showFull = !showFull;
            shown = "";
            render();
        });
        JPanel names = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        names.setOpaque(false);
        names.add(title);
        names.add(info);
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(names, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0));
        right.setOpaque(false);
        backButton.setToolTipText("Back to where Ctrl+click came from");
        backButton.setVisible(false);
        backButton.addActionListener(e -> goBack());
        right.add(backButton);
        right.add(full);
        header.add(right, BorderLayout.EAST);
        info.setToolTipText("Hover a line number: + adds that line to the flow, − takes a flow line out; double-click a "
                + "line to open it in the editor; Ctrl+click a name to go to it here");
        body.setBorder(JBUI.Borders.customLine(FlowTheme.CARD_BORDER));
        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        warning.setIcon(com.intellij.icons.AllIcons.General.Warning);
        warning.setForeground(new JBColor(0x9A5B00, 0xE5B567));
        warning.setBorder(JBUI.Borders.empty(4, 2, 0, 2));
        warning.setVisible(false);
        add(warning, BorderLayout.SOUTH);
        setBorder(JBUI.Borders.emptyTop(8));
        showMessage("Code", "Click a method in the diagram to see its lines of code here.");
    }

    /** A message instead of code (no method selected, a custom method, a missing file). */
    void showMessage(String heading, String message) {
        String key = "message\u0000" + heading + "\u0000" + message;
        if (key.equals(shown)) return;
        shown = key;
        title.setText(heading);
        info.setText("");
        full.setEnabled(false);
        full.setSelected(false);
        placeholder.setText(message);
        body.removeAll();
        body.add(placeholder, BorderLayout.CENTER);
        body.revalidate();
        body.repaint();
    }

    /** A method's code: its file and its lines in the flow (0-based). */
    void showMethod(String heading, String fileName, String url, List<Integer> lines) {
        String key = "method\u0000" + heading + "\u0000" + url + "\u0000" + lines + "\u0000" + showFull;
        if (key.equals(shown)) return;
        // after Ctrl+click the panel shows where it went; the same method selected again doesn't take it back
        if (!back.isEmpty() && heading.equals(back.getLast().heading())) return;
        if (!shown.startsWith("method\u0000" + heading + "\u0000")) {
            back.clear(); // another method: Ctrl+click history starts again
            focus = -1;
            warn(null);
        }
        backButton.setVisible(!back.isEmpty());
        title.setText(heading);
        info.setText(fileName + " · " + lines.size() + (lines.size() == 1 ? " line" : " lines") + " in the flow");
        this.url = url;
        this.fileName = fileName;
        this.lines = lines;
        render();
        shown = key;
    }

    /**
     * Shows the code. Reading the file, its colors and the method's place happen in read actions (newer IDEs check
     * that strictly, also on the UI thread); the code view itself is made outside of one (newer IDEs don't allow
     * making an editor inside a read action).
     */
    private void render() {
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(url);
        Document doc = vf == null ? null
                : com.example.flowtracker.compat.FlowCompat.read(() -> FileDocumentManager.getInstance().getDocument(vf));
        if (doc == null) {
            showMessage(title.getText(), fileName + " was not found in the project.");
            return;
        }
        if (lines.isEmpty() && !showFull) {
            showMessage(title.getText(), "This method has no lines in the flow yet.");
            return;
        }
        full.setEnabled(true);
        full.setSelected(showFull);
        full.setText(showFull ? "Flow Lines Only" : "View Full Code");
        full.setToolTipText(showFull ? "Show only the flow's lines of this method"
                : "Show the whole file, at this method (the flow's lines stay highlighted)");
        if (viewer == null || !url.equals(viewerUrl)) {
            release();
            viewer = (EditorEx) EditorFactory.getInstance().createViewer(doc, project);
            com.intellij.openapi.editor.highlighter.EditorHighlighter colors = com.example.flowtracker.compat.FlowCompat.read(
                    () -> EditorHighlighterFactory.getInstance().createEditorHighlighter(project, vf));
            viewer.setHighlighter(colors);
            EditorSettings s = viewer.getSettings();
            s.setLineNumbersShown(true);
            s.setFoldingOutlineShown(false);
            s.setLineMarkerAreaShown(true);
            s.setGutterIconsShown(true);
            s.setIndentGuidesShown(false);
            s.setAdditionalLinesCount(0);
            s.setAdditionalColumnsCount(1);
            s.setCaretRowShown(false);
            s.setRightMarginShown(false);
            viewer.setBorder(JBUI.Borders.empty());
            viewerUrl = url;
            listen(viewer);
        }
        EditorEx v = viewer;
        TreeSet<Integer> flow = new TreeSet<>();
        for (int l : lines) if (l >= 0 && l < doc.getLineCount()) flow.add(l);

        v.getMarkupModel().removeAllHighlighters();
        plus = null;
        TextAttributes mark = new TextAttributes();
        mark.setBackgroundColor(FLOW_LINE);
        for (int l : flow) v.getMarkupModel().addLineHighlighter(l, HighlighterLayer.SELECTION - 1, mark);

        // Flow lines only: everything else is folded away (click a "⋯" to open it).
        FoldingModel folds = v.getFoldingModel();
        folds.runBatchFoldingOperation(() -> {
            for (FoldRegion r : folds.getAllFoldRegions()) folds.removeFoldRegion(r);
            if (showFull) return;
            int next = 0;
            for (int l : flow) {
                if (l > next) fold(folds, doc, next, l - 1);
                next = l + 1;
            }
            if (next < doc.getLineCount()) fold(folds, doc, next, doc.getLineCount() - 1);
        });

        int target = flow.isEmpty() ? 0 : flow.first();
        if (showFull) {
            int start = target;
            int[] range = com.example.flowtracker.compat.FlowCompat.read(() -> FlowCodeSearch.methodRange(project, url, start));
            if (range != null) target = range[0];
        }
        if (focus >= 0 && focus < doc.getLineCount()) target = focus;
        body.removeAll();
        body.add(v.getComponent(), BorderLayout.CENTER);
        body.revalidate();
        body.repaint();
        int line = target;
        v.getCaretModel().moveToLogicalPosition(new LogicalPosition(line, 0));
        scrollTo(v, line);
    }

    /**
     * Puts the line near the top of the code view (two lines of room above it). Done again once the view has its size:
     * scrolling before that left the method at the bottom edge.
     */
    private void scrollTo(EditorEx v, int line) {
        Runnable go = () -> {
            if (viewer != v) return;
            v.getScrollingModel().disableAnimation();
            int y = v.logicalPositionToXY(new LogicalPosition(line, 0)).y;
            v.getScrollingModel().scrollVertically(Math.max(0, y - 2 * v.getLineHeight()));
            v.getScrollingModel().enableAnimation();
        };
        SwingUtilities.invokeLater(go);
        javax.swing.JComponent c = v.getScrollingModel().getVisibleArea().height > 0 ? null : v.getContentComponent();
        if (c != null) {
            v.getComponent().addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override public void componentResized(java.awt.event.ComponentEvent e) {
                    if (e.getComponent().getHeight() <= 0) return;
                    e.getComponent().removeComponentListener(this);
                    SwingUtilities.invokeLater(go);
                }
            });
        }
    }

    // ---------- clicks in the code ----------

    private void listen(EditorEx v) {
        v.addEditorMouseMotionListener(new com.intellij.openapi.editor.event.EditorMouseMotionListener() {
            @Override public void mouseMoved(@NotNull com.intellij.openapi.editor.event.EditorMouseEvent e) {
                boolean gutter = e.getArea() == com.intellij.openapi.editor.event.EditorMouseEventArea.LINE_NUMBERS_AREA
                        || e.getArea() == com.intellij.openapi.editor.event.EditorMouseEventArea.LINE_MARKERS_AREA;
                showPlus(v, gutter ? e.getLogicalPosition().line : -1);
            }
        });
        v.addEditorMouseListener(new com.intellij.openapi.editor.event.EditorMouseListener() {
            @Override public void mouseExited(@NotNull com.intellij.openapi.editor.event.EditorMouseEvent e) {
                showPlus(v, -1);
            }

            @Override public void mouseClicked(@NotNull com.intellij.openapi.editor.event.EditorMouseEvent e) {
                java.awt.event.MouseEvent me = e.getMouseEvent();
                if (e.getArea() != com.intellij.openapi.editor.event.EditorMouseEventArea.EDITING_AREA) return;
                if (me.getClickCount() == 1 && (me.isControlDown() || me.isMetaDown())) {
                    me.consume();
                    goToDeclaration(v, e.getOffset());
                } else if (me.getClickCount() == 2) {
                    me.consume();
                    openInEditor(v.getDocument().getLineNumber(e.getOffset()));
                }
            }
        });
    }

    /** A "+" on the line number under the mouse, or a "−" on a flow line (to take it out of the flow). */
    private void showPlus(EditorEx v, int line) {
        if (plus != null) {
            if (plus.isValid() && line >= 0 && v.getDocument().getLineNumber(plus.getStartOffset()) == line) return;
            v.getMarkupModel().removeHighlighter(plus);
            plus = null;
        }
        if (line < 0 || line >= v.getDocument().getLineCount()) return;
        String at = url;
        boolean inFlow = flowLines.apply(at).contains(line);
        plus = v.getMarkupModel().addLineHighlighter(line, HighlighterLayer.LAST, null);
        plus.setGutterIconRenderer(new com.intellij.openapi.editor.markup.GutterIconRenderer() {
            @Override public @NotNull javax.swing.Icon getIcon() {
                return inFlow ? com.intellij.icons.AllIcons.General.Remove : com.intellij.icons.AllIcons.General.Add;
            }
            @Override public String getTooltipText() {
                return inFlow ? "Take line " + (line + 1) + " out of the flow" : "Add line " + (line + 1) + " to the flow";
            }
            @Override public boolean isNavigateAction() { return true; }
            @Override public com.intellij.openapi.actionSystem.AnAction getClickAction() {
                return new com.intellij.openapi.project.DumbAwareAction() {
                    @Override public void actionPerformed(@NotNull com.intellij.openapi.actionSystem.AnActionEvent e) {
                        if (!inFlow) {
                            warn(null);
                            lineAdder.accept(at, line);
                            return;
                        }
                        warn(lineRemover.apply(at, line));
                    }
                };
            }
            @Override public boolean equals(Object o) { return o == this; }
            @Override public int hashCode() { return System.identityHashCode(this); }
        });
    }

    /** Double-click: the line in IntelliJ's editor, as a double-click in the Flow tab does. */
    private void openInEditor(int line) {
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(url);
        if (vf != null) new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, Math.max(0, line), 0).navigate(true);
    }

    /** Ctrl+click: what the name under the mouse refers to (a method, field, class), shown here; Back returns. */
    private void goToDeclaration(EditorEx v, int offset) {
        Object[] found = com.example.flowtracker.compat.FlowCompat.read(() -> {
            com.intellij.psi.PsiFile psi = com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(v.getDocument());
            if (psi == null) return null;
            com.intellij.psi.PsiReference ref = psi.findReferenceAt(offset);
            com.intellij.psi.PsiElement target = ref == null ? null : ref.resolve();
            if (target == null) return null;
            com.intellij.psi.PsiElement nav = target.getNavigationElement(); // sources rather than a .class when there are any
            com.intellij.psi.PsiFile file = nav.getContainingFile();
            VirtualFile vf = file == null ? null : file.getVirtualFile();
            if (vf == null) return null;
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc == null) return null;
            int at = nav.getTextOffset();
            String name = target instanceof com.intellij.psi.PsiNamedElement n && n.getName() != null ? n.getName() : vf.getName();
            return new Object[]{vf.getUrl(), vf.getName(), doc.getLineNumber(Math.max(0, at)), name};
        });
        if (found == null) {
            info.setText("Nothing to go to there (Ctrl+click a method, field or class name).");
            return;
        }
        back.push(new Place(title.getText(), fileName, url, lines, showFull, v.getCaretModel().getLogicalPosition().line));
        String target = (String) found[0];
        title.setText((String) found[3]);
        fileName = (String) found[1];
        url = target;
        lines = flowLines.apply(target);
        focus = (Integer) found[2];
        showFull = true; // a declaration is shown in its file
        info.setText(fileName + " · line " + (focus + 1) + " (Ctrl+click) · Back returns");
        backButton.setVisible(true);
        shown = "";
        render();
    }

    private void goBack() {
        Place p = back.poll();
        if (p == null) return;
        title.setText(p.heading());
        fileName = p.fileName();
        url = p.url();
        lines = p.lines();
        showFull = p.full();
        focus = p.focus();
        info.setText(fileName + " · " + lines.size() + (lines.size() == 1 ? " line" : " lines") + " in the flow");
        backButton.setVisible(!back.isEmpty());
        shown = "";
        render();
    }

    private static void fold(FoldingModel folds, Document doc, int first, int last) {
        int n = last - first + 1;
        FoldRegion r = folds.addFoldRegion(doc.getLineStartOffset(first), doc.getLineEndOffset(last),
                "⋯ " + n + (n == 1 ? " line" : " lines"));
        if (r != null) r.setExpanded(false);
    }

    private void release() {
        if (viewer != null) {
            EditorFactory.getInstance().releaseEditor(viewer);
            viewer = null;
            viewerUrl = null;
        }
    }

    @Override
    public void dispose() {
        release();
    }
}
