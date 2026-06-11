package com.katmoda.jterm.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

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

    /** Persist the current values to {@code settings.json} (best-effort). */
    public void save() {
        try {
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValue(file().toFile(), new Persisted(copyOnSelect, pasteOnRightClick));
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
    private record Persisted(boolean copyOnSelect, boolean pasteOnRightClick) {
    }
}
