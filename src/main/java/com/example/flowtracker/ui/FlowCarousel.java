package com.example.flowtracker.ui;

import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Screenshot carousel at the top of the Info dialog: the images packaged in the plugin
 * ({@code /images/traceweave-*.png}), one at a time with a caption, dots to pick one, arrows on hover and
 * the Left / Right keys. It moves on by itself every few seconds unless the mouse is over it.
 */
final class FlowCarousel extends JComponent {

    /** Screenshots in the order they are shown; missing files are skipped. */
    private static final String[][] SLIDES = {
            {"traceweave-grouped-view.png", "Grouped View: files, methods and saved lines with syntax colors"},
            {"traceweave-flat-view.png", "Flat View: one numbered sequence in flow order"},
            {"traceweave-hover-actions.png", "Hover a row for its actions: move, copy, note and delete"},
            {"traceweave-record.png", "Record: every line where the debugger stops is added"},
            {"traceweave-recycle-bin.png", "Recycle Bin: restore deleted lines and flows"},
            {"traceweave-settings.png", "Settings: call order, copy formats, Copy All Flows, IntelliJ theme, Recycle Bin"},
            {"traceweave-markdown-copy.png", "Markdown copy: diagrams of the real calls"},
            {"traceweave-flow-diagram.png", "Flow Diagram: edit the flow as boxes and arrows, with a History"},
            {"traceweave-diagram-code-panel.png", "Click a method to see its code; View Full Code shows the whole file"},
            {"traceweave-diagram-connect-flow.png", "Connect the Flow: the calls between two methods, read from the code"},
            {"traceweave-ways-onscreen.png", "Several ways to connect: hover one to see it on the tab"},
            {"traceweave-ways-fullscreen.png", "Choose a Way in full screen: the way you hover or click is previewed"},
            {"traceweave-diagram-format-box.png", "Format Box: themes, section colors, text formats and a note"},
            {"traceweave-diagram-color-picker.png", "Pick any color: Auto, suggested, a color grid, recent or #RRGGBB"},
            {"traceweave-diagram-format-line.png", "Line: pattern, width, shape, arrow and color"},
            {"traceweave-diagram-sequence.png", "Copy Sequence PNG: the calls as a sequence diagram image"},
            {"traceweave-debug-data.png", "Debug Data: save debug values by flow and line, find and edit them"},
            {"traceweave-compare.png", "Compare: two values side by side, what differs marked Old and New"},
            {"traceweave-stop-at-value.png", "Stop at Value: the debugger stops where a value shows up"},
            {"traceweave-add-stop-at-value.png", "Add Stop at Value: the values to look for and where to look"},
            {"traceweave-watch-field.png", "Watch a field, an object, a method's variable or what it returns"},
    };
    private static final int AUTO_ADVANCE_MS = 5000;

    private record Slide(BufferedImage image, String caption) {}

    private final List<Slide> slides = new ArrayList<>();
    private int index;
    private boolean hover;
    private int hoverArrow; // -1 left, +1 right, 0 none
    private final Timer timer;
    /** Last scaled image: index, device width and height it was made for. */
    private BufferedImage scaled;
    private int scaledIndex = -1, scaledW, scaledH;

