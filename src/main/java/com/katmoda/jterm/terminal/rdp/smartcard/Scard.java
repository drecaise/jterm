package com.katmoda.jterm.terminal.rdp.smartcard;

/**
 * Windows smart-card constants as MS-RDPESC carries them: IOCTL codes, return codes, states and
 * protocols. These are Windows' values; {@link JnaPcsc} translates where pcsc-lite differs.
 */
public final class Scard {

    private Scard() {
    }

    // IOCTLs (MS-RDPESC 3.1.4): 0x00090000 | n << 2, n from 5 to 66
    public static final int IOCTL_ESTABLISHCONTEXT = 0x00090014;
    public static final int IOCTL_RELEASECONTEXT = 0x00090018;
    public static final int IOCTL_ISVALIDCONTEXT = 0x0009001C;
    public static final int IOCTL_LISTREADERGROUPSA = 0x00090020;
    public static final int IOCTL_LISTREADERGROUPSW = 0x00090024;
    public static final int IOCTL_LISTREADERSA = 0x00090028;
    public static final int IOCTL_LISTREADERSW = 0x0009002C;
    public static final int IOCTL_INTRODUCEREADERGROUPA = 0x00090050;
    public static final int IOCTL_INTRODUCEREADERGROUPW = 0x00090054;
    public static final int IOCTL_FORGETREADERGROUPA = 0x00090058;
    public static final int IOCTL_FORGETREADERGROUPW = 0x0009005C;
    public static final int IOCTL_INTRODUCEREADERA = 0x00090060;
    public static final int IOCTL_INTRODUCEREADERW = 0x00090064;
    public static final int IOCTL_FORGETREADERA = 0x00090068;
    public static final int IOCTL_FORGETREADERW = 0x0009006C;
    public static final int IOCTL_ADDREADERTOGROUPA = 0x00090070;
    public static final int IOCTL_ADDREADERTOGROUPW = 0x00090074;
    public static final int IOCTL_REMOVEREADERFROMGROUPA = 0x00090078;
    public static final int IOCTL_REMOVEREADERFROMGROUPW = 0x0009007C;
    public static final int IOCTL_LOCATECARDSA = 0x00090098;
    public static final int IOCTL_LOCATECARDSW = 0x0009009C;
    public static final int IOCTL_GETSTATUSCHANGEA = 0x000900A0;
    public static final int IOCTL_GETSTATUSCHANGEW = 0x000900A4;
    public static final int IOCTL_CANCEL = 0x000900A8;
    public static final int IOCTL_CONNECTA = 0x000900AC;
    public static final int IOCTL_CONNECTW = 0x000900B0;
    public static final int IOCTL_RECONNECT = 0x000900B4;
    public static final int IOCTL_DISCONNECT = 0x000900B8;
    public static final int IOCTL_BEGINTRANSACTION = 0x000900BC;
    public static final int IOCTL_ENDTRANSACTION = 0x000900C0;
    public static final int IOCTL_STATE = 0x000900C4;
    public static final int IOCTL_STATUSA = 0x000900C8;
    public static final int IOCTL_STATUSW = 0x000900CC;
    public static final int IOCTL_TRANSMIT = 0x000900D0;
    public static final int IOCTL_CONTROL = 0x000900D4;
    public static final int IOCTL_GETATTRIB = 0x000900D8;
    public static final int IOCTL_SETATTRIB = 0x000900DC;
    public static final int IOCTL_ACCESSSTARTEDEVENT = 0x000900E0;
    public static final int IOCTL_RELEASESTARTEDEVENT = 0x000900E4;
    public static final int IOCTL_LOCATECARDSBYATRA = 0x000900E8;
    public static final int IOCTL_LOCATECARDSBYATRW = 0x000900EC;
    public static final int IOCTL_READCACHEA = 0x000900F0;
    public static final int IOCTL_READCACHEW = 0x000900F4;
    public static final int IOCTL_WRITECACHEA = 0x000900F8;
    public static final int IOCTL_WRITECACHEW = 0x000900FC;
    public static final int IOCTL_GETTRANSMITCOUNT = 0x00090100;
    public static final int IOCTL_GETREADERICON = 0x00090104;
    public static final int IOCTL_GETDEVICETYPEID = 0x00090108;

    // Return codes (Windows values)
    public static final int S_SUCCESS = 0;
    public static final int F_INTERNAL_ERROR = 0x80100001;
    public static final int E_CANCELLED = 0x80100002;
    public static final int E_INVALID_HANDLE = 0x80100003;
    public static final int E_INVALID_PARAMETER = 0x80100004;
    public static final int E_NO_MEMORY = 0x80100006;
    public static final int E_INSUFFICIENT_BUFFER = 0x80100008;
    public static final int E_UNKNOWN_READER = 0x80100009;
    public static final int E_TIMEOUT = 0x8010000A;
    public static final int E_SHARING_VIOLATION = 0x8010000B;
    public static final int E_NO_SMARTCARD = 0x8010000C;
    public static final int E_PROTO_MISMATCH = 0x8010000F;
    public static final int E_NOT_READY = 0x80100010;
    public static final int E_INVALID_VALUE = 0x80100011;
    public static final int E_READER_UNAVAILABLE = 0x80100017;
    public static final int E_NO_SERVICE = 0x8010001D;
    public static final int E_SERVICE_STOPPED = 0x8010001E;
    public static final int E_UNSUPPORTED_FEATURE = 0x80100022;
    public static final int E_NO_READERS_AVAILABLE = 0x8010002E;
    public static final int W_UNRESPONSIVE_CARD = 0x80100066;
    public static final int W_UNPOWERED_CARD = 0x80100067;
    public static final int W_RESET_CARD = 0x80100068;
    public static final int W_REMOVED_CARD = 0x80100069;
    public static final int W_CACHE_ITEM_NOT_FOUND = 0x80100070;
    public static final int W_CACHE_ITEM_STALE = 0x80100071;

