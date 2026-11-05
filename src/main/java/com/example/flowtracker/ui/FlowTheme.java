package com.example.flowtracker.ui;

import com.example.flowtracker.diagram.FlowDiagramPainter;
import com.example.flowtracker.model.FlowLook;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.JBColor;
import com.intellij.util.IconUtil;
import com.intellij.util.ui.JBUI;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Colors, fonts and icons shared by the TraceWeave tool window. Light value first, dark value second; with
 * Settings → Use IntelliJ Theme ({@link FlowLook}) they come from the current IntelliJ theme instead.
 */
public final class FlowTheme {
    private FlowTheme() {}

    /** Where the IntelliJ theme keeps its accent: the default button's color. */
    private static final String[] ACCENT_KEYS = {"Button.default.startBackground", "Component.focusColor"};

    // Each color has TraceWeave's own light / dark value and, for "Use IntelliJ Theme", where the current
    // IntelliJ theme (or editor color scheme) keeps it. They are looked up at paint time, so switching either
    // the setting or the IDE theme repaints with the new colors.

    // ----- surfaces -----
    public static final Color PANEL_BG = color(0xF2F3F5, 0x151A23, ui("ToolWindow.background", "Panel.background"));
    public static final Color CARD_BG = color(0xFFFFFF, 0x19202B, ui("Tree.background", "List.background"));
    public static final Color CARD_BORDER = color(0xDFE1E5, 0x283041, ui("Borders.color", "Component.borderColor"));

    // ----- buttons -----
    public static final Color ACCENT = color(0x3574F0, 0x2F6FED, ui(ACCENT_KEYS));
    static final Color ACCENT_HOVER = color(0x4682FA, 0x4581F5, () -> shade(ui(ACCENT_KEYS), 0.12));
    static final Color ACCENT_PRESSED = color(0x2A62D4, 0x265FD0, () -> shade(ui(ACCENT_KEYS), -0.12));
    public static final Color BTN_BG = color(0xFFFFFF, 0x1E2532, ui("Button.startBackground", "Button.background"));
    public static final Color BTN_HOVER = color(0xEDEEF2, 0x273042, ui("ActionButton.hoverBackground"));
    static final Color BTN_PRESSED = color(0xE1E3E8, 0x2F3A4F, ui("ActionButton.pressedBackground"));
    public static final Color BTN_BORDER = color(0xD3D5DB, 0x323C50, ui("Button.startBorderColor", "Component.borderColor"));
    static final Color BTN_BORDER_DISABLED = color(0xE4E5E9, 0x252C3A, ui("Button.disabledBorderColor", "Component.disabledBorderColor"));
    static final Color SEGMENT_BG = color(0xE9EBEF, 0x1C2230, ui("Panel.background"));
    public static final Color FG = color(0x1E1F22, 0xDFE3EA, ui("Label.foreground"));
    public static final Color FG_MUTED = color(0x6C707E, 0x8B93A3, ui("Label.infoForeground", "Component.infoForeground"));
    public static final Color FG_DISABLED = color(0xA8ADBD, 0x515A6B, ui("Label.disabledForeground"));
    static final Color DIVIDER = color(0xD3D5DB, 0x2C3546, ui("Separator.separatorColor", "Borders.color"));

    // ----- tree -----
    public static final Color GUIDE = color(0xD3D5DB, 0x2E3748, ui("Separator.separatorColor", "Borders.color"));
    public static final Color CODE_BG = color(0xF7F8FA, 0x161C26, () -> scheme().getDefaultBackground());
    public static final Color CODE_BORDER = color(0xE1E3E8, 0x283142, ui("Borders.color", "Component.borderColor"));
    static final Color ROW_HOVER = new JBColor(new Color(0, 0, 0, 12), new Color(255, 255, 255, 12));
    static final Color SELECTION = color(0xDCE6FB, 0x173564, ui("Tree.selectionBackground", "List.selectionBackground"));
    public static final Color SELECTION_BAR = color(0x3574F0, 0x3B82F6, ui(ACCENT_KEYS));
    // ----- move up / down buttons on the hovered row -----
    static final Color ARROWS_BG = color(0xFFFFFF, 0x232B3A, ui("Button.startBackground", "Button.background"));
    static final Color ARROWS_BORDER = color(0xC9CCD6, 0x3A4558, ui("Button.startBorderColor", "Component.borderColor"));
    static final Color ARROWS_SHADOW = new JBColor(new Color(0, 0, 0, 28), new Color(0, 0, 0, 90));
    static final Color ARROW_FG = color(0x1E1F22, 0xFFFFFF, ui("Label.foreground"));
    static final Color ARROW_FG_DISABLED = color(0xB4B8C4, 0x566072, ui("Label.disabledForeground"));
    static final Color ARROW_HOVER = ACCENT;
    static final Color DANGER = new JBColor(0xE55765, 0xD9534F);
    /** Saved line that no longer matches the code. */
    public static final Color WARNING = new JBColor(0xC77D0A, 0xE0A33A);
    /** Recording with the debugger. */
    static final Color RECORDING = new JBColor(0xD64550, 0xE5636D);
    static final Color ARROW_FG_HOVER = new JBColor(0xFFFFFF, 0xFFFFFF);

