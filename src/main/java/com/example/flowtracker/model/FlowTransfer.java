package com.example.flowtracker.model;

import com.example.flowtracker.compat.FlowCompat;
import com.example.flowtracker.diagram.FlowDiagram;
import com.example.flowtracker.diagram.FlowDiagramLook;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Export / import of one flow as a .flow.json file that can be committed or sent to a teammate.
 * Files are stored relative to the project folder, so the flow opens on another machine too.
 */
public final class FlowTransfer {
    private FlowTransfer() {}

    public static final String EXTENSION = ".flow.json";
    private static final String FORMAT = "traceweave";
    /** What files exported before the new name say (they are still read). */
    private static final String OLD_FORMAT = "flow-tracker";
    private static final int VERSION = 1;

    /** The file layout. Field names are the JSON keys. */
    @SuppressWarnings("unused")
    private static final class FileData {
        String format = FORMAT;
        int version = VERSION;
        String name;
        String exportedAt;
        List<Line> lines = new ArrayList<>();
        /** The Flow Diagram's look; its file addresses are paths like a line's {@code path}. */
        List<FlowDiagramLook> diagram;
    }

    @SuppressWarnings("unused")
    private static final class Line {
        String project;
        /** Path relative to the project folder (forward slashes), or an absolute path for outside files. */
        String path;
        String file;
        String className;
        String method;
        int line;
        String code;
        String note;
        String tag;
        /** Methods this line calls, connected by hand: "relative/path/File.java#method()". */
        List<String> calls;
    }

    /** A read flow: its name, the lines, its diagram's look, and how many lines point to files this project doesn't have. */
    public record Imported(String name, List<FlowEntry> entries, List<FlowDiagramLook> looks, int missing) {}

    private static Gson gson() {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    }

    public static void write(Project project, String name, Collection<FlowEntry> entries, List<FlowDiagramLook> looks,
                      Path target) throws IOException {
        FileData data = new FileData();
        data.name = name;
        data.exportedAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        for (FlowEntry e : entries) {
            Line l = new Line();
            l.project = e.projectName;
            l.path = relativePath(project, e.fileUrl);
            l.file = e.fileName;
            l.className = e.className;
            l.method = e.methodName;
            l.line = e.line;
            l.code = e.text;
            l.note = blankToNull(e.note);
            l.tag = blankToNull(e.tag);
            if (!e.calls.isEmpty()) {
                l.calls = new ArrayList<>();
                for (String key : e.calls) {
                    int hash = key.lastIndexOf('#');
                    if (hash > 0) l.calls.add(relativePath(project, key.substring(0, hash)) + key.substring(hash));
                }
            }
            data.lines.add(l);
        }
        if (!looks.isEmpty()) {
            data.diagram = new ArrayList<>();
            for (FlowDiagramLook look : looks) {
                FlowDiagramLook copy = copy(look);
                copy.fileUrl = look.fileUrl.isEmpty() ? "" : relativePath(project, look.fileUrl);
                copy.toFileUrl = look.toFileUrl.isEmpty() ? "" : relativePath(project, look.toFileUrl);
                data.diagram.add(copy);
            }
        }
        Files.writeString(target, gson().toJson(data) + "\n", StandardCharsets.UTF_8);
    }

    public static Imported read(Project project, Path source) throws IOException {
        FileData data;
        try {
            data = gson().fromJson(Files.readString(source, StandardCharsets.UTF_8), FileData.class);
        } catch (JsonParseException ex) {
            throw new IOException("Not a valid flow file: " + ex.getMessage(), ex);
        }
        if (data == null || !(FORMAT.equals(data.format) || OLD_FORMAT.equals(data.format)) || data.lines == null) {
            throw new IOException("Not a TraceWeave file (expected \"format\": \"" + FORMAT + "\").");
        }

        List<FlowEntry> entries = new ArrayList<>();
        int missing = 0;
        java.util.Map<String, String> urlByPath = new java.util.HashMap<>(); // to resolve the connections below
        List<List<String>> connections = new ArrayList<>();
        for (Line l : data.lines) {
            if (l == null || l.code == null) continue;
            String fileName = l.file != null ? l.file : l.path == null ? "?" : l.path.replaceAll(".*/", "");
            boolean diagramClass = l.path != null && l.path.startsWith(FlowDiagram.DIAGRAM_URL);
            VirtualFile vf = diagramClass ? null : resolve(project, l.path, fileName);
            String url = diagramClass ? l.path : vf != null ? vf.getUrl() : VfsUtilCore.pathToUrl(absolute(project, l.path));
            if (vf == null && !diagramClass) missing++;

            // Line 0: a method or class added in the Flow Diagram (no code yet).
            FlowEntry e = new FlowEntry(fileName, url, orDash(l.className), orDash(l.method), Math.max(0, l.line), l.code);
            e.projectName = l.project == null ? "" : l.project;
            e.note = l.note == null ? "" : l.note;
            e.tag = FlowTag.byId(l.tag) == null ? "" : l.tag;
            entries.add(e);
            if (l.path != null) urlByPath.put(l.path, url);
            connections.add(l.calls == null ? List.of() : l.calls);
        }
        String name = data.name == null || data.name.isBlank() ? defaultName(source) : data.name;
        // Connections point at methods by path: map each path to the file it was found at on this machine.
        for (int i = 0; i < entries.size(); i++) {
            for (String c : connections.get(i)) {
                int hash = c.lastIndexOf('#');
                if (hash <= 0) continue;
                String path = c.substring(0, hash);
                String url = urlByPath.getOrDefault(path, VfsUtilCore.pathToUrl(absolute(project, path)));
                entries.get(i).calls.add(url + c.substring(hash));
            }
        }
        List<FlowDiagramLook> looks = new ArrayList<>();
        if (data.diagram != null) {
            for (FlowDiagramLook l : data.diagram) {
                if (l == null) continue;
                FlowDiagramLook look = copy(l);
                look.fileUrl = lookUrl(project, l.fileUrl, urlByPath);
                look.toFileUrl = lookUrl(project, l.toFileUrl, urlByPath);
                looks.add(look);
            }
        }
        return new Imported(name, entries, looks, missing);
    }

