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
package com.katmoda.jterm.macro;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.IOException;

/**
 * One line of a {@link Macro}: literal text, a key press, or a pause. Persisted
 * polymorphically via a {@code "type"} discriminator (the same pattern as
 * {@code session.SessionNode}). Record components are annotated with {@link JsonProperty}
 * because the build does not compile with {@code -parameters}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = MacroStep.TextStep.class, name = "text"),
        @JsonSubTypes.Type(value = MacroStep.KeyStep.class, name = "key"),
        @JsonSubTypes.Type(value = MacroStep.SleepStep.class, name = "sleep")
})
public sealed interface MacroStep permits MacroStep.TextStep, MacroStep.KeyStep, MacroStep.SleepStep {

    /** Perform this step against the terminal. */
    void execute(MacroSink sink) throws IOException, InterruptedException;

    /** The single-line rendering shown in the macro editor (MobaXterm-style). */
    String displayLine();

    /** Literal text; {@code keystrokeDelayMs > 0} types character-by-character with that pause. */
    record TextStep(@JsonProperty("text") String text,
                    @JsonProperty("keystrokeDelayMs") int keystrokeDelayMs) implements MacroStep {

        @Override
        public void execute(MacroSink sink) throws IOException, InterruptedException {
            if (keystrokeDelayMs > 0) {
                for (int i = 0; i < text.length(); i++) {
                    sink.type(String.valueOf(text.charAt(i)));
                    Thread.sleep(keystrokeDelayMs);
                }
            } else {
                sink.type(text);
            }
        }

        @Override
        public String displayLine() {
            return text;
        }
    }

    /** A single named key press (RETURN, TAB, arrows, …). */
    record KeyStep(@JsonProperty("key") MacroKey key) implements MacroStep {

        @Override
        public void execute(MacroSink sink) throws IOException {
            sink.type(key.sequence());
        }

        @Override
        public String displayLine() {
            return key.name();
        }
    }

    /** Pause before the next step. */
    record SleepStep(@JsonProperty("ms") int ms) implements MacroStep {

        @Override
        public void execute(MacroSink sink) throws InterruptedException {
            Thread.sleep(ms);
        }

        @Override
        public String displayLine() {
            return "SLEEP=" + ms;
        }
    }
}
