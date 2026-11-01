package com.example.flowtracker.editor;

import com.example.flowtracker.code.FlowCapture;
import com.example.flowtracker.code.FlowLang;
import com.example.flowtracker.copy.FlowFormat;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowStore;
import com.example.flowtracker.model.FlowTag;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorMouseEvent;
import com.intellij.openapi.editor.event.EditorMouseListener;
import com.intellij.openapi.editor.event.EditorMouseMotionListener;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Per-editor gutter logic (Java and Kotlin files only):
 *  - hovering a line that is not added yet shows a "+" icon;
 *  - lines that are already added show a green badge with their step number (click removes);
 *  - stored line numbers follow edits made in the editor (in both views).
 */
final class LineHoverHandler implements EditorMouseMotionListener, EditorMouseListener {
    private final Editor editor;
    private final Project project;
    private final FlowStore store;
    private final Runnable storeListener;

    private @Nullable RangeHighlighter hoverHighlighter;
    /** Green markers and the entry each one belongs to. The markers move with the text. */
    private final Map<RangeHighlighter, FlowEntry> addedMarkers = new LinkedHashMap<>();
    private boolean disposed;
    private boolean syncQueued;

    LineHoverHandler(Editor editor, Disposable parent) {
        this.editor = editor;
        this.project = editor.getProject();
        this.store = FlowStore.get(project);
        this.storeListener = () -> ApplicationManager.getApplication().invokeLater(this::onStoreChanged);
        store.addListener(storeListener);

        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                scheduleLineSync();
            }
        }, parent);

        refreshAddedMarkers();
    }

    // ---------- store changes ----------

    private void onStoreChanged() {
        if (disposed || editor.isDisposed()) return;
        // Markers have been following the text, so copy their lines into the entries first.
        syncLinesFromMarkers();
        hideHover();
        refreshAddedMarkers();
    }

    private void refreshAddedMarkers() {
        removeAddedMarkers();
        if (editor.isDisposed() || !isSupportedFile()) return;

        String url = fileUrl();
        if (url == null) return;
        Document doc = editor.getDocument();

        // Step numbers follow the order the panel shows (Grouped or Flat View).
        List<FlowEntry> order = FlowWalk.order(store);
        for (FlowEntry e : store.getEntries()) {
            if (!url.equals(e.fileUrl)) continue;
            int line = e.line - 1;
            if (line < 0 || line >= doc.getLineCount()) continue;
            addedMarkers.put(addMarker(line, new AddedRenderer(e, order.indexOf(e) + 1, order.size())), e);
        }
    }

    private void removeAddedMarkers() {
        if (!editor.isDisposed()) {
            for (RangeHighlighter h : addedMarkers.keySet()) {
                if (h.isValid()) editor.getMarkupModel().removeHighlighter(h);
            }
        }
        addedMarkers.clear();
    }

    // ---------- keep stored line numbers in sync with edits ----------

    private void scheduleLineSync() {
        if (disposed || syncQueued) return;
        syncQueued = true;
        ApplicationManager.getApplication().invokeLater(() -> {
            syncQueued = false;
            if (disposed || editor.isDisposed()) return;
            syncLinesFromMarkers();
        });
    }

    private void syncLinesFromMarkers() {
        Document doc = editor.getDocument();
        boolean changed = false;
        for (Map.Entry<RangeHighlighter, FlowEntry> en : addedMarkers.entrySet()) {
            RangeHighlighter h = en.getKey();
            if (!h.isValid()) continue; // the whole line was deleted: keep the last known line number
            int line = doc.getLineNumber(h.getStartOffset()) + 1;
            FlowEntry e = en.getValue();
            if (e.line != line) {
                e.line = line;
                changed = true;
            }
        }
        if (changed) store.notifyChanged();
    }

    // ---------- mouse ----------

    @Override
    public void mouseMoved(@NotNull EditorMouseEvent e) {
        if (e.getEditor() != editor) return;
        if (!isSupportedFile()) { hideHover(); return; }

        Point p = e.getMouseEvent().getPoint();
        LogicalPosition pos = editor.xyToLogicalPosition(new Point(0, p.y));
        Document doc = editor.getDocument();
        int line = pos.line;

        if (line < 0 || line >= doc.getLineCount()) { hideHover(); return; }

        // Already added: the green marker is shown, so no hover "+".
        String url = fileUrl();
        if (url != null && store.contains(url, line + 1)) { hideHover(); return; }

        // Already showing the icon for this line? Do nothing (avoids flicker).
        RangeHighlighter h = hoverHighlighter;
        if (h != null && h.isValid() && doc.getLineNumber(h.getStartOffset()) == line) return;

        hideHover();
        int start = doc.getLineStartOffset(line);
        int end = doc.getLineEndOffset(line);
        if (doc.getText().substring(start, end).isBlank()) return; // no "+" on empty lines

        RangeHighlighter[] ref = new RangeHighlighter[1];
        PlusRenderer renderer = new PlusRenderer(() -> doc.getLineNumber(ref[0].getStartOffset()));
        ref[0] = addMarker(line, renderer);
        hoverHighlighter = ref[0];
    }

    @Override
    public void mouseExited(@NotNull EditorMouseEvent e) {
        if (e.getEditor() != editor) return;
        // Moving from the text area into the gutter also fires "exited" - only hide if truly outside.
        try {
            Component c = editor.getComponent();
            if (!c.isShowing()) { hideHover(); return; }
            Rectangle bounds = new Rectangle(c.getLocationOnScreen(), c.getSize());
            if (!bounds.contains(e.getMouseEvent().getLocationOnScreen())) hideHover();
        } catch (Exception ex) {
            hideHover();
        }
    }

    // ---------- helpers ----------

    void dispose() {
        disposed = true;
        store.removeListener(storeListener);
        hideHover();
        removeAddedMarkers();
    }

    private RangeHighlighter addMarker(int line, GutterIconRenderer renderer) {
        Document doc = editor.getDocument();
        RangeHighlighter h = editor.getMarkupModel().addRangeHighlighter(
                null, doc.getLineStartOffset(line), doc.getLineEndOffset(line),
                HighlighterLayer.LAST, HighlighterTargetArea.LINES_IN_RANGE);
        h.setGutterIconRenderer(renderer);
        return h;
    }

    private void hideHover() {
        RangeHighlighter h = hoverHighlighter;
        hoverHighlighter = null;
        if (h != null && h.isValid() && !editor.isDisposed()) editor.getMarkupModel().removeHighlighter(h);
    }

    private @Nullable VirtualFile file() {
        return FileDocumentManager.getInstance().getFile(editor.getDocument());
    }

    private @Nullable String fileUrl() {
        VirtualFile vf = file();
        return vf == null ? null : vf.getUrl();
    }

    private boolean isSupportedFile() {
        return FlowLang.isSupported(file());
    }

    // ---------- gutter renderers ----------

    /** Hover "+" : click adds the line. */
    private final class PlusRenderer extends GutterIconRenderer {
        private final IntSupplier lineSupplier;

        PlusRenderer(IntSupplier lineSupplier) {
            this.lineSupplier = lineSupplier;
        }

        @Override public @NotNull Icon getIcon() { return AllIcons.General.Add; }
        @Override public @NotNull Alignment getAlignment() { return Alignment.LEFT; }
        @Override public String getTooltipText() { return "Add this line to TraceWeave"; }
        @Override public boolean isNavigateAction() { return true; }

        @Override
        public @Nullable AnAction getClickAction() {
            return new AnAction() {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    FlowCapture.addLine(project, editor.getDocument(), lineSupplier.getAsInt());
                }
            };
        }

        @Override public boolean equals(Object o) { return this == o; }
        @Override public int hashCode() { return System.identityHashCode(this); }
    }

    /** Green badge with the step number: the line is already in TraceWeave. Click removes it. */
    private final class AddedRenderer extends GutterIconRenderer {
        private final FlowEntry entry;
        private final int step;
        private final int steps;
        private final Icon icon;

        AddedRenderer(FlowEntry entry, int step, int steps) {
            this.entry = entry;
            this.step = step;
            this.steps = steps;
            this.icon = step > 0 ? new AddedIcon(step) : AddedIcon.INSTANCE;
        }

        @Override public @NotNull Icon getIcon() { return icon; }
        @Override public @NotNull Alignment getAlignment() { return Alignment.LEFT; }

        @Override
        public String getTooltipText() {
            StringBuilder html = new StringBuilder("<html><b>TraceWeave");
            if (step > 0) html.append(": step ").append(step).append(" of ").append(steps);
            html.append("</b><br>").append(StringUtil.escapeXmlEntities(FlowFormat.flatLabel(entry)));
            FlowTag tag = FlowTag.of(entry);
            if (tag != null) html.append(" &nbsp;").append(tag.emoji).append(' ').append(tag.label);
            if (entry.note != null && !entry.note.isBlank()) {
                html.append("<br><i>").append(StringUtil.escapeXmlEntities(entry.note)).append("</i>");
            }
            return html.append("<br><small>Click to remove</small></html>").toString();
        }
        @Override public boolean isNavigateAction() { return true; }

        @Override
        public @Nullable AnAction getClickAction() {
            return new AnAction() {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    store.remove(List.of(entry));
                }
            };
        }

        @Override public boolean equals(Object o) { return this == o; }
        @Override public int hashCode() { return System.identityHashCode(this); }
    }
}
