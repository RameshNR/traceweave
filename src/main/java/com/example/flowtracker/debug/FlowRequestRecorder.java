package com.example.flowtracker.debug;

import com.example.flowtracker.code.FlowCodeSearch;
import com.example.flowtracker.compat.FlowCompat;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowStore;
import com.intellij.debugger.engine.DebugProcessImpl;
import com.intellij.debugger.engine.events.DebuggerCommandImpl;
import com.intellij.debugger.engine.events.SuspendContextCommandImpl;
import com.intellij.debugger.engine.requests.LocatableEventRequestor;
import com.intellij.debugger.engine.requests.RequestManagerImpl;
import com.intellij.debugger.settings.DebuggerSettings;
import com.intellij.debugger.ui.breakpoints.FilteredRequestor;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.classFilter.ClassFilter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.sun.jdi.Method;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.request.MethodEntryRequest;
import com.sun.jdi.request.MethodExitRequest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Record a Request: while it is on, the first thread that runs the project's code (e.g. the request you send to the
 * app) is followed, without breakpoints: every method of the project it enters is noted, in the order it ran. When
 * that first method returns (the request is done), or Stop is clicked, the methods become a new flow (each one's first
 * line). The app never stops; it only runs a little slower while recording.
 */
public final class FlowRequestRecorder {
    private static final Map<Project, FlowRequestRecorder> ALL = new ConcurrentHashMap<>();
    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance("TraceWeave.RecordRequest");
    /** The most methods a recording keeps. */
    static final int MAX = 400;

    public static FlowRequestRecorder get(Project project) {
        return ALL.computeIfAbsent(project, FlowRequestRecorder::new);
    }

    private final Project project;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<Hook> hooks = new CopyOnWriteArrayList<>();
    /** The methods entered, in order: "jvmClass#method" → its first line (1-based). */
    private final Map<String, Integer> methods = new LinkedHashMap<>();
    private volatile boolean recording;
    /** The thread followed (its id, or -1 until one runs the project's code) and its stack depth then. */
    private volatile long thread = -1;
    private volatile int depth;
    private volatile String firstMethod = "";

    private FlowRequestRecorder(Project project) {
        this.project = project;
    }

    public boolean isRecording() {
        return recording;
    }

    public void addListener(Runnable r) {
        listeners.add(r);
    }

