package com.example.flowtracker.ui;

import com.intellij.icons.AllIcons;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * Values shown as IntelliJ's Variables view shows them: (v) for the value itself, (f) for its fields and elements,
 * "{Type}" (no instance id) only before something that has fields, a plain value (text, number, enum, BigDecimal) alone.
 */
final class FlowVariablesLook {
    private FlowVariablesLook() {}

    static Icon icon(boolean top) {
        return top ? AllIcons.Nodes.Variable : AllIcons.Nodes.Field;
    }

    /** "{BookingDto}" for "BookingDto@7925" (or "{BookingDto@7925}"); "" when there is no type. */
    static String type(@Nullable String type) {
        String t = type == null ? "" : type.strip();
        if (t.startsWith("{") && t.endsWith("}")) t = t.substring(1, t.length() - 1);
        t = t.replaceAll("@[0-9a-fA-F]+", "");
        return t.isEmpty() ? "" : "{" + t + "}";
    }

    /** The value's own text after its type: none when it only repeats the type ("BookingDto@7925"). */
    static String text(@Nullable String value, @Nullable String type) {
        String v = value == null ? "" : value.strip();
        String t = type == null ? "" : type.strip();
        if (v.equals(t) || v.equals("{" + t + "}") || !t.isEmpty() && type(v).equals(type(t))) return "";
        return v;
    }
}
