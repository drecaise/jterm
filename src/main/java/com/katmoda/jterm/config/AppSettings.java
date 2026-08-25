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
package com.katmoda.jterm.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.katmoda.jterm.highlight.HighlightLibrary;
import com.katmoda.jterm.terminal.TerminalProfile;
import com.katmoda.jterm.ui.theme.FontResources;

import java.nio.file.Path;

/**
 * General application preferences persisted as {@code settings.json} in the config dir.
 *
 * <p>A small mutable singleton: values are read live by the terminal settings provider
 * ({@code copyOnSelect}) and the right-click paste handler ({@code pasteOnRightClick}),
 * so toggling them takes effect without recreating panes. Both default off, matching the
 * prior behavior.</p>
 *
 * <p>Not every value is live: the UI scale/font ({@link #getUiScalePercent()} and friends) are read
 * once by {@code ThemeManager} at startup, so changing them takes effect on the next launch.</p>
 */
public final class AppSettings {

    private static final AppSettings INSTANCE = load();

    /** Bounds for the scrollback buffer size, in lines. The upper bound caps memory use so a
     *  hand-edited or corrupted settings file can't drive an unbounded buffer allocation. */
    public static final int MIN_SCROLLBACK_LINES = 100;
    public static final int MAX_SCROLLBACK_LINES = 100_000;

    /** Bounds for the application UI scale, in percent (100 = unscaled). */
    public static final int MIN_UI_SCALE_PERCENT = 75;
    public static final int MAX_UI_SCALE_PERCENT = 300;

    /** Bounds for the application UI font-size override, in points. */
    public static final int MIN_UI_FONT_SIZE = 8;
    public static final int MAX_UI_FONT_SIZE = 48;

    private boolean copyOnSelect = false;
    private boolean pasteOnRightClick = false;

    // Whether the X11 primary-selection model is emulated: selecting text copies it to the PRIMARY
    // selection and a middle-click pastes from it. Read live by the terminal settings provider
    // (JediTerm's copyOnSelect/pasteOnMiddleMouseClick), so toggling takes effect without recreating
    // panes. Defaults off (the app's original behavior). Independent of copyOnSelect above, which
    // targets the regular clipboard.
    private boolean middleClickPaste = false;

    // Whether the terminal caret blinks. Read live by the terminal settings provider
    // (JediTerm's caretBlinkingMs()), so toggling takes effect on already-open terminals without
    // recreating panes. Defaults on, matching JediTerm's own default (and prior jterm behavior,
    // which never overrode it).
    private boolean blinkCursor = true;

    // Terminal scrollback size in lines, read by the settings provider when each new widget's
    // buffer is built (so it applies to newly opened terminals). Clamped to
    // [MIN_SCROLLBACK_LINES, MAX_SCROLLBACK_LINES]. Defaults to 10000; a settings file predating
    // this field keeps the default.
    private int scrollbackLines = 10_000;

    // Whether an unknown (first-seen) host's key is trusted and recorded automatically, without the
    // trust-on-first-use prompt. Defaults to off (prompt). A CHANGED host key is always warned about
    // regardless of this setting — see terminal.ssh.JtermKnownHostsVerifier.
    private boolean autoAcceptNewHostKeys = false;

    // Whether a connect whose agent/key authentication was rejected falls back to prompting for a
    // password (and answering keyboard-interactive challenges) instead of failing outright. Only
    // ever reached for auth methods the server actually offers — see terminal.ssh.SshConnect.
    private boolean promptPasswordOnAuthFailure = true;

    // Whether a local terminal tab is opened automatically on launch. Defaults to true (the app's
    // original behavior); read once at startup, so toggling it only affects the next launch.
    private boolean openTerminalOnStartup = true;

    // Whether a pane's label and its tab title carry the shell's working directory. Read live, so
    // toggling it takes effect on the next title poll. Off by default: for SSH and WSL it depends on
    // the remote shell announcing its directory, which many do not — see ui.pane.PaneTitle.
    private boolean showWorkingDirectory = false;

    // Whether macro steps are encrypted at rest in macros.json, under the credential vault's key
    // (see macro.MacroCrypto). Off by default: turning it on requires a master password, which is a
    // cost every user with a macro would otherwise pay for a risk only some of them carry. Names and
    // hotkeys stay plaintext either way, so the Macros menu works with the vault locked.
    private boolean encryptMacros = false;

