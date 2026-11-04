package com.example.flowtracker.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The blocks of a value's JSON or text that can be folded (minimized) in Debug Data and Compare: every { … } and
 * [ … ] over several lines of JSON (braces inside strings don't count), and in the indented text every line with
 * deeper lines under it.
 */
public final class FlowFolds {
    private FlowFolds() {}

    /**
     * A block: from {@code start} to {@code end} (offsets in the text) folded to {@code placeholder}.
     *
     * @param depth how deep it is (0: the outermost); Collapse All folds the blocks under the outermost one
     */
    public record Block(int start, int end, String placeholder, int depth) {}

    public static List<Block> of(String text, boolean json) {
        return json ? json(text) : indented(text);
    }

    private static List<Block> json(String text) {
        List<Block> out = new ArrayList<>();
        Deque<Integer> open = new ArrayDeque<>();
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{' || c == '[') open.push(i);
            else if ((c == '}' || c == ']') && !open.isEmpty()) {
                int start = open.pop();
                if (text.indexOf('\n', start) < i && text.indexOf('\n', start) >= 0) {
                    out.add(new Block(start, i + 1, text.charAt(start) == '{' ? "{…}" : "[…]", open.size()));
                }
            }
        }
        out.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return out;
    }

    private static List<Block> indented(String text) {
        List<Block> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int[] starts = new int[lines.length];
        for (int i = 0, at = 0; i < lines.length; i++) {
            starts[i] = at;
            at += lines[i].length() + 1;
        }
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            int indent = indent(lines[i]), last = i;
            for (int j = i + 1; j < lines.length; j++) {
                if (lines[j].isBlank()) continue;
                if (indent(lines[j]) <= indent) break;
                last = j;
            }
            if (last > i) {
                int end = starts[last] + lines[last].length();
                out.add(new Block(starts[i] + lines[i].length(), end, " …", indent / 4));
            }
        }
        return out;
    }

    private static int indent(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n;
    }
}
