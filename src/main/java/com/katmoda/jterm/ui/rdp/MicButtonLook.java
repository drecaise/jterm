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
package com.katmoda.jterm.ui.rdp;

import com.katmoda.jterm.terminal.rdp.audio.MicGate;
import com.katmoda.jterm.terminal.rdp.audio.RdpMicrophone;

/**
 * What the microphone button shows for a {@link RdpMicrophone.Status}: its text, its tooltip, and
 * whether it is drawn as recording (red, and the tab's mark). Swing-free, so the wording of every
 * state is tested.
 *
 * <p>{@code recording} is true only while the capture line is open, i.e. while what the
 * microphone hears actually goes to the server (design property A3).</p>
 */
record MicButtonLook(String text, String tip, boolean recording) {

    /** Put before the tab's title while recording, so it shows from another tab too. */
    static final String TAB_MARK = "● ";
    /** The button's text colour while recording. */
    static final int RECORDING_RGB = 0xE03030;

    static MicButtonLook of(RdpMicrophone.Status st) {
        if (st.capturing()) {
            return new MicButtonLook("● Mic on", "<html><b>The remote desktop hears this computer's microphone.</b>"
                    + "<br>Click to mute or block it.</html>", true);
        }
        String state;
        String text;
        switch (st.consent()) {
            case ASKING -> {
                text = "Mic?";
                state = "The remote desktop asked for the microphone; jterm is waiting for your answer.";
            }
            case DENIED -> {
                text = "Mic blocked";
                state = "Microphone blocked for this connection: the remote desktop hears silence.";
            }
            case ALLOWED -> {
                if (st.muted()) {
                    text = "Mic muted";
                    state = "Microphone muted: the remote desktop hears silence.";
                } else if (st.streamOpen() && st.deviceFailed()) {
                    text = "Mic";
                    state = "The microphone could not be opened (no microphone, or the system refused "
                            + "access); the remote desktop hears silence.";
                } else {
                    text = "Mic";
                    state = "Microphone allowed for this connection; the remote desktop is not using it now.";
                }
            }
            default -> {
                text = st.muted() ? "Mic muted" : "Mic";
                state = "The remote desktop is not using the microphone. jterm asks before it can listen.";
            }
        }
        return new MicButtonLook(text, "<html>" + state + "<br>Click to change.</html>", false);
    }

    /** The menu's allow/block entry for this state. */
    static String allowItem(MicGate.Consent consent) {
        return consent == MicGate.Consent.ALLOWED ? "Block the microphone for this connection"
                : "Allow the microphone for this connection";
    }
}
