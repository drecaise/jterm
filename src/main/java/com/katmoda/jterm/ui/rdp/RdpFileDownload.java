/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.ui.rdp;

import com.formdev.flatlaf.util.UIScale;
import com.katmoda.jterm.terminal.rdp.drive.OriginMark;
import com.katmoda.jterm.terminal.rdp.drive.ProtectedFolders;
import com.katmoda.jterm.terminal.rdp.files.RemoteClipboardFiles;
import com.katmoda.jterm.terminal.rdp.files.RemoteFileTransfer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Saves the remote desktop's copied files into a local folder, with a progress window and Cancel;
 * conflicts are asked about as in an SFTP download (Yes / Yes to All / No / No to All / Cancel).
 * The work itself is {@link RemoteFileTransfer}, which keeps every write inside the folder.
 *
 * <p>The progress window is <em>modeless</em>, unlike the SFTP one: a save can take minutes, and
 * the user may keep working in the remote desktop meanwhile, copying something else there included
 * (a lock keeps the save reading the files it started with).</p>
 */
final class RdpFileDownload {

    private enum Policy { PROMPT, REPLACE_ALL, SKIP_ALL }

    /**
     * Where dialogs go, asked afresh for each one: the pane's {@code dialogParent}, which leaves full
     * screen first. A save started from full screen can outlive it, or the user can go back to full
     * screen during a save; a prompt must never open behind the full-screen window.
     */
    private final Supplier<Component> parent;
    private final String title;
    private volatile boolean cancelled;
    private Policy policy = Policy.PROMPT; // worker thread only
    private JDialog dialog;
    private JLabel statusLabel;
    private JProgressBar bar;

    private RdpFileDownload(Supplier<Component> parent, String title) {
        this.parent = parent;
        this.title = title;
    }

    /**
     * EDT. Starts the transfer and returns at once; {@code onFinished} runs on the EDT once it ended
     * (also when it could not start).
     */
    static void run(Supplier<Component> parent, String title, RemoteClipboardFiles files, RemoteClipboardFiles.Offer offer,
                    Path destination, Runnable onFinished) {
        RemoteFileTransfer transfer;
        RdpFileDownload d = new RdpFileDownload(parent, title);
        try {
            transfer = new RemoteFileTransfer(destination, ProtectedFolders.defaults(),
                    (i, flags, pos, cb) -> files.fetch(offer, i, flags, pos, cb), d.listener(),
                    OriginMark.forThisPlatform());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(parent.get(), e.getMessage(), title, JOptionPane.WARNING_MESSAGE);
            onFinished.run();
            return;
        }
        d.start(files, offer, transfer, onFinished);
    }

