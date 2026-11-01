package com.example.flowtracker.copy;

import com.example.flowtracker.code.FlowSync;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowStore;
import com.example.flowtracker.model.FlowTransfer;
import com.intellij.ide.scratch.ScratchFileService;
import com.intellij.ide.scratch.ScratchRootType;
import com.intellij.lang.Language;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileTypes.PlainTextLanguage;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Turns lines into text in the format chosen in Settings, for one flow or for all of them, and puts it on
 * the clipboard or opens it as a Markdown file.
 */
public final class FlowCopy {
    private FlowCopy() {}

    /**
     * @param picked the lines to copy
     * @param all    every line of their flow (Flat View step numbers count within it)
     * @param links  calls between the flow's methods, for the Markdown diagram
     * @param title  Markdown document heading
     */
    public static String textFor(FlowStore store, List<FlowEntry> picked, List<FlowEntry> all, Set<String> links,
                          String title) {
        return textFor(store, picked, all, new FlowSync.Calls(links, Map.of()), title);
    }

    /** @param calls calls between the flow's methods (diagrams; nested Original Log) */
    public static String textFor(FlowStore store, List<FlowEntry> picked, List<FlowEntry> all, FlowSync.Calls calls,
                          String title) {
        return format(store, picked, all, calls, title, store.isCopyMarkdown());
    }

    /** @param markdown true = a Markdown document (with the Original Log), false = the plain copy */
    private static String format(FlowStore store, List<FlowEntry> picked, List<FlowEntry> all, FlowSync.Calls calls,
                                 String title, boolean markdown) {
        if (markdown) {
            String md = FlowMarkdown.toMarkdown(picked, store.isFlatView(), calls.links(), store.isSequenceDiagram(), title);
            return picked.isEmpty() ? md : md + originalLog(store, picked, all, calls);
        }
        return plainText(store, picked, all);
    }

    /** The plain copy of the current view: indented text (Grouped) or numbered steps / optimized log (Flat). */
    private static String plainText(FlowStore store, List<FlowEntry> picked, List<FlowEntry> all) {
        if (!store.isFlatView()) return FlowFormat.toText(picked);
        if (store.isOptimizeFlatLog()) return FlowFormat.toOptimizedLog(picked);
        return FlowFormat.toFlatText(picked, all);
    }

    /**
     * The end of a Markdown flow: the lines as plain text, in a text block, so the original log travels with
     * the document. With "Nest Calls in Original Log" on, each called method's lines sit under the line that
     * calls it (see {@link FlowCallLog}); off, it is the plain copy of the current view (what Copy gives with
     * Copy as Markdown off).
     */
    private static String originalLog(FlowStore store, List<FlowEntry> picked, List<FlowEntry> all, FlowSync.Calls calls) {
        boolean nested = store.isNestedCallLog();
        String log = originalLogText(store, picked, all, calls);
        String fence = log.contains("```") ? "````" : "```";
        return "## 📜 Original Log\n\n_" + (store.isFlatView() ? "Flat View" : "Grouped View")
                + (nested ? " copy, as plain text, with each call nested under the line that makes it._\n\n"
                : " copy, as plain text._\n\n")
                + fence + "text\n" + log + (log.endsWith("\n") ? "" : "\n") + fence + "\n";
    }

    /**
     * The Original Log alone, as plain text (what the Markdown copy ends with, without the Markdown around it): with
     * "Nest Calls in Original Log" on, each called method's lines under the line that calls it; off, the plain copy
     * of the current view.
     */
    public static String originalLogText(FlowStore store, List<FlowEntry> picked, List<FlowEntry> all, FlowSync.Calls calls) {
        return store.isNestedCallLog() ? FlowCallLog.render(picked, store.isFlatView(), all, calls) : plainText(store, picked, all);
    }

