package com.example.flowtracker.model;

import com.example.flowtracker.code.FlowSync;
import com.example.flowtracker.diagram.FlowDiagram;
import com.example.flowtracker.diagram.FlowDiagramLook;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-project storage. Saved locally in .idea/workspace.xml, so it survives IDE restarts.
 * A project holds several named flows; everything that reads or changes lines works on the active one.
 */
@Service(Service.Level.PROJECT)
@State(name = "FlowTrackerStore", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class FlowStore implements PersistentStateComponent<FlowStore.State> {

    /** One named list of saved lines, e.g. "Checkout bug". */
    public static class Flow {
        public String name = "Flow 1";
        public List<FlowEntry> entries = new ArrayList<>();
        /** Its Flow Diagram's look: box positions, colors, text formats, notes and lines (see FlowDiagram#looks). */
        public List<FlowDiagramLook> diagram = new ArrayList<>();
        /** Its color (e.g. "#3574F0"), shown on its Flow Diagram tab; "" = none chosen yet. */
        public String color = "";

        public Flow() {}

        Flow(String name, List<FlowEntry> entries) {
            this.name = name;
            this.entries = new ArrayList<>(entries);
        }
    }

    /** Something deleted: lines from a flow, or a whole flow. Kept until restored or purged. */
    public static class BinItem {
        /** What happened, e.g. "Delete BookingService.java:21" or "Clear all (5 lines)". */
        public String label = "";
        /** The flow the lines were in (for a whole deleted flow: its name). */
        public String flowName = "";
        /** When it was deleted (epoch milliseconds). */
        public long deletedAt;
        /** true = a whole flow was deleted; restoring brings it back as a flow of its own. */
        public boolean wholeFlow;
        public List<FlowEntry> entries = new ArrayList<>();
        /** Where each line was in its flow, so a restore puts it back at the same place. */
        public List<Integer> positions = new ArrayList<>();

        public BinItem() {}

        BinItem(String label, String flowName, boolean wholeFlow, List<FlowEntry> entries, List<Integer> positions) {
            this.label = label;
            this.flowName = flowName;
            this.wholeFlow = wholeFlow;
            this.deletedAt = System.currentTimeMillis();
            this.entries = new ArrayList<>(entries);
            this.positions = new ArrayList<>(positions);
        }
    }

    public static class State {
        /** Lines saved before named flows existed; moved into the first flow on load. */
        public List<FlowEntry> entries = new ArrayList<>();
        public List<Flow> flows = new ArrayList<>();
        /** Index of the flow shown in the panel. */
        public int activeFlow = 0;
        /** true = new lines are inserted at the top, false = appended at the end. */
        public boolean addAtTop = false;
        /** true = Flat View is enabled. */
        public boolean flatView = false;
        /** true = lines are kept in call order automatically (caller first, then the methods it calls). */
        public boolean syncFiles = true;
        /** true = copying in Flat View produces the compact project / file / method log. */
        public boolean optimizeFlatLog = false;
        /** true = Copy produces a Markdown document (headings, code blocks, notes, flow diagram). */
        public boolean copyMarkdown = true;
        /** true = the Markdown copy draws a Mermaid sequence diagram instead of a flowchart. */
        public boolean sequenceDiagram = true;
        /** true = the toolbar's Copy button copies every flow, not only the active one. */
        public boolean copyAllFlows = true;
        /** true = the Original Log nests each called method's lines under the line that calls it. */
        public boolean nestedCallLog = true;
        /** Deleted lines and flows, newest first. */
        public List<BinItem> recycleBin = new ArrayList<>();
    }

    private State state = new State();
    /** Calls between saved methods, from the last background analysis (not saved; see FlowSync). */
    private volatile FlowSync.Calls calls = FlowSync.Calls.NONE;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** The line the walk-through (Next / Previous Step) is on; not saved. */
    private @Nullable FlowEntry walkEntry;
    private final List<Runnable> walkListeners = new CopyOnWriteArrayList<>();
    /** Each flow's Flow Diagram history, kept while the IDE runs (not saved). */
    private final Map<Flow, FlowDiagram.History> diagramHistories = new java.util.IdentityHashMap<>();

    /**
     * One undoable add or delete: the lines involved and where they were (ascending positions). Undo / redo
     * replays just these lines, so moves, notes and sorting done in between are kept. A delete also has
     * its Recycle Bin item: undoing the delete takes the item back out of the bin.
     * <p>
     * A Flow Diagram change ({@link #applyDiagram}) instead keeps the whole flow (and its diagram's look) before
     * and after it.
     */
    private record Change(String label, boolean added, List<FlowEntry> lines, List<Integer> positions,
                          @Nullable BinItem bin, @Nullable List<FlowEntry> before, @Nullable List<FlowEntry> after,
                          @Nullable List<FlowDiagramLook> looksBefore, @Nullable List<FlowDiagramLook> looksAfter) {
        Change(String label, boolean added, List<FlowEntry> lines, List<Integer> positions, @Nullable BinItem bin) {
            this(label, added, lines, positions, bin, null, null, null, null);
        }
    }

    private static final int HISTORY_LIMIT = 100;
    /** Oldest Recycle Bin items are dropped beyond this. */
    private static final int BIN_LIMIT = 500;
    private final Deque<Change> undoStack = new ArrayDeque<>();
    private final Deque<Change> redoStack = new ArrayDeque<>();

    public static FlowStore get(Project project) {
        return project.getService(FlowStore.class);
    }

    /** The active flow (there is always at least one). */
    private Flow active() {
        if (state.flows.isEmpty()) state.flows.add(new Flow());
        if (state.activeFlow < 0 || state.activeFlow >= state.flows.size()) state.activeFlow = 0;
        return state.flows.get(state.activeFlow);
    }

    private List<FlowEntry> lines() {
        return active().entries;
    }

    public List<FlowEntry> getEntries() {
        return new ArrayList<>(lines());
    }

    /** @param line 1-based line number (lines added in the Flow Diagram have none, so they never match) */
    public boolean contains(String fileUrl, int line) {
        if (line <= 0) return false;
        for (FlowEntry e : lines()) {
            if (e.line == line && fileUrl.equals(e.fileUrl)) return true;
        }
        return false;
    }

    /** Adds the entry (top or bottom, see {@link #isAddAtTop()}) unless file + line is already stored. */
    public boolean add(FlowEntry entry) {
        return add(entry, state.addAtTop);
    }

    /** Adds the entry at the top or bottom unless file + line is already stored. */
    public boolean add(FlowEntry entry, boolean atTop) {
        if (contains(entry.fileUrl, entry.line)) return false;
        int position = atTop ? 0 : lines().size();
        lines().add(position, entry);
        record(new Change("Add " + describe(entry), true, List.of(entry), List.of(position), null));
        fire();
        return true;
    }

    /**
     * Adds several lines as one undoable step (Undo removes them all), at the top or bottom, keeping their
     * order. Lines whose file + line is stored already are skipped. Returns how many were added.
     */
    public int addAll(List<FlowEntry> entries, boolean atTop) {
        List<FlowEntry> added = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        int base = atTop ? 0 : lines().size();
        for (FlowEntry e : entries) {
            if (contains(e.fileUrl, e.line) || added.stream().anyMatch(a -> a.line == e.line && a.fileUrl.equals(e.fileUrl))) continue;
            lines().add(base + added.size(), e);
            positions.add(base + added.size());
            added.add(e);
        }
        if (added.isEmpty()) return 0;
        String label = added.size() == 1 ? "Add " + describe(added.get(0)) : "Add " + added.size() + " lines";
        record(new Change(label, true, added, positions, null));
        fire();
        return added.size();
    }

    /**
     * Puts lines right after another line of the flow (e.g. the calls found between two lines), as one undoable step,
     * in their order. Lines already in the flow are skipped. Returns how many were added.
     */
    public int insertAfter(FlowEntry anchor, List<FlowEntry> entries, String label) {
        int at = lines().indexOf(anchor);
        if (at < 0) return 0;
        List<FlowEntry> added = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        for (FlowEntry e : entries) {
            if (contains(e.fileUrl, e.line) || added.stream().anyMatch(a -> a.line == e.line && a.fileUrl.equals(e.fileUrl))) continue;
            int pos = at + 1 + added.size();
            lines().add(pos, e);
            positions.add(pos);
            added.add(e);
        }
        if (added.isEmpty()) return 0;
        record(new Change(label, true, added, positions, null));
        fire();
        return added.size();
    }

    public void remove(Collection<FlowEntry> toRemove) {
        Change c = removal(toRemove, null);
        if (c == null) return;
        lines().removeAll(c.lines);
        toBin(c.bin);
        record(c);
        fire();
    }

    public void clear() {
        Change c = removal(lines(), "Clear all (" + lines().size() + " lines)");
        if (c == null) return;
        lines().clear();
        toBin(c.bin);
        record(c);
        fire();
    }

    /**
     * Replaces the flow's lines with the result of the Flow Diagram window, as one undoable step. Lines it
     * no longer has go to the Recycle Bin.
     *
     * @param after   the new lines (unchanged lines are the same objects, changed ones are copies)
     * @param removed lines of the flow that {@code after} leaves out
     */
    public void applyDiagram(List<FlowEntry> after, List<FlowEntry> removed, String label, List<FlowDiagramLook> looks) {
        List<FlowEntry> before = new ArrayList<>(lines());
        List<FlowDiagramLook> looksBefore = new ArrayList<>(active().diagram);
        BinItem bin = null;
        if (!removed.isEmpty()) {
            List<Integer> positions = new ArrayList<>();
            for (FlowEntry e : removed) positions.add(Math.max(0, before.indexOf(e)));
            String binLabel = label + " (" + removed.size() + (removed.size() == 1 ? " line)" : " lines)");
            bin = new BinItem(binLabel, active().name, false, removed, positions);
        }
        active().entries = new ArrayList<>(after);
        active().diagram = new ArrayList<>(looks);
        toBin(bin);
        record(new Change(label, false, List.of(), List.of(), bin, before, new ArrayList<>(after), looksBefore, new ArrayList<>(looks)));
        fire();
    }

    /** The active flow's Flow Diagram look (box positions, colors, formats, notes, lines). */
    public List<FlowDiagramLook> getDiagramLooks() {
        return new ArrayList<>(active().diagram);
    }

    /** Where a flow is in the list (-1 when there is none of that name). */
    public int indexOfFlow(String name) {
        return getFlowNames().indexOf(name);
    }

    /** A flow's lines (a copy; empty when there is none of that name). */
    public List<FlowEntry> getEntriesOf(String name) {
        int i = indexOfFlow(name);
        return i < 0 ? List.of() : new ArrayList<>(state.flows.get(i).entries);
    }

    /** A flow's Flow Diagram look (a copy). */
    public List<FlowDiagramLook> getDiagramLooksOf(String name) {
        int i = indexOfFlow(name);
        return i < 0 ? List.of() : new ArrayList<>(state.flows.get(i).diagram);
    }

    /** A flow's color ("" when none was chosen). */
    public String getFlowColor(String name) {
        int i = indexOfFlow(name);
        String c = i < 0 ? null : state.flows.get(i).color;
        return c == null ? "" : c;
    }

    public void setFlowColor(String name, String color) {
        int i = indexOfFlow(name);
        if (i < 0) return;
        state.flows.get(i).color = color == null ? "" : color;
        fire();
    }

    /** Keeps the diagram's look without an undo step (e.g. the layout of a diagram opened for the first time). */
    public void setDiagramLooks(List<FlowDiagramLook> looks) {
        active().diagram = new ArrayList<>(looks);
    }

    /** The active flow's Flow Diagram history, or null when its diagram hasn't been opened yet. */
    public @Nullable FlowDiagram.History getDiagramHistory() {
        return diagramHistories.get(active());
    }

    public void setDiagramHistory(FlowDiagram.History history) {
        diagramHistories.put(active(), history);
    }

    // ---------- Recycle Bin ----------

    /** Deleted lines and flows, newest first. */
    public List<BinItem> getRecycleBin() {
        return new ArrayList<>(state.recycleBin);
    }

    private void toBin(@Nullable BinItem item) {
        if (item == null) return;
        state.recycleBin.add(0, item);
        while (state.recycleBin.size() > BIN_LIMIT) state.recycleBin.remove(state.recycleBin.size() - 1);
    }

    /**
     * Brings a deleted item back. A whole flow comes back as a new flow; lines go back into the flow they
     * were deleted from (or the active one, if that flow is gone), at their old positions, and that flow is
     * shown. Lines that are in the flow again already are skipped. Returns how many lines came back.
     */
    public int restore(BinItem item) {
        if (!state.recycleBin.remove(item)) return 0;
        if (item.wholeFlow) {
            newFlow(item.flowName, item.entries);
            return item.entries.size();
        }
        int target = -1;
        for (int i = 0; i < state.flows.size(); i++) {
            if (state.flows.get(i).name.equals(item.flowName)) { target = i; break; }
        }
        if (target >= 0 && target != state.activeFlow) {
            state.activeFlow = target;
            resetForNewFlow();
        }
        int restored = 0;
        for (int i = 0; i < item.entries.size(); i++) {
            FlowEntry e = item.entries.get(i);
            if (lines().contains(e) || contains(e.fileUrl, e.line)) continue;
            int at = i < item.positions.size() ? item.positions.get(i) : lines().size();
            lines().add(Math.min(at, lines().size()), e);
            restored++;
        }
        // Undo / redo entries that still point at this item would put it back in the bin.
        forgetBin(item);
        fire();
        return restored;
    }

    /** Deletes an item for good. */
    public void purge(Collection<BinItem> items) {
        if (!state.recycleBin.removeAll(items)) return;
        items.forEach(this::forgetBin);
        fire();
    }

    public void emptyRecycleBin() {
        if (state.recycleBin.isEmpty()) return;
        state.recycleBin.forEach(this::forgetBin);
        state.recycleBin.clear();
        fire();
    }

    /** Drops undo / redo entries for a bin item that has been restored or purged. */
    private void forgetBin(BinItem item) {
        undoStack.removeIf(c -> c.bin == item);
        redoStack.removeIf(c -> c.bin == item);
    }

    // ---------- named flows ----------

    public List<String> getFlowNames() {
        active();
        return state.flows.stream().map(f -> f.name).toList();
    }

    public int getActiveFlowIndex() {
        active();
        return state.activeFlow;
    }

    public String getActiveFlowName() {
        return active().name;
    }

    /** A copy of every flow (name and lines), in menu order. */
    public List<Flow> getAllFlows() {
        active();
        return state.flows.stream().map(f -> new Flow(f.name, f.entries)).toList();
    }

    /** Shows another flow. Undo history belongs to the flow it was made in, so it starts empty. */
    public void switchFlow(int index) {
        if (index < 0 || index >= state.flows.size() || index == state.activeFlow) return;
        state.activeFlow = index;
        resetForNewFlow();
        fire();
    }

    /** Adds an empty flow (or one with the given lines) and shows it. The name is made unique. */
    public void newFlow(String name, List<FlowEntry> entries) {
        newFlow(name, entries, List.of());
    }

    /** @param looks its Flow Diagram's look (e.g. from an imported .flow.json file) */
    public void newFlow(String name, List<FlowEntry> entries, List<FlowDiagramLook> looks) {
        Flow flow = new Flow(uniqueName(name), entries);
        flow.diagram = new ArrayList<>(looks);
        state.flows.add(flow);
        state.activeFlow = state.flows.size() - 1;
        resetForNewFlow();
        fire();
    }

    /**
     * Moves a flow to another place in the list (dragged in the Flows popup). Copy All Flows follows this
     * order. The active flow stays active.
     */
    public void moveFlow(int from, int to) {
        int size = state.flows.size();
        if (from == to || from < 0 || to < 0 || from >= size || to >= size) return;
        Flow shown = active();
        state.flows.add(to, state.flows.remove(from));
        state.activeFlow = state.flows.indexOf(shown);
        fire();
    }

    public void renameFlow(String name) {
        active();
        renameFlowAt(state.activeFlow, name);
    }

    /** Renames any flow (by its place in the list); who keeps data by flow name (Debug Data) is told. */
    public void renameFlowAt(int index, String name) {
        if (index < 0 || index >= state.flows.size() || name == null || name.isBlank()) return;
        Flow f = state.flows.get(index);
        if (name.strip().equals(f.name)) return;
        String old = f.name;
        f.name = uniqueName(name);
        for (java.util.function.BiConsumer<String, String> l : renameListeners) l.accept(old, f.name);
        fire();
    }

    /**
     * Deletes several flows (each to the Recycle Bin). When all of them go, one flow stays, emptied (there is always
     * a flow).
     */
    public void deleteFlows(Collection<String> names) {
        for (String n : new ArrayList<>(names)) {
            int i = indexOfFlow(n);
            if (i >= 0) deleteFlowAt(i);
        }
    }

    /**
     * Takes away a flow that was never used (made in the Flow Diagram and closed while still empty): no Recycle Bin
     * item, no undo. Does nothing when it has lines or is the only flow.
     */
    public void dropEmptyFlow(String name) {
        int i = indexOfFlow(name);
        if (i < 0 || state.flows.size() <= 1 || !state.flows.get(i).entries.isEmpty()) return;
        Flow shown = active();
        state.flows.remove(i);
        state.activeFlow = shown.name.equals(name) ? Math.min(i, state.flows.size() - 1) : state.flows.indexOf(shown);
        if (shown.name.equals(name)) resetForNewFlow();
        fire();
    }

    /**
     * Takes away every flow without lines (e.g. tabs of the Flow Diagram that were never saved), as
     * {@link #dropEmptyFlow} does; the only flow left stays even when it is empty.
     *
     * @return how many were taken away
     */
    public int dropEmptyFlows() {
        int n = 0;
        for (String name : new ArrayList<>(getFlowNames())) {
            int before = state.flows.size();
            dropEmptyFlow(name);
            if (state.flows.size() < before) n++;
        }
        return n;
    }

    /** Deletes any flow (by its place in the list), like {@link #deleteFlow()} (it goes to the Recycle Bin). */
    public void deleteFlowAt(int index) {
        if (index < 0 || index >= state.flows.size()) return;
        switchFlow(index);
        deleteFlow();
    }

    private final List<java.util.function.BiConsumer<String, String>> renameListeners = new CopyOnWriteArrayList<>();

    /** Told (old name, new name) when a flow is renamed. */
    public void addRenameListener(java.util.function.BiConsumer<String, String> l) { renameListeners.add(l); }
    public void removeRenameListener(java.util.function.BiConsumer<String, String> l) { renameListeners.remove(l); }

    /**
     * Deletes the active flow (it goes to the Recycle Bin). The last flow is never deleted: it is emptied
     * instead, and its lines go to the bin.
     */
    public void deleteFlow() {
        Flow f = active();
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < f.entries.size(); i++) positions.add(i);
        if (state.flows.size() <= 1) {
            if (!f.entries.isEmpty()) {
                toBin(new BinItem("Empty flow \u201c" + f.name + "\u201d (" + f.entries.size() + " lines)",
                        f.name, false, f.entries, positions));
            }
            active().entries.clear();
        } else {
            toBin(new BinItem("Delete flow \u201c" + f.name + "\u201d (" + f.entries.size() + " lines)",
                    f.name, true, f.entries, positions));
            state.flows.remove(state.activeFlow);
            state.activeFlow = Math.min(state.activeFlow, state.flows.size() - 1);
        }
        resetForNewFlow();
        fire();
    }

    /** "Flow 2" when "Flow" is taken, and so on. */
    public String uniqueName(String wanted) {
        String base = wanted == null || wanted.isBlank() ? "Flow" : wanted.strip();
        List<String> taken = state.flows.stream().map(f -> f.name).toList();
        if (!taken.contains(base)) return base;
        for (int i = 2; ; i++) {
            if (!taken.contains(base + " " + i)) return base + " " + i;
        }
    }

    private void resetForNewFlow() {
        undoStack.clear();
        redoStack.clear();
        calls = FlowSync.Calls.NONE;
        setWalkEntry(null);
    }

    // ---------- undo / redo of adds and deletes ----------

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    /** What Undo would revert, e.g. "Add BookingService.java:21", or null. */
    public String undoLabel() { return undoStack.isEmpty() ? null : undoStack.peek().label; }
    public String redoLabel() { return redoStack.isEmpty() ? null : redoStack.peek().label; }

    public void undo() {
        Change c = undoStack.poll();
        if (c == null) return;
        if (c.looksBefore != null) active().diagram = new ArrayList<>(c.looksBefore);
        if (c.before != null) active().entries = new ArrayList<>(c.before);
        else if (c.added) takeOut(c); else putBack(c);
        if (c.bin != null) state.recycleBin.remove(c.bin); // the delete is undone: nothing to restore
        redoStack.push(c);
        fire();
    }

    public void redo() {
        Change c = redoStack.poll();
        if (c == null) return;
        if (c.looksAfter != null) active().diagram = new ArrayList<>(c.looksAfter);
        if (c.after != null) active().entries = new ArrayList<>(c.after);
        else if (c.added) putBack(c); else takeOut(c);
        toBin(c.bin);
        undoStack.push(c);
        fire();
    }

    private void record(Change c) {
        undoStack.push(c);
        while (undoStack.size() > HISTORY_LIMIT) undoStack.removeLast();
        redoStack.clear(); // a new change starts a new branch of history
    }

    /** The lines of a change back at their old positions (skipping any that were re-added meanwhile). */
    private void putBack(Change c) {
        for (int i = 0; i < c.lines.size(); i++) {
            FlowEntry e = c.lines.get(i);
            if (lines().contains(e) || contains(e.fileUrl, e.line)) continue;
            lines().add(Math.min(c.positions.get(i), lines().size()), e);
        }
    }

    private void takeOut(Change c) {
        lines().removeAll(c.lines);
    }

    /** Describes removing the stored ones of the given lines, with their current positions; null if none. */
    private Change removal(Collection<FlowEntry> toRemove, String label) {
        List<FlowEntry> lines = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        List<FlowEntry> all = lines();
        for (int i = 0; i < all.size(); i++) {
            FlowEntry e = all.get(i);
            if (toRemove.contains(e)) {
                lines.add(e);
                positions.add(i);
            }
        }
        if (lines.isEmpty()) return null;
        if (label == null) {
            label = lines.size() == 1 ? "Delete " + describe(lines.get(0)) : "Delete " + lines.size() + " lines";
        }
        return new Change(label, false, lines, positions, new BinItem(label, active().name, false, lines, positions));
    }

    private static String describe(FlowEntry e) {
        return e.fileName + ":" + e.line;
    }

    public boolean isAddAtTop() {
        return state.addAtTop;
    }

    public void setAddAtTop(boolean top) {
        if (state.addAtTop == top) return;
        state.addAtTop = top;
        fire();
    }

    public boolean isFlatView() {
        return state.flatView;
    }

    public void setFlatView(boolean flat) {
        if (state.flatView == flat) return;
        state.flatView = flat;
        fire();
    }

    public boolean isSyncFiles() {
        return state.syncFiles;
    }

    public void setSyncFiles(boolean sync) {
        if (state.syncFiles == sync) return;
        state.syncFiles = sync;
        fire();
    }

    public boolean isOptimizeFlatLog() {
        return state.optimizeFlatLog;
    }

    /** Turning this on turns Copy as Markdown off: only one copy format can be active. */
    public void setOptimizeFlatLog(boolean optimize) {
        if (state.optimizeFlatLog == optimize) return;
        state.optimizeFlatLog = optimize;
        if (optimize) state.copyMarkdown = false;
        fire();
    }

    public boolean isCopyMarkdown() {
        return state.copyMarkdown;
    }

    /** Turning this on turns Optimize Flat View Log off: only one copy format can be active. */
    public void setCopyMarkdown(boolean markdown) {
        if (state.copyMarkdown == markdown) return;
        state.copyMarkdown = markdown;
        if (markdown) state.optimizeFlatLog = false;
        fire();
    }

    public boolean isSequenceDiagram() {
        return state.sequenceDiagram;
    }

    public void setSequenceDiagram(boolean sequence) {
        if (state.sequenceDiagram == sequence) return;
        state.sequenceDiagram = sequence;
        fire();
    }

    public boolean isNestedCallLog() {
        return state.nestedCallLog;
    }

    public void setNestedCallLog(boolean nested) {
        if (state.nestedCallLog == nested) return;
        state.nestedCallLog = nested;
        fire();
    }

    public boolean isCopyAllFlows() {
        return state.copyAllFlows;
    }

    public void setCopyAllFlows(boolean all) {
        if (state.copyAllFlows == all) return;
        state.copyAllFlows = all;
        fire();
    }

    public Set<String> getCallLinks() {
        return getCalls().links();
    }

    /**
     * The calls of the active flow: those found by the last analysis plus the ones connected by hand (with
     * the line each call is on).
     */
    public FlowSync.Calls getCalls() {
        return FlowSync.withManual(calls, lines());
    }

    /**
     * Stores the analysis result, keeping its order (each caller's calls in source order). Does not notify
     * listeners: nothing on screen depends on it.
     */
    public void setCalls(FlowSync.Calls result) {
        calls = new FlowSync.Calls(Collections.unmodifiableSet(new LinkedHashSet<>(result.links())), Map.copyOf(result.lines()));
    }

    public void setNote(FlowEntry entry, String note) {
        entry.note = note == null ? "" : note;
        fire();
    }

    /** Connects a line to a method it calls (by hand), or removes that connection when it exists. */
    public void toggleConnection(FlowEntry caller, String calleeKey) {
        if (!caller.calls.remove(calleeKey)) caller.calls.add(calleeKey);
        fire();
    }

    public void clearConnections(FlowEntry caller) {
        if (caller.calls.isEmpty()) return;
        caller.calls.clear();
        fire();
    }

    public void setTag(Collection<FlowEntry> entries, @Nullable FlowTag tag) {
        for (FlowEntry e : entries) e.tag = tag == null ? "" : tag.id;
        fire();
    }

    /** Call after changing an entry in place (for example its line number). */
    public void notifyChanged() {
        fire();
    }

    /** Replaces the order of all entries (same entries, new order). Ignored if the entries do not match. */
    public void setOrder(List<FlowEntry> newOrder) {
        if (newOrder.size() != lines().size() || !newOrder.containsAll(lines())) return;
        active().entries = new ArrayList<>(newOrder);
        fire();
    }

    /** A new order made by the user (drag or move arrows): also turns Sync Files off, in one update. */
    public void setManualOrder(List<FlowEntry> newOrder) {
        if (newOrder.size() != lines().size() || !newOrder.containsAll(lines())) return;
        state.syncFiles = false;
        active().entries = new ArrayList<>(newOrder);
        fire();
    }

    // ---------- walk-through ----------

    public @Nullable FlowEntry getWalkEntry() {
        return walkEntry != null && lines().contains(walkEntry) ? walkEntry : null;
    }

    public void setWalkEntry(@Nullable FlowEntry e) {
        if (walkEntry == e) return;
        walkEntry = e;
        for (Runnable r : walkListeners) r.run();
    }

    public void addWalkListener(Runnable r) { walkListeners.add(r); }
    public void removeWalkListener(Runnable r) { walkListeners.remove(r); }

    public void addListener(Runnable r) { listeners.add(r); }
    public void removeListener(Runnable r) { listeners.remove(r); }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }

    @Override
    public @NotNull State getState() {
        active();
        return state;
    }

    @Override
    public void loadState(@NotNull State s) {
        this.state = s;
        diagramHistories.clear();
        undoStack.clear();
        redoStack.clear();
        walkEntry = null;
        // Lines saved before named flows existed become the first flow.
        if (!state.entries.isEmpty()) {
            if (state.flows.isEmpty()) state.flows.add(new Flow("Flow 1", state.entries));
            else state.flows.get(0).entries.addAll(state.entries);
            state.entries = new ArrayList<>();
        }
        active();
        if (state.copyMarkdown) state.optimizeFlatLog = false; // settings saved before they were exclusive
        // View modes are not remembered: every time the project opens, start with the defaults.
        state.addAtTop = false;
        state.flatView = false;
        fire();
    }
}
