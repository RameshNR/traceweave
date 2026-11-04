package com.example.flowtracker.debug;

import com.example.flowtracker.model.FlowHuntStore;
import com.example.flowtracker.model.FlowValue;
import com.example.flowtracker.model.FlowValueSearch;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XDebuggerManagerListener;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XSuspendContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Stop at Value: the debugger is stopped where one or more values are found (or where a watched field is set), in
 * every debug session of the IDE's open projects, whatever thread is paused. Several records of the history can look
 * at the same time, each with its own values and way of looking; every stop is kept in the history
 * ({@link FlowHuntStore}). A value is found when a name, type or value <b>contains</b> it (case ignored), or matches it
 * as a /regex/; with several values, any of them, or all of them in the same stop.
 * <ul>
 *   <li>{@link Mode#ANYWHERE} and {@link Mode#FIELD}: field watches in the debugged app (no breakpoints); the app pauses
 *   only where a value that matches is stored;</li>
 *   <li>{@link Mode#FLOW_LINES} and {@link Mode#WHEREVER_IT_STOPS}: at the hunt's own breakpoints (the flow's lines) or
 *   yours, the variables are searched; where the values aren't, the debugger goes on by itself;</li>
 *   <li>{@link Mode#STEP_INTO} and {@link Mode#STEP_METHOD}: line by line from where the debugger is paused (one such
 *   record at a time: it drives the debugger).</li>
 * </ul>
 */
@Service(Service.Level.PROJECT)
public final class FlowValueHunt implements Disposable {

    public enum Mode {
        ANYWHERE("Anywhere in the project's code", "No breakpoints: it watches every field of the project's classes. When "
                + "code stores a value in one (a setter, a constructor, JSON read into an object…), in any thread or module, it "
                + "is looked at right there without stopping the app; the debugger stops only on the line that stores what "
                + "you look for. A value kept only in a local variable is seen once it is stored in a field."),
        FLOW_LINES("At the flow's lines", "No breakpoints needed: it puts its own on every line of the active flow (they go when "
                + "the hunt ends). Where the values aren't, the debugger goes on by itself; where they are, it stays paused."),
        FIELD("Watch a field or object", "No breakpoints: it watches one field of a class (or every field of it). Where code sets "
                + "it (to anything, to another value, to null, or to a value that holds what you look for), in any thread or "
                + "module, the debugger stops on that line."),
        WHEREVER_IT_STOPS("Wherever the debugger stops", "At your breakpoints and steps only, in any thread or project: where the "
                + "values aren't, the debugger goes on by itself; where they are, it stays paused."),
        STEP_INTO("Step from here, into the methods it calls", "Line by line from where the debugger is paused, following the calls, "
                + "until the values show up (at most " + 3000 + " lines). One stepping record at a time."),
        STEP_METHOD("Step through this method only", "Line by line through the method the debugger is paused in (calls are "
                + "stepped over), until the values show up or the method returns. One stepping record at a time.");
        public final String label, tip;
        Mode(String label, String tip) { this.label = label; this.tip = tip; }

        /** True when the hunt waits for the debugger to stop (and resumes it), false when it steps from where it is. */
        public boolean waits() {
            return this == ANYWHERE || this == FLOW_LINES || this == FIELD || this == WHEREVER_IT_STOPS;
        }

        /** True for the ways that watch fields in the app (no breakpoints, nothing to resume). */
        boolean watchesFields() {
            return this == ANYWHERE || this == FIELD;
        }

        /** The ways to look for values (all but watching a field). */
        public static List<Mode> forValues() {
            List<Mode> out = new ArrayList<>(List.of(values()));
            out.remove(FIELD);
            return out;
        }

        public static Mode of(String name) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException e) {
                return ANYWHERE;
            }
        }
    }

    static final int MAX_STEPS = 3000, DEPTH = 4;
    private static final com.intellij.openapi.diagnostic.Logger LOG = com.intellij.openapi.diagnostic.Logger.getInstance("TraceWeave.StopAtValue");
    /** While any record looks: a look every 2 seconds for debug sessions not listened to yet (a start the events missed). */
    private @Nullable java.util.concurrent.ScheduledFuture<?> scan;
    private int sessionCount = -1;

    private final Project project;
    /** The records looking now, by id (changed on the EDT). */
    private final Map<String, Runner> running = new LinkedHashMap<>();
    /** While any record looks: the debug sessions listened to, and what listens. */
    private @Nullable Disposable sessions;
    private final Set<XDebugSession> listened = new java.util.HashSet<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Runnable valuesChanged = this::refreshPatterns;
    /** Shows a stop in a project's Stop at Value tab (set by the tab): the project and the hunt's id. */
    private static volatile @Nullable BiConsumer<Project, String> shower;

    public FlowValueHunt(Project project) {
        this.project = project;
    }

    public static FlowValueHunt get(Project project) {
        return project.getService(FlowValueHunt.class);
    }

    public static void setShower(BiConsumer<Project, String> show) {
        shower = show;
    }

    /** True while any record looks. */
    public boolean isHunting() {
        return !running.isEmpty();
    }

    public boolean isRunning(@Nullable FlowHuntStore.Hunt h) {
        return h != null && running.containsKey(h.id);
    }

    /** The records looking now. */
    public List<FlowHuntStore.Hunt> runningHunts() {
        List<FlowHuntStore.Hunt> out = new ArrayList<>();
        for (Runner r : running.values()) out.add(r.hunt);
        return out;
    }

    /** How many stops (or stored values) a record has looked at. */
    public int checked(FlowHuntStore.Hunt h) {
        Runner r = running.get(h.id);
        return r == null ? h.checked : r.checked();
    }

    /**
     * True while a looking record can't see anything yet: no app runs in Debug (or, for the ways without breakpoints,
     * none is connected yet). The tab says "Waiting for Debug…" then.
     */
    public boolean isWaiting(FlowHuntStore.Hunt h) {
        Runner r = running.get(h.id);
        if (r == null) return false;
        if (debugSessions() == 0) return true;
        if (!r.mode.watchesFields()) return false;
        for (FlowFieldWatch w : r.fieldWatches) if (w.isWatching()) return false;
        return true;
    }

    /**
     * How many debug sessions run in the IDE's open projects. With none, nothing can pause: an app started with Run
     * (or outside the IDE) can't be stopped by any breakpoint.
     */
    public static int debugSessions() {
        int n = 0;
        for (Project p : ProjectManager.getInstance().getOpenProjects()) {
            if (p.isDisposed()) continue;
            for (XDebugSession s : XDebuggerManager.getInstance(p).getDebugSessions()) if (!s.isStopped()) n++;
        }
        return n;
    }

    /** The warning shown while no app runs in Debug. */
    public static final String NO_SESSION = "No app is running in Debug in this IDE, so nothing can pause. Start it with Debug "
            + "(the bug icon; both the web app and its API): an app started with Run, or outside the IDE, can't be stopped.";

    /** The values a text holds: one per line (blank lines left out). */
    public static List<String> values(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\R")) if (!line.isBlank()) out.add(line.strip());
        return out;
    }

    // ---------- starting and stopping records ----------

    /**
     * Starts a record of the history looking (a hunt for values, or a watch on a field); returns why it can't (null
     * when it started, or looks already). Other records go on looking, except a stepping one (it drives the
     * debugger: only one at a time, and alone).
     */
    public @Nullable String start(FlowHuntStore.Hunt h) {
        if (running.containsKey(h.id)) return null;
        Mode how = Mode.of(h.mode);
        if (h.active().isEmpty() && (how != Mode.FIELD || "VALUE".equals(h.when))) {
            return h.values.isEmpty() ? "Type the value (or values, one per line) to find." : "Every value is switched off: switch one on.";
        }
        for (String v : h.active()) {
            String error = FlowValueSearch.error(v);
            if (error != null) return error + " (" + v + ")";
        }
        XDebugSession current = XDebuggerManager.getInstance(project).getCurrentSession();
        if (!how.waits() && (current == null || !current.isSuspended())) {
            return "Pause the debugger first (e.g. at a breakpoint): it steps on from there.";
        }
        // stepping drives the debugger: it looks alone
        for (Runner r : new ArrayList<>(running.values())) {
            if (!how.waits() || !r.mode.waits()) stop(r.hunt);
        }
        LOG.info("Start " + h.name + " (" + how + "), " + debugSessions() + " debug sessions now");
        Runner r = new Runner(h, how);
        String why = r.prepare(current);
        if (why != null) {
            r.dispose();
            return why;
        }
        running.put(h.id, r);
        FlowHuntStore.get(project).begin(h);
        listenToSessions();
        for (XDebugSession s : new ArrayList<>(listened)) r.watchFields(s);
        fire();
        // paused right now: look here first
        if (!how.waits() && current != null) {
            ApplicationManager.getApplication().invokeLater(() -> paused(project, current));
        } else if (!how.watchesFields()) {
            for (XDebugSession s : new ArrayList<>(listened)) {
                Project p = projectOf(s);
                if (s.isSuspended() && p != null) ApplicationManager.getApplication().invokeLater(() -> paused(p, s));
            }
        }
        return null;
    }

    /** Stops a record looking (it stays in the history). */
    public void stop(FlowHuntStore.Hunt h) {
        end(h, false);
    }

    /** Stops every record looking. */
    public void stop() {
        for (Runner r : new ArrayList<>(running.values())) end(r.hunt, false);
    }

    private void end(FlowHuntStore.Hunt h, boolean gaveUp) {
        Runner r = running.remove(h.id);
        if (r == null) return;
        r.dispose();
        FlowHuntStore.get(project).checked(h, r.checked());
        FlowHuntStore.get(project).end(h, gaveUp);
        if (running.isEmpty()) stopListening();
        fire();
    }

    /** Every debug session of every open project (one IntelliJ window can hold several, and other windows too). */
    private void listenToSessions() {
        if (sessions != null) return;
        Disposable d = Disposer.newDisposable("TraceWeave Stop at Value");
        Disposer.register(this, d);
        sessions = d;
        scan = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                () -> ApplicationManager.getApplication().invokeLater(this::scanSessions), 2, 2, java.util.concurrent.TimeUnit.SECONDS);
        FlowHuntStore store = FlowHuntStore.get(project);
        store.addListener(valuesChanged);
        Disposer.register(d, () -> store.removeListener(valuesChanged));
        for (Project p : ProjectManager.getInstance().getOpenProjects()) {
            if (p.isDisposed()) continue;
            for (XDebugSession s : XDebuggerManager.getInstance(p).getDebugSessions()) listen(p, s);
            p.getMessageBus().connect(d).subscribe(XDebuggerManager.TOPIC, new XDebuggerManagerListener() {
                @Override public void processStarted(@NotNull XDebugProcess process) {
                    LOG.info("Debug process started: " + process.getSession().getSessionName());
                    listen(p, process.getSession());
                    fire(); // the tab stops saying no app runs in Debug
                }
                @Override public void processStopped(@NotNull XDebugProcess process) {
                    listened.remove(process.getSession());
                    fire();
                }
            });
        }
    }

    /**
     * The debug sessions of the open projects not listened to yet: listened to now (a session started while the
     * records look, whose start event didn't arrive or came before the session was there). The tab hears of a change.
     */
    private void scanSessions() {
        if (sessions == null || running.isEmpty()) return;
        int n = 0;
        for (Project p : ProjectManager.getInstance().getOpenProjects()) {
            if (p.isDisposed()) continue;
            for (XDebugSession s : XDebuggerManager.getInstance(p).getDebugSessions()) {
                if (s.isStopped()) continue;
                n++;
                if (!listened.contains(s)) {
                    LOG.info("Debug session found by the scan: " + s.getSessionName());
                    listen(p, s);
                    if (listened.contains(s) && s.isSuspended()) paused(p, s); // paused already (by a field watch)
                }
                for (Runner r : running.values()) r.watchFields(s); // a session whose process came late
            }
        }
        listened.removeIf(XDebugSession::isStopped);
        if (n != sessionCount) {
            sessionCount = n;
            fire();
        } else {
            for (Runner r : running.values()) {
                if (r.mode.watchesFields() && isWaiting(r.hunt)) { fire(); break; } // "Waiting for Debug…" may have ended
            }
        }
    }

    private void stopListening() {
        java.util.concurrent.ScheduledFuture<?> sc = scan;
        scan = null;
        if (sc != null) sc.cancel(false);
        sessionCount = -1;
        Disposable d = sessions;
        sessions = null;
        listened.clear();
        if (d != null) Disposer.dispose(d);
    }

    /**
     * The debug process of a session, or null while it isn't there yet: IntelliJ tells a debug session started before
     * it has its process (then asking for it fails), so it is listened to on a later look (every 2 seconds).
     */
    static @Nullable XDebugProcess processOf(XDebugSession session) {
        try {
            return session.isStopped() ? null : session.getDebugProcess();
        } catch (RuntimeException notYet) {
            return null;
        }
    }

    private void listen(Project where, XDebugSession session) {
        Disposable d = sessions;
        if (d == null || listened.contains(session)) return;
        XDebugProcess process = processOf(session);
        if (process == null) {
            LOG.info("Debug session " + session.getSessionName() + " has no process yet: listened to on the next look");
            return;
        }
        listened.add(session);
        LOG.info("Listening to debug session " + session.getSessionName() + " (" + process.getClass().getSimpleName() + ")");
        sessionProjects.put(session, where);
        for (Runner r : running.values()) r.watchFields(session);
        session.addSessionListener(new XDebugSessionListener() {
            @Override public void sessionPaused() {
                ApplicationManager.getApplication().invokeLater(() -> paused(where, session));
            }
        }, d);
    }

    private final Map<XDebugSession, Project> sessionProjects = new java.util.WeakHashMap<>();

    private @Nullable Project projectOf(XDebugSession s) {
        return sessionProjects.get(s);
    }

    /** The values each record looks for now (its values switched on): when they change in the tab. */
    private void refreshPatterns() {
        for (Runner r : running.values()) r.refreshPatterns();
    }

    // ---------- a pause ----------

    /**
     * The debugger paused: a field watch's find (that record stops there); else a stepping record steps on; else the
     * records that look at every pause search the variables (once for all of them), and the debugger goes on when
     * none of them finds its values. A pause no record looks at (e.g. your breakpoint) is left alone.
     */
    private void paused(Project where, XDebugSession session) {
        if (running.isEmpty() || where.isDisposed() || session.isStopped() || !session.isSuspended()) return;
        boolean any = false;
        for (Runner r : new ArrayList<>(running.values())) {
            List<String> stored = r.pending.getAndSet(null);
            if (stored != null) {
                found(r, where, session, stored, null);
                any = true;
            }
        }
        if (any) return;
        for (Runner r : running.values()) {
            if (!r.mode.waits()) {
                r.step(where, session);
                return;
            }
        }
        List<Runner> lookers = new ArrayList<>();
        for (Runner r : running.values()) if (!r.mode.watchesFields() && !r.patterns.isEmpty()) lookers.add(r);
        if (lookers.isEmpty()) return;
        XStackFrame frame = session.getCurrentStackFrame();
        if (frame == null) {
            session.resume();
            return;
        }
        FlowValueReader.readFrame(frame, DEPTH, root -> {
            if (session.isStopped() || !session.isSuspended()) return;
            boolean hit = false;
            for (Runner r : lookers) {
                if (!running.containsKey(r.hunt.id)) continue;
                r.checked++;
                List<String> m = matches(root, r.patterns, r.all);
                if (!m.isEmpty()) {
                    found(r, where, session, m, root);
                    hit = true;
                }
            }
            fire();
            if (!hit) session.resume();
        });
    }

    /**
     * What matched in a frame's variables ("label = value", at most 3 for each value), or nothing when the values
     * aren't there (any of them, or all of them when {@code all}).
     */
    static List<String> matches(FlowValue root, List<Pattern> patterns, boolean all) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Pattern p : patterns) {
            List<FlowValueSearch.Match> m = FlowValueSearch.find(null, root, p, 3, new ArrayList<>());
            if (m.isEmpty() && all) return List.of();
            for (FlowValueSearch.Match x : m) out.add(x.label() + " = " + shorten(x.value().value));
        }
        return new ArrayList<>(out);
    }

    private static String shorten(String s) {
        return s == null ? "" : s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    /** A record found what it looks for here: the stop goes in its history, with the variables there. */
    private void found(Runner r, Project where, XDebugSession session, List<String> matches, @Nullable FlowValue variables) {
        FlowHuntStore.Hunt h = r.hunt;
        FlowHuntStore store = FlowHuntStore.get(project);
        FlowHuntStore.Stop stop = stopAt(where, session, matches);
        store.addStop(h, stop);
        if (variables != null) {
            store.setVariables(stop, variables);
        } else {
            XStackFrame frame = session.getCurrentStackFrame();
            if (frame != null) {
                FlowValueReader.readFrame(frame, DEPTH, root -> ApplicationManager.getApplication().invokeLater(() -> store.setVariables(stop, root)));
            }
        }
        if (!r.keepGoing) end(h, false);
        session.showExecutionPoint();
        XSourcePosition pos = session.getCurrentPosition();
        String at = pos == null ? "" : " at " + pos.getFile().getName() + ":" + (pos.getLine() + 1);
        tell(where, h.name + ": " + matches.get(0) + at + ". The debugger is paused there"
                + (r.keepGoing ? "; resume it to keep looking." : "."), NotificationType.INFORMATION);
        BiConsumer<Project, String> show = shower;
        if (show != null) show.accept(project, h.id);
    }

    /** Where the debugger is paused, in which thread, and what matched. */
    private FlowHuntStore.Stop stopAt(Project where, XDebugSession session, List<String> matches) {
        FlowHuntStore.Stop s = new FlowHuntStore.Stop();
        s.at = System.currentTimeMillis();
        s.matches = new ArrayList<>(matches);
        XSourcePosition pos = session.getCurrentPosition();
        if (pos != null) {
            s.fileUrl = pos.getFile().getUrl();
            s.fileName = pos.getFile().getName();
            s.line = pos.getLine() + 1;
            Document doc = FileDocumentManager.getInstance().getDocument(pos.getFile());
            if (doc != null && pos.getLine() < doc.getLineCount()) {
                com.example.flowtracker.model.FlowEntry e = com.example.flowtracker.code.FlowCapture.lineEntry(where, doc, pos.getLine());
                if (e != null) s.method = (e.className == null || "-".equals(e.className) ? "" : e.className + ".") + e.methodName;
            }
        }
        XSuspendContext context = session.getSuspendContext();
        XExecutionStack stack = context == null ? null : context.getActiveExecutionStack();
        s.thread = stack == null ? "" : stack.getDisplayName();
        return s;
    }

    // ---------- one record looking ----------

    /** A record looking: its values and way of looking, its field watches or breakpoints, how far it got. */
    private final class Runner {
        final FlowHuntStore.Hunt hunt;
        final Mode mode;
        volatile List<Pattern> patterns = List.of();
        volatile boolean all;
        final boolean keepGoing;
        /** FIELD: the class (JVM name), the field (null: every field), when it stops, the object's data classes. */
        String watchClass = "", watchWhen = "SET";
        @Nullable String watchField;
        List<String> deepClasses = List.of();
        /** FIELD on a local variable: the class, the method, the variable, its lines (null: a field or object). */
        @Nullable String[] local;
        /** ANYWHERE: the project's classes. */
        List<String> projectClasses = List.of();
        final List<FlowFieldWatch> fieldWatches = new CopyOnWriteArrayList<>();
        /** What a field watch found, for the pause it causes. */
        final AtomicReference<List<String>> pending = new AtomicReference<>();
        final AtomicInteger watched = new AtomicInteger();
        final Set<XDebugSession> watchedSessions = new java.util.HashSet<>();
        int checked, steps;
        /** STEP_METHOD: the file and the range of the method being stepped through. */
        @Nullable String methodFile;
        @Nullable TextRange methodRange;
        /** FLOW_LINES: the breakpoints it put, removed when it ends. */
        final List<HuntBreakpoint> placed = new ArrayList<>();

        Runner(FlowHuntStore.Hunt h, Mode mode) {
            this.hunt = h;
            this.mode = mode;
            this.keepGoing = h.keepGoing && mode.waits();
            refreshPatterns();
        }

        int checked() {
            return checked + watched.get();
        }

        void refreshPatterns() {
            List<Pattern> ps = new ArrayList<>();
            for (String v : hunt.active()) {
                Pattern p = FlowValueSearch.error(v) == null ? FlowValueSearch.pattern(v) : null;
                if (p != null) ps.add(p);
            }
            patterns = ps;
            all = hunt.all && ps.size() > 1;
        }

        /** Gets ready (breakpoints, the classes to watch); returns why it can't. */
        @Nullable String prepare(@Nullable XDebugSession current) {
            if (mode == Mode.STEP_METHOD && current != null) {
                XSourcePosition pos = current.getCurrentPosition();
                methodFile = pos == null ? null : pos.getFile().getUrl();
                methodRange = pos == null ? null : methodRange(pos);
            }
            if (mode == Mode.FLOW_LINES) return placeBreakpoints(this);
            if (mode == Mode.ANYWHERE) {
                List<String> classes = new ArrayList<>();
                boolean done = com.intellij.openapi.progress.ProgressManager.getInstance().runProcessWithProgressSynchronously(
                        () -> classes.addAll(com.example.flowtracker.compat.FlowCompat.read(FlowValueHunt::projectClasses)), "Stop at Value: reading the project's classes", true, project);
                if (!done) return "Stopped reading the project's classes.";
                if (classes.isEmpty()) return "No Java or Kotlin classes were found in the open projects' code.";
                projectClasses = classes;
            }
            if (mode == Mode.FIELD && FlowLocalWatch.parse(hunt.target) != null) {
                local = FlowLocalWatch.parse(hunt.target);
                watchWhen = hunt.when.isEmpty() ? "SET" : hunt.when;
                return null;
            }
            if (mode == Mode.FIELD) {
                boolean deep = hunt.target.startsWith("*");
                String t = deep ? hunt.target.substring(1) : hunt.target;
                int hash = t.indexOf('#');
                watchClass = hash < 0 ? t : t.substring(0, hash);
                watchField = hash < 0 ? null : t.substring(hash + 1);
                watchWhen = hunt.when.isEmpty() ? "SET" : hunt.when;
                deepClasses = deep ? com.example.flowtracker.compat.FlowCompat.read(() -> objectClasses(project, watchClass, watchField)) : List.of();
            }
            return null;
        }

        /** ANYWHERE, FIELD: field watches in a Java debug session (once its app is connected). */
        void watchFields(XDebugSession session) {
            if (!mode.watchesFields() || watchedSessions.contains(session)) return;
            XDebugProcess process0 = processOf(session);
            if (process0 == null) return; // not there yet: the next look at the sessions does it
            watchedSessions.add(session);
            if (!(process0 instanceof com.intellij.debugger.engine.JavaDebugProcess java)) {
                LOG.info(hunt.name + ": not a Java debug session, no field watches: " + session.getSessionName());
                return;
            }
            com.intellij.debugger.engine.DebugProcessImpl process = java.getDebuggerSession().getProcess();
            Runnable counted = () -> {
                if (watched.incrementAndGet() % 50 == 0) ApplicationManager.getApplication().invokeLater(FlowValueHunt.this::fire);
            };
            List<FlowFieldWatch> ws = new ArrayList<>();
            if (mode == Mode.ANYWHERE) {
                ws.add(new FlowFieldWatch(process, projectClasses, null, FlowFieldWatch.values(() -> patterns, () -> all), pending::set, counted));
            } else if (local != null) {
                String[] lines = local[3].split("-");
                ws.add(new FlowLocalWatch(process, local[0], local[1] + "()", local[2], Integer.parseInt(lines[0]), Integer.parseInt(lines[1]),
                        watchWhen, hunt.target.startsWith("*"), () -> patterns, pending::set, counted).along(local.length >= 5 ? local[4] : ""));
            } else {
                boolean whole = deepClasses.contains(watchClass) && watchField == null; // the class's own fields are in the data
                if (!whole) ws.add(new FlowFieldWatch(process, List.of(watchClass), watchField, FlowFieldWatch.watch(watchWhen, () -> patterns),
                        pending::set, counted));
                if (!deepClasses.isEmpty()) ws.add(new FlowFieldWatch(process, deepClasses, null, FlowFieldWatch.watch(watchWhen, () -> patterns),
                        pending::set, counted));
            }
            for (FlowFieldWatch w : ws) {
                fieldWatches.add(w);
                w.onWatching(() -> {
                    LOG.info(hunt.name + ": field watches in " + session.getSessionName());
                    ApplicationManager.getApplication().invokeLater(FlowValueHunt.this::fire); // "Waiting for Debug…" goes
                });
                w.startWhenAttached();
            }
        }

        /** STEP_INTO, STEP_METHOD: looks at the paused line, then steps on. */
        void step(Project where, XDebugSession session) {
            XStackFrame frame = session.getCurrentStackFrame();
            if (frame == null || patterns.isEmpty()) return;
            FlowValueReader.readFrame(frame, DEPTH, root -> {
                if (!running.containsKey(hunt.id) || session.isStopped() || !session.isSuspended()) return;
                checked++;
                List<String> m = matches(root, patterns, all);
                fire();
                if (!m.isEmpty()) {
                    found(this, where, session, m, root);
                    return;
                }
                if (++steps > MAX_STEPS) {
                    end(hunt, true);
                    tell(where, hunt.name + ": not found in " + MAX_STEPS + " lines. The debugger is paused where it got to.", NotificationType.WARNING);
                    return;
                }
                if (mode == Mode.STEP_METHOD) {
                    XSourcePosition pos = session.getCurrentPosition();
                    TextRange range = methodRange;
                    boolean inside = pos != null && pos.getFile().getUrl().equals(methodFile) && range != null && range.contains(pos.getOffset());
                    if (steps > 1 && !inside) {
                        end(hunt, true);
                        tell(where, hunt.name + ": not found in the method: it returned. The debugger is paused where it went back to.",
                                NotificationType.INFORMATION);
                        return;
                    }
                    session.stepOver(false);
                } else {
                    session.stepInto();
                }
            });
        }

        void dispose() {
            for (FlowFieldWatch w : fieldWatches) w.stop();
            fieldWatches.clear();
            pending.set(null);
            removeBreakpoints(placed);
        }
    }

    // ---------- the classes to watch ----------

    /**
     * The classes an object's data is in: the class of the object (the field's type, or the class itself) and the
     * project's classes of the objects inside it (field types and their type arguments, e.g. List&lt;PriceLine&gt;), a
     * few levels deep. JVM names.
     */
    public static List<String> objectClasses(Project project, String jvmClass, @Nullable String field) {
        com.intellij.psi.PsiClass owner = com.intellij.psi.util.ClassUtil.findPsiClass(
                com.intellij.psi.PsiManager.getInstance(project), jvmClass.replace('$', '.'));
        if (owner == null) owner = com.intellij.psi.util.ClassUtil.findPsiClass(com.intellij.psi.PsiManager.getInstance(project), jvmClass);
        if (owner == null) return List.of();
        List<com.intellij.psi.PsiClass> roots = new ArrayList<>();
        if (field == null) {
            roots.add(owner);
        } else {
            com.intellij.psi.PsiField f = owner.findFieldByName(field, true);
            if (f != null) roots.addAll(projectClassesIn(project, f.getType()));
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        java.util.ArrayDeque<com.intellij.psi.PsiClass> todo = new java.util.ArrayDeque<>(roots);
        Map<com.intellij.psi.PsiClass, Integer> level = new java.util.HashMap<>();
        for (com.intellij.psi.PsiClass r : roots) level.put(r, 0);
        while (!todo.isEmpty() && out.size() < 60) {
            com.intellij.psi.PsiClass k = todo.poll();
            String name = com.example.flowtracker.compat.FlowCompat.jvmName(k);
            if (name == null || !out.add(name)) continue;
            int depth = level.getOrDefault(k, 0);
            if (depth >= 3) continue;
            for (com.intellij.psi.PsiField f : k.getAllFields()) {
                if (f.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC)) continue;
                for (com.intellij.psi.PsiClass inner : projectClassesIn(project, f.getType())) {
                    if (level.putIfAbsent(inner, depth + 1) == null) todo.add(inner);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** The project's (non-test) classes a type names: itself, its array element, its type arguments. */
    private static List<com.intellij.psi.PsiClass> projectClassesIn(Project project, com.intellij.psi.PsiType type) {
        List<com.intellij.psi.PsiClass> out = new ArrayList<>();
        com.intellij.psi.PsiType t = type.getDeepComponentType();
        if (t instanceof com.intellij.psi.PsiClassType ct) {
            com.intellij.psi.PsiClass k = ct.resolve();
            if (k != null && isProjectClass(project, k) && !k.isInterface() && !k.isEnum()) out.add(k);
            for (com.intellij.psi.PsiType arg : ct.getParameters()) out.addAll(projectClassesIn(project, arg));
        }
        return out;
    }

    private static boolean isProjectClass(Project project, com.intellij.psi.PsiClass k) {
        com.intellij.openapi.vfs.VirtualFile f = k.getContainingFile() == null ? null : k.getContainingFile().getVirtualFile();
        if (f == null) return false;
        com.intellij.openapi.roots.ProjectFileIndex index = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project);
        return index.isInSourceContent(f) && !index.isInTestSourceContent(f);
    }

    /** The project's classes (JVM names, nested ones too) in the open projects' (non-test) Java and Kotlin code. */
    private static List<String> projectClasses() {
        Set<String> out = new LinkedHashSet<>();
        for (Project p : ProjectManager.getInstance().getOpenProjects()) {
            if (p.isDisposed()) continue;
            com.intellij.openapi.roots.ProjectFileIndex index = com.intellij.openapi.roots.ProjectFileIndex.getInstance(p);
            com.intellij.psi.PsiManager psi = com.intellij.psi.PsiManager.getInstance(p);
            index.iterateContent(f -> {
                com.intellij.openapi.progress.ProgressManager.checkCanceled();
                String ext = f.getExtension();
                if (f.isDirectory() || !("java".equalsIgnoreCase(ext) || "kt".equalsIgnoreCase(ext))
                        || !index.isInSourceContent(f) || index.isInTestSourceContent(f)) return true;
                if (psi.findFile(f) instanceof com.intellij.psi.PsiClassOwner owner) {
                    for (com.intellij.psi.PsiClass c : owner.getClasses()) addClass(c, out);
                }
                return true;
            });
        }
        return new ArrayList<>(out);
    }

    private static void addClass(com.intellij.psi.PsiClass c, Set<String> out) {
        if (!c.isInterface() && !c.isAnnotationType()) {
            String name = com.example.flowtracker.compat.FlowCompat.jvmName(c);
            if (name != null) out.add(name);
        }
        for (com.intellij.psi.PsiClass inner : c.getInnerClasses()) addClass(inner, out);
    }

    // ---------- FLOW_LINES: the hunt's own breakpoints ----------

    private record HuntBreakpoint(Project project, com.intellij.xdebugger.breakpoints.XLineBreakpoint<?> breakpoint) {}

    /** A line where the hunt puts a breakpoint (0-based). */
    record Spot(Project project, com.intellij.openapi.vfs.VirtualFile file, int line) {}

    /** Puts a record's breakpoints on the flow's lines; returns why it can't (null when it did). */
    private @Nullable String placeBreakpoints(Runner r) {
        List<Spot> spots = new ArrayList<>();
        boolean done = com.intellij.openapi.progress.ProgressManager.getInstance().runProcessWithProgressSynchronously(
                () -> spots.addAll(com.example.flowtracker.compat.FlowCompat.read(this::flowSpots)), "Stop at Value: placing breakpoints", true, project);
        if (!done) return "Stopped placing the breakpoints.";
        if (spots.isEmpty()) return "The active flow has no code lines to put breakpoints on.";
        com.intellij.openapi.application.WriteAction.run(() -> {
            for (Spot s : spots) {
                com.intellij.xdebugger.breakpoints.XLineBreakpoint<?> bp = put(s);
                if (bp != null) r.placed.add(new HuntBreakpoint(s.project(), bp));
            }
        });
        return null;
    }

    /** A breakpoint on the line, unless one is there already (then the hunt checks there anyway). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static @Nullable com.intellij.xdebugger.breakpoints.XLineBreakpoint<?> put(Spot s) {
        com.intellij.xdebugger.breakpoints.XBreakpointManager bm = XDebuggerManager.getInstance(s.project()).getBreakpointManager();
        for (com.intellij.xdebugger.breakpoints.XLineBreakpointType type : com.intellij.xdebugger.XDebuggerUtil.getInstance().getLineBreakpointTypes()) {
            if (!type.canPutAt(s.file(), s.line(), s.project())) continue;
            for (Object b : bm.getBreakpoints(type)) {
                if (b instanceof com.intellij.xdebugger.breakpoints.XLineBreakpoint<?> lb
                        && s.file().getUrl().equals(lb.getFileUrl()) && lb.getLine() == s.line()) {
                    return null;
                }
            }
            return bm.addLineBreakpoint(type, s.file().getUrl(), s.line(), type.createBreakpointProperties(s.file(), s.line()));
        }
        return null;
    }

    private static void removeBreakpoints(List<HuntBreakpoint> placed) {
        if (placed.isEmpty()) return;
        List<HuntBreakpoint> gone = new ArrayList<>(placed);
        placed.clear();
        Runnable remove = () -> com.intellij.openapi.application.WriteAction.run(() -> {
            for (HuntBreakpoint h : gone) {
                if (!h.project().isDisposed()) XDebuggerManager.getInstance(h.project()).getBreakpointManager().removeBreakpoint(h.breakpoint());
            }
        });
        if (ApplicationManager.getApplication().isDispatchThread()) remove.run();
        else ApplicationManager.getApplication().invokeLater(remove);
    }

    /** Every code line of the active flow. */
    private List<Spot> flowSpots() {
        List<Spot> out = new ArrayList<>();
        for (com.example.flowtracker.model.FlowEntry e : com.example.flowtracker.model.FlowStore.get(project).getEntries()) {
            if (e.line <= 0) continue;
            com.intellij.openapi.vfs.VirtualFile f = com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(e.fileUrl);
            if (f != null) out.add(new Spot(project, f, e.line - 1));
        }
        return out;
    }

    /** The range of the method around a position (null when it isn't in one). */
    private @Nullable TextRange methodRange(XSourcePosition pos) {
        return com.example.flowtracker.compat.FlowCompat.read(() -> {
            Document doc = FileDocumentManager.getInstance().getDocument(pos.getFile());
            PsiFile psi = doc == null ? null : PsiDocumentManager.getInstance(project).getPsiFile(doc);
            PsiMethod m = psi == null ? null : PsiTreeUtil.getParentOfType(psi.findElementAt(pos.getOffset()), PsiMethod.class, false);
            return m == null ? null : m.getTextRange();
        });
    }

    private static void tell(Project where, String text, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup("TraceWeave").createNotification(text, type).notify(where);
    }

    public void addListener(Runnable r) { listeners.add(r); }

    public void removeListener(Runnable r) { listeners.remove(r); }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }

    @Override
    public void dispose() {
        stop();
    }

    /** What a value was found as (for tests): its label in the frame, or null. */
    static @Nullable String firstMatch(FlowValue root, String text) {
        Pattern p = FlowValueSearch.pattern(text);
        if (p == null) return null;
        List<FlowValueSearch.Match> m = FlowValueSearch.find(null, root, p, 1, new ArrayList<>());
        return m.isEmpty() ? null : m.get(0).label();
    }
}
