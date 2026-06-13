package com.katmoda.jterm.ui.tunnel;

import com.katmoda.jterm.session.FolderNode;
import com.katmoda.jterm.session.SessionNode;
import com.katmoda.jterm.session.SessionStore;
import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.session.TunnelConfig;
import com.katmoda.jterm.session.TunnelStore;
import com.katmoda.jterm.terminal.ssh.TunnelManager;
import org.apache.sshd.common.util.net.SshdSocketAddress;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Manages the SSH tunnel collection: a list of all tunnels with start/stop status and
 * <i>New / Edit / Start / Stop / Delete</i>. Mutates and saves {@link TunnelStore} directly and
 * drives start/stop through the hooks supplied by {@code MainWindow} (which owns SSH connecting).
 */
public final class TunnelManagerDialog extends JDialog {

    private final SessionStore sessions;
    private final BiConsumer<TunnelConfig, Runnable> startHook;
    private final Consumer<String> stopHook;

    private final DefaultListModel<TunnelConfig> model = new DefaultListModel<>();
    private final JList<TunnelConfig> list = new JList<>(model);

    private JButton edit;
    private JButton start;
    private JButton stop;
    private JButton delete;

    private TunnelManagerDialog(Window owner, SessionStore sessions,
                                BiConsumer<TunnelConfig, Runnable> startHook,
                                Consumer<String> stopHook) {
        super(owner, "Tunneling", ModalityType.APPLICATION_MODAL);
        this.sessions = sessions;
        this.startHook = startHook;
        this.stopHook = stopHook;
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new TunnelRenderer());
        list.addListSelectionListener(e -> updateButtons());
        reload();
        setContentPane(buildContent());
        updateButtons();
        pack();
        setMinimumSize(new Dimension(520, 340));
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the modal manager. {@code startHook} is called with a tunnel and a refresh callback
     * to run once it is connected; {@code stopHook} is called with a tunnel id to tear it down.
     */
    public static void show(Window owner, SessionStore sessions,
                            BiConsumer<TunnelConfig, Runnable> startHook,
                            Consumer<String> stopHook) {
        new TunnelManagerDialog(owner, sessions, startHook, stopHook).setVisible(true);
    }

    private void reload() {
        TunnelConfig selected = list.getSelectedValue();
        model.clear();
        for (TunnelConfig t : TunnelStore.get().tunnels()) {
            model.addElement(t);
        }
        if (selected != null) {
            for (int i = 0; i < model.size(); i++) {
                if (model.get(i).getId().equals(selected.getId())) {
                    list.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    /** Re-renders rows (running status may have changed) and refreshes button state. */
    private void refresh() {
        list.repaint();
        updateButtons();
    }

    private JPanel buildContent() {
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 6));

        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 6));
        buttons.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 12));
        JButton add = new JButton("New…");
        add.addActionListener(e -> newTunnel());
        edit = new JButton("Edit…");
        edit.addActionListener(e -> editTunnel());
        start = new JButton("Start");
        start.addActionListener(e -> startTunnel());
        stop = new JButton("Stop");
        stop.addActionListener(e -> stopTunnel());
        delete = new JButton("Delete");
        delete.addActionListener(e -> deleteTunnel());
        buttons.add(add);
        buttons.add(edit);
        buttons.add(start);
        buttons.add(stop);
        buttons.add(delete);

        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel closeBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        closeBar.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
        closeBar.add(close);

        JPanel content = new JPanel(new BorderLayout());
        content.add(scroll, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.EAST);
        content.add(closeBar, BorderLayout.SOUTH);
        return content;
    }

    private void updateButtons() {
        TunnelConfig sel = list.getSelectedValue();
        boolean has = sel != null;
        boolean running = has && TunnelManager.get().isRunning(sel.getId());
        edit.setEnabled(has && !running);
        delete.setEnabled(has && !running);
        start.setEnabled(has && !running);
        stop.setEnabled(running);
    }

    private void newTunnel() {
        TunnelConfig created = TunnelEditDialog.edit(this, new TunnelConfig(),
                sshSessions(), "New Tunnel");
        if (created != null) {
            TunnelStore.get().add(created);
            TunnelStore.get().save();
            reload();
            list.setSelectedValue(created, true);
        }
    }

    private void editTunnel() {
        TunnelConfig selected = list.getSelectedValue();
        if (selected == null) {
            return;
        }
        TunnelConfig edited = TunnelEditDialog.edit(this, selected.copy(),
                sshSessions(), "Edit Tunnel");
        if (edited != null) {
            TunnelStore.get().replace(edited);
            TunnelStore.get().save();
            reload();
            list.setSelectedValue(edited, true);
        }
    }

    private void startTunnel() {
        TunnelConfig selected = list.getSelectedValue();
        if (selected == null) {
            return;
        }
        startHook.accept(selected, this::refresh);
    }

    private void stopTunnel() {
        TunnelConfig selected = list.getSelectedValue();
        if (selected == null) {
            return;
        }
        stopHook.accept(selected.getId());
        refresh();
    }

    private void deleteTunnel() {
        TunnelConfig selected = list.getSelectedValue();
        if (selected == null) {
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete tunnel \"" + selected.getName() + "\"?", "Tunneling",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm == JOptionPane.OK_OPTION) {
            TunnelStore.get().remove(selected);
            TunnelStore.get().save();
            reload();
        }
    }

    /** All SSH sessions saved in the sidebar tree, flattened in tree order. */
    private List<SshSessionConfig> sshSessions() {
        List<SshSessionConfig> out = new ArrayList<>();
        collectSessions(sessions.root(), out);
        return out;
    }

    private static void collectSessions(FolderNode folder, List<SshSessionConfig> out) {
        for (SessionNode node : folder.getChildren()) {
            if (node instanceof SshSessionConfig ssh) {
                out.add(ssh);
            } else if (node instanceof FolderNode child) {
                collectSessions(child, out);
            }
        }
    }

    /** Renders a tunnel as name, type, route and running status. */
    private static final class TunnelRenderer extends javax.swing.DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof TunnelConfig t) {
                setText(describe(t));
                setToolTipText(route(t));
            }
            return this;
        }

        /** Green used for the "running" status dot; readable on both light and dark themes. */
        private static final String RUNNING_DOT = "#3FB950";

        private static String describe(TunnelConfig t) {
            boolean running = TunnelManager.get().isRunning(t.getId());
            String status;
            if (running) {
                SshdSocketAddress bound = TunnelManager.get().boundAddress(t.getId());
                String where = bound != null
                        ? bound.getHostName() + ":" + bound.getPort() : "active";
                status = "<font color='" + RUNNING_DOT + "'>&#9679;</font> running ("
                        + escape(where) + ")";
            } else {
                status = "&#9675; stopped";
            }
            return "<html>" + escape(t.getName()) + "  —  " + escape(t.getType().label())
                    + "  —  " + status + "</html>";
        }

        private static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        private static String route(TunnelConfig t) {
            return switch (t.getType()) {
                case LOCAL -> "127.0.0.1:" + t.getListenPort() + " → "
                        + t.getDestHost() + ":" + t.getDestPort() + " (via server)";
                case REMOTE -> "server 127.0.0.1:" + t.getListenPort() + " → "
                        + t.getDestHost() + ":" + t.getDestPort() + " (on this machine)";
                case DYNAMIC -> "SOCKS proxy on 127.0.0.1:" + t.getListenPort();
            };
        }
    }
}
