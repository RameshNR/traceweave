package com.example.flowtracker.debug;

import com.example.flowtracker.model.FlowHuntStore;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The fields bookmarked in the debugger (Variables, Watches, Evaluate: right-click a field → Pin to Top, the blue
 * flag) become TraceWeave bookmarks too, so Debug Data and Stop at Value show them first as well.
 *
 * <p>IntelliJ keeps them as (the parent's type, the field's name). Its pin manager isn't a public API and differs
 * between versions, so it is read by reflection: when it can't be read, nothing is added (and nothing fails).
 */
public final class FlowEvaluatePins {
    private static final Logger LOG = Logger.getInstance(FlowEvaluatePins.class);
    private static final String MANAGER = "com.intellij.xdebugger.impl.pinned.items.XDebuggerPinToTopManager";

    private FlowEvaluatePins() {}

    /** The debugger's pinned fields as bookmark keys ("SimpleType.field"); empty when they can't be read. */
    public static Set<String> keys(Project project) {
        Set<String> out = new LinkedHashSet<>();
        try {
            Class<?> type = Class.forName(MANAGER, false, com.intellij.xdebugger.XDebuggerManager.class.getClassLoader());
            Object manager = project.getService(type);
            if (manager == null) return out;
            Object infos = null;
            for (String name : new String[]{"getPinnedItemInfos", "getPinnedItems"}) {
                try {
                    Method m = type.getMethod(name);
                    infos = m.invoke(manager);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // another version: try the next name
                }
            }
            if (!(infos instanceof Collection<?> all)) return out;
            for (Object info : all) {
                String parent = text(info, "getParentTag"), member = text(info, "getMemberName");
                if (parent == null || member == null || parent.isBlank() || member.isBlank()) continue;
                out.add(simple(parent) + "." + member);
            }
        } catch (Throwable t) {
            LOG.debug("The debugger's pinned fields couldn't be read", t);
        }
        return out;
    }

    /**
     * The fields pinned in the debugger's tree under a node (what is loaded there: the value saved and the fields
     * opened below it), as bookmark keys. A field counts as pinned when IntelliJ's pin manager says so or when it shows
     * the pin (flag) icon.
     */
    public static Set<String> keysUnder(Project project, com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl node) {
        Set<String> out = new LinkedHashSet<>();
        Object manager = null;
        Method isPinned = null;
        try {
            Class<?> type = Class.forName(MANAGER, false, com.intellij.xdebugger.XDebuggerManager.class.getClassLoader());
            manager = project.getService(type);
            for (Method m : type.getMethods()) {
                if (m.getName().equals("isItemPinned") && m.getParameterCount() == 1) isPinned = m;
            }
        } catch (Throwable t) {
            LOG.debug("The debugger's pin manager couldn't be read", t);
        }
        walk(node, manager, isPinned, out, 0);
        return out;
    }

    private static void walk(com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl node, Object manager, Method isPinned,
                             Set<String> out, int depth) {
        if (depth > 12) return;
        String parentType = typeOf(node);
        for (Object c : node.getChildren()) {
            if (!(c instanceof com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl child)) continue;
            boolean pinned = false;
            try {
                if (manager != null && isPinned != null) pinned = Boolean.TRUE.equals(isPinned.invoke(manager, child));
            } catch (Throwable ignored) {
                // another version: the icon tells
            }
            javax.swing.Icon icon = child.getIcon();
            if (!pinned && icon != null) pinned = String.valueOf(icon).toLowerCase(java.util.Locale.ROOT).contains("pin");
            if (pinned && !parentType.isEmpty() && child.getName() != null) out.add(simple(parentType) + "." + child.getName());
            walk(child, manager, isPinned, out, depth + 1);
        }
    }

    /** The type the debugger shows for a node ("BookingDto@7925"), "" when none. */
    private static String typeOf(com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl node) {
        com.intellij.xdebugger.frame.presentation.XValuePresentation p = node.getValuePresentation();
        String t = p == null ? null : p.getType();
        return t == null ? "" : t;
    }

    /** Adds the debugger's pinned fields to TraceWeave's bookmarks; true when any was new. */
    public static boolean sync(Project project) {
        Set<String> keys = keys(project);
        return !keys.isEmpty() && FlowHuntStore.get(project).addPins(keys);
    }

    private static String text(Object o, String getter) {
        try {
            Object v = o.getClass().getMethod(getter).invoke(o);
            return v == null ? null : v.toString();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** "com.travel.CruiseProduct" → "CruiseProduct"; a generic or an array keeps only its class name. */
    static String simple(String type) {
        String t = type.strip();
        int generic = t.indexOf('<');
        if (generic > 0) t = t.substring(0, generic);
        int at = t.indexOf('@');
        if (at > 0) t = t.substring(0, at);
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }
}