    // Whether the app asks GitHub once a day whether a newer release exists. Read live by
    // update.UpdateScheduler at each firing, so both directions of the toggle take effect without
    // a restart. Nothing about the user is transmitted — see update.UpdateChecker.
    private boolean updateCheckEnabled = true;

    // When the last update check was *attempted* (epoch seconds; 0 = never). Recorded whether the
    // attempt succeeded or failed, which is what makes an offline or rate-limited client back off
    // for a day rather than retry in a loop. Throttle state rather than a user preference, kept
    // here for the same reason the window bounds are.
    private long lastUpdateCheckEpochSeconds = 0;

    // The release tag the user chose to skip ("" = none). Suppresses the dialog for exactly that
    // version; a later release still notifies. Ignored by the manual Help menu check.
    private String skippedUpdateVersion = "";

    // Whether the dark theme is active. Persisted so the choice survives a restart; defaults to
    // dark (the app's original default) on a fresh install or a settings file predating this field.
    private boolean darkTheme = true;

    // Window state restored on launch. Whether the frame was maximized, and the sidebar (split
    // divider) width in pixels. Defaults match the original hard-coded values. sidebarVisible is
    // the sessions sidebar's open/closed state; the width is the one to reopen at, so it stays
    // meaningful while the sidebar is closed. Defaults to open, so existing installs are unaffected.
    private boolean windowMaximized = false;
    private int sidebarWidth = 240;
    private boolean sidebarVisible = true;

    // The window's restored-down (non-maximized) bounds, so it reopens on the same monitor at the
    // same size. Location defaults to Integer.MIN_VALUE ("unset" → center on the primary screen);
    // size defaults to the original hard-coded 1100x720.
    private int windowX = Integer.MIN_VALUE;
    private int windowY = Integer.MIN_VALUE;
    private int windowWidth = 1100;
    private int windowHeight = 720;

    // Default terminal settings applied to the local terminal and to any saved session that
    // leaves a field unset ("inherit"). The font defaults to the bundled MobaFont.
    private String defaultTerminalType = "xterm-256color";
    private String defaultCharset = "UTF-8";
    private String defaultFontFamily = FontResources.DEFAULT_TERMINAL_FONT_FAMILY;
    private int defaultFontSize = 14;

    // Application UI (chrome) scale and font — the sidebar, tab strip, menus and dialogs, not the
    // terminal panes (those follow the terminal font settings above). All three are read once at
    // startup by ThemeManager, so changing them takes effect on the next launch.
    //
    // uiScalePercent drives FlatLaf's zoom factor, which scales fonts *and* the metrics FlatLaf
    // derives through UIScale (paddings, borders, row heights) together. 100 (the default) leaves
    // the look-and-feel untouched, so existing installs are unaffected.
    //
    // uiFontFamily/uiFontSize are optional overrides of the look-and-feel's own font: "" and 0 mean
    // "keep the look-and-feel's choice". The size is the unzoomed size — the scale multiplies it.
    private int uiScalePercent = 100;
    private String uiFontFamily = "";
    private int uiFontSize = 0;

    // Id of the globally-active highlight list (in HighlightLibrary); null means "(None)". Saved
    // sessions inherit this unless they set their own override. Fresh installs (no settings.json)
    // default to the seeded "Standard" list so error/warn/ok coloring works out of the box; upgrading
    // users whose settings.json predates this field keep "(None)" (loaded as null below).
    private String globalHighlightListId = HighlightLibrary.DEFAULT_LIST_ID;

    // Global default SSH username and tab color, inherited by folders/sessions that leave them
    // unset. The username defaults to the OS user (preserving the prior per-session default);
    // the tab color defaults to null ("theme default" — no override).
    private String defaultUsername = System.getProperty("user.name", "");
    private String defaultTabColorHex = null;

    // Global default SSH private-key path, inherited by folders/sessions that leave their key path
    // blank. "" means none configured (fall back to the auto-discovered ~/.ssh identities). The
    // matching passphrase (and the global default password) are secrets, kept in the credential
    // vault rather than here — see security.VaultKeys.
    private String defaultKeyPath = "";

    // Global default keep-alive interval in seconds, inherited by folders/sessions that leave their
    // keep-alive set to "inherit". 0 (the default) means off, so existing installs are unaffected;
    // > 0 enables keep-alive at that interval.
    private int defaultKeepAliveSeconds = 0;