    // ----- project tag next to file names -----
    public static final Color TAG_BG = color(0xE6EDFC, 0x1E2C46, () -> mixed(ui(ACCENT_KEYS), ui("Tree.background", "List.background"), 0.18));
    static final Color TAG_FG = color(0x2F5BB7, 0xA4BFF5, ui("Label.foreground"));
    static final Color DROP_LINE = color(0x3574F0, 0x548AF7, ui(ACCENT_KEYS));

    // ----- code (from the editor color scheme with the IntelliJ theme) -----
    public static final Color LINE_NO = color(0x3574F0, 0x6F8FD0, () -> scheme().getColor(EditorColors.LINE_NUMBERS_COLOR));
    public static final Color CODE_FG = color(0x1E1F22, 0xD5DAE3, () -> scheme().getDefaultForeground());
    public static final Color KEYWORD = color(0x0033B3, 0x6E9BFF, syntax(DefaultLanguageHighlighterColors.KEYWORD));
    public static final Color TYPE = color(0x1F6FBF, 0x5AB0FF, syntax(DefaultLanguageHighlighterColors.CLASS_REFERENCE));
    public static final Color STRING = color(0x067D17, 0xE8B562, syntax(DefaultLanguageHighlighterColors.STRING));
    public static final Color NUMBER = color(0x1750EB, 0x2AACB8, syntax(DefaultLanguageHighlighterColors.NUMBER));
    public static final Color COMMENT = color(0x8C8C8C, 0x6F7787, syntax(DefaultLanguageHighlighterColors.LINE_COMMENT));

    /** TraceWeave's own color (light, dark), or the IntelliJ theme's when that setting is on and it has one. */
    private static Color color(int light, int dark, Supplier<Color> ide) {
        JBColor own = new JBColor(light, dark);
        return JBColor.lazy(() -> {
            if (FlowLook.ideTheme()) {
                Color c = ide.get();
                if (c != null) return c;
            }
            return own;
        });
    }

    /** The first of these theme keys the current IntelliJ theme defines (null when none). */
    private static Supplier<Color> ui(String... keys) {
        return () -> {
            for (String k : keys) {
                Color c = UIManager.getColor(k);
                if (c != null) return c;
            }
            return null;
        };
    }

    private static EditorColorsScheme scheme() {
        return EditorColorsManager.getInstance().getGlobalScheme();
    }

    /** The foreground the editor color scheme gives this kind of code. */
    private static Supplier<Color> syntax(TextAttributesKey key) {
        return () -> {
            TextAttributes a = scheme().getAttributes(key);
            return a == null ? null : a.getForegroundColor();
        };
    }

    /** Lighter (amount > 0) or darker (amount < 0); null stays null. */
    private static Color shade(Supplier<Color> base, double amount) {
        Color c = base.get();
        if (c == null) return null;
        return amount > 0 ? FlowDiagramPainter.mix(Color.WHITE, c, amount) : FlowDiagramPainter.mix(Color.BLACK, c, -amount);
    }

    private static Color mixed(Supplier<Color> a, Supplier<Color> b, double amount) {
        Color ca = a.get(), cb = b.get();
        return ca == null || cb == null ? null : FlowDiagramPainter.mix(ca, cb, amount);
    }

