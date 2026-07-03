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
package com.katmoda.jterm.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.katmoda.jterm.config.AppPaths;
import com.katmoda.jterm.security.CredentialVault;
import com.katmoda.jterm.security.PassphraseBox;
import com.katmoda.jterm.security.VaultException;

import javax.crypto.AEADBadTagException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Swing-free read/write/build logic for the saved-sessions export/import feature. Handles the
 * on-disk file formats — plaintext {@link SessionExport} JSON and the AES-GCM-encrypted
 * {@link EncryptedSessionExport} envelope — plus the vault reconciliation an import/export needs.
 *
 * <p>All user interaction (file choosers, master-password / passphrase prompts, error dialogs) is
 * the caller's responsibility: this class throws or returns instead of showing UI, and never
 * imports {@code javax.swing}/{@code java.awt}. A wrong export passphrase surfaces as a
 * {@code null} return from {@link #openEnvelope} (not an exception), so the caller can re-prompt.</p>
 */
public final class SessionExportService {

    private static final Logger LOG = LoggerFactory.getLogger(SessionExportService.class);

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private SessionExportService() {
    }

    // ---- export ----

    /**
     * Gathers plaintext passwords for every password-saving SSH session under {@code folder} into
     * {@code out}, keyed by session id. Requires an already-unlocked {@code vault}.
     */
    public static void collectCredentials(FolderNode folder, CredentialVault vault, Map<String, String> out)
            throws VaultException {
        for (SessionNode child : folder.getChildren()) {
            if (child instanceof FolderNode sub) {
                collectCredentials(sub, vault, out);
            } else if (child instanceof SshSessionConfig ssh && ssh.isSavePassword()) {
                String password = vault.getPassword(ssh.getId());
                if (password != null) {
                    out.put(ssh.getId(), password);
                }
            }
        }
    }

    /**
     * Writes {@code export} as plaintext JSON (holds no secrets) and restricts it to owner-only.
     * Use only when {@code export.credentials} is empty.
     */
    public static void writePlain(Path target, SessionExport export) throws IOException {
        MAPPER.writeValue(target.toFile(), export);
        AppPaths.restrictToOwner(target);
    }

    /**
     * Encrypts the whole {@code export} document under {@code passphrase} (AES-GCM via
     * {@link PassphraseBox}), writes the {@link EncryptedSessionExport} envelope, and restricts it
     * to owner-only. Clears {@code passphrase} and {@code export.credentials} (the plaintext-password
     * references) before returning, whether it succeeds or fails.
     */
    public static void writeEncrypted(Path target, SessionExport export, char[] passphrase)
            throws IOException, GeneralSecurityException {
        byte[] plaintext = MAPPER.writeValueAsBytes(export);
        try {
            EncryptedSessionExport envelope =
                    new EncryptedSessionExport(PassphraseBox.seal(passphrase, plaintext));
            MAPPER.writeValue(target.toFile(), envelope);
            AppPaths.restrictToOwner(target);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(passphrase, '\0');
            export.credentials.clear();
        }
    }

    // ---- import ----

    /** Whether {@code file} is an encrypted-export envelope (vs. a plaintext {@link SessionExport}). */
    public static boolean isEncrypted(File file) throws IOException {
        JsonNode root = MAPPER.readTree(file);
        return root != null && root.hasNonNull("format")
                && EncryptedSessionExport.FORMAT.equals(root.get("format").asText());
    }

    /** Parses a plaintext {@link SessionExport} document. */
    public static SessionExport readPlain(File file) throws IOException {
        return MAPPER.readValue(file, SessionExport.class);
    }

    /** Reads the still-sealed {@link EncryptedSessionExport} envelope. */
    public static EncryptedSessionExport readEnvelope(File file) throws IOException {
        return MAPPER.readValue(file, EncryptedSessionExport.class);
    }

    /**
     * Decrypts an encrypted export {@code envelope} with {@code passphrase}. Returns the export, or
     * {@code null} if the passphrase is wrong (so the caller can re-prompt). Any other failure
     * throws. Does not clear {@code passphrase} — the caller owns it.
     *
     * @throws IllegalArgumentException if the envelope is missing its payload.
     */
    public static SessionExport openEnvelope(EncryptedSessionExport envelope, char[] passphrase)
            throws IOException, GeneralSecurityException {
        if (envelope.box == null) {
            throw new IllegalArgumentException("Encrypted export is missing its payload.");
        }
        byte[] plaintext = null;
        try {
            plaintext = PassphraseBox.open(passphrase, envelope.box);
            return MAPPER.readValue(plaintext, SessionExport.class);
        } catch (AEADBadTagException badTag) {
            return null; // wrong passphrase
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    /**
     * Gives {@code folder} and every folder/session beneath it fresh ids so an import never collides
     * with (or inherits) an existing vault entry, remapping any imported credentials from their old
     * session id in {@code oldCreds} onto the new id in {@code newCreds}.
     */
    public static void reassignIds(FolderNode folder, Map<String, String> oldCreds, Map<String, String> newCreds) {
        // Give the folder a fresh id too, so an imported folder never inherits an existing folder's
        // saved default password / key passphrase (those vault entries are keyed by folder id).
        folder.setId(UUID.randomUUID().toString());
        for (SessionNode child : folder.getChildren()) {
            if (child instanceof FolderNode sub) {
                reassignIds(sub, oldCreds, newCreds);
            } else if (child instanceof SshSessionConfig ssh) {
                String oldId = ssh.getId();
                String newId = UUID.randomUUID().toString();
                ssh.setId(newId);
                String password = (oldCreds != null) ? oldCreds.get(oldId) : null;
                if (password != null) {
                    newCreds.put(newId, password);
                }
            }
        }
    }

    /**
     * Stores {@code credentials} (id → plaintext password) into an already-unlocked {@code vault}.
     * A single bad entry is skipped (logged) rather than aborting the whole import. Clears each
     * plaintext password after storing it.
     */
    public static void storeCredentials(CredentialVault vault, Map<String, String> credentials) {
        for (Map.Entry<String, String> entry : credentials.entrySet()) {
            char[] password = entry.getValue().toCharArray();
            try {
                vault.setPassword(entry.getKey(), password);
            } catch (VaultException e) {
                // Skip a single bad entry rather than abort the whole import.
                LOG.warn("failed to import a credential entry into the vault", e);
            } finally {
                Arrays.fill(password, '\0');
            }
        }
    }
}
