package com.example.flowtracker.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Export / import of saved debug values (Debug Data) as a .flowdebug.json file, e.g. to keep the values of a
 * debug session with a bug report, or to compare them on another machine. Everything is kept: the values with
 * their fields, where they were saved, and their edit history.
 */
public final class FlowDebugTransfer {
    private FlowDebugTransfer() {}

    public static final String EXTENSION = ".flowdebug.json";
    static final String FORMAT = "traceweave-debug-data";
    /** What files exported before the new name say (they are still read). */
    static final String OLD_FORMAT = "flow-tracker-debug-data";

    /** The file's content. */
    static final class File {
        /** Empty unless the file says it is ours (Gson keeps this default for a field the file doesn't have). */
        String format = "";
        int version = 1;
        List<FlowSnapshot> snapshots = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static String toJson(List<FlowSnapshot> snapshots) {
        File f = new File();
        f.format = FORMAT;
        f.snapshots = new ArrayList<>(snapshots);
        return GSON.toJson(f);
    }

    public static void write(List<FlowSnapshot> snapshots, Path path) throws IOException {
        Files.writeString(path, toJson(snapshots), StandardCharsets.UTF_8);
    }

    /**
     * The values in an exported file, ready to add: values already in {@code existing} (same id) get a new id, so
     * importing the same file twice keeps both.
     */
    public static List<FlowSnapshot> fromJson(String json, List<FlowSnapshot> existing) throws IOException {
        File f;
        try {
            f = GSON.fromJson(json, File.class);
        } catch (JsonParseException e) {
            throw new IOException("This isn't a TraceWeave debug data file: " + e.getMessage(), e);
        }
        if (f == null || !(FORMAT.equals(f.format) || OLD_FORMAT.equals(f.format)) || f.snapshots == null) {
            throw new IOException("This isn't a TraceWeave debug data file (exported from Debug Data → Export).");
        }
        Set<String> ids = new HashSet<>();
        for (FlowSnapshot s : existing) ids.add(s.id);
        List<FlowSnapshot> out = new ArrayList<>();
        for (FlowSnapshot s : f.snapshots) {
            if (s == null) continue;
            if (s.value == null) s.value = new FlowValue(s.name);
            if (s.edits == null) s.edits = new ArrayList<>();
            fill(s.value);
            if (s.id == null || s.id.isEmpty() || !ids.add(s.id)) s.id = UUID.randomUUID().toString();
            s.deletedAt = 0;
            out.add(s);
        }
        return out;
    }

    public static List<FlowSnapshot> read(Path path, List<FlowSnapshot> existing) throws IOException {
        return fromJson(Files.readString(path, StandardCharsets.UTF_8), existing);
    }

    /** Missing fields (from a hand-edited file) become empty rather than null. */
    private static void fill(FlowValue v) {
        if (v.name == null) v.name = "";
        if (v.type == null) v.type = "";
        if (v.value == null) v.value = "";
        if (v.children == null) v.children = new ArrayList<>();
        for (FlowValue c : v.children) fill(c);
    }
}
