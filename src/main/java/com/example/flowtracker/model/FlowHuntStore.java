package com.example.flowtracker.model;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The history of Stop at Value: every hunt (the values looked for, how, when) and every place it stopped the debugger
 * (when, where, in which thread, what matched). Saved with the project; hunts can be renamed and deleted.
 */
@Service(Service.Level.PROJECT)
@State(name = "TraceWeaveStopAtValue", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class FlowHuntStore implements PersistentStateComponent<FlowHuntStore.State> {

    /** A hunt: its name, the values (one per entry), whether all must be found together, how it looked. */
    public static final class Hunt {
        public String id = "";
        public String name = "";
        public List<String> values = new ArrayList<>();
        /** Values switched off: kept in the hunt but not looked for. */
        public List<String> off = new ArrayList<>();
        /** true: every value in the same stop; false: any of them. */
        public boolean all;
        /** FlowValueHunt.Mode's name. */
        public String mode = "";
        /** After a find, it goes on looking (every find is listed) until it is stopped. */
        public boolean keepGoing = true;
        /** Watch a field or object: the class (JVM name), and "#field" for one field ("" = every field). */
        public String target = "";
        /** Watch a field or object: when it stops ("SET", "CHANGE", "NULL", "VALUE"; with VALUE, the values say which). */
        public String when = "";
        public long startedAt;
        /** "HUNTING", "FOUND", "NOT_FOUND" or "STOPPED". */
        public String status = "HUNTING";
        /** How many stops (or lines) were searched. */
        public int checked;
        public List<Stop> stops = new ArrayList<>();

        public String valuesText() {
            if (isWatch()) return targetText() + (when.equals("VALUE") ? " = " + String.join(" | ", active()) : "");
            return String.join(all ? " + " : " | ", active());
        }

        /** True for Watch a Field or Object (false for Stop at Value). */
        public boolean isWatch() {
            return !target.isEmpty();
        }

        /** The watched class and field as shown: "BookingDto.status", or "BookingDto" for every field. */
        public String targetText() {
            String t = isDeep() ? target.substring(1) : target;
            if (t.startsWith("~")) { // a local variable: "~jvmClass#method#variable#lines"
                String[] p = t.substring(1).split("#");
                if (p.length >= 3) {
                    String simple = p[0].substring(Math.max(p[0].lastIndexOf('.'), p[0].lastIndexOf('$')) + 1);
                    return simple + "." + p[1] + "()." + p[2] + (p.length >= 5 && !p[4].isEmpty() ? "." + p[4] : "");
                }
            }
            int hash = t.indexOf('#');
            String cls = hash < 0 ? t : t.substring(0, hash);
            String simple = cls.substring(Math.max(cls.lastIndexOf('.'), cls.lastIndexOf('$')) + 1);
            return hash < 0 ? simple : simple + "." + t.substring(hash + 1);
        }

        /** True for a watch on a local variable (or a parameter) of one method. */
        public boolean isLocal() {
            return target.startsWith("~") || target.startsWith("*~");
        }

        /** True when the object's data is watched too (the fields of the object, and of the objects inside it). */
        public boolean isDeep() {
            return target.startsWith("*");
        }

        /** The values looked for (those not switched off). */
        public List<String> active() {
            List<String> out = new ArrayList<>(values);
            out.removeAll(off);
            return out;
        }
    }

    /** A place a hunt stopped the debugger, and what matched there ("label = value"). */
    public static final class Stop {
        public long at;
        public String fileUrl = "";
        public String fileName = "";
        public int line;
        public String method = "";
        public String thread = "";
        public List<String> matches = new ArrayList<>();
        /** The paused code's variables at that moment, as JSON ("" while they are read, or when they couldn't be). */
        public String snapshot = "";
        /** The same variables with their types, as the debugger shows them (null for stops saved before they were kept). */
        public @Nullable FlowValue variables;

        public String where() {
            return fileName + ":" + line + (method.isEmpty() ? "" : "  " + method);
        }
    }

    /** Something deleted: a whole record, or stops of a record. Kept until restored or deleted for good. */
    public static final class Binned {
        public long deletedAt;
        /** The record deleted (null when stops of a record were). */
        public @Nullable Hunt hunt;
        /** The record the stops were in, and its name then. */
        public String huntId = "";
        public String huntName = "";
        public List<Stop> stops = new ArrayList<>();

        /** What it is, e.g. "Hunt 9 (4 stops)" or "2 stops of Hunt 9". */
        public String label() {
            if (hunt != null) return hunt.name + " (" + hunt.stops.size() + (hunt.stops.size() == 1 ? " stop)" : " stops)");
            return stops.size() + (stops.size() == 1 ? " stop of " : " stops of ") + huntName;
        }
    }

    public static final class State {
        public List<Hunt> hunts = new ArrayList<>();
        /** The Recycle Bin: records and stops deleted, the latest first. */
        public List<Binned> bin = new ArrayList<>();
        /** Fields pinned to the top of the variables tree: "Type.field" (e.g. "BookingDto.reference"), or "frame.name". */
        public List<String> pinned = new ArrayList<>();
        public int made;
    }

    private State state = new State();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static FlowHuntStore get(Project project) {
        return project.getService(FlowHuntStore.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State s) {
        state = s;
        // a hunt still marked as running when the IDE closed isn't running any more
        for (Hunt h : state.hunts) if ("HUNTING".equals(h.status)) h.status = h.stops.isEmpty() ? "STOPPED" : "FOUND";
        fire();
    }

    /** The hunts, the newest first. */
    public List<Hunt> hunts() {
        return new ArrayList<>(state.hunts);
    }

    public @Nullable Hunt hunt(String id) {
        for (Hunt h : state.hunts) if (h.id.equals(id)) return h;
        return null;
    }

    /** A new hunt that is looking now, named "Hunt n: the values" (rename it later). */
    public Hunt start(List<String> values, boolean all, String mode) {
        Hunt h = create(values, all, mode, true, "", "");
        begin(h);
        return h;
    }

    /**
     * A new record (not looking yet): "Hunt n: the values" for Stop at Value, "Watch n: Class.field" for a watch.
     *
     * @param target for a watch: the class (JVM name) and "#field" for one field; "" for Stop at Value
     * @param when   for a watch: "SET", "CHANGE", "NULL" or "VALUE"
     */
    public Hunt create(List<String> values, boolean all, String mode, boolean keepGoing, String target, String when) {
        return create("", values, all, mode, keepGoing, target, when);
    }

    /** @param name the record's name ("" for the next default one, e.g. "Hunt 8") */
    public Hunt create(String name, List<String> values, boolean all, String mode, boolean keepGoing, String target, String when) {
        Hunt h = new Hunt();
        h.id = java.util.UUID.randomUUID().toString();
        boolean watch = target != null && !target.isEmpty();
        String fallback = nextName(watch);
        state.made++;
        set(h, values, all, mode, keepGoing, target, when);
        h.name = name == null || name.isBlank() ? fallback : name.strip();
        h.status = "STOPPED";
        h.startedAt = System.currentTimeMillis();
        state.hunts.add(0, h);
        fire();
        return h;
    }

    /** The name a new record gets unless one is typed: "Hunt 8", "Watch 8" (not its values: they can change). */
    public String nextName(boolean watch) {
        return (watch ? "Watch " : "Hunt ") + (state.made + 1);
    }

    /** Edit: new values and settings for a record (its stops stay). */
    public void update(Hunt h, List<String> values, boolean all, String mode, boolean keepGoing, String target, String when) {
        set(h, values, all, mode, keepGoing, target, when);
        fire();
    }

    private static void set(Hunt h, List<String> values, boolean all, String mode, boolean keepGoing, String target, String when) {
        h.values = new ArrayList<>(values);
        h.off.retainAll(h.values);
        h.all = all;
        h.mode = mode;
        h.keepGoing = keepGoing;
        h.target = target == null ? "" : target;
        h.when = when == null ? "" : when;
    }

    /** The record that looks for the same thing already (same values, same way; or the same field and condition). */
    public @Nullable Hunt same(List<String> values, String mode, String target, String when) {
        for (Hunt h : state.hunts) {
            if (h.mode.equals(mode) && h.target.equals(target == null ? "" : target) && h.when.equals(when == null ? "" : when)
                    && new java.util.HashSet<>(h.values).equals(new java.util.HashSet<>(values))) {
                return h;
            }
        }
        return null;
    }

    /** The record is looking (again). */
    public void begin(Hunt h) {
        h.status = "HUNTING";
        fire();
    }

    /** Deletes stops of a record: they go to the Recycle Bin. */
    public void removeStops(Hunt h, List<Stop> stops) {
        List<Stop> gone = new ArrayList<>(stops);
        gone.retainAll(h.stops);
        if (gone.isEmpty()) return;
        h.stops.removeAll(gone);
        if (h.stops.isEmpty() && "FOUND".equals(h.status)) h.status = "STOPPED";
        Binned b = new Binned();
        b.deletedAt = System.currentTimeMillis();
        b.huntId = h.id;
        b.huntName = h.name;
        b.stops = gone;
        state.bin.add(0, b);
        fire();
    }

    /** The variables read at a stop (as JSON). */
    public void setSnapshot(Stop s, String json) {
        s.snapshot = json == null ? "" : json;
        fire();
    }

    /** The variables read at a stop: kept with their types (the tree) and as JSON. */
    public void setVariables(Stop s, FlowValue variables) {
        s.variables = variables;
        s.snapshot = FlowValueText.toJson(variables);
        fire();
    }

    public void addStop(Hunt h, Stop s) {
        h.stops.add(s);
        h.status = "FOUND";
        fire();
    }

    public void checked(Hunt h, int checked) {
        h.checked = checked;
        fire();
    }

    /** The hunt is over: found (it has stops), not found (it looked as far as it could), or stopped by the user. */
    public void end(Hunt h, boolean gaveUp) {
        if (!"HUNTING".equals(h.status) && !"FOUND".equals(h.status)) return;
        h.status = !h.stops.isEmpty() ? "FOUND" : gaveUp ? "NOT_FOUND" : "STOPPED";
        fire();
    }

    /** Adds a value to look for (nothing when it is there already); returns true when added. */
    public boolean addValue(Hunt h, String value) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty() || h.values.contains(v)) return false;
        h.values.add(v);
        fire();
        return true;
    }

    public void removeValue(Hunt h, String value) {
        if (h.values.remove(value) | h.off.remove(value)) fire();
    }

    /** Switches a value on (looked for) or off (kept, not looked for). */
    public void setOn(Hunt h, String value, boolean on) {
        if (!h.values.contains(value)) return;
        boolean changed = on ? h.off.remove(value) : !h.off.contains(value) && h.off.add(value);
        if (changed) fire();
    }

    public void rename(Hunt h, String name) {
        if (name == null || name.isBlank()) return;
        h.name = name.strip();
        fire();
    }

    /** Deletes records: they go to the Recycle Bin (with their stops). */
    public void delete(List<Hunt> hunts) {
        for (Hunt h : hunts) {
            if (!state.hunts.remove(h)) continue;
            Binned b = new Binned();
            b.deletedAt = System.currentTimeMillis();
            b.hunt = h;
            b.huntId = h.id;
            b.huntName = h.name;
            if (!"FOUND".equals(h.status) && !"NOT_FOUND".equals(h.status)) h.status = h.stops.isEmpty() ? "STOPPED" : "FOUND";
            state.bin.add(0, b);
        }
        fire();
    }

    /** The Recycle Bin, the latest first. */
    public List<Binned> bin() {
        return new ArrayList<>(state.bin);
    }

    /**
     * Puts something deleted back: a record into the list, stops into their record (in the order they were found).
     * False when the stops' record isn't there any more (it stays in the bin).
     */
    public boolean restore(Binned b) {
        if (b.hunt != null) {
            if (hunt(b.hunt.id) == null) state.hunts.add(0, b.hunt);
        } else {
            Hunt h = hunt(b.huntId);
            if (h == null) return false;
            for (Stop s : b.stops) if (!h.stops.contains(s)) h.stops.add(s);
            h.stops.sort(java.util.Comparator.comparingLong(s -> s.at));
            if (!h.stops.isEmpty() && "STOPPED".equals(h.status)) h.status = "FOUND";
        }
        state.bin.remove(b);
        fire();
        return true;
    }

    /** Deletes for good what is in the bin. */
    public void purge(List<Binned> gone) {
        if (state.bin.removeAll(gone)) fire();
    }

    /** The fields pinned to the top of the variables tree. */
    public java.util.Set<String> pinned() {
        return new java.util.LinkedHashSet<>(state.pinned);
    }

    /** Pins a field to the top of the variables tree (in every stop), or unpins it. */
    public void togglePin(String key) {
        if (!state.pinned.remove(key)) state.pinned.add(key);
        fire();
    }

    public void addListener(Runnable r) { listeners.add(r); }

    public void removeListener(Runnable r) { listeners.remove(r); }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }
}
