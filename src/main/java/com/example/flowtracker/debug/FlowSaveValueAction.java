package com.example.flowtracker.debug;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.frame.XValue;
import com.intellij.xdebugger.impl.ui.tree.actions.XDebuggerTreeActionBase;
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl;
import org.jetbrains.annotations.NotNull;

/**
 * "Save to TraceWeave" in the debugger's value trees: the Variables and Watches views, Inspect, and the result
 * of Evaluate Expression (they all show the debugger's value menu). Saves the right-clicked value with its fields,
 * a few levels deep (see {@link FlowDebugCapture#save}).
 */
public final class FlowSaveValueAction extends AnAction implements DumbAware {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null
                && XDebuggerTreeActionBase.getSelectedValue(e.getDataContext()) != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        XValue value = XDebuggerTreeActionBase.getSelectedValue(e.getDataContext());
        XValueNodeImpl node = XDebuggerTreeActionBase.getSelectedNode(e.getDataContext());
        if (project == null || value == null) return;
        FlowDebugCapture.save(project, node != null ? node.getName() : "value", value);
    }
}
