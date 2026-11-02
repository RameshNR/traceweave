package com.example.flowtracker.model;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;

import java.util.List;
import java.util.Objects;

/** Works out which (sub)project a file belongs to, so files with the same name can be told apart. */
public final class FlowProjects {
    private FlowProjects() {}

    private static final List<String> BUILD_FILES =
            List.of("build.gradle", "build.gradle.kts", "pom.xml", "build.xml", "settings.gradle", "settings.gradle.kts");

    /**
     * Name of the closest folder above the file that has its own build file (a Gradle or Maven project),
     * e.g. "TravelBooking" for TravelWorkspace/TravelBooking/src/main/java/.../Hotel.java.
     * Falls back to the IDE project's name.
     */
    public static String nameFor(Project project, VirtualFile file) {
        String base = project.getBasePath();
        for (VirtualFile dir = file.getParent(); dir != null; dir = dir.getParent()) {
            for (String b : BUILD_FILES) {
                if (dir.findChild(b) != null) return dir.getName();
            }
            if (Objects.equals(dir.getPath(), base)) break; // never look above the opened project
        }
        return project.getName();
    }

    /** Fills in the project name of entries saved before it was recorded. Returns true if anything changed. */
    public static boolean backfill(Project project, List<FlowEntry> entries) {
        boolean changed = false;
        for (FlowEntry e : entries) {
            if (e.projectName != null && !e.projectName.isEmpty()) continue;
            VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(e.fileUrl);
            e.projectName = vf == null ? project.getName() : nameFor(project, vf);
            changed = true;
        }
        return changed;
    }

    /** The project a file (by its URL) belongs to, or "" when the file isn't there. */
    public static String ofUrl(Project project, String fileUrl) {
        if (fileUrl == null || fileUrl.isEmpty()) return "";
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(fileUrl);
        return vf == null ? "" : nameFor(project, vf);
    }

    /**
     * The start of an exported file's name: the projects the data comes from, in camelCase ("car-search" → carSearch),
     * joined with "_" when there are several ("carSearch_hotelBooking"); the IDE project's name when none is known.
     */
    public static String exportPrefix(Project project, java.util.Collection<String> projectNames) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (String n : projectNames) if (n != null && !n.isBlank()) names.add(camel(n));
        names.remove("");
        if (names.isEmpty()) names.add(camel(project.getName()));
        return String.join("_", names);
    }

    /** "car-search", "Car Search", "car_search" → "carSearch". */
    static String camel(String name) {
        StringBuilder b = new StringBuilder();
        for (String part : name.strip().split("[^A-Za-z0-9]+")) {
            if (part.isEmpty()) continue;
            if (b.length() == 0) b.append(Character.toLowerCase(part.charAt(0))).append(part.substring(1));
            else b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return b.toString();
    }

    /** "Flow 1.flow.json" → "carSearch_Flow 1.flow.json" (a file name that is safe on every system). */
    public static String prefixed(String prefix, String fileName) {
        return (prefix + "_" + fileName).replaceAll("[\\\\/:*?\"<>|]+", "_");
    }
}
