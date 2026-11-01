package com.example.flowtracker.debug;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * "Save Value to TraceWeave" in the editor's right-click menu while the debugger is paused: evaluates the selected
 * expression (or the variable at the caret) where the debugger stopped, and saves the result to Debug Data.
 */
public final class FlowSaveExpressionAction extends AnAction implements DumbAware {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        boolean on = project != null && editor != null && FlowDebugCapture.currentFrame(project) != null && expression(editor) != null;
        e.getPresentation().setEnabledAndVisible(on);
        if (on) e.getPresentation().setText("Save “" + shorten(expression(editor)) + "” to TraceWeave");
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        String expression = editor == null ? null : expression(editor);
        if (project != null && expression != null) FlowDebugCapture.evaluateAndSave(project, expression);
    }

    /** The selected text, else the name (with its dots, e.g. request.session) at the caret; null when neither. */
    static @Nullable String expression(Editor editor) {
        String selected = editor.getSelectionModel().getSelectedText();
        if (selected != null && !selected.isBlank()) return selected.strip();
        CharSequence text = editor.getDocument().getCharsSequence();
        int at = Math.min(editor.getCaretModel().getOffset(), text.length());
        int start = at, end = at;
        while (start > 0 && isNamePart(text.charAt(start - 1))) start--;
        while (end < text.length() && isNamePart(text.charAt(end))) end++;
        String name = text.subSequence(start, end).toString();
        while (name.startsWith(".")) name = name.substring(1);
        while (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        return name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0)) ? null : name;
    }

    private static boolean isNamePart(char c) {
        return Character.isJavaIdentifierPart(c) || c == '.';
    }

    private static String shorten(@Nullable String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ");
        return one.length() > 40 ? one.substring(0, 39) + "…" : one;
    }
}
