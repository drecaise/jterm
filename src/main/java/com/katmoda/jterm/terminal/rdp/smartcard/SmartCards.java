package com.katmoda.jterm.terminal.rdp.smartcard;

import java.util.List;
import java.util.Optional;

/** The system's PC/SC, loaded once per process, and the reader list the session dialog offers. */
public final class SmartCards {

    private SmartCards() {
    }

    private static volatile Optional<Pcsc> system;

    /** The system's PC/SC, or empty when there is none jterm trusts (logged once). */
    public static Optional<Pcsc> system() {
        Optional<Pcsc> s = system;
        if (s == null) {
            synchronized (SmartCards.class) {
                s = system;
                if (s == null) {
                    s = JnaPcsc.load();
                    system = s;
                }
            }
        }
        return s;
    }

    /**
     * The readers present now, without pcsc-lite's index suffix (what a session saves), or empty
     * when there is no PC/SC or no reader. Blocking but quick: a round trip to the PC/SC service.
     */
    public static List<String> presentReaders() {
        Optional<Pcsc> p = system();
        if (p.isEmpty()) {
            return List.of();
        }
        Pcsc.Context c = p.get().establish(2 /* SCARD_SCOPE_SYSTEM */);
        if (c.rc() != Scard.S_SUCCESS) {
            return List.of();
        }
        try {
            Pcsc.Readers r = p.get().listReaders(c.handle());
            return r.rc() != Scard.S_SUCCESS ? List.of()
                    : r.names().stream().map(ScardPolicy::baseName).distinct().toList();
        } finally {
            p.get().release(c.handle());
        }
    }
}
