package com.example.flowtracker.diagram;

import com.example.flowtracker.ui.FlowTheme;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.ColorChooserService;
import com.intellij.ui.JBColor;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * The Flow Diagram's Format window: how a class box, a method or a call looks.
 * <ul>
 *   <li>A box: a theme (Plain, Light, Dark or a color), or its own background and border. Text on it turns dark or
 *       light by itself, so it stays readable.</li>
 *   <li>Names and notes: bold, italic, underline, strike-through, and a color (Auto: readable on the background).</li>
 *   <li>A call's line: pattern (dashes, dots, double lines...), width, shape, arrow and color.</li>
 * </ul>
 * A preview shows the result while the options change.
 */
final class FlowDiagramFormat {
    private FlowDiagramFormat() {}

    /** A ready-made look for a box: background and border (Plain: none, the diagram's own look). */
    /**
     * A ready-made look for a box: a strong class section, a soft method section in the same color, and a border
     * (Plain: none of them, the diagram's own look).
     */
    private record Theme(String name, @Nullable Color header, @Nullable Color fill, @Nullable Color border) {}

    private static final List<Theme> THEMES = List.of(
            new Theme("Plain", null, null, null),
            new Theme("Light", new Color(0xE8EAEE), new Color(0xFFFFFF), new Color(0xB9BDC7)),
            new Theme("Dark", new Color(0x45484E), new Color(0x2B2D30), new Color(0x5F636B)),
            new Theme("Blue", new Color(0x1E88E5), new Color(0xE3F2FD), new Color(0x1565C0)),
            new Theme("Green", new Color(0x43A047), new Color(0xE8F5E9), new Color(0x2E7D32)),
            new Theme("Amber", new Color(0xFFB300), new Color(0xFFF8E1), new Color(0xFF8F00)),
            new Theme("Rose", new Color(0xD81B60), new Color(0xFCE4EC), new Color(0xAD1457)),
            new Theme("Purple", new Color(0x8E24AA), new Color(0xF3E5F5), new Color(0x6A1B9A)),
            new Theme("Teal", new Color(0x00897B), new Color(0xE0F2F1), new Color(0x00695C)),
            new Theme("Navy", new Color(0x3949AB), new Color(0x1F2A4D), new Color(0x5C7BD9)),
            new Theme("Slate", new Color(0x607D8B), new Color(0x37474F), new Color(0x90A4AE)));

    /** Text and line colors to pick with one click. */
    private static final Color[] COLORS = {
            new Color(0xC62828), new Color(0xEF6C00), new Color(0xF9A825), new Color(0x2E7D32), new Color(0x00838F),
            new Color(0x1565C0), new Color(0x6A1B9A), new Color(0xAD1457), new Color(0x5D4037), new Color(0x546E7A),
            new Color(0x212121), new Color(0xFFFFFF)};

    /** Backgrounds for boxes: light ones, then dark ones. */
    private static final Color[] FILLS = {
            new Color(0xFFFFFF), new Color(0xFFEBEE), new Color(0xFFF3E0), new Color(0xFFFDE7), new Color(0xE8F5E9),
            new Color(0xE0F7FA), new Color(0xE3F2FD), new Color(0xF3E5F5), new Color(0xECEFF1),
            new Color(0x2B2D30), new Color(0x1F2A4D), new Color(0x37474F), new Color(0x1B3A2A), new Color(0x4A1F2E)};

    private static final FlowDiagram.Format BOLD = new FlowDiagram.Format(true, false, null);

    // ---------- class box ----------

    /**
     * Edits a class box's look and note (in place). True when OK was pressed.
     *
     * @param palette the diagram window's colors, for the preview
     */
    static boolean editBox(Project project, Component parent, FlowDiagram.Box b, FlowDiagramPainter.Palette palette) {
        Form f = boxForm(b, palette);
        if (!new Dialog(project, parent, "Format " + b.name, f.panel(), f.reset(),
                "Back to the default look: the Plain theme, usual text formats and colors (the note text stays)").showAndGet()) {
            return false;
        }
        f.apply().run();
        return true;
    }

    /** A Format window's content, what OK does with it, and what Reset does to it. */
    record Form(JPanel panel, Runnable apply, Runnable reset) {}

