package com.katmoda.jterm.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.katmoda.jterm.terminal.TerminalProfile;
import com.katmoda.jterm.ui.theme.FontResources;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * General application preferences persisted as {@code settings.json} in the config dir.
 *
 * <p>A small mutable singleton: values are read live by the terminal settings provider
 * ({@code copyOnSelect}) and the right-click paste handler ({@code pasteOnRightClick}),
 * so toggling them takes effect without recreating panes. Both default off, matching the
 * prior behavior.</p>
 */
public final class AppSettings {

    private static final AppSettings INSTANCE = load();

    private boolean copyOnSelect = false;
    private boolean pasteOnRightClick = false;

    // Default terminal settings applied to the local terminal and to any saved session that
    // leaves a field unset ("inherit"). The font defaults to the bundled MobaFont.
    private String defaultTerminalType = "xterm-256color";
    private String defaultCharset = "UTF-8";
    private String defaultFontFamily = FontResources.DEFAULT_TERMINAL_FONT_FAMILY;
    private int defaultFontSize = 14;

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
        try {
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValue(file().toFile(), new Persisted(copyOnSelect, pasteOnRightClick,
                            defaultTerminalType, defaultCharset, defaultFontFamily, defaultFontSize));
        } catch (Exception ignored) {
            // Settings are a convenience; a failed write shouldn't break the app.
        }
    }

    private static AppSettings load() {
        AppSettings settings = new AppSettings();
        Path file = file();
        if (Files.isRegularFile(file)) {
            try {
                Persisted p = new ObjectMapper().readValue(file.toFile(), Persisted.class);
                settings.copyOnSelect = p.copyOnSelect;
                settings.pasteOnRightClick = p.pasteOnRightClick;
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
            } catch (Exception ignored) {
                // Fall back to defaults on a malformed file.
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
                             String defaultFontFamily, int defaultFontSize) {
    }
}
