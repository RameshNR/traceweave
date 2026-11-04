package com.example.flowtracker.ui;

import com.example.flowtracker.model.FlowHuntStore;
import com.example.flowtracker.model.FlowValueSearch;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassOwner;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiManager;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TextFieldWithAutoCompletion;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Watch a Field or Object: the field (typed with the project's classes and fields suggested, or picked in the list of
 * the class's fields) or the whole object, and when the debugger stops. Only a class or field the project has can be
 * added.
 */
final class FlowWatchDialog extends DialogWrapper {
    /** A row of the fields list: a field of the class, or (field null) every field of it. */
    private record FieldRow(@Nullable String field, String type, String declaredIn) {}

    private final Project project;
    private final TextFieldWithAutoCompletion<String> target;
    private final DefaultListModel<FieldRow> fieldModel = new DefaultListModel<>();
    private final JBList<FieldRow> fields = new JBList<>(fieldModel);
    private final JBLabel fieldsTitle = new JBLabel();
    private final JBRadioButton set, change, toNull, toValue;
    private final JBTextField value = new JBTextField();
    private final JBCheckBox keep;
    private final JBTextField name = new JBTextField();
    /** Also the object's data: the fields of the object (the field's value, or the class) and of the objects in it. */
    private final JBCheckBox deep = new JBCheckBox();
    /** The class whose fields the list shows (its short name, as typed). */
    private @Nullable String listed;
    private boolean picking;

    FlowWatchDialog(Project project, @Nullable FlowHuntStore.Hunt existing, String initial) {
        this(project, existing, initial, existing != null && existing.isDeep());
    }

    /** @param deepAtFirst the object's data is ticked at first (e.g. for a local variable: its object) */
    FlowWatchDialog(Project project, @Nullable FlowHuntStore.Hunt existing, String initial, boolean deepAtFirst) {
        super(project, true);
        this.project = project;
        setTitle(existing == null ? "Watch a Field or Object" : "Edit " + existing.name);
        setOKButtonText(existing == null ? "Add and Start" : "Save");
        String next = FlowHuntStore.get(project).nextName(true);
        name.setText(existing != null ? existing.name : next);
        name.getEmptyText().setText(next);
        target = TextFieldWithAutoCompletion.create(project, List.of(), true, initial);
        // the suggestions: every class and field of the project, read in the background
        com.intellij.openapi.application.ReadAction.nonBlocking(() -> targets(project))
                .inSmartMode(project)
                .expireWith(getDisposable())
                .finishOnUiThread(com.intellij.openapi.application.ModalityState.any(), target::setVariants)
                .submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService());
        target.setPlaceholder("Type a class, Class.field or Class.method().variable, e.g. BookingDto.status");
        target.addDocumentListener(new com.intellij.openapi.editor.event.DocumentListener() {
            @Override public void documentChanged(@NotNull com.intellij.openapi.editor.event.DocumentEvent e) {
                if (!picking) showFields();
            }
        });

        String when = existing != null ? existing.when : "CHANGE";
        set = new JBRadioButton("Whenever it is set", "SET".equals(when));
        change = new JBRadioButton("Only when its value changes", "CHANGE".equals(when) || when.isEmpty());
        toNull = new JBRadioButton("When it is set to null", "NULL".equals(when));
        toValue = new JBRadioButton("When it is set to a value containing:", "VALUE".equals(when));
        ButtonGroup g = new ButtonGroup();
        for (JBRadioButton r : new JBRadioButton[]{set, change, toNull, toValue}) {
            g.add(r);
            r.addActionListener(e -> value.setEnabled(toValue.isSelected()));
        }
        if (existing != null && "VALUE".equals(existing.when)) value.setText(String.join(", ", existing.values));
        value.getEmptyText().setText("e.g. CANCELLED (several: comma-separated; a /regex/ works too)");
        value.setEnabled(toValue.isSelected());
        keep = new JBCheckBox("Keep watching after a stop: every stop is listed (resume the debugger to go on)",
                existing == null || existing.keepGoing);

        fields.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fields.setVisibleRowCount(7);
        fields.setCellRenderer(new FieldRenderer());
        fields.getEmptyText().setText("Type a class above to see its fields");
        fields.addListSelectionListener(e -> {
            FieldRow row = fields.getSelectedValue();
            if (e.getValueIsAdjusting() || row == null || listed == null) return;
            picking = true;
            try {
                target.setText(row.field() == null ? listed : listed + "." + row.field());
            } finally {
                picking = false;
            }
            showFields();
        });
        deep.setSelected(deepAtFirst);
        init();
        showFields();
    }

    /**
     * What the typed text names, worked out in the background: the class and field to watch ("!why" when there is
     * none), the class whose fields the list shows and its fields, and the "object's data" choice for it.
     */
    private record Lookup(String text, String resolved, @Nullable String listedAs, @Nullable String className,
                          List<FieldRow> rows, String deepText, boolean deepEnabled, @Nullable String title) {
        Lookup(String text, String resolved, @Nullable String listedAs, @Nullable String className,
               List<FieldRow> rows, String deepText, boolean deepEnabled) {
            this(text, resolved, listedAs, className, rows, deepText, deepEnabled, null);
        }
    }

    private @Nullable Lookup last;
    /** "Class.method()" with or without ".variable" after it. */
    private static final java.util.regex.Pattern METHOD_OR_VARIABLE = java.util.regex.Pattern.compile("([^()]+)\\.([\\w$]+)\\(\\)(?:\\.([\\w$]*))?");
    /** "Class.method().variable." and more: a path into the variable's object (the list shows the fields there). */
    private static final java.util.regex.Pattern METHOD_PATH = java.util.regex.Pattern.compile("([^()]+)\\.([\\w$]+)\\(\\)\\.([\\w$]+)(\\..*)");
    private int generation;

    private Lookup lookUp(String typed) {
        String t = typed.strip();
        String resolved = FlowHuntPanel.resolveTarget(project, t);
        // a path into a method's variable: the list shows the fields of the object at the end of it
        java.util.regex.Matcher mp = METHOD_PATH.matcher(t);
        if (mp.matches()) {
            List<String> pieces = FlowHuntPanel.pieces(mp.group(4));
            List<String> parent = pieces.subList(0, pieces.size() - 1);
            PsiClass[] at = new PsiClass[1];
            String walked = FlowHuntPanel.walk(FlowHuntPanel.variableType(project, mp.group(1), mp.group(2), mp.group(3)), parent, at);
            List<FieldRow> rows = new ArrayList<>();
            if (!walked.startsWith("!") && at[0] != null) {
                for (PsiField f : at[0].getAllFields()) {
                    if (f.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC)) continue;
                    PsiClass owner = f.getContainingClass();
                    rows.add(new FieldRow(f.getName(), f.getType().getPresentableText(), owner == null || owner == at[0] ? "" : owner.getName()));
                }
            }
            String listedAs = mp.group(1) + "." + mp.group(2) + "()." + mp.group(3) + (parent.isEmpty() ? "" : "." + String.join(".", parent));
            String shown = mp.group(3) + (parent.isEmpty() ? "" : "." + String.join(".", parent));
            return new Lookup(typed, resolved, rows.isEmpty() ? null : listedAs, rows.isEmpty() ? null : shown, rows,
                    "Also stop when the data of the object there changes (its fields, a little deep)", resolved.startsWith("~"),
                    rows.isEmpty() ? null : "Fields of " + shown + " (" + at[0].getName() + ", " + rows.size()
                            + "): pick one (a getter or setter works too, e.g. .getEmail())");
        }
        // a method ("Class.method()") or one of its variables: the list shows its variables and its return value
        java.util.regex.Matcher mv = METHOD_OR_VARIABLE.matcher(t);
        if (mv.matches()) {
            List<FieldRow> rows = new ArrayList<>();
            for (String[] v : FlowHuntPanel.methodVariables(project, mv.group(1), mv.group(2))) rows.add(new FieldRow(v[0], v[1], "#" + v[2]));
            String method = mv.group(1) + "." + mv.group(2) + "()";
            boolean ret = "return".equals(mv.group(3));
            return new Lookup(typed, resolved, rows.isEmpty() ? null : method, rows.isEmpty() ? null : mv.group(2) + "()", rows,
                    ret ? "Also stop when the data of the object it returns changes from one return to the next"
                            : "Also stop when the data of the object it holds changes (its fields, a little deep)", resolved.startsWith("~"));
        }
        // the class the list shows: the one typed, or the one of the field typed
        String cls = t;
        PsiClass k = FlowHuntPanel.findClass(project, t);
        if (k == null && t.lastIndexOf('.') > 0) {
            cls = t.substring(0, t.lastIndexOf('.'));
            k = FlowHuntPanel.findClass(project, cls);
        }
        List<FieldRow> rows = new ArrayList<>();
        if (k != null) {
            rows.add(new FieldRow(null, "", ""));
            for (PsiField f : k.getAllFields()) {
                PsiClass owner = f.getContainingClass();
                rows.add(new FieldRow(f.getName(), f.getType().getPresentableText(), owner == null || owner == k ? "" : owner.getName()));
            }
        }
        // the object's data: a field holding an object of the project's classes, or a class with objects inside it
        String deepText = "Also stop when the object's data changes";
        boolean deepEnabled = false;
        if (!resolved.startsWith("!")) {
            int hash = resolved.indexOf('#');
            String jvm = hash < 0 ? resolved : resolved.substring(0, hash);
            String field = hash < 0 ? null : resolved.substring(hash + 1);
            List<String> classes = com.example.flowtracker.debug.FlowValueHunt.objectClasses(project, jvm, field);
            String first = classes.isEmpty() ? "" : classes.get(0).substring(Math.max(classes.get(0).lastIndexOf('.'), classes.get(0).lastIndexOf('$')) + 1);
            int more = Math.max(0, classes.size() - 1);
            if (field == null) {
                deepText = "Also stop when the objects inside it change (" + (more == 0 ? "it has none of the project's classes"
                        : more + (more == 1 ? " class" : " classes") + " deeper") + ")";
                deepEnabled = more > 0;
            } else if (classes.isEmpty()) {
                deepText = "Also stop when the object's data changes (this field holds no object of the project's classes)";
            } else {
                deepText = "Also stop when the data of the " + first + " it holds changes (its fields"
                        + (more == 0 ? "" : ", and " + more + (more == 1 ? " class" : " classes") + " inside it") + ")";
                deepEnabled = true;
            }
        }
        return new Lookup(typed, resolved, k == null ? null : cls, k == null ? null : k.getName(), rows, deepText, deepEnabled);
    }

    /** Looks the typed text up in the background, then shows its fields and the object's data choice. */
    private void showFields() {
        String typed = target.getText();
        int gen = ++generation;
        com.intellij.openapi.application.ReadAction.nonBlocking(() -> lookUp(typed))
                .inSmartMode(project)
                .expireWith(getDisposable())
                .finishOnUiThread(com.intellij.openapi.application.ModalityState.any(), found -> {
                    if (gen != generation) return; // typed on meanwhile
                    apply(found);
                })
                .submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService());
    }

    private void apply(Lookup found) {
        last = found;
        deep.setText(found.deepText());
        deep.setEnabled(found.deepEnabled());
        if (!found.deepEnabled()) deep.setSelected(false);
        String t = found.text().strip();
        if (found.listedAs() == null) {
            listed = null;
            fieldModel.clear();
            fieldsTitle.setText("Fields");
        } else if (!found.listedAs().equals(listed) || fieldModel.isEmpty()) {
            listed = found.listedAs();
            fieldModel.clear();
            for (FieldRow r : found.rows()) fieldModel.addElement(r);
            fieldsTitle.setText(found.title() != null ? found.title() : found.className().endsWith("()")
                    ? "Variables of " + found.className() + " (" + found.rows().size() + "): pick one, or what it returns"
                    : "Fields of " + found.className() + " (" + (found.rows().size() - 1) + "): pick one, or all of them");
        }
        if (listed != null) selectTyped(t);
        initValidation();
    }

    /** "Class" and "Class.field" for every class of the project's (non-test) Java and Kotlin code: the suggestions. */
    static List<String> targets(Project project) {
        Set<String> out = new LinkedHashSet<>();
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        PsiManager psi = PsiManager.getInstance(project);
        index.iterateContent(f -> {
            String ext = f.getExtension();
            if (f.isDirectory() || !("java".equalsIgnoreCase(ext) || "kt".equalsIgnoreCase(ext))
                    || !index.isInSourceContent(f) || index.isInTestSourceContent(f)) return true;
            if (psi.findFile(f) instanceof PsiClassOwner owner) for (PsiClass c : owner.getClasses()) add(c, out);
            return out.size() < 20_000;
        });
        return new ArrayList<>(out);
    }

    private static void add(PsiClass c, Set<String> out) {
        if (c.getName() != null && !c.isInterface() && !c.isAnnotationType()) {
            out.add(c.getName());
            for (PsiField f : c.getAllFields()) out.add(c.getName() + "." + f.getName());
            // its methods: "Class.method()" lists the method's variables and what it returns
            for (com.intellij.psi.PsiMethod m : c.getMethods()) {
                if (!m.isConstructor() && m.getBody() != null) out.add(c.getName() + "." + m.getName() + "()");
            }
        }
        for (PsiClass inner : c.getInnerClasses()) add(inner, out);
    }

    /** Selects the row of what is typed (without typing it again). */
    private void selectTyped(String typed) {
        String field = listed != null && typed.startsWith(listed + ".") ? typed.substring(listed.length() + 1) : null;
        picking = true;
        try {
            for (int i = 0; i < fieldModel.size(); i++) {
                FieldRow r = fieldModel.get(i);
                if (field == null ? r.field() == null && typed.equals(listed) : field.equals(r.field())) {
                    fields.setSelectedIndex(i);
                    fields.ensureIndexIsVisible(i);
                    return;
                }
            }
            fields.clearSelection();
        } finally {
            picking = false;
        }
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = JBUI.insetsBottom(12);
        JPanel nameRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        nameRow.add(new JBLabel("Name:"), BorderLayout.WEST);
        nameRow.add(name, BorderLayout.CENTER);
        form.add(nameRow, c);
        c.gridy++;
        c.insets = JBUI.insetsBottom(4);
        form.add(new JBLabel("The field (Class.field), the object (Class), or a method's variable (Class.method().variable) to watch:"), c);
        c.gridy++;
        c.insets = JBUI.insetsBottom(8);
        form.add(target, c);
        c.gridy++;
        c.insets = JBUI.insetsBottom(4);
        fieldsTitle.setForeground(UIUtil.getContextHelpForeground());
        form.add(fieldsTitle, c);
        c.gridy++;
        c.insets = JBUI.insetsBottom(12);
        c.fill = GridBagConstraints.BOTH;
        c.weighty = 1;
        JBScrollPane scroll = FlowScroll.wheel(new JBScrollPane(fields));
        scroll.setPreferredSize(new Dimension(JBUI.scale(520), JBUI.scale(170)));
        form.add(scroll, c);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weighty = 0;
        c.gridy++;
        c.insets = JBUI.insetsBottom(12);
        deep.setToolTipText("<html>A field only tells when it is pointed at another object. Its data (the object's fields, and "
                + "the objects inside it) changes without that:<br>tick this to stop then too.</html>");
        form.add(deep, c);
        c.gridy++;
        c.insets = JBUI.insetsBottom(4);
        form.add(new JBLabel("Stop the debugger:"), c);
        for (JBRadioButton r : new JBRadioButton[]{set, change, toNull, toValue}) {
            c.gridy++;
            c.insets = JBUI.insets(0, 12, 2, 0);
            form.add(r, c);
        }
        c.gridy++;
        c.insets = JBUI.insets(0, 36, 10, 0);
        form.add(value, c);
        c.gridy++;
        c.insets = JBUI.insetsBottom(10);
        form.add(keep, c);
        c.gridy++;
        JBLabel note = new JBLabel("<html><div style='width:500px'>It stops on the line that sets the field (or the object's "
                + "data), in any thread or module, without breakpoints. Start the app in Debug first. A method's variable: "
                + "Class.method() lists its variables and what it returns; a field inside it goes after it "
                + "(.booking.email, or .booking.getEmail()). Or right-click a variable in the code.</div></html>");
        note.setForeground(UIUtil.getContextHelpForeground());
        form.add(note, c);
        return form;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return target;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        Lookup found = last;
        if (found == null || !found.text().equals(target.getText())) return new ValidationInfo("Looking it up…", target);
        String resolved = found.resolved();
        if (resolved.startsWith("!")) return new ValidationInfo(resolved.substring(1), target);
        if (toValue.isSelected()) {
            List<String> vals = values();
            if (vals.isEmpty()) return new ValidationInfo("Type the value it should be set to", value);
            for (String v : vals) {
                String error = FlowValueSearch.error(v);
                if (error != null) return new ValidationInfo(error + " (" + v + ")", value);
            }
        }
        return null;
    }

    /** The class (JVM name) and "#field" to watch; "*" first when the object's data is watched too. */
    String resolved() {
        Lookup found = last;
        String r = found != null && found.text().equals(target.getText()) ? found.resolved() : "!Not looked up yet";
        return r.startsWith("!") || !deep.isSelected() || !deep.isEnabled() ? r : "*" + r;
    }

    String when() {
        return set.isSelected() ? "SET" : toNull.isSelected() ? "NULL" : toValue.isSelected() ? "VALUE" : "CHANGE";
    }

    List<String> values() {
        List<String> out = new ArrayList<>();
        if (!toValue.isSelected()) return out;
        for (String v : value.getText().split(",")) if (!v.isBlank()) out.add(v.strip());
        return out;
    }

    boolean keepGoing() {
        return keep.isSelected();
    }

    String name() {
        return name.getText();
    }

    private static final class FieldRenderer extends ColoredListCellRenderer<FieldRow> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends FieldRow> list, FieldRow r, int index, boolean selected, boolean focus) {
            setBorder(JBUI.Borders.empty(2, 8));
            if (r.field() == null) {
                setIcon(AllIcons.Nodes.Class);
                append("All fields", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                append("   stop when any field of the object is set", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                return;
            }
            if (r.declaredIn().startsWith("#")) { // a method's parameter, variable or return value
                String kind = r.declaredIn().substring(1);
                setIcon(kind.equals("parameter") ? AllIcons.Nodes.Parameter : kind.equals("return") ? AllIcons.Actions.StepOut : AllIcons.Nodes.Variable);
                append(r.field(), kind.equals("return") ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append("   " + r.type(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append("   " + (kind.equals("return") ? "what it returns: stop as it returns" : kind), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                return;
            }
            setIcon(AllIcons.Nodes.Field);
            append(r.field(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
            append("   " + r.type(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            if (!r.declaredIn().isEmpty()) append("   (from " + r.declaredIn() + ")", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
        }
    }
}