    public AppSettings() {
    }

    public static AppSettings get() {
        return INSTANCE;
    }

    public boolean isCopyOnSelect() {
        return copyOnSelect;
    }

    public void setCopyOnSelect(boolean copyOnSelect) {
        this.copyOnSelect = copyOnSelect;
    }

    public boolean isPasteOnRightClick() {
        return pasteOnRightClick;
    }

    public void setPasteOnRightClick(boolean pasteOnRightClick) {
        this.pasteOnRightClick = pasteOnRightClick;
    }

    public boolean isMiddleClickPaste() {
        return middleClickPaste;
    }

    public void setMiddleClickPaste(boolean middleClickPaste) {
        this.middleClickPaste = middleClickPaste;
    }

    public boolean isBlinkCursor() {
        return blinkCursor;
    }

    public void setBlinkCursor(boolean blinkCursor) {
        this.blinkCursor = blinkCursor;
    }

    /**
     * Whether an unknown host's key is trusted automatically (no first-use prompt). Read live by
     * the SSH host-key verifier. A <em>changed</em> host key always prompts regardless of this.
     */
    public boolean isAutoAcceptNewHostKeys() {
        return autoAcceptNewHostKeys;
    }

    public void setAutoAcceptNewHostKeys(boolean autoAcceptNewHostKeys) {
        this.autoAcceptNewHostKeys = autoAcceptNewHostKeys;
    }

    /**
     * Whether a connect prompts for a password once ssh-agent/key authentication has been
     * rejected. Read live at each connect.
     */
    public boolean isPromptPasswordOnAuthFailure() {
        return promptPasswordOnAuthFailure;
    }

    public void setPromptPasswordOnAuthFailure(boolean promptPasswordOnAuthFailure) {
        this.promptPasswordOnAuthFailure = promptPasswordOnAuthFailure;
    }

    /**
     * Whether pane labels and tab titles show the shell's working directory. Read live at each
     * title refresh, so a change applies to open panes immediately.
     */
    public boolean isShowWorkingDirectory() {
        return showWorkingDirectory;
    }

    public void setShowWorkingDirectory(boolean showWorkingDirectory) {
        this.showWorkingDirectory = showWorkingDirectory;
    }

    /**
     * Whether macro steps are encrypted at rest. Read at every {@code MacroLibrary} save and by the
     * run/edit paths, so the toggle takes effect without a restart.
     */
    public boolean isEncryptMacros() {
        return encryptMacros;
    }

    public void setEncryptMacros(boolean encryptMacros) {
        this.encryptMacros = encryptMacros;
    }

    /**
     * Whether the scheduled GitHub update check runs. Read live at each firing, so switching it
     * either way in Preferences takes effect without a restart.
     */
    public boolean isUpdateCheckEnabled() {
        return updateCheckEnabled;
    }

    public void setUpdateCheckEnabled(boolean updateCheckEnabled) {
        this.updateCheckEnabled = updateCheckEnabled;
    }

    /** When an update check was last attempted, in epoch seconds; 0 means never. */
    public long getLastUpdateCheckEpochSeconds() {
        return lastUpdateCheckEpochSeconds;
    }

    public void setLastUpdateCheckEpochSeconds(long lastUpdateCheckEpochSeconds) {
        this.lastUpdateCheckEpochSeconds = Math.max(0, lastUpdateCheckEpochSeconds);
    }

    /** The release tag the user asked not to be told about again; {@code ""} when none. */
    public String getSkippedUpdateVersion() {
        return skippedUpdateVersion;
    }

    public void setSkippedUpdateVersion(String skippedUpdateVersion) {
        this.skippedUpdateVersion = isBlank(skippedUpdateVersion) ? "" : skippedUpdateVersion.trim();
    }

    /** Whether a local terminal tab is opened automatically on launch (read once at startup). */
    public boolean isOpenTerminalOnStartup() {
        return openTerminalOnStartup;
    }

    public void setOpenTerminalOnStartup(boolean openTerminalOnStartup) {
        this.openTerminalOnStartup = openTerminalOnStartup;
    }

    /** Whether the dark theme is active (persisted across restarts). */
    public boolean isDarkTheme() {
        return darkTheme;
    }

    public void setDarkTheme(boolean darkTheme) {
        this.darkTheme = darkTheme;
    }

    /** Whether the window was maximized on last exit (persisted across restarts). */
    public boolean isWindowMaximized() {
        return windowMaximized;
    }

