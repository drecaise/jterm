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
package com.katmoda.jterm.ui.sftp;

import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClient.Attributes;
import org.apache.sshd.sftp.client.SftpClient.DirEntry;

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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Runs one recursive SFTP transfer batch (download or upload) off the EDT, behind a small modal
 * progress dialog with a Cancel button. Each batch is one {@link SwingWorker}; per-file overwrites
 * are confirmed on the EDT (Yes / Yes to All / No / No to All / Cancel), with the "…to All" choices
 * latching a policy so deep trees don't nag.
 *
 * <p><b>Security.</b> Filenames in a download come from the remote server, so each component is
 * validated and the resolved local path is asserted to stay inside the chosen destination
 * (no path traversal). Symlinks are never followed during recursion — on either side — which both
 * bounds recursion (no loops) and prevents a link escaping the intended tree.</p>
 */
final class SftpTransfer {

    /** How to treat targets that already exist, once the user picks a "…to All" option. */
    private enum Policy { PROMPT, OVERWRITE_ALL, SKIP_ALL }

    private final Component parent;
    private final SftpClient client;
    private final String title;
    private final Runnable onComplete;

    private volatile boolean cancelled;
    private Policy policy = Policy.PROMPT;
    private final List<String> failures = new ArrayList<>();
    private final List<String> skipped = new ArrayList<>();

    private JDialog dialog;
    private JLabel statusLabel;

    private SftpTransfer(Component parent, SftpClient client, String title, Runnable onComplete) {
        this.parent = parent;
        this.client = client;
        this.title = title;
        this.onComplete = onComplete;
    }

    /**
     * Download the given entries (rooted at {@code remoteBase}) into {@code destDir}, recursing into
     * directories. Must be called on the EDT.
     */
    static void download(Component parent, SftpClient client, String remoteBase,
                         List<DirEntry> entries, Path destDir, Runnable onComplete) {
        SftpTransfer t = new SftpTransfer(parent, client, "Download", onComplete);
        t.start(() -> {
            Path root = destDir.normalize();
            for (DirEntry e : entries) {
                if (t.cancelled) {
                    break;
                }
                Path local = safeResolve(root, e.getFilename());
                if (local == null) {
                    t.failures.add(e.getFilename() + " (unsafe name)");
                    continue;
                }
                t.downloadInto(joinRemote(remoteBase, e.getFilename()), local);
            }
            return null;
        });
    }

    /**
     * Upload the given local files/directories into {@code remoteBase}, recursing into directories.
     * Must be called on the EDT.
     */
    static void upload(Component parent, SftpClient client, String remoteBase,
                       List<File> locals, Runnable onComplete) {
        SftpTransfer t = new SftpTransfer(parent, client, "Upload", onComplete);
        t.start(() -> {
            for (File f : locals) {
                if (t.cancelled) {
                    break;
                }
                t.uploadInto(f.toPath(), joinRemote(remoteBase, f.getName()));
            }
            return null;
        });
    }

    // ---- orchestration ----

    private void start(Callable<Void> body) {
        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                body.call();
                return null;
            }

