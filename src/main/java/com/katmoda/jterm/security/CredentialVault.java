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
package com.katmoda.jterm.security;

import com.katmoda.jterm.config.AppPaths;
import com.katmoda.jterm.config.JsonStore;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encrypted store for saved SSH passwords ({@code credentials.json}).
 *
 * <p>A random 256-bit <em>vault key</em> encrypts each password (AES-GCM, per-entry nonce).
 * The vault key itself is wrapped (AES-GCM) by a key-encryption-key derived from the user's
 * master password via PBKDF2-HMAC-SHA256. Only ciphertext, salt and nonces are persisted —
 * never the master password or the vault key in plaintext.</p>
 *
 * <p>Unlock requires the master password (supplied directly, or fetched transparently from
 * the OS keyring by {@link VaultManager}).</p>
 */
public final class CredentialVault {

    private static final Base64.Encoder B64E = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private final Path file;

    private VaultFile data;       // persisted structure (null until loaded)
    private SecretKey vaultKey;   // in-memory only, present once unlocked
    private final boolean loadFailed; // true if credentials.json existed but was unreadable/corrupt

    public CredentialVault() {
        this(AppPaths.file("credentials.json"));
    }

    /** Test seam: back the vault with an explicit file instead of the real config path. */
    CredentialVault(Path file) {
        this.file = file;
        // Distinguish a missing file (fresh vault may be created) from a corrupt one (the vault must
        // be treated as unavailable, never silently re-initialized over the — now preserved — file).
        JsonStore.LoadResult<VaultFile> result = JsonStore.loadResult(file, VaultFile.class);
        this.data = result.value();
        this.loadFailed = result.isCorrupt();
    }

    public boolean isInitialized() {
        return data != null && data.wrappedKey != null;
    }

    /**
     * Whether {@code credentials.json} existed but could not be read (corrupt/unparsable). Distinct
     * from a missing file: the vault is unavailable rather than uninitialized, so callers must surface
     * an error instead of creating a fresh vault (which would leave the user's saved passwords behind
     * in the preserved {@code credentials.json.unreadable-*} backup). See {@link VaultManager}.
     */
    public boolean isLoadFailed() {
        return loadFailed;
    }

    public boolean isUnlocked() {
        return vaultKey != null;
    }

    /** Create a brand-new vault protected by {@code masterPassword}. */
    public void initialize(char[] masterPassword) throws VaultException {
        if (loadFailed) {
            // The existing credentials.json was unreadable; refuse to replace it with a fresh vault.
            throw new VaultException("Credential vault file is unreadable; refusing to overwrite it");
        }
        byte[] rawVaultKey = PassphraseBox.randomBytes(PassphraseBox.KEY_BITS / 8);
        try {
            SecretKey vk = new SecretKeySpec(rawVaultKey, "AES");

            VaultFile vf = new VaultFile();
            byte[] salt = PassphraseBox.randomBytes(PassphraseBox.SALT_BYTES);
            vf.salt = B64E.encodeToString(salt);
            vf.iterations = PassphraseBox.PBKDF2_ITERATIONS;
            SecretKey kek = PassphraseBox.deriveKey(masterPassword, salt, vf.iterations);
            vf.wrappedKey = encrypt(kek, rawVaultKey);
            vf.passwords = new LinkedHashMap<>();

            this.data = vf;
            this.vaultKey = vk;
            save();
        } catch (Exception e) {
            throw new VaultException("Failed to initialize vault", e);
        } finally {
            Arrays.fill(rawVaultKey, (byte) 0);
        }
    }

    /** Unlock an existing vault. @return true on success, false if the password is wrong. */
    public boolean unlock(char[] masterPassword) throws VaultException {
        if (!isInitialized()) {
            throw new VaultException("Vault is not initialized");
        }
        byte[] raw = null;
        try {
            SecretKey kek = PassphraseBox.deriveKey(masterPassword, B64D.decode(data.salt), data.iterations);
            raw = decrypt(kek, data.wrappedKey);
            this.vaultKey = new SecretKeySpec(raw, "AES");
            return true;
        } catch (javax.crypto.AEADBadTagException badTag) {
            return false; // wrong master password
        } catch (Exception e) {
            throw new VaultException("Failed to unlock vault", e);
        } finally {
            if (raw != null) {
                Arrays.fill(raw, (byte) 0);
            }
        }
    }

    public void lock() {
        vaultKey = null;
    }

