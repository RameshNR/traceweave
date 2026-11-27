package com.example.flowtracker.ui;

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.util.Key;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Colors for a value shown as text ("name = value {Type}", one field a line, as Debug Data's and Compare's Text view
 * show it), in the editor's own colors: names as fields, "text" as strings, 12 as numbers, true / false / null as
 * keywords, {Type@id} grayed.
 */
final class FlowTextColors {
    private static final Key<List<RangeHighlighter>> MINE = Key.create("TraceWeave.textColors");
    private static final Pattern NAME = Pattern.compile("^(\\s*)([^=\\n]+?) = ");
    private static final Pattern TOKEN = Pattern.compile(
            "\"(?:[^\"\\\\]|\\\\.)*\"?"                   // "text"
            + "|'(?:[^'\\\\]|\\\\.)*'?"                   // 'c'
            + "|\\{[^{}\\n]*}"                           // {Type@id}
            + "|(?<![\\w.])-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?[lLfFdD]?(?![\\w.])" // 12, -3.5
            + "|\\b(?:true|false|null)\\b");

    private FlowTextColors() {}

    /** Colors the editor's text (again: the colors put before are taken away first). */
    static void apply(Editor editor) {
        MarkupModel markup = editor.getMarkupModel();
        List<RangeHighlighter> old = editor.getUserData(MINE);
        if (old != null) for (RangeHighlighter h : old) markup.removeHighlighter(h);
        List<RangeHighlighter> now = new ArrayList<>();
        editor.putUserData(MINE, now);
        EditorColorsScheme scheme = editor.getColorsScheme();
        String text = editor.getDocument().getText();
        if (text.length() > 2_000_000) return; // too big to color
        int start = 0;
        for (String line : text.split("\n", -1)) {
            int from = 0;
            Matcher name = NAME.matcher(line);
            if (name.find()) {
                add(markup, now, scheme, start + name.start(2), start + name.end(2), DefaultLanguageHighlighterColors.INSTANCE_FIELD);
                from = name.end();
            }
            Matcher t = TOKEN.matcher(line);
            t.region(from, line.length());
            while (t.find()) {
                String m = t.group();
                TextAttributesKey key = m.startsWith("\"") || m.startsWith("'") ? DefaultLanguageHighlighterColors.STRING
                        : m.startsWith("{") ? DefaultLanguageHighlighterColors.LINE_COMMENT
                        : Character.isLetter(m.charAt(0)) ? DefaultLanguageHighlighterColors.KEYWORD
                        : DefaultLanguageHighlighterColors.NUMBER;
                add(markup, now, scheme, start + t.start(), start + t.end(), key);
            }
            start += line.length() + 1;
        }
    }

    /** Takes this class's colors away (the text is JSON now: the JSON colors show). */
    static void clear(Editor editor) {
        List<RangeHighlighter> old = editor.getUserData(MINE);
        if (old != null) for (RangeHighlighter h : old) editor.getMarkupModel().removeHighlighter(h);
        editor.putUserData(MINE, null);
    }

    private static void add(MarkupModel markup, List<RangeHighlighter> now, EditorColorsScheme scheme, int from, int to,
                            TextAttributesKey key) {
        if (to <= from) return;
        now.add(markup.addRangeHighlighter(from, to, HighlighterLayer.SYNTAX, scheme.getAttributes(key), HighlighterTargetArea.EXACT_RANGE));
    }
}
