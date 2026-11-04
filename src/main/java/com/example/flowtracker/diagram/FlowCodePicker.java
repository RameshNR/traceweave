package com.example.flowtracker.diagram;

import com.example.flowtracker.code.FlowCodeSearch;
import com.example.flowtracker.code.FlowLang;
import com.example.flowtracker.compat.FlowCompat;
import com.example.flowtracker.copy.CodeHighlighter;
import com.example.flowtracker.ui.FlowTheme;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The pickers of the Flow Diagram window: a class (searched by name or pattern), a method of that class, and
 * the method's lines to add to the flow.
 */
final class FlowCodePicker {
    private FlowCodePicker() {}

    /**
     * What the class picker returns: a class (or file) of the project, or a custom class (one that isn't in the
     * code): its name and the file it will be in.
     */
    record ClassChoice(@Nullable FlowCodeSearch.ClassHit hit, @Nullable String customName, @Nullable String customFile) {
        boolean isCustom() { return hit == null; }
    }

    /**
     * Asks for a file of the project (listed as the user types: by name, {@code *} / {@code ?} wildcards or a
     * {@code /regex/}), then for one of the classes in it when it has several. A name no file has becomes a custom
     * class named after it (PaymentGateway.java: class PaymentGateway in that file). Null when cancelled.
     *
     * @param title       the window title, e.g. "Add File" or "Rename Class"
     * @param query       the search to start with (e.g. the class's current name)
     * @param customCheck why a class name can't be a custom class (null when it can)
     */
    static @Nullable ClassChoice pickClass(Project project, Component parent, String title, String query,
                                           Function<String, String> customCheck) {
        ClassDialog d = new ClassDialog(project, parent, title, customCheck);
        if (!query.isEmpty()) {
            d.search.setText(query); // starts the search
            d.search.getTextEditor().selectAll();
        }
        if (!d.showAndGet()) return null;
        Object v = d.list.getSelectedValue();
        if (v instanceof Custom c) return new ClassChoice(null, c.className(), c.fileName());
        if (!(v instanceof FlowCodeSearch.FileHit f)) return null;
        List<FlowCodeSearch.ClassHit> classes = FlowCompat.read(() -> FlowCodeSearch.classesIn(project, f.url()));
        if (classes.isEmpty()) return null;
        if (classes.size() == 1) return new ClassChoice(classes.get(0), null, null);
        ClassInFileDialog pick = new ClassInFileDialog(project, parent, f, classes);
        return pick.showAndGet() && pick.list.getSelectedValue() != null ? new ClassChoice(pick.list.getSelectedValue(), null, null) : null;
    }

    /** The list row that takes the typed name as a custom class: its class name and the file it will be in. */
    private record Custom(String className, String fileName) {}

    /** A heading in the file list (before anything is typed: open, recent, in the project): it can't be picked. */
    private record Heading(String text) {}

    /** What the method picker returns: a method of the class's code, or the name typed for a custom method. */
    record MethodChoice(@Nullable FlowCodeSearch.MethodHit hit, @Nullable String customName) {
        boolean isCustom() { return hit == null; }
    }

    /**
     * Asks for a method of the class; a name the class doesn't have can be taken as a custom method. Null when
     * cancelled.
     *
     * @param customCheck why a method name can't be a custom method (null when it can)
     */
    static @Nullable MethodChoice pickMethod(Project project, Component parent, FlowCodeSearch.ClassHit cls,
                                             Function<String, String> customCheck) {
        List<FlowCodeSearch.MethodHit> methods = FlowCompat.read(() -> FlowCodeSearch.methods(project, cls));
        MethodDialog d = new MethodDialog(project, parent, cls, methods, customCheck);
        if (!d.showAndGet()) return null;
        Object v = d.list.getSelectedValue();
        if (v instanceof FlowCodeSearch.MethodHit m) return new MethodChoice(m, null);
        return v instanceof CustomMethod c ? new MethodChoice(null, c.name()) : null;
    }

    /** The list row that takes the typed name as a custom method. */
    private record CustomMethod(String name) {}

