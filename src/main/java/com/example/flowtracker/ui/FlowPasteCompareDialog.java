package com.example.flowtracker.ui;

import com.example.flowtracker.model.FlowTextFormat;
import com.example.flowtracker.model.FlowValue;
import com.example.flowtracker.model.FlowValueDiff;
import com.example.flowtracker.model.FlowValueText;
import com.intellij.openapi.project.Project;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Compare with pasted data: paste one piece of data (next to a saved value) or two (JSON, XML, a toString() or the
 * debugger's text, a log), and see what was added, removed or changed. When both sides are JSON they are compared
 * field by field in the Compare window (Tree, JSON and Text views); otherwise the kind is detected, both are laid
 * out one value per line and IntelliJ's diff viewer shows the lines that differ. A window in the IDE's style, with
 * Minimize, Maximize and Close.
 */
public final class FlowPasteCompareDialog {
    private final @Nullable Project project;
    private final JBTextArea left = area(), right = area();
    private final JBLabel leftKind = new JBLabel(), rightKind = new JBLabel();
    private final JBLabel leftTitle = new JBLabel("Before (left)"), rightTitle = new JBLabel("After (right)");
    private final JButton compare = new JButton("Compare");
    private final String title;
    /** The saved value on the left (null when both sides are pasted). */
    private final @Nullable FlowValue saved;
    private final @Nullable FlowWindow window;
    /** The editing view and the result of Compare, in the same window. */
    private final JPanel views = new JPanel(new java.awt.CardLayout());
    private final JPanel result = new JPanel(new BorderLayout());
    /** What the result view holds (its diff viewer), let go when it is left. */
    private @Nullable com.intellij.openapi.Disposable shown;

    public static void open(@Nullable Project project) {
        new FlowPasteCompareDialog(project, "", null);
    }

    /** Opens it with a saved value on the left (as JSON) and the right side empty, ready to paste the other value. */
    public static void open(@Nullable Project project, String title, FlowValue saved) {
        new FlowPasteCompareDialog(project, title, saved);
    }

    private FlowPasteCompareDialog(@Nullable Project project, String title, @Nullable FlowValue saved) {
        this.project = project;
        this.saved = saved;
        this.title = title.isEmpty() ? "Compare Pasted Data" : "Compare " + title;
        if (saved != null) {
            leftTitle.setText("Saved: " + title);
            rightTitle.setText("Paste the other value here");
            showSaved(true);
        }
        views.add(build(), "edit");
        views.add(result, "result");
        window = new FlowWindow(project, "TraceWeave.PasteCompare", this.title, views, null);
        window.whenClosed(this::leaveResult);
        window.show();
        update();
        SwingUtilities.invokeLater(() -> (saved == null ? left : right).requestFocusInWindow());
    }

    private static JBTextArea area() {
        JBTextArea a = new JBTextArea();
        a.setFont(JBUI.Fonts.create(Font.MONOSPACED, JBUI.Fonts.label().getSize()));
        a.getEmptyText().setText("Paste data here (JSON, XML, toString(), text)");
        return a;
    }

    /** The saved value on the left, as JSON or as indented text. */
    private void showSaved(boolean json) {
        if (saved == null) return;
        left.setText(json ? FlowValueText.valueJson(saved, true) : FlowValueDiff.withoutIds(FlowValueText.toText(saved)));
        left.setCaretPosition(0);
    }

    private JPanel build() {
        DocumentAdapter changed = new DocumentAdapter() {
            @Override protected void textChanged(@NotNull DocumentEvent e) { update(); }
        };
        left.getDocument().addDocumentListener(changed);
        right.getDocument().addDocumentListener(changed);

        JPanel leftHead = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        leftHead.add(leftTitle, BorderLayout.CENTER);
        if (saved != null) {
            FlowToolbar format = new FlowToolbar();
            FlowButton json = new FlowButton("JSON", null, FlowButton.Style.SEGMENT);
            FlowButton text = new FlowButton("Text", null, FlowButton.Style.SEGMENT);
            json.setToolTipText("The saved value as JSON (compared field by field with pasted JSON)");
            text.setToolTipText("The saved value as indented text: name = value {type}");
            json.setSelected(true);
            json.addActionListener(e -> { showSaved(true); json.setSelected(true); text.setSelected(false); });
            text.addActionListener(e -> { showSaved(false); text.setSelected(true); json.setSelected(false); });
            format.addGroup(true, 3, json, text);
            leftHead.add(format, BorderLayout.EAST);
        }
        JBSplitter split = new JBSplitter(false, 0.5f);
        split.setFirstComponent(side(leftHead, leftTitle, left, leftKind));
        split.setSecondComponent(side(rightTitle, rightTitle, right, rightKind));

        JBLabel hint = new JBLabel("<html>" + (saved == null ? "Paste two versions of some data." : "Paste the value to "
                + "compare the saved one with on the right.") + " JSON on both sides is compared field by field (Tree, JSON "
                + "and Text views); other data line by line in IntelliJ's diff viewer. Instance ids like @1141 are left out.<br>"
                + "<b>Ctrl+Enter</b> Compare &nbsp;·&nbsp; <b>Ctrl+Shift+V</b> Paste &nbsp;·&nbsp; <b>Ctrl+Shift+X</b> Swap sides "
                + "&nbsp;·&nbsp; <b>Alt+1</b> / <b>Alt+2</b> Left / right side &nbsp;·&nbsp; <b>Esc</b> Close</html>");
        hint.setForeground(UIUtil.getContextHelpForeground());

        JButton paste = new JButton("Paste Clipboard");
        paste.setToolTipText("Paste the clipboard into the right side (Ctrl+Shift+V)");
        paste.addActionListener(e -> pasteClipboard());
        JButton swap = new JButton("Swap Sides");
        swap.setToolTipText("Ctrl+Shift+X");
        swap.addActionListener(e -> swap());
        JButton close = new JButton("Close");
        close.addActionListener(e -> close());
        compare.setToolTipText("Ctrl+Enter");
        compare.addActionListener(e -> compare());
        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0));
        leftButtons.add(paste);
        leftButtons.add(swap);
        JButton openFile = new JButton("Open File…");
        openFile.setToolTipText("Put a file's text on the right side (or on the left when that is empty)");
        openFile.addActionListener(e -> openFile());
        leftButtons.add(openFile);
        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0));
        rightButtons.add(compare);
        rightButtons.add(close);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(leftButtons, BorderLayout.WEST);
        buttons.add(rightButtons, BorderLayout.EAST);
        buttons.setBorder(JBUI.Borders.emptyTop(8));

        JPanel root = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        root.add(hint, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        root.setBorder(JBUI.Borders.empty(10, 12, 10, 12));
        root.setPreferredSize(new Dimension(JBUI.scale(960), JBUI.scale(560)));
        keys(root);
        return root;
    }

    private static JPanel side(JComponent head, JBLabel title, JBTextArea area, JBLabel kind) {
        JPanel p = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        title.setFont(JBUI.Fonts.label().asBold());
        kind.setForeground(UIUtil.getContextHelpForeground());
        p.add(head, BorderLayout.NORTH);
        p.add(new JBScrollPane(area), BorderLayout.CENTER);
        p.add(kind, BorderLayout.SOUTH);
        return p;
    }

    /** Keys: Ctrl+Enter compares, Ctrl+Shift+V pastes, Ctrl+Shift+X swaps, Alt+1 / Alt+2 go to a side, Esc closes. */
    private void keys(JComponent root) {
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        key(root, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, menu), "compare", () -> {
            if (compare.isEnabled()) compare();
        });
        key(root, KeyStroke.getKeyStroke(KeyEvent.VK_V, menu | InputEvent.SHIFT_DOWN_MASK), "paste", this::pasteClipboard);
        key(root, KeyStroke.getKeyStroke(KeyEvent.VK_X, menu | InputEvent.SHIFT_DOWN_MASK), "swap", this::swap);
        key(root, KeyStroke.getKeyStroke(KeyEvent.VK_1, InputEvent.ALT_DOWN_MASK), "left", left::requestFocusInWindow);
        key(root, KeyStroke.getKeyStroke(KeyEvent.VK_2, InputEvent.ALT_DOWN_MASK), "right", right::requestFocusInWindow);
        key(root, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close", this::close);
    }

    private static void key(JComponent root, KeyStroke stroke, String name, Runnable run) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, "traceweave." + name);
        root.getActionMap().put("traceweave." + name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { run.run(); }
        });
    }

    /** Puts the clipboard's text into the right side (or the left one, when that is focused and not the saved value). */
    private void pasteClipboard() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (!(data instanceof String text)) return;
            JBTextArea target = left.isFocusOwner() && saved == null ? left : right;
            target.setText(text);
            target.setCaretPosition(0);
            target.requestFocusInWindow();
        } catch (Exception ignored) {
            // nothing usable on the clipboard
        }
    }

    /** A file's text on the right (on the left when nothing is there yet and it isn't the saved value). */
    private void openFile() {
        com.intellij.openapi.vfs.VirtualFile file = com.intellij.openapi.fileChooser.FileChooser.chooseFile(
                new com.intellij.openapi.fileChooser.FileChooserDescriptor(true, false, false, false, false, false) // any one file
                        .withTitle("Compare with a File").withDescription("JSON, XML, text or a log"), project, null);
        if (file == null) return;
        try {
            String text = new String(file.contentsToByteArray(), file.getCharset());
            JBTextArea target = saved == null && left.getText().isBlank() ? left : right;
            target.setText(text);
            target.setCaretPosition(0);
            (target == left ? leftTitle : rightTitle).setText(file.getName());
        } catch (java.io.IOException ex) {
            com.intellij.openapi.ui.Messages.showErrorDialog(left, "Couldn't read " + file.getName() + ": " + ex.getMessage(), "Open File");
        }
    }

    private void swap() {
        String a = left.getText(), at = leftTitle.getText();
        left.setText(right.getText());
        right.setText(a);
        leftTitle.setText(rightTitle.getText());
        rightTitle.setText(at);
        left.setCaretPosition(0);
        right.setCaretPosition(0);
    }

    private void update() {
        leftKind.setText("Detected: " + FlowTextFormat.detect(left.getText()).kind());
        rightKind.setText("Detected: " + FlowTextFormat.detect(right.getText()).kind());
        compare.setEnabled(!left.getText().isBlank() && !right.getText().isBlank());
    }

    private String name(JBLabel title) {
        String t = title.getText();
        return t.startsWith("Paste the other") ? "Pasted" : t;
    }

    /**
     * Shows the result in this window (Back to Edit returns to the two sides): JSON on both sides field by field (the
     * Compare view), anything else line by line in IntelliJ's diff viewer.
     */
    private void compare() {
        String a = left.getText(), b = right.getText();
        if (a.isBlank() || b.isBlank()) return;
        leaveResult();
        FlowValue av = FlowValueText.fromJson("value", a), bv = FlowValueText.fromJson("value", b);
        JComponent view;
        if (av != null && bv != null) {
            FlowCompareDialog.Built built = FlowCompareDialog.build(project, title, null, av, name(leftTitle), null, bv, name(rightTitle),
                    "Back to Edit", this::backToEdit);
            shown = built.disposable();
            view = built.root();
        } else {
            FlowTextFormat.Detected da = FlowTextFormat.detect(a), db = FlowTextFormat.detect(b);
            String ext = da.extension().equals(db.extension()) ? da.extension() : "txt";
            com.intellij.openapi.Disposable d = com.intellij.openapi.util.Disposer.newDisposable("TraceWeave paste diff");
            shown = d;
            view = textDiff(FlowDiffs.request(project, title, name(leftTitle) + " · " + da.kind(), da.formatted(),
                    name(rightTitle) + " · " + db.kind(), db.formatted(), ext), d);
        }
        result.removeAll();
        result.add(view, BorderLayout.CENTER);
        result.revalidate();
        ((java.awt.CardLayout) views.getLayout()).show(views, "result");
        result.repaint();
    }

    /** IntelliJ's diff viewer in this window, with Back to Edit and Close under it. */
    private JComponent textDiff(com.intellij.diff.requests.DiffRequest request, com.intellij.openapi.Disposable parent) {
        com.intellij.diff.DiffRequestPanel diff = com.intellij.diff.DiffManager.getInstance().createRequestPanel(project, parent, null);
        diff.setRequest(request);
        JButton back = new JButton("Back to Edit");
        back.setToolTipText("Back to the two sides (Esc)");
        back.addActionListener(e -> backToEdit());
        JButton close = new JButton("Close");
        close.addActionListener(e -> close());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0));
        buttons.add(back);
        buttons.add(close);
        buttons.setBorder(JBUI.Borders.emptyTop(8));
        JPanel p = new JPanel(new BorderLayout());
        p.add(diff.getComponent(), BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);
        p.setBorder(JBUI.Borders.empty(10, 12, 10, 12));
        key(p, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "back", this::backToEdit);
        return p;
    }

    /** Back to the two sides (what was compared stays there to change and compare again). */
    private void backToEdit() {
        ((java.awt.CardLayout) views.getLayout()).show(views, "edit");
        leaveResult();
        left.requestFocusInWindow();
    }

    private void leaveResult() {
        if (shown != null) com.intellij.openapi.util.Disposer.dispose(shown);
        shown = null;
        result.removeAll();
    }

    private void close() {
        if (window != null) window.close();
    }
}
