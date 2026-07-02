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

import com.katmoda.jterm.security.PassphraseBox;

/**
 * On-disk envelope for a passphrase-encrypted session export. The {@link #box} holds the AES-GCM
 * ciphertext of a {@link SessionExport} JSON document (plus its salt/iterations/nonce). The
 * {@link #format} discriminator lets import distinguish an encrypted file from a plaintext
 * {@link SessionExport} without trial-decrypting.
 */
public final class EncryptedSessionExport {

    /** Marker value of {@link #format} for this version of the encrypted format. */
    public static final String FORMAT = "jterm-sessions-encrypted-v1";

    public String format = FORMAT;
    public PassphraseBox.SealedBox box;

    public EncryptedSessionExport() {
    }

    public EncryptedSessionExport(PassphraseBox.SealedBox box) {
        this.box = box;
    }
}
