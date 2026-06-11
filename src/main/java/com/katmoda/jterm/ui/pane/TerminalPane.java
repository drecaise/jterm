package com.katmoda.jterm.ui.pane;

import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.model.SelectionUtil;
import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.dnd.DropRegion;
import com.katmoda.jterm.terminal.TerminalSession;
import com.katmoda.jterm.ui.theme.JTermSettingsProvider;
import com.katmoda.jterm.ui.theme.ThemeColors;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

/**
 * A single terminal cell: a JediTerm widget driving one {@link TerminalSession}.
 *
 * <p>The JediTerm widget is wired to the connector handed in by the grid (a broadcast
 * wrapper around the session's real connector). When broadcast mode is active the pane
 * shows a bottom title bar with a checkbox controlling whether it participates.</p>
 */
public final class TerminalPane extends JPanel {

    private final TerminalSession session;
    private final JtermJediTermWidget widget;

    private final JPanel broadcastBar;
    private final JCheckBox broadcastCheck;
    private final JLabel titleLabel;
    private final Timer titleTimer;

    private Runnable onFocus;
    private Runnable onSessionEnd;
    private Border savedBorder;

    public TerminalPane(TerminalSession session, ThemeColors theme, TtyConnector connector) {
        super(new BorderLayout());
        this.session = session;
        var profile = session.profile();
        this.widget = new JtermJediTermWidget(
                new JTermSettingsProvider(theme, profile.fontFamily(), profile.fontSize()));
        this.widget.setTtyConnector(connector);
        this.widget.start();
        add(widget, BorderLayout.CENTER);
        installCopyOnSelect();

        this.broadcastCheck = new JCheckBox((String) null, true);
        this.broadcastCheck.setToolTipText("Include this pane in broadcast input");
        this.titleLabel = new JLabel();
        this.broadcastBar = new JPanel(new BorderLayout(6, 0));
        this.broadcastBar.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        this.broadcastBar.add(broadcastCheck, BorderLayout.WEST);
        this.broadcastBar.add(titleLabel, BorderLayout.CENTER);
        // While the bar is visible, keep the title (e.g. a local shell's CWD) up to date.
        this.titleTimer = new Timer(800, e -> refreshTitle());
        this.titleTimer.setRepeats(true);

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

    public String title() {
        return session.title();
    }

    public void setOnFocus(Runnable onFocus) {
        this.onFocus = onFocus;
    }

    public void setOnSessionEnd(Runnable onSessionEnd) {
        this.onSessionEnd = onSessionEnd;
    }

    // ---- broadcast ----

    public boolean isBroadcastChecked() {
        return broadcastCheck.isSelected();
    }

    /** Show/hide the broadcast title bar, refreshing the title from the live session. */
    public void setBroadcastBarVisible(boolean visible) {
        if (visible) {
            titleLabel.setText(session.title());
            add(broadcastBar, BorderLayout.SOUTH);
            titleTimer.start();
        } else {
            titleTimer.stop();
            remove(broadcastBar);
        }
        revalidate();
        repaint();
    }

    /** Polls the live session title (e.g. a local shell's current directory) into the bar. */
    private void refreshTitle() {
        String latest = session.title();
        if (!latest.equals(titleLabel.getText())) {
            titleLabel.setText(latest);
        }
    }

    // ---- drag-and-drop hint ----

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
            String text = SelectionUtil.getSelectionText(selection, widget.getTerminalTextBuffer());
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

    /** Move keyboard focus into the terminal. */
    public void focusTerminal() {
        widget.getTerminalPanel().requestFocusInWindow();
    }

    /** Terminate the terminal and its back-end session. */
    public void close() {
        titleTimer.stop();
        try {
            widget.close();
        } catch (Exception ignored) {
        }
        session.close();
    }
}
