package com.example.flowtracker.code;

import com.example.flowtracker.model.FlowStore;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flow checks against the code: a flow's calls marked as expected (Flows → Mark Calls as Expected) are compared with
 * the calls the code makes now. A call that is gone (a method renamed or moved, a call removed) is told, once for each
 * new difference, with the calls that are new.
 */
public final class FlowCallCheck {
    private FlowCallCheck() {
    }

    /** What was told last, by project and flow (so the same difference isn't told again on every sync). */
    private static final Map<String, Set<String>> TOLD = new ConcurrentHashMap<>();

    /** The expected calls the code doesn't make now. */
    public static List<String> missing(List<String> expected, Set<String> now) {
        List<String> out = new ArrayList<>();
        for (String link : expected) if (!now.contains(link)) out.add(link);
        return out;
    }

    /** The calls the code makes now that weren't expected. */
    public static List<String> added(List<String> expected, Set<String> now) {
        List<String> out = new ArrayList<>();
        Set<String> was = new LinkedHashSet<>(expected);
        for (String link : now) if (!was.contains(link)) out.add(link);
        return out;
    }

    /** "BookingService.create() → TravelCodes.normalize()" for a call's link. */
    public static String label(String link) {
        String[] ends = FlowSync.parseLink(link);
        if (ends == null) return link;
        return method(ends[0]) + " → " + method(ends[1]);
    }

    private static String method(String key) {
        int hash = key.lastIndexOf('#');
        String label = hash < 0 ? key : key.substring(hash + 1);
        String file = hash < 0 ? "" : key.substring(key.lastIndexOf('/', hash) + 1, hash);
        return label.contains(".") || file.isEmpty() ? label : file.replaceAll("\\.[^.]+$", "") + "." + label;
    }

    /**
     * After the calls were worked out (Sync): the active flow's expected calls against them; a call that is gone is
     * told (a warning), once for each new difference.
     */
    public static void afterSync(Project project, Set<String> now) {
        FlowStore store = FlowStore.get(project);
        List<String> expected = store.getExpectedCalls(store.getActiveFlowName());
        String key = project.getLocationHash() + "|" + store.getActiveFlowName();
        if (expected.isEmpty()) {
            TOLD.remove(key);
            return;
        }
        List<String> gone = missing(expected, now);
        Set<String> told = TOLD.getOrDefault(key, Set.of());
        if (gone.isEmpty() || told.equals(new LinkedHashSet<>(gone))) {
            if (gone.isEmpty()) TOLD.remove(key);
            return;
        }
        TOLD.put(key, new LinkedHashSet<>(gone));
        com.example.flowtracker.copy.FlowCopy.notify(project, report(store.getActiveFlowName(), gone, added(expected, now)),
                NotificationType.WARNING);
    }

    /** What a check found, in words. */
    public static String report(String flow, List<String> gone, List<String> added) {
        StringBuilder sb = new StringBuilder();
        if (gone.isEmpty()) {
            sb.append("“").append(flow).append("”: every expected call is still in the code.");
        } else {
            sb.append("“").append(flow).append("”: ").append(gone.size())
                    .append(gone.size() == 1 ? " expected call isn't in the code any more" : " expected calls aren't in the code any more")
                    .append(" (a method renamed or moved, or a call removed?):");
            for (String l : gone) sb.append("\n• ").append(label(l));
        }
        if (!added.isEmpty()) {
            sb.append("\nNew since it was marked:");
            for (String l : added) sb.append("\n• ").append(label(l));
        }
        return sb.toString();
    }
}
