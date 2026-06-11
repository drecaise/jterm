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
