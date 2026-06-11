package com.katmoda.jterm.app;

import com.katmoda.jterm.ui.theme.ThemeManager;

import javax.swing.SwingUtilities;
import java.awt.Toolkit;
import java.lang.reflect.Field;

/** Application entry point. */
public final class Main {

    /** App id used as the X11 WM_CLASS so GNOME/Wayland can match our {@code .desktop} file. */
    public static final String APP_ID = "jterm";

    private Main() {
    }

    public static void main(String[] args) {
        setLinuxApplicationName(APP_ID);
        SwingUtilities.invokeLater(() -> {
            ThemeManager.get().install();
            new MainWindow().show();
        });
    }

    /**
     * On Linux/X11 (incl. XWayland) the window's WM_CLASS defaults to the main class name.
     * GNOME Shell uses WM_CLASS to match a {@code .desktop} file (via {@code StartupWMClass})
     * for the dash/overview/Alt-Tab icon, so we set a clean, stable value here. Requires the
     * jar manifest's {@code Add-Opens: java.desktop/sun.awt.X11} (see pom.xml); a no-op on
     * other platforms or if reflection is blocked.
     */
    private static void setLinuxApplicationName(String name) {
        try {
            Toolkit toolkit = Toolkit.getDefaultToolkit();
            Field field = toolkit.getClass().getDeclaredField("awtAppClassName");
            field.setAccessible(true);
            field.set(null, name);
        } catch (Exception ignored) {
            // Not X11, or module not opened — falls back to the default WM_CLASS.
        }
    }
}
