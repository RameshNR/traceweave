package com.example.flowtracker.ui;

import com.example.flowtracker.copy.FlowCopy;
import com.example.flowtracker.copy.FlowFormat;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowStore;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The Recycle Bin: every deleted line, cleared flow and deleted flow, newest first. Items can be restored
 * (back into the flow they came from, at their old places) or deleted for good.
 */
final class FlowRecycleBinDialog extends DialogWrapper {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    private final Project project;
    private final FlowStore store;
    private final CollectionListModel<FlowStore.BinItem> model = new CollectionListModel<>();
    private final JBList<FlowStore.BinItem> list = new JBList<>(model);
    private final JBTextArea preview = new JBTextArea();
    private final Action deleteAction;
    private final Action emptyAction;

    FlowRecycleBinDialog(Project project) {
        super(project);
        this.project = project;
        this.store = FlowStore.get(project);
        setTitle("TraceWeave Recycle Bin");
        setOKButtonText("Restore");

        deleteAction = new DialogWrapperAction("Delete Permanently") {
            {
                putValue(SHORT_DESCRIPTION, "Delete the selected items for good (Delete key)");
            }

            @Override
            protected void doAction(ActionEvent e) {
                List<FlowStore.BinItem> picked = list.getSelectedValuesList();
                if (picked.isEmpty()) return;
                int answer = Messages.showYesNoDialog(getContentPanel(),
                        "Delete " + picked.size() + (picked.size() == 1 ? " item" : " items")
                                + " for good? This can't be undone.",
                        "Delete Permanently", Messages.getWarningIcon());
                if (answer == Messages.YES) store.purge(picked);
            }
        };
        emptyAction = new DialogWrapperAction("Empty Recycle Bin") {
            @Override
            protected void doAction(ActionEvent e) {
                int answer = Messages.showYesNoDialog(getContentPanel(),
                        "Delete all " + model.getSize() + " items for good? This can't be undone.",
                        "Empty Recycle Bin", Messages.getWarningIcon());
                if (answer == Messages.YES) store.emptyRecycleBin();
            }
        };

        Runnable reload = this::reload;
        store.addListener(reload);
        Disposer.register(getDisposable(), () -> store.removeListener(reload));
        init();
        reload();
    }

    private void reload() {
        List<FlowStore.BinItem> selected = list.getSelectedValuesList();
        model.replaceAll(store.getRecycleBin());
        for (FlowStore.BinItem item : selected) {
            int i = model.getElementIndex(item);
            if (i >= 0) list.addSelectionInterval(i, i);
        }
        updateState();
    }

    private void updateState() {
        List<FlowStore.BinItem> picked = list.getSelectedValuesList();
        setOKActionEnabled(!picked.isEmpty());
        deleteAction.setEnabled(!picked.isEmpty());
        emptyAction.setEnabled(model.getSize() > 0);
        preview.setText(previewText(picked));
        preview.setCaretPosition(0);
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.getEmptyText().setText("The Recycle Bin is empty");
        list.setCellRenderer(new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(@NotNull JList<? extends FlowStore.BinItem> l, FlowStore.BinItem item,
                                                 int index, boolean selected, boolean hasFocus) {
                setIcon(item.wholeFlow ? FlowTheme.ICON_FLOWS : FlowTheme.ICON_DELETE);
                append(item.label);
                append("   " + (item.wholeFlow ? "flow" : "from “" + item.flowName + "”") + " · "
                        + TIME.format(Instant.ofEpochMilli(item.deletedAt).atZone(ZoneId.systemDefault())),
                        SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        });
        list.addListSelectionListener(e -> updateState());
        // The Delete (or Backspace) key does the same as Delete Permanently: asks, then removes the selection.
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "binDelete");
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "binDelete");
        list.getActionMap().put("binDelete", deleteAction);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && !list.isSelectionEmpty()) doOKAction(); // double-click restores
            }
        });

        preview.setEditable(false);
        preview.setFont(FlowTheme.codeFont());
        preview.setBorder(JBUI.Borders.empty(6, 8));
        preview.getEmptyText().setText("Select an item to see its lines");
        preview.getEmptyText().appendSecondaryText("Restore brings it back · Delete key removes it for good",
                com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES, null);

        JBSplitter split = new JBSplitter(true, 0.5f);
        split.setFirstComponent(new JBScrollPane(list));
        split.setSecondComponent(new JBScrollPane(preview));
        split.setPreferredSize(new Dimension(JBUI.scale(640), JBUI.scale(460)));
        return split;
    }

    private static String previewText(List<FlowStore.BinItem> items) {
        StringBuilder sb = new StringBuilder();
        for (FlowStore.BinItem item : items) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("# ").append(item.label).append('\n');
            for (FlowEntry e : item.entries) {
                sb.append(e.fileName).append(':').append(e.line).append("   ").append(e.text.strip())
                        .append(FlowFormat.tagSuffix(e));
                if (e.note != null && !e.note.isBlank()) sb.append("   ← ").append(e.note);
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    @Override
    @NotNull
    protected Action[] createActions() {
        return new Action[]{getOKAction(), deleteAction, getCancelAction()};
    }

    @Override
    @NotNull
    protected Action[] createLeftSideActions() {
        return new Action[]{emptyAction};
    }

    @Override
    protected void doOKAction() {
        List<FlowStore.BinItem> picked = list.getSelectedValuesList();
        int restored = 0;
        // Oldest first, so lines deleted one after the other come back in their old order.
        for (int i = picked.size() - 1; i >= 0; i--) restored += store.restore(picked.get(i));
        if (restored > 0) {
            FlowCopy.notify(project, "Restored " + restored + (restored == 1 ? " line" : " lines") + " to “"
                    + store.getActiveFlowName() + "”", NotificationType.INFORMATION);
        }
        super.doOKAction();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return list;
    }

    @Override
    protected @Nullable String getDimensionServiceKey() {
        return "FlowTracker.RecycleBin";
    }
}