    /**
     * Shows the method's code and asks which lines to add. Returns the 0-based lines picked (none picked: the
     * method's first line), or null when cancelled.
     *
     * @param saved 0-based lines of this file the flow (or the diagram) has already
     */
    static @Nullable List<Integer> pickLines(Project project, Component parent, FlowCodeSearch.ClassHit cls,
                                             FlowCodeSearch.MethodHit method, Set<Integer> saved) {
        List<FlowCodeSearch.CodeLine> lines = FlowCompat.read(() -> FlowCodeSearch.lines(cls.url(), method.start(), method.end()));
        LinesDialog d = new LinesDialog(project, parent, cls, method, lines, saved);
        if (!d.showAndGet()) return null;
        List<Integer> picked = new ArrayList<>(d.checked);
        if (picked.isEmpty()) picked.add(method.declLine());
        picked.sort(null);
        return picked;
    }

    // ---------- class ----------

    private static final class ClassDialog extends DialogWrapper {
        private final Project project;
        private final SearchTextField search = new SearchTextField(false);
        private final DefaultListModel<Object> model = new DefaultListModel<>();
        final JBList<Object> list = new JBList<>(model);
        private final JBLabel status = new JBLabel();
        private final Timer debounce = new Timer(250, e -> runSearch());
        private final String okText;
        /** The message for a custom class name, or null when it can't be one. */
        private final Function<String, String> customCheck;
        /** The files found for the current search. */
        private List<FlowCodeSearch.FileHit> hits = List.of();
        private int generation;

        ClassDialog(Project project, Component parent, String title, Function<String, String> customCheck) {
            super(project, parent, false, IdeModalityType.IDE);
            this.project = project;
            this.customCheck = customCheck;
            this.okText = title.startsWith("Add") ? "Add" : "OK";
            search.getTextEditor().getAccessibleContext().setAccessibleName("File name");
            setTitle(title);
            setOKButtonText(okText);
            debounce.setRepeats(false);
            Disposer.register(getDisposable(), debounce::stop);
            init();
            updateOk();
            showSuggestions(); // nothing typed yet: the files at hand
        }