    /** The Format Box form (also used by the tests to draw it). */
    static Form boxForm(FlowDiagram.Box b, FlowDiagramPainter.Palette palette) {
        ColorField header = new ColorField(b.headerFill, FILLS, "Auto",
                "A shade of the method section's background (or the diagram's own look)");
        ColorField fill = new ColorField(b.fill, FILLS, "Auto",
                "A softer shade of the class section's background (or the diagram's own look)");
        ColorField border = new ColorField(b.border, COLORS, "Auto", "A border that suits the background");
        TextFormat title = new TextFormat(b.title, BOLD);
        TextFormat file = new TextFormat(b.fileFormat, FlowDiagram.Format.PLAIN);
        TextFormat methods = new TextFormat(b.methodsFormat, FlowDiagram.Format.PLAIN);
        JBTextArea note = noteArea(b.note);
        TextFormat noteFormat = new TextFormat(b.noteFormat, FlowDiagram.Format.PLAIN);
        ColorField projectFill = new ColorField(b.projectFill, FILLS, "Auto", "A deeper shade of the class section");
        ColorField projectBorder = new ColorField(b.projectBorder, COLORS, "Auto", "The box's border");
        TextFormat projectText = new TextFormat(b.projectFormat, BOLD);
        List<TextFormat> texts = List.of(title, file, methods, noteFormat, projectText);

        Preview preview = new Preview(palette, () -> {
            FlowDiagram.Box p = b.copy();
            p.headerFill = header.color;
            p.fill = fill.color;
            p.border = border.color;
            p.title = title.format();
            p.fileFormat = file.format();
            p.methodsFormat = methods.format();
            p.note = note.getText().strip();
            p.noteFormat = noteFormat.format();
            p.projectFill = projectFill.color;
            p.projectBorder = projectBorder.color;
            p.projectFormat = projectText.format();
            return p;
        }, null);
        java.util.function.Consumer<Theme> applyTheme = t -> {
            header.set(t.header());
            fill.set(t.fill());
            border.set(t.border());
            if (t.fill() == null) { // Plain: back to the diagram's own look
                title.set(BOLD);
                for (TextFormat f : List.of(file, methods, noteFormat)) f.set(FlowDiagram.Format.PLAIN);
                projectFill.set(null);
                projectBorder.set(null);
                projectText.set(BOLD);
            } else { // a theme: text colors become automatic again, so they suit its background
                texts.forEach(TextFormat::clearColor);
            }
        };
        ThemePicker themes = new ThemePicker(palette, applyTheme,
                t -> java.util.Objects.equals(t.header(), header.color) && java.util.Objects.equals(t.fill(), fill.color)
                && java.util.Objects.equals(t.border(), border.color));
        Runnable update = () -> {
            preview.refresh();
            themes.repaint();
        };
        for (ColorField c : List.of(header, fill, border, projectFill, projectBorder)) c.onChange = update;
        for (TextFormat f : texts) f.onChange = update;
        onEdit(note, update);

        FormBuilder builder = FormBuilder.createFormBuilder()
                .addComponent(section("Theme", "Plain is the diagram's own look (and resets everything below); a theme colors "
                        + "the class section, the method section and the border."))
                .addComponent(themes)
                .addComponent(preview)
                .addComponent(section("Colors", "Set one section and the other follows in a matching shade, until you set it too."))
                .addLabeledComponent("Class section:", left(header))
                .addLabeledComponent("Method section:", left(fill))
                .addLabeledComponent("Border:", left(border))
                .addComponent(section("Text", "Auto keeps text readable on its section's background."))
                .addLabeledComponent("Class name:", title);
        if (!b.subtitle().isEmpty()) builder.addLabeledComponent("File name:", file);
        if (!b.project().isEmpty()) {
            builder.addComponent(section("Project name", "The band above the class name: its own background, border and text "
                    + "(Auto: like the class section)."))
                    .addLabeledComponent("Background:", left(projectFill))
                    .addLabeledComponent("Border:", left(projectBorder))
                    .addLabeledComponent("Text:", projectText);
        }
        JPanel form = builder
                .addLabeledComponent("Method names:", methods)
                .addComponent(hint("For every method of this box; a method's own format (Format Method) wins."))
                .addComponent(section("Note under the class name", "Several lines are allowed; long lines wrap. Leave it empty for no note."))
                .addComponent(new NoteBox(note))
                .addLabeledComponent("Note text:", noteFormat)
                .getPanel();
        return new Form(form, () -> {
        b.headerFill = header.color;
        b.fill = fill.color;
        b.border = border.color;
        b.title = title.format();
        b.fileFormat = file.format();
        b.methodsFormat = methods.format();
        b.note = note.getText().strip();
        b.noteFormat = noteFormat.format();
        b.projectFill = projectFill.color;
        b.projectBorder = projectBorder.color;
        b.projectFormat = projectText.format();
        }, () -> applyTheme.accept(THEMES.get(0))); // Reset: the Plain theme
    }

    // ---------- method ----------

    /** Edits a method's name format and note (in place). True when OK was pressed. */
    static boolean editMethod(Project project, Component parent, FlowDiagram.Box box, FlowDiagram.Method m,
                              FlowDiagramPainter.Palette palette) {
        TextFormat name = new TextFormat(m.format, box.methodsFormat != null ? box.methodsFormat : FlowDiagram.Format.PLAIN);
        JBTextArea note = noteArea(m.note);
        TextFormat noteFormat = new TextFormat(m.noteFormat, FlowDiagram.Format.PLAIN);
        Preview preview = new Preview(palette, () -> {
            FlowDiagram.Box p = box.copy();
            for (FlowDiagram.Method x : p.methods) {
                if (!x.id.equals(m.id)) continue;
                x.format = name.format();
                x.note = note.getText().strip();
                x.noteFormat = noteFormat.format();
            }
            return p;
        }, m.id);
        name.onChange = preview::refresh;
        noteFormat.onChange = preview::refresh;
        onEdit(note, preview::refresh);

        JPanel form = FormBuilder.createFormBuilder()
                .addComponent(preview)
                .addComponent(section("Method / function name", "Auto keeps the text readable on the method section's "
                        + "background. Without changes it follows the box's Method names format."))
                .addLabeledComponent("Text:", name)
                .addComponent(section("Note under the name", "Several lines are allowed; long lines wrap. Leave it empty for no note."))
                .addComponent(new NoteBox(note))
                .addLabeledComponent("Text:", noteFormat)
                .getPanel();
        Dialog d = new Dialog(project, parent, "Format " + box.name + "." + m.name, form, () -> {
            name.set(box.methodsFormat != null ? box.methodsFormat : FlowDiagram.Format.PLAIN);
            noteFormat.set(FlowDiagram.Format.PLAIN);
        }, "Back to the default look: the name follows the box's Method names format, the note is plain (its text stays)");
        if (!d.showAndGet()) return false;
        m.format = name.format();
        m.note = note.getText().strip();
        m.noteFormat = noteFormat.format();
        return true;
    }

    // ---------- call ----------

    /** The line picked for a call, whether every call gets it, and the note written on the call. */
    record LineChoice(FlowDiagram.Line line, boolean everyLine, String note, FlowDiagram.NoteStyle noteStyle) {}