    public void setWindowMaximized(boolean windowMaximized) {
        this.windowMaximized = windowMaximized;
    }

    /** The sidebar (split divider) width in pixels, restored on launch. */
    public int getSidebarWidth() {
        return sidebarWidth;
    }

    public void setSidebarWidth(int sidebarWidth) {
        if (sidebarWidth > 0) {
            this.sidebarWidth = sidebarWidth;
        }
    }

    /** Whether the sessions sidebar is open, restored on launch. */
    public boolean isSidebarVisible() {
        return sidebarVisible;
    }

    public void setSidebarVisible(boolean sidebarVisible) {
        this.sidebarVisible = sidebarVisible;
    }

    public int getWindowX() {
        return windowX;
    }

    public int getWindowY() {
        return windowY;
    }

    public int getWindowWidth() {
        return windowWidth;
    }

    public int getWindowHeight() {
        return windowHeight;
    }

    /** Whether a window location was saved (false on a fresh install → center on screen). */
    public boolean hasWindowLocation() {
        return windowX != Integer.MIN_VALUE && windowY != Integer.MIN_VALUE;
    }

    /** Records the window's restored-down bounds (size must be positive to be stored). */
    public void setWindowBounds(int x, int y, int width, int height) {
        this.windowX = x;
        this.windowY = y;
        if (width > 0) {
            this.windowWidth = width;
        }
        if (height > 0) {
            this.windowHeight = height;
        }
    }

    public String getDefaultTerminalType() {
        return defaultTerminalType;
    }

    public void setDefaultTerminalType(String defaultTerminalType) {
        this.defaultTerminalType = defaultTerminalType;
    }

    public String getDefaultCharset() {
        return defaultCharset;
    }

    public void setDefaultCharset(String defaultCharset) {
        this.defaultCharset = defaultCharset;
    }

    public String getDefaultFontFamily() {
        return defaultFontFamily;
    }

    public void setDefaultFontFamily(String defaultFontFamily) {
        this.defaultFontFamily = defaultFontFamily;
    }

    public int getDefaultFontSize() {
        return defaultFontSize;
    }

    public void setDefaultFontSize(int defaultFontSize) {
        this.defaultFontSize = defaultFontSize;
    }

    /** The application UI scale in percent; {@code 100} leaves the look-and-feel unscaled. */
    public int getUiScalePercent() {
        return uiScalePercent;
    }

    /** Sets the UI scale, clamped to [{@value #MIN_UI_SCALE_PERCENT}, {@value #MAX_UI_SCALE_PERCENT}] percent. */
    public void setUiScalePercent(int percent) {
        this.uiScalePercent = Math.max(MIN_UI_SCALE_PERCENT, Math.min(MAX_UI_SCALE_PERCENT, percent));
    }

    /** The UI scale as a multiplier ({@code 1.0} = unscaled), for FlatLaf's zoom factor. */
    public float uiScaleFactor() {
        return uiScalePercent / 100f;
    }

    /** The UI font family override, or {@code ""} to keep the look-and-feel's own font. */
    public String getUiFontFamily() {
        return uiFontFamily;
    }

    public void setUiFontFamily(String uiFontFamily) {
        this.uiFontFamily = (uiFontFamily != null) ? uiFontFamily.trim() : "";
    }

    /** The UI font-size override in points, or {@code 0} to keep the look-and-feel's own size. */
    public int getUiFontSize() {
        return uiFontSize;
    }

    /**
     * Sets the UI font-size override, clamped to
     * [{@value #MIN_UI_FONT_SIZE}, {@value #MAX_UI_FONT_SIZE}] points. Any value {@code <= 0}
     * clears the override.
     */
    public void setUiFontSize(int uiFontSize) {
        this.uiFontSize = (uiFontSize <= 0) ? 0
                : Math.max(MIN_UI_FONT_SIZE, Math.min(MAX_UI_FONT_SIZE, uiFontSize));
    }

    /** Id of the globally-active highlight list, or {@code null} for "(None)". */
    public String getGlobalHighlightListId() {
        return globalHighlightListId;
    }

    public void setGlobalHighlightListId(String globalHighlightListId) {
        this.globalHighlightListId =
                (globalHighlightListId != null && !globalHighlightListId.isBlank())
                        ? globalHighlightListId : null;
    }

