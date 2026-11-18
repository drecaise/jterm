package com.katmoda.jterm.terminal.rdp.smartcard;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the server may see and do with the local smart cards (design S1, S3, S5). Pure: no PC/SC,
 * no UI, so every rule is unit-tested.
 */
public final class ScardPolicy {

    private ScardPolicy() {
    }

    // ---- readers (S1) ----

    /** pcsc-lite appends " <bus index> <slot>" ("… 00 00"); the same reader replugged may change it. */
    private static final Pattern PCSCLITE_INDEX = Pattern.compile(" [0-9A-Fa-f]{2} [0-9A-Fa-f]{2}$");

    /** A reader's name without pcsc-lite's index suffix, for comparing with the user's picks. */
    public static String baseName(String reader) {
        return PCSCLITE_INDEX.matcher(reader.strip()).replaceFirst("");
    }

    public static boolean picked(String reader, List<String> picks) {
        String base = baseName(reader);
        for (String p : picks) {
            if (!p.isBlank() && baseName(p).equals(base)) {
                return true;
            }
        }
        return false;
    }

    /** The present readers the server may see, in the system's order. */
    public static List<String> visible(List<String> present, List<String> picks) {
        List<String> out = new ArrayList<>();
        for (String r : present) {
            if (picked(r, picks) && !r.equals(Scard.PNP_NOTIFICATION)) {
                out.add(r);
            }
        }
        return out;
    }

    // ---- share modes and dispositions (S3, D8) ----

    /** EXCLUSIVE becomes SHARED (a local program keeps the card); DIRECT is refused (-1). */
    public static int shareMode(int requested) {
        return switch (requested) {
            case Scard.SHARE_EXCLUSIVE, Scard.SHARE_SHARED -> Scard.SHARE_SHARED;
            default -> -1;
        };
    }

    /** No ejecting a local card; anything unknown resets (which also drops the verified PIN). */
    public static int disposition(int requested) {
        return switch (requested) {
            case Scard.LEAVE_CARD, Scard.RESET_CARD, Scard.UNPOWER_CARD -> requested;
            default -> Scard.RESET_CARD;
        };
    }

    /** No protocol named means "either" (as FreeRDP and Windows treat it). */
    public static int protocols(int requested) {
        return requested == Scard.PROTOCOL_UNDEFINED ? Scard.PROTOCOL_T0 | Scard.PROTOCOL_T1 : requested;
    }

    // ---- control codes (S3) ----

    /** CM_IOCTL_GET_FEATURE_REQUEST, Windows' code (SCARD_CTL_CODE(3400)). */
    public static final int GET_FEATURE_REQUEST = 0x00310000 | (3400 << 2);

    /** PC/SC part 10 features a server may use: PIN entry on the reader's own keypad and its properties. */
    static final Set<Integer> PIN_PAD_FEATURES = Set.of(
            0x06, // FEATURE_VERIFY_PIN_DIRECT
            0x07, // FEATURE_MODIFY_PIN_DIRECT
            0x0A, // FEATURE_IFD_PIN_PROPERTIES
            0x12  // FEATURE_GET_TLV_PROPERTIES
    );