    /** Asks how a call's line looks and what its note says; null when cancelled. */
    static @Nullable LineChoice editLine(Project project, Component parent, String title, FlowDiagram.Line current, boolean manual,
                                         String currentNote, FlowDiagram.NoteStyle currentStyle, FlowDiagramPainter.Palette palette) {
        ComboBox<FlowDiagram.LinePattern> pattern = combo(FlowDiagram.LinePattern.values(), current.pattern(),
                v -> new LineIcon(v, FlowDiagram.LineWidth.NORMAL, FlowDiagram.LineShape.STRAIGHT, FlowDiagram.ArrowHead.NONE, manual));
        ComboBox<FlowDiagram.LineWidth> width = combo(FlowDiagram.LineWidth.values(), current.width(),
                v -> new LineIcon(FlowDiagram.LinePattern.SOLID, v, FlowDiagram.LineShape.STRAIGHT, FlowDiagram.ArrowHead.NONE, manual));
        ComboBox<FlowDiagram.LineShape> shape = combo(FlowDiagram.LineShape.values(), current.shape(),
                v -> new LineIcon(FlowDiagram.LinePattern.SOLID, FlowDiagram.LineWidth.NORMAL, v, FlowDiagram.ArrowHead.FILLED, manual));
        ComboBox<FlowDiagram.ArrowHead> arrow = combo(FlowDiagram.ArrowHead.values(), current.arrow(),
                v -> new LineIcon(FlowDiagram.LinePattern.SOLID, FlowDiagram.LineWidth.NORMAL, FlowDiagram.LineShape.STRAIGHT, v, manual));
        ColorField color = new ColorField(current.color(), COLORS, "Auto", "The usual color: grey for calls in the code, "
                + "blue for calls connected by hand, amber for custom ones");
        JBCheckBox all = new JBCheckBox("Use this line for every call in the diagram");
        JBTextArea note = noteArea(currentNote);
        ComboBox<FlowDiagram.NoteShape> noteShape = new ComboBox<>(FlowDiagram.NoteShape.values());
        noteShape.setSelectedItem(currentStyle.shape());
        ColorField noteBg = new ColorField(currentStyle.background(), COLORS, "Auto", "The usual note background (light or dark with the diagram)");
        ColorField noteText = new ColorField(currentStyle.text(), COLORS, "Auto", "The usual text color");
        ColorField noteBorder = new ColorField(currentStyle.border(), COLORS, "Auto", "The usual note border");

        LinePreview preview = new LinePreview(manual, palette);
        Runnable update = () -> {
            preview.line = new FlowDiagram.Line((FlowDiagram.LinePattern) pattern.getSelectedItem(),
                    (FlowDiagram.LineWidth) width.getSelectedItem(), (FlowDiagram.LineShape) shape.getSelectedItem(),
                    (FlowDiagram.ArrowHead) arrow.getSelectedItem(), color.color);
            preview.repaint();
        };
        pattern.addActionListener(e -> update.run());
        width.addActionListener(e -> update.run());
        shape.addActionListener(e -> update.run());
        arrow.addActionListener(e -> update.run());
        color.onChange = update;
        update.run();
        Runnable reset = () -> {
            pattern.setSelectedItem(FlowDiagram.Line.DEFAULT.pattern());
            width.setSelectedItem(FlowDiagram.Line.DEFAULT.width());
            shape.setSelectedItem(FlowDiagram.Line.DEFAULT.shape());
            arrow.setSelectedItem(FlowDiagram.Line.DEFAULT.arrow());
            color.set(null);
            noteShape.setSelectedItem(FlowDiagram.NoteStyle.DEFAULT.shape());
            noteBg.set(null);
            noteText.set(null);
            noteBorder.set(null);
        };

        JPanel form = FormBuilder.createFormBuilder()
                .addComponent(preview)
                .addVerticalGap(JBUI.scale(6))
                .addLabeledComponent("Pattern:", pattern)
                .addLabeledComponent("Width:", width)
                .addLabeledComponent("Shape:", shape)
                .addLabeledComponent("Arrow:", arrow)
                .addLabeledComponent("Color:", left(color))
                .addVerticalGap(JBUI.scale(4))
                .addComponent(all)
                .addComponent(section("Note on the call", "Shown on the arrow, in the Flow Diagram and in the sequence diagram "
                        + "(e.g. \"REST: POST /bookings\"). Press Enter for several lines; long lines wrap. Leave it empty for no note."))
                .addComponent(new NoteBox(note))
                .addLabeledComponent("Shape:", noteShape)
                .addLabeledComponent("Background:", left(noteBg))
                .addLabeledComponent("Text:", left(noteText))
                .addLabeledComponent("Border:", left(noteBorder))
                .getPanel();
        Dialog d = new Dialog(project, parent, "Line of " + title, form, reset,
                "Back to the usual line: automatic pattern, normal width, curved, sharp arrow, automatic color (the note stays)");
        if (!d.showAndGet()) return null;
        FlowDiagram.NoteStyle style = new FlowDiagram.NoteStyle((FlowDiagram.NoteShape) noteShape.getSelectedItem(),
                noteBg.color, noteText.color, noteBorder.color);
        return new LineChoice(preview.line, all.isSelected(), note.getText().strip(), style);
    }