    /** The global default SSH username, or {@code ""} if none is configured. */
    public String getDefaultUsername() {
        return defaultUsername;
    }

    public void setDefaultUsername(String defaultUsername) {
        this.defaultUsername = (defaultUsername != null) ? defaultUsername.trim() : "";
    }

    /** The global default tab color as {@code "#RRGGBB"}, or {@code null} for the theme default. */
    public String getDefaultTabColorHex() {
        return defaultTabColorHex;
    }

    public void setDefaultTabColorHex(String defaultTabColorHex) {
        this.defaultTabColorHex =
                (defaultTabColorHex != null && !defaultTabColorHex.isBlank()) ? defaultTabColorHex : null;
    }

    /** The global default SSH private-key path, or {@code ""} if none is configured. */
    public String getDefaultKeyPath() {
        return defaultKeyPath;
    }

    public void setDefaultKeyPath(String defaultKeyPath) {
        this.defaultKeyPath = (defaultKeyPath != null) ? defaultKeyPath.trim() : "";
    }

    /** The global default keep-alive interval in seconds; {@code 0} means off. */
    public int getDefaultKeepAliveSeconds() {
        return defaultKeepAliveSeconds;
    }

    public void setDefaultKeepAliveSeconds(int defaultKeepAliveSeconds) {
        this.defaultKeepAliveSeconds = Math.max(0, defaultKeepAliveSeconds);
    }

    /** Terminal scrollback size in lines, read when each new terminal widget's buffer is built. */
    public int getScrollbackLines() {
        return scrollbackLines;
    }

    /** Sets the scrollback size, clamped to [{@value #MIN_SCROLLBACK_LINES}, {@value #MAX_SCROLLBACK_LINES}]. */
    public void setScrollbackLines(int lines) {
        this.scrollbackLines = Math.max(MIN_SCROLLBACK_LINES, Math.min(MAX_SCROLLBACK_LINES, lines));
    }

    /** The application-wide default terminal profile (used by the local terminal). */
    public TerminalProfile defaultProfile() {
        return TerminalProfile.from(defaultTerminalType, defaultCharset, defaultFontFamily, defaultFontSize);
    }

