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

import com.formdev.flatlaf.util.SystemInfo;
import com.katmoda.jterm.dnd.DropRegion;
import com.katmoda.jterm.icon.IconLibrary;
import com.katmoda.jterm.security.CredentialResolver;
import com.katmoda.jterm.session.RdpSessionConfig;
import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.terminal.rdp.HelperSandbox;
import com.katmoda.jterm.terminal.rdp.LocalNetworkAccess;
import com.katmoda.jterm.terminal.rdp.OpenH264;
import com.katmoda.jterm.terminal.rdp.RdpConnection;
import com.katmoda.jterm.terminal.ssh.LocalForward;
import com.katmoda.jterm.terminal.rdp.RdpHelperLocator;
import com.katmoda.jterm.terminal.rdp.RdpKeyMap;
import com.katmoda.jterm.terminal.rdp.RdpKnownHosts;
import com.katmoda.jterm.terminal.rdp.RdpProtocol;
import com.katmoda.jterm.terminal.rdp.audio.JitterBuffer;
import com.katmoda.jterm.terminal.rdp.audio.RdpAudioPlayer;
import com.katmoda.jterm.terminal.rdp.audio.RdpMicrophone;
import com.katmoda.jterm.terminal.rdp.smartcard.Pcsc;
import com.katmoda.jterm.terminal.rdp.smartcard.ScardService;
import com.katmoda.jterm.terminal.rdp.smartcard.SmartCards;
import com.katmoda.jterm.terminal.rdp.RdpStats;
import com.katmoda.jterm.terminal.rdp.drive.DriveDispatcher;
import com.katmoda.jterm.terminal.rdp.files.FileSnapshot;
import com.katmoda.jterm.terminal.rdp.files.RemoteClipboardFiles;
import com.katmoda.jterm.terminal.rdp.files.RemoteFileList;
import com.katmoda.jterm.terminal.rdp.files.RemoteFileTransfer;
import com.katmoda.jterm.terminal.rdp.drive.DriveNames;
import com.katmoda.jterm.terminal.rdp.drive.DriveShare;
import com.katmoda.jterm.terminal.rdp.drive.ProtectedFolders;
import com.katmoda.jterm.terminal.rdp.drive.RdpDriveService;
import com.katmoda.jterm.terminal.rdp.print.AutoPrintLimiter;
import com.katmoda.jterm.terminal.rdp.print.PdfDocuments;
import com.katmoda.jterm.terminal.rdp.print.PrintJob;
import com.katmoda.jterm.terminal.rdp.print.PrintJobFolder;
import com.katmoda.jterm.terminal.rdp.print.PrintJobQueue;
import com.katmoda.jterm.terminal.rdp.print.RdpPrintService;
import com.katmoda.jterm.session.RdpDriveShare;
import com.katmoda.jterm.ui.ErrorDialog;
import com.katmoda.jterm.ui.component.DialogFocus;
import com.katmoda.jterm.ui.component.DropHighlighter;
import com.katmoda.jterm.ui.pane.PaneTitle;
import com.katmoda.jterm.ui.grid.GridContent;
import com.katmoda.jterm.ui.theme.ThemeColors;
import com.formdev.flatlaf.util.UIScale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JMenuItem;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A remote desktop in a {@code PaneGrid} cell, driven by an out-of-process
 * {@code jterm-rdp-helper} through {@link RdpConnection}.
 *
 * <p>The pane owns the connection lifecycle: it connects itself once placed ({@link #start}),
 * resolves the password on the EDT (vault, else prompt), pins certificates through
 * {@link RdpKnownHosts}, and on a disconnect stays in place with Reconnect / Close — it never
 * removes itself, except through Close (which fires the grid's content-ended callback).</p>
 *
 * <p>Threading: frames are copied into the image on the connection's reader thread under the
 * view's lock; everything else is marshalled to the EDT. A {@link #generation} counter discards
 * late callbacks from a connection that has been replaced by a reconnect.</p>
 */
public final class RdpPane extends JPanel implements GridContent {

    private static final Logger LOG = LoggerFactory.getLogger(RdpPane.class);
    private static final String CARD_VIEW = "view";
    private static final String CARD_MESSAGE = "message";
    /** Shown in the grid cell while the desktop view is in its full-screen window. */
    private static final String CARD_FULL_SCREEN = "fullScreen";

    /**
     * What the pane needs from the application. {@code sshForwarder} opens the SSH tunnel for a
     * session that connects through a saved SSH session; {@code null} where that is unavailable.
     * {@code fullScreenStroke} is the current Full Screen shortcut, named on the full-screen bar.
     */
    public record Services(CredentialResolver credentials, RdpKnownHosts knownHosts,
                           Supplier<RdpHelperLocator.Result> helper, SshForwarder sshForwarder,
                           Supplier<javax.swing.KeyStroke> fullScreenStroke) {
    }

    /**
     * Opens a local forward through a saved SSH session (EDT). Credentials are resolved on the EDT,
     * the connect runs off it, and exactly one of the callbacks runs, on the EDT. The forward
     * handed to {@code onReady} belongs to the caller, which must close it.
     */
    @FunctionalInterface
    public interface SshForwarder {
        void open(String sshSessionId, String destHost, int destPort,
                  Consumer<LocalForward> onReady, Consumer<Throwable> onFailure);
    }

    private final RdpSessionConfig cfg;
    private final Services services;
    private final DesktopView view = new DesktopView();
    private final JLabel message = new JLabel("", SwingConstants.CENTER);
    private final JPanel center = new JPanel(new CardLayout());
    private final JLabel status = new JLabel();
    private final JButton reconnect = new JButton("Reconnect");
    private final JButton close = new JButton("Close");
    private final JButton ctrlAltDel = new JButton("Ctrl+Alt+Del");
    private final JButton windowsKey = new JButton("Win");
    private final JButton fullScreenButton = new JButton("Full screen");
    /** The speaker button: shown while connected to a session that plays sound; mutes this pane. */
    private final JButton sound = new JButton();
    /** EDT. The user muted this pane's sound; kept across reconnects, not saved. */
    private boolean soundMuted;
    /**
     * The microphone button: shown while connected to a session that may use the microphone. Shows
     * whether the remote desktop hears it (red while it does) and opens the allow/block/mute menu.
     */
    private final JButton microphone = new JButton();
    /** EDT. The user muted this pane's microphone; kept across reconnects, not saved. */
    private boolean micMuted;
    /** EDT. The tab carries the recording mark; {@link #onTitleChanged} runs when this changes. */
    private boolean recordingMark;
    /** The window the desktop view is in while full screen (EDT); null otherwise. */
    private FullScreenWindow fullScreen;
    /** That window's bar (EDT); null when not full screen. */
    private FullScreenBar fullScreenBar;
    /** "Shared: 2 folders" while connected with shares, so it is always visible what the server can reach. */
    private final JLabel sharedIndicator = new JLabel();
    /**
     * The "Show performance" readout (the status label's menu): frames a second, the codec, and
     * where each frame's time goes ({@link RdpStats}). Hidden unless asked for; not remembered.
     */
    private final JLabel performance = new JLabel();
    /** "Save files... (3 files, 2.1 GB)": the server's copied files, while there are some (D1). */
    private final JButton saveFiles = new JButton();
    /** The server's current file list for {@link #saveFiles} (EDT); null when there is none. */
    private RemoteClipboardFiles.Offer remoteFileOffer;
    /**
     * "Print job: 3 pages": what the remote desktop printed to jterm, while something waits, or a
     * short note after an automatic print or a refused job (printer design). Shown whether connected
     * or not: waiting jobs outlive a disconnect.
     */
    private final JButton printJobsButton = new JButton();
    /** The jobs waiting in this pane across reconnects; null when the session has no printer. */
    private final PrintJobQueue printJobs;
    /** The open job dialog (EDT), so a job arriving meanwhile shows up in it; null when closed. */
    private PrintJobsDialog printDialog;
    /** EDT. "Printed: 3 pages on X" or why a job was refused, shown in the button for a while. */
    private String printNote;
    private final Timer printNoteTimer = new Timer(12_000, e -> {
        printNote = null;
        updateBar();
    });
    /**
     * "Smart card": shown while connected with smart cards on; red while the remote desktop holds a
     * card (S2). Its menu allows or blocks cards for the connection.
     */
    private final JButton smartCardButton = new JButton("Smart card");
    /** EDT. "Card blocked" after wrong PINs, shown in the card button's tooltip for the connection. */
    private String cardNote;
    /** The driver the server renders jobs with: its output is a PDF (printer spike, Windows 11). */
    static final String PRINTER_DRIVER = "Microsoft Print To PDF";
    /** The printer's name on the remote desktop. */
    static final String PRINTER_NAME = "jterm";
    /** A save is running (EDT): the button is disabled meanwhile, so the same list isn't saved twice. */
    private boolean saving;
    /** The folder files were last saved into, offered first next time (EDT). */
    private java.io.File lastSaveFolder;
    /** The folders shared with the current attempt (EDT); empty when none. */
    private List<DriveShare> activeShares = List.of();
    /** Configured folders that could not be shared this time, with the reason (EDT). */
    private List<String> shareProblems = List.of();
    private final DropHighlighter dropHighlighter = new DropHighlighter(this);
    private final Timer resizeDebounce = new Timer(300, e -> requestRemoteResize());
    /**
     * Redraws the "Show performance" readout once a second while it is shown. The helper's statistics
     * arrive only while frames flow, so without this the sound figures froze on a still desktop with
     * music playing. Stopped when the readout is hidden and when the pane closes: a running Swing
     * timer keeps its pane alive.
     */
    private final Timer performanceRefresh = new Timer(1000, e -> refreshPerformance());
    /** EDT. The frame part of the readout from the helper's last statistics, and when it came. */
    private String performanceFrames;
    private long performanceFramesAt;
    /** Frame figures older than this are no longer shown as current. */
    private static final long PERFORMANCE_FRESH_NANOS = 1_500_000_000L;

    /** How long to wait for an answer to macOS's local network prompt before giving up. */
    private static final java.time.Duration LOCAL_NETWORK_WAIT = java.time.Duration.ofMinutes(2);

    private RdpConnection connection;
    /**
     * EDT. True while the current attempt has no helper yet but is under way: its SSH tunnel is
     * opening, or it waits for macOS to grant local network access.
     */
    private boolean preparing;
    /** The current connect attempt's callbacks; owns that attempt's clipboard bridge. */
    private Listener listener;
    private volatile int generation;
    private boolean connected;
    private volatile boolean closed;
    private volatile boolean unicodeInput;
    /** A typed password the user asked to remember, saved once the server accepts it. */
    private char[] pendingRemember;
    /** A typed RD Gateway password to save once connected (as {@link #pendingRemember}). */
    private char[] pendingRememberGateway;
    private Runnable onFocus;
    private Runnable onEnded;
    private Runnable onTitleChanged;
    /** The user's runtime-only name for this tab (already sanitised), or null for the saved name. */
    private String customName;

    public RdpPane(RdpSessionConfig cfg, Services services) {
        super(new BorderLayout());
        this.cfg = cfg;
        this.services = services;
        this.printJobs = cfg.isPrintToJterm() ? new PrintJobQueue(new PrintJobQueue.Listener() {
            @Override
            public void jobAdded(PrintJob job) {
                inspectPrintJob(job);
            }

            @Override
            public void changed() {
                SwingUtilities.invokeLater(RdpPane.this::printJobsChanged);
            }
        }) : null;
        printNoteTimer.setRepeats(false);

        message.setForeground(new Color(0xC8C8C8));
        JPanel messagePanel = new JPanel(new BorderLayout());
        messagePanel.setBackground(Color.BLACK);
        messagePanel.add(message, BorderLayout.CENTER);
        center.add(view, CARD_VIEW);
        center.add(messagePanel, CARD_MESSAGE);
        center.add(buildFullScreenCard(), CARD_FULL_SCREEN);
        add(center, BorderLayout.CENTER);
        add(buildBar(), BorderLayout.SOUTH);

        resizeDebounce.setRepeats(false);
        view.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (cfg.getWidthMode() == RdpSessionConfig.WidthMode.FIT_TO_TAB) {
                    resizeDebounce.restart();
                }
                view.repaint();
            }
        });
        showMessage("Connecting to " + cfg.hostLabel() + "…");
    }

    private static final String RENAME_TIP = "Right-click to rename this tab";

    /** EDT. How far the helper of the current connection could restrict itself (sandbox design). */
    private void showSandbox(HelperSandbox sandbox) {
        status.setToolTipText("<html>" + esc(sandbox.describe()) + "<br>" + RENAME_TIP + "</html>");
    }

    private JComponent buildBar() {
        JPanel bar = new JPanel();
        bar.setLayout(new javax.swing.BoxLayout(bar, javax.swing.BoxLayout.X_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        status.setIcon(IconLibrary.get().icon(tabIconId(), 16));
        status.setToolTipText(RENAME_TIP);
        status.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                popup(e);
            }

            private void popup(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
                    JPopupMenu menu = new JPopupMenu();
                    JMenuItem rename = new JMenuItem("Rename…");
                    rename.addActionListener(a -> promptRename());
                    menu.add(rename);
                    javax.swing.JCheckBoxMenuItem perf = new javax.swing.JCheckBoxMenuItem(
                            "Show performance", performance.isVisible());
                    perf.addActionListener(a -> {
                        performanceFrames = null;
                        performance.setVisible(perf.isSelected());
                        if (perf.isSelected()) {
                            refreshPerformance();
                            performanceRefresh.start();
                        } else {
                            performanceRefresh.stop();
                            performance.setText("");
                        }
                    });
                    menu.add(perf);
                    menu.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });
        printJobsButton.addActionListener(e -> openPrintJobs());
        saveFiles.setToolTipText("Save the files copied on the remote desktop into a folder on this computer");
        saveFiles.addActionListener(e -> saveRemoteFiles());
        fullScreenButton.setToolTipText("Show this remote desktop full screen");
        fullScreenButton.addActionListener(e -> toggleFullScreen());
        sound.addActionListener(e -> toggleSound());
        microphone.addActionListener(e -> showMicrophoneMenu(microphone));
        smartCardButton.addActionListener(e -> showSmartCardMenu(smartCardButton));
        for (JButton b : new JButton[]{printJobsButton, saveFiles, sound, microphone, smartCardButton, fullScreenButton, ctrlAltDel, windowsKey, reconnect, close}) {
            b.setFocusable(false);
            b.putClientProperty("JButton.buttonType", "toolBarButton");
        }
        ctrlAltDel.setToolTipText("Send Ctrl+Alt+Del to the remote desktop");
        ctrlAltDel.addActionListener(e -> {
            if (sendCtrlAltDel()) {
                view.requestFocusInWindow();
            }
        });
        // Java never sees the Windows key on Windows (the OS keeps it), so the remote Start menu
        // needs a button.
        windowsKey.setToolTipText("Press the Windows key on the remote desktop (opens Start)");
        windowsKey.addActionListener(e -> {
            if (sendWindowsKey()) {
                view.requestFocusInWindow();
            }
        });
        reconnect.addActionListener(e -> reconnect());
        close.addActionListener(e -> {
            closeContent();
            if (onEnded != null) {
                onEnded.run(); // the grid removes the cell
            }
        });
        bar.add(status);
        bar.add(Box.createHorizontalStrut(UIScale.scale(12)));
        bar.add(sharedIndicator);
        bar.add(Box.createHorizontalStrut(UIScale.scale(12)));
        performance.setVisible(false);
        performance.setEnabled(false); // greyed: a readout, not a control
        performance.setToolTipText("<html>Per frame: <b>pipe</b> is the helper handing it to jterm, "
                + "<b>copy</b> jterm taking it, <b>paint</b> drawing it.<br>A 30 fps video leaves 33 ms "
                + "for all three. \"scaled\" means the desktop is being resized to fit the pane.</html>");
        bar.add(performance);
        bar.add(Box.createHorizontalGlue());
        bar.add(printJobsButton);
        bar.add(saveFiles);
        bar.add(sound);
        bar.add(microphone);
        bar.add(smartCardButton);
        bar.add(fullScreenButton);
        bar.add(ctrlAltDel);
        bar.add(windowsKey);
        bar.add(reconnect);
        bar.add(close);
        updateBar();
        return bar;
    }

    private void updateBar() {
        String who = (!cfg.getUser().isBlank() ? cfg.getUser() + "@" : "") + cfg.hostLabel();
        status.setText(who + (connected ? "" : " — not connected"));
        ctrlAltDel.setVisible(connected);
        windowsKey.setVisible(connected);
        fullScreenButton.setVisible(connected);
        updateSharedIndicator();
        RemoteClipboardFiles.Offer offer = connected ? remoteFileOffer : null;
        saveFiles.setVisible(offer != null);
        saveFiles.setEnabled(!saving);
        if (offer != null) {
            RemoteFileList l = offer.list();
            saveFiles.setText("Save files… (" + (l.files() == 1 ? "1 file" : String.format("%,d files", l.files()))
                    + (l.folders() > 0 ? ", " + (l.folders() == 1 ? "1 folder" : String.format("%,d folders", l.folders())) : "")
                    + ", " + humanBytes(l.bytes()) + ")");
        }
        String printText = updatePrintButton();
        updateSoundButton();
        MicButtonLook mic = updateMicrophoneButton();
        boolean cardShown = updateSmartCardButton();
        reconnect.setVisible(!connected && connection == null && !preparing);
        close.setVisible(!connected && connection == null && !preparing);
        if (fullScreenBar != null) {
            // The full-screen bar mirrors this one, the shared-folders indicator above all: what the
            // server can reach must stay visible when the pane's own bar is out of sight.
            fullScreenBar.refresh(who, sharedIndicator.isVisible() ? sharedIndicator.getText() : null,
                    sharedIndicator.getToolTipText(), offer != null ? saveFiles.getText() : null, !saving,
                    sound.isVisible() ? sound.getText() : null, sound.getToolTipText(), mic, printText,
                    printJobsButton.getToolTipText(), cardShown ? smartCardButton.getText() : null,
                    smartCardButton.getToolTipText(), smartCardButton.getForeground() == RECORDING_RED);
        }
    }

    /**
     * EDT. The microphone button and the tab's recording mark, from the microphone's status. Returns
     * what the button shows, or null when it is hidden (the full-screen bar mirrors it).
     */
    private MicButtonLook updateMicrophoneButton() {
        Listener l = listener;
        RdpMicrophone mic = l != null ? l.mic : null;
        boolean show = connected && mic != null;
        MicButtonLook look = show ? MicButtonLook.of(mic.status()) : null;
        microphone.setVisible(show);
        if (look != null) {
            microphone.setText(look.text());
            microphone.setToolTipText(look.tip());
            microphone.setForeground(look.recording() ? RECORDING_RED : null);
        }
        boolean mark = look != null && look.recording();
        if (mark != recordingMark) {
            recordingMark = mark;
            if (onTitleChanged != null) {
                onTitleChanged.run(); // the tab shows the mark even from another tab (A3)
            }
        }
        return look;
    }

    private static final Color RECORDING_RED = new Color(MicButtonLook.RECORDING_RGB);

    /** EDT. The print-job button; returns its text, or null when hidden (the full-screen bar mirrors it). */
    private String updatePrintButton() {
        java.util.List<PrintJob> waiting = printJobs != null ? printJobs.jobs() : java.util.List.of();
        String text;
        if (waiting.isEmpty()) {
            text = printNote;
        } else if (waiting.size() == 1) {
            PrintJob.Preview p = waiting.get(0).preview();
            text = "Print job" + (p != null && p.pages() > 0
                    ? ": " + (p.pages() == 1 ? "1 page" : String.format("%,d pages", p.pages())) : "");
        } else {
            text = waiting.size() + " print jobs";
        }
        printJobsButton.setVisible(text != null);
        if (text != null) {
            printJobsButton.setText(text);
            printJobsButton.setToolTipText(printNote != null ? printNote
                    : "Print, save or discard what the remote desktop printed to \"" + PRINTER_NAME + "\"");
        }
        return text;
    }

    /** EDT. A note in the print button for a while: an automatic print, or why a job was refused. */
    private void notePrint(String note) {
        printNote = note;
        printNoteTimer.restart();
        updateBar();
    }

    /** EDT. The queue changed: the bar and an open job dialog follow. */
    private void printJobsChanged() {
        if (printDialog != null) {
            printDialog.refresh();
        }
        updateBar();
    }

    /** EDT. Opens the job dialog, which leaves full screen first (dialogParent). */
    private void openPrintJobs() {
        if (printJobs == null || printJobs.size() == 0 || printDialog != null) {
            return;
        }
        Path scratch;
        try {
            scratch = PrintJobFolder.forThisProcess().scratchDir();
        } catch (java.io.IOException e) {
            ErrorDialog.show(this, "RDP", "Could not open jterm's print job folder:", e);
            return;
        }
        printDialog = new PrintJobsDialog(dialogParent(), printJobs, displayTitle(), scratch, this::notePrint);
        try {
            printDialog.setVisible(true); // modal: returns when closed
        } finally {
            printDialog = null;
            updateBar();
        }
    }

    /**
     * Any thread. A complete job arrived: PDFBox counts its pages and draws page 1, on a thread of its
     * own and within its limits, then the bar shows it, or the session's automatic printer takes it.
     */
    private void inspectPrintJob(PrintJob job) {
        Thread t = new Thread(() -> {
            try {
                job.setPreview(PdfDocuments.inspect(job.file(), PrintJobFolder.forThisProcess().scratchDir()));
            } catch (java.io.IOException e) {
                job.setPreview(new PrintJob.Preview(-1, null, "jterm could not read this document."));
            }
            SwingUtilities.invokeLater(() -> printJobInspected(job));
        }, "rdp-print-inspect");
        t.setDaemon(true);
        t.start();
    }

    /** EDT. A job's preview is in: print it without asking if the session says so and its caps allow. */
    private void printJobInspected(PrintJob job) {
        if (closed || printJobs == null) {
            return;
        }
        printJobsChanged();
        String target = cfg.getAutoPrintPrinter();
        PrintJob.Preview p = job.preview();
        if (target.isBlank() || p == null) {
            return;
        }
        if (!p.printable()) {
            notePrint("A print job waits: " + p.problem());
            return;
        }
        Listener l = listener;
        AutoPrintLimiter.Verdict v = l != null ? l.autoPrint.tryAcquire(p.pages(), System.currentTimeMillis())
                : AutoPrintLimiter.Verdict.TOO_MANY_JOBS;
        if (v != AutoPrintLimiter.Verdict.OK) {
            notePrint("A print job waits for you: " + (v.reason() != null ? v.reason() : "the session ended") + ".");
            return;
        }
        javax.print.PrintService service = null;
        for (javax.print.PrintService s : javax.print.PrintServiceLookup.lookupPrintServices(null, null)) {
            if (s.getName().equals(target)) {
                service = s;
            }
        }
        if (service == null) {
            notePrint("A print job waits for you: the printer \"" + target + "\" isn't available.");
            return;
        }
        javax.print.PrintService chosen = service;
        String pages = p.pages() == 1 ? "1 page" : String.format("%,d pages", p.pages());
        Thread t = new Thread(() -> {
            try {
                java.awt.print.PrinterJob pj = java.awt.print.PrinterJob.getPrinterJob();
                pj.setPrintService(chosen);
                pj.setJobName(displayTitle() + " (remote desktop)");
                PdfDocuments.print(job.file(), PrintJobFolder.forThisProcess().scratchDir(), pj);
                printJobs.remove(job);
                SwingUtilities.invokeLater(() -> notePrint("Printed: " + pages + " on " + target));
            } catch (Exception e) {
                LOG.warn("automatic printing to {} failed", target, e);
                SwingUtilities.invokeLater(() -> notePrint("Automatic printing failed, the job waits: " + e.getMessage()));
            }
        }, "rdp-print-auto");
        t.setDaemon(true);
        t.start();
    }

    /**
     * EDT. This attempt's printer service, or null when the session has no printer or jterm can't
     * keep jobs anywhere (then no printer is announced at all).
     */
    private RdpPrintService newPrintService(int gen) {
        if (printJobs == null) {
            return null;
        }
        try {
            return new RdpPrintService(PrintJobFolder.forThisProcess(), printJobs,
                    why -> SwingUtilities.invokeLater(() -> {
                        if (gen == generation) {
                            notePrint(why);
                        }
                    }));
        } catch (java.io.IOException e) {
            LOG.warn("RDP printing is off for this connection: no print job folder", e);
            return null;
        }
    }

    /** EDT. The microphone button's menu: allow or block for this connection, mute or unmute. */
    private void showMicrophoneMenu(java.awt.Component invoker) {
        Listener l = listener;
        RdpMicrophone mic = l != null ? l.mic : null;
        if (mic == null) {
            return;
        }
        RdpMicrophone.Status st = mic.status();
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        boolean allowed = st.consent() == com.katmoda.jterm.terminal.rdp.audio.MicGate.Consent.ALLOWED;
        javax.swing.JMenuItem allow = new javax.swing.JMenuItem(MicButtonLook.allowItem(st.consent()));
        // A user action: it needs no question, and it answers the one this connection would ask.
        allow.addActionListener(e -> mic.setAllowed(!allowed));
        menu.add(allow);
        javax.swing.JMenuItem mute = new javax.swing.JMenuItem(micMuted ? "Unmute the microphone" : "Mute the microphone");
        mute.addActionListener(e -> {
            micMuted = !micMuted;
            mic.setMuted(micMuted);
        });
        menu.add(mute);
        menu.show(invoker, 0, invoker.getHeight());
    }

    /**
     * EDT. The question the server can cause once per connection (A2): asked when it first opens a
     * microphone stream, defaulting to "Don't allow". Until it is answered the server hears silence.
     */
    private void askMicrophone(int gen, RdpMicrophone mic) {
        if (gen != generation || closed) {
            mic.answer(false);
            return;
        }
        String[] options = {"Allow", "Don't allow"};
        String text = "<html>The remote desktop <b>" + esc(cfg.getName()) + "</b> wants to use this "
                + "computer's microphone.<br><br>Until you allow it, it hears silence. You can change "
                + "this later with the Mic button in the session's bar.<br>jterm asks once per "
                + "connection.</html>";
        int choice = JOptionPane.showOptionDialog(dialogParent(), text, "Microphone — " + cfg.getName(),
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[1]);
        mic.answer(choice == 0 && gen == generation && !closed);
    }

    /** EDT. The smart-card button from the attempt's service; returns whether it shows. */
    private boolean updateSmartCardButton() {
        Listener l = listener;
        ScardService scard = l != null ? l.scard : null;
        boolean show = connected && scard != null;
        smartCardButton.setVisible(show);
        if (!show) {
            return false;
        }
        int open = scard.openCards();
        ScardService.Consent consent = scard.consent();
        smartCardButton.setForeground(open > 0 ? RECORDING_RED : null);
        String state = consent == ScardService.Consent.DENIED ? "Blocked for this connection"
                : open > 0 ? "The remote desktop is using a smart card"
                : consent == ScardService.Consent.ALLOWED ? "Allowed for this connection; no card in use"
                : "jterm asks before the remote desktop can use a card";
        smartCardButton.setToolTipText("<html>" + esc(state) + (cardNote != null ? "<br><b>" + esc(cardNote) + "</b>" : "")
                + "<br>Readers: " + esc(String.join(", ", cfg.getSmartCardReaders())) + "</html>");
        return true;
    }

    /** EDT. Allow or block smart cards for this connection (S2: revocable at any time). */
    private void showSmartCardMenu(java.awt.Component invoker) {
        Listener l = listener;
        ScardService scard = l != null ? l.scard : null;
        if (scard == null) {
            return;
        }
        boolean allowed = scard.consent() == ScardService.Consent.ALLOWED;
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem item = new javax.swing.JMenuItem(allowed
                ? "Block smart cards for this connection" : "Allow smart cards for this connection");
        item.addActionListener(e -> {
            if (allowed) {
                // Resetting the cards drops their verified PIN; off the EDT, PC/SC may take a moment.
                Thread t = new Thread(() -> {
                    scard.block();
                    SwingUtilities.invokeLater(this::updateBar);
                }, "rdp-scard-block");
                t.setDaemon(true);
                t.start();
            } else {
                scard.allow();
                updateBar();
            }
        });
        menu.add(item);
        menu.show(invoker, 0, invoker.getHeight());
    }

    /**
     * EDT. The question the server can cause once per connection (S2), on its first card connect,
     * defaulting to "Don't allow". While it is open the connect waits.
     */
    private boolean askSmartCard(int gen, String reader) {
        if (gen != generation || closed) {
            return false;
        }
        String[] options = {"Allow for this connection", "Don't allow"};
        String text = "<html>The remote desktop <b>" + esc(cfg.getName()) + "</b> wants to use your smart card in "
                + "<b>" + esc(reader) + "</b>.<br><br>It can then sign, decrypt and log on with it, and a PIN you "
                + "type in the remote desktop is visible to that computer.<br>You can block it later with the "
                + "Smart card button in the session's bar. jterm asks once per connection.</html>";
        int choice = JOptionPane.showOptionDialog(dialogParent(), text, "Smart card — " + cfg.getName(),
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        return choice == 0 && gen == generation && !closed;
    }

    /** EDT. S5: after a wrong PIN with one try left, the next attempt needs the user. */
    private boolean askLastPinTry(int gen, String reader) {
        if (gen != generation || closed) {
            return false;
        }
        String[] options = {"Let it try", "Don't let it try"};
        String text = "<html>The remote desktop <b>" + esc(cfg.getName()) + "</b> entered a wrong PIN for the card in "
                + "<b>" + esc(reader) + "</b>.<br><br>One more wrong attempt will <b>block your card</b>.<br>"
                + "Let it try again?</html>";
        int choice = JOptionPane.showOptionDialog(dialogParent(), text, "Smart card PIN — " + cfg.getName(),
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        return choice == 0 && gen == generation && !closed;
    }

    /**
     * EDT. The smart-card service for attempt {@code gen}, or null when the session doesn't use smart
     * cards, picked no reader, or the machine has no PC/SC jterm trusts: then no device is announced.
     */
    private ScardService newSmartCardService(int gen) {
        if (!cfg.isUseSmartCards() || cfg.getSmartCardReaders().isEmpty()) {
            return null;
        }
        java.util.Optional<Pcsc> pcsc = SmartCards.system();
        if (pcsc.isEmpty()) {
            LOG.info("Smart cards are on for {} but no PC/SC is available; none announced", cfg.getName());
            return null;
        }
        cardNote = null;
        return new ScardService(pcsc.get(), cfg.getSmartCardReaders(), new ScardService.Ui() {
            @Override
            public boolean allowCards(String reader) {
                return onEdtAndWait(() -> askSmartCard(gen, reader));
            }

            @Override
            public boolean allowLastPinTry(String reader) {
                return onEdtAndWait(() -> askLastPinTry(gen, reader));
            }

            @Override
            public void pinBlocked(String reader) {
                SwingUtilities.invokeLater(() -> {
                    if (gen == generation) {
                        cardNote = "The card in " + reader + " was blocked by wrong PINs";
                        updateBar();
                    }
                });
            }

            @Override
            public void cardsInUse(int open) {
                SwingUtilities.invokeLater(() -> {
                    if (gen == generation) {
                        updateBar();
                    }
                });
            }
        });
    }

    /** Runs {@code q} on the EDT and waits for its answer; false if that fails (a service thread asks). */
    private static boolean onEdtAndWait(java.util.function.BooleanSupplier q) {
        boolean[] answer = new boolean[1];
        try {
            SwingUtilities.invokeAndWait(() -> answer[0] = q.getAsBoolean());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.lang.reflect.InvocationTargetException e) {
            LOG.warn("Smart-card question failed: {}", e.getCause().toString());
            return false;
        }
        return answer[0];
    }

    /** EDT. The speaker button's state, from the session option, the mute and the player's status. */
    private void updateSoundButton() {
        Listener l = listener;
        RdpAudioPlayer audio = l != null ? l.audio : null;
        boolean show = connected && audio != null;
        sound.setVisible(show);
        if (!show) {
            return;
        }
        RdpAudioPlayer.Status st = audio.status();
        sound.setText(soundMuted ? "Muted" : "Sound");
        String stream = st.deviceFailed() ? "No sound device"
                : st.format() == null ? "No sound playing"
                : st.format().describe() + (st.codec() == RdpProtocol.AUDIO_CODEC_AAC ? ", AAC" : ", PCM");
        sound.setToolTipText("<html>" + esc(stream) + "<br>"
                + (soundMuted ? "Click to hear the remote desktop again" : "Click to mute this remote desktop")
                + "</html>");
    }

    /** EDT. Mutes or unmutes this pane; the sound channel stays open either way. */
    private void toggleSound() {
        soundMuted = !soundMuted;
        Listener l = listener;
        if (l != null && l.audio != null) {
            l.audio.setMuted(soundMuted);
        }
        updateBar();
    }

    /** Shows which folders the server can reach, and which configured ones it can't, while connected. */
    private void updateSharedIndicator() {
        boolean show = connected && (!activeShares.isEmpty() || !shareProblems.isEmpty());
        sharedIndicator.setVisible(show);
        if (!show) {
            return;
        }
        int n = activeShares.size();
        sharedIndicator.setText("Shared: " + n + (n == 1 ? " folder" : " folders")
                + (shareProblems.isEmpty() ? "" : " (" + shareProblems.size() + " not shared)"));
        StringBuilder tip = new StringBuilder("<html>");
        for (DriveShare d : activeShares) {
            tip.append("<b>").append(esc(d.name())).append("</b> = ").append(esc(d.root().toString()))
                    .append(d.readOnly() ? " (read-only)" : " (<b>read-write</b>)").append("<br>");
        }
        for (String p : shareProblems) {
            tip.append("Not shared: ").append(esc(p)).append("<br>");
        }
        sharedIndicator.setToolTipText(tip.append("</html>").toString());
    }

    /**
     * EDT. The readout's text: the frame figures while they are current, and the sound figures read
     * now, whether or not a frame arrived.
     */
    private void refreshPerformance() {
        if (!performance.isVisible()) {
            return;
        }
        Listener l = listener;
        RdpAudioPlayer.Status sound = l != null && l.audio != null ? l.audio.status() : null;
        performance.setText(performanceText(performanceFrames,
                performanceFrames != null && System.nanoTime() - performanceFramesAt < PERFORMANCE_FRESH_NANOS,
                soundSummary(sound)));
    }

    /** The frame part, or what stands in for it when no statistics came lately, then the sound part. */
    static String performanceText(String frames, boolean fresh, String sound) {
        return (fresh ? frames : frames == null ? "waiting for a frame…" : "no new frames") + sound;
    }

    /** The sound part of "Show performance": the stream and where its time went. Empty without sound. */
    static String soundSummary(RdpAudioPlayer.Status st) {
        if (st == null) {
            return "";
        }
        if (st.deviceFailed()) {
            return " · sound: no device";
        }
        if (st.format() == null) {
            return st.badMessages() > 0 ? " · sound: " + st.badMessages() + " refused" : "";
        }
        return " · sound " + st.format().describe() + (st.codec() == RdpProtocol.AUDIO_CODEC_AAC ? " AAC" : "")
                + (st.muted() ? " (muted)" : ", " + st.queuedMs() + " ms queued")
                + (!st.muted() && st.lowestMs() >= 0
                        ? " (lowest " + st.lowestMs() + " ms with the output, aim " + st.marginMs() + ")" : "")
                + (st.trimmedMs() > 0 ? ", " + st.trimmedMs() + " ms cut to catch up" : "")
                + (st.underruns() > 0 ? ", " + st.underruns() + " underruns" : "")
                + (st.droppedMs() > 0 ? ", " + st.droppedMs() + " ms dropped" : "")
                + (st.badMessages() > 0 ? ", " + st.badMessages() + " refused" : "");
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void showMessage(String text) {
        message.setText("<html><div style='text-align:center'>"
                + text.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") + "</div></html>");
        ((CardLayout) center.getLayout()).show(center, CARD_MESSAGE);
    }

    // ------------------------------------------------------------------------------ lifecycle

    /** Connects for the first time. Call on the EDT once the pane is placed. */
    public void start() {
        // Let the layout settle first so a fit-to-pane session asks for the real pane size.
        SwingUtilities.invokeLater(() -> connect(null));
    }

    private void reconnect() {
        connect(null);
    }

    /**
     * The desktop gained focus: whoever copied something locally is about to paste, and the JDK does
     * not announce a new copy of the same kind, so tell the server again (see
     * {@link RdpClipboardBridge}).
     */
    private void refreshClipboard() {
        Listener l = listener;
        if (l != null) {
            l.refreshClipboard();
        }
    }

    /**
     * EDT. A key or mouse button went to the connected desktop; from then on, what the server copies
     * is the user's doing and is brought over (see {@link RdpClipboardBridge#onUserInput}).
     */
    private void userActed() {
        Listener l = listener;
        if (connected && l != null) {
            l.userActed();
        }
    }

    /** EDT. {@code retryError} non-null means "the last password was rejected: prompt again". */
    private void connect(String retryError) {
        if (closed) {
            return;
        }
        RdpHelperLocator.Result located = services.helper().get();
        if (located instanceof RdpHelperLocator.NotFound nf) {
            showMessage(nf.reason());
            updateBar();
            return;
        }
        Path helper = ((RdpHelperLocator.Found) located).executable();

        if (cfg.isViaSsh() && cfg.isViaGateway()) {
            showMessage("This session names both an SSH session and an RD Gateway to connect through. "
                    + "Choose one on the Gateway tab of its settings.");
            updateBar();
            return;
        }
        showMessage(retryError != null ? retryError : "Connecting to " + cfg.hostLabel() + "…");
        CredentialResolver.RdpPassword pw = services.credentials().resolveRdpPassword(cfg, retryError);
        if (pw == null) {
            showMessage("Connection cancelled.");
            updateBar();
            return;
        }
        clearPendingRemember();
        if (pw.remember()) {
            pendingRemember = pw.password().clone();
        }

        int myGeneration = ++generation;
        char[] password = pw.password();
        if (cfg.isViaGateway()) {
            RdpProtocol.Gateway gateway = gateway(retryError);
            if (gateway == null) {
                Arrays.fill(password, '\0');
                clearPendingRemember();
                showMessage("Connection cancelled.");
                updateBar();
                return;
            }
            // The helper's first connection goes to the gateway, so that is what macOS asks about.
            startWhenAllowed(helper, myGeneration, password, cfg.getGatewayHost(), cfg.getGatewayPort(),
                    gateway);
            return;
        }
        if (!cfg.isViaSsh()) {
            startWhenAllowed(helper, myGeneration, password, cfg.getHost(), cfg.getPort(),
                    RdpProtocol.Gateway.NONE);
            return;
        }
        if (services.sshForwarder() == null) {
            Arrays.fill(password, '\0');
            clearPendingRemember();
            showMessage("Connecting through an SSH session isn't available here.");
            updateBar();
            return;
        }
        // Through a saved SSH session: open its connection and a local forward first, then point
        // the helper at the forward. The callbacks may arrive after a Close or a newer attempt.
        preparing = true;
        showMessage("Opening an SSH tunnel to " + cfg.hostLabel() + "…");
        updateBar();
        services.sshForwarder().open(cfg.getSshTunnelSessionId(), cfg.getHost(), cfg.getPort(), fwd -> {
            preparing = false;
            if (closed || myGeneration != generation) {
                fwd.close();
                Arrays.fill(password, '\0');
                return;
            }
            showMessage("Connecting to " + cfg.hostLabel() + " through SSH…");
            startHelper(helper, myGeneration, password, fwd.localHost(), fwd.localPort(), fwd,
                    RdpProtocol.Gateway.NONE);
        }, failure -> {
            preparing = false;
            Arrays.fill(password, '\0');
            if (closed || myGeneration != generation) {
                return;
            }
            clearPendingRemember();
            String why = failure != null && failure.getMessage() != null && !failure.getMessage().isBlank()
                    ? failure.getMessage() : String.valueOf(failure);
            showMessage("Couldn't open the SSH tunnel to " + cfg.hostLabel() + ".\n" + why);
            updateBar();
        });
    }

    /**
     * EDT. Starts the helper for a direct or RD Gateway connection, on macOS only once local network
     * access can't fail its first connect: the JVM connects to {@code firstHost}:{@code firstPort}
     * itself and waits out the privacy prompt (see {@link LocalNetworkAccess}). Through SSH none of
     * this is needed: the helper dials loopback, and the SSH connection already asked.
     */
    private void startWhenAllowed(Path helper, int myGeneration, char[] password, String firstHost,
                                  int firstPort, RdpProtocol.Gateway gateway) {
        if (!LocalNetworkAccess.needed(SystemInfo.isMacOS)) {
            startHelper(helper, myGeneration, password, cfg.getHost(), cfg.getPort(), null, gateway);
            return;
        }
        preparing = true;
        updateBar();
        Thread t = new Thread(() -> {
            LocalNetworkAccess.Outcome outcome = LocalNetworkAccess.await(firstHost, firstPort,
                    LOCAL_NETWORK_WAIT,
                    () -> SwingUtilities.invokeLater(() -> {
                        if (!closed && myGeneration == generation) {
                            showMessage("macOS is asking whether jterm may find devices on your local "
                                    + "network.\nChoose Allow to connect to " + cfg.hostLabel() + ".\n\n"
                                    + "No prompt? Turn on jterm in System Settings → Privacy & Security → "
                                    + "Local Network.");
                        }
                    }),
                    () -> closed || myGeneration != generation);
            SwingUtilities.invokeLater(() -> {
                preparing = false;
                if (closed || myGeneration != generation || outcome == LocalNetworkAccess.Outcome.CANCELLED) {
                    Arrays.fill(password, '\0');
                    return;
                }
                if (outcome == LocalNetworkAccess.Outcome.BLOCKED) {
                    Arrays.fill(password, '\0');
                    clearPendingRemember();
                    showMessage("macOS doesn't allow jterm to connect to devices on your local network.\n"
                            + "Turn on jterm in System Settings → Privacy & Security → Local Network, "
                            + "then press Reconnect.");
                    updateBar();
                    return;
                }
                showMessage("Connecting to " + cfg.hostLabel() + "…");
                startHelper(helper, myGeneration, password, cfg.getHost(), cfg.getPort(), null, gateway);
            });
        }, "rdp-local-network");
        t.setDaemon(true);
        t.start();
    }

    /**
     * EDT. Starts the helper for attempt {@code myGeneration}, dialling {@code connectHost}:
     * {@code connectPort}: the server itself, or the loopback end of {@code forward}, which then
     * belongs to this attempt and is closed with it. {@code password} is zeroed once sent.
     */
    private void startHelper(Path helper, int myGeneration, char[] password, String connectHost,
                             int connectPort, LocalForward forward, RdpProtocol.Gateway gateway) {
        Dimension size = requestedSize();
        boolean h264 = AppSettings.get().isOpenH264Enabled();
        OpenH264.Asset codec = OpenH264.assetForThisMachine();
        OpenH264.updateInBackground(h264, OpenH264.defaultDir(), codec); // outdated: next connect has it
        RdpPrintService printService = newPrintService(myGeneration);
        ScardService scardService = newSmartCardService(myGeneration);
        RdpConnection.Target target = new RdpConnection.Target(connectHost, connectPort,
                cfg.getUser(), cfg.getDomain(), password,
                pins(gateway),
                size.width, size.height, scalePercent(),
                RdpProtocol.clipboardFlags(cfg.isRedirectClipboard(),
                        cfg.isRedirectClipboard() && cfg.isClipboardToRemote(),
                        cfg.isClipboardFilesToRemote(), cfg.isClipboardFilesToLocal()),
                cfg.isAllowTlsWithoutNla() ? RdpProtocol.SEC_ALLOW_TLS : 0,
                // Through a forward the certificate is still checked against the real host.
                forward != null ? cfg.getHost() : "",
                gateway,
                OpenH264.libraryPath(h264, OpenH264.defaultDir(), codec),
                openShares(),
                // Off unless the session asks: then no audio channel exists at all.
                RdpProtocol.audioFlags(cfg.isPlaySound(), cfg.isUseMicrophone(), AppSettings.get().isRdpAacAudio()),
                JitterBuffer.LINE_BUFFER_MS,
                cfg.isUseKerberos(),
                printService == null ? RdpProtocol.Printer.NONE
                        : new RdpProtocol.Printer(RdpProtocol.PRINTER_ENABLED
                                | (cfg.isPrinterIsDefault() ? RdpProtocol.PRINTER_DEFAULT : 0),
                                PRINTER_NAME, PRINTER_DRIVER),
                scardService != null ? RdpProtocol.SMARTCARD_ENABLED : 0);
        try {
            listener = new Listener(myGeneration, forward, activeShares, printService, scardService);
            connection = RdpConnection.start(helper, target, listener);
            listener.attach(connection);
        } catch (Exception e) {
            listener = null;
            if (forward != null) {
                forward.close();
            }
            if (gateway.password() != null) {
                Arrays.fill(gateway.password(), '\0');
            }
            if (printService != null) {
                printService.close();
            }
            if (scardService != null) {
                scardService.close();
            }
            clearPendingRememberGateway();
            Arrays.fill(password, '\0');
            clearPendingRemember();
            connection = null;
            showMessage("Could not start the RDP helper.");
            updateBar();
            ErrorDialog.show(this, "RDP", "Could not start the RDP helper:", e);
            return;
        }
        updateBar();
    }

    /**
     * EDT. The RD Gateway part of CONNECT: reusing the desktop's credentials, or with its own
     * password (vault, else a prompt; re-prompted with {@code retryError} after a rejection, since
     * the server cannot tell jterm which of the two passwords failed). {@code null} if cancelled.
     */
    private RdpProtocol.Gateway gateway(String retryError) {
        clearPendingRememberGateway();
        if (cfg.isGatewaySameCredentials()) {
            return new RdpProtocol.Gateway(cfg.getGatewayHost(), cfg.getGatewayPort(), true, "", "", null);
        }
        CredentialResolver.RdpPassword gw = services.credentials().resolveRdpGatewayPassword(cfg, retryError);
        if (gw == null) {
            return null;
        }
        if (gw.remember()) {
            pendingRememberGateway = gw.password().clone();
        }
        return new RdpProtocol.Gateway(cfg.getGatewayHost(), cfg.getGatewayPort(), false,
                cfg.getGatewayUser(), cfg.getGatewayDomain(), gw.password());
    }

    /** The pinned fingerprints to send: the desktop's, plus the gateway's when there is one. */
    private String pins(RdpProtocol.Gateway gateway) {
        String desktop = services.knownHosts().pinsForConnect(cfg.getHost(), cfg.getPort());
        if (!gateway.enabled()) {
            return desktop;
        }
        String gw = services.knownHosts().pinsForConnect(gateway.host(), gateway.port());
        return desktop.isEmpty() ? gw : gw.isEmpty() ? desktop : desktop + "," + gw;
    }

    private void clearPendingRememberGateway() {
        if (pendingRememberGateway != null) {
            Arrays.fill(pendingRememberGateway, '\0');
            pendingRememberGateway = null;
        }
    }

    /** Device-pixel size to request: the pane (fit mode) or the configured resolution. */
    private Dimension requestedSize() {
        if (cfg.getWidthMode() == RdpSessionConfig.WidthMode.FIXED) {
            return new Dimension(cfg.getWidth(), cfg.getHeight());
        }
        double s = deviceScale();
        int w = view.getWidth() > 0 ? (int) Math.round(view.getWidth() * s) : 1280;
        int h = view.getHeight() > 0 ? (int) Math.round(view.getHeight() * s) : 800;
        // MS-RDPEDISP bounds; the width must be even.
        w = Math.max(200, Math.min(8192, w)) & ~1;
        h = Math.max(200, Math.min(8192, h));
        return new Dimension(w, h);
    }

    private double deviceScale() {
        // The view's monitor, not the pane's: full screen, the view can be on another monitor with
        // another scale.
        GraphicsConfiguration gc = view.getGraphicsConfiguration() != null ? view.getGraphicsConfiguration()
                : getGraphicsConfiguration();
        return gc != null ? gc.getDefaultTransform().getScaleX() : 1.0;
    }

    private int scalePercent() {
        return (int) Math.round(deviceScale() * 100);
    }

    private void requestRemoteResize() {
        if (connected && connection != null && cfg.getWidthMode() == RdpSessionConfig.WidthMode.FIT_TO_TAB) {
            Dimension size = requestedSize();
            connection.requestResize(size.width, size.height, scalePercent());
        }
    }

    /** Drops both pending "remember" passwords: a rejected or abandoned password is never saved. */
    private void clearPendingRemember() {
        if (pendingRemember != null) {
            Arrays.fill(pendingRemember, '\0');
            pendingRemember = null;
        }
        clearPendingRememberGateway();
    }

    /**
     * EDT. Opens the session's shared folders for this attempt and returns how CONNECT announces
     * them (ids and names only). A folder that is missing, protected, or beyond the limit is left
     * out and listed in the indicator's tooltip instead of failing the connection.
     */
    private List<RdpProtocol.Drive> openShares() {
        List<RdpDriveShare> configured = cfg.getDriveShares();
        List<String> names = DriveNames.unique(configured.stream().map(RdpDriveShare::getName).toList());
        List<DriveShare> opened = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<RdpProtocol.Drive> announce = new ArrayList<>();
        for (int i = 0; i < configured.size(); i++) {
            RdpDriveShare d = configured.get(i);
            if (opened.size() >= RdpProtocol.MAX_DRIVES) {
                problems.add(d.getPath() + ": more than " + RdpProtocol.MAX_DRIVES + " folders");
                continue;
            }
            try {
                DriveShare share = DriveShare.open(i + 1, names.get(i), Path.of(d.getPath()), d.isReadOnly(),
                        ProtectedFolders.defaults());
                opened.add(share);
                announce.add(new RdpProtocol.Drive(share.id(), share.name()));
            } catch (Exception e) {
                LOG.warn("not sharing {} with {}: {}", d.getPath(), cfg.hostLabel(), e.getMessage());
                problems.add(d.getPath() + ": " + e.getMessage());
            }
        }
        activeShares = List.copyOf(opened);
        shareProblems = List.copyOf(problems);
        return announce;
    }

    /** Connection callbacks, bound to the connect attempt that created them. */
    private final class Listener implements RdpConnection.Listener {
        private final int gen;
        /** Null when the session does not share its clipboard. */
        private final RdpClipboardBridge clipboard;
        private volatile RdpConnection conn;
        private boolean acted; // EDT
        /** The SSH tunnel this attempt dials through, or null for a direct connection. */
        private final LocalForward forward;
        /** Answers the server's drive requests; null when this attempt shares no folder. */
        private final DriveDispatcher drives;
        /** Receives this attempt's print jobs; null when the session has no printer. */
        private final RdpPrintService print;
        /** The caps on printing without asking, per attempt (printer design D3). */
        private final AutoPrintLimiter autoPrint = new AutoPrintLimiter();
        /** Plays this attempt's sound; null when the session doesn't play sound. */
        private final RdpAudioPlayer audio;
        /**
         * Sends this attempt's microphone (or silence); null when the session doesn't use the
         * microphone. One per attempt, so a reconnect asks again (A2).
         */
        private final RdpMicrophone mic;
        /**
         * Answers this attempt's smart-card calls; null when the session uses none. One per attempt,
         * so a reconnect asks again (S2) and every card it held is reset.
         */
        final ScardService scard;

        Listener(int gen, LocalForward forward, List<DriveShare> shares, RdpPrintService print, ScardService scard) {
            this.gen = gen;
            this.forward = forward;
            this.print = print;
            this.scard = scard;
            this.audio = cfg.isPlaySound() ? new RdpAudioPlayer(() -> SwingUtilities.invokeLater(() -> {
                if (!stale()) {
                    updateBar();
                }
            }), delayMs -> {
                // The play thread. What the server is told about how late the sound is (protocol v11).
                RdpConnection c = conn;
                if (c != null) {
                    c.sendAudioDelay(delayMs);
                }
            }) : null;
            if (audio != null) {
                audio.setMuted(soundMuted);
            }
            this.mic = cfg.isUseMicrophone() ? newMicrophone(gen) : null;
            this.drives = shares.isEmpty() ? null : new DriveDispatcher(new RdpDriveService(shares),
                    (completionId, status, output) -> {
                        RdpConnection c = conn;
                        if (c != null) {
                            c.completeDriveIrp(completionId, status, output);
                        }
                    });
            this.clipboard = cfg.isRedirectClipboard()
                    ? new RdpClipboardBridge(new RdpClipboardBridge.Sink() {
                        @Override
                        public void offer(int kinds) {
                            RdpConnection c = conn;
                            if (c != null) {
                                c.offerClipboard(kinds);
                            }
                        }

                        @Override
                        public void data(int kind, boolean ok, byte[] bytes) {
                            RdpConnection c = conn;
                            if (c != null) {
                                c.sendClipboardData(kind, ok, bytes);
                            }
                        }

                        @Override
                        public void fetch(int kind) {
                            RdpConnection c = conn;
                            if (c != null) {
                                c.fetchClipboard(kind);
                            }
                        }

                        @Override
                        public void fileData(int streamId, boolean ok, byte[] bytes) {
                            RdpConnection c = conn;
                            if (c != null) {
                                c.sendFileData(streamId, ok, bytes);
                            }
                        }

                        @Override
                        public void fetchFile(int streamId, int listIndex, int flags, long position, int cb,
                                              boolean haveId, int id) {
                            RdpConnection c = conn;
                            if (c != null) {
                                c.fetchFile(streamId, listIndex, flags, position, cb, haveId, id);
                            }
                        }

                        @Override
                        public void lockRemote(boolean lock, int id) {
                            RdpConnection c = conn;
                            if (c != null) {
                                c.lockRemoteClipboard(lock, id);
                            }
                        }
                    }, Toolkit.getDefaultToolkit().getSystemClipboard(), true, cfg.isClipboardToRemote(),
                    cfg.isClipboardFilesToRemote(), filePasteConsent(gen), ProtectedFolders.defaults(),
                    cfg.isClipboardFilesToLocal() ? offer -> SwingUtilities.invokeLater(() -> {
                        if (gen == generation) {
                            remoteFileOffer = offer;
                            updateBar();
                        }
                    }) : null)
                    : null;
        }

        /** Called once the helper is running: from here the bridge may talk to it. */
        void attach(RdpConnection c) {
            conn = c;
            if (clipboard != null) {
                clipboard.start();
            }
        }

        void refreshClipboard() {
            if (clipboard != null) {
                clipboard.refresh();
            }
        }

        /** EDT only, like {@code acted}. */
        void userActed() {
            if (clipboard != null && !acted) {
                acted = true;
                clipboard.onUserInput();
            }
        }

        void closeClipboard() {
            if (clipboard != null) {
                clipboard.close();
            }
        }

        /** Closes this attempt's drive service and every file it holds open. Idempotent, any thread. */
        void closeDrives() {
            if (drives != null) {
                drives.close();
            }
            if (print != null) {
                print.close(); // a job still arriving is dropped; complete ones stay in the pane's queue
            }
            if (scard != null) {
                // Cancels waits, resets and disconnects every card, releases the contexts. Off the
                // calling thread: it may wait briefly for a context thread, and this can be the EDT.
                Thread t = new Thread(scard::close, "rdp-scard-close");
                t.setDaemon(true);
                t.start();
            }
        }

        private RdpMicrophone newMicrophone(int gen) {
            RdpMicrophone[] self = new RdpMicrophone[1];
            self[0] = new RdpMicrophone(
                    (streamId, data, off, len) -> {
                        RdpConnection c = conn;
                        if (c != null) {
                            c.sendMicData(streamId, data, off, len);
                        }
                    },
                    () -> SwingUtilities.invokeLater(() -> askMicrophone(gen, self[0])),
                    () -> SwingUtilities.invokeLater(() -> {
                        if (!stale()) {
                            updateBar();
                        }
                    }),
                    micMuted);
            return self[0];
        }

        /**
         * Stops this attempt's sound and microphone: the output leaves the system mixer and the
         * capture line closes. Idempotent, any thread.
         */
        void closeAudio() {
            if (audio != null) {
                audio.close();
            }
            if (mic != null) {
                mic.close();
            }
        }

        /** Closes this attempt's SSH tunnel, if it has one. Idempotent, any thread. */
        void closeForward() {
            if (forward != null) {
                forward.close();
            }
        }

        private boolean stale() {
            return gen != generation;
        }

        @Override
        public void onMessage(RdpProtocol.Inbound msg) {
            switch (msg) {
                case RdpProtocol.Frame f -> view.blit(f); // reader thread, under the view lock
                case RdpProtocol.Connected c -> SwingUtilities.invokeLater(() -> {
                    if (stale()) {
                        return;
                    }
                    connected = true;
                    unicodeInput = c.unicodeInput();
                    view.allocate(c.width(), c.height());
                    ((CardLayout) center.getLayout()).show(center, CARD_VIEW);
                    updateBar();
                    view.requestFocusInWindow();
                    if (pendingRememberGateway != null) {
                        services.credentials().rememberRdpGatewayPassword(cfg, pendingRememberGateway);
                        pendingRememberGateway = null;
                    }
                    if (pendingRemember != null) {
                        services.credentials().rememberRdpPassword(cfg, pendingRemember); // zeroes it
                        pendingRemember = null;
                    }
                    requestRemoteResize(); // the pane may have changed size while connecting
                });
                case RdpProtocol.DesktopResized r -> view.allocate(r.width(), r.height());
                case RdpProtocol.PointerNew p -> view.cursors.put(p.id(), toCursor(p));
                case RdpProtocol.PointerFree p -> view.cursors.remove(p.id());
                case RdpProtocol.PointerSet p -> SwingUtilities.invokeLater(() -> {
                    Cursor c = view.cursors.get(p.id());
                    view.setCursor(c != null ? c : Cursor.getDefaultCursor());
                });
                case RdpProtocol.ClipRemoteOffer o -> {
                    if (clipboard != null) {
                        clipboard.onRemoteOffer(o.kinds());
                    }
                }
                case RdpProtocol.ClipRemoteData d -> {
                    if (clipboard != null) {
                        clipboard.onRemoteData(d.kind(), d.ok(), d.data());
                    }
                }
                case RdpProtocol.ClipRequest r -> {
                    if (clipboard != null) {
                        clipboard.onRequest(r.kind());
                    } else {
                        RdpConnection c = conn; // never leave the server waiting
                        if (c != null) {
                            c.sendClipboardData(r.kind(), false, null);
                        }
                    }
                }
                case RdpProtocol.FileRequest r -> {
                    if (clipboard != null) {
                        clipboard.onFileRequest(r);
                    } else {
                        RdpConnection c = conn;
                        if (c != null) {
                            c.sendFileData(r.streamId(), false, null);
                        }
                    }
                }
                case RdpProtocol.FileRemoteData d -> {
                    if (clipboard != null) {
                        clipboard.onFileRemoteData(d.streamId(), d.ok(), d.data());
                    }
                }
                case RdpProtocol.ClipCaps caps -> {
                    if (clipboard != null) {
                        clipboard.onClipCaps(caps);
                    }
                }
                case RdpProtocol.ClipLock l -> {
                    if (clipboard != null) {
                        clipboard.onLock(l.clipDataId());
                    }
                }
                case RdpProtocol.ClipUnlock u -> {
                    if (clipboard != null) {
                        clipboard.onUnlock(u.clipDataId());
                    }
                }
                case RdpProtocol.DriveIrp irp when irp.driveId() == RdpProtocol.PRINTER_DEVICE_ID -> {
                    // The printer's requests (protocol v13). Never handed to the drive service.
                    RdpPrintService.Reply r = print != null
                            ? print.handle(irp.fileId(), irp.major(), irp.minor(), irp.input())
                            : new RdpPrintService.Reply(com.katmoda.jterm.terminal.rdp.drive.NtStatus.NOT_SUPPORTED,
                                    new byte[irp.major() == 0x04 ? 5 : 4]);
                    RdpConnection c = conn;
                    if (c != null) {
                        c.completeDriveIrp(irp.completionId(), r.ioStatus(), r.output());
                    }
                }
                case RdpProtocol.DriveIrp irp when irp.driveId() == RdpProtocol.SMARTCARD_DEVICE_ID -> {
                    // The smart card's calls (protocol v13): decoded and answered by the service,
                    // which may take long (a status change waits for a card), so off this thread.
                    if (scard != null) {
                        scard.submit(irp.input(), reply -> {
                            RdpConnection c = conn;
                            if (c != null) {
                                c.completeDriveIrp(irp.completionId(), reply.ioStatus(), reply.output());
                            }
                        });
                    } else {
                        RdpConnection c = conn;
                        if (c != null) {
                            c.completeDriveIrp(irp.completionId(),
                                    com.katmoda.jterm.terminal.rdp.drive.NtStatus.NOT_SUPPORTED, new byte[4]);
                        }
                    }
                }
                case RdpProtocol.DriveIrp irp -> {
                    if (drives != null) {
                        drives.submit(irp.driveId(), irp.fileId(), irp.completionId(), irp.major(), irp.minor(),
                                irp.input());
                    } else { // the helper announces no drive without shares; answer anyway, never hang it
                        RdpDriveService.Reply r = NO_DRIVES.handle(irp.driveId(), irp.fileId(), irp.major(),
                                irp.minor(), irp.input());
                        RdpConnection c = conn;
                        if (c != null) {
                            c.completeDriveIrp(irp.completionId(), r.ioStatus(), r.output());
                        }
                    }
                }
                // Sound: the player only validates and enqueues on this (the reader) thread.
                case RdpProtocol.AudioOpen o -> {
                    if (audio != null) {
                        audio.onOpen(o.sampleRate(), o.channels(), o.codec());
                    }
                }
                case RdpProtocol.AudioData d -> {
                    if (audio != null) {
                        audio.onData(d.samples());
                    }
                }
                case RdpProtocol.AudioClose c -> {
                    if (audio != null) {
                        audio.onClose();
                    }
                }
                case RdpProtocol.AudioVolume v -> {
                    if (audio != null) {
                        audio.onVolume(v.left(), v.right());
                    }
                }
                // Microphone: likewise only state changes here; the question goes to the EDT.
                case RdpProtocol.MicOpen o -> {
                    if (mic != null) {
                        mic.onOpen(o.streamId(), o.sampleRate(), o.channels(), o.framesPerPacket() & 0xFFFFFFFFL);
                    }
                }
                case RdpProtocol.MicClose c -> {
                    if (mic != null) {
                        mic.onClose(c.streamId());
                    }
                }
                case RdpProtocol.PointerHide h -> SwingUtilities.invokeLater(() -> view.setCursor(blankCursor()));
                case RdpProtocol.PointerDefault d -> SwingUtilities.invokeLater(
                        () -> view.setCursor(Cursor.getDefaultCursor()));
                default -> { }
            }
        }

        @Override
        public void onSandbox(HelperSandbox sandbox) {
            SwingUtilities.invokeLater(() -> {
                if (!stale()) {
                    showSandbox(sandbox);
                }
            });
        }

        @Override
        public void onStats(RdpStats stats) {
            // The log reader's thread. The view's counters are reset every interval whether or not
            // the readout is shown, so switching it on never shows an average over minutes.
            RdpStats.Local local = view.takeStats();
            SwingUtilities.invokeLater(() -> {
                if (!stale()) {
                    performanceFrames = stats.summary(local);
                    performanceFramesAt = System.nanoTime();
                    refreshPerformance();
                }
            });
        }

        @Override
        public boolean verifyCertificate(RdpProtocol.CertRequest req) {
            boolean[] accept = new boolean[1];
            try {
                SwingUtilities.invokeAndWait(() -> accept[0] = !stale() && !closed && askCertificate(req));
            } catch (Exception e) {
                return false;
            }
            return accept[0];
        }

        @Override
        public void onClosed(RdpProtocol.Disconnected last, String stderrTail) {
            closeAudio();     // the stream is over; the output leaves the system mixer
            closeClipboard(); // this attempt's, whether or not a reconnect has replaced it
            closeForward();   // likewise: the helper has exited, nothing dials the tunnel any more
            closeDrives();    // and nothing can ask for a file any more
            SwingUtilities.invokeLater(() -> {
                if (stale()) {
                    return;
                }
                connection = null;
                connected = false;
                // Before any prompt or message: a reconnect may ask for a password or a certificate,
                // and jterm never asks for anything while full screen (the remote desktop could draw
                // a perfect copy of any prompt there).
                exitFullScreen();
                remoteFileOffer = null; // the server and its clipboard are gone
                view.releaseAllKeys();
                if (closed) {
                    return;
                }
                if (last != null && last.credentialsRejected()) {
                    clearPendingRemember(); // a rejected password is never saved
                    updateBar();
                    connect("The server rejected the user name or password. Try again.");
                    return;
                }
                clearPendingRemember();
                String why = last == null ? "The RDP helper stopped unexpectedly."
                        : last.code() == 0 ? "Disconnected."
                        : last.securityNegotiationFailed() ? negotiationFailure()
                        : "Disconnected: " + last.message();
                if (last == null && !stderrTail.isBlank()) {
                    LOG.warn("RDP helper for {} ended without a reason; its last output:\n{}",
                            cfg.hostLabel(), stderrTail);
                }
                showMessage(why);
                updateBar();
            });
        }
    }

    /** EDT. Shows the certificate prompt; on accept pins the fingerprint. */
    private boolean askCertificate(RdpProtocol.CertRequest req) {
        // Through an SSH tunnel the helper reports the forward's random local port; the pin and the
        // prompt belong to the host the user chose, which is also what pinsForConnect sends. The RD
        // Gateway's certificate is pinned under the gateway's own host:port.
        boolean gateway = req.gateway() && cfg.isViaGateway();
        String host = gateway ? cfg.getGatewayHost() : cfg.isViaSsh() ? cfg.getHost() : req.host();
        int port = gateway ? cfg.getGatewayPort() : cfg.isViaSsh() ? cfg.getPort() : req.port();
        String pinned = services.knownHosts().pinFor(host, port);
        boolean changed = pinned != null;
        StringBuilder text = new StringBuilder();
        if (changed) {
            text.append("WARNING: the certificate of ").append(host).append(':').append(port)
                    .append(" has CHANGED since you trusted it.\n")
                    .append("This can mean someone is intercepting the connection.\n")
                    .append("Only continue if you know the server's certificate was replaced.\n\n")
                    .append("Previously trusted SHA-256:\n").append(pinned).append("\n\n");
        } else {
            text.append("jterm can't verify the identity of ")
                    .append(gateway ? "the RD Gateway " : "").append(host).append(':')
                    .append(port).append(cfg.isViaSsh() ? " (through SSH)" : "").append(".\n\n");
        }
        text.append("Common name: ").append(req.commonName())
                .append("\nSubject: ").append(req.subject())
                .append("\nIssuer: ").append(req.issuer())
                .append("\nSHA-256: ").append(req.sha256Fingerprint());
        if (req.nameMismatch()) {
            text.append("\n\nThe certificate was not issued for this host name (common for self-signed "
                    + "certificates, e.g. GNOME Remote Desktop).");
        }
        text.append("\n\nCompare the fingerprint with the one on the server before trusting it.");
        Object[] options = {changed ? "Trust New Certificate" : "Trust", "Cancel"};
        int choice = JOptionPane.showOptionDialog(dialogParent(), text.toString(), "Verify RDP Server",
                JOptionPane.DEFAULT_OPTION, changed ? JOptionPane.ERROR_MESSAGE : JOptionPane.WARNING_MESSAGE,
                null, options, options[1]);
        if (choice != 0) {
            return false;
        }
        if (!services.knownHosts().pin(host, port, req.sha256Fingerprint())) {
            LOG.warn("could not persist the RDP certificate pin for {}:{}; trusting it for this session only",
                    host, port);
        }
        return true;
    }

    // ----------------------------------------------------------------------------- GridContent

    @Override
    public JComponent ui() {
        return this;
    }

    @Override
    public void closeContent() {
        if (closed) {
            return;
        }
        exitFullScreen();
        closed = true;
        resizeDebounce.stop();
        performanceRefresh.stop();
        printNoteTimer.stop();
        if (printJobs != null) {
            printJobs.clear(); // waiting jobs die with the pane: their files are deleted
        }
        if (printDialog != null) {
            printDialog.dispose();
        }
        clearPendingRemember();
        if (listener != null) {
            listener.closeAudio();
            listener.closeClipboard();
            listener.closeForward();
            listener.closeDrives();
            listener = null;
        }
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    @Override
    public void applyTheme(ThemeColors theme) {
        // Chrome follows FlatLaf; the desktop itself is the remote's colours.
    }

    @Override
    public void focusContent() {
        view.requestFocusInWindow();
    }

    @Override
    public void setOnFocus(Runnable onFocus) {
        this.onFocus = onFocus;
    }

    @Override
    public void setOnContentEnded(Runnable onEnded) {
        this.onEnded = onEnded;
    }

    @Override
    public void showMoveHint() {
        dropHighlighter.showMoveHint();
    }

    @Override
    public void showDropHint(DropRegion region) {
        dropHighlighter.showDropHint(region);
    }

    @Override
    public void clearDropHint() {
        dropHighlighter.clearDropHint();
    }

    @Override
    public String displayTitle() {
        String name = customName != null ? customName : cfg.getName();
        // While the remote desktop hears the microphone, visible from every tab (A3).
        return recordingMark ? MicButtonLook.TAB_MARK + name : name;
    }

    @Override
    public void setOnTitleChanged(Runnable onTitleChanged) {
        this.onTitleChanged = onTitleChanged;
    }

    /**
     * Renames this tab. Runtime only, like a terminal pane's rename: the saved session is never
     * touched and the name dies with the pane. The text goes through {@link PaneTitle#sanitizeName}
     * because a tab title is also rendered from pasted text; blank restores the saved name.
     */
    @Override
    public boolean canRename() {
        return true;
    }

    @Override
    public boolean promptRename() {
        JTextField field = new JTextField(customName == null ? "" : customName, 24);
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JLabel("Name for this connection:"), BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        JLabel hint = new JLabel("Leave blank to use the saved name.");
        hint.setEnabled(false);
        panel.add(hint, BorderLayout.SOUTH);
        if (DialogFocus.showConfirm(dialogParent(), panel, "Rename", field) == JOptionPane.OK_OPTION) {
            customName = PaneTitle.sanitizeName(field.getText());
            if (onTitleChanged != null) {
                onTitleChanged.run();
            }
        }
        return true;
    }

    @Override
    public String tabIconId() {
        return cfg.getIconId() != null ? cfg.getIconId() : "builtin/rdp";
    }

    /**
     * Why negotiation failed, worded for what this session allows. Requiring NLA, the likeliest
     * cause is a server without it (xrdp, or NLA turned off on Windows), so say where to allow TLS.
     */
    private String negotiationFailure() {
        if (!cfg.isAllowTlsWithoutNla()) {
            return "The server doesn't support Network Level Authentication (NLA), which this session "
                    + "requires. If the server can't enable it (xrdp can't), turn on \"Allow TLS without "
                    + "NLA\" on the Security tab of this session's settings.";
        }
        return "Security negotiation with the server failed. jterm uses only NLA or TLS and refuses the "
                + "legacy RDP security layer, which cannot verify the server's identity. A server that "
                + "offers nothing else needs TLS or NLA enabled.";
    }

    /**
     * The RDP pane whose keyboard focus this is, or {@code null}: for the shortcut dispatcher's
     * policy and for the actions aimed at an RDP pane. Full screen, the desktop view is in a
     * {@link FullScreenWindow} and has no {@code RdpPane} ancestor, so that window is asked first;
     * missing it would silently drop the Ctrl+Shift-only policy in full screen.
     */
    public static RdpPane forFocusOwner(java.awt.Component focusOwner) {
        if (focusOwner == null) {
            return null;
        }
        RdpPane p = FullScreenWindow.paneOf(focusOwner);
        return p != null ? p : (RdpPane) SwingUtilities.getAncestorOfClass(RdpPane.class, focusOwner);
    }

    /** Whether keyboard focus is in a full-screen RDP desktop's window. */
    public static boolean inFullScreen(java.awt.Component focusOwner) {
        return FullScreenWindow.paneOf(focusOwner) != null;
    }

    // ------------------------------------------------------------------------- full screen

    /**
     * The full-screen action (shortcut, View menu, the bar buttons): enters full screen while
     * connected, leaves it when already there. {@code false} when there was nothing to do, so the
     * shortcut dispatcher lets the stroke through. EDT.
     */
    public boolean toggleFullScreen() {
        if (fullScreen != null) {
            exitFullScreen();
            return true;
        }
        return enterFullScreen();
    }

    /** Whether the desktop is in its full-screen window right now. EDT. */
    public boolean isFullScreen() {
        return fullScreen != null;
    }

    /**
     * Brings the desktop back into this pane before a dialog that is jterm's, not this pane's (the
     * macOS quit confirmation), under the same rule as {@link #dialogParent}: nothing is asked while
     * full screen. No-op when not full screen. EDT.
     */
    public void leaveFullScreen() {
        exitFullScreen();
    }

    /**
     * Whether quitting would end something: connected, or on the way there (a helper running, or
     * the password and tunnel being prepared). A pane showing Reconnect has nothing left to end.
     * EDT.
     */
    public boolean isSessionOpen() {
        return !closed && (connected || connection != null || preparing);
    }

    /**
     * Moves the desktop view into a {@link FullScreenWindow} on the monitor of this pane's window.
     * The pane stays in its grid cell, showing a placeholder, and keeps the connection, clipboard and
     * drives.
     */
    private boolean enterFullScreen() {
        if (fullScreen != null || !connected || closed) {
            return false;
        }
        java.awt.Window owner = SwingUtilities.getWindowAncestor(this);
        GraphicsConfiguration gc = getGraphicsConfiguration();
        if (owner == null || gc == null) {
            return false;
        }
        fullScreenBar = new FullScreenBar(new FullScreenBar.Actions() {
            @Override
            public void exitFullScreen() {
                RdpPane.this.exitFullScreen();
            }

            @Override
            public void ctrlAltDel() {
                if (sendCtrlAltDel()) {
                    view.requestFocusInWindow();
                }
            }

            @Override
            public void windowsKey() {
                if (sendWindowsKey()) {
                    view.requestFocusInWindow();
                }
            }

            @Override
            public void saveFiles() {
                saveRemoteFiles(); // its folder chooser leaves full screen first (dialogParent)
            }

            @Override
            public void printJobs() {
                openPrintJobs(); // leaves full screen first (dialogParent)
            }

            @Override
            public void toggleSound() {
                RdpPane.this.toggleSound();
            }

            @Override
            public void microphoneMenu(java.awt.Component invoker) {
                showMicrophoneMenu(invoker);
            }

            @Override
            public void smartCardMenu(java.awt.Component invoker) {
                showSmartCardMenu(invoker);
            }
        }, fullScreenShortcutText());
        center.remove(view);
        ((CardLayout) center.getLayout()).show(center, CARD_FULL_SCREEN);
        fullScreen = new FullScreenWindow(this, displayTitle() + " — jterm", owner.getIconImages(), view,
                fullScreenBar, this::exitFullScreen);
        // CardLayout hides the card that was showing when it is removed (next() runs while the view
        // is still a child), so it would arrive in the full-screen window invisible: all black.
        view.setVisible(true);
        updateBar();
        fullScreen.open(gc.getDevice());
        SwingUtilities.invokeLater(() -> {
            view.requestFocusInWindow();
            requestRemoteResize(); // the view's componentResized also asks, after a debounce
        });
        return true;
    }

    /**
     * Brings the desktop view back into this pane and closes the full-screen window. Idempotent; runs
     * before every dialog ({@link #dialogParent}), on disconnect and on close. EDT.
     */
    private void exitFullScreen() {
        FullScreenWindow w = fullScreen;
        if (w == null) {
            return;
        }
        boolean wasActive = w.isActive();
        fullScreen = null;
        fullScreenBar = null;
        center.add(view, CARD_VIEW); // takes it out of the full-screen window
        ((CardLayout) center.getLayout()).show(center, CARD_VIEW);
        center.revalidate();
        center.repaint();
        w.close();
        updateBar();
        if (wasActive) {
            java.awt.Window mine = SwingUtilities.getWindowAncestor(this);
            if (mine != null) {
                mine.toFront();
            }
            SwingUtilities.invokeLater(view::requestFocusInWindow);
        }
    }

    /**
     * The parent for every dialog this pane shows. Leaves full screen first, for two reasons: a
     * dialog owned by the jterm window would open behind the full-screen window and block input to
     * both, and the user can only tell a real jterm prompt from one the remote desktop draws when
     * jterm's own window is around it. Every {@code JOptionPane}, {@code DialogFocus} and file
     * chooser in this package goes through here.
     */
    private java.awt.Component dialogParent() {
        exitFullScreen();
        return this;
    }

    private JComponent buildFullScreenCard() {
        JLabel text = new JLabel("This remote desktop is shown full screen.", SwingConstants.CENTER);
        text.setForeground(new Color(0xC8C8C8));
        JButton back = new JButton("Return");
        back.setToolTipText("Bring the remote desktop back into this pane");
        back.addActionListener(e -> exitFullScreen());
        JPanel inner = new JPanel();
        inner.setOpaque(false);
        inner.setLayout(new javax.swing.BoxLayout(inner, javax.swing.BoxLayout.Y_AXIS));
        text.setAlignmentX(CENTER_ALIGNMENT);
        back.setAlignmentX(CENTER_ALIGNMENT);
        inner.add(text);
        inner.add(Box.createVerticalStrut(UIScale.scale(10)));
        inner.add(back);
        JPanel card = new JPanel(new java.awt.GridBagLayout());
        card.setBackground(Color.BLACK);
        card.add(inner);
        return card;
    }

    private String fullScreenShortcutText() {
        javax.swing.KeyStroke ks = services.fullScreenStroke() != null ? services.fullScreenStroke().get() : null;
        if (ks == null) {
            return null;
        }
        String mods = java.awt.event.InputEvent.getModifiersExText(ks.getModifiers());
        String key = java.awt.event.KeyEvent.getKeyText(ks.getKeyCode());
        return mods.isBlank() ? key : mods + "+" + key;
    }

    // ------------------------------------------------------------------------------ input

    private void send(Runnable action) {
        if (connected && connection != null) {
            action.run();
        }
    }

    /**
     * EDT. Sends Ctrl+Alt+Del to the remote desktop: the bar's button and the Send Ctrl+Alt+Del
     * shortcut. {@code false} when not connected, so the shortcut dispatcher lets the stroke through.
     */
    public boolean sendCtrlAltDel() {
        RdpConnection c = connection;
        if (!connected || c == null) {
            return false;
        }
        // From the shortcut, its own Ctrl and Shift went down remotely before jterm claimed the
        // stroke; released first, or the server would see Ctrl+Shift+Alt+Del. A no-op for the button.
        view.releaseAllKeys();
        int[] keys = {0xA2, 0xA4, 0x2E};
        for (int vk : keys) {
            c.sendKey(true, vk, 0);
        }
        for (int i = keys.length - 1; i >= 0; i--) {
            c.sendKey(false, keys[i], 0);
        }
        return true;
    }

    /**
     * EDT. Taps the left Windows key on the remote desktop: the bar's Win button. {@code false}
     * when not connected.
     */
    public boolean sendWindowsKey() {
        RdpConnection c = connection;
        if (!connected || c == null) {
            return false;
        }
        // A key still held remotely would turn the tap into a chord (Ctrl+Win switches desktops).
        view.releaseAllKeys();
        c.sendKey(true, VK_LWIN, 0);
        c.sendKey(false, VK_LWIN, 0);
        return true;
    }

    /**
     * EDT. "Save files...": asks for a folder, checks it and the free space, then saves the server's
     * current file list there (design decision D1). Nothing was written before this.
     */
    private void saveRemoteFiles() {
        RemoteClipboardFiles.Offer offer = remoteFileOffer;
        Listener l = listener;
        RemoteClipboardFiles files = l != null && l.clipboard != null ? l.clipboard.remoteFiles() : null;
        if (offer == null || files == null || saving) {
            return;
        }
        String title = "Save files from " + cfg.getName();
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(lastSaveFolder);
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
        chooser.setApproveButtonText("Save here");
        if (chooser.showDialog(dialogParent(), "Save here") != javax.swing.JFileChooser.APPROVE_OPTION
                || chooser.getSelectedFile() == null) {
            return;
        }
        Path dest = chooser.getSelectedFile().toPath();
        if (!java.nio.file.Files.isDirectory(dest)) {
            JOptionPane.showMessageDialog(dialogParent(), dest + " is not a folder.", title, JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (ProtectedFolders.classify(dest) == ProtectedFolders.Verdict.REFUSE) {
            JOptionPane.showMessageDialog(dialogParent(), "jterm never saves into its own settings folder or your SSH keys.",
                    title, JOptionPane.WARNING_MESSAGE);
            return;
        }
        String space = RemoteFileTransfer.spaceProblem(dest, offer.list().bytes());
        if (space != null) {
            JOptionPane.showMessageDialog(dialogParent(), space, title, JOptionPane.WARNING_MESSAGE);
            return;
        }
        lastSaveFolder = dest.toFile();
        saving = true;
        updateBar();
        RdpFileDownload.run(this::dialogParent, title, files, offer, dest, () -> {
            saving = false;
            updateBar();
        });
    }

    /**
     * The prompt for a paste of local files on the remote desktop (design decision D2): asked once
     * per copy, on the EDT, with "Don't allow" as the default button. An answer arriving after this
     * attempt ended goes to a closed bridge and does nothing.
     */
    private RdpClipboardBridge.FileConsent filePasteConsent(int gen) {
        return new RdpClipboardBridge.FileConsent() {
            @Override
            public void ask(FileSnapshot.Summary summary, java.util.function.Consumer<Boolean> answer) {
                SwingUtilities.invokeLater(() -> {
                    if (gen != generation) {
                        answer.accept(false);
                        return;
                    }
                    String[] options = {"Allow", "Don't allow"};
                    int choice = JOptionPane.showOptionDialog(dialogParent(), filePasteQuestion(summary),
                            "Paste files on " + cfg.getName(), JOptionPane.DEFAULT_OPTION,
                            JOptionPane.WARNING_MESSAGE, null, options, options[1]);
                    answer.accept(choice == 0 && gen == generation);
                });
            }

            @Override
            public void unavailable(String reason) {
                SwingUtilities.invokeLater(() -> {
                    if (gen == generation) {
                        JOptionPane.showMessageDialog(dialogParent(),
                                "<html><body style='width: " + UIScale.scale(360) + "px'>"
                                        + "The copied files can't be pasted on the remote desktop.<br><br>"
                                        + escapeHtml(reason) + "</body></html>",
                                "Paste files on " + cfg.getName(), JOptionPane.INFORMATION_MESSAGE);
                    }
                });
            }
        };
    }

    /** "The remote desktop wants to paste 3 files and 1 folder (2.1 GB) ..." */
    static String filePasteQuestion(FileSnapshot.Summary s) {
        StringBuilder what = new StringBuilder();
        what.append(count(s.files(), "file", "files"));
        if (s.folders() > 0) {
            what.append(" and ").append(count(s.folders(), "folder", "folders"));
        }
        StringBuilder names = new StringBuilder();
        List<String> top = s.topNames();
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            names.append(i == 0 ? "" : ", ").append(escapeHtml(top.get(i)));
        }
        if (top.size() > 3) {
            names.append(", and ").append(top.size() - 3).append(" more");
        }
        return "<html><body style='width: " + UIScale.scale(360) + "px'>"
                + "The remote desktop wants to paste " + what + " (" + humanBytes(s.bytes())
                + ") from this computer's clipboard:<br><b>" + names + "</b><br><br>"
                + "Allow it to read " + (s.files() + s.folders() == 1 ? "it" : "them") + "?"
                + (s.skipped() > 0 ? "<br><br>" + count(s.skipped(), "link or special file", "links or special files")
                + " will be left out." : "")
                + "</body></html>";
    }

    private static String count(int n, String one, String many) {
        return String.format("%,d", n) + " " + (n == 1 ? one : many);
    }

    static String humanBytes(long b) {
        if (b < 1024) {
            return b + " bytes";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = b;
        int u = -1;
        do {
            v /= 1024;
            u++;
        } while (v >= 1024 && u < units.length - 1);
        return String.format(java.util.Locale.ROOT, "%.1f %s", v, units[u]);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Windows virtual-key code of the left Windows key, as {@link RdpKeyMap} sends it. */
    private static final int VK_LWIN = 0x5B;

    /** Answers drive requests when no folder is shared: every one refused, with a well-formed reply. */
    private static final RdpDriveService NO_DRIVES = new RdpDriveService(List.of());

    private Cursor toCursor(RdpProtocol.PointerNew p) {
        if (p.width() == 0 || p.height() == 0) {
            return Cursor.getDefaultCursor();
        }
        BufferedImage img = new BufferedImage(p.width(), p.height(), BufferedImage.TYPE_INT_ARGB);
        System.arraycopy(p.argb(), 0, ((DataBufferInt) img.getRaster().getDataBuffer()).getData(), 0,
                p.argb().length);
        // The cursor is in device pixels, like the desktop drawn 1:1. AWT stretches any image to the
        // platform's best cursor size (48x48 at 150% on Windows), doubling a 24x24 cursor; pad it
        // onto a transparent best-size canvas instead, shrinking only if it is larger.
        Dimension best = Toolkit.getDefaultToolkit().getBestCursorSize(p.width(), p.height());
        if (best.width <= 0 || best.height <= 0) {
            return Cursor.getDefaultCursor(); // platform has no custom cursors
        }
        double shrink = Math.min(1.0, Math.min((double) best.width / p.width(), (double) best.height / p.height()));
        BufferedImage padded = new BufferedImage(best.width, best.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = padded.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, (int) Math.round(p.width() * shrink), (int) Math.round(p.height() * shrink), null);
        g.dispose();
        if (SystemInfo.isWindows || isX11Toolkit()) {
            // These toolkits make every pixel with any alpha opaque: Windows' translucent pointer
            // shadow came out as a solid black outline. See CursorAlpha.
            CursorAlpha.binarize(((DataBufferInt) padded.getRaster().getDataBuffer()).getData());
        }
        Point hot = new Point((int) Math.min(p.hotX() * shrink, best.width - 1),
                (int) Math.min(p.hotY() * shrink, best.height - 1));
        return Toolkit.getDefaultToolkit().createCustomCursor(padded, hot, "rdp-" + p.id());
    }

    /** AWT's X11 toolkit (1-bit cursor masks), as opposed to JBR's Wayland one (real alpha). */
    private static boolean isX11Toolkit() {
        return Toolkit.getDefaultToolkit().getClass().getName().equals("sun.awt.X11.XToolkit");
    }

    private static Cursor blankCursor;

    private static Cursor blankCursor() {
        if (blankCursor == null) {
            blankCursor = Toolkit.getDefaultToolkit().createCustomCursor(
                    new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new Point(), "rdp-blank");
        }
        return blankCursor;
    }

    /** Paints the remote framebuffer and turns Swing input into RDP input. */
    private final class DesktopView extends JComponent {

        private BufferedImage image;
        private int[] raster;
        // Counters for the performance readout, reset by takeStats(). Guarded by the view's lock.
        private int statBlits;
        private long statBlitNanos;
        private int statPaints;
        private long statPaintNanos;
        private long statPaintMaxNanos;
        private boolean statScaledPaint;
        final Map<Integer, Cursor> cursors = new ConcurrentHashMap<>();
        /** Keys sent down (vk << 8 | flags), released on focus loss so nothing stays stuck remotely. */
        private final Set<Integer> down = new HashSet<>();
        /** Java key codes whose press was deferred to keyTyped (sent as Unicode). */
        private final Set<Integer> deferred = new HashSet<>();
        /**
         * Java key codes of a Cmd+Q press held back from the remote desktop (macOS), so that its
         * release is held back too. Deliberately not cleared by {@link #releaseAllKeys}: the release
         * usually arrives after the quit confirmation has come and gone. Nothing was sent for these,
         * so there is nothing to release remotely.
         */
        private final Set<Integer> quitKeys = new HashSet<>();

        DesktopView() {
            setFocusable(true);
            setFocusTraversalKeysEnabled(false); // Tab belongs to the remote desktop
            setOpaque(true);
            setBackground(Color.BLACK);
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    e.consume();
                    if (RdpKeyMap.isMacQuitStroke(e.getKeyCode(), e.getModifiersEx(), SystemInfo.isMacOS)) {
                        // Cmd+Q is jterm's Quit (the app quit handler, which asks first); as Ctrl+Q it
                        // would quit remote programs too. Whether this view also gets the event was
                        // never measured on a Mac, so it is held back here either way, and its
                        // release with it. Not userActed(): nothing went to the remote desktop.
                        quitKeys.add(e.getKeyCode());
                        return;
                    }
                    // A Q typed normally must be released normally, even if an earlier Cmd+Q's
                    // release never arrived here (the confirmation had focus by then).
                    quitKeys.remove(e.getKeyCode());
                    userActed();
                    if (unicodeInput && !RdpKeyMap.chord(e.getModifiersEx()) && RdpKeyMap.printable(e.getKeyChar())) {
                        deferred.add(e.getKeyCode()); // keyTyped sends it as Unicode
                        return;
                    }
                    RdpKeyMap.WinKey k = RdpKeyMap.toWindows(e.getKeyCode(), e.getKeyLocation(), cmdAsCtrl());
                    if (k.mapped()) {
                        boolean repeat = !down.add((k.vk() << 8) | k.flags());
                        send(() -> connection.sendKey(true, k.vk(), k.flags() | (repeat ? RdpProtocol.KEY_REPEAT : 0)));
                    }
                }

                @Override
                public void keyReleased(KeyEvent e) {
                    e.consume();
                    // Cmd may already be up when the Q is released, so the press decides, and a Q
                    // released with Cmd down whose press went elsewhere is not sent either.
                    if (quitKeys.remove(e.getKeyCode())
                            || RdpKeyMap.isMacQuitStroke(e.getKeyCode(), e.getModifiersEx(), SystemInfo.isMacOS)) {
                        return;
                    }
                    if (deferred.remove(e.getKeyCode())) {
                        return;
                    }
                    RdpKeyMap.WinKey k = RdpKeyMap.toWindows(e.getKeyCode(), e.getKeyLocation(), cmdAsCtrl());
                    if (k.mapped()) {
                        down.remove((k.vk() << 8) | k.flags());
                        send(() -> connection.sendKey(false, k.vk(), k.flags()));
                    }
                }

                @Override
                public void keyTyped(KeyEvent e) {
                    e.consume();
                    char c = e.getKeyChar();
                    if (unicodeInput && !RdpKeyMap.chord(e.getModifiersEx()) && RdpKeyMap.printable(c)) {
                        send(() -> {
                            connection.sendUnicode(true, c);
                            connection.sendUnicode(false, c);
                        });
                    }
                }
            });
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    refreshClipboard();
                    if (onFocus != null) {
                        onFocus.run();
                    }
                }

                @Override
                public void focusLost(FocusEvent e) {
                    releaseAllKeys();
                }
            });
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    userActed();
                    button(e, true);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    button(e, false);
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    move(e);
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    move(e);
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    int notches = e.getWheelRotation();
                    int base = e.isShiftDown() ? RdpProtocol.PTR_HWHEEL : RdpProtocol.PTR_WHEEL;
                    Point p = toRemote(e.getX(), e.getY());
                    // Java: positive = towards the user (scroll down) = negative RDP rotation.
                    int flags = base | (notches > 0 ? RdpProtocol.WHEEL_DOWN : RdpProtocol.WHEEL_UP);
                    for (int i = 0; i < Math.abs(notches); i++) {
                        send(() -> connection.sendMouse(flags, p.x, p.y));
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
        }

        /**
         * macOS: Cmd goes as Ctrl unless Preferences says otherwise. Read per event; changing it
         * opens Preferences, which takes focus and so releases every held key first.
         */
        private boolean cmdAsCtrl() {
            return SystemInfo.isMacOS && AppSettings.get().isRdpCmdAsCtrl();
        }

        void releaseAllKeys() {
            for (int id : down) {
                send(() -> connection.sendKey(false, id >>> 8, id & 0xFF));
            }
            down.clear();
            deferred.clear();
        }

        private void move(MouseEvent e) {
            Point p = toRemote(e.getX(), e.getY());
            send(() -> connection.sendMouse(RdpProtocol.PTR_MOVE, p.x, p.y));
        }

        private void button(MouseEvent e, boolean pressed) {
            int flag = switch (e.getButton()) {
                case MouseEvent.BUTTON1 -> RdpProtocol.PTR_BUTTON1;
                case MouseEvent.BUTTON3 -> RdpProtocol.PTR_BUTTON2; // RDP button 2 is the right button
                case MouseEvent.BUTTON2 -> RdpProtocol.PTR_BUTTON3;
                default -> 0;
            };
            if (flag != 0) {
                Point p = toRemote(e.getX(), e.getY());
                int flags = flag | (pressed ? RdpProtocol.PTR_DOWN : 0);
                send(() -> connection.sendMouse(flags, p.x, p.y));
            }
        }

        /** Where the desktop is drawn: fit inside the view keeping its aspect ratio, centred. */
        private synchronized Rectangle drawRect() {
            if (image == null || getWidth() <= 0 || getHeight() <= 0) {
                return new Rectangle(0, 0, getWidth(), getHeight());
            }
            double s = Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
            int w = (int) Math.round(image.getWidth() * s), h = (int) Math.round(image.getHeight() * s);
            return new Rectangle((getWidth() - w) / 2, (getHeight() - h) / 2, w, h);
        }

        private Point toRemote(int x, int y) {
            BufferedImage img = image;
            Rectangle r = drawRect();
            if (img == null || r.width <= 0 || r.height <= 0) {
                return new Point(0, 0);
            }
            int rx = (int) ((long) (x - r.x) * img.getWidth() / r.width);
            int ry = (int) ((long) (y - r.y) * img.getHeight() / r.height);
            return new Point(Math.max(0, Math.min(img.getWidth() - 1, rx)),
                    Math.max(0, Math.min(img.getHeight() - 1, ry)));
        }

        synchronized void allocate(int w, int h) {
            if (image == null || image.getWidth() != w || image.getHeight() != h) {
                image = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_RGB);
                raster = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            }
            repaint();
        }

        /** Reader thread: copy a dirty rectangle into the image and schedule its repaint. */
        void blit(RdpProtocol.Frame f) {
            Rectangle dirty;
            synchronized (this) {
                if (image == null) {
                    return;
                }
                long t0 = System.nanoTime();
                int iw = image.getWidth(), ih = image.getHeight();
                IntBuffer ints = f.pixels().asIntBuffer();
                int copyW = Math.min(f.width(), iw - f.x());
                for (int row = 0; row < f.height() && f.y() + row < ih && copyW > 0; row++) {
                    ints.position(row * f.width());
                    ints.get(raster, (f.y() + row) * iw + f.x(), copyW);
                }
                Rectangle r = drawRect();
                double sx = (double) r.width / iw, sy = (double) r.height / ih;
                dirty = new Rectangle(r.x + (int) (f.x() * sx) - 1, r.y + (int) (f.y() * sy) - 1,
                        (int) Math.ceil(f.width() * sx) + 2, (int) Math.ceil(f.height() * sy) + 2);
                statBlits++;
                statBlitNanos += System.nanoTime() - t0;
            }
            repaint(dirty);
        }

        /** What this view measured since the last call (any thread). */
        synchronized RdpStats.Local takeStats() {
            RdpStats.Local out = new RdpStats.Local(statBlits, statBlitNanos, statPaints, statPaintNanos,
                    statPaintMaxNanos, statScaledPaint);
            statBlits = 0;
            statBlitNanos = 0;
            statPaints = 0;
            statPaintNanos = 0;
            statPaintMaxNanos = 0;
            statScaledPaint = false;
            return out;
        }

        /**
         * Draws the desktop, pixel for pixel whenever it can.
         *
         * <p>A fit-to-pane session asks the server for exactly the pane's size in <em>device</em>
         * pixels, so on a 150% display the image is 1.5 times the component's size in Swing's
         * units. Drawing it "scaled to the component" under the Graphics' own 1.5 transform lands
         * every pixel where it belongs, but Java2D can't know that and runs a real bilinear scaling
         * loop: 21 to 31 ms for a 2560x1440 frame in software, against 1 ms for a plain copy
         * (measured). That time is spent holding the lock {@link #blit} needs, so it also held up
         * the helper, and through it the acknowledgements the server paces video by. So when the
         * image matches the target in device pixels, the transform is dropped and the image copied
         * as it is. Within two pixels counts as a match: the remote width is forced even
         * (MS-RDPEDISP), and a fractional scale rounds. Only a desktop of another size (a fixed
         * resolution, or a server without Display Control) is really scaled.</p>
         */
        @Override
        protected void paintComponent(Graphics g) {
            long t0 = System.nanoTime();
            Graphics2D g2 = (Graphics2D) g;
            synchronized (this) {
                if (image == null) {
                    g.setColor(getBackground());
                    g.fillRect(0, 0, getWidth(), getHeight());
                    return;
                }
                Rectangle r = drawRect();
                int iw = image.getWidth(), ih = image.getHeight();
                AffineTransform tx = g2.getTransform();
                double deviceW = r.width * tx.getScaleX(), deviceH = r.height * tx.getScaleY();
                boolean pixelForPixel = tx.getShearX() == 0 && tx.getShearY() == 0
                        && tx.getScaleX() > 0 && tx.getScaleY() > 0
                        && Math.abs(deviceW - iw) <= 2 && Math.abs(deviceH - ih) <= 2;
                boolean covered = pixelForPixel && r.x == 0 && r.y == 0 && r.width == getWidth()
                        && r.height == getHeight() && iw >= deviceW - 0.5 && ih >= deviceH - 0.5;
                if (!covered) { // letterbox bars, or the pixel or two a rounded size leaves over
                    g.setColor(getBackground());
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
                if (pixelForPixel) {
                    int dx = (int) Math.round(tx.getTranslateX() + r.x * tx.getScaleX());
                    int dy = (int) Math.round(tx.getTranslateY() + r.y * tx.getScaleY());
                    g2.setTransform(new AffineTransform()); // device pixels; the clip stays as it was
                    try {
                        g2.drawImage(image, dx, dy, null);
                    } finally {
                        g2.setTransform(tx);
                    }
                } else {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(image, r.x, r.y, r.width, r.height, null);
                    statScaledPaint = true;
                }
                long took = System.nanoTime() - t0;
                statPaints++;
                statPaintNanos += took;
                statPaintMaxNanos = Math.max(statPaintMaxNanos, took);
            }
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(UIScale.scale(640), UIScale.scale(400));
        }
    }
}