    /** The file address of a path in a look ("" stays "": a custom class). */
    private static String lookUrl(Project project, @Nullable String path, java.util.Map<String, String> urlByPath) {
        if (path == null || path.isEmpty()) return "";
        return urlByPath.getOrDefault(path, VfsUtilCore.pathToUrl(absolute(project, path)));
    }

    /** A copy with no null texts (a file may leave fields out). */
    private static FlowDiagramLook copy(FlowDiagramLook l) {
        FlowDiagramLook c = new FlowDiagramLook();
        c.fileUrl = nn(l.fileUrl);
        c.className = nn(l.className);
        c.method = nn(l.method);
        c.toFileUrl = nn(l.toFileUrl);
        c.toClass = nn(l.toClass);
        c.toMethod = nn(l.toMethod);
        c.placed = l.placed;
        c.x = l.x;
        c.y = l.y;
        c.fill = nn(l.fill);
        c.header = nn(l.header);
        c.fileFormat = nn(l.fileFormat);
        c.methodFormat = nn(l.methodFormat);
        c.border = nn(l.border);
        c.format = nn(l.format);
        c.note = nn(l.note);
        c.noteFormat = nn(l.noteFormat);
        c.line = nn(l.line);
        c.noteStyle = nn(l.noteStyle);
        c.projectFill = nn(l.projectFill);
        c.projectBorder = nn(l.projectBorder);
        c.projectFormat = nn(l.projectFormat);
        return c;
    }

    private static String nn(@Nullable String s) {
        return s == null ? "" : s;
    }

    /** "checkout" for checkout.flow.json. */
    static String defaultName(Path file) {
        String n = file.getFileName().toString();
        if (n.endsWith(EXTENSION)) return n.substring(0, n.length() - EXTENSION.length());
        return n.endsWith(".json") ? n.substring(0, n.length() - 5) : n;
    }

    /** A file name for a flow name: "Checkout bug" becomes "Checkout-bug.flow.json". */
    public static String fileNameFor(String flowName) {
        String safe = flowName.strip().replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return (safe.isEmpty() ? "flow" : safe) + EXTENSION;
    }

    // ---------- paths ----------

    private static String relativePath(Project project, String fileUrl) {
        if (fileUrl.startsWith(FlowDiagram.DIAGRAM_URL)) return fileUrl; // a class added in the Flow Diagram
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(fileUrl);
        String path = vf != null ? vf.getPath() : VfsUtilCore.urlToPath(fileUrl);
        String base = project.getBasePath();
        if (base != null) {
            String prefix = base.endsWith("/") ? base : base + "/";
            if (path.startsWith(prefix)) return path.substring(prefix.length());
        }
        return path;
    }

    private static String absolute(Project project, @Nullable String path) {
        if (path == null) return "";
        if (isAbsolute(path) || project.getBasePath() == null) return path;
        return project.getBasePath() + "/" + path;
    }

    private static boolean isAbsolute(String path) {
        return path.startsWith("/") || path.matches("^[A-Za-z]:[/\\\\].*");
    }

    /**
     * The file a line points to: by its path relative to this project, then by its absolute path, and
     * finally (when the folders differ) the only file in the project with the same name.
     */
    private static @Nullable VirtualFile resolve(Project project, @Nullable String path, String fileName) {
        LocalFileSystem fs = LocalFileSystem.getInstance();
        if (path != null) {
            VirtualFile vf = fs.findFileByPath(absolute(project, path));
            if (vf != null) return vf;
            vf = fs.findFileByPath(path);
            if (vf != null) return vf;
        }
        if (DumbService.isDumb(project)) return null;
        return FlowCompat.read(() -> {
            Collection<VirtualFile> byName =
                    FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project));
            if (byName.size() == 1) return byName.iterator().next();
            // Several files with this name: pick the one whose path ends like the stored one.
            if (path != null) {
                for (VirtualFile f : byName) {
                    if (f.getPath().endsWith("/" + path) || path.endsWith(tail(f.getPath()))) return f;
                }
            }
            return null;
        });
    }

    /** The last three path segments, e.g. "flowtracker/service/BookingService.java". */
    private static String tail(String path) {
        String[] parts = path.split("/");
        int from = Math.max(0, parts.length - 3);
        return String.join("/", java.util.Arrays.copyOfRange(parts, from, parts.length));
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String orDash(@Nullable String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