        @Override
        protected JComponent createCenterPanel() {
            search.getTextEditor().getEmptyText().setText("File name, e.g. BookingService.java, index.jsp, *Cruise*.java or /Book.*Service/");
            search.addDocumentListener(new DocumentAdapter() {
                @Override protected void textChanged(@NotNull DocumentEvent e) {
                    fill(); // the custom class row follows the text right away; the search waits for a pause
                    debounce.restart();
                }
            });
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new ColoredListCellRenderer<>() {
                @Override
                protected void customizeCellRenderer(@NotNull JList<?> l, Object value, int index, boolean selected, boolean focus) {
                    if (value instanceof Heading h) {
                        setIcon(null);
                        setBorder(JBUI.Borders.empty(index == 0 ? 2 : 8, 2, 2, 2));
                        append(h.text(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                        return;
                    }
                    setBorder(JBUI.Borders.empty(1, 10, 1, 2));
                    if (value instanceof Custom c) {
                        setIcon(FlowTheme.ICON_CLASS_UNKNOWN);
                        append(c.fileName(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                        append("  not in the project: add custom class " + c.className() + " (you can rename it or change its file later)",
                                SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                        return;
                    }
                    FlowCodeSearch.FileHit f = (FlowCodeSearch.FileHit) value;
                    setIcon(FileTypeManager.getInstance().getFileTypeByFileName(f.fileName()).getIcon());
                    append(f.fileName(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    append("  " + f.path(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    if (!f.projectName().isEmpty()) append("  [" + f.projectName() + "]", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
            });
            list.addListSelectionListener(e -> {
                // a heading isn't a file: the row after it is meant
                if (list.getSelectedValue() instanceof Heading && list.getSelectedIndex() + 1 < model.size()) {
                    list.setSelectedIndex(list.getSelectedIndex() + 1);
                    return;
                }
                updateOk();
            });
            list.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && list.getSelectedValue() != null) doOKAction();
                }
            });
            // Up / Down in the search box move through the results.
            search.getTextEditor().registerKeyboardAction(e -> move(1), KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), JComponent.WHEN_FOCUSED);
            search.getTextEditor().registerKeyboardAction(e -> move(-1), KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), JComponent.WHEN_FOCUSED);

            status.setForeground(UIUtil.getContextHelpForeground());
            status.setText(hint());
            JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
            panel.add(search, BorderLayout.NORTH);
            panel.add(com.example.flowtracker.ui.FlowScroll.wheel(new JBScrollPane(list)), BorderLayout.CENTER);
            panel.add(status, BorderLayout.SOUTH);
            panel.setPreferredSize(new Dimension(JBUI.scale(640), JBUI.scale(420)));
            return panel;
        }

        private static String hint() {
            return "<html>Type at least " + FlowCodeSearch.MIN_LETTERS + " letters of the file name (a class name finds its file too). "
                    + "Use * and ? as wildcards (*Cruise*.java), or /…/ for a regular expression. Next you pick a class of the file "
                    + "when it has several. A file that isn't in the project becomes a custom class named after it.</html>";
        }

        private void move(int delta) {
            if (model.isEmpty()) return;
            int i = list.getSelectedIndex();
            for (int k = 0; k < model.size(); k++) {
                i = Math.max(0, Math.min(model.size() - 1, i + delta));
                if (!(model.get(i) instanceof Heading)) break;
                if (i == 0 || i == model.size() - 1) delta = -delta; // a heading at an end: back the other way
            }
            list.setSelectedIndex(i);
            list.ensureIndexIsVisible(i);
        }

        /** Before anything is typed: the files open in the editor, the ones opened lately, then the project's others. */
        private void showSuggestions() {
            int gen = ++generation;
            hits = List.of();
            status.setText(DumbService.isDumb(project) ? "Waiting for indexing to finish…" : "Loading files…");
            ReadAction.nonBlocking(() -> FlowCodeSearch.suggestions(project, 60))
                    .inSmartMode(project)
                    .expireWith(getDisposable())
                    .finishOnUiThread(ModalityState.stateForComponent(list), sug -> {
                        if (gen != generation || !search.getText().isBlank()) return;
                        model.clear();
                        group("OPEN IN THE EDITOR", sug.open());
                        group("RECENTLY OPENED", sug.recent());
                        group("IN THE PROJECT", sug.other());
                        if (model.size() > 1) list.setSelectedIndex(1);
                        updateOk();
                        status.setText(hint());
                    })
                    .submit(AppExecutorUtil.getAppExecutorService());
        }

        private void group(String title, List<FlowCodeSearch.FileHit> files) {
            if (files.isEmpty()) return;
            model.addElement(new Heading(title));
            files.forEach(model::addElement);
        }

        private void runSearch() {
            String q = search.getText();
            if (q.isBlank()) {
                showSuggestions();
                return;
            }
            int gen = ++generation;
            Pattern p = FlowCodeSearch.pattern(q);
            if (p == null) {
                hits = List.of();
                fill();
                String error = FlowCodeSearch.patternError(q);
                status.setText(error != null ? error : hint());
                return;
            }
            status.setText(DumbService.isDumb(project) ? "Waiting for indexing to finish…" : "Searching…");
            ReadAction.nonBlocking(() -> FlowCodeSearch.findFiles(project, q, 100))
                    .inSmartMode(project)
                    .expireWith(getDisposable())
                    .finishOnUiThread(ModalityState.stateForComponent(list), found -> {
                        if (gen != generation) return; // a newer search is running
                        hits = found;
                        fill();
                        status.setText(found.isEmpty()
                                ? "No file matches “" + q.strip() + "”" + (custom() != null ? ": it can be added as a custom class." : "")
                                : found.size() + (found.size() == 1 ? " match" : found.size() >= 100 ? "+ matches (type more to narrow)" : " matches"));
                    })
                    .submit(AppExecutorUtil.getAppExecutorService());
        }

        /**
         * The typed name as a custom class (named after the file: PaymentGateway.java gives PaymentGateway), or null:
         * when it isn't a valid name, or a file of the results is called that (then that file is the one meant).
         */
        private @Nullable Custom custom() {
            String typed = search.getText().strip();
            if (typed.isEmpty() || typed.startsWith("/") || typed.contains("*") || typed.contains("?")) return null;
            String cls = FlowLang.isSupportedName(typed) ? FlowLang.baseName(typed) : typed;
            if (cls.isEmpty() || customCheck.apply(cls) != null) return null;
            for (FlowCodeSearch.FileHit f : hits) {
                if (f.fileName().equals(typed) || FlowLang.baseName(f.fileName()).equals(cls)) return null;
            }
            return new Custom(cls, typed);
        }

        /** Shows the classes found and, last, the typed name as a custom class; keeps the selection when it can. */
        private void fill() {
            if (search.getText().isBlank()) return; // the suggestions stay until something is typed
            Object was = list.getSelectedValue();
            model.clear();
            hits.forEach(model::addElement);
            Custom c = custom();
            if (c != null) model.addElement(c);
            int keep = was instanceof Custom ? (c == null ? -1 : model.size() - 1) : model.indexOf(was);
            if (keep >= 0) list.setSelectedIndex(keep);
            else if (!model.isEmpty()) list.setSelectedIndex(0);
            updateOk();
        }

        private void updateOk() {
            Object v = list.getSelectedValue();
            setOKActionEnabled(v != null && !(v instanceof Heading));
            setOKButtonText(v instanceof Custom ? okText.equals("Add") ? "Add Custom Class" : "Use Custom Class" : okText);
        }

        @Override
        public @Nullable JComponent getPreferredFocusedComponent() {
            return search.getTextEditor();
        }
    }

    /** The second step of Add Class for a file with several classes: which one. */
    private static final class ClassInFileDialog extends DialogWrapper {
        final JBList<FlowCodeSearch.ClassHit> list;

        ClassInFileDialog(Project project, Component parent, FlowCodeSearch.FileHit file, List<FlowCodeSearch.ClassHit> classes) {
            super(project, parent, false, IdeModalityType.IDE);
            list = new JBList<>(classes);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            int main = 0; // the class named like the file, when there is one
            for (int i = 0; i < classes.size(); i++) {
                if (classes.get(i).className().equals(FlowLang.baseName(file.fileName()))) main = i;
            }
            list.setSelectedIndex(main);
            setTitle("Classes in " + file.fileName());
            setOKButtonText("Add");
            init();
        }

        @Override
        protected JComponent createCenterPanel() {
            list.setCellRenderer(new ColoredListCellRenderer<>() {
                @Override
                protected void customizeCellRenderer(@NotNull JList<? extends FlowCodeSearch.ClassHit> l, FlowCodeSearch.ClassHit h,
                                                     int index, boolean selected, boolean focus) {
                    setIcon(AllIcons.Nodes.Class);
                    append(h.className(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    append("   line " + (h.declLine() + 1), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            });
            list.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && list.getSelectedValue() != null) doOKAction();
                }
            });
            JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
            JBLabel hint = new JBLabel("This file has " + list.getModel().getSize() + " classes. Pick the one to add:");
            panel.add(hint, BorderLayout.NORTH);
            panel.add(com.example.flowtracker.ui.FlowScroll.wheel(new JBScrollPane(list)), BorderLayout.CENTER);
            panel.setPreferredSize(new Dimension(JBUI.scale(420), JBUI.scale(280)));
            return panel;
        }

        @Override
        public @Nullable JComponent getPreferredFocusedComponent() {
            return list;
        }
    }

    // ---------- method ----------

    private static final class MethodDialog extends DialogWrapper {
        private final List<FlowCodeSearch.MethodHit> all;
        private final SearchTextField filter = new SearchTextField(false);
        private final DefaultListModel<Object> model = new DefaultListModel<>();
        final JBList<Object> list = new JBList<>(model);
        private final JBLabel status = new JBLabel();
        /** Why a typed name can't be a custom method, or null when it can. */
        private final Function<String, String> customCheck;

        MethodDialog(Project project, Component parent, FlowCodeSearch.ClassHit cls, List<FlowCodeSearch.MethodHit> methods,
                     Function<String, String> customCheck) {
            super(project, parent, false, IdeModalityType.IDE);
            this.all = methods;
            this.customCheck = customCheck;
            setTitle("Add Function to " + cls.name());
            init();
            refill();
        }

        @Override
        protected JComponent createCenterPanel() {
            filter.getTextEditor().getEmptyText().setText("Method name, e.g. search, get*, *Booking* or /^(get|set)/ (a new name: custom method)");
            filter.addDocumentListener(new DocumentAdapter() {
                @Override protected void textChanged(@NotNull DocumentEvent e) { refill(); }
            });
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new ColoredListCellRenderer<>() {
                @Override
                protected void customizeCellRenderer(@NotNull JList<?> l, Object value, int index, boolean selected, boolean focus) {
                    if (value instanceof CustomMethod c) {
                        setIcon(FlowTheme.ICON_METHOD_UNKNOWN);
                        append(c.name(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                        append("  custom method: not in the code (yet)", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                        return;
                    }
                    FlowCodeSearch.MethodHit m = (FlowCodeSearch.MethodHit) value;
                    setIcon(AllIcons.Nodes.Method);
                    append(m.display(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    append("   lines " + (m.start() + 1) + "–" + (m.end() + 1), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            });
            list.addListSelectionListener(e -> updateOk());
            list.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && list.getSelectedValue() != null) doOKAction();
                }
            });
            // Up / Down in the filter move through the methods.
            filter.getTextEditor().registerKeyboardAction(e -> move(1), KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), JComponent.WHEN_FOCUSED);
            filter.getTextEditor().registerKeyboardAction(e -> move(-1), KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), JComponent.WHEN_FOCUSED);
            status.setForeground(UIUtil.getContextHelpForeground());
            JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
            panel.add(filter, BorderLayout.NORTH);
            panel.add(com.example.flowtracker.ui.FlowScroll.wheel(new JBScrollPane(list)), BorderLayout.CENTER);
            panel.add(status, BorderLayout.SOUTH);
            panel.setPreferredSize(new Dimension(JBUI.scale(520), JBUI.scale(380)));
            return panel;
        }

        /** The methods matching the filter: the same name / wildcard / regex search as Add Class, from the first letter. */
        private void refill() {
            String q = filter.getText();
            Pattern p = FlowCodeSearch.filterPattern(q);
            String error = FlowCodeSearch.patternError(q);
            model.clear();
            for (FlowCodeSearch.MethodHit m : all) {
                if (error == null && (p == null || FlowCodeSearch.matchesMethod(p, m))) model.addElement(m);
            }
            CustomMethod custom = customMethod();
            if (custom != null) model.addElement(custom);
            if (!model.isEmpty()) list.setSelectedIndex(0);
            updateOk();
            if (all.isEmpty()) status.setText("This class has no methods in the code: type a name to add a custom method.");
            else if (error != null) status.setText(error);
            else if (p == null) status.setText("<html>Pick a method; the next step shows its code. Filter by name, "
                    + "* and ? wildcards (get*), or /…/ for a regular expression.</html>");
            else status.setText(model.isEmpty() ? "No method matches “" + q.strip() + "”"
                    : model.size() + " of " + all.size() + (all.size() == 1 ? " method" : " methods"));
        }

        /** The typed name as a custom method, or null: not a valid name, or the class has a method of that name. */
        private @Nullable CustomMethod customMethod() {
            String typed = filter.getText().strip();
            if (typed.isEmpty() || typed.startsWith("/") || typed.contains("*") || typed.contains("?")) return null;
            String name = FlowDiagram.methodName(typed);
            for (FlowCodeSearch.MethodHit m : all) if (m.name().equals(name)) return null;
            return customCheck.apply(typed) == null ? new CustomMethod(name) : null;
        }

        private void updateOk() {
            Object v = list.getSelectedValue();
            setOKActionEnabled(v != null);
            setOKButtonText(v instanceof CustomMethod ? "Add Custom Method" : "Next: Pick Lines…");
        }

        private void move(int delta) {
            if (model.isEmpty()) return;
            int i = Math.max(0, Math.min(model.size() - 1, list.getSelectedIndex() + delta));
            list.setSelectedIndex(i);
            list.ensureIndexIsVisible(i);
        }

        @Override
        public @Nullable JComponent getPreferredFocusedComponent() {
            return filter.getTextEditor();
        }
    }

    // ---------- lines ----------

    private static final class LinesDialog extends DialogWrapper {
        private final List<FlowCodeSearch.CodeLine> lines;
        private final Set<Integer> saved;
        private final FlowLang.Kind kind;
        final Set<Integer> checked = new LinkedHashSet<>();
        private final JBList<FlowCodeSearch.CodeLine> list;
        private final FlowCodeSearch.MethodHit method;

        LinesDialog(Project project, Component parent, FlowCodeSearch.ClassHit cls, FlowCodeSearch.MethodHit method,
                    List<FlowCodeSearch.CodeLine> lines, Set<Integer> saved) {
            super(project, parent, false, IdeModalityType.IDE);
            this.lines = lines;
            this.saved = saved;
            this.method = method;
            this.kind = FlowLang.kind(cls.fileName());
            this.list = new JBList<>(lines);
            setTitle(cls.name() + "." + method.name() + " – Pick Lines");
            setOKButtonText("Add");
            init();
        }

        @Override
        protected JComponent createCenterPanel() {
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new LineRenderer());
            list.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    int i = list.locationToIndex(e.getPoint());
                    if (i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) toggle(lines.get(i));
                }
            });
            list.registerKeyboardAction(e -> {
                FlowCodeSearch.CodeLine l = list.getSelectedValue();
                if (l != null) toggle(l);
            }, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), JComponent.WHEN_FOCUSED);

