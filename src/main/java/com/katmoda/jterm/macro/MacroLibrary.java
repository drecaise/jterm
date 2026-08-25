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
package com.katmoda.jterm.macro;

import com.katmoda.jterm.config.AppPaths;
import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.config.JsonStore;
import com.katmoda.jterm.security.CredentialVault;
import com.katmoda.jterm.security.VaultException;
import com.katmoda.jterm.security.VaultManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The user's macros, persisted as {@code macros.json} in the config dir. A small mutable
 * singleton (mirroring {@code icon.IconLibrary} / {@code security.VaultManager}) read live by
 * the Macros menu, the global hotkey dispatcher, and the session run-on-connect lookup.
 *
 * <p>The file is schema-versioned (see {@link MacroMigrations}) and its steps may be encrypted at
 * rest (see {@link MacroCrypto}). Encryption is a <em>storage</em> concern only: names and hotkeys
 * stay readable here, so nothing in the menu or hotkey path needs the vault.</p>
 */
public final class MacroLibrary {

    private static final Logger LOG = LoggerFactory.getLogger(MacroLibrary.class);

    private static final MacroLibrary INSTANCE = load();

    private final List<Macro> macros = new ArrayList<>();

    /**
     * True when {@code macros.json} existed but could not be parsed. It has been preserved as
     * {@code macros.json.unreadable-N} and this library is empty — so saving would write an empty
     * list over the user's macros while their only remaining copy sits in a backup file they have
     * not been told about. Every write is refused in that state, and the UI reports it. This mirrors
     * {@code CredentialVault.isLoadFailed()}, and matters more now that the file may hold the only
     * readable copy of a macro's contents.
     */
    private boolean loadFailed;

    private MacroLibrary() {
    }

    public static MacroLibrary get() {
        return INSTANCE;
    }

    /** Live view of the macros (do not mutate directly; use add/remove/replace). */
    public List<Macro> macros() {
        return macros;
    }

    /** See the {@code loadFailed} field. */
    public boolean isLoadFailed() {
        return loadFailed;
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

    /** Swaps in a whole new macro list (used by import). */
    public void replaceAll(List<Macro> replacement) {
        macros.clear();
        if (replacement != null) {
            macros.addAll(replacement);
        }
    }

    /**
     * Persist the current macros to {@code macros.json}.
     *
     * <p>When {@code AppSettings.isEncryptMacros()} is on, any macro currently held in plaintext is
     * sealed first — so a macro that was opened for editing never lands back on disk readable. That
     * needs an unlocked vault; callers that edit macros must have unlocked it already
     * ({@code VaultManager.ensureUnlocked}).</p>
     *
     * @return {@code true} if the file was written
     */
    public boolean save() {
        if (loadFailed) {
            LOG.warn("refusing to save over the preserved unreadable {}", file());
            return false;
        }
        if (AppSettings.get().isEncryptMacros()) {
            try {
                MacroCrypto.sealAll(macros, VaultManager.get().vault());
            } catch (VaultException e) {
                // Writing plaintext here would silently defeat the setting the user turned on, so
                // fail the save instead and let the caller report it.
                LOG.warn("could not seal macros; not saving", e);
                return false;
            }
        }
        return JsonStore.save(file(), new MacroMigrations.Document(
                MacroMigrations.CURRENT_VERSION, macros));
    }

    private static MacroLibrary load() {
        MacroLibrary library = new MacroLibrary();
        Path file = file();
        MacroMigrations.Loaded loaded = MacroMigrations.read(file);
        if (loaded == null) {
            if (Files.isRegularFile(file)) {
                // Present but unparsable: preserve it aside and refuse to write until resolved.
                library.loadFailed = true;
                preserveUnreadable(file);
                LOG.warn("macros.json was unreadable and has been preserved aside");
            }
            return library;
        }
        library.macros.addAll(loaded.macros());
        if (loaded.version() < MacroMigrations.CURRENT_VERSION) {
            // Stamp the version even though the macros themselves are unchanged — that stamp is the
            // only thing making the v0 array→object rewrite one-shot.
            JsonStore.save(file, new MacroMigrations.Document(
                    MacroMigrations.CURRENT_VERSION, library.macros));
        }
        return library;
    }

    /**
     * Renames an unparsable {@code macros.json} aside, choosing the lowest free counter so earlier
     * backups are never overwritten. Duplicates {@code JsonStore}'s handling because the schema sniff
     * reads the file itself rather than going through {@code JsonStore.loadResult}.
     */
    private static void preserveUnreadable(Path file) {
        try {
            for (int counter = 1; ; counter++) {
                Path backup = file.resolveSibling(file.getFileName() + ".unreadable-" + counter);
                if (!Files.exists(backup)) {
                    Files.move(file, backup);
                    return;
                }
            }
        } catch (Exception e) {
            LOG.warn("could not preserve unreadable {}", file, e);
        }
    }

    private static Path file() {
        return AppPaths.file("macros.json");
    }
}