    /** Copies the Original Log of the given lines of the active flow (every flow's, one after the other, with Copy All Flows). */
    public static void copyOriginalLog(Project project, List<FlowEntry> picked) {
        FlowStore store = FlowStore.get(project);
        if (store.isCopyAllFlows()) {
            List<FlowStore.Flow> flows = store.getAllFlows().stream().filter(f -> !f.entries.isEmpty()).toList();
            if (flows.isEmpty()) {
                notify(project, "Nothing to copy: no flow has lines yet", NotificationType.INFORMATION);
                return;
            }
            FlowSync.callsFor(project, flows.stream().map(f -> f.entries).toList(), calls -> {
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < flows.size(); i++) {
                    FlowStore.Flow f = flows.get(i);
                    if (i > 0) out.append("\n\n");
                    out.append("===== ").append(f.name).append(" =====\n\n")
                            .append(originalLogText(store, f.entries, f.entries, FlowSync.withManual(calls.get(i), f.entries)));
                }
                toClipboard(out.toString());
                notify(project, "Copied the Original Log of " + flows.size() + (flows.size() == 1 ? " flow" : " flows"),
                        NotificationType.INFORMATION);
            });
            return;
        }
        if (picked.isEmpty()) {
            notify(project, "Nothing to copy: this flow has no lines yet", NotificationType.INFORMATION);
            return;
        }
        toClipboard(originalLogText(store, picked, store.getEntries(), store.getCalls()));
        notify(project, "Copied the Original Log (" + picked.size() + (picked.size() == 1 ? " line)" : " lines)"),
                NotificationType.INFORMATION);
    }

    /** The given lines of the active flow, titled with the flow's name. */
    public static String textFor(FlowStore store, List<FlowEntry> picked) {
        return textFor(store, picked, store.getEntries(), store.getCalls(), store.getActiveFlowName());
    }

    public static void toClipboard(String text) {
        if (text.isEmpty()) return;
        CopyPasteManager.getInstance().setContents(new StringSelection(text));
    }

    /** What {@link #allFlows} produced: the text and how many flows and lines it holds. */
    record AllFlows(String text, int flows, int lines) {}

    /**
     * Every flow that has lines, one after the other, each under its name, in the Flows popup's order.
     * Calls are analysed for each flow first (in the background), so Markdown diagrams are complete;
     * {@code done} runs on the UI thread, and not at all when no flow has lines (a notification says so).
     */
    static void allFlows(Project project, boolean markdown, Consumer<AllFlows> done) {
        FlowStore store = FlowStore.get(project);
        List<FlowStore.Flow> flows = store.getAllFlows().stream().filter(f -> !f.entries.isEmpty()).toList();
        if (flows.isEmpty()) {
            notify(project, "Nothing to copy: no flow has lines yet", NotificationType.INFORMATION);
            return;
        }
        FlowSync.callsFor(project, flows.stream().map(f -> f.entries).toList(), calls -> {
            StringBuilder out = new StringBuilder();
            int lines = 0;
            for (int i = 0; i < flows.size(); i++) {
                FlowStore.Flow f = flows.get(i);
                if (i > 0) out.append(markdown ? "\n---\n\n" : "\n\n");
                if (!markdown) {
                    out.append("===== ").append(f.name).append(" (").append(f.entries.size())
                            .append(f.entries.size() == 1 ? " line" : " lines").append(") =====\n\n");
                }
                out.append(format(store, f.entries, f.entries, FlowSync.withManual(calls.get(i), f.entries), f.name, markdown));
                lines += f.entries.size();
            }
            done.accept(new AllFlows(out.toString(), flows.size(), lines));
        });
    }

    /** Copies every flow in the current copy format (see {@link #allFlows}). */
    public static void copyAllFlows(Project project) {
        allFlows(project, FlowStore.get(project).isCopyMarkdown(), all -> {
            toClipboard(all.text());
            notify(project, "Copied " + all.flows() + (all.flows() == 1 ? " flow (" : " flows (") + all.lines()
                    + " lines)", NotificationType.INFORMATION);
        });
    }

    /**
     * Opens the flow as a Markdown document in the editor (with the preview, when the Markdown plugin is on):
     * every flow when Copy All Flows is on, else the given lines of the active flow. The document is a
     * scratch file (Project view → Scratches and Consoles), so it can be kept, edited or saved elsewhere.
     */
    public static void openMarkdown(Project project, List<FlowEntry> picked) {
        FlowStore store = FlowStore.get(project);
        if (store.isCopyAllFlows()) {
            allFlows(project, true, all -> openScratch(project, "TraceWeave - all flows", all.text()));
            return;
        }
        if (picked.isEmpty()) {
            notify(project, "Nothing to open: this flow has no lines yet", NotificationType.INFORMATION);
            return;
        }
        String name = store.getActiveFlowName();
        openScratch(project, name, format(store, picked, store.getEntries(), store.getCalls(), name, true));
    }

    private static void openScratch(Project project, String name, String text) {
        Language markdown = Language.findLanguageByID("Markdown");
        String fileName = FlowTransfer.fileNameFor(name).replace(FlowTransfer.EXTENSION, "") + ".md";
        VirtualFile file = ScratchRootType.getInstance().createScratchFile(project, fileName,
                markdown != null ? markdown : PlainTextLanguage.INSTANCE, text, ScratchFileService.Option.create_new_always);
        if (file != null) new OpenFileDescriptor(project, file).navigate(true);
    }

    public static void notify(Project project, String text, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup("TraceWeave")
                .createNotification(text, type).notify(project);
    }
}
