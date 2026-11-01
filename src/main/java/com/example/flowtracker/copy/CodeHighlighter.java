package com.example.flowtracker.copy;

import com.example.flowtracker.code.FlowLang;
import com.example.flowtracker.ui.FlowTheme;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tiny single-line tokenizer: enough to color a saved line the way the mockup does. Code (Java, Kotlin,
 * Groovy, JavaScript / TypeScript) gets keywords, types, strings, numbers and comments; markup (JSP, TLD,
 * XML, HTML) gets tags, attribute values, scriptlet markers and comments; config files get comments,
 * keys and strings.
 */
public final class CodeHighlighter {
    private CodeHighlighter() {}

    /** What a piece of a line is. The panel paints it in the theme's color; the Markdown copy in a fixed one. */
    public enum Token {
        PLAIN, KEYWORD, TYPE, STRING, NUMBER, COMMENT;

        /** The tool window's color for this token (resolved when painted, so theme changes apply). */
        Color color() {
            return switch (this) {
                case PLAIN -> FlowTheme.CODE_FG;
                case KEYWORD -> FlowTheme.KEYWORD;
                case TYPE -> FlowTheme.TYPE;
                case STRING -> FlowTheme.STRING;
                case NUMBER -> FlowTheme.NUMBER;
                case COMMENT -> FlowTheme.COMMENT;
            };
        }
    }

