package com.example.flowtracker.code;

import com.example.flowtracker.compat.FlowCompat;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowProjects;
import com.example.flowtracker.model.FlowStore;
import java.util.ArrayList;
import java.util.List;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassInitializer;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPackageStatement;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.uast.UClass;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UField;
import org.jetbrains.uast.UImportStatement;
import org.jetbrains.uast.UMethod;

/**
 * Builds a FlowEntry for a given line: class and method come from PSI (the IDE's parsed code tree) for Java
 * and Groovy, from UAST for Kotlin, and from {@link FlowOutline} (the text) for web and config files.
 */
public final class FlowCapture {
    private FlowCapture() {}

    public static void addLine(Project project, Document doc, int line) {
        addLine(project, doc, line, FlowStore.get(project).isAddAtTop());
    }

    /** @param atTop where the line goes; the debugger recorder always appends, to keep execution order */
    public static boolean addLine(Project project, Document doc, int line, boolean atTop) {
        FlowEntry entry = entryFor(project, doc, line);
        if (entry == null) return false;
        boolean added = FlowStore.get(project).add(entry, atTop);
        if (added) FlowSync.apply(project);
        return added;
    }

    /**
     * Adds several lines of one document (e.g. an editor selection) as one undoable step, in line order,
     * skipping empty lines and lines that are saved already. Returns how many were added.
     */
    public static int addLines(Project project, Document doc, List<Integer> lines) {
        List<FlowEntry> entries = new ArrayList<>();
        for (int line : lines) {
            FlowEntry e = entryFor(project, doc, line);
            if (e != null) entries.add(e);
        }
        FlowStore store = FlowStore.get(project);
        int added = store.addAll(entries, store.isAddAtTop());
        if (added > 0) FlowSync.apply(project);
        return added;
    }

    /** The entry for a 0-based line, or null for an empty line, an unsupported file or a line saved already. */
    public static @Nullable FlowEntry entryFor(Project project, Document doc, int line) {
        if (line < 0 || line >= doc.getLineCount()) return null;

        VirtualFile vf = FileDocumentManager.getInstance().getFile(doc);
        if (vf == null || !FlowLang.isSupported(vf)) return null;

        // Already added? Do nothing (no duplicates).
        if (FlowStore.get(project).contains(vf.getUrl(), line + 1)) return null;

        int start = doc.getLineStartOffset(line);
        int end = doc.getLineEndOffset(line);
        String lineText = doc.getCharsSequence().subSequence(start, end).toString();
        String trimmed = lineText.trim();
        if (trimmed.isEmpty()) return null;

        // Point at the first non-whitespace character of the line.
        int offset = start + (lineText.length() - lineText.stripLeading().length());

        String fileName = vf.getName();
        String[] names = FlowCompat.read(() -> {
            if (!FlowLang.usesJavaPsi(fileName) && !FlowLang.isKotlin(fileName)) {
                FlowOutline.Scope scope = FlowOutline.locate(doc.getCharsSequence(), offset, fileName);
                return new String[]{scope.className(), scope.methodName()};
            }
            PsiFile psi = PsiDocumentManager.getInstance(project).getPsiFile(doc);
            if (psi == null) return new String[]{"-", "-"};
            PsiElement el = psi.findElementAt(offset);
            if (FlowLang.usesJavaPsi(fileName)) return new String[]{classNameOf(el), methodNameOf(el)};
            UElement u = FlowLang.uElementAt(el);
            return new String[]{uClassNameOf(u), uMethodNameOf(u)};
        });

        FlowEntry entry = new FlowEntry(vf.getName(), vf.getUrl(), names[0], names[1], line + 1, trimmed);
        entry.projectName = FlowProjects.nameFor(project, vf);
        return entry;
    }

    // ---------- Kotlin (UAST) ----------

    private static String uMethodNameOf(UElement u) {
        UMethod m = FlowLang.uParent(u, UMethod.class);
        if (m == null) {
            if (FlowLang.uParent(u, UField.class) != null) return "[properties]";
            if (FlowLang.uParent(u, UImportStatement.class) != null) return "[imports]";
            return FlowLang.uParent(u, UClass.class) != null ? "[class]" : "-";
        }
        return m.isConstructor() ? m.getName() + "() [constructor]" : m.getName() + "()";
    }

    private static String uClassNameOf(UElement u) {
        StringBuilder sb = new StringBuilder();
        // Walk outward so nested classes read as Outer.Inner (anonymous objects are skipped).
        for (UClass k = FlowLang.uParent(u, UClass.class); k != null;
             k = FlowLang.uParent(k.getUastParent(), UClass.class)) {
            if (k.getName() == null) continue;
            if (sb.length() > 0) sb.insert(0, ".");
            sb.insert(0, k.getName());
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static String methodNameOf(PsiElement el) {
        if (el == null) return "-";
        PsiMethod m = PsiTreeUtil.getParentOfType(el, PsiMethod.class, false);
        if (m != null) {
            return m.isConstructor() ? m.getName() + "() [constructor]" : m.getName() + "()";
        }
        if (PsiTreeUtil.getParentOfType(el, PsiClassInitializer.class, false) != null) {
            return "[initializer block]";
        }
        if (PsiTreeUtil.getParentOfType(el, PsiField.class, false) != null) return "[fields]";
        if (PsiTreeUtil.getParentOfType(el, PsiImportList.class, PsiPackageStatement.class) != null) return "[imports]";
        if (PsiTreeUtil.getParentOfType(el, PsiClass.class, false) != null) return "[class]"; // header, annotations
        return "-";
    }

    private static String classNameOf(PsiElement el) {
        if (el == null) return "-";
        PsiClass c = PsiTreeUtil.getParentOfType(el, PsiClass.class, false);
        StringBuilder sb = new StringBuilder();
        // Walk outward so inner classes read as Outer.Inner (anonymous classes are skipped).
        for (PsiClass k = c; k != null; k = PsiTreeUtil.getParentOfType(k, PsiClass.class, true)) {
            if (k.getName() == null) continue;
            if (sb.length() > 0) sb.insert(0, ".");
            sb.insert(0, k.getName());
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }
}
