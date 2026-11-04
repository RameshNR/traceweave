package com.example.flowtracker.debug;

import com.intellij.debugger.engine.DebugProcessImpl;
import com.intellij.debugger.engine.events.SuspendContextCommandImpl;
import com.intellij.debugger.engine.requests.RequestManagerImpl;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.request.EventRequest;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Watch a local variable (or a parameter) of one method: the debugger looks at it on each line of the code it is
 * known in, without stopping the app, and keeps the thread paused only where it was set (or changed, set to null, or
 * set to a value that holds what is looked for). With {@code deep}, a change in the object's data counts too.
 * Nothing shows in the editor or the Breakpoints list.
 */
final class FlowLocalWatch extends FlowFieldWatch {
    private final String variable, methodText;
    /** The fields from the variable's object to the value watched (empty: the variable itself). */
    private List<String> path = List.of();
    private final int fromLine, toLine;
    private final String when;
    private final boolean deep;
    private final Supplier<List<Pattern>> patterns;
    /** What the variable held at the last line, per thread and call (stack depth). */
    private final Map<String, Seen> last = new ConcurrentHashMap<>();

    private record Seen(@Nullable Value value, String print) {}

    /**
     * @param jvmClass   the class the method is in (JVM name)
     * @param methodText the method as shown, e.g. "book()"
     * @param fromLine   the first line (1-based) the variable is known on; {@code toLine} the last
     * @param when       "SET", "CHANGE", "NULL" or "VALUE"
     */
    FlowLocalWatch(DebugProcessImpl process, String jvmClass, String methodText, String variable, int fromLine, int toLine,
                   String when, boolean deep, Supplier<List<Pattern>> patterns, Consumer<List<String>> onFind, Runnable onCheck) {
        super(process, List.of(jvmClass), null, (label, current, toBe) -> List.of(), onFind, onCheck);
        this.variable = variable;
        this.methodText = methodText;
        this.fromLine = fromLine;
        this.toLine = toLine;
        this.when = when;
        this.deep = deep;
        this.patterns = patterns;
    }

    /** Watches a value inside the variable's object: "email", "traveller.name". */
    FlowLocalWatch along(String fields) {
        path = fields == null || fields.isEmpty() ? List.of() : List.of(fields.split("\\."));
        return this;
    }

    /** The value at the end of the path from a variable's value (null when an object on the way is null). */
    private @Nullable Value follow(@Nullable Value v) {
        for (String f : path) {
            if (!(v instanceof com.sun.jdi.ObjectReference o)) return null;
            com.sun.jdi.Field field = o.referenceType().fieldByName(f);
            if (field == null) return null;
            v = o.getValue(field);
        }
        return v;
    }

    /** What is watched, as shown: "booking.email in book()". */
    private String label() {
        return (variable.equals("return") ? methodText + " returned" : variable) + (path.isEmpty() ? "" : "." + String.join(".", path))
                + (variable.equals("return") ? "" : " in " + methodText);
    }

    /** "return": what the method returns, looked at as it returns. */
    private boolean isReturn() {
        return variable.equals("return");
    }

    private String methodName() {
        return methodText.endsWith("()") ? methodText.substring(0, methodText.length() - 2) : methodText;
    }

