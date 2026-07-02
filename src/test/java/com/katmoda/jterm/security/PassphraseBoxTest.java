/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.security;

import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PassphraseBoxTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void sealThenOpenRoundTrips() throws Exception {
        byte[] plaintext = bytes("s3cr3t session export payload");
        PassphraseBox.SealedBox box = PassphraseBox.seal("correct horse".toCharArray(), plaintext);
        byte[] opened = PassphraseBox.open("correct horse".toCharArray(), box);
        assertArrayEquals(plaintext, opened);
    }

    @Test
    void wrongPassphraseFailsAuthentication() throws Exception {
        PassphraseBox.SealedBox box = PassphraseBox.seal("right".toCharArray(), bytes("data"));
        assertThrows(AEADBadTagException.class,
                () -> PassphraseBox.open("wrong".toCharArray(), box));
    }

    @Test
    void tamperedCiphertextFailsAuthentication() throws Exception {
        PassphraseBox.SealedBox box = PassphraseBox.seal("pw".toCharArray(), bytes("data"));
        PassphraseBox.SealedBox tampered = new PassphraseBox.SealedBox(
                box.salt(), box.iterations(), box.nonce(),
                box.ciphertext().substring(0, box.ciphertext().length() - 2) + "AA");
        assertThrows(Exception.class, () -> PassphraseBox.open("pw".toCharArray(), tampered));
    }

    @Test
    void everySealUsesFreshSaltAndNonce() throws Exception {
        PassphraseBox.SealedBox a = PassphraseBox.seal("pw".toCharArray(), bytes("data"));
        PassphraseBox.SealedBox b = PassphraseBox.seal("pw".toCharArray(), bytes("data"));
        assertNotEquals(a.salt(), b.salt());
        assertNotEquals(a.nonce(), b.nonce());
        assertNotEquals(a.ciphertext(), b.ciphertext());
    }
}
