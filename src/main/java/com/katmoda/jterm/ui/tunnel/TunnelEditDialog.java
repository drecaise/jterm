package com.katmoda.jterm.ui.tunnel;

import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.session.TunnelConfig;
import com.katmoda.jterm.session.TunnelConfig.TunnelType;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridLayout;
import java.util.List;

/**
 * Modal form for creating/editing a {@link TunnelConfig}, using the
 * {@code JOptionPane.showConfirmDialog} + form-panel pattern used by the SSH session editor.
 * Returns the populated config on OK (the caller persists it) or {@code null} on cancel.
 */
public final class TunnelEditDialog {

    private TunnelEditDialog() {
    }

    /**
     * Shows the editor seeded from {@code seed} (a copy is edited, so the original is untouched).
     * {@code sessions} populates the SSH-session chooser. Returns the edited config or {@code null}.
     */
    public static TunnelConfig edit(Component parent, TunnelConfig seed,
                                    List<SshSessionConfig> sessions, String title) {
        JTextField name = new JTextField(seed.getName(), 20);

        JComboBox<TunnelType> type = new JComboBox<>(TunnelType.values());
        type.setSelectedItem(seed.getType());

        JComboBox<SshSessionConfig> session = new JComboBox<>(
                new DefaultComboBoxModel<>(sessions.toArray(new SshSessionConfig[0])));
        session.setRenderer(new SessionRenderer());
        selectSession(session, sessions, seed.getSshSessionId());

        JSpinner listenPort = new JSpinner(new SpinnerNumberModel(
                clampPort(seed.getListenPort()), 0, 65535, 1));
        JTextField destHost = new JTextField(seed.getDestHost(), 20);
        JSpinner destPort = new JSpinner(new SpinnerNumberModel(
                clampPort(seed.getDestPort()), 0, 65535, 1));
        JCheckBox autoStart = new JCheckBox("Start automatically on launch", seed.isAutoStart());

        JLabel listenLabel = new JLabel();
        JLabel destHostLabel = new JLabel("Destination host:");
        JLabel destPortLabel = new JLabel("Destination port:");

        Runnable applyType = () -> {
            TunnelType t = (TunnelType) type.getSelectedItem();
            boolean remote = t == TunnelType.REMOTE;
            boolean dynamic = t == TunnelType.DYNAMIC;
            listenLabel.setText(remote ? "Remote (server) port:" : "Local listen port:");
            destHost.setEnabled(!dynamic);
            destPort.setEnabled(!dynamic);
            destHostLabel.setEnabled(!dynamic);
            destPortLabel.setEnabled(!dynamic);
        };
        type.addActionListener(e -> applyType.run());
        applyType.run();

        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.add(new JLabel("Name:"));
        form.add(name);
        form.add(new JLabel("Type:"));
        form.add(type);
        form.add(new JLabel("SSH session:"));
        form.add(session);
        form.add(listenLabel);
        form.add(listenPort);
        form.add(destHostLabel);
        form.add(destHost);
        form.add(destPortLabel);
        form.add(destPort);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(form, BorderLayout.CENTER);
        panel.add(autoStart, BorderLayout.SOUTH);

        while (true) {
            int result = JOptionPane.showConfirmDialog(parent, panel, title,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return null;
            }
            String error = validate(name.getText(), (SshSessionConfig) session.getSelectedItem(),
                    (TunnelType) type.getSelectedItem(), (Integer) listenPort.getValue(),
                    destHost.getText(), (Integer) destPort.getValue());
            if (error != null) {
                JOptionPane.showMessageDialog(parent, error, "Tunneling",
                        JOptionPane.WARNING_MESSAGE);
                continue;
            }

            TunnelConfig out = seed.copy();
            out.setName(name.getText().trim());
            out.setType((TunnelType) type.getSelectedItem());
            SshSessionConfig chosen = (SshSessionConfig) session.getSelectedItem();
            out.setSshSessionId(chosen != null ? chosen.getId() : null);
            out.setListenPort((Integer) listenPort.getValue());
            out.setDestHost(destHost.getText().trim());
            out.setDestPort((Integer) destPort.getValue());
            out.setAutoStart(autoStart.isSelected());
            return out;
        }
    }

    private static String validate(String name, SshSessionConfig session, TunnelType type,
                                   int listenPort, String destHost, int destPort) {
        if (name == null || name.isBlank()) {
            return "Please enter a name.";
        }
        if (session == null) {
            return "Please choose an SSH session. Add one in the sidebar first.";
        }
        if (listenPort <= 0) {
            return "Please enter a listen port between 1 and 65535.";
        }
        if (type != TunnelType.DYNAMIC) {
            if (destHost == null || destHost.isBlank()) {
                return "Please enter a destination host.";
            }
            if (destPort <= 0) {
                return "Please enter a destination port between 1 and 65535.";
            }
        }
        return null;
    }

    private static void selectSession(JComboBox<SshSessionConfig> combo,
                                      List<SshSessionConfig> sessions, String id) {
        if (id != null) {
            for (SshSessionConfig s : sessions) {
                if (s.getId().equals(id)) {
                    combo.setSelectedItem(s);
                    return;
                }
            }
        }
        if (!sessions.isEmpty()) {
            combo.setSelectedIndex(0);
        }
    }

    private static int clampPort(int port) {
        if (port < 0) {
            return 0;
        }
        return Math.min(port, 65535);
    }

    /** Renders an SSH session as {@code name (user@host)}. */
    private static final class SessionRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof SshSessionConfig s) {
                setText(s.getName() + "  (" + s.getUser() + "@" + s.getHost() + ")");
            }
            return this;
        }
    }
}