    private RemoteFileTransfer.Listener listener() {
        return new RemoteFileTransfer.Listener() {
            @Override
            public RemoteFileTransfer.Decision exists(Path target) {
                return decide(target);
            }

            @Override
            public void progress(String name, long done, long total) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Saving " + name);
                    if (total > 0) {
                        bar.setIndeterminate(false);
                        bar.setValue((int) Math.min(1000, done * 1000 / total));
                    }
                });
            }

            @Override
            public boolean cancelled() {
                return cancelled;
            }
        };
    }

    private void start(RemoteClipboardFiles files, RemoteClipboardFiles.Offer offer, RemoteFileTransfer transfer,
                       Runnable onFinished) {
        SwingWorker<RemoteFileTransfer.Result, Void> worker = new SwingWorker<>() {
            @Override
            protected RemoteFileTransfer.Result doInBackground() {
                files.begin(offer); // keeps the server's lock until the transfer ends
                try {
                    return transfer.run(offer.list());
                } finally {
                    files.end(offer);
                }
            }

            @Override
            protected void done() {
                dialog.dispose();
                onFinished.run();
                RemoteFileTransfer.Result r;
                try {
                    r = get();
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(parent.get(), "The files could not be saved: " + e.getMessage(),
                            title, JOptionPane.ERROR_MESSAGE);
                    return;
                }
                summarize(r, offer.list().leftOut());
            }
        };
        buildDialog();
        dialog.setVisible(true); // modeless: returns at once, the remote desktop stays usable
        worker.execute();
    }

    private void buildDialog() {
        Component p = parent.get();
        Window owner = p == null ? null : SwingUtilities.getWindowAncestor(p);
        dialog = new JDialog(owner, title, Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        statusLabel = new JLabel("Preparing…");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
        bar = new JProgressBar(0, 1000);
        bar.setIndeterminate(true);
        JPanel center = new JPanel(new BorderLayout());
        center.add(statusLabel, BorderLayout.NORTH);
        center.add(bar, BorderLayout.CENTER);
        center.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> {
            cancelled = true;
            cancel.setEnabled(false);
            statusLabel.setText("Stopping…");
        });
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(cancel);
        dialog.add(center, BorderLayout.CENTER);
        dialog.add(south, BorderLayout.SOUTH);
        dialog.setMinimumSize(new Dimension(UIScale.scale(380), 0));
        dialog.pack();
        dialog.setLocationRelativeTo(p);
    }

    /** Worker thread: asks on the EDT, latching the "…to All" answers. */
    private RemoteFileTransfer.Decision decide(Path target) {
        if (policy == Policy.REPLACE_ALL) {
            return RemoteFileTransfer.Decision.REPLACE;
        }
        if (policy == Policy.SKIP_ALL) {
            return RemoteFileTransfer.Decision.SKIP;
        }
        Object[] options = {"Yes", "Yes to All", "No", "No to All", "Cancel"};
        int[] choice = {2};
        try {
            SwingUtilities.invokeAndWait(() -> choice[0] = JOptionPane.showOptionDialog(dialogOver(),
                    "Already exists here:\n" + target + "\n\nReplace it?", title, JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE, null, options, options[2]));
        } catch (Exception e) {
            return RemoteFileTransfer.Decision.SKIP;
        }
        return switch (choice[0]) {
            case 0 -> RemoteFileTransfer.Decision.REPLACE;
            case 1 -> {
                policy = Policy.REPLACE_ALL;
                yield RemoteFileTransfer.Decision.REPLACE;
            }
            case 3 -> {
                policy = Policy.SKIP_ALL;
                yield RemoteFileTransfer.Decision.SKIP;
            }
            case 4 -> RemoteFileTransfer.Decision.CANCEL;
            default -> RemoteFileTransfer.Decision.SKIP;
        };
    }

    private void summarize(RemoteFileTransfer.Result r, int leftOut) {
        StringBuilder sb = new StringBuilder();
        if (r.cancelled()) {
            sb.append("Cancelled. ");
        }
        sb.append(r.written()).append(r.written() == 1 ? " file saved." : " files saved.").append('\n');
        if (leftOut > 0) {
            sb.append("\nLeft out (").append(leftOut).append("): names that can't be used on this computer.\n");
        }
        if (!r.skipped().isEmpty()) {
            sb.append("\nSkipped (").append(r.skipped().size()).append("):\n");
            append(sb, r.skipped());
        }
        if (!r.failed().isEmpty()) {
            sb.append("\nFailed (").append(r.failed().size()).append("):\n");
            append(sb, r.failed());
        }
        int type = r.failed().isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE;
        JOptionPane.showMessageDialog(parent.get(), sb.toString().trim(), title, type);
    }

    /** EDT. The progress window as the owner of a prompt, after leaving full screen. */
    private Component dialogOver() {
        parent.get();
        return dialog;
    }

    private static void append(StringBuilder sb, List<String> items) {
        int shown = Math.min(items.size(), 12);
        for (int i = 0; i < shown; i++) {
            sb.append("  • ").append(items.get(i)).append('\n');
        }
        if (items.size() > shown) {
            sb.append("  … and ").append(items.size() - shown).append(" more\n");
        }
    }
}
