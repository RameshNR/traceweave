package com.example.flowtracker.code;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the "class" and "method" a line belongs to in files the IDE has no parser for (the Community
 * edition can't parse JavaScript or JSP): the enclosing function or callback in scripts, the method or
 * scriptlet in JSP pages, the tag / function in TLD files and the element in XML / HTML. It reads the text
 * only, so it is a best effort; names fall back to "-" (shown as part of the file) when nothing fits.
 */
public final class FlowOutline {
    private FlowOutline() {}

    /** Where a line is: a class (or "-") and a method label such as "search()" or "tag status". */
    public record Scope(String className, String methodName) {}

    private static final Scope NONE = new Scope("-", "-");

    private static final Set<String> NOT_FUNCTIONS = Set.of(
            "if", "for", "while", "switch", "catch", "function", "return", "with", "synchronized", "else",
            "do", "try", "finally", "new", "typeof", "await", "yield", "case");

    private static final Set<String> VOID_HTML = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr");

    private static final Pattern FUNCTION_DECL =
            Pattern.compile("(?:^|[^\\w$.])function\\s*\\*?\\s*([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern ASSIGNED_FUNCTION = Pattern.compile(
            "([A-Za-z_$][\\w$]*)\\s*[:=]\\s*(?:async\\s+)?(?:function\\b[^(]*\\([^)]*\\)|(?:\\([^()]*\\)|[A-Za-z_$][\\w$]*)\\s*=>)\\s*$");
    private static final Pattern CLASS_DECL = Pattern.compile(
            "(?:^|[^\\w$])(?:class|interface|enum|struct|trait|object|record|protocol|extension)\\s+([A-Za-z_$][\\w$]*)");
    /** Rust: impl Type { or impl Trait for Type {. */
    private static final Pattern IMPL_DECL = Pattern.compile("(?:^|[^\\w$])impl(?:\\s*<[^>]*>)?\\s+(?:[\\w:<>]+\\s+for\\s+)?([A-Za-z_][\\w]*)");
    /** Functions with a keyword: Go (func, with a receiver), Rust (fn), Kotlin / Swift (fun / func), Scala (def). */
    private static final Pattern KEYWORD_FUNCTION = Pattern.compile(
            "(?:^|[^\\w$.])(?:func|fn|fun|def)\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_$][\\w$]*)\\s*[<(\\[]");
    /** What may follow a parameter list before the block: C++ const / override / noexcept, Dart async, Swift throws, -> Type. */
    private static final Pattern AFTER_PARAMS = Pattern.compile(
            "\\)\\s*(?:(?:const|override|final|noexcept|async\\*?|sync\\*|throws|rethrows|mutating)\\s*)*(?:->\\s*[^{};=]+)?$");
    /** Python and Ruby: def name / def self.name, and class / module Name. */
    private static final Pattern INDENT_DEF = Pattern.compile("^\\s*(?:async\\s+)?def\\s+(?:self\\.)?([A-Za-z_][\\w]*[?!=]?)");
    private static final Pattern INDENT_CLASS = Pattern.compile("^\\s*(?:class|module)\\s+([A-Za-z_]\\w*(?:::[A-Za-z_]\\w*)*)");
    private static final Pattern TS_RETURN_TYPE = Pattern.compile("\\)\\s*:\\s*[^(){};=]+$");
    private static final Pattern THROWS = Pattern.compile("\\)\\s*throws\\s+[\\w.,\\s]+$");
    private static final Pattern TLD_NAME = Pattern.compile("<name>\\s*([^<]+?)\\s*</name>");
    private static final Pattern SHORT_NAME = Pattern.compile("<short-name>\\s*([^<]+?)\\s*</short-name>");
    private static final Pattern ID_OR_NAME = Pattern.compile("\\b(id|name)\\s*=\\s*[\"']([^\"']+)[\"']");

    /**
     * @param text     the whole file
     * @param offset   the first non-blank character of the line
     * @param fileName decides how the text is read (see {@link FlowLang.Kind})
     */
    public static Scope locate(CharSequence text, int offset, String fileName) {
        FlowLang.Kind kind = FlowLang.kind(fileName);
        if (kind == null || offset < 0 || offset > text.length()) return NONE;
        return switch (kind) {
            case SCRIPT -> scriptScope(text, maskCode(text, offset, false), offset, NONE);
            case JSP -> jspScope(text, offset, fileName);
            case MARKUP -> markupScope(text, offset, fileName, NONE);
            case CONFIG -> configScope(text, offset, fileName);
            case BRACES -> scriptScope(text, maskCode(text, offset, false), offset, NONE);
            case INDENTED -> indentScope(text, offset);
            default -> NONE; // JVM languages are read with PSI instead
        };
    }

    // ---------- JSP ----------

    private static Scope jspScope(CharSequence text, int offset, String fileName) {
        String code = maskCode(text, offset, true);
        String region = jspRegion(text, offset);
        Scope fallback = switch (region) {
            case "declaration" -> new Scope("-", "[declaration]");
            case "scriptlet" -> new Scope("-", "[scriptlet]");
            case "script" -> new Scope("-", "[script]");
            default -> null;
        };
        if (fallback != null) return scriptScope(text, code, offset, fallback);
        return markupScope(text, offset, fileName, new Scope("-", "[page]"));
    }

    /** "declaration" inside <%! %>, "scriptlet" inside <% %>, "script" inside <script>, else "markup". */
    private static String jspRegion(CharSequence text, int offset) {
        String before = text.subSequence(0, offset).toString();
        int open = before.lastIndexOf("<%");
        if (open >= 0 && before.indexOf("%>", open) < 0 && !before.startsWith("<%--", open)
                && !before.startsWith("<%@", open) && !before.startsWith("<%=", open)) {
            return before.startsWith("<%!", open) ? "declaration" : "scriptlet";
        }
        String lower = before.toLowerCase(Locale.ROOT);
        int script = lower.lastIndexOf("<script");
        if (script >= 0 && lower.indexOf("</script", script) < 0) return "script";
        return "markup";
    }

    // ---------- scripts (and Java inside JSP) ----------

    /**
     * Walks outward from the line over enclosing { } blocks. The nearest named function is the method
     * (falling back to the innermost anonymous callback, e.g. addEventListener("submit") callback), and the
     * nearest class declaration is the class.
     */
    private static Scope scriptScope(CharSequence text, String code, int offset, Scope fallback) {
        String cls = null, method = null, callback = null;
        int depth = 0;
        for (int i = Math.min(offset, code.length()) - 1; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == '}') {
                depth++;
            } else if (c == '{') {
                if (depth > 0) { depth--; continue; }
                int start = headerStart(code, i);
                String header = collapse(text.subSequence(start, i).toString());
                if (method == null) {
                    String fn = functionName(header);
                    if (fn != null) method = fn + "()";
                    else if (callback == null) callback = callbackName(header);
                }
                Matcher m = CLASS_DECL.matcher(header);
                Matcher impl = IMPL_DECL.matcher(header);
                if (m.find() || impl.find()) {
                    cls = m.find(0) ? m.group(1) : impl.group(1);
                    break;
                }
            }
        }
        if (method == null) method = callback;
        if (method == null && cls == null) return fallback;
        return new Scope(cls == null ? "-" : cls, method == null ? fallback.methodName : method);
    }

    /**
     * Python and Ruby: the nearest def above the line with less indentation is the method, and the nearest class
     * (or module) above that is the class.
     */
    private static Scope indentScope(CharSequence text, int offset) {
        String before = text.subSequence(0, Math.min(offset, text.length())).toString();
        String[] lines = before.split("\\n", -1);
        int indent = indentOf(lines[lines.length - 1] + text.subSequence(offset, Math.min(text.length(), offset + 1)));
        String method = null, cls = null;
        for (int i = lines.length - 2; i >= 0; i--) {
            String l = lines[i];
            if (l.isBlank() || l.stripLeading().startsWith("#")) continue;
            int in = indentOf(l);
            if (in >= indent) continue;
            indent = in;
            Matcher def = INDENT_DEF.matcher(l);
            if (method == null && def.find()) {
                method = def.group(1) + "()";
                continue;
            }
            Matcher c = INDENT_CLASS.matcher(l);
            if (c.find()) {
                cls = c.group(1);
                break;
            }
            if (in == 0) break;
        }
        // The line is a def or class itself.
        String own = lines[lines.length - 1] + text.subSequence(offset, lineEnd(text, offset, text.length())).toString();
        Matcher def = INDENT_DEF.matcher(own);
        if (method == null && def.find()) method = def.group(1) + "()";
        if (method == null && cls == null) return NONE;
        return new Scope(cls == null ? "-" : cls, method == null ? "[top level]" : method);
    }

    private static int indentOf(String line) {
        int n = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') n++;
            else if (c == '\t') n += 4;
            else break;
        }
        return n;
    }

    /**
     * Start of the code before a '{': just after the previous ';', '{', '}' or the start of the code region
     * (JSP markup is masked with {@link #OUTSIDE}), at most 400 characters back.
     */
    private static int headerStart(String code, int brace) {
        int limit = Math.max(0, brace - 400);
        for (int j = brace - 1; j >= limit; j--) {
            char c = code.charAt(j);
            if (c == ';' || c == '{' || c == '}' || c == OUTSIDE) return j + 1;
        }
        return limit;
    }

    /** Mask character for text that is not code at all (JSP markup and the <% %> markers). */
    private static final char OUTSIDE = '\0';

    /** The name of the function a block header declares, or null (control flow, calls, anonymous code). */
    static @Nullable String functionName(String header) {
        Matcher m = FUNCTION_DECL.matcher(header);
        String found = null;
        while (m.find()) found = m.group(1);
        if (found != null) return found;
        m = KEYWORD_FUNCTION.matcher(header);
        while (m.find()) found = m.group(1);
        if (found != null) return found;
        m = ASSIGNED_FUNCTION.matcher(header);
        if (m.find()) return m.group(1);

        // A method: "name(params)" at the end, optionally with "throws ..." or a TypeScript return type.
        String h = AFTER_PARAMS.matcher(header).replaceFirst(")");
        h = THROWS.matcher(h).replaceFirst(")");
        h = TS_RETURN_TYPE.matcher(h).replaceFirst(")").strip();
        if (!h.endsWith(")")) return null;
        int open = matchingOpen(h, h.length() - 1);
        if (open <= 0) return null;
        String before = h.substring(0, open).stripTrailing();
        Matcher id = Pattern.compile("([A-Za-z_$][\\w$]*)$").matcher(before);
        if (!id.find()) return null;
        String name = id.group(1);
        String rest = before.substring(0, id.start()).stripTrailing();
        if (NOT_FUNCTIONS.contains(name) || rest.endsWith(".") || rest.endsWith("new")
                || rest.contains("=") || rest.contains("(")) {
            return null; // a call, an anonymous class, or control flow
        }
        return name;
    }

    /** "addEventListener(\"submit\") callback" for a block that is an anonymous callback argument, else null. */
    static @Nullable String callbackName(String header) {
        String h = header.strip();
        if (!h.endsWith("=>") && !h.matches(".*function\\s*\\*?\\s*\\([^()]*\\)$")) return null;
        int depth = 0;
        for (int i = h.length() - 1; i >= 0; i--) {
            char c = h.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') {
                if (depth > 0) { depth--; continue; }
                Matcher id = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*$").matcher(h.substring(0, i));
                if (!id.find()) return null;
                Matcher arg = Pattern.compile("^\\s*([\"'`])([^\"'`]*)\\1").matcher(h.substring(i + 1));
                return id.group(1) + (arg.find() ? "(\"" + arg.group(2) + "\")" : "()") + " callback";
            }
        }
        return null;
    }

    private static int matchingOpen(String s, int close) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == ')') depth++;
            else if (c == '(' && --depth == 0) return i;
        }
        return -1;
    }

    /**
     * A copy of the text up to {@code end} where strings and comments are blanked out, so braces and
     * semicolons in them are ignored. For JSP, everything that isn't code inside <% %> or <script> becomes
     * {@link #OUTSIDE}. Same length as the original, so positions match.
     */
    private static String maskCode(CharSequence text, int end, boolean jsp) {
        return maskCode(text, end, jsp, true);
    }

    /** @param scripts for JSP: also keep <script> code (false = only the Java in <% %>) */
    private static String maskCode(CharSequence text, int end, boolean jsp, boolean scripts) {
        char[] out = new char[end];
        int i = 0;
        boolean inCode = !jsp;
        while (i < end) {
            char c = text.charAt(i);
            if (jsp && !inCode) {
                if (startsWith(text, i, "<%--")) { i = outside(out, i, indexOf(text, "--%>", i, end, 4)); continue; }
                if (startsWith(text, i, "<%@")) { i = outside(out, i, indexOf(text, "%>", i, end, 2)); continue; }
                if (startsWith(text, i, "<%")) { i = outside(out, i, Math.min(end, i + (startsWith(text, i, "<%!") || startsWith(text, i, "<%=") ? 3 : 2))); inCode = true; continue; }
                if (scripts && startsWithIgnoreCase(text, i, "<script")) { i = outside(out, i, indexOf(text, ">", i, end, 1)); inCode = true; continue; }
                out[i++] = OUTSIDE;
                continue;
            }
            if (jsp && startsWith(text, i, "%>")) { i = outside(out, i, Math.min(end, i + 2)); inCode = false; continue; }
            if (jsp && startsWithIgnoreCase(text, i, "</script")) { i = outside(out, i, indexOf(text, ">", i, end, 1)); inCode = false; continue; }
            if (startsWith(text, i, "//")) { i = blank(out, i, lineEnd(text, i, end)); continue; }
            if (startsWith(text, i, "/*")) { i = blank(out, i, indexOf(text, "*/", i + 2, end, 2)); continue; }
            if (c == '"' || c == '\'' || c == '`') {
                int j = i + 1;
                while (j < end && text.charAt(j) != c && (c == '`' || text.charAt(j) != '\n')) {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
                // Keep the quotes, blank the inside: "x" still counts as an argument.
                int close = Math.min(end, j + 1);
                blank(out, i, close);
                out[i] = c;
                if (close - 1 > i && close - 1 < end) out[close - 1] = text.charAt(close - 1);
                i = close;
                continue;
            }
            out[i++] = c;
        }
        return new String(out);
    }

    private static int blank(char[] out, int from, int to) {
        for (int k = from; k < to && k < out.length; k++) out[k] = ' ';
        return Math.max(from + 1, to);
    }

    private static int outside(char[] out, int from, int to) {
        for (int k = from; k < to && k < out.length; k++) out[k] = OUTSIDE;
        return Math.max(from + 1, to);
    }

    /** Index just after the next occurrence of s (or end). */
    private static int indexOf(CharSequence text, String s, int from, int end, int len) {
        int at = text.toString().indexOf(s, from);
        return at < 0 || at >= end ? end : Math.min(end, at + len);
    }

    private static int lineEnd(CharSequence text, int from, int end) {
        for (int k = from; k < end; k++) if (text.charAt(k) == '\n') return k;
        return end;
    }

    private static boolean startsWith(CharSequence text, int at, String s) {
        return at + s.length() <= text.length() && text.subSequence(at, at + s.length()).toString().equals(s);
    }

    private static boolean startsWithIgnoreCase(CharSequence text, int at, String s) {
        return at + s.length() <= text.length()
                && text.subSequence(at, at + s.length()).toString().equalsIgnoreCase(s);
    }

    private static String collapse(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    // ---------- JSP -> Java calls ----------

    /** A call written in a JSP's Java code: the method (or, for new X(...), the class), its argument count and position. */
    public record TextCall(String name, int args, boolean constructor, int offset) {}

    private static final Pattern CALL = Pattern.compile(
            "\\bnew\\s+([A-Za-z_$][\\w$.]*)\\s*(?:<[^<>()]*>)?\\s*\\(|([A-Za-z_$][\\w$]*)\\s*\\(");

    /**
     * The calls in the Java code of the JSP block(s) a line belongs to: the <% %>, <%! %> or <%= %> block
     * the line is in, plus any blocks on the line itself (e.g. <%= travel.getDestination() %> inside markup).
     * Read from the text (the Community edition has no JSP parser); matched to Java methods by name and
     * argument count. Declarations ("String escapeHtml(String v) {") and control flow are skipped.
     */
    static java.util.List<TextCall> jspJavaCalls(CharSequence text, int offset) {
        String code = maskCode(text, text.length(), true, false);
        int lineStart = Math.max(0, Math.min(offset, code.length()));
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;
        int lineEnd = lineEnd(text, Math.min(offset, text.length()), text.length());

        java.util.List<TextCall> out = new java.util.ArrayList<>();
        for (int k = lineStart; k < lineEnd; k++) {
            if (code.charAt(k) == OUTSIDE) continue;
            int start = k, end = k;
            while (start > 0 && code.charAt(start - 1) != OUTSIDE) start--;
            while (end < code.length() && code.charAt(end) != OUTSIDE) end++;
            callsIn(code, start, end, out);
            k = end;
        }
        return out;
    }

    /**
     * The calls in the script function a line belongs to: the nearest named function around it (or, when
     * there is none, the innermost callback), the same block that gives the line its method label. Code
     * outside any function contributes the line itself. Read from the text.
     */
    static java.util.List<TextCall> scriptCalls(CharSequence text, int offset) {
        String code = maskCode(text, text.length(), false);
        int at = Math.max(0, Math.min(offset, code.length()));
        int named = -1, callback = -1, depth = 0;
        for (int i = at - 1; i >= 0; i--) {
            char c = code.charAt(i);
            if (c == '}') {
                depth++;
            } else if (c == '{') {
                if (depth > 0) { depth--; continue; }
                String header = collapse(text.subSequence(headerStart(code, i), i).toString());
                if (functionName(header) != null) { named = i; break; }
                if (callback < 0 && callbackName(header) != null) callback = i;
            }
        }
        int open = named >= 0 ? named : callback;
        java.util.List<TextCall> out = new java.util.ArrayList<>();
        if (open < 0) {
            int lineStart = at;
            while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;
            callsIn(code, lineStart, lineEnd(text, at, text.length()), out);
            return out;
        }
        int close = open + 1;
        for (int d = 1; close < code.length() && d > 0; close++) {
            char c = code.charAt(close);
            if (c == '{') d++;
            else if (c == '}') d--;
        }
        callsIn(code, open + 1, Math.min(close, code.length()), out);
        return out;
    }

    private static void callsIn(String code, int start, int end, java.util.List<TextCall> out) {
        Matcher m = CALL.matcher(code).region(start, end);
        while (m.find()) {
            boolean constructor = m.group(1) != null;
            String name = constructor ? m.group(1).replaceAll(".*\\.", "") : m.group(2);
            if (!constructor && NOT_FUNCTIONS.contains(name)) continue;
            int open = m.end() - 1;
            int close = matchingClose(code, open, end);
            if (close < 0) continue;
            String after = code.substring(close + 1, Math.min(end, close + 40)).stripLeading();
            if (!constructor && (after.startsWith("{") || after.startsWith("throws"))) continue; // a declaration
            out.add(new TextCall(name, argCount(code, open, close), constructor, m.start()));
        }
    }

    private static int matchingClose(String s, int open, int end) {
        int depth = 0;
        for (int i = open; i < end; i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if ((c == ')' || c == ']' || c == '}') && --depth == 0) return c == ')' ? i : -1;
        }
        return -1;
    }

    /** Arguments between the parentheses: top-level commas + 1, or 0 when empty. */
    private static int argCount(String s, int open, int close) {
        if (s.substring(open + 1, close).isBlank()) return 0;
        int depth = 0, args = 1;
        for (int i = open + 1; i < close; i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{' || c == '<') depth++;
            else if (c == ')' || c == ']' || c == '}' || c == '>') depth = Math.max(0, depth - 1);
            else if (c == ',' && depth == 0) args++;
        }
        return args;
    }

    // ---------- markup (TLD, XML, HTML, JSP markup) ----------

    private record Element(String name, String attrs, int start) {}

    /**
     * The elements around the line. TLD: the enclosing tag / function and its name ("tag status"), with the
     * library's short-name as the class. Other markup: the nearest element with an id or name
     * ("form#searchForm"), else the nearest element ("<tbody>").
     */
    private static Scope markupScope(CharSequence text, int offset, String fileName, Scope fallback) {
        // The first element that opens on the line counts as the line's own element
        // (so "</head><body>" is in <body>, and "<form id=...>" is in that form).
        int limit = offset;
        int lineEnd = lineEnd(text, offset, text.length());
        for (int k = offset; k < lineEnd; k++) {
            if (text.charAt(k) != '<' || k + 1 >= lineEnd) continue;
            char next = text.charAt(k + 1);
            if (Character.isLetter(next)) {
                limit = indexOf(text, ">", k, text.length(), 1);
                break;
            }
        }
        Deque<Element> stack = ancestors(text, limit);
        if (stack.isEmpty()) return fallback;

        if (fileName.toLowerCase(Locale.ROOT).endsWith(".tld")) {
            Matcher shortName = SHORT_NAME.matcher(text);
            String cls = shortName.find() ? shortName.group(1) : "-";
            for (Element e : stack) {
                if (!Set.of("tag", "function", "tag-file", "validator", "listener").contains(e.name)) continue;
                Matcher name = TLD_NAME.matcher(text);
                if (name.find(e.start)) return new Scope(cls, e.name + " " + name.group(1));
                return new Scope(cls, e.name);
            }
            return new Scope(cls, "[taglib]"); // the library header: tlib-version, short-name, uri, ...
        }
        for (Element e : stack) {
            Matcher m = ID_OR_NAME.matcher(e.attrs);
            if (m.find()) {
                return new Scope("-", e.name + (m.group(1).equals("id") ? "#" + m.group(2) : "[name=" + m.group(2) + "]"));
            }
        }
        return new Scope("-", "<" + stack.peek().name + ">");
    }

    /** Elements open at {@code end}, innermost first. */
    private static Deque<Element> ancestors(CharSequence text, int end) {
        Deque<Element> stack = new ArrayDeque<>();
        String s = text.toString();
        int i = 0;
        while (i < end) {
            int lt = s.indexOf('<', i);
            if (lt < 0 || lt >= end) break;
            if (s.startsWith("<!--", lt)) { i = after(s, "-->", lt); continue; }
            if (s.startsWith("<%--", lt)) { i = after(s, "--%>", lt); continue; }
            if (s.startsWith("<%", lt)) { i = after(s, "%>", lt); continue; }
            if (s.startsWith("<?", lt) || s.startsWith("<!", lt)) { i = after(s, ">", lt); continue; }
            int gt = tagEnd(s, lt);
            if (gt < 0) break;
            String tag = s.substring(lt + 1, gt);
            if (tag.startsWith("/")) {
                String name = tagName(tag.substring(1));
                if (stack.stream().anyMatch(e -> e.name.equals(name))) {
                    while (!stack.isEmpty() && !stack.pop().name.equals(name)) { /* drop unclosed children */ }
                }
            } else {
                String name = tagName(tag);
                boolean selfClosing = tag.endsWith("/") || VOID_HTML.contains(name.toLowerCase(Locale.ROOT));
                if (!name.isEmpty() && !selfClosing) stack.push(new Element(name, tag.substring(name.length()), lt));
            }
            i = gt + 1;
        }
        return stack;
    }

    private static int after(String s, String close, int from) {
        int at = s.indexOf(close, from);
        return at < 0 ? s.length() : at + close.length();
    }

    /** Index of the '>' that ends the tag starting at lt (quotes respected), or -1. */
    private static int tagEnd(String s, int lt) {
        char quote = 0;
        for (int k = lt + 1; k < s.length(); k++) {
            char c = s.charAt(k);
            if (quote != 0) { if (c == quote) quote = 0; }
            else if (c == '"' || c == '\'') quote = c;
            else if (c == '>') return k;
        }
        return -1;
    }

    private static String tagName(String tag) {
        int k = 0;
        while (k < tag.length() && (Character.isLetterOrDigit(tag.charAt(k)) || ":-_.".indexOf(tag.charAt(k)) >= 0)) k++;
        return tag.substring(0, k);
    }

    // ---------- config files ----------

    /** YAML: the parent keys of the line ("spring.datasource"). Properties and SQL have no structure to show. */
    private static Scope configScope(CharSequence text, int offset, String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".yaml") && !lower.endsWith(".yml")) return NONE;
        String before = text.subSequence(0, offset).toString();
        int lineStart = before.lastIndexOf('\n') + 1;
        int indent = offset - lineStart;
        StringBuilder path = new StringBuilder();
        String[] lines = before.substring(0, lineStart).split("\n");
        for (int k = lines.length - 1; k >= 0 && indent > 0; k--) {
            String line = lines[k];
            String body = line.stripLeading();
            if (body.isEmpty() || body.startsWith("#") || body.startsWith("-")) continue;
            int ind = line.length() - body.length();
            int colon = body.indexOf(':');
            if (ind < indent && colon > 0) {
                path.insert(0, path.isEmpty() ? body.substring(0, colon) : body.substring(0, colon) + ".");
                indent = ind;
            }
        }
        return path.isEmpty() ? NONE : new Scope("-", path.toString());
    }
}
