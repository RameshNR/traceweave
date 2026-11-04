package com.example.flowtracker.model;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A variable along the flow: the values saved of it (same name, same flow) at each line, in the flow's order, and
 * what changed from one to the next (counted like the Compare window: changed, added and removed values).
 */
public final class FlowValueTrail {
    private FlowValueTrail() {}

    /**
     * One saved value on the trail, and what changed since the one before (all 0 for the first, or when nothing did).
     *
     * @param step its place in the flow (1-based; 0 when its line isn't in the flow any more)
     */
    public record Stop(FlowSnapshot value, int step, int changed, int added, int removed) {
        public boolean same() {
            return changed + added + removed == 0;
        }
    }

    /**
     * @param all       the saved values
     * @param of        the value whose variable is followed (its name and flow)
     * @param flowLines the flow's lines in their order (the trail goes that way; lines no longer in it come last)
     */
    public static List<Stop> along(List<FlowSnapshot> all, FlowSnapshot of, List<FlowEntry> flowLines) {
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < flowLines.size(); i++) order.putIfAbsent(flowLines.get(i).fileUrl + "#" + flowLines.get(i).line, i + 1);
        List<FlowSnapshot> same = new ArrayList<>();
        for (FlowSnapshot s : all) if (s.name.equals(of.name) && s.flowName.equals(of.flowName)) same.add(s);
        same.sort(Comparator.<FlowSnapshot>comparingInt(s -> order.getOrDefault(s.fileUrl + "#" + s.line, Integer.MAX_VALUE))
                .thenComparingLong(s -> s.savedAt));
        List<Stop> out = new ArrayList<>();
        @Nullable FlowSnapshot before = null;
        for (FlowSnapshot s : same) {
            int changed = 0, added = 0, removed = 0;
            if (before != null) {
                for (FlowValueDiff.Row r : FlowValueDiff.compare(before.value, s.value)) {
                    switch (r.kind()) {
                        case CHANGED -> changed++;
                        case ADDED -> added++;
                        case REMOVED -> removed++;
                        default -> { }
                    }
                }
            }
            out.add(new Stop(s, order.getOrDefault(s.fileUrl + "#" + s.line, 0), changed, added, removed));
            before = s;
        }
        return out;
    }
}
