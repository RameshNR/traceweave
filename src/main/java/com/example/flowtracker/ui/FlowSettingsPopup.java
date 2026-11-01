package com.example.flowtracker.ui;

import com.example.flowtracker.code.FlowSync;
import com.example.flowtracker.model.FlowLook;
import com.example.flowtracker.model.FlowStore;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** The popup behind the toolbar's gear button: each option is a row with a title, a hint and a switch. */
public final class FlowSettingsPopup {
    private FlowSettingsPopup() {}

    static void show(Project project, FlowStore store, JComponent anchor) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(FlowTheme.CARD_BG);
        panel.setBorder(JBUI.Borders.empty(12, 14, 10, 14));

        JLabel title = new JLabel("TraceWeave Settings");
        title.setFont(JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD, JBUI.Fonts.label().getSize2D() + 1f));
        title.setForeground(FlowTheme.FG);
        title.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        panel.add(title);
        panel.add(Box.createVerticalStrut(JBUI.scale(4)));
        Switch sync = new Switch(store.isSyncFiles());
        Switch optimize = new Switch(store.isOptimizeFlatLog());
        Switch markdown = new Switch(store.isCopyMarkdown());
        Switch sequence = new Switch(store.isSequenceDiagram());
        Switch copyAll = new Switch(store.isCopyAllFlows());
        Switch nestedLog = new Switch(store.isNestedCallLog());
        FlowLook look = FlowLook.get();
        Switch ideTheme = new Switch(look.isUseIdeTheme());

        panel.add(sectionLabel("ORDERING"));
        panel.add(row(FlowTheme.ICON_SYNC, "Sync Files",
                "Keep lines in call order automatically: a method first, then the methods it calls, "
                        + "right after the calling line. Moving a line by hand turns this off.",
                sync, on -> {
                    store.setSyncFiles(on);
                    FlowSync.apply(project);
                }));
        panel.add(sectionLabel("COPY"));
        panel.add(row(FlowTheme.ICON_LIST, "Optimize Flat View Log",
                "In Flat View, copy a compact log with [project], file and method headings and "
                        + "\"line | code\" rows. Turns Copy as Markdown off.",
                optimize, store::setOptimizeFlatLog));
        panel.add(Box.createVerticalStrut(JBUI.scale(2)));
        panel.add(row(FlowTheme.ICON_MARKDOWN, "Copy as Markdown",
                "Copy everything as a .md document: a summary, flow diagrams, each method's lines as a "
                        + "table with highlighted code and notes, and the Original Log at the end. "
                        + "Turns Optimize Flat View Log off.",
                markdown, store::setCopyMarkdown));
        panel.add(Box.createVerticalStrut(JBUI.scale(2)));
        panel.add(row(FlowTheme.ICON_FLOWS, "Sequence Diagram",
                "In the Markdown copy, draw the calls as a Mermaid sequence diagram (files calling each "
                        + "other, with returns) instead of a flowchart.",
                sequence, store::setSequenceDiagram));
        panel.add(Box.createVerticalStrut(JBUI.scale(2)));
        int flows = store.getFlowNames().size();
        panel.add(row(FlowTheme.ICON_FLOWS, "Copy All Flows",
                "The toolbar's Copy button copies all " + flows + (flows == 1 ? " flow" : " flows")
                        + ", one after the other under its name, instead of only the current one. "
                        + "Ctrl+C still copies the selected lines.",
                copyAll, store::setCopyAllFlows));
        panel.add(Box.createVerticalStrut(JBUI.scale(2)));
        panel.add(row(FlowTheme.ICON_LIST, "Nest Calls in Original Log",
                "In the Markdown copy's Original Log, put each called method's lines right under the line that "
                        + "calls it, indented like a call stack (Grouped and Flat View). Off: the plain file / method "
                        + "list or numbered steps.",
                nestedLog, store::setNestedCallLog));

        panel.add(sectionLabel("LOOK"));
        panel.add(row(FlowTheme.ICON_THEME, "Use IntelliJ Theme",
                "Take every color from the current IntelliJ theme and editor color scheme: the panel, buttons, popups, "
                        + "highlighted code and the Flow Diagram. Off: TraceWeave's own colors. Applies to all projects.",
                ideTheme, look::setUseIdeTheme));

        JBPopup[] holder = new JBPopup[1];
        panel.add(sectionLabel("DELETED"));
        int binned = store.getRecycleBin().size();
        panel.add(actionRow(FlowTheme.ICON_RECYCLE, "Recycle Bin",
                (binned == 0 ? "Empty. " : binned + (binned == 1 ? " deleted item. " : " deleted items. "))
                        + "Everything you delete (lines, cleared and deleted flows) goes here and can be "
                        + "restored.",
                () -> {
                    holder[0].cancel();
                    new FlowRecycleBinDialog(project).show();
                }));
        panel.add(sectionLabel("HELP"));
        panel.add(actionRow(FlowTheme.ICON_INFO, "Info",
                "How TraceWeave works: adding lines, flows, views, the row action bar, Sync, recording, "
                        + "walk-through, copy formats and shortcuts.",
                () -> {
                    holder[0].cancel();
                    new FlowInfoDialog(project).show();
                }));

        JBPopup popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, null)
                .setRequestFocus(true)
                .setCancelOnClickOutside(true)
                .setMovable(false)
                .setResizable(false)
                .createPopup();
        holder[0] = popup;
        // Keep the switches in step with the store while open: turning one copy format on turns the
        // other off, and moving a line by hand (or the toolbar's Sync button) changes Sync Files.
        Runnable syncSwitches = () -> {
            sync.setOn(store.isSyncFiles());
            optimize.setOn(store.isOptimizeFlatLog());
            markdown.setOn(store.isCopyMarkdown());
            sequence.setOn(store.isSequenceDiagram());
            copyAll.setOn(store.isCopyAllFlows());
            nestedLog.setOn(store.isNestedCallLog());
            ideTheme.setOn(look.isUseIdeTheme());
        };
        store.addListener(syncSwitches);
        Disposer.register(popup, () -> store.removeListener(syncSwitches));
        // The popup itself is drawn in TraceWeave's colors: repaint it when they change.
        Runnable repaint = () -> {
            syncSwitches.run();
            panel.repaint();
        };
        look.addListener(repaint);
        Disposer.register(popup, () -> look.removeListener(repaint));

        // Below the gear button, right edges aligned, so the popup opens towards the panel.
        Dimension size = popup.getContent().getPreferredSize();
        popup.show(new RelativePoint(anchor, new Point(anchor.getWidth() - size.width, anchor.getHeight() + FlowTheme.popupGap())));
    }

    /**
     * One option of a choice popup: a row with an icon, a title and an optional hint, and a check mark when it is
     * the chosen one.
     */
    public record Choice(Icon icon, String title, @Nullable String hint, BooleanSupplier chosen, Runnable pick) {}

    /**
     * A popup in the look of the settings popup, with sections of choices, below the anchor with right edges aligned
     * (so it opens towards the window, like the settings popup). With {@code closeOnPick} a pick closes it (a menu of
     * actions); otherwise it stays open and moves the check mark (settings).
     *
     * @param sections section title -> its choices, in order
     * @param footer   a hint under the choices, or null
     */
    public static void showChoices(JComponent anchor, String title, Map<String, List<Choice>> sections, @Nullable String footer,
                            boolean closeOnPick) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(FlowTheme.CARD_BG);
        panel.setBorder(JBUI.Borders.empty(12, 14, 10, 14));
        JLabel heading = new JLabel(title);
        heading.setFont(JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD, JBUI.Fonts.label().getSize2D() + 1f));
        heading.setForeground(FlowTheme.FG);
        heading.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        panel.add(heading);

        JBPopup[] holder = new JBPopup[1];
        List<Runnable> updates = new java.util.ArrayList<>();
        Runnable updateAll = () -> updates.forEach(Runnable::run);
        for (Map.Entry<String, List<Choice>> section : sections.entrySet()) {
            if (!section.getKey().isEmpty()) panel.add(sectionLabel(section.getKey()));
            for (Choice c : section.getValue()) {
                JLabel check = new JLabel();
                Runnable update = () -> check.setIcon(c.chosen().getAsBoolean()
                        ? FlowTheme.tinted(AllIcons.Actions.Checked, FlowTheme.ACCENT)
                        : com.intellij.util.ui.EmptyIcon.ICON_16);
                update.run();
                updates.add(update);
                panel.add(baseRow(c.icon(), c.title(), c.hint(), check, () -> {
                    if (closeOnPick) holder[0].cancel();
                    c.pick().run();
                    updateAll.run();
                }));
            }
        }
        if (footer != null) {
            JLabel f = new JLabel("<html><body style='width:" + JBUI.scale(230) + "px'>" + footer + "</body></html>");
            f.setFont(JBUI.Fonts.smallFont());
            f.setForeground(FlowTheme.FG_MUTED);
            f.setBorder(JBUI.Borders.empty(8, 6, 0, 0));
            f.setAlignmentX(JComponent.LEFT_ALIGNMENT);
            panel.add(f);
        }
        JBPopup popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, null)
                .setRequestFocus(true)
                .setCancelOnClickOutside(true)
                .setMovable(false)
                .setResizable(false)
                .createPopup();
        holder[0] = popup;
        Dimension size = popup.getContent().getPreferredSize();
        popup.show(new RelativePoint(anchor, new Point(anchor.getWidth() - size.width, anchor.getHeight() + FlowTheme.popupGap())));
    }

    private static JComponent sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(JBUI.Fonts.smallFont().deriveFont(java.awt.Font.BOLD));
        l.setForeground(FlowTheme.FG_MUTED);
        l.setBorder(JBUI.Borders.empty(10, 4, 4, 0));
        l.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return l;
    }

    /** One option: icon, title and wrapped hint on the left, switch on the right. Clicking the row toggles. */
    private static JComponent row(Icon icon, String title, String hint, Switch toggle, Consumer<Boolean> onChange) {
        Runnable flip = () -> {
            toggle.setOn(!toggle.on);
            onChange.accept(toggle.on);
        };
        // Clicks on the switch itself do the same as clicks on the row.
        toggle.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { flip.run(); }
        });
        return baseRow(icon, title, hint, toggle, flip);
    }

    /** A row that runs an action when clicked, with a chevron on the right. */
    private static JComponent actionRow(Icon icon, String title, String hint, Runnable onClick) {
        JLabel chevron = new JLabel(FlowTheme.tinted(AllIcons.General.ChevronRight, FlowTheme.FG_MUTED));
        return baseRow(icon, title, hint, chevron, onClick);
    }

    private static JComponent baseRow(Icon icon, String title, @Nullable String hint, JComponent trailing, Runnable onClick) {
        JLabel iconLabel = new JLabel(FlowTheme.tinted(icon, FlowTheme.FG_MUTED));
        iconLabel.setVerticalAlignment(JLabel.TOP);
        iconLabel.setBorder(JBUI.Borders.empty(2, 0, 0, 10));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD));
        titleLabel.setForeground(FlowTheme.FG);
        JPanel text = new JPanel(new BorderLayout(0, JBUI.scale(3)));
        text.setOpaque(false);
        text.add(titleLabel, BorderLayout.NORTH);
        if (hint != null) {
            JLabel hintLabel = new JLabel("<html><body style='width:" + JBUI.scale(230) + "px'>" + hint + "</body></html>");
            hintLabel.setFont(JBUI.Fonts.smallFont());
            hintLabel.setForeground(FlowTheme.FG_MUTED);
            text.add(hintLabel, BorderLayout.CENTER);
        }

        JPanel switchBox = new JPanel(new BorderLayout());
        switchBox.setOpaque(false);
        switchBox.setBorder(JBUI.Borders.emptyLeft(16));
        switchBox.add(trailing, BorderLayout.NORTH);

        JPanel row = new JPanel(new BorderLayout()) {
            boolean hover;
            {
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                    @Override public void mouseClicked(MouseEvent e) { onClick.run(); }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                if (!hover) return;
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(FlowTheme.BTN_HOVER);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), JBUI.scale(10), JBUI.scale(10));
                } finally {
                    g2.dispose();
                }
            }
        };
        row.setOpaque(false);
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        row.setBorder(BorderFactory.createEmptyBorder(JBUI.scale(8), JBUI.scale(6), JBUI.scale(8), JBUI.scale(8)));
        row.add(iconLabel, BorderLayout.WEST);
        row.add(text, BorderLayout.CENTER);
        row.add(switchBox, BorderLayout.EAST);
        row.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        return row;
    }

    /** Small on/off switch: accent track with the knob on the right when on. */
    private static final class Switch extends JComponent {
        boolean on;

        Switch(boolean on) {
            this.on = on;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        void setOn(boolean on) {
            this.on = on;
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(JBUI.scale(36), JBUI.scale(20));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                g2.setColor(on ? FlowTheme.ACCENT : FlowTheme.BTN_BORDER);
                g2.fillRoundRect(0, 0, w, h, h, h);
                int pad = JBUI.scale(3);
                int knob = h - pad * 2;
                g2.setColor(Color.WHITE);
                g2.fillOval(on ? w - pad - knob : pad, pad, knob, knob);
            } finally {
                g2.dispose();
            }
        }
    }
}
