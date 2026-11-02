package com.example.flowtracker.diagram;

import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowStore;
import com.example.flowtracker.ui.FlowWindow;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Flow Diagram window of a project (one per project), with a tab for each flow open in it. A tab is a flow of
 * the Flow tab: its name, its color (a dot on the tab), its diagram. Tabs can be opened (Open Flow), renamed,
 * colored, combined into a new flow, and closed (after asking; its classes can be moved to another tab first).
 * Classes can be copied, cut and pasted between tabs. Switching tabs makes that flow the active one, so Apply works
 * on it.
 */
final class FlowDiagramHost {
    private static final Map<Project, FlowDiagramHost> HOSTS = new ConcurrentHashMap<>();
    /** Colors new tabs take in turn (one not used by another open tab). */
    private static final Color[] TAB_COLORS = {new Color(0x3574F0), new Color(0xE5A83B), new Color(0x2E9D57), new Color(0xD64550),
            new Color(0x8E5BD9), new Color(0x1A9CB0), new Color(0xC2611F), new Color(0x5C6BC0), new Color(0xA6559C)};

    private final Project project;
    private final FlowStore store;
    private final FlowWindow window;
    private final List<FlowDiagramDialog> pages = new ArrayList<>();
    private int selectedIndex = -1;
    /** Flows made in this window (New Flow, moved / copied to a new tab, combined): not kept when closed unsaved. */
    private final java.util.Set<String> madeHere = new java.util.HashSet<>();

    void madeHere(String flow) {
        madeHere.add(flow);
    }

