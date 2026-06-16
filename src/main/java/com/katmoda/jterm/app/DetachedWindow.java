package com.katmoda.jterm.app;

import com.katmoda.jterm.ui.tabs.TabPane;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * A lightweight secondary window holding a {@link TabPane} of detached tabs (a "tabbed collector":
 * several tabs can be detached/dragged into one window, e.g. for a second monitor). No sidebar and no
 * full menu bar — just the terminal grids, the usual terminal shortcuts (handled by the main window's
 * global dispatcher, routed to whichever window is focused), and an <em>Attach to Main</em> button.
 *
 * <p>Tabs carry their live sessions when they move here, so nothing is reconnected. Closing the
 * window (or attaching its last tab) terminates / hands back the sessions it still holds.</p>
 */
public final class DetachedWindow implements TerminalWindow {

    private static final Dimension DEFAULT_SIZE = new Dimension(960, 660);

    private final JFrame frame = new JFrame("jterm");
    private final TabPane tabPane;

    public DetachedWindow() {
        // Share the main window's services (SSH connect / vault, icons, keymap) — one JVM, one vault.
        this.tabPane = new TabPane(this, WindowManager.get().mainWindow());
        frame.setLayout(new BorderLayout());
        frame.add(buildToolbar(), BorderLayout.NORTH);
        frame.add(tabPane, BorderLayout.CENTER);
        frame.setIconImages(AppIcon.images());
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // Closing with tabs still present: terminate their sessions and drop the window.
                tabPane.disposeAllGrids();
                WindowManager.get().unregister(DetachedWindow.this);
            }
        });
    }

    private JPanel buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setLayout(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        JButton attach = new JButton("⇤ Attach to Main");
        attach.setToolTipText("Move every tab in this window back to the main window");
        attach.setFocusable(false);
        attach.addActionListener(e -> tabPane.attachAllToMain());
        bar.add(attach);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(bar, BorderLayout.CENTER);
        return wrapper;
    }

    /** Registers, positions and shows the window at the given screen location. */
    public void showAt(Point screenLocation) {
        WindowManager.get().register(this);
        Dimension size = DEFAULT_SIZE;
        MainWindow main = WindowManager.get().mainWindow();
        if (main != null && main.frame() != null) {
            Dimension mainSize = main.frame().getSize();
            if (mainSize.width > 200 && mainSize.height > 150) {
                size = mainSize;
            }
        }
        frame.setSize(size);
        if (screenLocation != null) {
            frame.setLocation(screenLocation);
        } else {
            frame.setLocationRelativeTo(null);
        }
        frame.setVisible(true);
        frame.toFront();
    }

    @Override
    public JFrame frame() {
        return frame;
    }

    @Override
    public TabPane tabPane() {
        return tabPane;
    }

    @Override
    public boolean isMain() {
        return false;
    }
}
