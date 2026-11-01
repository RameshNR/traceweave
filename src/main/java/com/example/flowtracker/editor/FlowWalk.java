package com.example.flowtracker.editor;

import com.example.flowtracker.copy.FlowFormat;
import com.example.flowtracker.diagram.FlowDiagram;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowStore;
import com.example.flowtracker.model.FlowTag;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Walk-through: Next / Previous Step jump the editor to each saved line in turn (in the order the panel
 * shows them) and show the step number, the method and the note in a small hint.
 */
public final class FlowWalk {
    private FlowWalk() {}

    /** The lines in the order the current view shows them. */
    static List<FlowEntry> order(FlowStore store) {
        if (store.isFlatView()) return store.getEntries();
        List<FlowEntry> out = new ArrayList<>();
        for (FlowFormat.FileGroup f : FlowFormat.group(store.getEntries())) {
            for (List<FlowEntry> lines : f.methods().values()) out.addAll(lines);
        }
        return out;
    }

    /** Moves the walk one step forward (+1) or back (-1). The first Next starts at step 1. */
    public static void step(Project project, int delta) {
        FlowStore store = FlowStore.get(project);
        List<FlowEntry> order = order(store);
        if (order.isEmpty()) {
            hintInSelectedEditor(project, "TraceWeave: this flow has no lines yet");
            return;
        }
        FlowEntry current = store.getWalkEntry();
        int index = current == null ? (delta > 0 ? 0 : order.size() - 1) : order.indexOf(current) + delta;
        if (index < 0 || index >= order.size()) {
            hintInSelectedEditor(project, index < 0
                    ? "TraceWeave: already at the first step"
                    : "TraceWeave: end of the flow (" + order.size() + " steps)");
            return;
        }
        show(project, order.get(index));
    }

    /** Opens the line in the editor, marks it as the current step, and shows the step hint. */
    public static void show(Project project, FlowEntry entry) {
        FlowStore store = FlowStore.get(project);
        store.setWalkEntry(entry);
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(entry.fileUrl);
        if (vf == null && FlowDiagram.isDiagramOnly(entry)) {
            hintInSelectedEditor(project, "TraceWeave: " + FlowFormat.flatLabel(entry) + " was added in the Flow Diagram and has no code yet");
            return;
        }
        if (vf == null) {
            hintInSelectedEditor(project, "TraceWeave: " + entry.fileName + " was not found");
            return;
        }
        // navigate() is the open call that isn't deprecated in 2026.2 or 2026.3. If the editor opens later
        // (asynchronously), show the hint once it is there.
        new OpenFileDescriptor(project, vf, Math.max(0, entry.line - 1), 0).navigate(true);
        if (!showHint(project, store, entry, vf)) {
            ApplicationManager.getApplication().invokeLater(() -> showHint(project, store, entry, vf), project.getDisposed());
        }
    }

    /** Centers the step's line in the selected editor and shows the step hint; false if vf isn't open yet. */
    private static boolean showHint(Project project, FlowStore store, FlowEntry entry, VirtualFile vf) {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null || !vf.equals(editor.getVirtualFile())) return false;
        editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);

        List<FlowEntry> order = order(store);
        StringBuilder html = new StringBuilder("<html><b>Step ").append(order.indexOf(entry) + 1)
                .append(" / ").append(order.size()).append("</b> &nbsp;·&nbsp; ")
                .append(StringUtil.escapeXmlEntities(FlowFormat.flatLabel(entry)));
        FlowTag tag = FlowTag.of(entry);
        if (tag != null) html.append(" &nbsp;").append(tag.emoji).append(' ').append(tag.label);
        if (entry.note != null && !entry.note.isBlank()) {
            html.append("<br><i>").append(StringUtil.escapeXmlEntities(entry.note)).append("</i>");
        }
        html.append("<br><small>Alt+] next &nbsp;·&nbsp; Alt+[ previous</small></html>");
        HintManager.getInstance().showInformationHint(editor, html.toString());
        return true;
    }

    private static void hintInSelectedEditor(Project project, String text) {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor != null) HintManager.getInstance().showInformationHint(editor, text);
    }

    /** Base for the two actions (registered in plugin.xml with Alt+] / Alt+[). */
    abstract static class StepAction extends DumbAwareAction {
        private final int delta;

        StepAction(int delta) {
            this.delta = delta;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (e.getProject() != null) step(e.getProject(), delta);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(e.getProject() != null);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }

    public static final class Next extends StepAction {
        public Next() { super(+1); }
    }

    public static final class Previous extends StepAction {
        public Previous() { super(-1); }
    }
}