    /**
     * Resolves raw per-session values against the application defaults: any blank string or
     * non-positive size falls back to the corresponding default ("inherit when unset").
     */
    public TerminalProfile resolve(String terminalType, String charset, String fontFamily, int fontSize) {
        return TerminalProfile.from(
                isBlank(terminalType) ? defaultTerminalType : terminalType,
                isBlank(charset) ? defaultCharset : charset,
                isBlank(fontFamily) ? defaultFontFamily : fontFamily,
                fontSize > 0 ? fontSize : defaultFontSize);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Persist the current values to {@code settings.json} (best-effort). */
    public void save() {
        JsonStore.save(file(), new Persisted(copyOnSelect, pasteOnRightClick,
                defaultTerminalType, defaultCharset, defaultFontFamily, defaultFontSize,
                globalHighlightListId, darkTheme, windowMaximized, sidebarWidth,
                windowX, windowY, windowWidth, windowHeight,
                defaultUsername, defaultTabColorHex, openTerminalOnStartup,
                defaultKeyPath, autoAcceptNewHostKeys, scrollbackLines, middleClickPaste,
                uiScalePercent, uiFontFamily, uiFontSize, promptPasswordOnAuthFailure,
                sidebarVisible, blinkCursor, defaultKeepAliveSeconds, showWorkingDirectory,
                updateCheckEnabled, lastUpdateCheckEpochSeconds, skippedUpdateVersion,
                encryptMacros));
    }

    private static AppSettings load() {
        AppSettings settings = new AppSettings();
        // Missing or malformed file → defaults (a malformed file is preserved aside by JsonStore).
        Persisted p = JsonStore.load(file(), Persisted.class);
        if (p != null) {
            settings.copyOnSelect = p.copyOnSelect;
            settings.pasteOnRightClick = p.pasteOnRightClick;
            if (p.autoAcceptNewHostKeys != null) {
                settings.autoAcceptNewHostKeys = p.autoAcceptNewHostKeys;
            }
            if (p.promptPasswordOnAuthFailure != null) {
                settings.promptPasswordOnAuthFailure = p.promptPasswordOnAuthFailure;
            }
            if (p.openTerminalOnStartup != null) {
                settings.openTerminalOnStartup = p.openTerminalOnStartup;
            }
            if (p.showWorkingDirectory != null) {
                settings.showWorkingDirectory = p.showWorkingDirectory;
            }
            if (p.updateCheckEnabled != null) {
                settings.updateCheckEnabled = p.updateCheckEnabled;
            }
            if (p.lastUpdateCheckEpochSeconds != null) {
                settings.setLastUpdateCheckEpochSeconds(p.lastUpdateCheckEpochSeconds);
            }
            if (p.skippedUpdateVersion != null) {
                settings.setSkippedUpdateVersion(p.skippedUpdateVersion);
            }
            if (p.encryptMacros != null) {
                settings.encryptMacros = p.encryptMacros;
            }
            if (!isBlank(p.defaultTerminalType)) {
                settings.defaultTerminalType = p.defaultTerminalType;
            }
            if (!isBlank(p.defaultCharset)) {
                settings.defaultCharset = p.defaultCharset;
            }
            if (!isBlank(p.defaultFontFamily)) {
                settings.defaultFontFamily = p.defaultFontFamily;
            }
            if (p.defaultFontSize > 0) {
                settings.defaultFontSize = p.defaultFontSize;
            }
            settings.setGlobalHighlightListId(p.globalHighlightListId);
            if (p.defaultUsername != null) {
                settings.defaultUsername = p.defaultUsername.trim();
            }
            settings.setDefaultTabColorHex(p.defaultTabColorHex);
            if (p.defaultKeyPath != null) {
                settings.defaultKeyPath = p.defaultKeyPath.trim();
            }
            if (p.defaultKeepAliveSeconds != null) {
                settings.setDefaultKeepAliveSeconds(p.defaultKeepAliveSeconds);
            }
            if (p.darkTheme != null) {
                settings.darkTheme = p.darkTheme;
            }
            if (p.windowMaximized != null) {
                settings.windowMaximized = p.windowMaximized;
            }
            if (p.sidebarWidth != null && p.sidebarWidth > 0) {
                settings.sidebarWidth = p.sidebarWidth;
            }
            if (p.sidebarVisible != null) {
                settings.sidebarVisible = p.sidebarVisible;
            }
            if (p.windowX != null) {
                settings.windowX = p.windowX;
            }
            if (p.windowY != null) {
                settings.windowY = p.windowY;
            }
            if (p.windowWidth != null && p.windowWidth > 0) {
                settings.windowWidth = p.windowWidth;
            }
            if (p.windowHeight != null && p.windowHeight > 0) {
                settings.windowHeight = p.windowHeight;
            }
            if (p.scrollbackLines != null) {
                settings.setScrollbackLines(p.scrollbackLines);
            }
            if (p.middleClickPaste != null) {
                settings.middleClickPaste = p.middleClickPaste;
            }
            if (p.blinkCursor != null) {
                settings.blinkCursor = p.blinkCursor;
            }
            if (p.uiScalePercent != null) {
                settings.setUiScalePercent(p.uiScalePercent);
            }
            settings.setUiFontFamily(p.uiFontFamily);
            if (p.uiFontSize != null) {
                settings.setUiFontSize(p.uiFontSize);
            }
        }
        return settings;
    }

    private static Path file() {
        return AppPaths.file("settings.json");
    }

    /** On-disk shape (kept separate so the live singleton stays a plain bean). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Persisted(boolean copyOnSelect, boolean pasteOnRightClick,
                             String defaultTerminalType, String defaultCharset,
                             String defaultFontFamily, int defaultFontSize,
                             String globalHighlightListId, Boolean darkTheme,
                             Boolean windowMaximized, Integer sidebarWidth,
                             Integer windowX, Integer windowY,
                             Integer windowWidth, Integer windowHeight,
                             String defaultUsername, String defaultTabColorHex,
                             Boolean openTerminalOnStartup, String defaultKeyPath,
                             Boolean autoAcceptNewHostKeys, Integer scrollbackLines,
                             Boolean middleClickPaste, Integer uiScalePercent,
                             String uiFontFamily, Integer uiFontSize,
                             Boolean promptPasswordOnAuthFailure, Boolean sidebarVisible,
                             Boolean blinkCursor, Integer defaultKeepAliveSeconds,
                             Boolean showWorkingDirectory, Boolean updateCheckEnabled,
                             Long lastUpdateCheckEpochSeconds, String skippedUpdateVersion,
                             Boolean encryptMacros) {
    }
}
