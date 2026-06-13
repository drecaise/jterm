package com.katmoda.jterm.ui.component;

import com.katmoda.jterm.session.JumpHostConfig;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * The reusable "Jump Hosts" editor for the SSH session dialog: a dynamic, ordered list of up to
 * {@link #MAX_HOSTS} bastion hops (added/removed on demand) that the connection tunnels through
 * before reaching the target. Hops connect in display order ({@code hop 1} first).
 *
 * <p>Each hop edits host/port/user plus an optional key file and password, mirroring the main
 * host's auth fields; ssh-agent and the default on-disk keys apply to every hop automatically. A
 * typed password is saved to the vault on OK; a blank one falls back to the connect-time prompt.
 * The form preserves each existing hop's id so a saved password survives an edit, and mints a
 * fresh id for newly added hops.</p>
 */
public final class JumpHostsForm {

    /** Maximum number of chained jump hosts a session may declare. */
    public static final int MAX_HOSTS = 4;

    /** A finished hop plus the password typed for it (cleared by the caller after vault handling). */
    public record Result(JumpHostConfig config, char[] password) {
    }

    private final JPanel panel;
    private final JPanel rowsPanel;
    private final JButton addButton;
    private final List<Row> rows = new ArrayList<>();

    public JumpHostsForm(List<JumpHostConfig> initial) {
        this.rowsPanel = new JPanel();
        rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));

        this.addButton = new JButton("Add jump host");
        addButton.addActionListener(a -> addRow(new JumpHostConfig()));
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JLabel hint = new JLabel("Hops are connected in order (hop 1 first), then the target.");
        hint.setEnabled(false);
        bar.add(addButton);

        JScrollPane scroll = new JScrollPane(rowsPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(420, 280));

        this.panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JPanel north = new JPanel(new BorderLayout());
        north.add(bar, BorderLayout.WEST);
        north.add(hint, BorderLayout.SOUTH);
        panel.add(north, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);

        if (initial != null) {
            for (JumpHostConfig jh : initial) {
                addRow(jh);
            }
        }
        refresh();
    }

    /** The Swing component to drop into the dialog's "Jump Hosts" tab. */
    public JComponent component() {
        return panel;
    }

    /**
     * The edited hops (blank-host rows dropped) with the password typed for each. Existing hops
     * keep their id; new hops get a fresh one. Call once on OK.
     */
    public List<Result> results() {
        List<Result> out = new ArrayList<>();
        for (Row r : rows) {
            String host = r.host.getText().trim();
            if (host.isEmpty()) {
                continue;
            }
            JumpHostConfig jh = new JumpHostConfig();
            jh.setId(r.id); // null/blank → new UUID (new hop); otherwise preserved
            jh.setHost(host);
            jh.setUser(r.user.getText().trim());
            try {
                jh.setPort(Integer.parseInt(r.port.getText().trim()));
            } catch (NumberFormatException ex) {
                jh.setPort(22);
            }
            jh.setPasswordAuth(r.passwordAuth.isSelected());
            jh.setKeyPath(r.keyFile.path());
            out.add(new Result(jh, r.password.getPassword()));
        }
        return out;
    }

    private void addRow(JumpHostConfig jh) {
        if (rows.size() >= MAX_HOSTS) {
            return;
        }
        Row row = new Row(jh);
        rows.add(row);
        rowsPanel.add(row.panel);
        refresh();
    }

    private void removeRow(Row row) {
        rows.remove(row);
        rowsPanel.remove(row.panel);
        refresh();
    }

    /** Re-number the hop titles, sync the Add button's enabled state, and re-lay-out. */
    private void refresh() {
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).panel.setBorder(BorderFactory.createTitledBorder("Jump host " + (i + 1)));
        }
        addButton.setEnabled(rows.size() < MAX_HOSTS);
        rowsPanel.revalidate();
        rowsPanel.repaint();
    }

    /** One hop's editing widgets; {@code id} is the source hop's id, or null for a new hop. */
    private final class Row {
        private final String id;
        private final JTextField host = new JTextField();
        private final JTextField port = new JTextField();
        private final JTextField user = new JTextField();
        private final KeyFileField keyFile;
        private final ToggleSwitch passwordAuth;
        private final JPasswordField password = new JPasswordField();
        private final JPanel panel;

        Row(JumpHostConfig jh) {
            this.id = jh.getId();
            host.setText(jh.getHost());
            port.setText(String.valueOf(jh.getPort()));
            user.setText(jh.getUser());
            keyFile = new KeyFileField(jh.getKeyPath());
            passwordAuth = new ToggleSwitch(jh.isPasswordAuth());
            password.putClientProperty("JTextField.placeholderText",
                    jh.isSavePassword() ? "(leave blank to keep saved)" : "(prompt on connect)");

            Runnable syncEnabled = () -> password.setEnabled(passwordAuth.isSelected());
            passwordAuth.addActionListener(a -> syncEnabled.run());
            syncEnabled.run();

            JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
            row(form, "Host:", host);
            row(form, "Port:", port);
            row(form, "User:", user);
            row(form, "Key file:", keyFile.component());
            row(form, "Password auth:", passwordAuth);
            row(form, "Password:", password);

            JButton remove = new JButton("Remove");
            remove.addActionListener(a -> removeRow(this));
            JPanel removeBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            removeBar.add(remove);

            this.panel = new JPanel(new BorderLayout(0, 4));
            panel.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(form, BorderLayout.CENTER);
            panel.add(removeBar, BorderLayout.SOUTH);
            // Keep each hop block at its natural height inside the vertical stack.
            panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        }

        private void row(JPanel form, String label, JComponent field) {
            form.add(new JLabel(label));
            form.add(field);
        }
    }
}
