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
package com.katmoda.jterm.terminal.rdp.audio;

/**
 * Who may hear this computer's microphone, for one connect attempt (a reconnect starts a new gate).
 * Pure state; not thread-safe, {@link RdpMicrophone} guards it.
 *
 * <p>The rule it exists for (design property A1): the capture line is open only while a microphone
 * stream is open, the user allowed it for this connection, and it is not muted. In every other state
 * the line is closed, so the operating system's "microphone in use" indicator tells the truth, and
 * the server gets silence while a stream is open.</p>
 *
 * <p>The server can cause one question per connection and no more (A2): only the first stream
 * opened while nobody has answered asks. A later stream, or one opened while the question is
 * still up, does not ask again; only the user can change the answer afterwards.</p>
 */
public final class MicGate {

    /** The user's answer for this connection. */
    public enum Consent {
        /** Not asked yet: the server has not opened a stream, and the user has not decided. */
        UNASKED,
        /** The question is up. */
        ASKING,
        ALLOWED,
        DENIED
    }

    private Consent consent = Consent.UNASKED;
    private boolean streamOpen;
    private boolean muted;

    public MicGate(boolean muted) {
        this.muted = muted;
    }

    /**
     * The server opened a stream. Returns true exactly when the user must now be asked: the first
     * time in this connection, with no answer given beforehand.
     */
    public boolean streamOpened() {
        streamOpen = true;
        if (consent == Consent.UNASKED) {
            consent = Consent.ASKING;
            return true;
        }
        return false;
    }

    public void streamClosed() {
        streamOpen = false;
    }

    /** The answer to the question. Ignored unless the question is up (a stale dialog). */
    public void answer(boolean allow) {
        if (consent == Consent.ASKING) {
            consent = allow ? Consent.ALLOWED : Consent.DENIED;
        }
    }

    /**
     * The user allows or blocks from the microphone button. A user action, so it needs no question,
     * and it settles the question for this connection whether or not one was asked.
     */
    public void setAllowed(boolean allow) {
        consent = allow ? Consent.ALLOWED : Consent.DENIED;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public Consent consent() {
        return consent;
    }

    public boolean isStreamOpen() {
        return streamOpen;
    }

    public boolean isMuted() {
        return muted;
    }

    /** The capture line may be open, and what it hears goes to the server. */
    public boolean mayCapture() {
        return streamOpen && consent == Consent.ALLOWED && !muted;
    }

    /** A stream is open but the microphone may not be heard: the server gets silence. */
    public boolean sendsSilence() {
        return streamOpen && !mayCapture();
    }
}
