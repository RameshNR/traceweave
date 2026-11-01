package com.example.flowtracker.code;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UastContextKt;

import java.util.Locale;
import java.util.Map;

/**
 * The file types TraceWeave works in and how each is understood:
 * <ul>
 *   <li>Java and Groovy through Java PSI, Kotlin through UAST (the IDE's common syntax tree for JVM
 *       languages, so no compile-time dependency on the Kotlin plugin is needed). Sync follows their calls.</li>
 *   <li>Web and config files (JSP, JavaScript / TypeScript, TLD, XML, HTML, properties, YAML, SQL) through
 *       {@link FlowOutline}, which finds the enclosing function, tag or element from the text, since the
 *       Community edition has no parser for most of them.</li>
 *   <li>Other popular languages, read as text the same way: brace languages (C#, C, C++, Go, Rust, Swift, PHP,
 *       Dart, Scala, and the decompiled Java of a .class file) by their {} blocks, Python and Ruby by indentation.</li>
 * </ul>
 */
public final class FlowLang {
    private FlowLang() {}

    public enum Kind {
        JAVA("java"), KOTLIN("kotlin"), GROOVY("groovy"),
        /** JavaScript / TypeScript, including JSX and TSX. */
        SCRIPT("javascript"),
        /** JSP pages and tag files: markup with Java scriptlets. */
        JSP("jsp"),
        /** XML-like markup: TLD, XML, HTML. */
        MARKUP("xml"),
        /** Line-oriented config and query files. */
        CONFIG("properties"),
        /** Other brace languages (C#, C / C++, Go, Rust, Swift, PHP, Dart, Scala) and decompiled .class files. */
        BRACES("c"),
        /** Indented languages: Python, Ruby. */
        INDENTED("python");

        final String fence;

        Kind(String fence) {
            this.fence = fence;
        }
    }

    private static final Map<String, Kind> BY_EXTENSION = Map.ofEntries(
            Map.entry("java", Kind.JAVA),
            Map.entry("kt", Kind.KOTLIN), Map.entry("kts", Kind.KOTLIN),
            Map.entry("groovy", Kind.GROOVY), Map.entry("gradle", Kind.GROOVY),
            Map.entry("js", Kind.SCRIPT), Map.entry("jsx", Kind.SCRIPT), Map.entry("mjs", Kind.SCRIPT),
            Map.entry("cjs", Kind.SCRIPT), Map.entry("ts", Kind.SCRIPT), Map.entry("tsx", Kind.SCRIPT),
            Map.entry("jsp", Kind.JSP), Map.entry("jspf", Kind.JSP), Map.entry("jspx", Kind.JSP),
            Map.entry("tag", Kind.JSP), Map.entry("tagx", Kind.JSP),
            Map.entry("tld", Kind.MARKUP), Map.entry("xml", Kind.MARKUP), Map.entry("html", Kind.MARKUP),
            Map.entry("htm", Kind.MARKUP), Map.entry("xhtml", Kind.MARKUP),
            Map.entry("properties", Kind.CONFIG), Map.entry("yaml", Kind.CONFIG), Map.entry("yml", Kind.CONFIG),
            Map.entry("sql", Kind.CONFIG),
            Map.entry("cs", Kind.BRACES), Map.entry("c", Kind.BRACES), Map.entry("h", Kind.BRACES),
            Map.entry("cpp", Kind.BRACES), Map.entry("cc", Kind.BRACES), Map.entry("cxx", Kind.BRACES),
            Map.entry("hpp", Kind.BRACES), Map.entry("hh", Kind.BRACES), Map.entry("go", Kind.BRACES),
            Map.entry("rs", Kind.BRACES), Map.entry("swift", Kind.BRACES), Map.entry("php", Kind.BRACES),
            Map.entry("dart", Kind.BRACES), Map.entry("scala", Kind.BRACES), Map.entry("class", Kind.BRACES),
            Map.entry("py", Kind.INDENTED), Map.entry("rb", Kind.INDENTED));

    /** Code fence names that differ from the kind's default. */
    private static final Map<String, String> FENCE_BY_EXTENSION = Map.ofEntries(
            Map.entry("jsx", "jsx"), Map.entry("ts", "typescript"), Map.entry("tsx", "tsx"), Map.entry("html", "html"),
            Map.entry("htm", "html"), Map.entry("xhtml", "html"), Map.entry("yaml", "yaml"), Map.entry("yml", "yaml"),
            Map.entry("sql", "sql"), Map.entry("cs", "csharp"), Map.entry("cpp", "cpp"), Map.entry("cc", "cpp"),
            Map.entry("cxx", "cpp"), Map.entry("hpp", "cpp"), Map.entry("hh", "cpp"), Map.entry("go", "go"),
            Map.entry("rs", "rust"), Map.entry("swift", "swift"), Map.entry("php", "php"), Map.entry("dart", "dart"),
            Map.entry("scala", "scala"), Map.entry("class", "java"), Map.entry("rb", "ruby"));

    /** The kind of a file, or null when TraceWeave doesn't support it. */
    public static @Nullable Kind kind(String fileName) {
        return BY_EXTENSION.get(extension(fileName));
    }

    public static boolean isSupported(@Nullable VirtualFile vf) {
        return vf != null && isSupportedName(vf.getName());
    }

    public static boolean isSupportedName(String fileName) {
        return kind(fileName) != null;
    }

    static boolean isJava(String fileName) {
        return kind(fileName) == Kind.JAVA;
    }

    public static boolean isKotlin(String fileName) {
        return kind(fileName) == Kind.KOTLIN;
    }

    /** Java and Groovy: their PSI has real PsiMethod / PsiClass elements. */
    public static boolean usesJavaPsi(String fileName) {
        Kind k = kind(fileName);
        return k == Kind.JAVA || k == Kind.GROOVY;
    }

    /** File name without its extension, e.g. "BookingService" for BookingService.kt. */
    public static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Language name for Markdown code fences. */
    public static String fence(String fileName) {
        String special = FENCE_BY_EXTENSION.get(extension(fileName));
        if (special != null) return special;
        Kind k = kind(fileName);
        return k == null ? "" : k.fence;
    }

    /** The innermost UAST element at or above a PSI element (leaf tokens have none of their own). */
    static @Nullable UElement uElementAt(@Nullable PsiElement el) {
        for (PsiElement p = el; p != null && !(p instanceof PsiFile); p = p.getParent()) {
            UElement u = UastContextKt.toUElement(p);
            if (u != null) return u;
        }
        return null;
    }

    /** The closest UAST element of the given type at or above u. */
    static <T extends UElement> @Nullable T uParent(@Nullable UElement u, Class<T> type) {
        for (UElement x = u; x != null; x = x.getUastParent()) {
            if (type.isInstance(x)) return type.cast(x);
        }
        return null;
    }
}
