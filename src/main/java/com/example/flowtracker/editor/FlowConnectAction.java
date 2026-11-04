package com.example.flowtracker.editor;

import com.example.flowtracker.code.FlowCapture;
import com.example.flowtracker.code.FlowLang;
import com.example.flowtracker.code.FlowSync;
import com.example.flowtracker.compat.FlowCompat;
import com.example.flowtracker.copy.FlowCopy;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowStore;
import com.example.flowtracker.ui.FlowConnecting;
import com.intellij.icons.AllIcons;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.SimpleListCellRenderer;
import org.jetbrains.annotations.NotNull;

import javax.swing.JList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Connect the Flow to…" in the editor's right-click menu: from the caret line to a method of the flow. The ways
 * between them are read from the code (Choose a Way when there are several) and their lines go into the flow after
 * the caret line (which joins the flow first when it isn't in it).
 */
public final class FlowConnectAction extends DumbAwareAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) return;
        if (DumbService.isDumb(project)) {
            FlowCopy.notify(project, "The IDE is still indexing: connect the flow when it is done.", NotificationType.INFORMATION);
            return;
        }
        Document doc = editor.getDocument();
        int line = doc.getLineNumber(editor.getCaretModel().getOffset());
        FlowEntry here = FlowCompat.read(() -> FlowCapture.lineEntry(project, doc, line));
        if (here == null) return;
        FlowStore store = FlowStore.get(project);
        FlowEntry from = FlowConnecting.stored(store, here);
        if (from == null) from = here;

        // the flow's methods (one line each), not the caret's own
        String own = FlowSync.methodKey(from);
        Map<String, FlowEntry> methods = new LinkedHashMap<>();
        for (FlowEntry x : store.getEntries()) {
            if (x.line <= 0 || own.equals(FlowSync.methodKey(x))) continue;
            methods.putIfAbsent(FlowSync.methodKey(x), x);
        }
        if (methods.isEmpty()) {
            FlowCopy.notify(project, "Add a line of the method the flow should reach first (+ in the gutter), then connect to it.",
                    NotificationType.INFORMATION);
            return;
        }
        FlowEntry start = from;
        List<FlowEntry> targets = new ArrayList<>(methods.values());
        JBPopupFactory.getInstance().createPopupChooserBuilder(targets)
                .setTitle("Connect " + where(start) + " to…")
                .setRenderer(new SimpleListCellRenderer<FlowEntry>() {
                    @Override public void customize(@NotNull JList<? extends FlowEntry> list, FlowEntry v, int i, boolean sel, boolean focus) {
                        setText(where(v) + "   " + v.fileName + ":" + v.line);
                        setIcon(AllIcons.Nodes.Method);
                    }
                })
                .setNamerForFiltering(FlowConnectAction::where)
                .setItemChosenCallback(to -> FlowConnecting.connect(project, store, start, to))
                .createPopup()
                .showInBestPositionFor(editor);
    }

    /** "Travel.getDestination()" */
    private static String where(FlowEntry e) {
        String cls = e.className == null || e.className.isBlank() || "-".equals(e.className) ? e.fileName : e.className;
        return cls + (e.methodName == null || e.methodName.isBlank() ? "" : "." + e.methodName);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile vf = editor == null ? null : FileDocumentManager.getInstance().getFile(editor.getDocument());
        // the code is read as Java or Kotlin
        e.getPresentation().setEnabledAndVisible(e.getProject() != null && vf != null
                && (FlowLang.usesJavaPsi(vf.getName()) || FlowLang.isKotlin(vf.getName())));
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
