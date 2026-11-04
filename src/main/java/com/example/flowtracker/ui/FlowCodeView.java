package com.example.flowtracker.ui;

import com.example.flowtracker.model.FlowFolds;
import com.intellij.find.EditorSearchSession;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.undo.UndoUtil;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.FoldingModel;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

/**
 * A value as JSON or text in an IntelliJ editor (Debug Data): the code's colors, line numbers, every { … } / [ … ]
 * block (or indented block) foldable with the gutter's markers, Expand All / Collapse All, and IntelliJ's find bar
 * (Ctrl+F). It can be made editable; edits by the user (not the text set here) are told.
 */
public final class FlowCodeView implements Disposable {
    private final @Nullable Project project;
    private final Document document = EditorFactory.getInstance().createDocument("");
    private final EditorEx editor;
    private boolean json = true;
    private boolean setting;
    private @Nullable Runnable onUserEdit;

    public FlowCodeView(@Nullable Project project, Disposable parent) {
        this.project = project;
        UndoUtil.disableUndoFor(document); // what is set here isn't an edit to undo
        editor = (EditorEx) EditorFactory.getInstance().createEditor(document, project);
        EditorSettings s = editor.getSettings();
        s.setLineNumbersShown(true);
        s.setFoldingOutlineShown(true);
        s.setLineMarkerAreaShown(false);
        s.setIndentGuidesShown(true);
        s.setAdditionalLinesCount(1);
        s.setAdditionalColumnsCount(2);
        s.setRightMarginShown(false);
        s.setCaretRowShown(false);
        s.setUseSoftWraps(false);
        editor.setViewer(true);
        highlight();
        document.addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(@NotNull DocumentEvent e) {
                if (!setting && onUserEdit != null) onUserEdit.run();
            }
        }, this);
        Disposer.register(parent, this);
    }

    public JComponent getComponent() {
        return editor.getComponent();
    }

    /** The part that has the focus while typing (for its keys). */
    public JComponent getContentComponent() {
        return editor.getContentComponent();
    }

    /** Shows a text: JSON (colored as JSON, its { } and [ ] blocks foldable) or plain indented text. */
    public void setText(String text, boolean asJson) {
        if (asJson != json) {
            json = asJson;
            highlight();
        }
        boolean viewer = editor.isViewer();
        setting = true;
        try {
            document.setReadOnly(false);
            ApplicationManager.getApplication().runWriteAction(() -> document.setText(text.replace("\r\n", "\n")));
        } finally {
            setting = false;
            editor.setViewer(viewer);
        }
        folds();
        editor.getCaretModel().moveToOffset(0);
        editor.getScrollingModel().scrollVertically(0);
    }

    public String getText() {
        return document.getText();
    }

    public void setEditable(boolean editable) {
        editor.setViewer(!editable);
    }

    public boolean isEditable() {
        return !editor.isViewer();
    }

    /** Runs when the user changes the text (typing, pasting). */
    public void onUserEdit(Runnable r) {
        onUserEdit = r;
    }

    public void requestFocus() {
        editor.getContentComponent().requestFocusInWindow();
    }

    public boolean hasFocus() {
        return editor.getContentComponent().isFocusOwner();
    }

    /** Every block open. */
    public void expandAll() {
        FoldingModel f = editor.getFoldingModel();
        f.runBatchFoldingOperation(() -> {
            for (FoldRegion r : f.getAllFoldRegions()) r.setExpanded(true);
        });
    }

    /** Every block folded but the outermost: the value's own fields show, each folded. */
    public void collapseAll() {
        FoldingModel f = editor.getFoldingModel();
        f.runBatchFoldingOperation(() -> {
            for (FoldRegion r : f.getAllFoldRegions()) r.setExpanded(depths.getOrDefault(r.getStartOffset(), 0) == 0);
        });
    }

    /** Each block's depth, by where it starts (0: the outermost). */
    private final java.util.Map<Integer, Integer> depths = new java.util.HashMap<>();

    /**
     * Folds (or opens) the blocks of any editor showing a value's JSON or text, e.g. the Compare window's diff viewers:
     * the blocks are made foldable first; Collapse keeps the outermost one open.
     */
    static void fold(com.intellij.openapi.editor.Editor editor, boolean json, boolean collapse) {
        editor.getSettings().setFoldingOutlineShown(true); // the ▸ / ▾ arrows in the gutter
        editor.getSettings().setLineNumbersShown(true);
        FoldingModel f = editor.getFoldingModel();
        String text = editor.getDocument().getText();
        f.runBatchFoldingOperation(() -> {
            // the diff viewer's own folds (unchanged lines) cut across the blocks: the blocks fold instead
            for (FoldRegion r : f.getAllFoldRegions()) {
                String p = r.getPlaceholderText();
                if (!"{…}".equals(p) && !"[…]".equals(p) && !" …".equals(p)) f.removeFoldRegion(r);
            }
            for (FlowFolds.Block b : FlowFolds.of(text, json)) {
                if (b.end() > text.length() || b.start() >= b.end()) continue;
                FoldRegion r = f.getFoldRegion(b.start(), b.end());
                if (r == null) r = f.addFoldRegion(b.start(), b.end(), b.placeholder());
                if (r != null) r.setExpanded(!collapse || b.depth() == 0);
            }
        });
    }

    /** IntelliJ's find bar over the text (Ctrl+F). */
    public void find() {
        requestFocus();
        if (project != null) EditorSearchSession.start(editor, project);
    }

    private void folds() {
        FoldingModel f = editor.getFoldingModel();
        String text = document.getText();
        f.runBatchFoldingOperation(() -> {
            for (FoldRegion r : f.getAllFoldRegions()) f.removeFoldRegion(r);
            depths.clear();
            for (FlowFolds.Block b : FlowFolds.of(text, json)) {
                depths.putIfAbsent(b.start(), b.depth());
                if (b.end() > text.length() || b.start() >= b.end()) continue;
                FoldRegion r = f.addFoldRegion(b.start(), b.end(), b.placeholder());
                if (r != null) r.setExpanded(true);
            }
        });
    }

    private void highlight() {
        FileType type = FileTypeManager.getInstance().getFileTypeByExtension(json ? "json" : "txt");
        editor.setHighlighter(EditorHighlighterFactory.getInstance().createEditorHighlighter(project, type));
    }

    @Override
    public void dispose() {
        if (!editor.isDisposed()) EditorFactory.getInstance().releaseEditor(editor);
    }
}
