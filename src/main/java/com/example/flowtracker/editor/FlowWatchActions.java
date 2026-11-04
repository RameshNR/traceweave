package com.example.flowtracker.editor;

import com.example.flowtracker.ui.FlowHuntPanel;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The editor's right-click menu for Stop at Value: watch the field at the caret, or stop where the selected text shows up. */
public final class FlowWatchActions {
    private FlowWatchActions() {
    }

    /**
     * What the caret is on, for Watch: a field ("Class.field"), a class ("Class"), or a local variable or parameter
     * holding an object (its class, with the object's data: the variable itself can't be watched).
     */
    record Target(String text, boolean deep, String label, @Nullable String variable) {
        Target(String text, boolean deep, String label) {
            this(text, deep, label, null);
        }
    }

    /** "Watch “BookingDto.status”", "Watch object “booking” (BookingDto)": in Watch a Field or Object. */
    public static final class WatchField extends AnAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            Target target = targetAt(e);
            e.getPresentation().setEnabledAndVisible(target != null);
            if (target != null) e.getPresentation().setText(target.label() + " (Stop at Value)");
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            Target target = targetAt(e);
            if (project != null && target != null) FlowHuntPanel.addWatch(project, null, target.text(), target.deep());
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }

    /** "Stop at Value “BK-1042”": the selected text (or the string at the caret), in Add Stop at Value. */
    public static final class StopAtText extends AnAction {
        @Override
        public void update(@NotNull AnActionEvent e) {
            String text = textAt(e);
            // a variable's name selected: it is watched as the variable (Watch variable…), not looked for as text
            Target t = text == null ? null : targetAt(e);
            if (t != null && text.equals(t.variable())) text = null;
            e.getPresentation().setEnabledAndVisible(text != null);
            if (text != null) {
                String shown = text.length() > 30 ? text.substring(0, 30) + "…" : text;
                e.getPresentation().setText("Stop at Value “" + shown + "”");
            }
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            String text = textAt(e);
            if (project != null && text != null) FlowHuntPanel.addValueHunt(project, text);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }

    /** What the caret is on (a field, also where it is used; a local variable or parameter; a class's name), or null. */
    static @Nullable Target targetAt(AnActionEvent e) {
        PsiElement at = elementAt(e);
        if (at == null) return null;
        com.intellij.psi.PsiVariable v = PsiTreeUtil.getParentOfType(at, com.intellij.psi.PsiVariable.class, false);
        if (v != null && v.getNameIdentifier() != at) v = null; // inside its initializer: not the variable itself
        if (v == null && at.getParent() instanceof PsiReferenceExpression r && r.resolve() instanceof com.intellij.psi.PsiVariable used) v = used;
        if (v instanceof PsiField f && f.getContainingClass() != null && f.getContainingClass().getName() != null) {
            String text = f.getContainingClass().getName() + "." + f.getName();
            return new Target(text, false, "Watch “" + text + "”");
        }
        if (v instanceof com.intellij.psi.PsiLocalVariable || v instanceof com.intellij.psi.PsiParameter) {
            // the variable itself, in its method: where it is set, changed, set to null, or holds a value
            com.intellij.psi.PsiMethod m = PsiTreeUtil.getParentOfType(v, com.intellij.psi.PsiMethod.class);
            PsiClass k = m == null ? null : m.getContainingClass();
            if (m == null || k == null || k.getName() == null || m.getBody() == null) return null;
            String text = k.getName() + "." + m.getName() + "()." + v.getName();
            return new Target(text, false, "Watch variable “" + v.getName() + "” in " + m.getName() + "()", v.getName());
        }
        // on a class's name: the whole object
        if (at.getParent() instanceof PsiClass k && k.getNameIdentifier() == at && k.getName() != null && !k.isInterface()) {
            return new Target(k.getName(), false, "Watch “" + k.getName() + "”");
        }
        return null;
    }

    /** The selected text (one line, at most 120 characters) or the string literal at the caret; null when none. */
    static @Nullable String textAt(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (e.getProject() == null || editor == null) return null;
        String sel = editor.getSelectionModel().getSelectedText();
        if (sel != null) {
            String t = sel.strip();
            return t.isEmpty() || t.contains("\n") || t.length() > 120 ? null : t;
        }
        PsiElement at = elementAt(e);
        PsiLiteralExpression lit = at == null ? null : PsiTreeUtil.getParentOfType(at, PsiLiteralExpression.class, false);
        return lit != null && lit.getValue() instanceof String s && !s.isBlank() && s.length() <= 120 ? s : null;
    }

    private static @Nullable PsiElement elementAt(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        if (e.getProject() == null || editor == null || file == null) return null;
        // a selection: what it starts on (the caret is at its end, past the name)
        int at = editor.getSelectionModel().hasSelection() ? editor.getSelectionModel().getSelectionStart() : editor.getCaretModel().getOffset();
        return file.findElementAt(at);
    }
}