    // NTSTATUS for the IRP
    public static final int STATUS_SUCCESS = 0;
    public static final int STATUS_UNSUCCESSFUL = 0xC0000001;
    public static final int STATUS_BUFFER_TOO_SMALL = 0xC0000023;

    // Share modes, dispositions, protocols (Windows)
    public static final int SHARE_EXCLUSIVE = 1;
    public static final int SHARE_SHARED = 2;
    public static final int SHARE_DIRECT = 3;
    public static final int LEAVE_CARD = 0;
    public static final int RESET_CARD = 1;
    public static final int UNPOWER_CARD = 2;
    public static final int EJECT_CARD = 3;
    public static final int PROTOCOL_UNDEFINED = 0;
    public static final int PROTOCOL_T0 = 1;
    public static final int PROTOCOL_T1 = 2;
    public static final int PROTOCOL_RAW = 0x10000;

    // Reader states (identical in Windows and pcsc-lite)
    public static final int STATE_UNAWARE = 0;
    public static final int STATE_IGNORE = 0x1;
    public static final int STATE_CHANGED = 0x2;
    public static final int STATE_UNKNOWN = 0x4;
    public static final int STATE_UNAVAILABLE = 0x8;
    public static final int STATE_EMPTY = 0x10;
    public static final int STATE_PRESENT = 0x20;
    public static final int STATE_ATRMATCH = 0x40;
    public static final int STATE_EXCLUSIVE = 0x80;
    public static final int STATE_INUSE = 0x100;
    public static final int STATE_MUTE = 0x200;

    // Card states from Status/State (Windows: an enumeration, not flags)
    public static final int CARD_UNKNOWN = 0;
    public static final int CARD_ABSENT = 1;
    public static final int CARD_PRESENT = 2;
    public static final int CARD_SWALLOWED = 3;
    public static final int CARD_POWERED = 4;
    public static final int CARD_NEGOTIABLE = 5;
    public static final int CARD_SPECIFIC = 6;

    /** The pseudo-reader a status-change call watches to learn that readers came or went. */
    public static final String PNP_NOTIFICATION = "\\\\?PnP?\\Notification";

    /** SCARD_READER_TYPE_USB, what GetDeviceTypeId answers (as FreeRDP does on PC/SC). */
    public static final int READER_TYPE_USB = 0x20;

    /** dwTimeout meaning "wait for ever". */
    public static final int INFINITE = 0xFFFFFFFF;

    /** A name for logs: the IOCTL, never its contents. */
    public static String ioctlName(int ioctl) {
        return switch (ioctl) {
            case IOCTL_ESTABLISHCONTEXT -> "EstablishContext";
            case IOCTL_RELEASECONTEXT -> "ReleaseContext";
            case IOCTL_ISVALIDCONTEXT -> "IsValidContext";
            case IOCTL_LISTREADERGROUPSA, IOCTL_LISTREADERGROUPSW -> "ListReaderGroups";
            case IOCTL_LISTREADERSA, IOCTL_LISTREADERSW -> "ListReaders";
            case IOCTL_GETSTATUSCHANGEA, IOCTL_GETSTATUSCHANGEW -> "GetStatusChange";
            case IOCTL_CANCEL -> "Cancel";
            case IOCTL_CONNECTA, IOCTL_CONNECTW -> "Connect";
            case IOCTL_RECONNECT -> "Reconnect";
            case IOCTL_DISCONNECT -> "Disconnect";
            case IOCTL_BEGINTRANSACTION -> "BeginTransaction";
            case IOCTL_ENDTRANSACTION -> "EndTransaction";
            case IOCTL_STATE -> "State";
            case IOCTL_STATUSA, IOCTL_STATUSW -> "Status";
            case IOCTL_TRANSMIT -> "Transmit";
            case IOCTL_CONTROL -> "Control";
            case IOCTL_GETATTRIB -> "GetAttrib";
            case IOCTL_SETATTRIB -> "SetAttrib";
            case IOCTL_ACCESSSTARTEDEVENT -> "AccessStartedEvent";
            case IOCTL_RELEASESTARTEDEVENT -> "ReleaseStartedEvent";
            case IOCTL_LOCATECARDSBYATRA, IOCTL_LOCATECARDSBYATRW -> "LocateCardsByATR";
            case IOCTL_LOCATECARDSA, IOCTL_LOCATECARDSW -> "LocateCards";
            case IOCTL_READCACHEA, IOCTL_READCACHEW -> "ReadCache";
            case IOCTL_WRITECACHEA, IOCTL_WRITECACHEW -> "WriteCache";
            case IOCTL_GETTRANSMITCOUNT -> "GetTransmitCount";
            case IOCTL_GETREADERICON -> "GetReaderIcon";
            case IOCTL_GETDEVICETYPEID -> "GetDeviceTypeId";
            default -> String.format("0x%08X", ioctl);
        };
    }
}