            @Override
            protected void done() {
                if (dialog != null) {
                    dialog.dispose();
                }
                finish();
            }
        };
        buildDialog();
        worker.execute();
        // Modal: this enters a nested event loop on the EDT (so the worker's done(), status updates,
        // overwrite prompts and the Cancel button all keep dispatching) and returns once disposed.
        dialog.setVisible(true);
    }

    private void buildDialog() {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        statusLabel = new JLabel("Preparing…");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        JPanel center = new JPanel(new BorderLayout());
        center.add(statusLabel, BorderLayout.NORTH);
        center.add(bar, BorderLayout.CENTER);
        center.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> {
            cancelled = true;
            cancel.setEnabled(false);
            setStatus("Finishing current item…");
        });
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(cancel);
        dialog.add(center, BorderLayout.CENTER);
        dialog.add(south, BorderLayout.SOUTH);
        dialog.setMinimumSize(new Dimension(380, 0));
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
    }

    private void finish() {
        StringBuilder sb = new StringBuilder();
        if (cancelled) {
            sb.append("Transfer cancelled.\n");
        }
        if (!skipped.isEmpty()) {
            sb.append("\nSkipped (").append(skipped.size()).append("):\n");
            append(sb, skipped);
        }
        if (!failures.isEmpty()) {
            sb.append("\nFailed (").append(failures.size()).append("):\n");
            append(sb, failures);
        }
        if (sb.length() > 0) {
            int type = failures.isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE;
            JOptionPane.showMessageDialog(parent, sb.toString().trim(), title, type);
        }
        if (onComplete != null) {
            onComplete.run();
        }
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

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> {
            if (statusLabel != null) {
                statusLabel.setText(text);
            }
        });
    }

    // ---- download ----

    private void downloadInto(String remote, Path local) {
        if (cancelled) {
            return;
        }
        Attributes attrs;
        try {
            attrs = client.lstat(remote); // lstat: describe the link itself, don't follow it
        } catch (IOException e) {
            failures.add(remote + " (" + e.getMessage() + ")");
            return;
        }
        if (attrs.isSymbolicLink()) {
            skipped.add(remote + " (symlink)");
            return;
        }
        if (attrs.isDirectory()) {
            try {
                Files.createDirectories(local);
            } catch (IOException e) {
                failures.add(local + " (" + e.getMessage() + ")");
                return;
            }
            for (DirEntry child : readDirSafely(remote)) {
                if (cancelled) {
                    return;
                }
                String name = child.getFilename();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                Path childLocal = safeResolve(local, name);
                if (childLocal == null) {
                    failures.add(remote + "/" + name + " (unsafe name)");
                    continue;
                }
                downloadInto(joinRemote(remote, name), childLocal);
            }
        } else if (attrs.isRegularFile()) {
            downloadFile(remote, local);
        } else {
            skipped.add(remote + " (special file)");
        }
    }

    private Iterable<DirEntry> readDirSafely(String remote) {
        try {
            List<DirEntry> out = new ArrayList<>();
            for (DirEntry e : client.readDir(remote)) {
                out.add(e);
            }
            return out;
        } catch (IOException e) {
            failures.add(remote + " (" + e.getMessage() + ")");
            return List.of();
        }
    }

    private void downloadFile(String remote, Path local) {
        if (Files.exists(local) && !confirmOverwrite(local.toString(), true)) {
            return; // user skipped (or cancelled — the loop checks the flag)
        }
        setStatus("Downloading " + local.getFileName());
        try (InputStream in = client.read(remote)) {
            Files.copy(in, local, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            failures.add(remote + " (" + e.getMessage() + ")");
        }
    }

    // ---- upload ----

    private void uploadInto(Path local, String remote) {
        if (cancelled) {
            return;
        }
        if (Files.isSymbolicLink(local)) {
            skipped.add(local + " (symlink)");
            return;
        }
        if (Files.isDirectory(local)) {
            if (!ensureRemoteDir(remote)) {
                return;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(local)) {
                for (Path child : ds) {
                    if (cancelled) {
                        return;
                    }
                    uploadInto(child, joinRemote(remote, child.getFileName().toString()));
                }
            } catch (IOException e) {
                failures.add(local + " (" + e.getMessage() + ")");
            }
        } else if (Files.isRegularFile(local)) {
            uploadFile(local, remote);
        } else {
            skipped.add(local + " (special file)");
        }
    }

    /** Make sure the remote directory exists (creating it if absent); returns false on failure. */
    private boolean ensureRemoteDir(String remote) {
        try {
            if (remoteExists(remote)) {
                return true; // assume an existing node is usable; a file-vs-dir clash surfaces later
            }
            client.mkdir(remote);
            return true;
        } catch (IOException e) {
            failures.add(remote + " (" + e.getMessage() + ")");
            return false;
        }
    }

    private void uploadFile(Path local, String remote) {
        if (remoteExists(remote) && !confirmOverwrite(remote, false)) {
            return;
        }
        setStatus("Uploading " + local.getFileName());
        try (OutputStream out = client.write(remote, SftpClient.OpenMode.Create,
                SftpClient.OpenMode.Write, SftpClient.OpenMode.Truncate)) {
            Files.copy(local, out);
        } catch (IOException e) {
            failures.add(remote + " (" + e.getMessage() + ")");
        }
    }

    private boolean remoteExists(String remote) {
        try {
            client.lstat(remote);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ---- overwrite policy ----

    /** Returns whether to overwrite {@code target}; updates the latched policy and cancel flag. */
    private boolean confirmOverwrite(String target, boolean local) {
        if (policy == Policy.OVERWRITE_ALL) {
            return true;
        }
        if (policy == Policy.SKIP_ALL) {
            return false;
        }
        int choice = askOverwrite(target, local);
        switch (choice) {
            case 0: // Yes
                return true;
            case 1: // Yes to All
                policy = Policy.OVERWRITE_ALL;
                return true;
            case 3: // No to All
                policy = Policy.SKIP_ALL;
                return false;
            case 4: // Cancel
                cancelled = true;
                return false;
            default: // No, or the dialog was dismissed
                return false;
        }
    }

    /** Shows the overwrite prompt on the EDT and blocks the worker thread until answered. */
    private int askOverwrite(String target, boolean local) {
        String where = local ? "locally" : "on the server";
        String message = "Already exists " + where + ":\n" + target + "\n\nReplace it?";
        Object[] options = {"Yes", "Yes to All", "No", "No to All", "Cancel"};
        int[] result = {2}; // default to "No" if something goes wrong
        try {
            SwingUtilities.invokeAndWait(() -> result[0] = JOptionPane.showOptionDialog(
                    dialog != null ? dialog : parent, message, title,
                    JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, options, options[0]));
        } catch (Exception e) {
            return 2;
        }
        return result[0];
    }

    // ---- path safety ----

    /**
     * Resolves a server-supplied {@code name} under {@code root}, returning null if the name is not a
     * single safe path component or the result would escape {@code root} (path-traversal guard).
     */
    /** Joins a remote POSIX path with a child segment, avoiding a double slash when base is "/". */
    private static String joinRemote(String base, String name) {
        return base.endsWith("/") ? base + name : base + "/" + name;
    }

    private static Path safeResolve(Path root, String name) {
        if (name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            return null;
        }
        Path resolved = root.resolve(name).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }
}
