package com.example.flowtracker.ui;

import com.example.flowtracker.copy.FlowAi;
import com.example.flowtracker.model.FlowEntry;
import com.example.flowtracker.model.FlowLook;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.ButtonGroup;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.List;
import java.util.Set;

/**
 * The AI summary's windows: its Settings (off by default; turning it on needs the user's tick), the window that
 * shows exactly what would be sent and where (nothing is sent without Send), and the answer.
 */
public final class FlowAiDialogs {
    private FlowAiDialogs() {
    }

    /** Settings → AI Summary: Off, a local model, or a cloud service (the user's own key). */
    public static void settings(@Nullable Project project) {
        new SettingsDialog(project).show();
    }

    /**
     * Write Summary with AI: shows the text and where it goes; only Send sends it (in the background), then the answer.
     * When AI Summary is off, it says where to turn it on (nothing else happens).
     */
    public static void writeSummary(Project project, List<FlowEntry> entries, Set<String> links) {
        if (!FlowAi.enabled()) {
            Messages.showInfoMessage(project, "AI Summary is off, so nothing is sent anywhere. Copy Flow Summary writes one here "
                    + "without AI. To use an AI, turn it on yourself in Settings → AI Summary.", "Write Summary with AI");
            return;
        }
        String text = FlowAi.request(entries, links);
        if (!new SendDialog(project, text).showAndGet()) return; // only Send sends it
        new Task.Backgroundable(project, "Writing the flow summary with AI", true) {
            private String answer;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    answer = FlowAi.send(text);
                } catch (Exception e) {
                    answer = null;
                    String why = e.getMessage() == null ? e.toString() : e.getMessage();
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() ->
                            Messages.showErrorDialog(project, "The summary couldn't be written: " + why, "Write Summary with AI"));
                }
            }

            @Override
            public void onSuccess() {
                if (answer != null) new AnswerDialog(project, answer).show();
            }
        }.queue();
    }

    // ---------- Settings ----------

    private static final class SettingsDialog extends DialogWrapper {
        private final JBRadioButton off = new JBRadioButton("Off: nothing is sent anywhere (Copy Flow Summary writes one here, without AI)");
        private final JBRadioButton local = new JBRadioButton("A local model on this machine (e.g. Ollama): nothing leaves the machine");
        private final JBRadioButton cloud = new JBRadioButton("A cloud service, with your own API key: the flow's lines are sent to it");
        private final JBTextField localUrl = new JBTextField(), localModel = new JBTextField();
        private final JComboBox<String> provider = new JComboBox<>(new String[]{"Claude (Anthropic API)", "OpenAI-compatible service"});
        private final JBTextField cloudUrl = new JBTextField(), cloudModel = new JBTextField();
        private final JBPasswordField key = new JBPasswordField();
        private final JBCheckBox agree = new JBCheckBox("I understand: when I ask for a summary, the flow's lines (code and notes) "
                + "are sent where chosen above. Nothing is sent without my Send.");

        SettingsDialog(@Nullable Project project) {
            super(project, true);
            FlowLook l = FlowLook.get();
            setTitle("AI Summary");
            ButtonGroup g = new ButtonGroup();
            for (JBRadioButton r : new JBRadioButton[]{off, local, cloud}) {
                g.add(r);
                r.addActionListener(e -> enable());
            }
            String mode = l.getAiMode();
            (FlowAi.LOCAL.equals(mode) ? local : FlowAi.CLOUD.equals(mode) ? cloud : off).setSelected(true);
            agree.setSelected(!FlowAi.OFF.equals(mode));
            localUrl.setText(l.getAiLocalUrl());
            localModel.setText(l.getAiLocalModel());
            provider.setSelectedIndex("OPENAI".equals(l.getAiCloudProvider()) ? 1 : 0);
            provider.addActionListener(e -> enable());
            cloudUrl.setText(l.getAiCloudUrl());
            cloudModel.setText(l.getAiCloudModel());
            String saved = FlowAi.apiKey();
            if (saved != null) key.setText(saved);
            init();
            enable();
        }

        private void enable() {
            localUrl.setEnabled(local.isSelected());
            localModel.setEnabled(local.isSelected());
            provider.setEnabled(cloud.isSelected());
            cloudUrl.setEnabled(cloud.isSelected() && provider.getSelectedIndex() == 1);
            cloudModel.setEnabled(cloud.isSelected());
            key.setEnabled(cloud.isSelected());
            agree.setEnabled(!off.isSelected());
            setOKButtonText(off.isSelected() ? "Save (Off)" : "Turn On");
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel p = new JPanel(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = 0;
            c.gridwidth = 2;
            c.anchor = GridBagConstraints.WEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            JBLabel head = new JBLabel("<html><div style='width:560px'>A flow summary can be written by an AI you choose. It is "
                    + "<b>off</b> unless you turn it on here, and even then nothing is sent until you see the exact text and press "
                    + "<b>Send</b>. Copy Flow Summary always works without AI.</div></html>");
            head.setForeground(UIUtil.getContextHelpForeground());
            c.insets = JBUI.insetsBottom(10);
            p.add(head, c);
            c.insets = JBUI.insetsBottom(2);
            c.gridy++;
            p.add(off, c);
            c.gridy++;
            p.add(local, c);
            row(p, c, "Ollama address:", localUrl);
            row(p, c, "Model:", localModel);
            c.gridy++;
            c.gridx = 0;
            c.gridwidth = 2;
            c.insets = JBUI.insets(6, 0, 2, 0);
            p.add(cloud, c);
            row(p, c, "Service:", provider);
            row(p, c, "Address (OpenAI-compatible):", cloudUrl);
            row(p, c, "Model:", cloudModel);
            row(p, c, "API key (kept in IntelliJ's password safe):", key);
            c.gridy++;
            c.gridx = 0;
            c.gridwidth = 2;
            c.insets = JBUI.insetsTop(12);
            p.add(agree, c);
            return p;
        }

        private static void row(JPanel p, GridBagConstraints c, String label, JComponent field) {
            c.gridy++;
            c.gridx = 0;
            c.gridwidth = 1;
            c.weightx = 0;
            c.insets = JBUI.insets(2, 28, 2, 8);
            p.add(new JBLabel(label), c);
            c.gridx = 1;
            c.weightx = 1;
            c.insets = JBUI.insets(2, 0, 2, 0);
            p.add(field, c);
        }

        @Override
        protected @Nullable ValidationInfo doValidate() {
            if (off.isSelected()) return null;
            if (!agree.isSelected()) return new ValidationInfo("Tick this to turn it on (or choose Off)", agree);
            if (local.isSelected() && localModel.getText().isBlank()) return new ValidationInfo("Which model? e.g. llama3.1", localModel);
            if (cloud.isSelected()) {
                if (cloudModel.getText().isBlank()) return new ValidationInfo("Which model?", cloudModel);
                if (key.getPassword().length == 0) return new ValidationInfo("Your API key for the service", key);
            }
            return null;
        }

        @Override
        protected void doOKAction() {
            String mode = off.isSelected() ? FlowAi.OFF : local.isSelected() ? FlowAi.LOCAL : FlowAi.CLOUD;
            FlowLook.get().setAi(mode, localUrl.getText().strip(), localModel.getText().strip(),
                    provider.getSelectedIndex() == 1 ? "OPENAI" : "CLAUDE", cloudUrl.getText().strip(), cloudModel.getText().strip());
            if (cloud.isSelected()) FlowAi.setApiKey(new String(key.getPassword()));
            else if (off.isSelected()) FlowAi.setApiKey(null); // off: the key isn't kept either
            super.doOKAction();
        }
    }

    // ---------- what is sent, before it is ----------

    private static final class SendDialog extends DialogWrapper {
        private final String text;

        SendDialog(Project project, String text) {
            super(project, true);
            this.text = text;
            setTitle("Send to AI?");
            setOKButtonText("Send");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel p = new JPanel(new BorderLayout(0, JBUI.scale(8)));
            JBLabel where = new JBLabel("<html><div style='width:620px'>This exact text will be sent to <b>"
                    + com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(FlowAi.destination())
                    + "</b>, and nothing else. Press <b>Send</b> to send it, or Cancel.</div></html>");
            p.add(where, BorderLayout.NORTH);
            JBTextArea area = new JBTextArea(text);
            area.setEditable(false);
            area.setFont(JBUI.Fonts.create(java.awt.Font.MONOSPACED, JBUI.Fonts.label().getSize()));
            area.setCaretPosition(0);
            JBScrollPane scroll = new JBScrollPane(area);
            scroll.setPreferredSize(new Dimension(JBUI.scale(720), JBUI.scale(380)));
            p.add(scroll, BorderLayout.CENTER);
            return p;
        }
    }

    // ---------- the answer ----------

    private static final class AnswerDialog extends DialogWrapper {
        private final String answer;

        AnswerDialog(Project project, String answer) {
            super(project, true);
            this.answer = answer;
            setTitle("Flow Summary (written by AI)");
            setOKButtonText("Copy and Close");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JBTextArea area = new JBTextArea(answer);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setCaretPosition(0);
            JBScrollPane scroll = new JBScrollPane(area);
            scroll.setPreferredSize(new Dimension(JBUI.scale(640), JBUI.scale(320)));
            return scroll;
        }

        @Override
        protected void doOKAction() {
            com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(new java.awt.datatransfer.StringSelection(answer));
            super.doOKAction();
        }
    }
}
