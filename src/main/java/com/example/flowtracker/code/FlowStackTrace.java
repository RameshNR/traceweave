package com.example.flowtracker.code;

import com.example.flowtracker.model.FlowEntry;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A flow from a stack trace: the frames of a pasted Java (or Kotlin…) exception, the project's own lines of them, in
 * the order the code ran (the outermost call first). When the trace has "Caused by:" parts, the last one is taken (the
 * root cause, where the error began); library frames are left out.
 */
public final class FlowStackTrace {
    private FlowStackTrace() {
    }

    /** One "at com.shop.Order.pay(Order.java:42)" line. */
    public record Frame(String className, String method, String file, int line) {}

    /** The exception (e.g. "IllegalStateException: no seats") and its frames, the innermost first (as printed). */
    public record Trace(String exception, List<Frame> frames) {}

    /** "at [module/]class.method(File.java:42)"; the module part ("app//", "java.base/") is left out. */
    private static final Pattern FRAME = Pattern.compile(
            "^\\s*at\\s+(?:[\\w.$@-]*/+)?([\\w$.]+)\\.([\\w$<>-]+)\\(([^:()]*)(?::(\\d+))?\\)");
    /** "java.lang.IllegalStateException: text" or "Caused by: …". */
    private static final Pattern HEAD = Pattern.compile("^(Caused by:\\s*)?([\\w$.]+(?:Exception|Error|Throwable)[\\w$]*)(:.*)?$");

    /** The trace's root-cause part (else its first part); null when no frame is in the text. */
    public static @Nullable Trace parse(String text) {
        List<Trace> parts = new ArrayList<>();
        String exception = "";
        List<Frame> frames = new ArrayList<>();
        boolean causedBy = false;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            Matcher f = FRAME.matcher(raw);
            if (f.find()) {
                int n = f.group(4) == null ? 0 : Integer.parseInt(f.group(4));
                frames.add(new Frame(f.group(1), f.group(2), f.group(3), n));
                continue;
            }
            Matcher h = HEAD.matcher(line);
            if (h.matches()) {
                if (!frames.isEmpty() || !exception.isEmpty()) parts.add(new Trace(exception, frames));
                frames = new ArrayList<>();
                String name = h.group(2);
                exception = name.substring(name.lastIndexOf('.') + 1) + (h.group(3) == null ? "" : h.group(3));
                causedBy |= h.group(1) != null;
            }
        }
        if (!frames.isEmpty() || !exception.isEmpty()) parts.add(new Trace(exception, frames));
        parts.removeIf(t -> t.frames().isEmpty());
        if (parts.isEmpty()) return null;
        return causedBy ? parts.get(parts.size() - 1) : parts.get(0);
    }

    /**
     * The project's lines of a trace's frames, the outermost call first (the order the code ran), each once. Frames
     * of libraries and of classes not found are left out. Read the code in a read action.
     */
    public static List<FlowEntry> lines(Project project, Trace trace) {
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        List<FlowEntry> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Frame> frames = new ArrayList<>(trace.frames());
        java.util.Collections.reverse(frames);
        for (Frame f : frames) {
            if (f.line() <= 0) continue;
            String outer = f.className().contains("$") ? f.className().substring(0, f.className().indexOf('$')) : f.className();
            PsiClass k = JavaPsiFacade.getInstance(project).findClass(outer, scope);
            VirtualFile vf = k == null || k.getContainingFile() == null ? null : k.getContainingFile().getVirtualFile();
            if (vf == null || !index.isInSourceContent(vf)) continue; // a library's frame (or a class this project hasn't)
            if (!seen.add(vf.getUrl() + ":" + f.line())) continue;
            FlowEntry e = FlowCodeSearch.lineEntry(project, vf.getUrl(), f.line() - 1);
            if (e != null) out.add(e);
        }
        return out;
    }
}