    /**
     * The stored password for {@code id}, or {@code null} if none. Returns a {@link String}
     * deliberately: the value's terminal consumer is MINA's {@code addPasswordIdentity(String)}
     * (see {@code SshConnect}), so a {@code char[]} would be copied straight into an immutable
     * {@code String} at that boundary anyway. Zeroing effort is concentrated on the master-password
     * / key-derivation path in {@link PassphraseBox}, where it actually pays off.
     */
    public String getPassword(String id) throws VaultException {
        requireUnlocked();
        Blob blob = data.passwords.get(id);
        if (blob == null) {
            return null;
        }
        try {
            return new String(decrypt(vaultKey, blob), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new VaultException("Failed to decrypt password", e);
        }
    }

    public void setPassword(String id, char[] password) throws VaultException {
        requireUnlocked();
        byte[] bytes = utf8(password);
        try {
            data.passwords.put(id, encrypt(vaultKey, bytes));
            save();
        } catch (Exception e) {
            throw new VaultException("Failed to encrypt password", e);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public void removePassword(String id) {
        if (data != null && data.passwords != null && data.passwords.remove(id) != null) {
            save();
        }
    }

    public boolean hasPassword(String id) {
        return data != null && data.passwords != null && data.passwords.containsKey(id);
    }

    // ---- arbitrary blobs under the vault key ----

    /**
     * AES-GCM encrypts {@code plaintext} under the vault key, binding {@code aad} into the tag.
     *
     * <p>Exists so features other than saved passwords can protect data at rest without inventing a
     * second secret: the caller never sees the master password or the vault key, it just needs the
     * vault unlocked ({@link VaultManager#ensureUnlocked}). Used by {@code macro.MacroCrypto} to seal
     * macro steps. Pass the owning record's id as {@code aad} — see
     * {@link PassphraseBox#encrypt(SecretKey, byte[], byte[], byte[])} for why that matters.</p>
     */
    public Blob seal(byte[] plaintext, byte[] aad) throws VaultException {
        requireUnlocked();
        try {
            byte[] nonce = PassphraseBox.randomBytes(PassphraseBox.GCM_NONCE_BYTES);
            Blob blob = new Blob();
            blob.nonce = B64E.encodeToString(nonce);
            blob.ciphertext = B64E.encodeToString(
                    PassphraseBox.encrypt(vaultKey, nonce, plaintext, aad));
            return blob;
        } catch (Exception e) {
            throw new VaultException("Failed to encrypt data", e);
        }
    }

    /**
     * Decrypts a {@link #seal}ed blob. A wrong {@code aad} — a blob moved to another record's slot —
     * fails the same way a tampered ciphertext does.
     *
     * @throws javax.crypto.AEADBadTagException if the blob does not authenticate
     */
    public byte[] open(Blob blob, byte[] aad) throws VaultException, GeneralSecurityException {
        requireUnlocked();
        if (blob == null || blob.nonce == null || blob.ciphertext == null) {
            throw new VaultException("Encrypted data is missing its nonce or ciphertext");
        }
        return PassphraseBox.decrypt(vaultKey, B64D.decode(blob.nonce),
                B64D.decode(blob.ciphertext), aad);
    }

    // ---- crypto helpers ----

    private void requireUnlocked() throws VaultException {
        if (!isUnlocked()) {
            throw new VaultException("Vault is locked");
        }
    }

    /** AES-GCM encrypt {@code plaintext} under {@code key} into a persisted {@link Blob}. */
    private static Blob encrypt(SecretKey key, byte[] plaintext) throws Exception {
        byte[] nonce = PassphraseBox.randomBytes(PassphraseBox.GCM_NONCE_BYTES);
        byte[] ct = PassphraseBox.encrypt(key, nonce, plaintext);
        Blob blob = new Blob();
        blob.nonce = B64E.encodeToString(nonce);
        blob.ciphertext = B64E.encodeToString(ct);
        return blob;
    }

    private static byte[] decrypt(SecretKey key, Blob blob) throws Exception {
        return PassphraseBox.decrypt(key, B64D.decode(blob.nonce), B64D.decode(blob.ciphertext));
    }

    private static byte[] utf8(char[] chars) {
        java.nio.ByteBuffer buf = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars));
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        Arrays.fill(buf.array(), (byte) 0);
        return bytes;
    }

    // ---- persistence ----

    private void save() {
        JsonStore.save(file, data);
    }

    // ---- persisted shapes ----

    /** AES-GCM blob (base64). */
    public static final class Blob {
        public String nonce;
        public String ciphertext;
    }

    /** Root of credentials.json. */
    public static final class VaultFile {
        public String salt;
        public int iterations;
        public Blob wrappedKey;
        public Map<String, Blob> passwords = new LinkedHashMap<>();
    }
}
