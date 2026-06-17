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
package com.katmoda.jterm.ui.pane;

import com.jediterm.core.compatibility.Point;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.model.SelectionUtil;
import com.katmoda.jterm.broadcast.BroadcastingTtyConnector;
import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.dnd.DropRegion;
import com.katmoda.jterm.dnd.PaneTransferable;
import com.katmoda.jterm.highlight.CompiledHighlightList;
import com.katmoda.jterm.highlight.HighlightList;
import com.katmoda.jterm.highlight.HighlightListResolver;
import com.katmoda.jterm.highlight.HighlightingInstaller;
import com.katmoda.jterm.terminal.TerminalSession;
import com.katmoda.jterm.ui.SessionIcon;
import com.katmoda.jterm.ui.grid.GridContent;
import com.katmoda.jterm.ui.theme.JTermSettingsProvider;
import com.katmoda.jterm.ui.theme.ThemeColors;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragGestureListener;
import java.awt.dnd.DragSource;
import java.awt.dnd.InvalidDnDOperationException;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

/**
 * A single terminal cell: a JediTerm widget driving one {@link TerminalSession}.
 *
 * <p>The JediTerm widget is wired to the connector handed in by the grid (a broadcast
 * wrapper around the session's real connector). When broadcast mode is active the pane
 * shows a bottom title bar with a checkbox controlling whether it participates.</p>
 */
public final class TerminalPane extends JPanel implements GridContent {

    private TerminalSession session;
    private final JtermJediTermWidget widget;
    private final JTermSettingsProvider settingsProvider;
    private final TtyConnector inputConnector;
    private ThemeColors theme;

    private final JPanel bottomArea;
    private final JPanel broadcastBar;
    private final JCheckBox broadcastCheck;
    private final JLabel titleLabel;
    private final Timer titleTimer;

    private Runnable onFocus;
    private Runnable onSessionEnd;
    private Runnable onBroadcastToggle;
    private Runnable highlightTeardown;
    private Border savedBorder;
    private boolean stopped;
    private JPanel stoppedPanel;
    /** Repopulates the stopped screen's hint lines; used to restore them after a failed reconnect. */
    private Runnable stoppedHintsPopulator;
    /** Set once Return/R is taken on the stopped screen, so the action and its status show only once. */
    private boolean stoppedActionTaken;
    // Stopped-screen state shared by the strip's key bindings and the global dispatcher path
    // (handleStoppedKey), so both funnel through one implementation. Populated by buildStoppedPanel.
    private Box stoppedLines;
    private Font stoppedFont;
    private Color stoppedCyan;
    private Color stoppedMagenta;
    private Runnable stoppedOnExit;
    private Runnable stoppedOnRestart;
    private PaneActivity activity = PaneActivity.NONE;

