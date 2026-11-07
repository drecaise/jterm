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
package com.katmoda.jterm.terminal.rdp;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Keyboard policy for an RDP pane. Pure functions, unit-tested.
 *
 * <p>RDP wants hardware scancodes; Java gives virtual keys. Non-printing keys (modifiers, arrows,
 * function keys, Ctrl/Alt chords) go as a <b>Windows virtual key</b>, which the helper turns into a
 * scancode with WinPR's table. Printable characters typed without Ctrl/Alt/Meta go as
 * <b>Unicode</b> when the server supports it, so the result doesn't depend on the remote keyboard
 * layout matching the local one.</p>
 */
public final class RdpKeyMap {

    private RdpKeyMap() {
    }

    /** A Windows virtual key plus {@code RdpProtocol.KEY_*} extended-key flags. */
    public record WinKey(int vk, int flags) {
        public static final WinKey NONE = new WinKey(0, 0);

        public boolean mapped() {
            return vk != 0;
        }
    }

    /**
     * Maps a Java key code and key location to a Windows virtual key. Returns {@link WinKey#NONE}
     * for keys with no sensible mapping (those are dropped, or sent as Unicode by the caller).
     */
    public static WinKey toWindows(int javaKeyCode, int keyLocation) {
        boolean numpad = keyLocation == KeyEvent.KEY_LOCATION_NUMPAD;
        boolean right = keyLocation == KeyEvent.KEY_LOCATION_RIGHT;
        // Numpad navigation keys (NumLock off) share VKs with the dedicated cluster but are the
        // non-extended scancodes; the dedicated cluster is extended.
        int notExtIfPad = numpad ? RdpProtocol.KEY_FORCE_NOT_EXTENDED : 0;
        return switch (javaKeyCode) {
            case KeyEvent.VK_ENTER -> new WinKey(0x0D, numpad ? RdpProtocol.KEY_FORCE_EXTENDED : 0);
            case KeyEvent.VK_BACK_SPACE, KeyEvent.VK_TAB, KeyEvent.VK_PAUSE, KeyEvent.VK_CAPS_LOCK,
                 KeyEvent.VK_ESCAPE, KeyEvent.VK_SPACE, KeyEvent.VK_NUM_LOCK, KeyEvent.VK_SCROLL_LOCK ->
                    new WinKey(javaKeyCode, 0); // identical codes
            case KeyEvent.VK_CLEAR -> new WinKey(0x0C, 0);
            case KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_END, KeyEvent.VK_HOME,
                 KeyEvent.VK_LEFT, KeyEvent.VK_UP, KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN ->
                    new WinKey(javaKeyCode, notExtIfPad);
            case KeyEvent.VK_KP_LEFT -> new WinKey(0x25, RdpProtocol.KEY_FORCE_NOT_EXTENDED);
            case KeyEvent.VK_KP_UP -> new WinKey(0x26, RdpProtocol.KEY_FORCE_NOT_EXTENDED);
            case KeyEvent.VK_KP_RIGHT -> new WinKey(0x27, RdpProtocol.KEY_FORCE_NOT_EXTENDED);
            case KeyEvent.VK_KP_DOWN -> new WinKey(0x28, RdpProtocol.KEY_FORCE_NOT_EXTENDED);
            case KeyEvent.VK_INSERT -> new WinKey(0x2D, notExtIfPad);
            case KeyEvent.VK_DELETE -> new WinKey(0x2E, notExtIfPad);
            case KeyEvent.VK_PRINTSCREEN -> new WinKey(0x2C, 0);
            case KeyEvent.VK_SHIFT -> new WinKey(right ? 0xA1 : 0xA0, 0);
            case KeyEvent.VK_CONTROL -> new WinKey(right ? 0xA3 : 0xA2, 0);
            case KeyEvent.VK_ALT -> new WinKey(right ? 0xA5 : 0xA4, 0);
            case KeyEvent.VK_ALT_GRAPH -> new WinKey(0xA5, 0);
            case KeyEvent.VK_WINDOWS -> new WinKey(right ? 0x5C : 0x5B, 0);
            case KeyEvent.VK_CONTEXT_MENU -> new WinKey(0x5D, 0);
            case KeyEvent.VK_SEMICOLON -> new WinKey(0xBA, 0);
            case KeyEvent.VK_EQUALS -> new WinKey(0xBB, 0);
            case KeyEvent.VK_COMMA -> new WinKey(0xBC, 0);
            case KeyEvent.VK_MINUS -> new WinKey(0xBD, 0);
            case KeyEvent.VK_PERIOD -> new WinKey(0xBE, 0);
            case KeyEvent.VK_SLASH -> new WinKey(0xBF, 0);
            case KeyEvent.VK_BACK_QUOTE -> new WinKey(0xC0, 0);
            case KeyEvent.VK_OPEN_BRACKET -> new WinKey(0xDB, 0);
            case KeyEvent.VK_BACK_SLASH -> new WinKey(0xDC, 0);
            case KeyEvent.VK_CLOSE_BRACKET -> new WinKey(0xDD, 0);
            case KeyEvent.VK_QUOTE -> new WinKey(0xDE, 0);
            case KeyEvent.VK_LESS -> new WinKey(0xE2, 0); // the ISO 102nd key
            default -> {
                if ((javaKeyCode >= '0' && javaKeyCode <= '9') || (javaKeyCode >= 'A' && javaKeyCode <= 'Z')
                        || (javaKeyCode >= KeyEvent.VK_NUMPAD0 && javaKeyCode <= KeyEvent.VK_DIVIDE)
                        || (javaKeyCode >= KeyEvent.VK_F1 && javaKeyCode <= KeyEvent.VK_F12)) {
                    yield new WinKey(javaKeyCode, 0); // identical codes
                }
                if (javaKeyCode >= KeyEvent.VK_F13 && javaKeyCode <= KeyEvent.VK_F24) {
                    yield new WinKey(0x7C + (javaKeyCode - KeyEvent.VK_F13), 0);
                }
                yield WinKey.NONE;
            }
        };
    }

    /** Whether {@code c} is a character to send as Unicode (defined and not a control character). */
    public static boolean printable(char c) {
        return c != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(c);
    }

    /** Whether Ctrl, Alt or Meta is held — then a key is a chord and goes as a scancode. */
    public static boolean chord(int modifiersEx) {
        return (modifiersEx & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK
                | InputEvent.META_DOWN_MASK)) != 0;
    }

    /**
     * The RDP-pane shortcut policy (design decision 2026-11-07): while an RDP pane has focus, jterm
     * only claims strokes holding <b>both Ctrl and Shift</b>; everything else belongs to the remote
     * desktop, so Ctrl+T, Ctrl+W, Ctrl+C and the F-keys reach remote programs.
     */
    public static boolean jtermMayClaim(int modifiersEx) {
        return (modifiersEx & InputEvent.CTRL_DOWN_MASK) != 0
                && (modifiersEx & InputEvent.SHIFT_DOWN_MASK) != 0;
    }
}
