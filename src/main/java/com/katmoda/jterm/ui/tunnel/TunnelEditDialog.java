package com.katmoda.jterm.ui.tunnel;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.session.TunnelConfig;
import com.katmoda.jterm.session.TunnelConfig.TunnelType;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
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

        JLabel typeHint = new JLabel();
        typeHint.setIcon(new FlatSVGIcon("icons/actions/info.svg", 16, 16));
        typeHint.setIconTextGap(6);
        typeHint.setVerticalAlignment(SwingConstants.TOP);
        Color hintColor = javax.swing.UIManager.getColor("Label.disabledForeground");
        if (hintColor != null) {
            typeHint.setForeground(hintColor);
        }

        Runnable applyType = () -> {
            TunnelType t = (TunnelType) type.getSelectedItem();
            boolean remote = t == TunnelType.REMOTE;
            boolean dynamic = t == TunnelType.DYNAMIC;
            listenLabel.setText(remote ? "Remote (server) port:" : "Local listen port:");
            destHost.setEnabled(!dynamic);
            destPort.setEnabled(!dynamic);
            destHostLabel.setEnabled(!dynamic);
            destPortLabel.setEnabled(!dynamic);
            typeHint.setText(typeHint(t));
        };
        type.addActionListener(e -> applyType.run());
        applyType.run();

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        addRow(form, row++, new JLabel("Name:"), name);
        addRow(form, row++, new JLabel("Type:"), type);
        addFullWidth(form, row++, typeHint);
        addRow(form, row++, new JLabel("SSH session:"), session);
        addRow(form, row++, listenLabel, listenPort);
        addRow(form, row++, destHostLabel, destHost);
        addRow(form, row++, destPortLabel, destPort);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(form, BorderLayout.CENTER);
        panel.add(autoStart, BorderLayout.SOUTH);

        JOptionPane pane = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE,
                JOptionPane.OK_CANCEL_OPTION);

        while (true) {
            if (showDialog(parent, pane, title) != JOptionPane.OK_OPTION) {
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

    /** Adds a "label: field" row to a {@link GridBagLayout} form: label hugs left, field fills. */
    private static void addRow(JPanel form, int row, JLabel label, Component field) {
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.gridy = row;
        l.anchor = GridBagConstraints.LINE_END;
        l.insets = new Insets(3, 0, 3, 8);
        form.add(label, l);

        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 1;
        f.gridy = row;
        f.weightx = 1.0;
        f.fill = GridBagConstraints.HORIZONTAL;
        f.insets = new Insets(3, 0, 3, 0);
        form.add(field, f);
    }

    /** Adds a component spanning both columns (e.g. the type hint). */
    private static void addFullWidth(JPanel form, int row, Component comp) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = row;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 6, 0);
        form.add(comp, c);
    }

    /**
     * Shows {@code pane} in a dialog whose title bar carries the tunnel icon, and returns the
     * selected option ({@link JOptionPane#OK_OPTION} etc.). The pane is reused across validation
     * retries, so its value is reset before each showing.
     */
    private static int showDialog(Component parent, JOptionPane pane, String title) {
        pane.setValue(JOptionPane.UNINITIALIZED_VALUE);
        JDialog dialog = pane.createDialog(parent, title);
        dialog.setIconImages(tunnelIconImages());
        // FlatLaf defaults the title-bar icon off for dialogs (showIconInDialogs=false); opt in
        // per-dialog so the icon set above is drawn beside the title.
        dialog.getRootPane().putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON, true);
        dialog.setVisible(true);
        dialog.dispose();
        return pane.getValue() instanceof Integer i ? i : JOptionPane.CLOSED_OPTION;
    }

    /** The tunnel glyph rasterized at title-bar sizes (the WM picks the closest). */
    static List<Image> tunnelIconImages() {
        List<Image> images = new ArrayList<>();
        for (int size : new int[]{16, 20, 24, 32, 48}) {
            FlatSVGIcon icon = new FlatSVGIcon("icons/actions/tunnel.svg", size, size);
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            var g = img.createGraphics();
            icon.paintIcon(null, g, 0, 0);
            g.dispose();
            images.add(img);
        }
        return images;
    }

    /** Short explanation of what the selected tunnel type does (HTML so it wraps). */
    private static String typeHint(TunnelType type) {
        String text = switch (type) {
            case LOCAL -> "Listens on a port on this machine and forwards it through the SSH "
                    + "server to the destination (ssh -L).";
            case REMOTE -> "Listens on a port on the SSH server and forwards it back to a "
                    + "destination reachable from this machine (ssh -R).";
            case DYNAMIC -> "Runs a local SOCKS proxy; apps pointed at it route their traffic "
                    + "through the SSH server (ssh -D).";
        };
        return "<html><body style='width:320px'>" + text + "</body></html>";
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
