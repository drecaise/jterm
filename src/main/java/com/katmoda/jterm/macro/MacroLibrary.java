package com.katmoda.jterm.macro;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.katmoda.jterm.config.AppPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The user's macros, persisted as {@code macros.json} in the config dir. A small mutable
 * singleton (mirroring {@code icon.IconLibrary} / {@code security.VaultManager}) read live by
 * the Macros menu, the global hotkey dispatcher, and the session run-on-connect lookup.
 */
public final class MacroLibrary {

    private static final MacroLibrary INSTANCE = load();

    private final List<Macro> macros = new ArrayList<>();

    private MacroLibrary() {
    }

    public static MacroLibrary get() {
        return INSTANCE;
    }

    /** Live view of the macros (do not mutate directly; use add/remove/replace). */
    public List<Macro> macros() {
        return macros;
    }

    public Macro byId(String id) {
        if (id == null) {
            return null;
        }
        for (Macro m : macros) {
            if (m.getId().equals(id)) {
                return m;
            }
        }
        return null;
    }

    /** The first macro bound to {@code strokeString} ({@code KeyStroke.toString()}), or null. */
    public Macro byHotkey(String strokeString) {
        if (strokeString == null || strokeString.isBlank()) {
            return null;
        }
        for (Macro m : macros) {
            if (strokeString.equals(m.getHotkey())) {
                return m;
            }
        }
        return null;
    }

    public void add(Macro macro) {
        macros.add(macro);
    }

    public void remove(Macro macro) {
        macros.removeIf(m -> m.getId().equals(macro.getId()));
    }

    /** Replaces the stored macro with the same id (or appends if not present). */
    public void replace(Macro macro) {
        for (int i = 0; i < macros.size(); i++) {
            if (macros.get(i).getId().equals(macro.getId())) {
                macros.set(i, macro);
                return;
            }
        }
        macros.add(macro);
    }

    /** Persist the current macros to {@code macros.json} (best-effort). */
    public void save() {
        try {
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValue(file().toFile(), macros);
        } catch (Exception ignored) {
            // Macros are a convenience; a failed write shouldn't break the app.
        }
    }

    private static MacroLibrary load() {
        MacroLibrary library = new MacroLibrary();
        Path file = file();
        if (Files.isRegularFile(file)) {
            try {
                List<Macro> loaded = new ObjectMapper()
                        .readValue(file.toFile(), new TypeReference<List<Macro>>() { });
                library.macros.addAll(loaded);
            } catch (Exception ignored) {
                // Fall back to an empty list on a malformed file.
            }
        }
        return library;
    }

    private static Path file() {
        return AppPaths.file("macros.json");
    }
}