    /** A flow made here and never applied (it has no lines yet) goes away with its tab. */
    private void dropIfUnused(String flow) {
        if (madeHere.contains(flow) && store.getEntriesOf(flow).isEmpty()) {
            store.dropEmptyFlow(flow);
            madeHere.remove(flow);
        }
    }
    /** The selected tab's toolbar, above the tabs. */
    private final JPanel toolbarHolder = new JPanel(new BorderLayout());
    /** The tabs (like a browser's), then "+" to open another flow. */
    private final JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)) {
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            // the line under the tabs, except under the selected one (it is joined to its page)
            g.setColor(TAB_LINE);
            int y = getHeight() - 1;
            int at = selectedIndex < 0 ? -1 : selectedIndex * 2; // tabs and the bars between them
            Component sel = at >= 0 && at < getComponentCount() ? getComponent(at) : null;
            if (sel == null) {
                g.drawLine(0, y, getWidth(), y);
            } else {
                g.drawLine(0, y, sel.getX(), y);
                g.drawLine(sel.getX() + sel.getWidth() - 1, y, getWidth(), y);
            }
        }
    };
    private final JPanel body = new JPanel(new BorderLayout());
    private final JButton plus = new JButton(AllIcons.General.Add);
    private static final com.intellij.ui.JBColor TAB_LINE = com.intellij.ui.JBColor.namedColor("Borders.color", new com.intellij.ui.JBColor(0xD3D5DB, 0x43454A));

    /** The project's open Flow Diagram window, or null. */
    static @Nullable FlowDiagramHost of(Project project) {
        FlowDiagramHost h = HOSTS.get(project);
        return h != null && !h.window.isClosed() ? h : null;
    }

    /** Opens the window with its first tab. */
    static FlowDiagramHost open(Project project, FlowDiagramDialog first) {
        FlowDiagramHost h = new FlowDiagramHost(project);
        HOSTS.put(project, h);
        h.add(first);
        h.window.show();
        first.shown();
        return h;
    }

    private FlowDiagramHost(Project project) {
        this.project = project;
        this.store = FlowStore.get(project);
        // the toolbar of the selected tab, then the tabs (with "+" after them), then the tab's page, joined to it
        plus.setToolTipText("Open a saved flow in a tab, or a new flow");
        plus.setBorder(JBUI.Borders.empty());
        java.awt.Dimension plusSize = JBUI.size(28, 28);
        plus.setPreferredSize(plusSize);
        plus.setMinimumSize(plusSize);
        plus.setMaximumSize(plusSize);
        plus.setContentAreaFilled(false);
        plus.setFocusable(false);
        plus.addActionListener(e -> openFlowMenu(plus));
        strip.setOpaque(true);
        strip.setBackground(com.intellij.ui.JBColor.namedColor("EditorTabs.background", com.intellij.util.ui.UIUtil.getPanelBackground()));
        strip.setBorder(JBUI.Borders.empty(4, 10, 0, 10));
        toolbarHolder.setBorder(JBUI.Borders.empty(8, 12, 6, 12));
        JPanel top = new JPanel(new BorderLayout());
        top.add(toolbarHolder, BorderLayout.NORTH);
        top.add(strip, BorderLayout.SOUTH);
        JPanel content = new JPanel(new BorderLayout());
        content.add(top, BorderLayout.NORTH);
        content.add(body, BorderLayout.CENTER);
        strip.setToolTipText("One tab per flow: right-click a tab to rename, color, combine or close it. Ctrl+click classes "
                + "to select several, then Ctrl+C / Ctrl+X / Ctrl+V between tabs.");
        window = new FlowWindow(project, "TraceWeave.FlowDiagram", "Flow Diagram", content, this::mayCloseAll);
        window.whenClosed(() -> {
            HOSTS.remove(project, this);
            for (FlowDiagramDialog p : new ArrayList<>(pages)) p.disposePage();
            for (FlowDiagramDialog p : new ArrayList<>(pages)) dropIfUnused(p.flowName());
            pages.clear();
            store.dropEmptyFlows(); // no empty flows are left behind (also ones from earlier)
        });
    }

    // ---------- tabs ----------

    @Nullable FlowDiagramDialog selected() {
        return selectedIndex < 0 || selectedIndex >= pages.size() ? null : pages.get(selectedIndex);
    }

    /** Shows a tab: its toolbar on top, its page under the tabs; its flow becomes the active one. */
    private void show(int i) {
        if (i < 0 || i >= pages.size()) return;
        selectedIndex = i;
        FlowDiagramDialog p = pages.get(i);
        toolbarHolder.removeAll();
        if (p.toolbar() != null) toolbarHolder.add(p.toolbar(), BorderLayout.CENTER);
        body.removeAll();
        body.add(p.root(), BorderLayout.CENTER);
        rebuildStrip();
        toolbarHolder.revalidate();
        body.revalidate();
        body.repaint();
        activate(p); // Apply and the code view work on the selected tab's flow
        p.shown();
    }

    /** The | between two tabs (none next to the selected tab, which is joined to its page). */
    private JComponent separator(int before) {
        boolean hidden = before == selectedIndex || before - 1 == selectedIndex;
        JComponent bar = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                if (hidden) return;
                g.setColor(TAB_LINE);
                int h = getHeight(), x = getWidth() / 2;
                g.drawLine(x, h / 4, x, h * 3 / 4);
            }
        };
        bar.setPreferredSize(new java.awt.Dimension(JBUI.scale(5), JBUI.scale(26)));
        return bar;
    }

    private void rebuildStrip() {
        strip.removeAll();
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) strip.add(separator(i));
            strip.add(header(pages.get(i)));
        }
        strip.add(separator(pages.size()));
        strip.add(plus);
        strip.revalidate();
        strip.repaint();
    }

    /** The open tab of a flow, or null. */
    @Nullable FlowDiagramDialog pageOf(String flow) {
        for (FlowDiagramDialog p : pages) if (p.flowName().equals(flow)) return p;
        return null;
    }

    List<FlowDiagramDialog> pages() {
        return List.copyOf(pages);
    }

    /** Adds a tab for a page (a flow) and shows it. */
    void add(FlowDiagramDialog page) {
        page.attach(this);
        if (store.getFlowColor(page.flowName()).isEmpty()) store.setFlowColor(page.flowName(), hex(freeColor()));
        pages.add(page);
        show(pages.size() - 1);
    }

    /** Puts a new page (the same flow, reloaded after Apply or Cancel) in place of the old one. */
    void replace(FlowDiagramDialog old, FlowDiagramDialog next) {
        int i = pages.indexOf(old);
        if (i < 0) {
            add(next);
            return;
        }
        next.attach(this);
        pages.set(i, next);
        show(i);
        old.disposePage();
    }

    /** The selected tab's flow becomes the active flow (what the page reads and Apply writes). */
    void activate(FlowDiagramDialog page) {
        int i = store.indexOfFlow(page.flowName());
        if (i >= 0 && i != store.getActiveFlowIndex()) store.switchFlow(i);
    }

    void select(FlowDiagramDialog page) {
        int i = pages.indexOf(page);
        if (i >= 0 && i != selectedIndex) show(i);
    }

    /** A tab's title: its color dot, the flow's name and a close button. */
    private JComponent header(FlowDiagramDialog page) {
        boolean on = pages.indexOf(page) == selectedIndex;
        JPanel h = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), ht = getHeight(), r = JBUI.scale(8);
                java.awt.geom.Path2D shape = new java.awt.geom.Path2D.Double();
                shape.moveTo(0, ht);
                shape.lineTo(0, r);
                shape.quadTo(0, 0, r, 0);
                shape.lineTo(w - 1 - r, 0);
                shape.quadTo(w - 1, 0, w - 1, r);
                shape.lineTo(w - 1, ht);
                if (on) {
                    g2.setColor(com.intellij.util.ui.UIUtil.getPanelBackground());
                    g2.fill(shape);
                    g2.setColor(TAB_LINE);
                    g2.draw(shape);
                    g2.setColor(colorOf(page.flowName())); // the tab's color along its top
                    g2.fillRoundRect(r / 2, 0, w - r, JBUI.scale(3), 3, 3);
                } else if (getMousePosition() != null) {
                    g2.setColor(com.intellij.ui.JBColor.namedColor("EditorTabs.hoverBackground", new com.intellij.ui.JBColor(0xE8E9EB, 0x393B40)));
                    g2.fill(shape);
                }
                g2.dispose();
            }
        };
        h.setLayout(new javax.swing.BoxLayout(h, javax.swing.BoxLayout.X_AXIS));
        h.setOpaque(false);
        h.setBorder(JBUI.Borders.empty(8, 10, 4, 4));
        JComponent dot = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(colorOf(page.flowName()));
                int d = JBUI.scale(9);
                g2.fillOval(0, (getHeight() - d) / 2, d, d);
                g2.dispose();
            }
        };
        dot.setPreferredSize(JBUI.size(9, 14));
        JBLabel name = new JBLabel(page.flowName());
        if (on) name.setFont(JBUI.Fonts.label().asBold());
        else name.setForeground(com.intellij.util.ui.UIUtil.getContextHelpForeground());
        JButton close = new JButton(com.intellij.util.IconUtil.scale(AllIcons.Actions.Close, null, 0.8f));
        close.setRolloverIcon(com.intellij.util.IconUtil.scale(AllIcons.Actions.CloseHovered, null, 0.8f));
        java.awt.Dimension small = JBUI.size(15, 15);
        close.setPreferredSize(small);
        close.setMinimumSize(small);
        close.setMaximumSize(small);
        close.setBorder(BorderFactory.createEmptyBorder());
        close.setContentAreaFilled(false);
        close.setFocusable(false);
        close.setToolTipText("Close this tab (asks first)");
        close.addActionListener(e -> closePage(page));
        h.add(dot);
        h.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(6)));
        h.add(name);
        h.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8)));
        h.add(close);
        MouseAdapter clicks = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) tabMenu(page, e);
                else select(page);
            }

            @Override public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) tabMenu(page, e);
            }

            @Override public void mouseEntered(MouseEvent e) { h.repaint(); }
            @Override public void mouseExited(MouseEvent e) { h.repaint(); }
        };
        h.addMouseListener(clicks);
        name.addMouseListener(clicks);
        return h;
    }

    private Color colorOf(String flow) {
        String c = store.getFlowColor(flow);
        try {
            return c.isEmpty() ? TAB_COLORS[0] : Color.decode(c);
        } catch (NumberFormatException e) {
            return TAB_COLORS[0];
        }
    }

    /** A color no open tab has (the first one when all are taken). */
    private Color freeColor() {
        List<String> used = new ArrayList<>();
        for (FlowDiagramDialog p : pages) used.add(store.getFlowColor(p.flowName()).toUpperCase());
        for (Color c : TAB_COLORS) if (!used.contains(hex(c).toUpperCase())) return c;
        return TAB_COLORS[pages.size() % TAB_COLORS.length];
    }

    private static String hex(Color c) {
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }

    private void refreshHeaders() {
        rebuildStrip();
    }

    // ---------- the tab menu ----------

    private void tabMenu(FlowDiagramDialog page, MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        item(menu, "Rename Flow…", () -> renameTab(page));
        item(menu, "Tab Color…", () -> chooseColor(page));
        JMenu combine = new JMenu("Connect With (New Tab)");
        for (FlowDiagramDialog other : pages) {
            if (other == page) continue;
            JMenuItem it = new JMenuItem(other.flowName());
            it.addActionListener(ev -> combine(page, other));
            combine.add(it);
        }
        combine.setEnabled(combine.getItemCount() > 0);
        combine.setToolTipText("A new tab with both flows: the calls between them are drawn; the second one is placed under the first");
        menu.add(combine);
        menu.addSeparator();
        item(menu, "Close Tab", () -> closePage(page));
        item(menu, "Delete Flow…", () -> deleteFlow(page));
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private static void item(JPopupMenu menu, String text, Runnable run) {
        JMenuItem it = new JMenuItem(text);
        it.setBorder(JBUI.Borders.empty(5, 10));
        it.addActionListener(e -> run.run());
        menu.add(it);
    }

    /** Deletes the tab's flow (to the Recycle Bin, after asking) and closes its tab. */
    private void deleteFlow(FlowDiagramDialog page) {
        String flow = page.flowName();
        int lines = store.getEntriesOf(flow).size();
        if (Messages.showYesNoDialog(project, "Delete the flow “" + flow + "” (" + lines + (lines == 1 ? " line" : " lines")
                + ")? It goes to the Recycle Bin, so it can be restored.", "Delete Flow", "Delete", "Cancel",
                Messages.getWarningIcon()) != Messages.YES) {
            return;
        }
        remove(page);
        int i = store.indexOfFlow(flow);
        if (i >= 0) store.deleteFlowAt(i);
        madeHere.remove(flow);
    }

    private void renameTab(FlowDiagramDialog page) {
        String now = Messages.showInputDialog(project, "New name of the flow “" + page.flowName() + "” (also in the Flow tab):",
                "Rename Flow", null, page.flowName(), null);
        int i = store.indexOfFlow(page.flowName());
        if (now == null || now.isBlank() || i < 0) return;
        store.renameFlowAt(i, now);
        page.renamed(store.getFlowNames().get(i));
        refreshHeaders();
    }

    private void chooseColor(FlowDiagramDialog page) {
        Color c = javax.swing.JColorChooser.showDialog(strip, "Tab Color of “" + page.flowName() + "”", colorOf(page.flowName()));
        if (c == null) return;
        store.setFlowColor(page.flowName(), hex(c));
        refreshHeaders();
    }

    /**
     * A tab with both tabs' classes as they are now (also changes not applied yet): the first one's on top, the second
     * one's under them, and the calls between them (through their link boxes) drawn between the real classes. It is
     * a new flow (Apply saves it); made again whenever asked.
     */
    void combine(FlowDiagramDialog a, FlowDiagramDialog b) {
        String name = store.uniqueName(a.flowName() + " + " + b.flowName());
        store.newFlow(name, List.of());
        String made = store.getActiveFlowName();
        madeHere(made);
        store.setFlowColor(made, hex(freeColor()));
        FlowDiagramDialog.openFlow(project, made);
        FlowDiagramDialog both = pageOf(made);
        if (both == null) return;
        both.combineFrom(a, b);
        select(both);
    }

    /** "+": a new, empty flow in a tab of its own (named Flow 2, Flow 3…). */
    private void newTab() {
        store.newFlow(store.uniqueName("Flow"), List.of());
        madeHere(store.getActiveFlowName());
        FlowDiagramDialog.openFlow(project, store.getActiveFlowName());
    }

    private void openFlowMenu(JComponent anchor) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem fresh = new JMenuItem("New Flow", AllIcons.General.Add);
        fresh.setBorder(JBUI.Borders.empty(5, 10));
        fresh.addActionListener(e -> newTab());
        menu.add(fresh);
        List<String> closed = new ArrayList<>();
        for (String f : store.getFlowNames()) if (pageOf(f) == null) closed.add(f);
        if (!closed.isEmpty()) {
            menu.addSeparator();
            JMenuItem head = new JMenuItem("Open a saved flow:");
            head.setEnabled(false);
            head.setBorder(JBUI.Borders.empty(3, 10));
            menu.add(head);
        }
        for (String f : closed) {
            JMenuItem it = new JMenuItem(f, new DotIcon(colorOf(f)));
            it.setBorder(JBUI.Borders.empty(5, 10));
            it.addActionListener(e -> FlowDiagramDialog.openFlow(project, f));
            menu.add(it);
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    /** A small round dot in a flow's color (in the menus). */
    private static final class DotIcon implements javax.swing.Icon {
        private final Color color;
        DotIcon(Color color) { this.color = color; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            int d = JBUI.scale(9);
            g2.fillOval(x + (getIconWidth() - d) / 2, y + (getIconHeight() - d) / 2, d, d);
            g2.dispose();
        }
        @Override public int getIconWidth() { return JBUI.scale(16); }
        @Override public int getIconHeight() { return JBUI.scale(16); }
    }

    // ---------- closing ----------

    /**
     * Closes a tab after asking: its flow stays (with its lines); changes not applied can be applied, dropped, or its
     * classes moved to another tab first. The window closes with its last tab.
     */
    void closePage(FlowDiagramDialog page) {
        if (!page.hasClasses()) { // nothing in it: nothing to ask about (a new flow left empty isn't kept)
            remove(page);
            dropIfUnused(page.flowName());
            return;
        }
        boolean changes = page.hasChanges();
        if (!changes) { // everything is saved: nothing to ask
            remove(page);
            dropIfUnused(page.flowName());
            return;
        }
        List<String> options = new ArrayList<>();
        boolean others = pages.size() > 1;
        if (changes) options.add("Save and Close");
        options.add(changes ? "Don't Save" : "Close Tab");
        if (others && page.hasClasses()) options.add("Move to Another Tab…");
        options.add("Cancel");
        boolean fresh = madeHere.contains(page.flowName()) && store.getEntriesOf(page.flowName()).isEmpty();
        String msg = (changes ? "“" + page.flowName() + "” has changes that aren't saved (applied)."
                : "Close the tab of “" + page.flowName() + "”?")
                + (fresh ? " It is a new flow: if you don't save it, it isn't kept." : "")
                + " Saved lines stay in the flow (and in the Flow tab)."
                + (others && page.hasClasses() ? " Its classes can also be moved to another tab." : "");
        int choice = Messages.showDialog(page.root(), msg, "Close Tab", options.toArray(new String[0]), options.size() - 1,
                Messages.getQuestionIcon());
        if (choice < 0 || choice == options.size() - 1) return;
        String picked = options.get(choice);
        if (picked.equals("Save and Close")) {
            if (!page.applyNow()) return;
        } else if (picked.equals("Move to Another Tab…")) {
            FlowDiagramDialog target = pickOther(page, "Move the classes of “" + page.flowName() + "” to:");
            if (target == null) return;
            target.paste(page.copyAll(), "Moved from " + page.flowName());
            select(target);
        }
        remove(page);
        dropIfUnused(page.flowName());
    }

    @Nullable FlowDiagramDialog pickOther(FlowDiagramDialog page, String question) {
        List<FlowDiagramDialog> others = new ArrayList<>();
        for (FlowDiagramDialog p : pages) if (p != page) others.add(p);
        if (others.isEmpty()) return null;
        String[] names = others.stream().map(FlowDiagramDialog::flowName).toArray(String[]::new);
        String[] buttons = java.util.Arrays.copyOf(names, names.length + 1);
        buttons[names.length] = "Cancel";
        int i = Messages.showDialog(project, question, "Choose a Tab", buttons, 0, Messages.getQuestionIcon());
        return i < 0 || i >= others.size() ? null : others.get(i);
    }

    /** Takes a tab away without asking (it was asked, or nothing is lost). */
    void remove(FlowDiagramDialog page) {
        int i = pages.indexOf(page);
        if (i < 0) return;
        pages.remove(i);
        page.disposePage();
        if (pages.isEmpty()) {
            window.close();
            return;
        }
        selectedIndex = -1;
        show(Math.min(i, pages.size() - 1));
    }

    /** The window's ✕: every tab with changes not applied asks first. */
    private boolean mayCloseAll() {
        for (FlowDiagramDialog p : new ArrayList<>(pages)) {
            if (!p.hasChanges()) continue;
            select(p);
            if (!p.mayClose()) return false;
        }
        return true;
    }

    void bringBack() {
        window.bringBack();
    }

    /** Closes the whole window (all tabs). */
    void close() {
        window.close();
    }

    /** Close All: every tab with changes not saved asks first (as the window's ✕ does). */
    void closeAll() {
        if (mayCloseAll()) window.close();
    }

    /** Don't Save and Close All: the changes of every tab are dropped. */
    void closeAllWithoutSaving() {
        for (FlowDiagramDialog p : new ArrayList<>(pages)) p.dropWithoutAsking();
        window.close();
    }

    /** Save and Close All: every tab is saved to its flow, then the window closes (not when one can't be saved). */
    void saveAndCloseAll() {
        for (FlowDiagramDialog p : new ArrayList<>(pages)) {
            if (!p.hasChanges()) continue;
            select(p);
            if (!p.saveBeforeClosing()) return; // it says why; the window stays open
        }
        window.close();
    }

    /** Save All: every tab with changes is saved to its flow; the tabs stay open. */
    void saveAll() {
        FlowDiagramDialog was = selected();
        int saved = 0;
        for (FlowDiagramDialog p : new ArrayList<>(pages)) {
            if (!p.hasChanges()) continue;
            select(p);
            if (!p.saveForAll()) return; // it says why
            saved++;
        }
        if (was != null && pages.contains(was)) select(was);
        else if (selected() != null) activate(selected());
        com.example.flowtracker.copy.FlowCopy.notify(project, saved == 0 ? "Every tab is saved already."
                : "Saved " + saved + (saved == 1 ? " tab" : " tabs") + " to their flows.",
                com.intellij.notification.NotificationType.INFORMATION);
    }

    Project project() {
        return project;
    }

    /** Selected classes copied (or cut) in a tab, to paste in another. Shared by the tabs. */
    static @Nullable FlowDiagramDialog.Clip clipboard;
}
