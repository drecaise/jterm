package com.katmoda.jterm.macro;

import java.io.IOException;

/**
 * The write target a {@link MacroStep} sends keystrokes to. Backed by a terminal connector
 * (see {@link MacroRunner}); kept as a tiny interface so steps don't depend on JediTerm.
 */
public interface MacroSink {

    /** Send literal text to the terminal, exactly as if typed. */
    void type(String text) throws IOException;
}