    FlowCarousel() {
        for (String[] s : SLIDES) {
            BufferedImage img = load(s[0]);
            if (img != null) slides.add(new Slide(img, s[1]));
        }
        setOpaque(false);
        setFocusable(true);
        timer = new Timer(AUTO_ADVANCE_MS, e -> {
            if (!hover) go(+1);
        });

        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
            @Override public void mouseExited(MouseEvent e) { hover = false; hoverArrow = 0; setCursor(null); repaint(); }

            @Override
            public void mouseMoved(MouseEvent e) {
                int arrow = arrowAt(e.getX(), e.getY());
                int dot = dotAt(e.getX(), e.getY());
                setCursor(arrow != 0 || dot >= 0 ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
                if (arrow != hoverArrow) { hoverArrow = arrow; repaint(); }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                requestFocusInWindow();
                int arrow = arrowAt(e.getX(), e.getY());
                if (arrow != 0) { go(arrow); return; }
                int dot = dotAt(e.getX(), e.getY());
                if (dot >= 0) show(dot);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        getInputMap().put(KeyStroke.getKeyStroke("LEFT"), "prevSlide");
        getInputMap().put(KeyStroke.getKeyStroke("RIGHT"), "nextSlide");
        getActionMap().put("prevSlide", action(() -> go(-1)));
        getActionMap().put("nextSlide", action(() -> go(+1)));
    }

    /** True when at least one screenshot is packaged (otherwise the dialog leaves the carousel out). */
    boolean hasSlides() {
        return !slides.isEmpty();
    }

    private static @Nullable BufferedImage load(String name) {
        try (InputStream in = FlowCarousel.class.getResourceAsStream("/images/" + name)) {
            return in == null ? null : ImageIO.read(in);
        } catch (Exception ex) {
            return null;
        }
    }

    private void go(int delta) {
        if (slides.isEmpty()) return;
        show((index + delta + slides.size()) % slides.size());
    }

    private void show(int i) {
        index = i;
        timer.restart(); // a manual pick gets the full time before the next automatic one
        repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        if (slides.size() > 1) timer.start();
    }

    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }

    // ---------- layout ----------

    private static int pad() { return JBUI.scale(8); }
    private static int dotsHeight() { return JBUI.scale(22); }
    private int captionHeight() { return getFontMetrics(captionFont()).getHeight() + JBUI.scale(4); }

    private static Font captionFont() {
        return JBUI.Fonts.label();
    }

    @Override
    public Dimension getPreferredSize() {
        int w = JBUI.scale(560);
        return new Dimension(w, (int) ((w - 2 * pad()) * 0.56) + 2 * pad() + dotsHeight() + captionHeight());
    }

    /** The area the screenshot is drawn in (the card around it is a little larger). */
    private Rectangle imageArea() {
        int h = getHeight() - dotsHeight() - captionHeight() - 2 * pad();
        return new Rectangle(pad(), pad(), getWidth() - 2 * pad(), Math.max(0, h));
    }

    private int arrowSize() { return JBUI.scale(30); }

    /** -1 / +1 when the point is on the left / right arrow (only shown while hovering), else 0. */
    private int arrowAt(int x, int y) {
        if (!hover || slides.size() < 2) return 0;
        Rectangle a = imageArea();
        int s = arrowSize(), cy = a.y + a.height / 2;
        if (y < cy - s / 2 || y > cy + s / 2) return 0;
        if (x >= a.x + JBUI.scale(8) && x <= a.x + JBUI.scale(8) + s) return -1;
        if (x <= a.x + a.width - JBUI.scale(8) && x >= a.x + a.width - JBUI.scale(8) - s) return +1;
        return 0;
    }

    private int dotX(int i) {
        int gap = JBUI.scale(16);
        int total = (slides.size() - 1) * gap;
        return getWidth() / 2 - total / 2 + i * gap;
    }

    private int dotY() {
        Rectangle a = imageArea();
        return a.y + a.height + pad() + dotsHeight() / 2;
    }

    private int dotAt(int x, int y) {
        if (slides.size() < 2 || Math.abs(y - dotY()) > JBUI.scale(8)) return -1;
        for (int i = 0; i < slides.size(); i++) {
            if (Math.abs(x - dotX(i)) <= JBUI.scale(7)) return i;
        }
        return -1;
    }

    // ---------- painting ----------

    @Override
    protected void paintComponent(Graphics g) {
        if (slides.isEmpty()) return;
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Rectangle a = imageArea();
            int arc = JBUI.scale(12);

            // Card behind the screenshot.
            Shape card = new RoundRectangle2D.Float(a.x - JBUI.scale(4), a.y - JBUI.scale(4),
                    a.width + JBUI.scale(8), a.height + JBUI.scale(8), arc, arc);
            g2.setColor(FlowTheme.CODE_BG);
            g2.fill(card);
            g2.setColor(FlowTheme.CARD_BORDER);
            g2.draw(card);

            // The screenshot, fitted inside the area and centered.
            Slide slide = slides.get(index);
            double fit = Math.min(a.width / (double) slide.image.getWidth(), a.height / (double) slide.image.getHeight());
            int w = Math.max(1, (int) (slide.image.getWidth() * fit));
            int h = Math.max(1, (int) (slide.image.getHeight() * fit));
            int x = a.x + (a.width - w) / 2, y = a.y + (a.height - h) / 2;
            double device = Math.max(1.0, g2.getTransform().getScaleX());
            Shape oldClip = g2.getClip();
            g2.clip(new RoundRectangle2D.Float(x, y, w, h, JBUI.scale(8), JBUI.scale(8)));
            g2.drawImage(scaledImage(index, (int) Math.round(w * device), (int) Math.round(h * device)), x, y, w, h, null);
            g2.setClip(oldClip);

            if (hover && slides.size() > 1) {
                paintArrow(g2, a.x + JBUI.scale(8), a.y + a.height / 2, -1);
                paintArrow(g2, a.x + a.width - JBUI.scale(8) - arrowSize(), a.y + a.height / 2, +1);
            }

            // Dots, then the caption.
            for (int i = 0; i < slides.size() && slides.size() > 1; i++) {
                int r = JBUI.scale(i == index ? 4 : 3);
                g2.setColor(i == index ? FlowTheme.ACCENT : FlowTheme.FG_MUTED);
                if (i == index) g2.fillOval(dotX(i) - r, dotY() - r, 2 * r, 2 * r);
                else g2.drawOval(dotX(i) - r, dotY() - r, 2 * r, 2 * r);
            }
            g2.setFont(captionFont());
            g2.setColor(FlowTheme.FG);
            FontMetrics fm = g2.getFontMetrics();
            String caption = slide.caption + (slides.size() > 1 ? "   " + (index + 1) + " / " + slides.size() : "");
            int cy = a.y + a.height + pad() + dotsHeight() + fm.getAscent();
            g2.drawString(caption, Math.max(pad(), (getWidth() - fm.stringWidth(caption)) / 2), cy);
        } finally {
            g2.dispose();
        }
    }