    // ----- toolbar icons (single grey SVGs, recolored at paint time) -----
    static final Icon ICON_GROUPED = icon("tb_grouped");
    static final Icon ICON_LIST = icon("tb_list");
    static final Icon ICON_COPY = icon("tb_copy");
    public static final Icon ICON_DELETE = icon("tb_delete");
    static final Icon ICON_CLEAR = icon("tb_clear");
    public static final Icon ICON_NOTE = icon("tb_note");
    static final Icon ICON_UP = icon("tb_up");
    static final Icon ICON_DOWN = icon("tb_down");
    public static final Icon ICON_SETTINGS = icon("tb_settings");
    public static final Icon ICON_SYNC = icon("tb_sync");
    static final Icon ICON_MARKDOWN = icon("tb_markdown");
    static final Icon ICON_INFO = icon("tb_info");
    public static final Icon ICON_UNDO = icon("tb_undo");
    public static final Icon ICON_REDO = icon("tb_redo");
    public static final Icon ICON_FLOWS = icon("tb_flows");
    static final Icon ICON_EXPORT = icon("tb_export");
    static final Icon ICON_IMPORT = icon("tb_import");
    static final Icon ICON_RECORD = icon("tb_record");
    static final Icon ICON_PREV = icon("tb_prev");
    static final Icon ICON_NEXT = icon("tb_next");
    public static final Icon ICON_TAG = icon("tb_tag");
    static final Icon ICON_WARNING = icon("tb_warning");
    // ----- Flow Diagram window -----
    static final Icon ICON_DIAGRAM = icon("tb_diagram");
    public static final Icon ICON_SELECT = icon("tb_select");
    public static final Icon ICON_CLASS = icon("tb_class");
    public static final Icon ICON_CONNECT = icon("tb_connect");
    public static final Icon ICON_METHOD = icon("tb_method");
    public static final Icon ICON_LAYOUT = icon("tb_layout");
    public static final Icon ICON_IMAGE = icon("tb_image");
    public static final Icon ICON_SEQUENCE = icon("tb_sequence");
    public static final Icon ICON_CLASS_UNKNOWN = icon("tb_class_unknown");
    public static final Icon ICON_METHOD_UNKNOWN = icon("tb_method_unknown");
    public static final Icon ICON_THEME = icon("tb_theme");
    public static final Icon ICON_FORMAT = icon("tb_format");
    public static final Icon ICON_LIGHT = icon("tb_light");
    public static final Icon ICON_DARK = icon("tb_dark");
    public static final Icon ICON_ZOOM_IN = icon("tb_zoom_in");
    public static final Icon ICON_ZOOM_OUT = icon("tb_zoom_out");
    public static final Icon ICON_CODE = icon("tb_code");
    static final Icon ICON_RECYCLE = icon("tb_recycle");
    static final Icon ICON_CAPTURE = icon("tb_capture");

    /** The grey SVG icon painted in exactly the given color (theme colors resolve at paint time). */
    public static Icon tinted(Icon icon, Color color) {
        return new TintedIcon(icon, color);
    }

    /**
     * Paints the icon's shape filled with one solid color. The icon is rendered at the screen's pixel
     * density into an offscreen image, so it stays sharp on HiDPI displays.
     */
    private record TintedIcon(Icon base, Color color) implements Icon {
        private static final Map<String, BufferedImage> CACHE = new HashMap<>();

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g;
            double scale = Math.max(1.0, g2.getTransform().getScaleX());
            String key = System.identityHashCode(base) + ":" + color.getRGB() + ":" + scale;
            BufferedImage img = CACHE.computeIfAbsent(key, k -> render(c, scale));
            g2.drawImage(img, x, y, getIconWidth(), getIconHeight(), null);
        }

        private BufferedImage render(Component c, double scale) {
            Icon big = scale == 1.0 ? base : IconUtil.scale(base, null, (float) scale);
            int w = Math.max(1, big.getIconWidth()), h = Math.max(1, big.getIconHeight());
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D ig = img.createGraphics();
            try {
                big.paintIcon(c, ig, 0, 0);
                ig.setComposite(AlphaComposite.SrcIn); // keep the shape, replace its color
                ig.setColor(color);
                ig.fillRect(0, 0, w, h);
            } finally {
                ig.dispose();
            }
            return img;
        }

        @Override public int getIconWidth() { return base.getIconWidth(); }
        @Override public int getIconHeight() { return base.getIconHeight(); }
    }

    private static Icon icon(String name) {
        return IconLoader.getIcon("/icons/" + name + ".svg", FlowTheme.class);
    }

    /** Space between a toolbar button and the popup it opens (small, so the popup still reads as the button's). */
    public static int popupGap() {
        return JBUI.scale(4);
    }

    /** Editor font at the size of the UI label font, so code rows line up with the rest of the tool window. */
    public static Font codeFont() {
        Font editor = EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN);
        return editor.deriveFont((float) JBUI.Fonts.label().getSize());
    }
}
