package com.example.flowtracker.ui;

import com.example.flowtracker.model.FlowHuntStore;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Stop at Value's Recycle Bin: records and stops deleted, the latest first. Restore puts them back (a record into the
 * list, stops into their record); Delete Forever and Empty Bin delete them for good.
 */
final class FlowHuntBinDialog extends DialogWrapper {
    private final FlowHuntStore store;
    private final DefaultListModel<FlowHuntStore.Binned> model = new DefaultListModel<>();
    private final JBList<FlowHuntStore.Binned> list = new JBList<>(model);
    private final JBLabel note = new JBLabel();
    private final Action restore, forever, empty;

    FlowHuntBinDialog(Project project) {
        super(project, true);
        this.store = FlowHuntStore.get(project);
        setTitle("Stop at Value: Recycle Bin");
        restore = new AbstractAction("Restore") {
            @Override public void actionPerformed(ActionEvent e) { restoreSelected(); }
        };
        forever = new AbstractAction("Delete Forever") {
            @Override public void actionPerformed(ActionEvent e) { deleteSelected(); }
        };
        empty = new AbstractAction("Empty Bin") {
            @Override public void actionPerformed(ActionEvent e) { emptyBin(); }
        };
        setOKButtonText("Close");
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setCellRenderer(new Renderer());
        list.getEmptyText().setText("The Recycle Bin is empty");
        list.addListSelectionListener(e -> update());
        init();
        fill();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel p = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        JBLabel title = new JBLabel("Records and stops deleted in Stop at Value. Restore puts them back where they were.");
        title.setForeground(UIUtil.getContextHelpForeground());
        p.add(title, BorderLayout.NORTH);
        p.add(FlowScroll.wheel(new JBScrollPane(list)), BorderLayout.CENTER);
        note.setForeground(UIUtil.getContextHelpForeground());
        p.add(note, BorderLayout.SOUTH);
        p.setPreferredSize(new Dimension(JBUI.scale(560), JBUI.scale(360)));
        return p;
    }

    @Override
    protected Action[] createLeftSideActions() {
        return new Action[]{restore, forever, empty};
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction()};
    }

    private void fill() {
        model.clear();
        for (FlowHuntStore.Binned b : store.bin()) model.addElement(b);
        if (!model.isEmpty() && list.getSelectedIndex() < 0) list.setSelectedIndex(0);
        update();
    }

    private void update() {
        boolean picked = !list.getSelectedValuesList().isEmpty();
        restore.setEnabled(picked);
        forever.setEnabled(picked);
        empty.setEnabled(!model.isEmpty());
    }

    private void restoreSelected() {
        int kept = 0;
        for (FlowHuntStore.Binned b : list.getSelectedValuesList()) if (!store.restore(b)) kept++;
        note.setText(kept == 0 ? "" : kept + (kept == 1 ? " item stays" : " items stay")
                + " in the bin: the record its stops were in was deleted for good.");
        fill();
    }

    private void deleteSelected() {
        List<FlowHuntStore.Binned> gone = list.getSelectedValuesList();
        if (gone.isEmpty()) return;
        if (Messages.showDialog(getContentPanel(), "Delete " + (gone.size() == 1 ? "“" + gone.get(0).label() + "”" : gone.size() + " items")
                + " for good? This can't be undone.", "Delete Forever", new String[]{"Delete Forever", "Cancel"}, 1, Messages.getWarningIcon()) != 0) {
            return;
        }
        store.purge(gone);
        fill();
    }

    private void emptyBin() {
        if (model.isEmpty()) return;
        if (Messages.showDialog(getContentPanel(), "Delete everything in the Recycle Bin (" + model.size() + " items) for good? "
                + "This can't be undone.", "Empty Bin", new String[]{"Empty Bin", "Cancel"}, 1, Messages.getWarningIcon()) != 0) {
            return;
        }
        store.purge(store.bin());
        fill();
    }

    private static final class Renderer extends ColoredListCellRenderer<FlowHuntStore.Binned> {
        @Override
        protected void customizeCellRenderer(@NotNull JList<? extends FlowHuntStore.Binned> l, FlowHuntStore.Binned b, int index,
                                             boolean selected, boolean focus) {
            setBorder(JBUI.Borders.empty(5, 8));
            setIcon(b.hunt != null ? (b.hunt.isWatch() ? AllIcons.Debugger.Db_field_breakpoint : AllIcons.Actions.Find) : AllIcons.Debugger.Db_set_breakpoint);
            append(b.label(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            append("   deleted " + new SimpleDateFormat("dd MMM HH:mm:ss").format(new Date(b.deletedAt)), SimpleTextAttributes.GRAYED_ATTRIBUTES);
        }
    }
}
