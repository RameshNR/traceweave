package com.example.flowtracker.code;

import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowProjects;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassOwner;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.uast.UClass;
import org.jetbrains.uast.UFile;
import org.jetbrains.uast.UMethod;
import org.jetbrains.uast.UastContextKt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Finds the project's classes and methods for the Flow Diagram window: classes by name or by a wildcard
 * pattern such as {@code *Cruise*.java}, the methods of a class, and the lines of a method. Everything but
 * {@link #pattern} must run in a read action.
 */
public final class FlowCodeSearch {
    private FlowCodeSearch() {}

    /** Letters a search needs before it runs. */
    public static final int MIN_LETTERS = 3;

    /**
     * A class (Java, Kotlin, Groovy) or a whole file (JSP, JavaScript, TLD, XML, ...).
     *
     * @param className the class as saved lines name it ("Outer.Inner"), or "-" for a file
     * @param declLine  0-based line of its declaration (the first line with code for a file)
     */
    public record ClassHit(String url, String fileName, String path, String className, String projectName, int declLine) {
        public boolean isFile() { return className.equals("-"); }
        public String name() { return isFile() ? fileName : className; }
    }

    /**
     * A method, or a block of a file (a function, a scriptlet, an element).
     *
     * @param name     the method as saved lines name it, e.g. "search()"
     * @param display  what the list shows, e.g. "search(String, int)"
     * @param start    0-based first line
     * @param end      0-based last line
     * @param declLine 0-based line of its name
     */
    public record MethodHit(String name, String display, int start, int end, int declLine) {}

    /** One line of code: 0-based number and text. */
    public record CodeLine(int line, String text) {}

    // ---------- patterns ----------

    /**
     * What a search text matches, or null while it has fewer than {@link #MIN_LETTERS} letters. With {@code *}
     * or {@code ?} it is a wildcard pattern for the whole name ({@code *Cruise*.java} matches
     * {@code OdysseyCruiseProcessor.java}); between slashes it is a regular expression found anywhere in the name
     * ({@code /Book.*Service/}); otherwise the name must contain it. All ignore case.
     */
    public static @Nullable Pattern pattern(@Nullable String query) {
        String q = query == null ? "" : query.strip();
        String letters = isRegex(q) ? q.substring(1, q.length() - 1).replaceAll("[^\\p{L}\\p{N}_$]", "")
                : q.replaceAll("[*?\\s]", "");
        if (letters.length() < MIN_LETTERS) return null;
        return filterPattern(q);
    }

    /** True for a search between slashes: {@code /regex/}. */
    static boolean isRegex(String q) {
        return q.length() > 2 && q.startsWith("/") && q.endsWith("/");
    }

    /** Why the search text can't be used (an invalid regular expression), or null. */
    public static @Nullable String patternError(@Nullable String query) {
        String q = query == null ? "" : query.strip();
        if (!isRegex(q)) return null;
        try {
            Pattern.compile(q.substring(1, q.length() - 1));
            return null;
        } catch (PatternSyntaxException e) {
            return "Not a valid regular expression: " + e.getDescription();
        }
    }

    /** True when the method's name (or its signature as listed) matches. */
    public static boolean matchesMethod(Pattern p, MethodHit m) {
        return p.matcher(m.display()).find() || p.matcher(m.name()).find()
                || p.matcher(m.name().replace("()", "")).find(); // so "get*" and "/^get/" match "getName()"
    }

    /**
     * The same as {@link #pattern} without a minimum length, for filtering a short list (a class's methods).
     * Null for an empty text (everything matches) or an invalid regular expression.
     */
    public static @Nullable Pattern filterPattern(@Nullable String query) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) return null;
        if (isRegex(q)) {
            try {
                return Pattern.compile(q.substring(1, q.length() - 1), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            } catch (PatternSyntaxException e) {
                return null;
            }
        }
        if (q.indexOf('*') < 0 && q.indexOf('?') < 0) {
            return Pattern.compile(Pattern.quote(q), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }
        StringBuilder re = new StringBuilder("^");
        for (char c : q.toCharArray()) {
            if (c == '*') re.append(".*");
            else if (c == '?') re.append('.');
            else re.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(re.append('$').toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    // ---------- classes ----------

    /**
     * Classes and files of the project whose class name or file name matches the search text, best matches
     * first: names starting with the text, then shorter names.
     */
    public static List<ClassHit> findClasses(Project project, String query, int limit) {
        Pattern p = pattern(query);
        if (p == null) return List.of();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

        // Files by name, and the files of classes by name (a file can hold classes with other names).
        Set<String> names = new LinkedHashSet<>();
        FilenameIndex.processAllFileNames(name -> {
            if (FlowLang.isSupportedName(name) && p.matcher(name).find()) names.add(name);
            return names.size() < 500;
        }, scope, null);
        Map<VirtualFile, Boolean> files = new LinkedHashMap<>(); // file -> its name matched
        for (String name : names) {
            for (VirtualFile vf : FilenameIndex.getVirtualFilesByName(name, scope)) files.put(vf, true);
        }
        PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
        GlobalSearchScope libraries = GlobalSearchScope.allScope(project);
        Map<VirtualFile, Boolean> libraryFiles = new LinkedHashMap<>(); // compiled classes of libraries (.class)
        int classes = 0;
        for (String name : cache.getAllClassNames()) {
            if (!p.matcher(name).find() || ++classes > 500) continue;
            for (PsiClass c : cache.getClassesByName(name, scope)) {
                PsiFile f = c.getContainingFile();
                VirtualFile vf = f == null ? null : f.getVirtualFile();
                if (vf != null) files.putIfAbsent(vf, false);
            }
            if (libraryFiles.size() >= 40) continue;
            for (PsiClass c : cache.getClassesByName(name, libraries)) {
                PsiFile f = c.getContainingFile();
                VirtualFile vf = f == null ? null : f.getVirtualFile();
                if (vf != null && !files.containsKey(vf) && FlowLang.isSupported(vf)) libraryFiles.putIfAbsent(vf, false);
            }
        }
        // The project's own files first; library classes (read from their decompiled .class) after them.
        libraryFiles.forEach(files::putIfAbsent);

        List<ClassHit> hits = new ArrayList<>();
        for (Map.Entry<VirtualFile, Boolean> f : files.entrySet()) {
            for (ClassHit h : classesIn(project, f.getKey())) {
                boolean topLevel = !h.className().contains(".");
                String simple = h.className().substring(h.className().lastIndexOf('.') + 1);
                if (h.isFile() || f.getValue() && topLevel || p.matcher(simple).find()) hits.add(h);
            }
            if (hits.size() >= limit * 3) break;
        }
        String q = query.strip().toLowerCase(Locale.ROOT);
        hits.sort(Comparator.<ClassHit>comparingInt(h -> isLibrary(h.url()) ? 1 : 0)
                .thenComparingInt(h -> h.name().toLowerCase(Locale.ROOT).startsWith(q) ? 0 : 1)
                .thenComparingInt(h -> h.name().length())
                .thenComparing(ClassHit::path));
        return hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
    }

    /** A file of the project: where it is and which project it belongs to. */
    public record FileHit(String url, String fileName, String path, String projectName) {}

    /**
     * Files of the project whose file name (or one of whose classes) matches the search text, best matches first:
     * names starting with the text, then shorter names.
     */
    public static List<FileHit> findFiles(Project project, String query, int limit) {
        Map<String, FileHit> files = new LinkedHashMap<>();
        for (ClassHit h : findClasses(project, query, limit * 3)) {
            files.putIfAbsent(h.url(), new FileHit(h.url(), h.fileName(), h.path(), h.projectName()));
        }
        String q = query.strip().toLowerCase(Locale.ROOT);
        List<FileHit> out = new ArrayList<>(files.values());
        out.sort(Comparator.<FileHit>comparingInt(f -> isLibrary(f.url()) ? 1 : 0)
                .thenComparingInt(f -> f.fileName().toLowerCase(Locale.ROOT).startsWith(q) ? 0 : 1)
                .thenComparingInt(f -> f.fileName().length())
                .thenComparing(FileHit::path));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** A library's file (a compiled .class in a jar), not one of the project's own. */
    public static boolean isLibrary(String url) {
        return url.startsWith("jar:") || url.endsWith(".class");
    }

    /** The classes of a file (nested ones as Outer.Inner), or the file itself when it has no classes. */
    public static List<ClassHit> classesIn(Project project, String url) {
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(url);
        return vf == null ? List.of() : classesIn(project, vf);
    }

    /** The classes of a file (nested ones as Outer.Inner), or the file itself when it has no classes. */
    static List<ClassHit> classesIn(Project project, VirtualFile vf) {
        List<ClassHit> out = new ArrayList<>();
        if (!FlowLang.isSupported(vf)) return out;
        PsiFile psi = PsiManager.getInstance(project).findFile(vf);
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (psi == null || doc == null) return out;
        String path = relativePath(project, vf);
        String projectName = FlowProjects.nameFor(project, vf);
        String fileName = vf.getName();
        if (FlowLang.usesJavaPsi(fileName) && psi instanceof PsiClassOwner owner) {
            for (PsiClass c : owner.getClasses()) addJava(c, "", vf, path, projectName, doc, out);
        } else if (FlowLang.isKotlin(fileName)) {
            UFile file = UastContextKt.toUElement(psi, UFile.class);
            if (file != null) for (UClass c : file.getClasses()) addKotlin(c, "", vf, path, projectName, doc, out);
        }
        if (out.isEmpty()) out.add(new ClassHit(vf.getUrl(), fileName, path, "-", projectName, firstCodeLine(doc)));
        return out;
    }

    private static void addJava(PsiClass c, String outer, VirtualFile vf, String path, String project, Document doc, List<ClassHit> out) {
        if (c.getName() == null) return;
        String name = outer.isEmpty() ? c.getName() : outer + "." + c.getName();
        out.add(new ClassHit(vf.getUrl(), vf.getName(), path, name, project, lineOf(doc, nameOffset(c))));
        for (PsiClass inner : c.getInnerClasses()) addJava(inner, name, vf, path, project, doc, out);
    }

    private static void addKotlin(UClass c, String outer, VirtualFile vf, String path, String project, Document doc, List<ClassHit> out) {
        if (c.getName() == null || c.getSourcePsi() == null) return;
        String name = outer.isEmpty() ? c.getName() : outer + "." + c.getName();
        out.add(new ClassHit(vf.getUrl(), vf.getName(), path, name, project, lineOf(doc, anchorOffset(c.getUastAnchor(), c.getSourcePsi()))));
        for (UClass inner : c.getInnerClasses()) addKotlin(inner, name, vf, path, project, doc, out);
    }

    // ---------- methods ----------

    /** The methods of a class (constructors too), or the blocks of a file, in source order. */
    public static List<MethodHit> methods(Project project, ClassHit cls) {
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(cls.url());
        if (vf == null) return List.of();
        PsiFile psi = PsiManager.getInstance(project).findFile(vf);
        Document doc = FileDocumentManager.getInstance().getDocument(vf);
        if (psi == null || doc == null) return List.of();
        List<MethodHit> out = new ArrayList<>();
        if (!cls.isFile() && FlowLang.usesJavaPsi(vf.getName()) && psi instanceof PsiClassOwner owner) {
            PsiClass c = findJava(owner.getClasses(), cls.className(), "");
            if (c != null) {
                for (PsiMethod m : c.getMethods()) {
                    if (m.getTextRange() == null) continue;
                    out.add(new MethodHit(methodName(m), display(m), lineOf(doc, m.getTextRange().getStartOffset()),
                            lineOf(doc, m.getTextRange().getEndOffset()), lineOf(doc, nameOffset(m))));
                }
            }
        } else if (!cls.isFile() && FlowLang.isKotlin(vf.getName())) {
            UFile file = UastContextKt.toUElement(psi, UFile.class);
            UClass c = file == null ? null : findKotlin(file.getClasses(), cls.className(), "");
            if (c != null) {
                for (UMethod m : c.getMethods()) {
                    PsiElement src = m.getSourcePsi();
                    if (src == null || src.getTextRange() == null) continue; // generated (e.g. data class members)
                    out.add(new MethodHit(methodName(m), display(m), lineOf(doc, src.getTextRange().getStartOffset()),
                            lineOf(doc, src.getTextRange().getEndOffset()), lineOf(doc, anchorOffset(m.getUastAnchor(), src))));
                }
            }
        } else {
            out.addAll(blocks(doc, vf.getName()));
        }
        out.sort(Comparator.comparingInt(MethodHit::start));
        return out;
    }

    /**
     * The blocks of a file read as text: each run of lines with the same method (a function, a scriptlet, an
     * element), as TraceWeave names them when a line is saved.
     */
    static List<MethodHit> blocks(Document doc, String fileName) {
        List<MethodHit> out = new ArrayList<>();
        CharSequence text = doc.getCharsSequence();
        String current = null;
        int start = -1, last = -1;
        for (int line = 0; line < doc.getLineCount(); line++) {
            int s = doc.getLineStartOffset(line), e = doc.getLineEndOffset(line);
            String code = text.subSequence(s, e).toString();
            if (code.isBlank()) continue;
            int offset = s + (code.length() - code.stripLeading().length());
            String method = FlowOutline.locate(text, offset, fileName).methodName();
            if (!method.equals(current)) {
                if (current != null) out.add(new MethodHit(current, current, start, last, start));
                current = method;
                start = line;
            }
            last = line;
        }
        if (current != null) out.add(new MethodHit(current, current, start, last, start));
        return out;
    }

    /** "search()" (or "Booking() [constructor]"), as saved lines name the method. */
    private static String methodName(PsiMethod m) {
        return m.isConstructor() ? m.getName() + "() [constructor]" : m.getName() + "()";
    }

    /** "search(String, int)". */
    private static String display(PsiMethod m) {
        List<String> params = new ArrayList<>();
        for (PsiParameter p : m.getParameterList().getParameters()) params.add(p.getType().getPresentableText());
        return m.getName() + "(" + String.join(", ", params) + ")" + (m.isConstructor() ? "  [constructor]" : "");
    }

    private static @Nullable PsiClass findJava(PsiClass[] classes, String wanted, String outer) {
        for (PsiClass c : classes) {
            if (c.getName() == null) continue;
            String name = outer.isEmpty() ? c.getName() : outer + "." + c.getName();
            if (name.equals(wanted)) return c;
            PsiClass inner = findJava(c.getInnerClasses(), wanted, name);
            if (inner != null) return inner;
        }
        return null;
    }

    private static @Nullable UClass findKotlin(List<UClass> classes, String wanted, String outer) {
        for (UClass c : classes) {
            if (c.getName() == null) continue;
            String name = outer.isEmpty() ? c.getName() : outer + "." + c.getName();
            if (name.equals(wanted)) return c;
            UClass inner = findKotlin(List.of(c.getInnerClasses()), wanted, name);
            if (inner != null) return inner;
        }
        return null;
    }

    // ---------- lines ----------

    /** The lines from {@code start} to {@code end} (0-based, inclusive) of a file. */
    public static List<CodeLine> lines(String url, int start, int end) {
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(url);
        Document doc = vf == null ? null : FileDocumentManager.getInstance().getDocument(vf);
        if (doc == null) return List.of();
        List<CodeLine> out = new ArrayList<>();
        CharSequence text = doc.getCharsSequence();
        for (int line = Math.max(0, start); line <= Math.min(end, doc.getLineCount() - 1); line++) {
            out.add(new CodeLine(line, text.subSequence(doc.getLineStartOffset(line), doc.getLineEndOffset(line)).toString()));
        }
        return out;
    }

    /**
     * The 0-based first and last line of the method, function or block around a 0-based line of a file (Java and
     * Kotlin from the code, other files read as blocks), or null when there is none.
     */
    @Nullable
    public static int[] methodRange(Project project, String url, int line) {
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(url);
        Document doc = vf == null ? null : FileDocumentManager.getInstance().getDocument(vf);
        PsiFile psi = vf == null ? null : PsiManager.getInstance(project).findFile(vf);
        if (doc == null || psi == null || line < 0 || line >= doc.getLineCount()) return null;
        String text = doc.getCharsSequence().subSequence(doc.getLineStartOffset(line), doc.getLineEndOffset(line)).toString();
        PsiElement el = psi.findElementAt(doc.getLineStartOffset(line) + (text.length() - text.stripLeading().length()));
        PsiElement method = null;
        if (FlowLang.usesJavaPsi(vf.getName())) {
            method = com.intellij.psi.util.PsiTreeUtil.getParentOfType(el, PsiMethod.class, false);
        } else if (FlowLang.isKotlin(vf.getName())) {
            UMethod m = FlowLang.uParent(FlowLang.uElementAt(el), UMethod.class);
            method = m == null ? null : m.getSourcePsi();
        }
        if (method != null && method.getTextRange() != null) {
            return new int[]{lineOf(doc, method.getTextRange().getStartOffset()), lineOf(doc, method.getTextRange().getEndOffset())};
        }
        for (MethodHit h : blocks(doc, vf.getName())) {
            if (h.start() <= line && line <= h.end()) return new int[]{h.start(), h.end()};
        }
        return null;
    }

    /**
     * The saved line for a 0-based line of a file, named like a line saved from the gutter; null for an empty
     * line or one the flow has already.
     */
    public static @Nullable FlowEntry entry(Project project, String url, int line) {
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(url);
        Document doc = vf == null ? null : FileDocumentManager.getInstance().getDocument(vf);
        return doc == null ? null : FlowCapture.entryFor(project, doc, line);
    }

    /** The saved line for a 0-based line of a file, also when the flow has it already; null for an empty line. */
    public static @Nullable FlowEntry lineEntry(Project project, String url, int line) {
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(url);
        Document doc = vf == null ? null : FileDocumentManager.getInstance().getDocument(vf);
        return doc == null ? null : FlowCapture.lineEntry(project, doc, line);
    }

    // ---------- helpers ----------

    private static int nameOffset(PsiElement e) {
        PsiElement id = e instanceof PsiNameIdentifierOwner o ? o.getNameIdentifier() : null;
        return (id != null ? id : e).getTextRange().getStartOffset();
    }

    private static int anchorOffset(@Nullable org.jetbrains.uast.UElement anchor, PsiElement fallback) {
        PsiElement a = anchor == null ? null : anchor.getSourcePsi();
        return (a != null ? a : fallback).getTextRange().getStartOffset();
    }

    private static int lineOf(Document doc, int offset) {
        return doc.getLineNumber(Math.max(0, Math.min(offset, doc.getTextLength())));
    }

    private static int firstCodeLine(Document doc) {
        CharSequence text = doc.getCharsSequence();
        for (int line = 0; line < doc.getLineCount(); line++) {
            if (!text.subSequence(doc.getLineStartOffset(line), doc.getLineEndOffset(line)).toString().isBlank()) return line;
        }
        return 0;
    }

    private static String relativePath(Project project, VirtualFile vf) {
        String base = project.getBasePath();
        String path = vf.getPath();
        if (base != null && path.startsWith(base + "/")) return path.substring(base.length() + 1);
        return path;
    }
}
