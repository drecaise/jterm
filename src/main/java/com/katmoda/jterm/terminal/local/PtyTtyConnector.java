package com.katmoda.jterm.terminal.local;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.ProcessTtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.WinSize;

/**
 * Bridges a pty4j {@link PtyProcess} to JediTerm. {@link ProcessTtyConnector} already
 * implements stream reading/writing against {@link Process}; we only add a name and
 * forward resize events to the pty.
 */
final class PtyTtyConnector extends ProcessTtyConnector {

    private final PtyProcess process;
    private final String name;

    PtyTtyConnector(PtyProcess process, String name) {
        super(process, LocalSession.charset());
        this.process = process;
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void resize(TermSize size) {
        if (process.isAlive()) {
            process.setWinSize(new WinSize(size.getColumns(), size.getRows()));
        }
    }
}