    /**
     * Keeps only the PIN-pad features of a feature request's answer (TLVs: tag, 4, control code),
     * so the server never learns a code it may not use; malformed TLVs end the list.
     */
    public static byte[] filterFeatures(byte[] tlvs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i + 6 <= tlvs.length && (tlvs[i + 1] & 0xFF) == 4; i += 6) {
            if (PIN_PAD_FEATURES.contains(tlvs[i] & 0xFF)) {
                out.write(tlvs, i, 6);
            }
        }
        return out.toByteArray();
    }

    /** The control codes in a (filtered) feature answer. */
    public static Set<Integer> featureCodes(byte[] tlvs) {
        Set<Integer> codes = new HashSet<>();
        for (int i = 0; i + 6 <= tlvs.length && (tlvs[i + 1] & 0xFF) == 4; i += 6) {
            codes.add((tlvs[i + 2] & 0xFF) << 24 | (tlvs[i + 3] & 0xFF) << 16 | (tlvs[i + 4] & 0xFF) << 8
                    | (tlvs[i + 5] & 0xFF));
        }
        return codes;
    }

    // ---- attributes ----

    public static final int ATTR_VENDOR_NAME = 0x00010100;
    public static final int ATTR_VENDOR_IFD_TYPE = 0x00010101;
    public static final int ATTR_VENDOR_IFD_VERSION = 0x00010102;
    public static final int ATTR_VENDOR_IFD_SERIAL_NO = 0x00010103;
    public static final int ATTR_CHANNEL_ID = 0x00020110;
    public static final int ATTR_PROTOCOL_TYPES = 0x00030120;
    public static final int ATTR_CURRENT_PROTOCOL_TYPE = 0x00080201;
    public static final int ATTR_ICC_PRESENCE = 0x00090300;
    public static final int ATTR_ICC_INTERFACE_STATUS = 0x00090301;
    public static final int ATTR_ATR_STRING = 0x00090303;
    public static final int ATTR_DEVICE_FRIENDLY_NAME_A = 0x7FFF0003;
    public static final int ATTR_DEVICE_SYSTEM_NAME_A = 0x7FFF0004;
    public static final int ATTR_DEVICE_FRIENDLY_NAME_W = 0x7FFF0005;
    public static final int ATTR_DEVICE_SYSTEM_NAME_W = 0x7FFF0006;

    /** Asked of the reader. The serial number is not: it identifies the user's hardware across servers. */
    static final Set<Integer> READER_ATTRIBUTES = Set.of(ATTR_VENDOR_NAME, ATTR_VENDOR_IFD_TYPE,
            ATTR_VENDOR_IFD_VERSION, ATTR_CHANNEL_ID, ATTR_PROTOCOL_TYPES, ATTR_CURRENT_PROTOCOL_TYPE,
            ATTR_ICC_PRESENCE, ATTR_ICC_INTERFACE_STATUS, ATTR_ATR_STRING);

    /** Answered by jterm from the reader name the server already knows. */
    static final Set<Integer> NAME_ATTRIBUTES = Set.of(ATTR_DEVICE_FRIENDLY_NAME_A, ATTR_DEVICE_SYSTEM_NAME_A,
            ATTR_DEVICE_FRIENDLY_NAME_W, ATTR_DEVICE_SYSTEM_NAME_W);

    public static boolean attributeAllowed(int attr) {
        return READER_ATTRIBUTES.contains(attr) || NAME_ATTRIBUTES.contains(attr);
    }

    // ---- PIN guard (S5) ----

    /** VERIFY, CHANGE REFERENCE DATA, RESET RETRY COUNTER (ISO 7816-4), not reader pseudo-APDUs (CLA FF). */
    public static boolean isPinCommand(byte[] apdu) {
        if (apdu.length < 4 || (apdu[0] & 0xFF) == 0xFF) {
            return false;
        }
        int ins = apdu[1] & 0xFF;
        return ins == 0x20 || ins == 0x24 || ins == 0x2C;
    }

    /** What a PIN command's status word says about the retry counter. */
    public enum PinOutcome { OK, ONE_LEFT, WRONG, BLOCKED, OTHER }

    public static PinOutcome pinOutcome(byte[] response) {
        if (response.length < 2) {
            return PinOutcome.OTHER;
        }
        int sw1 = response[response.length - 2] & 0xFF;
        int sw2 = response[response.length - 1] & 0xFF;
        if (sw1 == 0x90 && sw2 == 0x00) {
            return PinOutcome.OK;
        }
        if (sw1 == 0x63 && (sw2 & 0xF0) == 0xC0) {
            int left = sw2 & 0x0F;
            return left == 0 ? PinOutcome.BLOCKED : left == 1 ? PinOutcome.ONE_LEFT : PinOutcome.WRONG;
        }
        if (sw1 == 0x69 && sw2 == 0x83) {
            return PinOutcome.BLOCKED;
        }
        return PinOutcome.OTHER;
    }

    /**
     * Per card: after a PIN command answered "one try left", the next PIN command needs the user
     * ({@link #mustAsk}). A correct PIN, or a reset of the card, clears it. A VERIFY with no data is
     * a query for the counter and can't use up a try, so it goes through without asking. Kept per
     * reader, not per handle: the counter belongs to the card.
     */
    public static final class PinGuard {
        private boolean oneLeft;
        private boolean blocked;

        public synchronized boolean mustAsk(byte[] apdu) {
            return oneLeft && isPinCommand(apdu) && apdu.length > 5;
        }

        /** Returns the outcome when {@code apdu} was a PIN command, else null. */
        public synchronized PinOutcome observe(byte[] apdu, byte[] response) {
            if (!isPinCommand(apdu)) {
                return null;
            }
            PinOutcome o = pinOutcome(response);
            switch (o) {
                case OK -> {
                    oneLeft = false;
                    blocked = false;
                }
                case ONE_LEFT -> oneLeft = true;
                case BLOCKED -> {
                    blocked = true;
                    oneLeft = false;
                }
                default -> {
                }
            }
            return o;
        }

        public synchronized void cardReset() {
            oneLeft = false;
        }

        public synchronized boolean blocked() {
            return blocked;
        }
    }
}