            JBLabel hint = new JBLabel("<html>Click the lines to add to the flow (Space toggles). They are listed in the Markdown "
                    + "copy, not drawn in the diagram. Add without picking any adds the method's first line, so the method "
                    + "is in the flow.</html>");
            hint.setForeground(UIUtil.getContextHelpForeground());
            JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
            panel.add(hint, BorderLayout.NORTH);
            panel.add(com.example.flowtracker.ui.FlowScroll.wheel(new JBScrollPane(list)), BorderLayout.CENTER);
            panel.setPreferredSize(new Dimension(JBUI.scale(760), JBUI.scale(Math.min(560, 120 + lines.size() * 22))));
            return panel;
        }

        private boolean selectable(FlowCodeSearch.CodeLine l) {
            return !l.text().isBlank() && !saved.contains(l.line());
        }

        private void toggle(FlowCodeSearch.CodeLine l) {
            if (!selectable(l)) return;
            if (!checked.remove(l.line())) checked.add(l.line());
            setOKButtonText(checked.isEmpty() ? "Add" : "Add " + checked.size() + (checked.size() == 1 ? " Line" : " Lines"));
            list.repaint();
        }

        @Override
        public @Nullable JComponent getPreferredFocusedComponent() {
            return list;
        }

        /** A check box, the line number and the code, colored like the panel. */
        private final class LineRenderer extends JPanel implements ListCellRenderer<FlowCodeSearch.CodeLine> {
            private final JCheckBox box = new JCheckBox();
            private final SimpleColoredComponent code = new SimpleColoredComponent();

