package com.example.flowtracker.model;

import com.example.flowtracker.compat.FlowCompat;
import com.example.flowtracker.diagram.FlowDiagram;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.Nullable;

/**
 * Checks that a saved line still matches the code: the file must exist and the stored line number must
 * still hold the stored code. When the code just moved (lines were added or removed above it), the
 * nearest line with the same code is reported so the entry can be re-linked to it.
 */
public final class FlowCheck {
    private FlowCheck() {}

    /** How far (in lines, each way) to look for code that moved. */
    private static final int SEARCH_RADIUS = 400;

    public enum Kind { MISSING_FILE, MOVED, CHANGED }

    /**
     * @param movedTo     1-based line the same code is on now (MOVED only), else -1
     * @param currentCode what the stored line number holds now (CHANGED only), else null
     */
    public record Problem(Kind kind, String message, int movedTo, @Nullable String currentCode) {}

    /** The problem with a saved line, or null when it still matches the code. */
    public static @Nullable Problem check(FlowEntry e) {
        if (FlowDiagram.isDiagramOnly(e)) return null; // added in the Flow Diagram: no code to compare with
        return FlowCompat.read(() -> {
            VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(e.fileUrl);
            if (vf == null || !vf.isValid()) {
                return new Problem(Kind.MISSING_FILE, e.fileName + " no longer exists (or was moved)", -1, null);
            }
            Document doc = FileDocumentManager.getInstance().getDocument(vf);
            if (doc == null) return null;

            String want = e.text.strip();
            int index = e.line - 1;
            if (index >= 0 && index < doc.getLineCount() && lineText(doc, index).equals(want)) return null;

            int found = nearest(doc, want, index);
            if (found >= 0) {
                return new Problem(Kind.MOVED, "This code is now on line " + (found + 1), found + 1, null);
            }
            if (index < 0 || index >= doc.getLineCount()) {
                return new Problem(Kind.CHANGED, "Line " + e.line + " no longer exists", -1, null);
            }
            String now = lineText(doc, index);
            return new Problem(Kind.CHANGED, now.isEmpty()
                    ? "Line " + e.line + " is now empty"
                    : "The code changed. Line " + e.line + " now reads: " + now, -1, now);
        });
    }

    /**
     * Brings the entry back in line with the code: follows moved code to its new line, or takes the
     * current code of the stored line. Returns false when there is nothing that can be done.
     */
    public static boolean relink(FlowEntry e, Problem p) {
        switch (p.kind()) {
            case MOVED -> e.line = p.movedTo();
            case CHANGED -> {
                if (p.currentCode() == null || p.currentCode().isEmpty()) return false;
                e.text = p.currentCode();
            }
            default -> { return false; }
        }
        return true;
    }

    private static String lineText(Document doc, int line) {
        int start = doc.getLineStartOffset(line);
        int end = doc.getLineEndOffset(line);
        return doc.getCharsSequence().subSequence(start, end).toString().strip();
    }

    /** The closest line (0-based) holding exactly this code, or -1. */
    private static int nearest(Document doc, String code, int around) {
        if (code.isEmpty()) return -1;
        int count = doc.getLineCount();
        int center = Math.max(0, Math.min(around, count - 1));
        for (int d = 0; d <= SEARCH_RADIUS; d++) {
            int up = center - d, down = center + d;
            if (up < 0 && down >= count) break;
            if (up >= 0 && up != around && lineText(doc, up).equals(code)) return up;
            if (d > 0 && down < count && down != around && lineText(doc, down).equals(code)) return down;
        }
        return -1;
    }
}