    public TerminalPane(TerminalSession session, ThemeColors theme, TtyConnector connector) {
        super(new BorderLayout());
        this.session = session;
        this.inputConnector = connector;
        this.theme = theme;
        var profile = session.profile();
        this.settingsProvider = new JTermSettingsProvider(theme, profile.fontFamily(), profile.fontSize());
        this.widget = new JtermJediTermWidget(settingsProvider);
        this.widget.setTtyConnector(connector);
        this.widget.start();
        add(widget, BorderLayout.CENTER);
        installCopyOnSelect();
        installHighlighting();

        this.broadcastCheck = new JCheckBox((String) null, true);
        this.broadcastCheck.setToolTipText("Include this pane in broadcast input");
        // The checkbox only makes sense while broadcasting; the bar itself is always shown.
        this.broadcastCheck.setVisible(false);
        // Toggling participation re-decorates the grid (highlight enabled panes, dim excluded ones).
        this.broadcastCheck.addActionListener(e -> {
            if (onBroadcastToggle != null) {
                onBroadcastToggle.run();
            }
        });
        this.titleLabel = new JLabel(session.title(), SessionIcon.forSession(session, 16), SwingConstants.LEADING);
        this.titleLabel.setIconTextGap(6);
        this.broadcastBar = new JPanel(new BorderLayout(6, 0));
        this.broadcastBar.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        this.broadcastBar.add(broadcastCheck, BorderLayout.WEST);
        this.broadcastBar.add(titleLabel, BorderLayout.CENTER);
        this.broadcastBar.add(buildMenuButton(), BorderLayout.EAST);
        installPaneMenuTrigger();
        // The bar sits permanently at the bottom; the session-stopped panel (when shown) stacks
        // above it in the same container so the two never fight over BorderLayout.SOUTH.
        this.bottomArea = new JPanel(new BorderLayout());
        this.bottomArea.add(broadcastBar, BorderLayout.SOUTH);
        add(bottomArea, BorderLayout.SOUTH);
        installPaneDragSource();
        // Keep the title (e.g. a local shell's CWD) up to date while the pane is live.
        this.titleTimer = new Timer(800, e -> refreshTitle());
        this.titleTimer.setRepeats(true);
        this.titleTimer.start();

        widget.addListener(w -> {
            if (onSessionEnd != null) {
                SwingUtilities.invokeLater(onSessionEnd);
            }
        });
        widget.getTerminalPanel().addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (onFocus != null) {
                    onFocus.run();
                }
            }
        });
    }

    public TerminalSession session() {
        return session;
    }

    /** This pane's background-activity state, shown on its tab while the tab isn't in front. */
    public PaneActivity activity() {
        return activity;
    }

    public void setActivity(PaneActivity activity) {
        this.activity = activity;
    }

    /** The real (unwrapped) connector, used as broadcast source/target identity. */
    public TtyConnector realConnector() {
        return session.connector();
    }

    /**
     * The connector terminal input is written through (the grid's broadcasting wrapper).
     * Macros run on this so they respect broadcast mode, like manual typing.
     */
    public TtyConnector inputConnector() {
        return inputConnector;
    }

    public String title() {
        return session.title();
    }

    public void setOnFocus(Runnable onFocus) {
        this.onFocus = onFocus;
    }

    public void setOnSessionEnd(Runnable onSessionEnd) {
        this.onSessionEnd = onSessionEnd;
    }

    /** Fired when this pane's broadcast checkbox is toggled, so the grid can re-decorate borders. */
    public void setOnBroadcastToggle(Runnable onBroadcastToggle) {
        this.onBroadcastToggle = onBroadcastToggle;
    }

    // ---- session-stopped screen ----

    /** Whether the stopped overlay is currently shown (the backend session has ended). */
    public boolean isStopped() {
        return stopped;
    }

    /**
     * Replaces nothing — keeps the dead terminal's final output visible and appends a
     * "Session stopped" panel below it (a separator plus key hints). {@code onExit} removes the
     * pane and {@code onRestart} reopens a fresh session in its place; saving the output is
     * handled internally. The panel grabs focus and binds Return / R / S.
     */
    public void showSessionStopped(Runnable onExit, Runnable onRestart) {
        if (stopped) {
            return;
        }
        stopped = true;
        stoppedActionTaken = false;
        titleTimer.stop();
        JPanel panel = buildStoppedPanel(onExit, onRestart);
        stoppedPanel = panel;
        bottomArea.add(panel, BorderLayout.CENTER);
        revalidate();
        repaint();
        SwingUtilities.invokeLater(panel::requestFocusInWindow);
    }

    /**
     * Reuse this pane's widget — and its scrollback — with a freshly created session (the R /
     * restart action). The old transport is closed, the broadcast wrapper is repointed at the new
     * connector (keeping the same wrapper object, so broadcast registration and the widget binding
     * stay intact), the "Session stopped" overlay is removed, and the widget is restarted so the new
     * session's output appends below the preserved history. Called on the EDT once the new session
     * is connected.
     */
    public void reconnect(TerminalSession newSession) {
        session.close();
        this.session = newSession;
        if (inputConnector instanceof BroadcastingTtyConnector b) {
            b.setReal(newSession.connector());
        }
        stopped = false;
        if (stoppedPanel != null) {
            bottomArea.remove(stoppedPanel);
            stoppedPanel = null;
        }
        titleLabel.setText(session.title());
        titleLabel.setIcon(SessionIcon.forSession(session, 16));
        titleTimer.start();
        widget.restartWith(inputConnector);
        revalidate();
        repaint();
        focusTerminal();
    }

    private JPanel buildStoppedPanel(Runnable onExit, Runnable onRestart) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(true);
        panel.setBackground(theme.background());
        panel.add(new JSeparator(SwingConstants.HORIZONTAL), BorderLayout.NORTH);

        Color red = ansi(1, theme.foreground());
        Color cyan = ansi(6, theme.foreground());
        Color magenta = ansi(5, theme.foreground());
        Font mono = new Font(session.profile().fontFamily(), Font.PLAIN, session.profile().fontSize());

        Box lines = Box.createVerticalBox();
        // Stash everything handleStoppedKey needs so the global dispatcher can drive the same
        // actions as the strip's own key bindings (see handleStoppedKey).
        this.stoppedLines = lines;
        this.stoppedFont = mono;
        this.stoppedCyan = cyan;
        this.stoppedMagenta = magenta;
        this.stoppedOnExit = onExit;
        this.stoppedOnRestart = onRestart;
        lines.setOpaque(true);
        lines.setBackground(theme.background());
        lines.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        // Captured so a failed reconnect can swap the transient "Reconnecting…" status back to the
        // hints (see restoreStoppedScreen).
        stoppedHintsPopulator = () -> {
            lines.removeAll();
            lines.add(label("<html><b style='color:" + hex(red) + "'>Session stopped</b></html>", mono));
            lines.add(label(hintLine("&lt;Return&gt;", cyan, " to exit tab"), mono));
            lines.add(label(hintLine("R", magenta, " to restart session"), mono));
            lines.add(label(hintLine("S", magenta, " to save terminal output to file"), mono));
            lines.revalidate();
            lines.repaint();
        };
        stoppedHintsPopulator.run();
        panel.add(lines, BorderLayout.CENTER);

        panel.setFocusable(true);
        // Both the strip's bindings (when it holds focus) and the window's global dispatcher (when
        // focus is on the dead terminal) funnel through handleStoppedKey, so the once-only guard and
        // the transient "Closing…"/"Reconnecting…" status behave identically either way.
        bindKey(panel, java.awt.event.KeyEvent.VK_ENTER, () -> handleStoppedKey(java.awt.event.KeyEvent.VK_ENTER));
        bindKey(panel, java.awt.event.KeyEvent.VK_R, () -> handleStoppedKey(java.awt.event.KeyEvent.VK_R));
        bindKey(panel, java.awt.event.KeyEvent.VK_S, () -> handleStoppedKey(java.awt.event.KeyEvent.VK_S));
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                panel.requestFocusInWindow();
            }
        });
        // Mark this pane active when the strip gains focus (mirroring the terminal panel's listener),
        // so the global dispatcher's activePane() lookup resolves to this pane even if the user only
        // clicked the strip rather than the terminal area above it.
        panel.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (onFocus != null) {
                    onFocus.run();
                }
            }
        });
        return panel;
    }

    /**
     * Performs the stopped-screen action for a bare key (Return / R / S), returning whether it was
     * handled. Called both by the strip's own key bindings and by the window's global key dispatcher
     * (so the keys work wherever focus sits in a stopped pane). Safe to call when not stopped.
     */
    public boolean handleStoppedKey(int keyCode) {
        if (!stopped) {
            return false;
        }
        switch (keyCode) {
            case java.awt.event.KeyEvent.VK_ENTER ->
                    takeStoppedAction(stoppedLines, stoppedFont, "Closing…", stoppedCyan, stoppedOnExit);
            case java.awt.event.KeyEvent.VK_R ->
                    takeStoppedAction(stoppedLines, stoppedFont, "Reconnecting…", stoppedMagenta, stoppedOnRestart);
            case java.awt.event.KeyEvent.VK_S -> saveOutput();
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Replaces the stopped-screen hint lines with a single bold status message, then runs the
     * action on the next EDT cycle so the status paints first. Guarded by {@link #stoppedActionTaken}
     * so a second key press (e.g. Return after R) is ignored while the first action is in flight.
     */
    private void takeStoppedAction(Box lines, Font mono, String message, Color color, Runnable action) {
        if (stoppedActionTaken) {
            return;
        }
        stoppedActionTaken = true;
        lines.removeAll();
        lines.add(label("<html><b style='color:" + hex(color) + "'>" + message + "</b></html>", mono));
        lines.revalidate();
        lines.repaint();
        SwingUtilities.invokeLater(action);
    }

    /**
     * Restores the stopped screen's hints after a reconnect attempt failed (the transient
     * "Reconnecting…" status is swapped back to "Press R / Return …"), and re-arms the keys so the
     * user can retry. Called on the EDT by {@code PaneGrid.restartPane}'s error handler. A no-op if
     * the overlay is gone (already reconnected or the pane was closed).
     */
    public void restoreStoppedScreen() {
        if (!stopped || stoppedPanel == null || stoppedHintsPopulator == null) {
            return;
        }
        stoppedActionTaken = false;
        stoppedHintsPopulator.run();
        stoppedPanel.requestFocusInWindow();
    }

    /** One indented "    - Press <key> <suffix>" line with the key letter colored. */
    private String hintLine(String key, Color keyColor, String suffix) {
        return "<html>&nbsp;&nbsp;&nbsp;&nbsp;- Press <span style='color:" + hex(keyColor)
                + "'>" + key + "</span>" + suffix + "</html>";
    }

    private JLabel label(String html, Font mono) {
        JLabel label = new JLabel(html);
        label.setFont(mono);
        label.setForeground(theme.foreground());
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private void bindKey(JComponent comp, int keyCode, Runnable action) {
        String name = "stopped-" + keyCode;
        comp.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyCode, 0), name);
        comp.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    // ---- pane action menu ----

    /**
     * The small "⋮" overflow button on the title bar that opens the pane action menu. Rendered as a
     * borderless toolbar button (FlatLaf) so it stays unobtrusive; it is not a drag source, so it
     * doesn't interfere with the title bar's pane-move gesture.
     */
    private JButton buildMenuButton() {
        JButton button = new JButton("⋮");
        button.setToolTipText("Pane actions");
        button.setFocusable(false);
        button.setMargin(new Insets(0, 4, 0, 4));
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.addActionListener(e -> buildPaneMenu().show(button, 0, button.getHeight()));
        return button;
    }

    /**
     * Makes a right-click anywhere on the title bar open the same pane action menu as the "⋮" button.
     * The popup is checked on both press and release because the platform's popup trigger differs.
     */
    private void installPaneMenuTrigger() {
        MouseAdapter popup = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShow(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShow(e);
            }

            private void maybeShow(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    buildPaneMenu().show(e.getComponent(), e.getX(), e.getY());
                }
            }
        };
        broadcastBar.addMouseListener(popup);
        titleLabel.addMouseListener(popup);
    }

    /**
     * Builds this pane's action menu fresh each time it is shown (cheap, and avoids stale state).
     * Currently holds only "Save output to file…"; further per-pane actions slot in here.
     */
    private JPopupMenu buildPaneMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem save = new JMenuItem("Save output to file…");
        save.addActionListener(e -> saveOutput());
        menu.add(save);
        return menu;
    }

    /**
     * Writes the terminal's full scrollback + screen to a user-chosen file. Shared by the live
     * title-bar menu and the stopped-session screen's S key. Prompts before overwriting an existing
     * file, and restricts the saved file to the owner since scrollback may contain typed secrets.
     */
    private void saveOutput() {
        String text = TerminalBufferText.collect(widget);
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save terminal output");
        chooser.setSelectedFile(new File(safeBaseName(session.title()) + "-output.txt"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        if (Files.exists(target)) {
            int choice = JOptionPane.showConfirmDialog(this,
                    target.getFileName() + " already exists.\nDo you want to replace it?",
                    "Save terminal output", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }
        try {
            Files.writeString(target, text, StandardCharsets.UTF_8);
            restrictToOwner(target);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not save output:\n" + ex.getMessage(),
                    "jterm", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Restricts a just-written file to owner read/write (0600); a no-op on non-POSIX filesystems. */
    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (Exception ignored) {
            // Non-POSIX filesystem (e.g. Windows) — rely on the user profile's ACLs.
        }
    }

    private static String safeBaseName(String title) {
        String base = (title == null || title.isBlank()) ? "terminal" : title;
        return base.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private Color ansi(int index, Color fallback) {
        Color[] palette = theme.ansi();
        return (palette != null && index < palette.length && palette[index] != null)
                ? palette[index] : fallback;
    }

    private static String hex(Color c) {
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }

    // ---- broadcast ----

    public boolean isBroadcastChecked() {
        return broadcastCheck.isSelected();
    }

    /** Show/hide the per-pane participation checkbox (the title bar itself is always visible). */
    public void setBroadcastMode(boolean broadcast) {
        broadcastCheck.setVisible(broadcast);
        broadcastBar.revalidate();
        broadcastBar.repaint();
    }

    /** Polls the live session title (e.g. a local shell's current directory) into the bar. */
    private void refreshTitle() {
        String latest = session.title();
        if (!latest.equals(titleLabel.getText())) {
            titleLabel.setText(latest);
        }
    }

    // ---- drag source (move this pane) ----

    /**
     * Makes the title bar (icon + name) a drag handle that carries this live pane. Dropping it on
     * the "+" pulls it into a new tab; on another pane/empty cell it rearranges or moves in. The
     * terminal widget itself is not a drag source, so text selection is unaffected, and the
     * broadcast checkbox keeps its own click handling.
     */
    private void installPaneDragSource() {
        DragGestureListener listener = dge -> {
            try {
                dge.startDrag(null, new PaneTransferable(this));
            } catch (InvalidDnDOperationException ignored) {
                // Another drag is already in flight; ignore this gesture.
            }
        };
        DragSource ds = DragSource.getDefaultDragSource();
        ds.createDefaultDragGestureRecognizer(broadcastBar, DnDConstants.ACTION_MOVE, listener);
        ds.createDefaultDragGestureRecognizer(titleLabel, DnDConstants.ACTION_MOVE, listener);
    }

    // ---- drag-and-drop hint ----

    /** Full-border highlight shown while a dragged pane hovers this one (swap / move target). */
    public void showMoveHint() {
        if (savedBorder == null) {
            savedBorder = getBorder();
        }
        setBorder(BorderFactory.createLineBorder(accentColor(), 3));
    }

    /** Highlight the edge where a dropped session would open (right=column, bottom=row). */
    public void showDropHint(DropRegion region) {
        if (savedBorder == null) {
            savedBorder = getBorder();
        }
        Color accent = accentColor();
        setBorder(region == DropRegion.COLUMN
                ? BorderFactory.createMatteBorder(0, 0, 0, 4, accent)
                : BorderFactory.createMatteBorder(0, 0, 4, 0, accent));
    }

    public void clearDropHint() {
        if (savedBorder != null) {
            setBorder(savedBorder);
            savedBorder = null;
        }
    }

    private static Color accentColor() {
        Color c = UIManager.getColor("Component.focusColor");
        return c != null ? c : new Color(0x4A90D9);
    }

    // ---- copy on select ----

    /**
     * Copies the current selection to the system clipboard whenever it changes and the
     * preference is on. JediTerm's own {@code copyOnSelect} targets the X11 selection
     * (PRIMARY) clipboard, which a normal paste won't read; copying here puts it on the
     * regular clipboard so it can be pasted anywhere.
     */
    private void installCopyOnSelect() {
        widget.getTerminalPanel().addSelectionListener(selection -> {
            // While dragging, JediTerm first fires with only a start point (end still null) and
            // then again with the end set; skip the incomplete event to avoid an NPE inside
            // getSelectionText.
            if (selection == null || selection.getEnd() == null || !AppSettings.get().isCopyOnSelect()) {
                return;
            }
            // Use pointsForRun (as JediTerm's own copy does) rather than the raw start/end: it sorts
            // the points and makes the end column inclusive. The plain getSelectionText(selection,…)
            // overload passes the exclusive end as-is, dropping the last selected character.
            var buffer = widget.getTerminalTextBuffer();
            kotlin.Pair<Point, Point> run = selection.pointsForRun(buffer.getWidth());
            if (run.getFirst() == null || run.getSecond() == null) {
                return;
            }
            String text = SelectionUtil.getSelectionText(run.getFirst(), run.getSecond(), buffer);
            if (text != null && !text.isEmpty()) {
                setClipboard(text, true);
            }
        });
    }

    /**
     * Writes to the system clipboard, tolerating the transient
     * {@code "cannot open system clipboard"} {@link IllegalStateException} that X11/Wayland
     * throw when another app momentarily owns it (common on the first access). On failure it
     * retries once after the current event, by which point the clipboard is usually free.
     */
    private void setClipboard(String text, boolean retry) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
        } catch (IllegalStateException e) {
            if (retry) {
                SwingUtilities.invokeLater(() -> setClipboard(text, false));
            }
        }
    }

    /**
     * Installs regex output-highlighting for this pane's resolved active list. The list is resolved
     * (session override → global default → none) and frozen at construction, so later edits to the
     * library only affect newly opened panes.
     */
    private void installHighlighting() {
        HighlightList resolved = HighlightListResolver.resolve(session.highlightListOverrideId());
        if (resolved != null) {
            highlightTeardown = HighlightingInstaller.install(widget, CompiledHighlightList.compile(resolved));
        }
    }

    /**
     * Recolors this pane's terminal in place after a light/dark switch — no restart needed. The
     * settings provider's theme is swapped and the panel repainted; default-pen and ANSI-indexed
     * cells (including existing scrollback) re-resolve against the new theme on paint. Explicit
     * truecolor output keeps its absolute colors, as intended.
     */
    public void applyTheme(ThemeColors newTheme) {
        this.theme = newTheme;
        settingsProvider.setTheme(newTheme);
        // The plain-shell glyph is theme-contrasting, so re-resolve it on a light/dark switch.
        titleLabel.setIcon(SessionIcon.forSession(session, 16));
        widget.recolor();
    }

    /** Move keyboard focus into the terminal. */
    public void focusTerminal() {
        widget.getTerminalPanel().requestFocusInWindow();
    }

    // ---- GridContent ----

    @Override
    public JComponent ui() {
        return this;
    }

    @Override
    public void closeContent() {
        close();
    }

    @Override
    public void focusContent() {
        focusTerminal();
    }

    @Override
    public void setOnContentEnded(Runnable onEnded) {
        setOnSessionEnd(onEnded);
    }

    @Override
    public String displayTitle() {
        return title();
    }

    /** Terminate the terminal and its back-end session. */
    public void close() {
        titleTimer.stop();
        if (highlightTeardown != null) {
            highlightTeardown.run();
            highlightTeardown = null;
        }
        try {
            widget.close();
        } catch (Exception ignored) {
        }
        session.close();
    }
}
