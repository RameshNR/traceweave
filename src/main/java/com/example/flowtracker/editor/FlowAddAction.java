package com.example.flowtracker.editor;

import com.example.flowtracker.code.FlowCapture;
import com.example.flowtracker.code.FlowLang;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowStore;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.TreeSet;

/**
 * "Add to TraceWeave" in the editor's right-click menu (and a shortcut): adds the caret line, or every
 * line of the selection (all carets), as one undoable step. On a saved line with no selection it becomes
 * "Remove from TraceWeave".
 */
public final class FlowAddAction extends DumbAwareAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) return;
        Document doc = editor.getDocument();
        FlowStore store = FlowStore.get(project);

        FlowEntry saved = savedCaretLine(store, editor);
        if (saved != null) {
            store.remove(List.of(saved));
            return;
        }
        FlowCapture.addLines(project, doc, List.copyOf(lines(editor)));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile vf = editor == null ? null : FileDocumentManager.getInstance().getFile(editor.getDocument());
        boolean on = project != null && FlowLang.isSupported(vf);
        e.getPresentation().setEnabledAndVisible(on);
        if (!on) return;
        if (savedCaretLine(FlowStore.get(project), editor) != null) {
            e.getPresentation().setText("Remove from TraceWeave");
        } else {
            int n = lines(editor).size();
            e.getPresentation().setText(n > 1 ? "Add " + n + " Lines to TraceWeave" : "Add to TraceWeave");
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    /** 0-based lines covered by the carets / selections. A selection ending at the start of a line excludes it. */
    private static TreeSet<Integer> lines(Editor editor) {
        Document doc = editor.getDocument();
        TreeSet<Integer> out = new TreeSet<>();
        for (Caret caret : editor.getCaretModel().getAllCarets()) {
            if (!caret.hasSelection()) {
                out.add(doc.getLineNumber(caret.getOffset()));
                continue;
            }
            int first = doc.getLineNumber(caret.getSelectionStart());
            int last = doc.getLineNumber(caret.getSelectionEnd());
            if (last > first && caret.getSelectionEnd() == doc.getLineStartOffset(last)) last--;
            for (int l = first; l <= last; l++) out.add(l);
        }
        return out;
    }

    /** The saved line under a single caret without a selection, or null. */
    private static @Nullable FlowEntry savedCaretLine(FlowStore store, Editor editor) {
        if (editor.getCaretModel().getCaretCount() != 1 || editor.getSelectionModel().hasSelection()) return null;
        VirtualFile vf = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (vf == null) return null;
        int line = editor.getCaretModel().getLogicalPosition().line + 1;
        for (FlowEntry e : store.getEntries()) {
            if (e.line == line && vf.getUrl().equals(e.fileUrl)) return e;
        }
        return null;
    }
}