            LineRenderer() {
                super(new BorderLayout(JBUI.scale(4), 0));
                box.setOpaque(false);
                code.setOpaque(false);
                code.setFont(FlowTheme.codeFont());
                add(box, BorderLayout.WEST);
                add(code, BorderLayout.CENTER);
                setBorder(JBUI.Borders.empty(1, 4));
            }

            @Override
            public Component getListCellRendererComponent(JList<? extends FlowCodeSearch.CodeLine> l, FlowCodeSearch.CodeLine line,
                                                          int index, boolean selected, boolean focus) {
                boolean inFlow = saved.contains(line.line());
                box.setSelected(inFlow || checked.contains(line.line()));
                box.setEnabled(selectable(line));
                box.setVisible(!line.text().isBlank());
                setBackground(selected ? UIUtil.getListSelectionBackground(focus) : UIUtil.getListBackground());
                setOpaque(true);
                code.clear();
                code.append(String.format("%4d  ", line.line() + 1), new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, FlowTheme.LINE_NO));
                String text = line.text().replace("\t", "    ");
                for (CodeHighlighter.Span s : CodeHighlighter.highlight(text, kind)) {
                    java.awt.Color c = switch (s.token()) {
                        case KEYWORD -> FlowTheme.KEYWORD;
                        case TYPE -> FlowTheme.TYPE;
                        case STRING -> FlowTheme.STRING;
                        case NUMBER -> FlowTheme.NUMBER;
                        case COMMENT -> FlowTheme.COMMENT;
                        case PLAIN -> FlowTheme.CODE_FG;
                    };
                    int style = s.token() == CodeHighlighter.Token.KEYWORD ? SimpleTextAttributes.STYLE_BOLD : SimpleTextAttributes.STYLE_PLAIN;
                    code.append(s.text(), new SimpleTextAttributes(style, c));
                }
                if (inFlow) code.append("   in the flow", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                if (line.line() == method.declLine() && !inFlow && checked.isEmpty()) {
                    code.append("   ← added if no line is picked", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                }
                return this;
            }
        }
    }
}
