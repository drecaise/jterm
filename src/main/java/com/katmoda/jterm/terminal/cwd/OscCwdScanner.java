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
package com.katmoda.jterm.terminal.cwd;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A character-at-a-time state machine that picks the two <em>authoritative</em> working-directory
 * escape sequences out of a terminal's output stream:
 *
 * <ul>
 *   <li><b>OSC 7</b> — {@code ESC ] 7 ; file://host/path ST}, the VTE convention. JediTerm 3.70
 *       parses this and throws it away ({@code JediEmulator.doProcessOsc}: <i>"Support for OSC 7 is
 *       pending"</i>) and that method is private, so the only way to see one is to scan ahead of the
 *       emulator.</li>
 *   <li><b>OSC 9;9</b> — {@code ESC ] 9 ; 9 ; "C:\path" ST}, the Windows Terminal convention, and the
 *       only route to a directory for a local {@code cmd.exe}.</li>
 * </ul>
 *
 * <p>OSC 0/1/2 window titles are deliberately <em>not</em> handled here: JediTerm already parses
 * those and hands them over through {@code TerminalApplicationTitleListener}, defragmented and with
 * every terminator form dealt with, so re-implementing them would only risk divergence.</p>
 *
 * <p>Not thread-safe; it is fed from a single terminal reader thread.</p>
 */
public final class OscCwdScanner {

    /** Where a decoded directory is delivered. */
    @FunctionalInterface
    public interface Sink {
        void report(CwdTracker.Source source, String path);
    }

    /**
     * Longest OSC body accumulated before the sequence is abandoned. Real ones are a path; an
     * unterminated or hostile sequence must not be allowed to grow a buffer without bound.
     */
    private static final int MAX_BODY = 2048;

    private enum State { IDLE, ESC_SEEN, COLLECT, ESC_IN_OSC, DISCARD, DISCARD_ESC }

    private final Sink sink;
    private final StringBuilder body = new StringBuilder();
    private State state = State.IDLE;

    public OscCwdScanner(Sink sink) {
        this.sink = sink;
    }

    /**
     * Consumes one character of terminal output. In the common case (state IDLE, an ordinary
     * character) this is a single comparison, which is what makes it affordable to run over every
     * character the emulator pulls one at a time.
     */
    public void feed(char c) {
        switch (state) {
            case IDLE -> {
                if (c == 0x1B) {
                    state = State.ESC_SEEN;
                } else if (c == 0x9D) {          // C1 OSC
                    begin();
                }
            }
            case ESC_SEEN -> {
                if (c == ']') {
                    begin();
                } else if (c != 0x1B) {
                    state = State.IDLE;
                }
            }
            case COLLECT -> {
                if (c == 0x07 || c == 0x9C) {    // BEL, or C1 ST
                    finish();
                } else if (c == 0x1B) {
                    state = State.ESC_IN_OSC;
                } else if (c < 0x20) {
                    // CAN/SUB and friends abort a sequence; so does anything else non-printable.
                    state = State.IDLE;
                } else {
                    body.append(c);
                    if (body.length() > MAX_BODY) {
                        state = State.DISCARD;
                    }
                }
            }
            case ESC_IN_OSC -> {
                if (c == '\\') {                 // ESC \ — the two-character ST
                    finish();
                } else {
                    // The sequence was abandoned, but the ESC that ended it may well be starting
                    // the next one. Re-dispatch this character as if the ESC had just arrived,
                    // otherwise back-to-back sequences lose the second introducer.
                    restartAfterEscape(c);
                }
            }
            // An over-long body is dropped, but the terminator still has to be swallowed so the
            // scanner resynchronises instead of treating the payload's tail as fresh output.
            case DISCARD -> {
                if (c == 0x07 || c == 0x9C) {
                    reset();
                } else if (c == 0x1B) {
                    state = State.DISCARD_ESC;
                }
            }
            case DISCARD_ESC -> {
                if (c == '\\') {
                    reset();
                } else {
                    restartAfterEscape(c);
                }
            }
        }
    }

    /**
     * Abandons the sequence in flight and re-offers {@code c} to the rules that apply just after an
     * ESC. Recurses exactly one level: {@code ESC_SEEN} never calls back into here.
     */
    private void restartAfterEscape(char c) {
        reset();
        state = State.ESC_SEEN;
        feed(c);
    }

    private void begin() {
        body.setLength(0);
        state = State.COLLECT;
    }

    private void reset() {
        body.setLength(0);
        state = State.IDLE;
    }

    private void finish() {
        String payload = body.toString();
        reset();
        int semi = payload.indexOf(';');
        if (semi < 0) {
            return;
        }
        String ps = payload.substring(0, semi);
        String value = payload.substring(semi + 1);
        if ("7".equals(ps)) {
            emit(CwdTracker.Source.OSC7, fileUriToPath(value));
        } else if ("9".equals(ps) && value.startsWith("9;")) {
            emit(CwdTracker.Source.OSC99, unquote(value.substring(2)));
        }
        // Everything else — 0/1/2 titles, 8 hyperlinks, 4 palette changes — is not ours.
    }

    private void emit(CwdTracker.Source source, String path) {
        if (path != null && !path.isBlank()) {
            sink.report(source, path);
        }
    }

    /**
     * {@code file://host/path} → {@code /path}. The authority is skipped without being validated:
     * shells fill it with whatever {@code $HOSTNAME} happens to be, and it plays no part in a label.
     * Both {@code file:///p} and {@code file://host/p} are accepted, and a Windows
     * {@code file:///C:/Users/x} loses the slash that precedes its drive letter.
     */
    private static String fileUriToPath(String value) {
        if (!value.startsWith("file://")) {
            return null;
        }
        String rest = value.substring("file://".length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String decoded = percentDecode(rest.substring(slash));
        if (decoded == null) {
            return null;
        }
        if (decoded.length() >= 3 && decoded.charAt(0) == '/'
                && Character.isLetter(decoded.charAt(1)) && decoded.charAt(2) == ':') {
            decoded = decoded.substring(1);
        }
        return decoded;
    }

    /**
     * Percent-decodes to bytes and then interprets them as UTF-8, which is the only correct order:
     * a non-ASCII directory arrives as one {@code %XX} per byte, not per character.
     *
     * @return the decoded string, or null if any escape is malformed (the whole sequence is then
     *         dropped rather than guessed at)
     */
    private static String percentDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    return null;
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return null;
                }
                out.write((hi << 4) | lo);
                i += 2;
            } else if (c < 0x80) {
                out.write(c);
            } else {
                // Already-decoded non-ASCII: not strictly legal in a URI, but shells emit it.
                byte[] utf8 = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(utf8, 0, utf8.length);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Strips one surrounding pair of double quotes, which OSC 9;9 emitters usually include. */
    private static String unquote(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
