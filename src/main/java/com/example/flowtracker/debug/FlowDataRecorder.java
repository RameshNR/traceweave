package com.example.flowtracker.debug;

import com.example.flowtracker.code.FlowCapture;
import com.example.flowtracker.model.FlowDebugStore;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowSnapshot;
import com.example.flowtracker.model.FlowStore;
import com.example.flowtracker.model.FlowValue;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XDebuggerManagerListener;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.frame.XStackFrame;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Capture Data: while on, the variables of the current stack frame are saved to Debug Data automatically, each
 * variable as its own saved value (so it can be compared with the same variable saved at that line before).
 * <ul>
 * <li><b>Where the debugger stops</b>: at breakpoints and at each step the user takes.</li>
 * <li><b>Every line it runs</b>: from a stop on, it steps (Step Into) line by line by itself and saves what changed on
 * each line, until it reaches the next breakpoint, where it stops as the debugger does. When the user goes on from
 * there (Resume, or a step), it steps by itself again from the next stop. At most {@link #MAX_STEPS} lines a run.</li>
 * </ul>
 * Either way it can save every variable of the frame, or only the ones used on the line.
 */
@Service(Service.Level.PROJECT)
public final class FlowDataRecorder implements Disposable {
    /** How many lines one debug run is stepped through by itself before Capture Data stops stepping. */
    public static final int MAX_STEPS = 500;
    /** How deep the frame's variables are read: as deep as Save to TraceWeave (fields, map entries, their values…). */
    static final int DEPTH = FlowValueReader.DEPTH;
    private static final Pattern WORD = Pattern.compile("[A-Za-z_$][\\w$]*");

    private final Project project;
    private @Nullable Disposable capturing;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private int steps;
    /** True while the pause about to come is from Capture Data's own step (not the user's). */
    private boolean ownStep;
    /** In "every line" mode: what each variable held when it was last saved in this run, to save only changes. */
    private final Map<String, String> last = new HashMap<>();

    public FlowDataRecorder(Project project) {
        this.project = project;
    }

    public static FlowDataRecorder get(Project project) {
        return project.getService(FlowDataRecorder.class);
    }

    public boolean isCapturing() {
        return capturing != null;
    }

    public void toggle() {
        if (isCapturing()) stop(); else start();
    }

    public void start() {
        if (capturing != null) return;
        Disposable d = Disposer.newDisposable("TraceWeave Capture Data");
        Disposer.register(this, d);
        capturing = d;
        newRun();
        for (XDebugSession session : XDebuggerManager.getInstance(project).getDebugSessions()) watch(session, d);
        project.getMessageBus().connect(d).subscribe(XDebuggerManager.TOPIC, new XDebuggerManagerListener() {
            @Override
            public void processStarted(@NotNull XDebugProcess debugProcess) {
                newRun();
                watch(debugProcess.getSession(), d);
            }
        });
        fire();
    }

    public void stop() {
        Disposable d = capturing;
        if (d == null) return;
        capturing = null;
        ownStep = false;
        Disposer.dispose(d);
        fire();
    }

    private void newRun() {
        steps = 0;
        ownStep = false;
        last.clear();
    }

    private void watch(XDebugSession session, Disposable parent) {
        session.addSessionListener(new XDebugSessionListener() {
            @Override
            public void sessionPaused() {
                ApplicationManager.getApplication().invokeLater(() -> capture(session));
            }
        }, parent);
        if (session.isSuspended()) ApplicationManager.getApplication().invokeLater(() -> capture(session));
    }

    /** Saves the current frame's variables; in "every line" mode, then steps on (unless it reached a breakpoint). */
    private void capture(XDebugSession session) {
        boolean byOwnStep = ownStep;
        ownStep = false;
        if (!isCapturing() || project.isDisposed() || session.isStopped() || !session.isSuspended()) return;
        XStackFrame frame = session.getCurrentStackFrame();
        XSourcePosition pos = session.getCurrentPosition();
        if (frame == null) return;
        FlowDebugStore store = FlowDebugStore.get(project);
        boolean everyLine = store.isCaptureEveryLine();
        boolean lineOnly = store.isCaptureLineOnly();
        // Stepping by itself reached a breakpoint: save it, and stop there as the debugger does.
        boolean atBreakpoint = everyLine && byOwnStep && hasBreakpoint(session, pos);

        String flowName = FlowStore.get(project).getActiveFlowName();
        String fileUrl = "", fileName = "", className = "", methodName = "", lineText = null;
        int line = 0;
        if (pos != null) {
            fileUrl = pos.getFile().getUrl();
            fileName = pos.getFile().getName();
            line = pos.getLine() + 1;
            Document doc = FileDocumentManager.getInstance().getDocument(pos.getFile());
            if (doc != null && pos.getLine() < doc.getLineCount()) {
                lineText = doc.getText(new TextRange(doc.getLineStartOffset(pos.getLine()), doc.getLineEndOffset(pos.getLine())));
                FlowEntry where = FlowCapture.entryFor(project, doc, pos.getLine());
                if (where != null) {
                    className = where.className;
                    methodName = where.methodName;
                }
            }
        }
        String fUrl = fileUrl, fName = fileName, cName = className, mName = methodName, text = lineText;
        int ln = line;
        FlowValueReader.readFrame(frame, DEPTH, all -> {
            if (project.isDisposed()) return;
            List<FlowSnapshot> saved = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (FlowValue v : pick(all, lineOnly ? text : null)) {
                if (everyLine) {
                    String sig = signature(v);
                    if (sig.equals(last.put(v.name, sig))) continue; // unchanged since the last line: nothing new
                }
                FlowSnapshot s = new FlowSnapshot();
                s.name = v.name;
                s.flowName = flowName;
                s.fileUrl = fUrl;
                s.fileName = fName;
                s.line = ln;
                s.className = cName;
                s.methodName = mName;
                s.savedAt = now;
                s.id = java.util.UUID.randomUUID().toString();
                s.value = v;
                // Already saved here with the same data (e.g. by hand before Capture Data was on): no copy.
                List<FlowSnapshot> before = store.sameVariable(s);
                if (!before.isEmpty() && signature(before.get(0).value).equals(signature(v))) continue;
                saved.add(s);
            }
            if (!saved.isEmpty()) store.addAll(saved);
            if (!everyLine || atBreakpoint || !isCapturing() || session.isStopped() || !session.isSuspended()) return;
            if (++steps > MAX_STEPS) {
                tell("Capture Data stopped stepping after " + MAX_STEPS + " lines of this run (the values so far are saved). "
                        + "Resume the debugger or step on to capture more.");
                steps = 0;
                return;
            }
            ownStep = true;
            session.stepInto();
        });
    }

    /** True when an enabled line breakpoint is on this line (and breakpoints aren't muted). */
    private boolean hasBreakpoint(XDebugSession session, @Nullable XSourcePosition pos) {
        if (pos == null || session.areBreakpointsMuted()) return false;
        String url = pos.getFile().getUrl();
        for (XBreakpoint<?> b : XDebuggerManager.getInstance(project).getBreakpointManager().getAllBreakpoints()) {
            if (b instanceof XLineBreakpoint<?> lb && lb.isEnabled() && lb.getLine() == pos.getLine() && url.equals(lb.getFileUrl())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The values to save from a frame's variables: all of them, or (with the line's text) only those used on the
     * line: its variables, and the fields of {@code this} it names (saved under the field's name).
     */
    static List<FlowValue> pick(FlowValue frame, @Nullable String lineText) {
        if (lineText == null) return new ArrayList<>(frame.children);
        Set<String> words = new HashSet<>();
        Matcher m = WORD.matcher(stripStrings(lineText));
        while (m.find()) words.add(m.group());
        List<FlowValue> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (FlowValue v : frame.children) {
            if (words.contains(v.name) && names.add(v.name)) out.add(v);
        }
        for (FlowValue v : frame.children) {
            if (!v.name.equals("this") || names.contains("this")) continue;
            for (FlowValue field : v.children) {
                if (words.contains(field.name) && names.add(field.name)) out.add(field);
            }
        }
        return out;
    }

    /** The line without its string literals and // comment (so words in them don't count). */
    private static String stripStrings(String line) {
        String s = line.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", " ").replaceAll("'(?:\\\\.|[^'\\\\])*'", " ");
        int comment = s.indexOf("//");
        return comment >= 0 ? s.substring(0, comment) : s;
    }

    /** What a value holds, as one string (to see whether it changed). */
    static String signature(FlowValue v) {
        StringBuilder b = new StringBuilder();
        sign(v, b);
        return b.toString();
    }

    private static void sign(FlowValue v, StringBuilder b) {
        b.append(v.name).append('=').append(v.value.replaceAll("@\\d+", "")).append('{');
        for (FlowValue c : v.children) sign(c, b);
        b.append('}');
    }

    private void tell(String text) {
        NotificationGroupManager.getInstance().getNotificationGroup("TraceWeave")
                .createNotification(text, NotificationType.INFORMATION).notify(project);
    }

    public void addListener(Runnable r) { listeners.add(r); }
    public void removeListener(Runnable r) { listeners.remove(r); }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }

    @Override
    public void dispose() {
        capturing = null;
    }
}
