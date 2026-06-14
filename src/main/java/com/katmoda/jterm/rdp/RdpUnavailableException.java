package com.katmoda.jterm.rdp;

/**
 * Thrown when no FreeRDP binary ({@code xfreerdp}/{@code wfreerdp}) can be found on the system.
 * Carries a human-readable install hint for the current OS so the UI can show a friendly message
 * rather than a stack trace.
 */
public final class RdpUnavailableException extends Exception {

    public RdpUnavailableException(String message) {
        super(message);
    }
}
