package com.example.flowtracker.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.intellij.icons.AllIcons;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TreeUIHelper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.util.List;
import java.util.Map;

/**
 * A stop's variables as a tree, the way the debugger's Threads &amp; Variables shows them ("this = {BookingDto}",
 * fields under it, strings in green), from the variables read there (or from their JSON, for older stops). Type to
 * search in it.
 */
final class FlowJsonTree {
    enum Kind { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

    /** A row: its name (a field, or [i] in a list) and its value as shown. */
    record Row(String name, String value, Kind kind) {
        @Override public String toString() { return name + " " + value; } // what search looks in
    }

    /** A variable as the debugger reads it: its name, type and value; {@code top} for the frame's own variables. */
    record VarRow(String name, String type, String value, boolean top, boolean hasChildren, String key, boolean pinned) {
        VarRow(String name, String type, String value, boolean top, boolean hasChildren) {
            this(name, type, value, top, hasChildren, "", false);
        }

        @Override public String toString() { return name + " " + type + " " + value; } // what search looks in
    }

    /** The fields pinned to the top (keys: "Type.field", "frame.name"), and what pins / unpins one. */
    private java.util.function.Supplier<java.util.Set<String>> pins = java.util.Set::of;
    private java.util.function.Consumer<String> togglePin = k -> {};
    private @Nullable com.example.flowtracker.model.FlowValue shownValue;

    /** Lets the tree pin fields to the top (right-click → Pin to Top), kept by {@code toggle}. */
    void setPins(java.util.function.Supplier<java.util.Set<String>> pins, java.util.function.Consumer<String> toggle) {
        this.pins = pins;
        this.togglePin = toggle;
    }

    /** The key a field is pinned by: its object's type and its name ("BookingDto.reference"); "frame.x" at the top. */
    static String pinKey(@Nullable com.example.flowtracker.model.FlowValue parent, String name) {
        return parent == null ? "frame." + name : simpleType(typeOf(parent)) + "." + name;
    }

    private static final java.util.regex.Pattern OBJECT_ID = java.util.regex.Pattern.compile("^\\{?([\\w.$]+)@\\w+");

    /** A value's type; for one saved without it (as JSON), the class in its text ("BookingDto@8207" → BookingDto). */
    static String typeOf(com.example.flowtracker.model.FlowValue v) {
        if (v.type != null && !v.type.isEmpty() && !v.type.equals("Object")) return v.type;
        java.util.regex.Matcher m = OBJECT_ID.matcher(v.value == null ? "" : v.value.strip());
        return m.find() ? m.group(1) : v.type == null ? "" : v.type;
    }

    private final Tree tree = new Tree(new DefaultTreeModel(new DefaultMutableTreeNode()));
    private final JBScrollPane scroll = FlowScroll.wheel(new JBScrollPane(tree));