    public record Span(String text, Token token) {
        public Color color() {
            return token.color();
        }
    }

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "var", "record", "yield", "true", "false", "null");

    private static final Set<String> KOTLIN_KEYWORDS = Set.of(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
            "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
            "val", "var", "when", "while", "by", "catch", "constructor", "init", "finally", "get", "set",
            "import", "where", "abstract", "data", "enum", "final", "inline", "internal", "lateinit", "open",
            "override", "private", "protected", "public", "sealed", "suspend", "companion", "const", "vararg");

    private static final Set<String> GROOVY_KEYWORDS = Set.of(
            "def", "as", "in", "trait", "assert", "break", "case", "catch", "class", "continue", "default",
            "do", "else", "enum", "extends", "false", "finally", "for", "if", "implements", "import",
            "instanceof", "interface", "new", "null", "package", "return", "super", "switch", "this", "throw",
            "throws", "true", "try", "while", "void", "static", "final", "private", "protected", "public");

    private static final Set<String> SCRIPT_KEYWORDS = Set.of(
            "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default",
            "delete", "do", "else", "export", "extends", "false", "finally", "for", "from", "function", "if",
            "import", "in", "instanceof", "let", "new", "null", "of", "return", "static", "super", "switch",
            "this", "throw", "true", "try", "typeof", "undefined", "var", "void", "while", "with", "yield",
            "interface", "type", "enum", "implements", "private", "protected", "public", "readonly", "as");

    private static final Set<String> SQL_KEYWORDS = Set.of(
            "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "table",
            "drop", "alter", "join", "left", "right", "inner", "outer", "on", "and", "or", "not", "null", "as",
            "group", "by", "order", "having", "limit", "distinct", "primary", "key", "foreign", "references",
            "index", "view", "union", "all", "case", "when", "then", "else", "end", "is", "in", "like");

    private static final Set<String> BRACES_KEYWORDS = Set.of(
            "abstract", "as", "async", "await", "bool", "break", "case", "catch", "char", "class", "const", "continue",
            "def", "default", "defer", "do", "double", "else", "enum", "extends", "extension", "false", "final", "float",
            "fn", "for", "func", "function", "go", "if", "impl", "implements", "import", "in", "int", "interface", "let",
            "long", "match", "mod", "mut", "namespace", "new", "nil", "null", "nullptr", "object", "override", "package",
            "private", "protected", "pub", "public", "return", "self", "static", "string", "struct", "super", "switch",
            "this", "throw", "throws", "trait", "true", "try", "type", "use", "using", "val", "var", "virtual", "void",
            "where", "while", "yield", "echo", "include", "require", "unsafe", "guard", "protocol", "sealed");

    private static final Set<String> INDENTED_KEYWORDS = Set.of(
            "and", "as", "assert", "async", "await", "begin", "break", "class", "continue", "def", "del", "do", "elif",
            "else", "elsif", "end", "ensure", "except", "False", "false", "finally", "for", "from", "global", "if",
            "import", "in", "is", "lambda", "module", "nil", "None", "nonlocal", "not", "or", "pass", "raise", "rescue",
            "return", "self", "True", "true", "try", "unless", "until", "when", "while", "with", "yield", "puts", "require");

    public static List<Span> highlight(String line) {
        return highlight(line, FlowLang.Kind.JAVA);
    }

    public static List<Span> highlight(String line, @Nullable FlowLang.Kind kind) {
        if (kind == null) return List.of(new Span(line, Token.PLAIN));
        return switch (kind) {
            case JAVA -> code(line, KEYWORDS, false);
            case KOTLIN -> code(line, KOTLIN_KEYWORDS, false);
            case GROOVY -> code(line, GROOVY_KEYWORDS, false);
            case SCRIPT -> code(line, SCRIPT_KEYWORDS, true);
            case JSP, MARKUP -> markup(line);
            case CONFIG -> config(line);
            case BRACES -> code(line, BRACES_KEYWORDS, true);
            case INDENTED -> code(line, INDENTED_KEYWORDS, false, true);
        };
    }

    /** Java-like code. Scripts also allow `template` strings. */
    private static List<Span> code(String line, Set<String> keywords, boolean script) {
        return code(line, keywords, script, false);
    }

    /** @param hashComments true for Python and Ruby: # starts a comment */
    private static List<Span> code(String line, Set<String> keywords, boolean script, boolean hashComments) {
        Spans out = new Spans();
        int n = line.length();
        int i = 0;
        while (i < n) {
            char c = line.charAt(i);
            int end;
            Token color;
            if (hashComments && c == '#') {
                end = n;
                color = Token.COMMENT;
            } else if (c == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
                end = n;
                color = Token.COMMENT;
            } else if (c == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
                int close = line.indexOf("*/", i + 2);
                end = close < 0 ? n : close + 2;
                color = Token.COMMENT;
            } else if (c == '"' || c == '\'' || (script && c == '`')) {
                end = stringEnd(line, i);
                color = Token.STRING;
            } else if (Character.isDigit(c)) {
                end = i + 1;
                while (end < n && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '.'
                        || line.charAt(end) == '_')) end++;
                color = Token.NUMBER;
            } else if (Character.isJavaIdentifierStart(c)) {
                end = i + 1;
                while (end < n && Character.isJavaIdentifierPart(line.charAt(end))) end++;
                String word = line.substring(i, end);
                if (keywords.contains(word)) color = Token.KEYWORD;
                else if (Character.isUpperCase(c)) color = Token.TYPE;
                else color = null;
            } else {
                end = i + 1;
                color = null;
            }
            out.add(line.substring(i, end), color);
            i = end;
        }
        return out.done();
    }

    /** Tags and scriptlet markers as keywords, attribute values as strings, comments greyed. */
    private static List<Span> markup(String line) {
        Spans out = new Spans();
        int n = line.length();
        int i = 0;
        boolean inTag = false;
        while (i < n) {
            int end;
            Token color;
            if (line.startsWith("<!--", i) || line.startsWith("<%--", i)) {
                String close = line.startsWith("<!--", i) ? "-->" : "--%>";
                int at = line.indexOf(close, i + 4);
                end = at < 0 ? n : at + close.length();
                color = Token.COMMENT;
            } else if (line.startsWith("<%", i) || line.startsWith("%>", i)) {
                end = i + 2;
                while (end < n && "!=@".indexOf(line.charAt(end)) >= 0) end++;
                color = Token.KEYWORD;
            } else if (line.charAt(i) == '<' && i + 1 < n && (Character.isLetter(line.charAt(i + 1))
                    || line.charAt(i + 1) == '/' || line.charAt(i + 1) == '?' || line.charAt(i + 1) == '!')) {
                end = i + 1;
                while (end < n && !Character.isWhitespace(line.charAt(end)) && line.charAt(end) != '>') end++;
                color = Token.KEYWORD;
                inTag = true;
            } else if (inTag && (line.charAt(i) == '>' || line.startsWith("/>", i) || line.startsWith("?>", i))) {
                end = line.charAt(i) == '>' ? i + 1 : i + 2;
                color = Token.KEYWORD;
                inTag = false;
            } else if (inTag && (line.charAt(i) == '"' || line.charAt(i) == '\'')) {
                int close = line.indexOf(line.charAt(i), i + 1);
                end = close < 0 ? n : close + 1;
                color = Token.STRING;
            } else if (inTag && Character.isLetter(line.charAt(i))) {
                end = i + 1;
                while (end < n && (Character.isLetterOrDigit(line.charAt(end)) || ":-_".indexOf(line.charAt(end)) >= 0)) end++;
                color = Token.TYPE; // attribute name
            } else {
                end = i + 1;
                color = null;
            }
            out.add(line.substring(i, end), color);
            i = end;
        }
        return out.done();
    }

    /** Properties / YAML / SQL: comments, keys (before = or :), strings, and SQL keywords. */
    private static List<Span> config(String line) {
        String t = line.stripLeading();
        if (t.startsWith("#") || t.startsWith("!") || t.startsWith("--")) {
            return List.of(new Span(line, Token.COMMENT));
        }
        Spans out = new Spans();
        int key = -1;
        for (int k = 0; k < line.length(); k++) {
            char c = line.charAt(k);
            if (c == '=' || c == ':') { key = k; break; }
            if (Character.isWhitespace(c) && k > 0 && !Character.isWhitespace(line.charAt(k - 1))) break;
        }
        int i = 0;
        if (key > 0) {
            out.add(line.substring(0, key), Token.TYPE);
            i = key;
        }
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            int end;
            Token color;
            if (c == '"' || c == '\'') {
                end = stringEnd(line, i);
                color = Token.STRING;
            } else if (Character.isLetter(c)) {
                end = i + 1;
                while (end < n && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) end++;
                color = SQL_KEYWORDS.contains(line.substring(i, end).toLowerCase()) && key < 0 ? Token.KEYWORD : null;
            } else if (Character.isDigit(c)) {
                end = i + 1;
                while (end < n && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '.')) end++;
                color = Token.NUMBER;
            } else {
                end = i + 1;
                color = null;
            }
            out.add(line.substring(i, end), color);
            i = end;
        }
        return out.done();
    }

    private static int stringEnd(String line, int start) {
        char q = line.charAt(start);
        int end = start + 1;
        while (end < line.length() && line.charAt(end) != q) end += line.charAt(end) == '\\' ? 2 : 1;
        return Math.min(line.length(), end + 1);
    }

    /** Collects spans, merging plain text runs. */
    private static final class Spans {
        private final List<Span> out = new ArrayList<>();
        private final StringBuilder plain = new StringBuilder();

        void add(String text, @Nullable Token color) {
            if (color == null || color == Token.PLAIN) {
                plain.append(text);
                return;
            }
            flush();
            out.add(new Span(text, color));
        }

        private void flush() {
            if (!plain.isEmpty()) {
                out.add(new Span(plain.toString(), Token.PLAIN));
                plain.setLength(0);
            }
        }

        List<Span> done() {
            flush();
            return out;
        }
    }
}
