package com.example.flowtracker.ui;

import com.example.flowtracker.model.FlowDebugStore;
import com.example.flowtracker.model.FlowSnapshot;
import com.example.flowtracker.model.FlowStore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;

import java.util.ArrayList;
import java.util.List;

/**
 * Deleting flows, the same from the Flow tab and from Debug Data: a flow is deleted in both (its lines and its saved
 * values go to their Recycle Bins). When neither has anything in it, it goes at once; else one confirmation says
 * what goes, first for the screen it is deleted from, then for the other one.
 */
public final class FlowDeleting {
    private FlowDeleting() {}

    /** The screen a flow is deleted from (its details come first in the confirmation). */
    public enum From { FLOW_TAB, DEBUG_DATA }

    /** @return true when the flows were deleted (false: cancelled, or nothing to delete) */
    public static boolean delete(Project project, List<String> names, From from) {
        if (names.isEmpty()) return false;
        FlowStore flows = FlowStore.get(project);
        FlowDebugStore debug = FlowDebugStore.get(project);
        int lines = 0;
        for (String f : names) lines += flows.getEntriesOf(f).size();
        List<FlowSnapshot> values = new ArrayList<>();
        for (FlowSnapshot s : debug.getSnapshots()) if (names.contains(s.flowName)) values.add(s);

        if (lines > 0 || !values.isEmpty()) {
            String flowRow = "Flow tab: " + count(lines, "line") + (lines == 0 ? "" : ", to its Recycle Bin") + ".";
            String debugRow = "Debug Data: " + count(values.size(), "saved value") + (values.isEmpty() ? "" : ", to its Recycle Bin") + ".";
            boolean onlyFlow = flows.getFlowNames().size() <= names.size(); // the last flow is emptied, not taken away
            String msg = question(names, from, debug) + "\n\n"
                    + (from == From.FLOW_TAB ? flowRow + "\n" + debugRow : debugRow + "\n" + flowRow)
                    + "\n\nIt is deleted in both the Flow tab and Debug Data"
                    + (onlyFlow ? " (the only flow is emptied instead)." : ".");
            if (Messages.showYesNoDialog(project, msg, names.size() == 1 ? "Delete Flow" : "Delete Flows", "Delete", "Cancel",
                    Messages.getWarningIcon()) != Messages.YES) {
                return false;
            }
        }
        if (!values.isEmpty()) debug.delete(values);
        List<String> known = new ArrayList<>();
        for (String f : names) if (flows.getFlowNames().contains(f)) known.add(f);
        flows.deleteFlows(known);
        return true;
    }

    /** "Delete the flow “X”?" with the screen's own name of it (and the other one's, when it differs). */
    private static String question(List<String> names, From from, FlowDebugStore debug) {
        if (names.size() > 1) return "Delete " + names.size() + " flows?";
        String flow = names.get(0), title = debug.title(flow);
        if (title.equals(flow)) return "Delete the flow “" + flow + "”?";
        return from == From.FLOW_TAB ? "Delete the flow “" + flow + "” (“" + title + "” in Debug Data)?"
                : "Delete the flow “" + title + "” (“" + flow + "” in the Flow tab)?";
    }

    /** What a flow has in both screens, for the Delete Flows list: "3 lines · 2 saved values". */
    public static String details(Project project, String flow, From from) {
        int lines = FlowStore.get(project).getEntriesOf(flow).size();
        long values = FlowDebugStore.get(project).getSnapshots().stream().filter(s -> s.flowName.equals(flow)).count();
        String l = count(lines, "line"), v = count((int) values, "saved value");
        return from == From.FLOW_TAB ? l + " · " + v : v + " · " + l;
    }

    private static String count(int n, String what) {
        return n + " " + what + (n == 1 ? "" : "s");
    }
}
