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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.katmoda.jterm.config.AppPaths;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
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

    private static final int PBKDF2_ITERATIONS = 600_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Base64.Encoder B64E = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private final Path file = AppPaths.file("credentials.json");
    private final SecureRandom random = new SecureRandom();

    private VaultFile data;       // persisted structure (null until loaded)
    private SecretKey vaultKey;   // in-memory only, present once unlocked

    public CredentialVault() {
        this.data = load();
    }

    public boolean isInitialized() {
        return data != null && data.wrappedKey != null;
    }

    public boolean isUnlocked() {
        return vaultKey != null;
    }

    /** Create a brand-new vault protected by {@code masterPassword}. */
    public void initialize(char[] masterPassword) throws VaultException {
        try {
            byte[] rawVaultKey = new byte[KEY_BITS / 8];
            random.nextBytes(rawVaultKey);
            SecretKey vk = new SecretKeySpec(rawVaultKey, "AES");

            VaultFile vf = new VaultFile();
            vf.salt = B64E.encodeToString(randomBytes(SALT_BYTES));
            vf.iterations = PBKDF2_ITERATIONS;
            SecretKey kek = deriveKek(masterPassword, B64D.decode(vf.salt), vf.iterations);
            vf.wrappedKey = encrypt(kek, rawVaultKey);
            vf.passwords = new LinkedHashMap<>();

            this.data = vf;
            this.vaultKey = vk;
            save();
        } catch (Exception e) {
            throw new VaultException("Failed to initialize vault", e);
        }
    }

    /** Unlock an existing vault. @return true on success, false if the password is wrong. */
    public boolean unlock(char[] masterPassword) throws VaultException {
        if (!isInitialized()) {
            throw new VaultException("Vault is not initialized");
        }
        try {
            SecretKey kek = deriveKek(masterPassword, B64D.decode(data.salt), data.iterations);
            byte[] raw = decrypt(kek, data.wrappedKey);
            this.vaultKey = new SecretKeySpec(raw, "AES");
            return true;
        } catch (javax.crypto.AEADBadTagException badTag) {
            return false; // wrong master password
        } catch (Exception e) {
            throw new VaultException("Failed to unlock vault", e);
        }
    }

    public void lock() {
        vaultKey = null;
    }

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
        try {
            byte[] bytes = new String(password).getBytes(StandardCharsets.UTF_8);
            data.passwords.put(id, encrypt(vaultKey, bytes));
            save();
        } catch (Exception e) {
            throw new VaultException("Failed to encrypt password", e);
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

    // ---- crypto helpers ----

    private void requireUnlocked() throws VaultException {
        if (!isUnlocked()) {
            throw new VaultException("Vault is locked");
        }
    }

    private static SecretKey deriveKek(char[] password, byte[] salt, int iterations) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        byte[] derived = factory.generateSecret(spec).getEncoded();
        spec.clearPassword();
        return new SecretKeySpec(derived, "AES");
    }

    private Blob encrypt(SecretKey key, byte[] plaintext) throws Exception {
        byte[] nonce = randomBytes(GCM_NONCE_BYTES);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        byte[] ct = cipher.doFinal(plaintext);
        Blob blob = new Blob();
        blob.nonce = B64E.encodeToString(nonce);
        blob.ciphertext = B64E.encodeToString(ct);
        return blob;
    }

    private byte[] decrypt(SecretKey key, Blob blob) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, B64D.decode(blob.nonce)));
        return cipher.doFinal(B64D.decode(blob.ciphertext));
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    // ---- persistence ----

    private VaultFile load() {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return MAPPER.readValue(file.toFile(), VaultFile.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void save() {
        try {
            MAPPER.writeValue(file.toFile(), data);
            restrictPermissions();
        } catch (Exception ignored) {
        }
    }

    private void restrictPermissions() {
        try {
            Files.setPosixFilePermissions(file,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (Exception ignored) {
            // Non-POSIX filesystem (e.g. Windows) — rely on the user profile's ACLs.
        }
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