    FlowJsonTree() {
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new Renderer());
        tree.getEmptyText().setText("No variables");
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree); // type to find a name or value
        tree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { if (e.isPopupTrigger()) menu(e); }
            @Override public void mouseExited(java.awt.event.MouseEvent e) { hover(-1); }
            // the button down on a row's icon: bookmark it (to the top of its parent), or take the bookmark off
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
                    menu(e);
                    return;
                }
                if (!javax.swing.SwingUtilities.isLeftMouseButton(e)) return;
                int row = tree.getClosestRowForLocation(e.getX(), e.getY());
                if (row < 0 || !onIcon(e.getPoint(), row)) return;
                javax.swing.tree.TreePath p = tree.getPathForRow(row);
                if (p.getLastPathComponent() instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof VarRow v) {
                    togglePin.accept(v.key());
                    e.consume();
                }
            }
        });
        tree.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                int row = tree.getRowForLocation(e.getX(), e.getY());
                boolean on = row >= 0 && onIcon(e.getPoint(), row)
                        && tree.getPathForRow(row).getLastPathComponent() instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof VarRow;
                hover(on ? row : -1);
                tree.setCursor(java.awt.Cursor.getPredefinedCursor(on ? java.awt.Cursor.HAND_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
                tree.setToolTipText(on ? (((VarRow) ((DefaultMutableTreeNode) tree.getPathForRow(row).getLastPathComponent()).getUserObject()).pinned()
                        ? "Bookmarked: click to put it back in its place" : "Bookmark: to the top of its parent (in every stop)") : null);
            }
        });
        scroll.setBorder(JBUI.Borders.empty());
    }

    JComponent component() {
        return scroll;
    }

    JTree tree() {
        return tree;
    }

    /** Shows a JSON value (two levels open); text that isn't JSON shows as one row. */
    void setJson(String json) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(new Row("", "", Kind.OBJECT));
        if (json != null && !json.isBlank()) {
            try {
                JsonElement e = JsonParser.parseString(json);
                if (e.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> f : e.getAsJsonObject().entrySet()) root.add(node(f.getKey(), f.getValue()));
                } else {
                    root.add(node("value", e));
                }
            } catch (RuntimeException notJson) {
                root.add(new DefaultMutableTreeNode(new Row("value", json, Kind.STRING)));
            }
        }
        tree.setModel(new DefaultTreeModel(root));
        expandLevels(2);
    }

    /** Shows the variables read at a stop the way the debugger shows them: the frame's variables, then their fields. */
    void setValue(com.example.flowtracker.model.FlowValue root) {
        boolean same = root == shownValue;
        java.util.Set<String> open = same ? expandedKeys() : java.util.Set.of();
        shownValue = root;
        java.util.Set<String> pinned = pins.get();
        DefaultMutableTreeNode top = new DefaultMutableTreeNode(new Row("", "", Kind.OBJECT));
        for (com.example.flowtracker.model.FlowValue v : pinnedFirst(null, root.children, pinned)) top.add(varNode(null, v, true, pinned, ""));
        tree.setModel(new DefaultTreeModel(top));
        if (same) expandKeys(open);
        else expandLevels(2);
    }

    /** The same variables again (e.g. a field pinned or unpinned), the open ones staying open. */
    void refresh() {
        if (shownValue != null) setValue(shownValue);
    }

    static DefaultMutableTreeNode varNode(com.example.flowtracker.model.FlowValue v, boolean top) {
        return varNode(null, v, top, java.util.Set.of(), "");
    }

    private static DefaultMutableTreeNode varNode(@Nullable com.example.flowtracker.model.FlowValue parent, com.example.flowtracker.model.FlowValue v,
                                                  boolean top, java.util.Set<String> pinned, String path) {
        String key = pinKey(parent, v.name);
        DefaultMutableTreeNode n = new DefaultMutableTreeNode(
                new VarRow(v.name, v.type, v.value, top, !v.children.isEmpty(), key, pinned.contains(key)));
        String here = path + "/" + v.name;
        for (com.example.flowtracker.model.FlowValue c : pinnedFirst(v, v.children, pinned)) n.add(varNode(v, c, false, pinned, here));
        return n;
    }

    /** The children with the pinned ones first (each group in its own order). */
    static List<com.example.flowtracker.model.FlowValue> pinnedFirst(@Nullable com.example.flowtracker.model.FlowValue parent,
                                                                     List<com.example.flowtracker.model.FlowValue> children, java.util.Set<String> pinned) {
        if (pinned.isEmpty()) return children;
        List<com.example.flowtracker.model.FlowValue> out = new java.util.ArrayList<>();
        for (com.example.flowtracker.model.FlowValue c : children) if (pinned.contains(pinKey(parent, c.name))) out.add(c);
        for (com.example.flowtracker.model.FlowValue c : children) if (!pinned.contains(pinKey(parent, c.name))) out.add(c);
        return out;
    }

    /** The open rows, by their names from the top ("/this/trip"). */
    private java.util.Set<String> expandedKeys() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (int i = 0; i < tree.getRowCount(); i++) {
            javax.swing.tree.TreePath p = tree.getPathForRow(i);
            if (tree.isExpanded(p)) out.add(pathKey(p));
        }
        return out;
    }

    private void expandKeys(java.util.Set<String> keys) {
        for (int i = 0; i < tree.getRowCount(); i++) {
            javax.swing.tree.TreePath p = tree.getPathForRow(i);
            if (keys.contains(pathKey(p))) tree.expandPath(p);
        }
    }

    private static String pathKey(javax.swing.tree.TreePath p) {
        StringBuilder sb = new StringBuilder();
        for (Object o : p.getPath()) {
            if (o instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof VarRow v) sb.append('/').append(v.name());
        }
        return sb.toString();
    }

    private void hover(int row) {
        if (row == hoverRow) return;
        int was = hoverRow;
        hoverRow = row;
        for (int r : new int[]{was, row}) {
            java.awt.Rectangle b = r < 0 ? null : tree.getRowBounds(r);
            if (b != null) tree.repaint(b);
        }
    }

    /** Right-click on a variable: Pin to Top / Unpin, Copy Value, Copy Name. */
    private void menu(java.awt.event.MouseEvent e) {
        javax.swing.tree.TreePath p = tree.getPathForLocation(e.getX(), e.getY());
        if (p == null || !(p.getLastPathComponent() instanceof DefaultMutableTreeNode n) || !(n.getUserObject() instanceof VarRow v)) return;
        tree.setSelectionPath(p);
        String shownValueText = v.value() == null ? "" : v.value();
        FlowPopupMenu m = new FlowPopupMenu().header(v.name().toUpperCase());
        m.item(new BookmarkIcon(v.pinned()), v.pinned() ? "Remove Bookmark" : "Bookmark: to the Top",
                v.pinned() ? "It goes back to its place" : "First in every " + (v.top() ? "stop" : simpleType(parentType(n))) + " from now on",
                null, () -> togglePin.accept(v.key()));
        m.separator();
        m.item(AllIcons.Actions.Copy, "Copy Value", null, null, () -> copy(shownValueText));
        m.item(AllIcons.Actions.Copy, "Copy Name", null, null, () -> copy(v.name()));
        m.item(AllIcons.Actions.Copy, "Copy Name = Value", null, null, () -> copy(v.name() + " = " + shownValueText));
        m.show(tree, e.getX(), e.getY());
    }

    private static String parentType(DefaultMutableTreeNode n) {
        return n.getParent() instanceof DefaultMutableTreeNode p && p.getUserObject() instanceof VarRow v ? v.type() : "";
    }

    private static void copy(String text) {
        com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(new java.awt.datatransfer.StringSelection(text));
    }

    /** "com.shop.Order&lt;X&gt;" → "Order". */
    static String simpleType(String type) {
        String t = type == null ? "" : type.strip();
        int generic = t.indexOf('<');
        if (generic >= 0) t = t.substring(0, generic);
        return t.substring(t.lastIndexOf('.') + 1);
    }

    static DefaultMutableTreeNode node(String name, JsonElement e) {
        if (e == null || e.isJsonNull()) return new DefaultMutableTreeNode(new Row(name, "null", Kind.NULL));
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            DefaultMutableTreeNode n = new DefaultMutableTreeNode(new Row(name, "{" + o.size() + (o.size() == 1 ? " field}" : " fields}"), Kind.OBJECT));
            for (Map.Entry<String, JsonElement> f : o.entrySet()) n.add(node(f.getKey(), f.getValue()));
            return n;
        }
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            DefaultMutableTreeNode n = new DefaultMutableTreeNode(new Row(name, "[" + a.size() + (a.size() == 1 ? " item]" : " items]"), Kind.ARRAY));
            for (int i = 0; i < a.size(); i++) n.add(node("[" + i + "]", a.get(i)));
            return n;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return new DefaultMutableTreeNode(new Row(name, p.getAsString(), Kind.BOOLEAN));
        if (p.isNumber()) return new DefaultMutableTreeNode(new Row(name, p.getAsString(), Kind.NUMBER));
        return new DefaultMutableTreeNode(new Row(name, "\"" + p.getAsString() + "\"", Kind.STRING));
    }

    void expandAll() {
        TreeUtil.expandAll(tree);
    }

    void collapseAll() {
        expandLevels(1);
    }

    private void expandLevels(int levels) {
        TreeUtil.collapseAll(tree, 0);
        TreeUtil.expand(tree, levels);
    }

    /** Focus the tree: typing searches in it. */
    void find() {
        tree.requestFocusInWindow();
        if (tree.getSelectionCount() == 0 && tree.getRowCount() > 0) tree.setSelectionRow(0);
    }

    /** The row whose icon the mouse is on (its icon shows as a bookmark to click), -1 for none. */
    private int hoverRow = -1;
    private static final JBColor BOOKMARK = new JBColor(0x3574F0, 0x548AF7);

    /** A bookmark ribbon: outlined (to click: pin to the top) or filled blue (pinned). */
    static final class BookmarkIcon implements javax.swing.Icon {
        private final boolean filled;

        BookmarkIcon(boolean filled) { this.filled = filled; }

        @Override public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                float s = getIconWidth() / 16f;
                java.awt.geom.Path2D p = new java.awt.geom.Path2D.Float();
                p.moveTo(x + 3.5f * s, y + 1.5f * s);
                p.lineTo(x + 12.5f * s, y + 1.5f * s);
                p.lineTo(x + 12.5f * s, y + 14.5f * s);
                p.lineTo(x + 8f * s, y + 10.5f * s);
                p.lineTo(x + 3.5f * s, y + 14.5f * s);
                p.closePath();
                if (filled) {
                    g2.setColor(BOOKMARK);
                    g2.fill(p);
                } else {
                    g2.setStroke(new java.awt.BasicStroke(1.2f * s));
                    g2.setColor(JBColor.namedColor("Label.infoForeground", new JBColor(0x6C707E, 0x9DA0A8)));
                    g2.draw(p);
                }
            } finally {
                g2.dispose();
            }
        }

        @Override public int getIconWidth() { return com.intellij.util.ui.JBUI.scale(16); }
        @Override public int getIconHeight() { return com.intellij.util.ui.JBUI.scale(16); }
    }

    /** True when a point of the tree is on its row's icon (the bookmark to click). */
    private boolean onIcon(java.awt.Point p, int row) {
        java.awt.Rectangle r = tree.getRowBounds(row);
        return r != null && p.x >= r.x && p.x <= r.x + com.intellij.util.ui.JBUI.scale(20) && p.y >= r.y && p.y < r.y + r.height;
    }

    private final class Renderer extends ColoredTreeCellRenderer {
        private final SimpleTextAttributes STRING = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, new JBColor(0x067D17, 0x6AAB73));
        private final SimpleTextAttributes NUMBER = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, new JBColor(0x1750EB, 0x2AACB8));
        private final SimpleTextAttributes KEYWORD = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, new JBColor(0x0033B3, 0xCF8E6D));

        @Override
        public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf,
                                          int row, boolean hasFocus) {
            if (!(value instanceof DefaultMutableTreeNode n)) return;
            if (n.getUserObject() instanceof VarRow v) {
                variable(v, row == hoverRow);
                return;
            }
            if (!(n.getUserObject() instanceof Row r)) return;
            setIcon(switch (r.kind()) {
                case OBJECT -> AllIcons.Nodes.Class;
                case ARRAY -> AllIcons.Debugger.Db_array;
                default -> AllIcons.Debugger.Db_primitive;
            });
            append(r.name(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            append(r.kind() == Kind.OBJECT || r.kind() == Kind.ARRAY ? "  " : " = ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
            String v = r.value().length() > 200 ? r.value().substring(0, 200) + "…" : r.value();
            append(v, switch (r.kind()) {
                case STRING -> STRING;
                case NUMBER -> NUMBER;
                case BOOLEAN, NULL -> KEYWORD;
                default -> SimpleTextAttributes.GRAYED_ATTRIBUTES;
            });
        }

        /** Like the debugger's Variables: "this = {BookingDto}", "priceLines = {ArrayList} size = 0", "reference = \"BK-1044\"". */
        private void variable(VarRow v, boolean hover) {
            boolean element = v.name().startsWith("[");
            javax.swing.Icon icon = v.top() ? ("this".equals(v.name()) ? AllIcons.Debugger.Value : AllIcons.Nodes.Variable)
                    : element ? AllIcons.Debugger.Value : AllIcons.Nodes.Field;
            // bookmarked: a blue bookmark; the mouse on the icon: a bookmark to click
            setIcon(v.pinned() ? new BookmarkIcon(true) : hover ? new BookmarkIcon(false) : icon);
            append(v.name(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
            append(" = ", SimpleTextAttributes.REGULAR_ATTRIBUTES);
            String value = v.value() == null ? "" : v.value().strip();
            String type = simpleType(v.type());
            if (v.hasChildren() || value.isEmpty() && !type.isEmpty()) {
                if (value.startsWith("{")) {
                    append(value, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                } else {
                    if (!type.isEmpty()) append("{" + type + "}", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    if (!value.isEmpty()) append("  " + shorten(value), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                }
                return;
            }
            append(shorten(value), value.startsWith("\"") || value.startsWith("'") ? STRING
                    : value.matches("-?[0-9][0-9_.,eE+-]*[LlFfDd]?") ? NUMBER
                    : value.equals("null") || value.equals("true") || value.equals("false") ? KEYWORD
                    : SimpleTextAttributes.REGULAR_ATTRIBUTES);
        }

        private static String shorten(String s) {
            return s.length() > 200 ? s.substring(0, 200) + "…" : s;
        }
    }
}
