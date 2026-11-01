package com.example.flowtracker.debug;

import com.example.flowtracker.code.FlowCapture;
import com.example.flowtracker.model.FlowDebugStore;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowSnapshot;
import com.example.flowtracker.model.FlowStore;
import com.example.flowtracker.model.FlowValueDiff;
import com.example.flowtracker.ui.FlowCompareDialog;
import com.example.flowtracker.ui.FlowDebugPanel;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * Saving debugger values to TraceWeave's Debug Data, from wherever they come: the Variables, Watches, Inspect and
 * Evaluate Expression trees, an expression selected in the editor, or one typed in the Debug Data tab.
 */
public final class FlowDebugCapture {
    private FlowDebugCapture() {}

    /** The paused debug session's current stack frame, or null when no session is paused. */
    public static @Nullable XStackFrame currentFrame(Project project) {
        XDebugSession session = XDebuggerManager.getInstance(project).getCurrentSession();
        return session == null || !session.isSuspended() ? null : session.getCurrentStackFrame();
    }

    /**
     * Evaluates an expression in the paused debug session's current frame (as Evaluate Expression does) and saves
     * the result; tells the user when there is no paused session or the expression can't be evaluated.
     */
    public static void evaluateAndSave(Project project, String expression) {
        String text = expression.strip();
        XStackFrame frame = currentFrame(project);
        XDebuggerEvaluator evaluator = frame == null ? null : frame.getEvaluator();
        if (evaluator == null) {
            tell(project, "Pause the debugger first: “" + text + "” is evaluated where it stopped.", NotificationType.WARNING);
            return;
        }
        XDebugSession session = XDebuggerManager.getInstance(project).getCurrentSession();
        XSourcePosition position = session == null ? null : session.getCurrentPosition();
        evaluator.evaluate(text, new XDebuggerEvaluator.XEvaluationCallback() {
            @Override
            public void evaluated(@NotNull XValue result) {
                ApplicationManager.getApplication().invokeLater(() -> save(project, text, result));
            }

            @Override
            public void errorOccurred(@NotNull String errorMessage) {
                tell(project, "Couldn't evaluate “" + text + "”: " + errorMessage, NotificationType.WARNING);
            }
        }, position);
    }

    /**
     * Saves a debugger value with its fields (a few levels deep), where the debugger stopped and in the active
     * flow. When the same variable was saved at that line before, the notification says how it differs and
     * offers Compare.
     */
    public static void save(Project project, String name, XValue value) {
        FlowSnapshot s = new FlowSnapshot();
        s.name = name == null || name.isBlank() ? "value" : name;
        s.flowName = FlowStore.get(project).getActiveFlowName();
        XDebugSession session = XDebuggerManager.getInstance(project).getCurrentSession();
        XSourcePosition pos = session == null ? null : session.getCurrentPosition();
        if (pos != null) {
            s.fileUrl = pos.getFile().getUrl();
            s.fileName = pos.getFile().getName();
            s.line = pos.getLine() + 1;
            Document doc = FileDocumentManager.getInstance().getDocument(pos.getFile());
            FlowEntry where = doc == null ? null : FlowCapture.entryFor(project, doc, pos.getLine());
            if (where != null) {
                s.className = where.className;
                s.methodName = where.methodName;
            }
        }
        FlowValueReader.read(s.name, value, FlowValueReader.DEPTH, v -> {
            if (project.isDisposed()) return;
            s.value = v;
            FlowDebugStore store = FlowDebugStore.get(project);
            store.add(s);
            saved(project, store.sameVariable(s), s);
        });
    }

    /** Tells what was saved; when the variable was saved at this line before, how it differs and a Compare link. */
    private static void saved(Project project, List<FlowSnapshot> before, FlowSnapshot s) {
        String text = "Saved " + s.name + " at " + s.where() + " in " + s.flowName + ".";
        if (!before.isEmpty()) {
            FlowSnapshot last = before.get(0);
            int n = FlowValueDiff.differences(FlowValueDiff.compare(last.value, s.value));
            text += n == 0 ? " Same data as the one saved " + time(last.savedAt) + " (" + last.flowName + ")."
                    : " " + n + (n == 1 ? " value differs" : " values differ") + " from the one saved " + time(last.savedAt)
                    + " (" + last.flowName + ").";
        }
        Notification note = NotificationGroupManager.getInstance().getNotificationGroup("TraceWeave")
                .createNotification(text, NotificationType.INFORMATION);
        if (!before.isEmpty()) {
            FlowSnapshot last = before.get(0);
            note.addAction(NotificationAction.createSimple("Compare", () -> FlowCompareDialog.show(project,
                    "Compare " + s.name + " at " + s.where(), last, "Saved " + time(last.savedAt) + " · " + last.flowName,
                    s, "Saved " + time(s.savedAt) + " · " + s.flowName)));
        }
        note.addAction(NotificationAction.createSimple("Show in Debug Data", () -> FlowDebugPanel.reveal(project, s)));
        note.notify(project);
    }

    private static void tell(Project project, String text, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup("TraceWeave").createNotification(text, type).notify(project);
    }

    private static String time(long millis) {
        return new SimpleDateFormat("dd MMM HH:mm:ss").format(new Date(millis));
    }

    /** Runs {@code action} with the paused session's frame, or tells the user to pause first. */
    public static void withFrame(Project project, Consumer<XStackFrame> action) {
        XStackFrame frame = currentFrame(project);
        if (frame == null) tell(project, "Pause the debugger first.", NotificationType.WARNING);
        else action.accept(frame);
    }
}
