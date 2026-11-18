package com.katmoda.jterm.terminal.rdp.smartcard;

import java.util.List;

/**
 * The PC/SC functions jterm uses, in Windows' terms: return codes, card states, protocols and
 * control codes are Windows' values whatever the OS ({@link JnaPcsc} translates). Contexts and card
 * handles are the system's own and never leave jterm ({@link ScardService} maps them).
 *
 * <p>Calls on one context are made from one thread at a time; {@link #cancel} is the exception and
 * may come from any thread while a status change waits.
 */
public interface Pcsc {

    record Context(int rc, long handle) {
    }

    record Readers(int rc, List<String> names) {
    }

    record Connected(int rc, long card, int protocol) {
    }

    record IntResult(int rc, int value) {
    }

    /** {@code state} is Windows' card-state enumeration ({@link Scard#CARD_ABSENT} …). */
    record CardStatus(int rc, String reader, int state, int protocol, byte[] atr) {
    }

    record Bytes(int rc, byte[] data) {
    }

    record Transmitted(int rc, int recvProtocol, byte[] response) {
    }

    /** One reader of a status-change call: {@code eventState} and {@code atr} are filled in. */
    final class StatusSlot {
        public final String reader;
        public final int currentState;
        public int eventState;
        public byte[] atr = new byte[0];

        public StatusSlot(String reader, int currentState) {
            this.reader = reader;
            this.currentState = currentState;
        }
    }

    Context establish(int scope);

    int release(long context);

    int isValid(long context);

    Readers listReaders(long context);

    /** Waits up to {@code timeoutMs} ({@link Scard#INFINITE}: for ever) for a change in any slot. */
    int getStatusChange(long context, int timeoutMs, StatusSlot[] slots);

    int cancel(long context);

    Connected connect(long context, String reader, int shareMode, int protocols);

    IntResult reconnect(long card, int shareMode, int protocols, int initialization);

    int disconnect(long card, int disposition);

    int beginTransaction(long card);

    int endTransaction(long card, int disposition);

    CardStatus status(long card);

    Transmitted transmit(long card, int protocol, byte[] send, int recvMax);

    /** {@code code} is a Windows control code; so are the codes in a feature request's answer. */
    Bytes control(long card, int code, byte[] in, int outMax);

    Bytes getAttrib(long card, int attrId);

    /** Whether {@link #getStatusChange} may be given {@link Scard#PNP_NOTIFICATION}. */
    default boolean supportsPnpNotification() {
        return true;
    }
}
