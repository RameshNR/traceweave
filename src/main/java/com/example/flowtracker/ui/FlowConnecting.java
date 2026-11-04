package com.example.flowtracker.ui;

import com.example.flowtracker.code.FlowCallPath;
import com.example.flowtracker.compat.FlowCompat;
import com.example.flowtracker.copy.FlowCopy;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowStore;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Connect the Flow from the Flow tab and the editor: the ways from one line to another are looked for (a progress
 * window says how far it is, Cancel stops it), the user picks one when there are several ("Choose a Way"), and its
 * lines are put into the flow right after the first line, as one undoable step.
 */
public final class FlowConnecting {
    private FlowConnecting() {}

    /**
     * @param first  a line of the flow (the lines go after it); tried the other way round too when nothing leads from
     *               it to {@code second}
     * @param second the line to reach (in the flow or not)
     */
    public static void connect(Project project, FlowStore store, FlowEntry first, FlowEntry second) {
        new Task.Modal(project, "Looking for the Ways", true) {
            private FlowCallPath.Ways found;
            private FlowEntry from = first, to = second;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Looking in the code for the ways from " + first.fileName + ":" + first.line + " to "
                        + second.fileName + ":" + second.line + "…");
                FlowCallPath.Progress progress = new FlowCallPath.Progress() {
                    @Override public boolean cancelled() {
                        return indicator.isCanceled();
                    }

                    @Override public void read(int methods, int ways) {
                        indicator.setText2(methods + (methods == 1 ? " method" : " methods") + " read · " + ways
                                + (ways == 1 ? " way" : " ways") + " found");
                    }
                };
                found = FlowCompat.read(() -> FlowCallPath.ways(project, first, second, null, FlowCallPath.MAX_WAYS, progress));
                if (found.ways().isEmpty() && !indicator.isCanceled()) { // maybe the second line calls the first
                    FlowCallPath.Ways back = FlowCompat.read(() -> FlowCallPath.ways(project, second, first, null, FlowCallPath.MAX_WAYS, progress));
                    if (!back.ways().isEmpty()) {
                        found = back;
                        from = second;
                        to = first;
                    }
                }
            }

            @Override
            public void onSuccess() {
                if (found == null || found.ways().isEmpty()) {
                    FlowCopy.notify(project, found == null ? "No way was found." : found.message(), NotificationType.WARNING);
                    return;
                }
                FlowCallPath.Way pick = found.ways().size() == 1 ? found.ways().get(0)
                        : FlowWaysDialog.choose(project, null, found, label(from), label(to), "Add This Way", inFlow(store), "the flow");
                if (pick == null) return;
                FlowEntry anchor = stored(store, from);
                if (anchor == null) { // the first line isn't in the flow yet: it comes first
                    anchor = from;
                    store.add(anchor);
                }
                int n = store.insertAfter(anchor, pick.lines(), "Connect " + from.fileName + ":" + from.line);
                String what = found.ways().size() == 1 ? found.message()
                        : "Way " + (found.ways().indexOf(pick) + 1) + " of " + found.ways().size() + ": " + pick.chain() + ".";
                FlowCopy.notify(project, what + (n == 0 ? " The lines in between are in the flow already."
                        : " Added " + n + (n == 1 ? " line" : " lines") + " (Undo takes them out)."), NotificationType.INFORMATION);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                FlowCopy.notify(project, "Couldn't read the code between the lines: " + error.getMessage(), NotificationType.ERROR);
            }
        }.queue();
    }

    /** Where a way's step is in the flow: its method has lines there, its class does, or it is new. */
    static java.util.function.Function<FlowCallPath.Step, FlowWaysList.Where> inFlow(FlowStore store) {
        java.util.Set<String> classes = new java.util.HashSet<>(), methods = new java.util.HashSet<>();
        for (FlowEntry e : store.getEntries()) {
            String m = e.methodName == null ? "" : e.methodName.replaceAll("\\(.*$", "");
            classes.add(e.className);
            methods.add(e.className + "#" + m);
        }
        return s -> methods.contains(s.className() + "#" + s.methodName()) ? FlowWaysList.Where.METHOD_THERE
                : classes.contains(s.className()) ? FlowWaysList.Where.CLASS_THERE : FlowWaysList.Where.NEW;
    }

    /** The flow's own line for a line (the same file and line), or null when the flow doesn't have it. */
    public static @Nullable FlowEntry stored(FlowStore store, FlowEntry line) {
        for (FlowEntry e : store.getEntries()) if (e.line == line.line && e.fileUrl.equals(line.fileUrl)) return e;
        return null;
    }

    /** "Main.main()" for a line. */
    static String label(FlowEntry e) {
        String cls = e.className == null || e.className.isBlank() || "-".equals(e.className) ? e.fileName : e.className;
        String m = e.methodName == null || e.methodName.isBlank() ? "" : "." + e.methodName;
        return cls + m;
    }
}