    private void paintArrow(Graphics2D g, int x, int cy, int direction) {
        int s = arrowSize();
        int y = cy - s / 2;
        boolean active = hoverArrow == direction;
        g.setColor(new Color(0, 0, 0, active ? 170 : 110));
        g.fillOval(x, y, s, s);
        g.setColor(Color.WHITE);
        g.setStroke(new java.awt.BasicStroke(JBUIScale.scale(2f), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        int m = s / 2, d = JBUI.scale(5);
        Path2D chevron = new Path2D.Float();
        chevron.moveTo(x + m - direction * (d / 2f), y + m - d);
        chevron.lineTo(x + m + direction * (d / 2f), y + m);
        chevron.lineTo(x + m - direction * (d / 2f), y + m + d);
        g.draw(chevron);
    }

    /** The image at a device size, scaled down in halving steps so large screenshots stay sharp. */
    private BufferedImage scaledImage(int i, int w, int h) {
        if (scaled != null && scaledIndex == i && scaledW == w && scaledH == h) return scaled;
        BufferedImage src = slides.get(i).image;
        BufferedImage img = src;
        int cw = src.getWidth(), ch = src.getHeight();
        do {
            cw = Math.max(w, cw / 2);
            ch = Math.max(h, ch / 2);
            if (cw < w * 2 && ch < h * 2) { cw = w; ch = h; }
            BufferedImage next = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(img, 0, 0, cw, ch, null);
            g.dispose();
            img = next;
        } while (cw != w || ch != h);
        scaled = img;
        scaledIndex = i;
        scaledW = w;
        scaledH = h;
        return img;
    }

    private static AbstractAction action(Runnable r) {
        return new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        };
    }
}
