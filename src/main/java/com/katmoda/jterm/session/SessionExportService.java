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
import com.katmoda.jterm.security.VaultKeys;

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

    /**
     * Shared folders are machine-specific, and an import must never share a folder the user did
     * not choose on this machine: {@code driveShares} is left out of exports and ignored in imports.
     * The same goes for {@code useMicrophone}: an import must never arrive able to reach the local
     * microphone; for {@code useKerberos}, which runs the session's helper with less isolation; and for
     * {@code autoPrintPrinter}, a local printer's name that would let an import print without asking;
     * and for {@code useSmartCards} and {@code smartCardReaders}, which reach the local card readers.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
            .addMixIn(RdpSessionConfig.class, WithoutDriveShares.class);

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(value = {"driveShares", "useMicrophone", "useKerberos", "autoPrintPrinter",
            "useSmartCards", "smartCardReaders"}, ignoreUnknown = true)
    private abstract static class WithoutDriveShares {
    }

    private SessionExportService() {
    }

    // ---- export ----

    /**
     * Gathers plaintext passwords for every password-saving SSH/RDP session under {@code folder} into
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
            } else if (child instanceof RdpSessionConfig rdp && rdp.isSavePassword()) {
                String password = vault.getPassword(VaultKeys.sessionPassword(rdp.getId()));
                if (password != null) {
                    out.put(rdp.getId(), password);
                }
            }
        }
    }

    /**
     * Writes {@code export} as plaintext JSON (holds no secrets) and restricts it to owner-only.
     * Use only when {@code export.credentials} is empty.
     */
    public static void writePlain(Path target, SessionExport export) throws IOException {
        MAPPER.writeValue(target.toFile(), withoutUnknownNodes(export));
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
        byte[] plaintext = null;
        try {
            plaintext = MAPPER.writeValueAsBytes(withoutUnknownNodes(export));
            EncryptedSessionExport envelope =
                    new EncryptedSessionExport(PassphraseBox.seal(passphrase, plaintext));
            MAPPER.writeValue(target.toFile(), envelope);
            AppPaths.restrictToOwner(target);
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
            Arrays.fill(passphrase, '\0');
            export.credentials.clear();
        }
    }

    /**
     * {@code export} with every {@link UnknownSessionNode} left out. The placeholder is kept in
     * {@code sessions.json} only so a newer jterm finds its own node again; this build can't vouch
     * for what it holds (its id can't be reassigned on import, and its credentials, if any, aren't
     * collected), so it doesn't travel. The live tree is not touched: when there is something to
     * leave out, the folder is copied through the export mapper and the copy pruned.
     */
    private static SessionExport withoutUnknownNodes(SessionExport export) {
        if (export.folder == null || !containsUnknownNodes(export.folder)) {
            return export;
        }
        SessionExport copy = new SessionExport();
        copy.root = export.root;
        copy.credentials = export.credentials;
        copy.folder = MAPPER.convertValue(MAPPER.valueToTree(export.folder), FolderNode.class);
        removeUnknownNodes(copy.folder);
        return copy;
    }

    private static boolean containsUnknownNodes(FolderNode folder) {
        for (SessionNode child : folder.getChildren()) {
            if (child instanceof UnknownSessionNode
                    || (child instanceof FolderNode sub && containsUnknownNodes(sub))) {
                return true;
            }
        }
        return false;
    }

    /** Removes every {@link UnknownSessionNode} under {@code folder}, returning how many went. */
    private static int removeUnknownNodes(FolderNode folder) {
        int removed = 0;
        for (var it = folder.getChildren().iterator(); it.hasNext(); ) {
            SessionNode child = it.next();
            if (child instanceof UnknownSessionNode) {
                it.remove();
                removed++;
            } else if (child instanceof FolderNode sub) {
                removed += removeUnknownNodes(sub);
            }
        }
        return removed;
    }

    // ---- import ----

    /**
     * An import is untrusted input: a node type this build doesn't know would otherwise arrive as an
     * {@link UnknownSessionNode} and be written into {@code sessions.json} unexamined, for a newer
     * jterm to load as one of its own sessions. Such nodes are dropped (an export from this build
     * never contains one; see {@link #withoutUnknownNodes}).
     */
    private static SessionExport dropUnknownNodes(SessionExport export) {
        if (export != null && export.folder != null) {
            int removed = removeUnknownNodes(export.folder);
            if (removed > 0) {
                LOG.info("Import: left out {} saved-session node(s) of a type this version doesn't know", removed);
            }
        }
        return export;
    }

    /** Whether {@code file} is an encrypted-export envelope (vs. a plaintext {@link SessionExport}). */
    public static boolean isEncrypted(File file) throws IOException {
        JsonNode root = MAPPER.readTree(file);
        return root != null && root.hasNonNull("format")
                && EncryptedSessionExport.FORMAT.equals(root.get("format").asText());
    }

    /** Parses a plaintext {@link SessionExport} document. */
    public static SessionExport readPlain(File file) throws IOException {
        return dropUnknownNodes(MAPPER.readValue(file, SessionExport.class));
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
            return dropUnknownNodes(MAPPER.readValue(plaintext, SessionExport.class));
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
        Map<String, String> sshIds = new java.util.HashMap<>();
        reassignIds(folder, oldCreds, newCreds, sshIds);
        relinkSshTunnels(folder, sshIds);
    }

    /**
     * An RDP session that connects through an SSH session imported alongside it must follow that
     * session to its new id. One that points outside the import keeps its id: it may name a session
     * the user already has, and if not, connecting says the SSH session no longer exists.
     */
    private static void relinkSshTunnels(FolderNode folder, Map<String, String> sshIds) {
        for (SessionNode child : folder.getChildren()) {
            if (child instanceof FolderNode sub) {
                relinkSshTunnels(sub, sshIds);
            } else if (child instanceof RdpSessionConfig rdp && rdp.isViaSsh()) {
                String moved = sshIds.get(rdp.getSshTunnelSessionId());
                if (moved != null) {
                    rdp.setSshTunnelSessionId(moved);
                }
            }
        }
    }

    private static void reassignIds(FolderNode folder, Map<String, String> oldCreds, Map<String, String> newCreds,
                                    Map<String, String> sshIds) {
        // Give the folder a fresh id too, so an imported folder never inherits an existing folder's
        // saved default password / key passphrase (those vault entries are keyed by folder id).
        folder.setId(UUID.randomUUID().toString());
        for (SessionNode child : folder.getChildren()) {
            if (child instanceof FolderNode sub) {
                reassignIds(sub, oldCreds, newCreds, sshIds);
            } else if (child instanceof SshSessionConfig ssh) {
                String oldId = ssh.getId();
                String newId = UUID.randomUUID().toString();
                ssh.setId(newId);
                sshIds.put(oldId, newId);
                String password = (oldCreds != null) ? oldCreds.get(oldId) : null;
                if (password != null) {
                    newCreds.put(newId, password);
                }
            } else if (child instanceof RdpSessionConfig rdp) {
                // Same rule as SSH: an imported RDP session must never share (or inherit) the vault
                // entry of an existing session with the same id.
                String oldId = rdp.getId();
                String newId = UUID.randomUUID().toString();
                rdp.setId(newId);
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
