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
package com.katmoda.jterm.terminal;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Per-session terminal/rendering settings. Connect-time fields ({@link #terminalType},
 * {@link #charset}) are consumed by the session back-end; {@link #fontFamily}/{@link #fontSize}
 * are read by the UI when building the terminal widget.
 *
 * <p>A {@code null} {@link #fontFamily} or non-positive {@link #fontSize} means "use the
 * application default".</p>
 */
public record TerminalProfile(String terminalType, Charset charset, String fontFamily, int fontSize) {

    public static final TerminalProfile DEFAULT =
            new TerminalProfile("xterm-256color", StandardCharsets.UTF_8, null, 0);

    /** Builds a profile from raw config values, applying defaults and resolving the charset. */
    public static TerminalProfile from(String terminalType, String charsetName,
                                       String fontFamily, int fontSize) {
        String type = (terminalType != null && !terminalType.isBlank())
                ? terminalType.trim() : DEFAULT.terminalType();
        Charset charset = DEFAULT.charset();
        if (charsetName != null && !charsetName.isBlank()) {
            try {
                charset = Charset.forName(charsetName.trim());
            } catch (Exception ignored) {
                // Unknown/unsupported name → keep the UTF-8 default.
            }
        }
        String family = (fontFamily != null && !fontFamily.isBlank()) ? fontFamily.trim() : null;
        return new TerminalProfile(type, charset, family, Math.max(fontSize, 0));
    }
}
