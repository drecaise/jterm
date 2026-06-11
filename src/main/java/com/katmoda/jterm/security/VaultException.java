package com.katmoda.jterm.security;

/** Raised for vault initialization / unlock / crypto failures. */
public final class VaultException extends Exception {

    public VaultException(String message) {
        super(message);
    }

    public VaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
