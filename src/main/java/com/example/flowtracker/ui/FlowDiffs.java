package com.example.flowtracker.ui;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

/** Two texts in IntelliJ's diff viewer: added, removed and changed lines side by side, with syntax colors. */
public final class FlowDiffs {
    private FlowDiffs() {}

    /** @param extension the texts' kind for highlighting, e.g. "json", "xml" or "txt" */
    public static void show(@Nullable Project project, String title, String leftTitle, String left, String rightTitle,
                            String right, String extension) {
        DiffManager.getInstance().showDiff(project, request(project, title, leftTitle, left, rightTitle, right, extension));
    }

    /** The two texts as a diff (to show in a window's own diff viewer). */
    public static SimpleDiffRequest request(@Nullable Project project, String title, String leftTitle, String left, String rightTitle,
                                            String right, String extension) {
        FileType type = FileTypeManager.getInstance().getFileTypeByExtension(extension);
        DiffContentFactory f = DiffContentFactory.getInstance();
        return new SimpleDiffRequest(title, f.create(project, left, type), f.create(project, right, type), leftTitle, rightTitle);
    }
}
