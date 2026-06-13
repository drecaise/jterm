package com.katmoda.jterm.ui.pane;

import com.jediterm.core.compatibility.Point;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.model.SelectionUtil;
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
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
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

/**
 * A single terminal cell: a JediTerm widget driving one {@link TerminalSession}.
 *
 * <p>The JediTerm widget is wired to the connector handed in by the grid (a broadcast
 * wrapper around the session's real connector). When broadcast mode is active the pane
 * shows a bottom title bar with a checkbox controlling whether it participates.</p>
 */
public final class TerminalPane extends JPanel implements GridContent {

    private final TerminalSession session;
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
        titleTimer.stop();
        JPanel panel = buildStoppedPanel(onExit, onRestart);
        bottomArea.add(panel, BorderLayout.CENTER);
        revalidate();
        repaint();
        SwingUtilities.invokeLater(panel::requestFocusInWindow);
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
        lines.setOpaque(true);
        lines.setBackground(theme.background());
        lines.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        lines.add(label("<html><b style='color:" + hex(red) + "'>Session stopped</b></html>", mono));
        lines.add(label(hintLine("&lt;Return&gt;", cyan, " to exit tab"), mono));
        lines.add(label(hintLine("R", magenta, " to restart session"), mono));
        lines.add(label(hintLine("S", magenta, " to save terminal output to file"), mono));
        panel.add(lines, BorderLayout.CENTER);

        panel.setFocusable(true);
        bindKey(panel, java.awt.event.KeyEvent.VK_ENTER, onExit);
        bindKey(panel, java.awt.event.KeyEvent.VK_R, onRestart);
        bindKey(panel, java.awt.event.KeyEvent.VK_S, this::saveOutput);
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                panel.requestFocusInWindow();
            }
        });
        return panel;
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

    /** Writes the terminal's full scrollback + screen to a user-chosen file. */
    private void saveOutput() {
        String text = TerminalBufferText.collect(widget);
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save terminal output");
        chooser.setSelectedFile(new File(safeBaseName(session.title()) + "-output.txt"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            Files.writeString(chooser.getSelectedFile().toPath(), text, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not save output:\n" + ex.getMessage(),
                    "jterm", JOptionPane.ERROR_MESSAGE);
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