    /** A breakpoint (of its own, not in the Breakpoints list) on each line of the variable's code. */
    @Override
    void watch(ReferenceType type) {
        if (stopped || !type.isPrepared() || !watched.add(type)) return;
        if (isReturn()) { // its returns: one request for the class's method exits
            if (!process.getVirtualMachineProxy().canGetMethodReturnValues()) return;
            com.sun.jdi.request.MethodExitRequest r = process.getRequestsManager().createMethodExitRequest(this);
            r.addClassFilter(type);
            process.getRequestsManager().enableRequest(r);
            return;
        }
        List<Location> lines;
        try {
            lines = type.allLineLocations();
        } catch (AbsentInformationException e) {
            return; // compiled without line numbers
        }
        RequestManagerImpl requests = process.getRequestsManager();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Location l : lines) {
            int line = l.lineNumber();
            if (line < fromLine || line > toLine || !seen.add(l.method().name() + l.method().signature() + ":" + line)) continue;
            EventRequest r = requests.createBreakpointRequest(this, l);
            requests.enableRequest(r);
        }
    }

    /** A line of the variable's code is about to run: stay paused only when the variable changed as asked. */
    @Override
    public boolean processLocatableEvent(SuspendContextCommandImpl action, LocatableEvent event) {
        if (stopped) return false;
        if (isReturn()) return returned(event);
        try {
            ThreadReference thread = event.thread();
            StackFrame frame = thread.frame(0);
            String key = thread.uniqueID() + ":" + thread.frameCount();
            LocalVariable v;
            try {
                v = frame.visibleVariableByName(variable);
            } catch (AbsentInformationException e) {
                return false; // compiled without the local variables' names
            }
            if (v == null) { // not declared yet on this line (a new call starts afresh)
                last.remove(key);
                return false;
            }
            onCheck.run();
            Value now = follow(frame.getValue(v));
            String print = deep ? fingerprint(now) : show(now);
            Seen before = last.put(key, new Seen(now, print));
            boolean first = before == null;
            boolean otherValue = first || !Objects.equals(before.value(), now);
            boolean dataChanged = !first && !otherValue && !before.print().equals(print);
            if (!otherValue && !dataChanged) return false;
            String label = label();
            String change = label + ": " + (first ? "(not set)" : show(before.value())) + " → " + show(now)
                    + (dataChanged ? " (its data changed)" : "");
            List<String> found = switch (when) {
                case "NULL" -> now == null ? List.of(change) : List.of();
                case "VALUE" -> {
                    List<Pattern> ps = patterns.get();
                    List<String> m = ps.isEmpty() ? List.of() : matches(variable + (path.isEmpty() ? "" : "." + String.join(".", path)), now, ps, false);
                    if (m.isEmpty()) yield List.of();
                    List<String> out = new ArrayList<>();
                    out.add(change);
                    out.addAll(m);
                    yield out;
                }
                // SET, CHANGE: given a value (the first one too), another one, or (deep) other data
                default -> "CHANGE".equals(when) && first && now == null ? List.of() : List.of(change);
            };
            if (found.isEmpty()) return false;
            onFind.accept(found);
            return true;
        } catch (Exception e) { // the thread went on, the frame is gone…: let it run
            return false;
        }
    }

    /** The method returns: stay paused (on its last line) only when what it returns is as asked. */
    private boolean returned(LocatableEvent event) {
        if (!(event instanceof com.sun.jdi.event.MethodExitEvent exit) || !exit.method().name().equals(methodName())) return false;
        try {
            onCheck.run();
            Value returned = exit.returnValue();
            if (returned instanceof com.sun.jdi.VoidValue) return false;
            Value now = follow(returned);
            String print = deep ? fingerprint(now) : show(now);
            Seen before = last.put("return", new Seen(now, print));
            boolean first = before == null;
            boolean changed = first || !Objects.equals(before.value(), now) || !before.print().equals(print);
            String change = label() + " " + show(now) + (first ? "" : " (before: " + show(before.value()) + ")");
            List<String> found = switch (when) {
                case "SET" -> List.of(change);
                case "CHANGE" -> changed ? List.of(change) : List.of();
                case "NULL" -> now == null ? List.of(change) : List.of();
                default -> {
                    List<Pattern> ps = patterns.get();
                    List<String> m = ps.isEmpty() ? List.of() : matches("return", now, ps, false);
                    if (m.isEmpty()) yield List.of();
                    List<String> out = new ArrayList<>();
                    out.add(change);
                    out.addAll(m);
                    yield out;
                }
            };
            if (found.isEmpty()) return false;
            onFind.accept(found);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** "~com.shop.BookingWorkflow#book#booking#24-27": the target of a local variable watch, or null when it isn't one. */
    static @Nullable String[] parse(String target) {
        String t = target.startsWith("*") ? target.substring(1) : target;
        if (!t.startsWith("~")) return null;
        String[] p = t.substring(1).split("#");
        if (p.length < 4 || p.length > 5 || !p[3].matches("\\d+-\\d+")) return null;
        return p;
    }
}