    public void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }

    /** Starts: the project's classes are read (with a progress), then the hooks go into each Java debug session. */
    public void start() {
        if (recording) return;
        XDebugSession[] sessions = XDebuggerManager.getInstance(project).getDebugSessions();
        if (sessions.length == 0) {
            tell("Record a Request needs the app running in Debug. Start it in Debug, then Record a Request.", NotificationType.WARNING);
            return;
        }
        List<String> classes = new ArrayList<>();
        boolean read = com.intellij.openapi.progress.ProgressManager.getInstance().runProcessWithProgressSynchronously(
                () -> classes.addAll(FlowCompat.read(FlowValueHunt::projectClasses)), "Record a Request: reading the project's classes",
                true, project);
        if (!read || classes.isEmpty()) return;
        synchronized (methods) {
            methods.clear();
        }
        thread = -1;
        firstMethod = "";
        recording = true;
        Set<String> own = new HashSet<>(classes);
        String prefix = commonPackage(classes);
        int hooked = 0;
        for (XDebugSession session : sessions) {
            if (!(session.getDebugProcess() instanceof com.intellij.debugger.engine.JavaDebugProcess java)) continue;
            Hook h = new Hook(java.getDebuggerSession().getProcess(), own, prefix);
            hooks.add(h);
            h.begin();
            hooked++;
        }
        if (hooked == 0) {
            recording = false;
            tell("Record a Request works with Java debug sessions; none is running.", NotificationType.WARNING);
            return;
        }
        fire();
        tell("Recording: send the request to the app now (e.g. call the endpoint). The methods it runs become a flow when it "
                + "is done, or when you click Stop.", NotificationType.INFORMATION);
    }

    /** Stops: the hooks go, and what was recorded becomes a new flow. */
    public void stop() {
        if (!recording) return;
        recording = false;
        for (Hook h : hooks) h.end();
        hooks.clear();
        fire();
        List<Map.Entry<String, Integer>> got;
        synchronized (methods) {
            got = new ArrayList<>(methods.entrySet());
        }
        if (got.isEmpty()) {
            tell("Nothing was recorded: no thread ran the project's code while recording.", NotificationType.WARNING);
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            List<FlowEntry> lines = new ArrayList<>();
            boolean done = com.intellij.openapi.progress.ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    () -> lines.addAll(FlowCompat.read(() -> lines(got))), "Record a Request: finding the lines", true, project);
            if (!done || project.isDisposed()) return;
            if (lines.isEmpty()) {
                tell("The " + got.size() + " methods recorded weren't found in the project's code.", NotificationType.WARNING);
                return;
            }
            FlowStore.get(project).newFlow("Request: " + firstMethod, lines);
            tell("New flow “" + FlowStore.get(project).getActiveFlowName() + "”: " + lines.size() + " methods the request ran, "
                    + "in the order it ran them" + (got.size() >= MAX ? " (the first " + MAX + ")" : "") + ".", NotificationType.INFORMATION);
        });
    }

    /** Each recorded method's first line in the project's code, in order. */
    private List<FlowEntry> lines(List<Map.Entry<String, Integer>> got) {
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        List<FlowEntry> out = new ArrayList<>();
        for (Map.Entry<String, Integer> m : got) {
            String cls = m.getKey().substring(0, m.getKey().indexOf('#'));
            String outer = cls.contains("$") ? cls.substring(0, cls.indexOf('$')) : cls;
            PsiClass k = JavaPsiFacade.getInstance(project).findClass(outer, scope);
            VirtualFile vf = k == null || k.getContainingFile() == null ? null : k.getContainingFile().getVirtualFile();
            if (vf == null || !index.isInSourceContent(vf) || m.getValue() <= 0) continue;
            FlowEntry e = FlowCodeSearch.lineEntry(project, vf.getUrl(), m.getValue() - 1);
            if (e != null) out.add(e);
        }
        return out;
    }

    /** The package all the classes are in ("com.shop"), to keep the hooks to them; "" when they share none. */
    static String commonPackage(List<String> classes) {
        String common = null;
        for (String c : classes) {
            int dot = c.lastIndexOf('.');
            String pkg = dot < 0 ? "" : c.substring(0, dot);
            if (common == null) {
                common = pkg;
                continue;
            }
            while (!common.isEmpty() && !(pkg.equals(common) || pkg.startsWith(common + "."))) {
                int d = common.lastIndexOf('.');
                common = d < 0 ? "" : common.substring(0, d);
            }
        }
        return common == null ? "" : common;
    }

    /** A method of the project was entered (in the thread followed): noted once, with its first line. */
    private void entered(ThreadReference t, Method m) {
        if (!recording) return;
        try {
            if (thread == -1) { // the first thread that runs the project's code: the request
                synchronized (this) {
                    if (thread == -1) {
                        thread = t.uniqueID();
                        depth = t.frameCount();
                        String type = m.declaringType().name();
                        firstMethod = type.substring(type.lastIndexOf('.') + 1) + "." + m.name() + "()";
                    }
                }
            }
            if (t.uniqueID() != thread) return;
            int line = m.location() == null ? 0 : m.location().lineNumber();
            String key = m.declaringType().name() + "#" + m.name();
            synchronized (methods) {
                if (methods.size() < MAX) methods.putIfAbsent(key, line);
            }
        } catch (Exception e) {
            LOG.debug(e);
        }
    }

    /** A method returned in the thread followed: the first one returning means the request is done. */
    private void exited(ThreadReference t) {
        if (!recording || thread == -1 || t.uniqueID() != thread) return;
        try {
            if (t.frameCount() <= depth) ApplicationManager.getApplication().invokeLater(this::stop);
        } catch (Exception e) {
            LOG.debug(e);
        }
    }

    private void tell(String text, NotificationType type) {
        com.example.flowtracker.copy.FlowCopy.notify(project, text, type);
    }

    /** The method entry and exit hooks in one debugged app (only the project's classes: by their package). */
    private final class Hook implements LocatableEventRequestor, FilteredRequestor {
        private final DebugProcessImpl process;
        private final Set<String> own;
        private final String prefix;

        Hook(DebugProcessImpl process, Set<String> own, String prefix) {
            this.process = process;
            this.own = own;
            this.prefix = prefix;
        }

        void begin() {
            process.getManagerThread().schedule(new DebuggerCommandImpl() {
                @Override protected void action() {
                    RequestManagerImpl requests = process.getRequestsManager();
                    MethodEntryRequest in = requests.createMethodEntryRequest(Hook.this);
                    MethodExitRequest out = requests.createMethodExitRequest(Hook.this);
                    if (!prefix.isEmpty()) {
                        in.addClassFilter(prefix + ".*");
                        out.addClassFilter(prefix + ".*");
                    }
                    requests.enableRequest(in);
                    requests.enableRequest(out);
                }
            });
        }

        void end() {
            if (process.isDetached()) return;
            process.getManagerThread().schedule(new DebuggerCommandImpl() {
                @Override protected void action() {
                    process.getRequestsManager().deleteRequest(Hook.this);
                }
            });
        }

        @Override
        public boolean processLocatableEvent(SuspendContextCommandImpl action, LocatableEvent event) {
            if (event instanceof MethodEntryEvent in && own.contains(in.method().declaringType().name())) {
                entered(in.thread(), in.method());
            } else if (event instanceof MethodExitEvent out && own.contains(out.method().declaringType().name())) {
                exited(out.thread());
            }
            return false; // never stops: the app goes on
        }

        @Override public String getSuspendPolicy() { return DebuggerSettings.SUSPEND_THREAD; }
        @Override public boolean isInstanceFiltersEnabled() { return false; }
        @Override public com.intellij.debugger.InstanceFilter[] getInstanceFilters() { return new com.intellij.debugger.InstanceFilter[0]; }
        @Override public boolean isCountFilterEnabled() { return false; }
        @Override public int getCountFilter() { return 0; }
        @Override public boolean isClassFiltersEnabled() { return false; }
        @Override public ClassFilter[] getClassFilters() { return ClassFilter.EMPTY_ARRAY; }
        @Override public ClassFilter[] getClassExclusionFilters() { return ClassFilter.EMPTY_ARRAY; }
    }
}
