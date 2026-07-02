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

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * The one place that does password-based authenticated encryption for jterm.
 *
 * <p>A key is derived from a passphrase via PBKDF2-HMAC-SHA256 and used with AES-256-GCM. Both the
 * low-level primitives (used by {@link CredentialVault} for its two-layer key wrapping and
 * per-entry password encryption) and the self-describing {@link #seal}/{@link #open} convenience
 * (used for encrypted session exports) live here, so nonce sizing, tag length, iteration count and
 * intermediate-key zeroing are defined exactly once.</p>
 */
public final class PassphraseBox {

    public static final int PBKDF2_ITERATIONS = 600_000;
    public static final int KEY_BITS = 256;
    public static final int SALT_BYTES = 16;
    public static final int GCM_NONCE_BYTES = 12;
    public static final int GCM_TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64E = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    private PassphraseBox() {
    }

    /**
     * A passphrase-sealed payload: the ciphertext plus everything needed to reproduce the key
     * (salt, iteration count) and open it (nonce). Base64 fields; safe to serialize as JSON.
     */
    public record SealedBox(String salt, int iterations, String nonce, String ciphertext) {
    }

    /** Encrypt {@code plaintext} under a freshly salted key derived from {@code passphrase}. */
    public static SealedBox seal(char[] passphrase, byte[] plaintext) throws GeneralSecurityException {
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] nonce = randomBytes(GCM_NONCE_BYTES);
        SecretKey key = deriveKey(passphrase, salt, PBKDF2_ITERATIONS);
        try {
            byte[] ct = encrypt(key, nonce, plaintext);
            return new SealedBox(B64E.encodeToString(salt), PBKDF2_ITERATIONS,
                    B64E.encodeToString(nonce), B64E.encodeToString(ct));
        } finally {
            destroy(key);
        }
    }

    /**
     * Decrypt a {@link SealedBox}. A wrong passphrase (or tampered ciphertext) surfaces as
     * {@link javax.crypto.AEADBadTagException}, a {@link GeneralSecurityException} subtype.
     */
    public static byte[] open(char[] passphrase, SealedBox box) throws GeneralSecurityException {
        SecretKey key = deriveKey(passphrase, B64D.decode(box.salt()), box.iterations());
        try {
            return decrypt(key, B64D.decode(box.nonce()), B64D.decode(box.ciphertext()));
        } finally {
            destroy(key);
        }
    }

    // ---- primitives shared with CredentialVault ----

    /** Derive an AES key from {@code passphrase}; the intermediate key bytes are zeroed. */
    public static SecretKey deriveKey(char[] passphrase, byte[] salt, int iterations)
            throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, KEY_BITS);
        byte[] derived = null;
        try {
            derived = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(derived, "AES");
        } finally {
            spec.clearPassword();
            if (derived != null) {
                Arrays.fill(derived, (byte) 0);
            }
        }
    }

    /** AES-256-GCM encrypt; returns ciphertext-with-tag. */
    public static byte[] encrypt(SecretKey key, byte[] nonce, byte[] plaintext)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        return cipher.doFinal(plaintext);
    }

    /** AES-256-GCM decrypt; throws {@link javax.crypto.AEADBadTagException} on auth failure. */
    public static byte[] decrypt(SecretKey key, byte[] nonce, byte[] ciphertext)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        return cipher.doFinal(ciphertext);
    }

    /** {@code n} cryptographically-random bytes from the shared {@link SecureRandom}. */
    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    /** Best-effort zeroing of a {@link SecretKey}'s bytes (no-op if the provider forbids it). */
    private static void destroy(SecretKey key) {
        try {
            key.destroy();
        } catch (Exception ignored) {
            // SecretKeySpec throws DestroyFailedException by default; nothing else to do.
        }
    }
}
