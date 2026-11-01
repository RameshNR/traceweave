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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Debug values saved from the debugger (see {@link FlowSnapshot}), per project, in .idea/workspace.xml: newest
 * first, with their edits, and a Recycle Bin for deleted ones. Kept apart from the flows, so a value saved in
 * one flow can be compared with the same variable saved in another.
 */
@Service(Service.Level.PROJECT)
@State(name = "FlowTrackerDebugData", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class FlowDebugStore implements PersistentStateComponent<FlowDebugStore.State> {
    /** How many deleted values the Recycle Bin keeps. */
    static final int BIN_LIMIT = 200;

    public static class State {
        public List<FlowSnapshot> snapshots = new ArrayList<>();
        public List<FlowSnapshot> deleted = new ArrayList<>();
        /** Capture Data: "STOPS" (where the debugger stops) or "LINES" (every line it runs, stepping by itself). */
        public String captureMode = "STOPS";
        /** Capture Data: "ALL" (every variable of the frame) or "LINE" (only the variables used on the line). */
        public String captureScope = "ALL";
        /** How the list and the value are placed: "AUTO", "SIDE" (side by side) or "STACKED" (top and bottom). */
        public String layout = "AUTO";
    }

    /** True when Capture Data saves only the variables used on the line where the debugger is. */
    public boolean isCaptureLineOnly() {
        return "LINE".equals(state.captureScope);
    }

    public void setCaptureLineOnly(boolean lineOnly) {
        state.captureScope = lineOnly ? "LINE" : "ALL";
        fire();
    }

    public String getLayout() {
        return state.layout == null || state.layout.isEmpty() ? "AUTO" : state.layout;
    }

    public void setLayout(String layout) {
        state.layout = layout;
        fire();
    }

    /** True when Capture Data steps through every line by itself (false: it captures where the debugger stops). */
    public boolean isCaptureEveryLine() {
        return "LINES".equals(state.captureMode);
    }

    public void setCaptureEveryLine(boolean everyLine) {
        state.captureMode = everyLine ? "LINES" : "STOPS";
        fire();
    }

    private State state = new State();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static FlowDebugStore get(Project project) {
        return project.getService(FlowDebugStore.class);
    }

    /** Saved values, newest first. */
    public List<FlowSnapshot> getSnapshots() {
        return new ArrayList<>(state.snapshots);
    }

    /** The Recycle Bin, last deleted first. */
    public List<FlowSnapshot> getDeleted() {
        return new ArrayList<>(state.deleted);
    }

    public void add(FlowSnapshot s) {
        if (s.id.isEmpty()) s.id = UUID.randomUUID().toString();
        if (s.savedAt == 0) s.savedAt = System.currentTimeMillis();
        state.snapshots.add(0, s);
        fire();
    }

    /** Adds imported values among the saved ones, in saving order (newest first). */
    public void addAll(List<FlowSnapshot> list) {
        if (list.isEmpty()) return;
        state.snapshots.addAll(list);
        state.snapshots.sort(Comparator.comparingLong((FlowSnapshot s) -> s.savedAt).reversed());
        fire();
    }

    /** Moves values to the Recycle Bin. */
    public void delete(List<FlowSnapshot> list) {
        if (list.isEmpty()) return;
        for (FlowSnapshot s : list) {
            if (!state.snapshots.remove(s)) continue;
            s.deletedAt = System.currentTimeMillis();
            state.deleted.add(0, s);
        }
        while (state.deleted.size() > BIN_LIMIT) state.deleted.remove(state.deleted.size() - 1);
        fire();
    }

    /** Takes values out of the Recycle Bin, back among the saved ones (in saving order). */
    public void restore(List<FlowSnapshot> list) {
        for (FlowSnapshot s : list) {
            if (!state.deleted.remove(s)) continue;
            s.deletedAt = 0;
            state.snapshots.add(s);
        }
        state.snapshots.sort(Comparator.comparingLong((FlowSnapshot s) -> s.savedAt).reversed());
        fire();
    }

    /** Deletes values from the Recycle Bin for good. */
    public void purge(List<FlowSnapshot> list) {
        if (state.deleted.removeAll(list)) fire();
    }

    /**
     * Changes a saved value by hand (e.g. to note what it should have been); the change is kept in the
     * snapshot's edit history.
     *
     * @param path child indexes from the root, see {@link #at}
     */
    public void editValue(FlowSnapshot s, String path, String newValue) {
        FlowValue v = at(s.value, path);
        if (v == null || v.value.equals(newValue)) return;
        keepOriginal(s);
        s.edits.add(new FlowValueEdit(path, label(s.value, path), v.value, newValue));
        v.value = newValue;
        syncEntry(s, path);
        fire();
    }

    /**
     * After a map entry's key or value changed: the entry's own text ("jaipur" -> 7, as the debugger shows it) says
     * the same, so the tree, the search and Compare all see the new key or value.
     */
    private static void syncEntry(FlowSnapshot s, String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return;
        FlowValue entry = at(s.value, path.substring(0, dot));
        if (entry == null || !FlowValueText.isMapEntry(entry)) return;
        int up = path.substring(0, dot).lastIndexOf('.');
        FlowValue map = at(s.value, up < 0 ? "" : path.substring(0, up));
        FlowValueText.fixEntryText(entry, map);
    }

    /** A list's or map's "size = 6" follows its children. */
    private static void syncSize(FlowValue container) {
        container.value = FlowValueText.sized(container.value, container.children.size());
    }

    /** Renames a field (a map key, a field name), kept in its history. */
    public void editName(FlowSnapshot s, String path, String newName) {
        FlowValue v = at(s.value, path);
        if (v == null || v.name.equals(newName) || newName.isBlank()) return;
        keepOriginal(s);
        FlowValueEdit e = new FlowValueEdit(path, label(s.value, path), v.name, newName);
        e.kind = "NAME";
        s.edits.add(e);
        v.name = newName;
        fire();
    }

    /** Adds a field under a value ("" = the saved value itself); returns its path. */
    public String addField(FlowSnapshot s, String parentPath, String name, String value) {
        FlowValue v = new FlowValue(name);
        v.value = value;
        return addField(s, parentPath, v);
    }

    /** Adds a field (with what is in it: a map entry, a list element, an object) under a value; returns its path. */
    public String addField(FlowSnapshot s, String parentPath, FlowValue v) {
        FlowValue parent = at(s.value, parentPath);
        if (parent == null) return "";
        keepOriginal(s);
        parent.children.add(v);
        syncSize(parent);
        String path = (parentPath.isEmpty() ? "" : parentPath + ".") + (parent.children.size() - 1);
        FlowValueEdit e = new FlowValueEdit(path, label(s.value, path), "", v.name + " = " + v.value);
        e.kind = "ADD";
        s.edits.add(e);
        fire();
        return path;
    }

    /** Removes a field (not the saved value itself), kept in the history so Revert can put it back. */
    public void removeField(FlowSnapshot s, String path) {
        if (path == null || path.isEmpty()) return;
        int dot = path.lastIndexOf('.');
        FlowValue parent = at(s.value, dot < 0 ? "" : path.substring(0, dot));
        FlowValue v = at(s.value, path);
        if (parent == null || v == null) return;
        keepOriginal(s);
        FlowValueEdit e = new FlowValueEdit(path, label(s.value, path), v.name + " = " + v.value, "(removed)");
        e.kind = "REMOVE";
        e.item = v.copy();
        parent.children.remove(v);
        syncSize(parent);
        s.edits.add(e);
        fire();
    }

    /** From the first change on, the data as it was saved is kept (for Reset). */
    private static void keepOriginal(FlowSnapshot s) {
        if (s.original == null) s.original = s.value.copy();
    }

    /**
     * Puts a whole new value in place (edited as JSON or text, or Reset), kept in the history with the value it
     * replaced, so Revert puts that back.
     * @param how e.g. "edited as JSON" or "reset to the saved data"
     */
    public void replaceValue(FlowSnapshot s, FlowValue value, String how) {
        if (FlowValueText.toText(value).equals(FlowValueText.toText(s.value))) return;
        keepOriginal(s);
        FlowValueEdit e = new FlowValueEdit("", s.value.name, "(the data before)", how);
        e.kind = "REPLACE";
        e.item = s.value.copy();
        value.name = s.value.name;
        s.value = value;
        s.edits.add(e);
        fire();
    }

    /** Reset: back to the data as it was saved (in the history too, so it can be reverted). */
    public void reset(FlowSnapshot s) {
        if (s.original == null) return;
        replaceValue(s, s.original.copy(), "reset to the saved data");
    }

    /** True when the value was changed since it was saved. */
    public static boolean isChanged(FlowSnapshot s) {
        return s.original != null && !FlowValueText.toText(s.original).equals(FlowValueText.toText(s.value));
    }

    /** Undoes an edit (as a new edit, so the history stays complete): the old value or name, or the field back or gone. */
    public void revert(FlowSnapshot s, FlowValueEdit edit) {
        switch (edit.kind == null ? "" : edit.kind) {
            case "REPLACE" -> {
                if (edit.item != null) replaceValue(s, edit.item.copy(), "reverted: " + edit.after);
            }
            case "NAME" -> editName(s, edit.path, edit.before);
            case "ADD" -> {
                FlowValue v = at(s.value, edit.path);
                if (v != null && edit.after.startsWith(v.name + " = ")) removeField(s, edit.path);
            }
            case "REMOVE" -> {
                if (edit.item == null) return;
                int dot = edit.path.lastIndexOf('.');
                String parentPath = dot < 0 ? "" : edit.path.substring(0, dot);
                FlowValue parent = at(s.value, parentPath);
                if (parent == null) return;
                int index;
                try {
                    index = Math.min(Integer.parseInt(edit.path.substring(dot + 1)), parent.children.size());
                } catch (NumberFormatException ex) {
                    index = parent.children.size();
                }
                parent.children.add(index, edit.item.copy());
                syncSize(parent);
                FlowValueEdit back = new FlowValueEdit(edit.path, label(s.value, edit.path), "(removed)", edit.before);
                back.kind = "ADD";
                s.edits.add(back);
                fire();
            }
            default -> editValue(s, edit.path, edit.before);
        }
    }

    /** Other saved values of the same variable at the same line (what Compare offers), newest first. */
    public List<FlowSnapshot> sameVariable(FlowSnapshot s) {
        List<FlowSnapshot> out = new ArrayList<>();
        for (FlowSnapshot o : state.snapshots) if (o != s && o.sameVariableAs(s)) out.add(o);
        return out;
    }

    /** The value at a path of child indexes ("0.3"; "" = the root), or null when there is none. */
    public static @Nullable FlowValue at(FlowValue root, String path) {
        FlowValue v = root;
        if (path == null || path.isEmpty()) return v;
        for (String part : path.split("\\.")) {
            int i;
            try {
                i = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return null;
            }
            if (i < 0 || i >= v.children.size()) return null;
            v = v.children.get(i);
        }
        return v;
    }

    /** The names along a path, for showing, e.g. "session › attributes › \"cart\"". */
    public static String label(FlowValue root, String path) {
        StringBuilder sb = new StringBuilder(root.name);
        FlowValue v = root;
        if (path == null || path.isEmpty()) return sb.toString();
        for (String part : path.split("\\.")) {
            int i = Integer.parseInt(part);
            if (i < 0 || i >= v.children.size()) break;
            v = v.children.get(i);
            sb.append(" › ").append(v.name);
        }
        return sb.toString();
    }

    public void addListener(Runnable r) { listeners.add(r); }
    public void removeListener(Runnable r) { listeners.remove(r); }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State s) {
        state = s;
        fire();
    }
}