    private static <T> ComboBox<T> combo(T[] values, T selected, java.util.function.Function<T, Icon> icon) {
        ComboBox<T> box = new ComboBox<>(values);
        box.setSelectedItem(selected);
        box.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel, boolean focus) {
                super.getListCellRendererComponent(list, value, index, sel, focus);
                @SuppressWarnings("unchecked") T v = (T) value;
                if (v != null) setIcon(icon.apply(v));
                setIconTextGap(JBUI.scale(10));
                setBorder(JBUI.Borders.empty(2, 4));
                return this;
            }
        });
        return box;
    }

    // ---------- parts ----------

    /** A Format window: the form, OK / Cancel, and a Reset button at the bottom left. */
    private static final class Dialog extends DialogWrapper {
        private final JComponent form;
        private final Runnable reset;
        private final String resetTip;

        /**
         * @param reset    puts every option of the form back to its default (the note text stays)
         * @param resetTip what Reset does, as its tooltip
         */
        Dialog(Project project, Component parent, String title, JComponent form, Runnable reset, String resetTip) {
            super(project, parent, false, IdeModalityType.IDE);
            this.form = form;
            this.reset = reset;
            this.resetTip = resetTip;
            setTitle(title);
            init();
        }

        @Override
        protected javax.swing.Action[] createLeftSideActions() {
            javax.swing.Action action = new javax.swing.AbstractAction("Reset") {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) { reset.run(); }
            };
            action.putValue(javax.swing.Action.SHORT_DESCRIPTION, resetTip);
            return new javax.swing.Action[]{action};
        }

        @Override
        protected JComponent createCenterPanel() {
            // Every part keeps its own height; the window scrolls when that is taller than the screen.
            JBScrollPane scroll = new JBScrollPane(form, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
                @Override
                public Dimension getPreferredSize() {
                    Dimension f = form.getPreferredSize();
                    int max = (int) (Toolkit.getDefaultToolkit().getScreenSize().height * 0.8);
                    return new Dimension(Math.max(JBUI.scale(620), f.width + JBUI.scale(16)), Math.min(f.height + JBUI.scale(4), max));
                }
            };
            com.example.flowtracker.ui.FlowScroll.wheel(scroll);
            scroll.setBorder(JBUI.Borders.empty());
            scroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(16));
            return scroll;
        }
    }

    private static void onEdit(JBTextArea area, Runnable r) {
        area.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(DocumentEvent e) { r.run(); }
        });
    }

    private static JBTextArea noteArea(String text) {
        JBTextArea area = new JBTextArea(text, 2, 40);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(JBUI.Fonts.label());
        area.setBorder(JBUI.Borders.empty(4, 6));
        area.getEmptyText().setText("A note shown in the diagram");
        return area;
    }

    /** Keeps a component at its own width in a form row (instead of stretched across it). */
    private static JComponent left(JComponent c) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setOpaque(false);
        p.add(c);
        return p;
    }

    private static JComponent hint(String text) {
        JBLabel h = new JBLabel(text);
        h.setForeground(UIUtil.getContextHelpForeground());
        h.setFont(JBUI.Fonts.smallFont());
        return h;
    }

    /** A section title, with a short explanation under it. */
    private static JComponent section(String text, @Nullable String hint) {
        JPanel p = new JPanel(new BorderLayout(0, JBUI.scale(2)));
        p.setOpaque(false);
        p.setBorder(JBUI.Borders.empty(12, 0, 4, 0));
        JBLabel l = new JBLabel(text);
        l.setFont(JBUI.Fonts.label().asBold());
        p.add(l, BorderLayout.NORTH);
        if (hint != null) {
            JBLabel h = new JBLabel(hint);
            h.setForeground(UIUtil.getContextHelpForeground());
            h.setFont(JBUI.Fonts.smallFont());
            p.add(h, BorderLayout.CENTER);
        }
        return p;
    }

    /** B, I, U and S toggles (filled when on), and a color (Auto: readable on the background). */
    private static final class TextFormat extends JPanel {
        private final Toggle bold = new Toggle("B", "Bold", new FlowDiagram.Format(true, false, null));
        private final Toggle italic = new Toggle("I", "Italic", new FlowDiagram.Format(false, true, null));
        private final Toggle underline = new Toggle("U", "Underline", new FlowDiagram.Format(false, false, true, false, null));
        private final Toggle strike = new Toggle("S", "Strike-through", new FlowDiagram.Format(false, false, false, true, null));
        private final ColorField color;
        /** The format the text has without one (e.g. a bold class name). */
        private final FlowDiagram.Format usual;
        Runnable onChange = () -> {};

        TextFormat(@Nullable FlowDiagram.Format current, FlowDiagram.Format usual) {
            super(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(1)));
            this.usual = usual;
            setOpaque(false);
            color = new ColorField(null, COLORS, "Auto", "Readable on the background: dark on light, light on dark");
            set(current == null ? usual : current);
            for (Toggle t : List.of(bold, italic, underline, strike)) {
                t.onChange = () -> onChange.run();
                add(t);
            }
            add(Box.createHorizontalStrut(JBUI.scale(10)));
            color.onChange = () -> onChange.run();
            add(color);
        }

        void set(FlowDiagram.Format f) {
            bold.setSelected(f.bold());
            italic.setSelected(f.italic());
            underline.setSelected(f.underline());
            strike.setSelected(f.strike());
            color.set(f.color());
        }

        void clearColor() {
            color.set(null);
        }

        /** Null when it is the usual format (so a theme change still applies). */
        @Nullable FlowDiagram.Format format() {
            FlowDiagram.Format f = new FlowDiagram.Format(bold.isSelected(), italic.isSelected(), underline.isSelected(),
                    strike.isSelected(), color.color);
            return f.equals(usual) ? null : f;
        }
    }

    /** Colors picked in this IDE session, newest first (shown as "Recent" in the color picker). */
    private static final List<Color> RECENT = new ArrayList<>();

    /** The color picker's grid: ten color families in six shades (light to dark), then greys. */
    private static final int[][] GRID = {
            {0xFFEBEE, 0xFFF3E0, 0xFFF8E1, 0xE8F5E9, 0xE0F2F1, 0xE0F7FA, 0xE3F2FD, 0xE8EAF6, 0xF3E5F5, 0xFCE4EC},
            {0xFFCDD2, 0xFFE0B2, 0xFFECB3, 0xC8E6C9, 0xB2DFDB, 0xB2EBF2, 0xBBDEFB, 0xC5CAE9, 0xE1BEE7, 0xF8BBD0},
            {0xE57373, 0xFFB74D, 0xFFD54F, 0x81C784, 0x4DB6AC, 0x4DD0E1, 0x64B5F6, 0x7986CB, 0xBA68C8, 0xF06292},
            {0xF44336, 0xFF9800, 0xFFC107, 0x4CAF50, 0x009688, 0x00BCD4, 0x2196F3, 0x3F51B5, 0x9C27B0, 0xE91E63},
            {0xD32F2F, 0xF57C00, 0xFFA000, 0x388E3C, 0x00796B, 0x0097A7, 0x1976D2, 0x303F9F, 0x7B1FA2, 0xC2185B},
            {0xB71C1C, 0xE65100, 0xFF6F00, 0x1B5E20, 0x004D40, 0x006064, 0x0D47A1, 0x1A237E, 0x4A148C, 0x880E4F},
            {0xFFFFFF, 0xF5F5F5, 0xE0E0E0, 0xBDBDBD, 0x9E9E9E, 0x757575, 0x616161, 0x424242, 0x2B2D30, 0x000000}};

    /**
     * A color as one compact button: its swatch, its name ("Auto" or #RRGGBB) and a ▾. Clicking it opens the color
     * picker: Auto, suggested colors, a grid of shades, recent colors, a #RRGGBB field and the IDE's color chooser.
     */
    static final class ColorField extends JComponent {
        @Nullable Color color;
        Runnable onChange = () -> {};
        private final Color[] suggested;
        private final String autoText;
        private final String autoTip;
        private boolean hover;

        ColorField(@Nullable Color initial, Color[] suggested, String autoText, String autoTip) {
            this.color = initial;
            this.suggested = suggested;
            this.autoText = autoText;
            this.autoTip = autoTip;
            setFont(JBUI.Fonts.label());
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { showPicker(); }
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
            updateTip();
        }

        void set(@Nullable Color c) {
            color = c;
            if (c != null) {
                RECENT.remove(c);
                RECENT.add(0, c);
                while (RECENT.size() > 10) RECENT.remove(RECENT.size() - 1);
            }
            updateTip();
            repaint();
            onChange.run();
        }

        private void updateTip() {
            setToolTipText(color == null ? autoText + ": " + autoTip : FlowDiagram.hex(color) + " (click to change)");
        }

        private String label() {
            return color == null ? autoText : FlowDiagram.hex(color);
        }

        @Override
        public Dimension getPreferredSize() {
            java.awt.FontMetrics m = getFontMetrics(getFont());
            int text = Math.max(m.stringWidth("#WWWWWW"), m.stringWidth(autoText));
            return new Dimension(JBUI.scale(8 + 16 + 8) + text + JBUI.scale(8 + 16 + 6), JBUI.scale(28));
        }

        @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                FlowDiagramPainter.hints(g2);
                int w = getWidth(), h = getHeight(), arc = JBUI.scale(8);
                g2.setColor(hover ? FlowTheme.BTN_HOVER : FlowTheme.BTN_BG);
                g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
                g2.setColor(FlowTheme.BTN_BORDER);
                g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
                int s = JBUI.scale(16), x = JBUI.scale(8), y = (h - s) / 2;
                paintSwatch(g2, color, x, y, s);
                g2.setFont(getFont());
                java.awt.FontMetrics m = g2.getFontMetrics();
                g2.setColor(color == null ? FlowTheme.FG_MUTED : FlowTheme.FG);
                g2.drawString(label(), x + s + JBUI.scale(8), (h - m.getHeight()) / 2f + m.getAscent());
                Icon chevron = FlowTheme.tinted(AllIcons.General.ChevronDown, FlowTheme.FG_MUTED);
                chevron.paintIcon(this, g2, w - JBUI.scale(6) - chevron.getIconWidth(), (h - chevron.getIconHeight()) / 2);
            } finally {
                g2.dispose();
            }
        }

        private void showPicker() {
            JBPopup[] popup = new JBPopup[1];
            JBTextField[] hex = new JBTextField[1];
            JPanel panel = pickerPanel(c -> {
                if (popup[0] != null) popup[0].cancel();
                set(c);
            }, () -> {
                if (popup[0] != null) popup[0].cancel();
                // The overload every supported IDE (2023.1 and later) has without deprecation; no project needed.
                Color c = ColorChooserService.getInstance().showDialog(null, this, "Pick a Color", color, false,
                        java.util.List.of(), false);
                if (c != null) set(c);
            }, hex);
            popup[0] = JBPopupFactory.getInstance()
                    .createComponentPopupBuilder(panel, hex[0])
                    .setRequestFocus(true)
                    .setCancelOnClickOutside(true)
                    .setMovable(false)
                    .setResizable(false)
                    .createPopup();
            popup[0].show(new RelativePoint(this, new Point(0, getHeight() + FlowTheme.popupGap())));
        }

        /** The color picker's content (also used by the tests to draw it). */
        JPanel pickerPanel(java.util.function.Consumer<Color> pick, Runnable more, JBTextField[] hexOut) {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBackground(FlowTheme.CARD_BG);
            panel.setBorder(JBUI.Borders.empty(10, 12));

            panel.add(new AutoOption(autoText, autoTip, color == null, () -> pick.accept(null)));
            panel.add(Box.createVerticalStrut(JBUI.scale(8)));
            panel.add(pickerLabel("Suggested"));
            panel.add(swatchRow(List.of(suggested), pick));
            panel.add(pickerLabel("All colors"));
            JPanel grid = new JPanel(new GridLayout(GRID.length, GRID[0].length, JBUI.scale(3), JBUI.scale(3)));
            grid.setOpaque(false);
            grid.setAlignmentX(LEFT_ALIGNMENT);
            for (int[] row : GRID) {
                for (int rgb : row) {
                    Color c = new Color(rgb);
                    grid.add(new Swatch(c, () -> c.equals(color), () -> pick.accept(c), 22));
                }
            }
            panel.add(grid);
            if (!RECENT.isEmpty()) {
                panel.add(pickerLabel("Recent"));
                panel.add(swatchRow(new ArrayList<>(RECENT), pick));
            }

            JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            bottom.setOpaque(false);
            bottom.setAlignmentX(LEFT_ALIGNMENT);
            bottom.setBorder(JBUI.Borders.emptyTop(10));
            JBTextField hex = new JBTextField(color == null ? "" : FlowDiagram.hex(color), 8);
            hexOut[0] = hex;
            hex.getEmptyText().setText("#RRGGBB");
            hex.setToolTipText("Type a color as #RRGGBB and press Enter");
            hex.addActionListener(e -> {
                String t = hex.getText().strip();
                Color c = FlowDiagram.parseColor(t.startsWith("#") ? t : "#" + t);
                if (c != null) pick.accept(c);
                else hex.putClientProperty("JComponent.outline", "error");
            });
            bottom.add(hex);
            bottom.add(Box.createHorizontalStrut(JBUI.scale(10)));
            bottom.add(new ActionLink("More colors…", (java.awt.event.ActionListener) e -> more.run()));
            panel.add(bottom);
            return panel;
        }

        private static JComponent pickerLabel(String text) {
            JBLabel l = new JBLabel(text);
            l.setFont(JBUI.Fonts.smallFont().asBold());
            l.setForeground(FlowTheme.FG_MUTED);
            l.setBorder(JBUI.Borders.empty(8, 0, 4, 0));
            l.setAlignmentX(LEFT_ALIGNMENT);
            return l;
        }

        private JComponent swatchRow(List<Color> colors, java.util.function.Consumer<Color> pick) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0));
            row.setOpaque(false);
            row.setAlignmentX(LEFT_ALIGNMENT);
            for (Color c : colors) row.add(new Swatch(c, () -> c.equals(color), () -> pick.accept(c), 22));
            return row;
        }
    }

    /** The picker's "Auto" choice: a wide row, highlighted while no color is set. */
    private static final class AutoOption extends JComponent {
        private final String text, tip;
        private final boolean on;
        private boolean hover;

        AutoOption(String text, String tip, boolean on, Runnable pick) {
            this.text = text;
            this.tip = tip;
            this.on = on;
            setAlignmentX(LEFT_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { pick.run(); }
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }

        @Override public Dimension getPreferredSize() { return new Dimension(JBUI.scale(10 * 25), JBUI.scale(40)); }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                FlowDiagramPainter.hints(g2);
                int w = getWidth(), h = getHeight(), arc = JBUI.scale(8);
                g2.setColor(on ? FlowDiagramPainter.mix(FlowTheme.ACCENT, FlowTheme.CARD_BG, 0.22) : hover ? FlowTheme.BTN_HOVER : FlowTheme.CARD_BG);
                g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
                g2.setColor(on ? FlowTheme.ACCENT : FlowTheme.BTN_BORDER);
                g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
                int s = JBUI.scale(18);
                paintSwatch(g2, null, JBUI.scale(10), (h - s) / 2, s);
                int tx = JBUI.scale(10) + s + JBUI.scale(10);
                g2.setFont(JBUI.Fonts.label().asBold());
                g2.setColor(FlowTheme.FG);
                java.awt.FontMetrics m = g2.getFontMetrics();
                g2.drawString(text, tx, JBUI.scale(5) + m.getAscent());
                g2.setFont(JBUI.Fonts.smallFont());
                g2.setColor(FlowTheme.FG_MUTED);
                java.awt.FontMetrics sm = g2.getFontMetrics();
                String t = tip;
                while (t.length() > 4 && tx + sm.stringWidth(t) > w - JBUI.scale(28)) t = t.substring(0, t.length() - 2);
                g2.drawString(t.equals(tip) ? t : t + "…", tx, h - JBUI.scale(6) - sm.getDescent());
                if (on) {
                    Icon check = FlowTheme.tinted(AllIcons.Actions.Checked, FlowTheme.ACCENT);
                    check.paintIcon(this, g2, w - JBUI.scale(8) - check.getIconWidth(), (h - check.getIconHeight()) / 2);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /** A color square (rounded), or "none" (a crossed square) for null. */
    private static void paintSwatch(Graphics2D g2, @Nullable Color c, int x, int y, int s) {
        int arc = JBUI.scale(5);
        if (c != null) {
            g2.setColor(c);
            g2.fillRoundRect(x, y, s, s, arc, arc);
        } else {
            g2.setColor(FlowTheme.FG_MUTED);
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawLine(x + 3, y + s - 3, x + s - 3, y + 3);
        }
        g2.setColor(JBColor.border());
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(x, y, s - 1, s - 1, arc, arc);
    }

    /** The note's text box at a set height, with a handle under it to drag it taller or shorter. */
    private static final class NoteBox extends JPanel {
        private int height = JBUI.scale(64);

        NoteBox(JBTextArea area) {
            super(new BorderLayout());
            setOpaque(false);
            JBScrollPane scroll = new JBScrollPane(area) {
                @Override public Dimension getPreferredSize() { return new Dimension(JBUI.scale(300), height); }
                @Override public Dimension getMinimumSize() { return new Dimension(JBUI.scale(100), height); }
            };
            com.example.flowtracker.ui.FlowScroll.wheel(scroll);
            add(scroll, BorderLayout.CENTER);
            JComponent grip = new JComponent() {
                private int startY, startH;
                {
                    setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
                    setToolTipText("Drag to make the note box taller or shorter");
                    MouseAdapter drag = new MouseAdapter() {
                        @Override public void mousePressed(MouseEvent e) { startY = e.getYOnScreen(); startH = height; }
                        @Override public void mouseDragged(MouseEvent e) {
                            height = Math.max(JBUI.scale(40), Math.min(JBUI.scale(480), startH + e.getYOnScreen() - startY));
                            scroll.revalidate();
                            fitWindow(NoteBox.this);
                        }
                    };
                    addMouseListener(drag);
                    addMouseMotionListener(drag);
                }

                @Override public Dimension getPreferredSize() { return new Dimension(JBUI.scale(40), JBUI.scale(10)); }

                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    try {
                        FlowDiagramPainter.hints(g2);
                        g2.setColor(FlowTheme.FG_MUTED);
                        int cx = getWidth() / 2, cy = getHeight() / 2, len = JBUI.scale(14);
                        g2.drawLine(cx - len, cy - 1, cx + len, cy - 1);
                        g2.drawLine(cx - len / 2, cy + 2, cx + len / 2, cy + 2);
                    } finally {
                        g2.dispose();
                    }
                }
            };
            add(grip, BorderLayout.SOUTH);
        }
    }

    /** Grows or shrinks the window around a component whose size changed (up to most of the screen). */
    private static void fitWindow(Component c) {
        java.awt.Window w = SwingUtilities.getWindowAncestor(c);
        if (w == null) return;
        w.validate();
        Dimension pref = w.getPreferredSize();
        int max = (int) (Toolkit.getDefaultToolkit().getScreenSize().height * 0.9);
        w.setSize(w.getWidth(), Math.min(pref.height, max));
    }

    /** A B / I / U / S switch: filled with the accent color while on, outlined while off. */
    private static final class Toggle extends JComponent {
        private final String letter;
        private final FlowDiagram.Format look;
        private final String tip;
        private boolean on, hover;
        Runnable onChange = () -> {};

        Toggle(String letter, String tip, FlowDiagram.Format look) {
            this.letter = letter;
            this.tip = tip;
            this.look = look;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { setSelected(!on); onChange.run(); }
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
            setSelected(false);
        }

        boolean isSelected() { return on; }

        void setSelected(boolean on) {
            this.on = on;
            setToolTipText(tip + (on ? ": on" : ": off"));
            repaint();
        }

        @Override public Dimension getPreferredSize() { return new Dimension(JBUI.scale(30), JBUI.scale(26)); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                FlowDiagramPainter.hints(g2);
                int w = getWidth(), h = getHeight(), arc = JBUI.scale(8);
                g2.setColor(on ? FlowTheme.ACCENT : hover ? FlowTheme.BTN_HOVER : FlowTheme.BTN_BG);
                g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
                if (!on) {
                    g2.setColor(FlowTheme.BTN_BORDER);
                    g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
                }
                java.awt.Font f = FlowDiagramPainter.styled(JBUI.Fonts.label().deriveFont(JBUI.Fonts.label().getSize2D() + 1f),
                        look, java.awt.Font.PLAIN);
                g2.setFont(f);
                g2.setColor(on ? Color.WHITE : FlowTheme.FG);
                java.awt.FontMetrics m = g2.getFontMetrics();
                g2.drawString(letter, (w - m.stringWidth(letter)) / 2f, (h - m.getHeight()) / 2f + m.getAscent());
            } finally {
                g2.dispose();
            }
        }
    }

    /** One color to click; a ring marks the chosen one. A null color is drawn as "none" (crossed out). */
    private static class Swatch extends JComponent {
        private final @Nullable Color color;
        private final @Nullable java.util.function.BooleanSupplier chosen;
        private final int size;

        Swatch(@Nullable Color color, @Nullable java.util.function.BooleanSupplier chosen, Runnable pick, int size) {
            this.color = color;
            this.chosen = chosen;
            this.size = size;
            if (color != null) setToolTipText(FlowDiagram.hex(color));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { pick.run(); }
            });
        }

        @Nullable Color shown() { return color; }

        @Override public Dimension getPreferredSize() { return new Dimension(JBUI.scale(size), JBUI.scale(size)); }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                FlowDiagramPainter.hints(g2);
                int s = Math.min(getWidth(), getHeight());
                int pad = JBUI.scale(2), arc = JBUI.scale(5);
                Color c = shown();
                if (c != null) {
                    g2.setColor(c);
                    g2.fillRoundRect(pad, pad, s - 2 * pad, s - 2 * pad, arc, arc);
                } else {
                    g2.setColor(FlowTheme.FG_MUTED);
                    g2.drawLine(pad + 2, s - pad - 2, s - pad - 2, pad + 2);
                }
                g2.setColor(JBColor.border());
                g2.drawRoundRect(pad, pad, s - 2 * pad - 1, s - 2 * pad - 1, arc, arc);
                if (chosen != null && chosen.getAsBoolean()) {
                    g2.setColor(FlowTheme.ACCENT);
                    g2.setStroke(new BasicStroke(JBUI.scale(2)));
                    g2.drawRoundRect(1, 1, s - 3, s - 3, arc + 2, arc + 2);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /** The themes as small sample boxes; clicking one applies it. The one the box looks like has a ring. */
    private static final class ThemePicker extends JPanel {
        ThemePicker(FlowDiagramPainter.Palette palette, java.util.function.Consumer<Theme> apply,
                    java.util.function.Predicate<Theme> chosen) {
            super(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(2)));
            setOpaque(false);
            for (Theme t : THEMES) {
                add(new ThemeChip(t, palette, () -> {
                    apply.accept(t);
                    repaint();
                }, () -> chosen.test(t)));
            }
        }
    }

    private static final class ThemeChip extends JComponent {
        private final Theme theme;
        private final FlowDiagramPainter.Palette palette;
        private final java.util.function.BooleanSupplier chosen;

        ThemeChip(Theme theme, FlowDiagramPainter.Palette palette, Runnable pick, java.util.function.BooleanSupplier chosen) {
            this.theme = theme;
            this.palette = palette;
            this.chosen = chosen;
            setToolTipText(theme.fill() == null ? "Plain: the diagram's own look (resets colors and text)" : theme.name());
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { pick.run(); }
            });
        }

        @Override public Dimension getPreferredSize() { return new Dimension(JBUI.scale(48), JBUI.scale(56)); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                FlowDiagramPainter.hints(g2);
                FlowDiagramPainter.Palette p = FlowDiagramPainter.boxPalette(palette, theme.fill());
                int w = getWidth(), bw = JBUI.scale(42), bh = JBUI.scale(32), x = (w - bw) / 2, y = JBUI.scale(4);
                float arc = JBUI.scale(8);
                g2.setColor(palette.background());
                g2.fill(new RoundRectangle2D.Float(x - 2, y - 2, bw + 4, bh + 4, arc + 2, arc + 2));
                RoundRectangle2D box = new RoundRectangle2D.Float(x, y, bw, bh, arc, arc);
                g2.setColor(p.boxBg());
                g2.fill(box);
                java.awt.Shape clip = g2.getClip();
                g2.clip(box);
                g2.setColor(theme.header() != null ? theme.header() : p.headerBg());
                g2.fillRect(x, y, bw, JBUI.scale(11));
                g2.setClip(clip);
                // the name (readable on the class section), and two method lines
                g2.setColor(theme.header() != null ? FlowDiagramPainter.boxPalette(palette, theme.header()).title() : p.title());
                g2.fillRoundRect(x + JBUI.scale(6), y + JBUI.scale(4), JBUI.scale(20), JBUI.scale(3), 2, 2);
                g2.setColor(p.muted());
                g2.fillRoundRect(x + JBUI.scale(6), y + JBUI.scale(17), JBUI.scale(26), JBUI.scale(3), 2, 2);
                g2.fillRoundRect(x + JBUI.scale(6), y + JBUI.scale(24), JBUI.scale(16), JBUI.scale(3), 2, 2);
                g2.setColor(theme.border() != null ? theme.border() : p.boxBorder());
                g2.setStroke(new BasicStroke(theme.border() != null ? 1.6f : 1f));
                g2.draw(box);
                if (chosen.getAsBoolean()) {
                    g2.setColor(FlowTheme.ACCENT);
                    g2.setStroke(new BasicStroke(JBUI.scale(2)));
                    g2.draw(new RoundRectangle2D.Float(x - 3, y - 3, bw + 6, bh + 6, arc + 4, arc + 4));
                }
                g2.setFont(JBUI.Fonts.smallFont());
                g2.setColor(FlowTheme.FG);
                int tw = g2.getFontMetrics().stringWidth(theme.name());
                g2.drawString(theme.name(), (w - tw) / 2f, getHeight() - JBUI.scale(3));
            } finally {
                g2.dispose();
            }
        }
    }

    /** The box as it will look, on the diagram's background; the method being formatted is highlighted. */
    private static final class Preview extends JComponent {
        private final FlowDiagramPainter painter = new FlowDiagramPainter(JBUI.Fonts.label());
        private final FlowDiagramPainter.Palette palette;
        private final java.util.function.Supplier<FlowDiagram.Box> box;
        private final @Nullable String methodId;
        private FlowDiagram diagram = new FlowDiagram();

        Preview(FlowDiagramPainter.Palette palette, java.util.function.Supplier<FlowDiagram.Box> box, @Nullable String methodId) {
            this.palette = palette;
            this.box = box;
            this.methodId = methodId;
            setBorder(JBUI.Borders.customLine(JBColor.border()));
            refresh();
        }

        void refresh() {
            FlowDiagram d = new FlowDiagram();
            FlowDiagram.Box b = box.get();
            if (methodId == null) while (b.methods.size() > 3) b.methods.remove(b.methods.size() - 1);
            b.x = 16;
            b.y = 14;
            d.boxes.add(b);
            diagram = d;
            revalidate();
            repaint();
        }

        private float scale() { return JBUI.scale(100) / 100f; }

        @Override
        public Dimension getPreferredSize() {
            FlowDiagram.Box b = diagram.boxes.get(0);
            return new Dimension(JBUI.scale(300), (int) ((painter.height(b) + 30) * scale()));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(palette.background());
                g2.fillRect(0, 0, getWidth(), getHeight());
                FlowDiagram.Box b = diagram.boxes.get(0);
                b.x = Math.max(16, (getWidth() / scale() - painter.width(b)) / 2); // centered
                g2.scale(scale(), scale());
                painter.paint(g2, diagram, palette, new FlowDiagramPainter.Selection(null, methodId, null));
            } finally {
                g2.dispose();
            }
        }
    }

    /** A small picture of a line option, for the drop-down lists. */
    private record LineIcon(FlowDiagram.LinePattern pattern, FlowDiagram.LineWidth width, FlowDiagram.LineShape shape,
                            FlowDiagram.ArrowHead arrow, boolean manual) implements Icon {
        @Override public int getIconWidth() { return JBUI.scale(64); }
        @Override public int getIconHeight() { return JBUI.scale(16); }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                FlowDiagramPainter.hints(g2);
                g2.translate(x, y);
                float k = JBUI.scale(100) / 100f;
                g2.scale(k, k);
                g2.setColor(c.getForeground());
                drawSample(g2, pattern, width, shape, arrow, manual, c.getBackground(), 4, 3, 60, 13);
            } finally {
                g2.dispose();
            }
        }
    }

    /** A sample line from (x1, y1) to (x2, y2) in diagram units, with its arrow. */
    private static void drawSample(Graphics2D g2, FlowDiagram.LinePattern pattern, FlowDiagram.LineWidth width,
                                   FlowDiagram.LineShape shape, FlowDiagram.ArrowHead arrow, boolean manual,
                                   Color background, double x1, double y1, double x2, double y2) {
        java.awt.Shape path;
        Point2D before;
        double mx = (x1 + x2) / 2;
        switch (shape) {
            case CURVED -> {
                path = new CubicCurve2D.Double(x1, y1, mx, y1, mx, y2, x2, y2);
                before = new Point2D.Double(mx, y2);
            }
            case STRAIGHT -> {
                double my = (y1 + y2) / 2; // a straight sample is level, so patterns read well
                path = new Line2D.Double(x1, my, x2, my);
                before = new Point2D.Double(x1, my);
                y2 = my;
            }
            default -> {
                Path2D p = new Path2D.Double();
                p.moveTo(x1, y1);
                p.lineTo(mx, y1);
                p.lineTo(mx, y2);
                p.lineTo(x2, y2);
                path = p;
                before = new Point2D.Double(mx, y2);
            }
        }
        FlowDiagramPainter.drawLine(g2, path, pattern, manual, width.width, background);
        FlowDiagramPainter.arrowHead(g2, before, new Point2D.Double(x2, y2), arrow, width.width);
    }

    /** A sample of the line being picked, on the diagram's background. */
    private static final class LinePreview extends JComponent {
        FlowDiagram.Line line = FlowDiagram.Line.DEFAULT;
        private final boolean manual;
        private final FlowDiagramPainter.Palette palette;

        LinePreview(boolean manual, FlowDiagramPainter.Palette palette) {
            this.manual = manual;
            this.palette = palette;
            setBorder(JBUI.Borders.customLine(JBColor.border()));
        }

        @Override public Dimension getPreferredSize() { return new Dimension(JBUI.scale(300), JBUI.scale(84)); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                FlowDiagramPainter.hints(g2);
                g2.setColor(palette.background());
                g2.fillRect(0, 0, getWidth(), getHeight());
                float k = JBUI.scale(100) / 100f;
                g2.scale(k, k);
                double w = getWidth() / k, h = getHeight() / k;
                g2.setColor(line.color() != null ? line.color() : manual ? palette.manualCall() : palette.codeCall());
                drawSample(g2, line.pattern(), line.width(), line.shape(), line.arrow(), manual, palette.background(), 40, 22, w - 40, h - 22);
            } finally {
                g2.dispose();
            }
        }
    }
}
