package com.example.flowtracker.debug;

import com.intellij.debugger.engine.DebugProcess;
import com.intellij.debugger.engine.DebugProcessImpl;
import com.intellij.debugger.engine.events.DebuggerCommandImpl;
import com.intellij.debugger.engine.events.SuspendContextCommandImpl;
import com.intellij.debugger.engine.requests.RequestManagerImpl;
import com.intellij.debugger.requests.ClassPrepareRequestor;
import com.intellij.debugger.settings.DebuggerSettings;
import com.intellij.debugger.ui.breakpoints.FilteredRequestor;
import com.intellij.debugger.engine.requests.LocatableEventRequestor;
import com.intellij.ui.classFilter.ClassFilter;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.event.ModificationWatchpointEvent;
import com.sun.jdi.request.EventRequest;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Stop at Value, anywhere: watches every field of the project's classes in one debugged app. When code stores a value
 * in one of them (a setter, a constructor, JSON filled into a DTO…), the value is looked at right there, in the
 * debugger, without stopping the app; only when it holds what is looked for does the thread stay paused, on that line.
 * Nothing shows in the editor or the Breakpoints list.
 */
class FlowFieldWatch implements ClassPrepareRequestor, LocatableEventRequestor, FilteredRequestor {
    /** How deep an object's fields are looked into, and how many fields / elements at each level. */
    static final int DEPTH = 2, MAX_FIELDS = 40, MAX_ELEMENTS = 25;

    /**
     * Whether a value stored in a field is a find: what to list for it ("label = text"), or nothing to let the app go
     * on. {@code label} is "Class.field"; {@code current} is the field's value before, {@code toBe} the one stored.
     */
    interface Rule {
        List<String> test(String label, @Nullable Value current, @Nullable Value toBe);
    }

    final DebugProcessImpl process;
    private final Collection<String> classes;
    private final @Nullable String field;
    private final Rule rule;
    final Consumer<List<String>> onFind;
    final Runnable onCheck;
    final Set<ReferenceType> watched = new java.util.HashSet<>();
    volatile boolean stopped;

    /**
     * @param classes the classes watched (JVM names, e.g. "com.shop.Order$Line")
     * @param field   the one field watched in them (null: every field)
     * @param onFind  what matched, before the thread stays paused there
     * @param onCheck a stored value was looked at
     */
    FlowFieldWatch(DebugProcessImpl process, Collection<String> classes, @Nullable String field, Rule rule,
                   Consumer<List<String>> onFind, Runnable onCheck) {
        this.process = process;
        this.classes = classes;
        this.field = field;
        this.rule = rule;
        this.onFind = onFind;
        this.onCheck = onCheck;
    }

    /** Stop at Value: a stored value that holds one of the values (all of them, with {@code all}). */
    static Rule values(Supplier<List<Pattern>> patterns, Supplier<Boolean> all) {
        return (label, current, toBe) -> {
            List<Pattern> ps = patterns.get();
            return ps.isEmpty() ? List.of() : matches(label, toBe, ps, all.get());
        };
    }

    /**
     * Watch a field: stop when it is set ("SET"), set to another value ("CHANGE"), set to null ("NULL"), or set to a
     * value that holds one of the values ("VALUE").
     */
    static Rule watch(String when, Supplier<List<Pattern>> patterns) {
        return (label, current, toBe) -> {
            String was = show(current), now = show(toBe);
            String change = label + ": " + was + " → " + now;
            return switch (when) {
                case "SET" -> List.of(change);
                case "CHANGE" -> java.util.Objects.equals(current, toBe) || was.equals(now) && !(toBe instanceof ObjectReference)
                        ? List.of() : List.of(change);
                case "NULL" -> toBe == null && current != null ? List.of(change) : List.of();
                default -> {
                    List<Pattern> ps = patterns.get();
                    List<String> m = ps.isEmpty() ? List.of() : matches(label, toBe, ps, false);
                    if (m.isEmpty()) yield List.of();
                    List<String> out = new ArrayList<>();
                    out.add(change);
                    out.addAll(m);
                    yield out;
                }
            };
        };
    }

