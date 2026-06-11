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
