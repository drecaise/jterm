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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.katmoda.jterm.config.AppPaths;
import com.katmoda.jterm.keymap.Keymap;
import com.katmoda.jterm.security.PassphraseBox;

import javax.crypto.AEADBadTagException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Swing-free read/write/merge logic for the macro export/import feature, mirroring
 * {@code session.SessionExportService}.
 *
 * <p>All user interaction (file choosers, passphrase prompts, conflict dialogs, error dialogs) is
 * the caller's responsibility: this class throws or returns instead of showing UI, and never imports
 * {@code javax.swing}/{@code java.awt}. A wrong export passphrase surfaces as a {@code null} return
 * from {@link #openEnvelope} (not an exception), so the caller can re-prompt.</p>
 */
public final class MacroExportService {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private MacroExportService() {
    }

    // ---- export ----

    /** Builds an export document from {@code macros} (which must already be plaintext). */
    public static MacroExport build(List<Macro> macros) {
        MacroExport export = new MacroExport();
        export.schemaVersion = MacroMigrations.CURRENT_VERSION;
        export.macros = new ArrayList<>(macros);
        return export;
    }

    /** Writes {@code export} as plaintext JSON and restricts it to owner-only. */
    public static void writePlain(Path target, MacroExport export) throws IOException {
        MAPPER.writeValue(target.toFile(), export);
        AppPaths.restrictToOwner(target);
    }

    /**
     * Encrypts the whole {@code export} document under {@code passphrase} (AES-GCM via
     * {@link PassphraseBox}), writes the {@link EncryptedMacroExport} envelope, and restricts it to
     * owner-only. Clears {@code passphrase} before returning, whether it succeeds or fails.
     */
    public static void writeEncrypted(Path target, MacroExport export, char[] passphrase)
            throws IOException, GeneralSecurityException {
        byte[] plaintext = MAPPER.writeValueAsBytes(export);
        try {
            EncryptedMacroExport envelope =
                    new EncryptedMacroExport(PassphraseBox.seal(passphrase, plaintext));
            MAPPER.writeValue(target.toFile(), envelope);
            AppPaths.restrictToOwner(target);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(passphrase, '\0');
        }
    }

    // ---- import ----

    /** Whether {@code file} is an encrypted-export envelope (vs. a plaintext {@link MacroExport}). */
    public static boolean isEncrypted(File file) throws IOException {
        JsonNode root = MAPPER.readTree(file);
        return root != null && root.hasNonNull("format")
                && EncryptedMacroExport.FORMAT.equals(root.get("format").asText());
    }

    /** Parses a plaintext {@link MacroExport} document. */
    public static MacroExport readPlain(File file) throws IOException {
        return MAPPER.readValue(file, MacroExport.class);
    }

    /** Reads the still-sealed {@link EncryptedMacroExport} envelope. */
    public static EncryptedMacroExport readEnvelope(File file) throws IOException {
        return MAPPER.readValue(file, EncryptedMacroExport.class);
    }

    /**
     * Decrypts an encrypted export {@code envelope} with {@code passphrase}. Returns the export, or
     * {@code null} if the passphrase is wrong (so the caller can re-prompt). Any other failure
     * throws. Does not clear {@code passphrase} — the caller owns it.
     *
     * @throws IllegalArgumentException if the envelope is missing its payload.
     */
    public static MacroExport openEnvelope(EncryptedMacroExport envelope, char[] passphrase)
            throws IOException, GeneralSecurityException {
        if (envelope.box == null) {
            throw new IllegalArgumentException("Encrypted export is missing its payload.");
        }
        byte[] plaintext = null;
        try {
            plaintext = PassphraseBox.open(passphrase, envelope.box);
            return MAPPER.readValue(plaintext, MacroExport.class);
        } catch (AEADBadTagException badTag) {
            return null; // wrong passphrase
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    // ---- merge ----

    /** What to do with an imported macro whose id already exists locally. */
    public enum Conflict {
        /** Overwrite the existing macro in place, keeping its position in the list. */
        REPLACE,
        /** Import as a new macro with a fresh id, leaving the existing one alone. */
        KEEP_BOTH,
        /** Discard the imported macro. */
        SKIP
    }

    /** What a {@link #merge} did, so the caller can report it without re-deriving anything. */
    public record MergeResult(List<Macro> macros, int added, int replaced, int skipped,
                              List<String> clearedHotkeys) {
    }

    /**
     * Merges {@code incoming} into {@code existing}, returning the new list — a pure function, so
     * the caller decides whether to commit it and it is unit-testable without the singleton.
     *
     * <p><b>Ids are preserved</b> (unlike a sessions import, which always reassigns them). A session
     * references its run-on-connect macro by id ({@code SshSessionConfig.macroId}), so preserving
     * them is what lets a sessions export and a macros export be moved together and still work. The
     * cost is that an import can collide, which is what {@code policy} resolves; {@link
     * Conflict#KEEP_BOTH} is the one case that does assign a fresh id.</p>
     *
     * <p>Hotkeys are checked separately: an imported hotkey already owned by a keyboard shortcut or
     * by a macro that is staying is <b>cleared</b> on the imported macro rather than left to lose
     * silently at dispatch time. Each cleared binding is named in
     * {@link MergeResult#clearedHotkeys()} so the caller can tell the user.</p>
     *
     * @param policy decides per conflicting macro; called only for ids that actually collide
     * @param keymap checked for shortcut conflicts; may be {@code null} to skip that check
     */
    public static MergeResult merge(List<Macro> incoming, List<Macro> existing,
                                    Function<Macro, Conflict> policy, Keymap keymap) {
        List<Macro> result = new ArrayList<>(existing);
        Map<String, Integer> indexById = new LinkedHashMap<>();
        for (int i = 0; i < result.size(); i++) {
            indexById.put(result.get(i).getId(), i);
        }

        int added = 0;
        int replaced = 0;
        int skipped = 0;
        List<String> clearedHotkeys = new ArrayList<>();

        for (Macro candidate : incoming) {
            Macro macro = candidate.copy();
            Integer existingIndex = indexById.get(macro.getId());
            Conflict decision = (existingIndex != null) ? policy.apply(macro) : null;

            if (decision == Conflict.SKIP) {
                skipped++;
                continue;
            }
            if (decision == Conflict.KEEP_BOTH) {
                macro.setId(UUID.randomUUID().toString());
                existingIndex = null;
            }

            // Rewrite the hotkey into the form the dispatcher matches on before checking it: an
            // imported file may spell a stroke differently, and a hotkey left in another spelling is
            // bound to a key that can never fire it.
            if (macro.getHotkey() != null) {
                String canonical = MacroHotkeys.canonical(macro.getHotkey());
                if (canonical == null) {
                    clearedHotkeys.add(macro.getName() + " (unrecognised hotkey \""
                            + macro.getHotkey() + "\")");
                }
                macro.setHotkey(canonical);
            }

            // Resolve the hotkey against everything that will still be there afterwards.
            String conflict = MacroHotkeys.conflictFor(macro.getHotkey(), keymap,
                    othersThan(result, existingIndex));
            if (conflict != null) {
                clearedHotkeys.add(macro.getName() + " (was bound to " + conflict + ")");
                macro.setHotkey(null);
            }

            if (existingIndex != null) {
                result.set(existingIndex, macro);
                replaced++;
            } else {
                indexById.put(macro.getId(), result.size());
                result.add(macro);
                added++;
            }
        }
        return new MergeResult(result, added, replaced, skipped, clearedHotkeys);
    }

    /** Everything in {@code all} except the entry at {@code excludeIndex} (which may be null). */
    private static List<Macro> othersThan(List<Macro> all, Integer excludeIndex) {
        if (excludeIndex == null) {
            return all;
        }
        List<Macro> others = new ArrayList<>(all);
        others.remove(excludeIndex.intValue());
        return others;
    }
}