    /** A value as text: "null", "\"text\"", 42, an enum's name, or the object's class and id. */
    static String show(@Nullable Value v) {
        if (v == null) return "null";
        if (v instanceof StringReference s) {
            String t = s.value();
            return "\"" + (t.length() > 80 ? t.substring(0, 80) + "…" : t) + "\"";
        }
        if (v instanceof PrimitiveValue) return v.toString();
        if (v instanceof ObjectReference o) {
            ReferenceType t = o.referenceType();
            if (t instanceof ClassType ct && ct.isEnum()) {
                Field name = t.fieldByName("name");
                if (name != null && o.getValue(name) instanceof StringReference n) return n.value();
            }
            String type = t.name();
            return type.substring(Math.max(type.lastIndexOf('.'), type.lastIndexOf('$')) + 1) + "@" + o.uniqueID();
        }
        return v.toString();
    }

    private final java.util.concurrent.atomic.AtomicBoolean begun = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile @Nullable java.util.concurrent.ScheduledFuture<?> retry;

    /** True once the watches are in the app (its debugger is connected). */
    boolean isWatching() {
        return begun.get() && !stopped;
    }

    /**
     * Watches as soon as the debugger is connected to the app: now when it is, else when it connects (an app started
     * in Debug after the hunt). A check every second makes sure a connection isn't missed.
     */
    void startWhenAttached() {
        if (process.isAttached()) {
            begin();
            return;
        }
        process.addDebugProcessListener(new com.intellij.debugger.engine.DebugProcessListener() {
            @Override public void processAttached(@org.jetbrains.annotations.NotNull DebugProcess p) { begin(); }
        });
        long until = System.currentTimeMillis() + 15 * 60_000L;
        retry = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(() -> {
            if (stopped || begun.get() || System.currentTimeMillis() > until) {
                java.util.concurrent.ScheduledFuture<?> r = retry;
                if (r != null) r.cancel(false);
            } else if (process.isAttached()) {
                begin();
            }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void begin() {
        if (!stopped && begun.compareAndSet(false, true)) {
            start();
            Runnable r = onWatching;
            if (r != null) r.run();
        }
    }

    private volatile @Nullable Runnable onWatching;

    /** Runs when the watches go in (the app's debugger is connected). */
    void onWatching(Runnable r) {
        onWatching = r;
    }

    /** Watches the classes loaded already and, as they load, the others. */
    private void start() {
        process.getManagerThread().schedule(new DebuggerCommandImpl() {
            @Override protected void action() {
                if (stopped) return;
                try {
                    RequestManagerImpl requests = process.getRequestsManager();
                    for (String name : classes) {
                        requests.callbackOnPrepareClasses(FlowFieldWatch.this, name);
                        for (ReferenceType t : process.getVirtualMachineProxy().classesByName(name)) watch(t);
                    }
                } catch (RuntimeException e) {
                    begun.set(false); // not connected after all: the check every second tries again
                }
            }
        });
    }

    /** Takes every watch away. */
    void stop() {
        stopped = true;
        java.util.concurrent.ScheduledFuture<?> r = retry;
        if (r != null) r.cancel(false);
        if (process.isDetached() || !begun.get()) return;
        process.getManagerThread().schedule(new DebuggerCommandImpl() {
            @Override protected void action() {
                process.getRequestsManager().deleteRequest(FlowFieldWatch.this);
            }
        });
    }

    void watch(ReferenceType type) {
        if (stopped || !type.isPrepared() || !watched.add(type)) return;
        if (!process.getVirtualMachineProxy().canWatchFieldModification()) return;
        RequestManagerImpl requests = process.getRequestsManager();
        for (Field f : type.fields()) {
            if (f.isSynthetic() || field != null && !field.equals(f.name())) continue;
            EventRequest r = requests.createModificationWatchpointRequest(this, f);
            requests.enableRequest(r);
        }
    }

    @Override
    public void processClassPrepare(DebugProcess debugProcess, ReferenceType type) {
        watch(type);
    }

    /** A value is being stored in a watched field: stay paused only when it holds what is looked for. */
    @Override
    public boolean processLocatableEvent(SuspendContextCommandImpl action, LocatableEvent event) {
        if (stopped || !(event instanceof ModificationWatchpointEvent m)) return false;
        onCheck.run();
        String owner = m.field().declaringType().name();
        String label = owner.substring(Math.max(owner.lastIndexOf('.'), owner.lastIndexOf('$')) + 1) + "." + m.field().name();
        List<String> found = rule.test(label, m.valueCurrent(), m.valueToBe());
        if (found.isEmpty()) return false;
        onFind.accept(found);
        return true;
    }

    /**
     * What a value holds that matches (as "label = text"), or nothing; with {@code all}, every pattern must match
     * somewhere in it. Strings, numbers, booleans, enums, and objects' fields and arrays' elements a little deep.
     */
    static List<String> matches(String label, @Nullable Value v, List<Pattern> ps, boolean all) {
        List<String[]> texts = new ArrayList<>(); // label, text
        collect(label, v, 0, texts);
        Set<String> out = new LinkedHashSet<>();
        for (Pattern p : ps) {
            boolean any = false;
            for (String[] t : texts) {
                if (p.matcher(t[1]).find()) {
                    out.add(t[0] + " = " + (t[1].length() > 120 ? t[1].substring(0, 120) + "…" : t[1]));
                    any = true;
                }
            }
            if (!any && all) return List.of();
        }
        return new ArrayList<>(out);
    }

    /** A value and its data a little deep, as text: two different texts mean the value or its data changed. */
    static String fingerprint(@Nullable Value v) {
        List<String[]> texts = new ArrayList<>();
        collect("", v, 0, texts);
        StringBuilder sb = new StringBuilder(show(v));
        for (String[] t : texts) sb.append('|').append(t[0]).append('=').append(t[1]);
        return sb.toString();
    }

    static void collect(String label, @Nullable Value v, int depth, List<String[]> out) {
        if (v == null) return;
        if (v instanceof StringReference s) {
            out.add(new String[]{label, s.value()});
        } else if (v instanceof PrimitiveValue) {
            out.add(new String[]{label, v.toString()});
        } else if (v instanceof ArrayReference a) {
            if (depth >= DEPTH) return;
            int n = Math.min(a.length(), MAX_ELEMENTS);
            List<Value> items = n == 0 ? List.of() : a.getValues(0, n);
            for (int i = 0; i < items.size(); i++) collect(label + "[" + i + "]", items.get(i), depth + 1, out);
        } else if (v instanceof ObjectReference o) {
            ReferenceType t = o.referenceType();
            if (t instanceof ClassType ct && ct.isEnum()) {
                Field name = t.fieldByName("name");
                if (name != null) collect(label, o.getValue(name), depth, out);
                return;
            }
            if (depth >= DEPTH) return;
            int n = 0;
            for (Field f : t.allFields()) {
                if (f.isStatic() || n++ >= MAX_FIELDS) continue;
                collect(label + "." + f.name(), o.getValue(f), depth + 1, out);
            }
        }
    }

    // ---------- a requestor without filters: it stops the thread that stores the value ----------

    @Override public String getSuspendPolicy() { return DebuggerSettings.SUSPEND_THREAD; }
    @Override public boolean isInstanceFiltersEnabled() { return false; }
    @Override public com.intellij.debugger.InstanceFilter[] getInstanceFilters() { return new com.intellij.debugger.InstanceFilter[0]; }
    @Override public boolean isCountFilterEnabled() { return false; }
    @Override public int getCountFilter() { return 0; }
    @Override public boolean isClassFiltersEnabled() { return false; }
    @Override public ClassFilter[] getClassFilters() { return ClassFilter.EMPTY_ARRAY; }
    @Override public ClassFilter[] getClassExclusionFilters() { return ClassFilter.EMPTY_ARRAY; }
}
