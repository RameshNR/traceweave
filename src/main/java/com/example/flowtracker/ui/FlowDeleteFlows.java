package com.example.flowtracker.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogBuilder;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Delete Flows: pick several flows (or all of them) to delete at once. */
public final class FlowDeleteFlows {
    private FlowDeleteFlows() {}

    /**
     * @param details what each flow holds, shown next to its name (e.g. "8 lines")
     * @param also    what else is deleted with them (shown under the list), or null
     * @return the flows picked, or null when cancelled
     */
    public static @Nullable List<String> ask(Project project, List<String> flows, Function<String, String> details, @Nullable String also) {
        List<JBCheckBox> boxes = new ArrayList<>();
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        for (String f : flows) {
            JBCheckBox c = new JBCheckBox(f + "   (" + details.apply(f) + ")");
            c.setBorder(JBUI.Borders.empty(3, 4));
            boxes.add(c);
            list.add(c);
        }
        JBCheckBox all = new JBCheckBox("Select all");
        all.setFont(JBUI.Fonts.label().asBold());
        all.addActionListener(e -> boxes.forEach(b -> b.setSelected(all.isSelected())));
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        panel.add(all, BorderLayout.NORTH);
        JBScrollPane scroll = FlowScroll.wheel(new JBScrollPane(list));
        scroll.setPreferredSize(new Dimension(JBUI.scale(420), JBUI.scale(Math.min(320, 40 + flows.size() * 28))));
        panel.add(scroll, BorderLayout.CENTER);
        JBLabel hint = new JBLabel("<html>They go to the Recycle Bin, so they can be restored."
                + (also == null ? "" : " " + also) + " When every flow is picked, one empty flow stays.</html>");
        hint.setForeground(com.intellij.util.ui.UIUtil.getContextHelpForeground());
        panel.add(hint, BorderLayout.SOUTH);
        DialogBuilder b = new DialogBuilder(project);
        b.setTitle("Delete Flows");
        b.setCenterPanel(panel);
        b.addOkAction().setText("Delete");
        b.addCancelAction();
        if (b.show() != DialogWrapper.OK_EXIT_CODE) return null;
        List<String> picked = new ArrayList<>();
        for (int i = 0; i < flows.size(); i++) if (boxes.get(i).isSelected()) picked.add(flows.get(i));
        return picked;
    }
}
