package com.example.flowtracker.ui;

import com.intellij.ui.JBColor;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.beans.PropertyChangeListener;
import java.lang.reflect.Method;

/**
 * Gives a TraceWeave window's title bar the color of IntelliJ's main window header (in the new UI the main header
 * is the main toolbar's color, often dark, while other windows get a light title bar). The title bar is found by its
 * class (the IDE's own header panel); its color is set again whenever the IDE resets it (on focus or theme
 * changes). When anything isn't there, as in the classic UI, the title bar simply stays as the IDE draws it.
 */
final class FlowWindowHeader {
    private static final String HEADER = "com.intellij.openapi.wm.impl.customFrameDecorations.header.CustomHeader";
    private static final String KEY = "TraceWeave.headerColor";

    private FlowWindowHeader() {}

    static void match(@Nullable Window window) {
        if (window == null) return;
        SwingUtilities.invokeLater(() -> {
            try {
                JComponent header = find(window);
                if (header == null || header.getClientProperty(KEY) != null) return;
                header.putClientProperty(KEY, Boolean.TRUE);
                apply(header);
                PropertyChangeListener again = e -> {
                    Color want = background(isActive(header));
                    if (want != null && !want.equals(header.getBackground())) SwingUtilities.invokeLater(() -> apply(header));
                };
                header.addPropertyChangeListener("background", again);
                header.addPropertyChangeListener("foreground", again);
                window.addWindowFocusListener(new java.awt.event.WindowAdapter() {
                    @Override public void windowGainedFocus(java.awt.event.WindowEvent e) { SwingUtilities.invokeLater(() -> apply(header)); }
                    @Override public void windowLostFocus(java.awt.event.WindowEvent e) { SwingUtilities.invokeLater(() -> apply(header)); }
                });
            } catch (Throwable ignored) {
                // the IDE's own title bar stays
            }
        });
    }

    /** The main header's color (null when the IDE has no separate one, e.g. in the classic UI). */
    private static @Nullable Color background(boolean active) {
        Color c = active ? null : UIManager.getColor("MainToolbar.inactiveBackground");
        if (c == null) c = UIManager.getColor("MainToolbar.background");
        // Only when the main header has its own color (the new UI); otherwise the title bar already matches it.
        Color title = UIManager.getColor(active ? "TitlePane.background" : "TitlePane.inactiveBackground");
        return c == null || c.equals(title) ? null : c;
    }

    private static void apply(JComponent header) {
        try {
            Color bg = background(isActive(header));
            if (bg == null) return;
            if (!bg.equals(header.getBackground())) header.setBackground(bg);
            Color fg = foreground(bg);
            for (Component c : all(header)) {
                if (c instanceof JLabel l && !fg.equals(l.getForeground())) l.setForeground(fg);
            }
            controls(header, bg);
            header.repaint();
        } catch (Throwable ignored) {
            // keep what the IDE drew
        }
    }

    /** The title's color: the main toolbar's text color, or light text on a dark header and dark text on a light one. */
    private static Color foreground(Color bg) {
        Color c = UIManager.getColor("MainToolbar.foreground");
        if (c != null) return c;
        double lum = (0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue()) / 255;
        return lum < 0.5 ? new Color(0xDFE1E5) : JBColor.foreground();
    }

    /** The system's Minimize / Maximize / Close buttons follow the header's color (light or dark glyphs). */
    private static void controls(JComponent header, Color bg) {
        try {
            Object bar = header.getClass().getMethod("getCustomTitleBar").invoke(header);
            if (bar == null) return;
            Class<?> kt = Class.forName(HEADER + "Kt", false, header.getClass().getClassLoader());
            for (Method m : kt.getMethods()) {
                if (m.getName().equals("updateWinControlsTheme") && m.getParameterCount() == 2) {
                    m.invoke(null, bg, bar);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // the buttons keep their look
        }
    }

    private static boolean isActive(JComponent header) {
        Window w = SwingUtilities.getWindowAncestor(header);
        return w == null || w.isActive();
    }

    private static @Nullable JComponent find(Component c) {
        for (Class<?> k = c.getClass(); k != null; k = k.getSuperclass()) {
            if (k.getName().equals(HEADER) && c instanceof JComponent j) return j;
        }
        if (c instanceof Container box) {
            for (Component child : box.getComponents()) {
                JComponent found = find(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static java.util.List<Component> all(Container box) {
        java.util.List<Component> out = new java.util.ArrayList<>();
        for (Component c : box.getComponents()) {
            out.add(c);
            if (c instanceof Container inner) out.addAll(all(inner));
        }
        return out;
    }
}
