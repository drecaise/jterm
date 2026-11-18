package com.katmoda.jterm.terminal.rdp.smartcard;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * {@link Pcsc} over the system's PC/SC library through JNA: winscard.dll, macOS' PCSC framework or
 * pcsc-lite's libpcsclite. The three disagree on type sizes, structure packing, card states,
 * protocol bits, control codes and one error code, which is the classic PC/SC porting bug; each OS
 * has one {@link Layout} (checked against the platform headers in a test) and the translations
 * live here, so everything above speaks Windows.
 *
 * <p>The library is loaded by absolute path only. On Linux it must be root-owned and not writable by
 * others, file and directory (the rule the helper applies to libfaad): never a name the loader looks
 * up, never jterm's own folder.
 */
public final class JnaPcsc implements Pcsc {

    private static final Logger LOG = LoggerFactory.getLogger(JnaPcsc.class);

    enum Os { LINUX, MAC, WINDOWS }

    /**
     * Sizes and offsets of {@code DWORD}, the handles and {@code SCARD_READERSTATE} on one OS.
     * Linux (pcsc-lite): {@code unsigned long} DWORDs, natural alignment, a 33-byte ATR. macOS:
     * 32-bit DWORDs, {@code #pragma pack(1)}, 33 bytes. Windows: 32-bit DWORDs, pointer-sized
     * handles, a 36-byte ATR.
     */
    record Layout(int dword, int handle, int stateSize, int currentAt, int eventAt, int cbAtrAt, int atrAt,
                  int atrMax, int pciSize, boolean wide) {
        static Layout of(Os os) {
            return switch (os) {
                case LINUX -> new Layout(8, 8, 80, 16, 24, 32, 40, 33, 16, false);
                case MAC -> new Layout(4, 4, 61, 16, 20, 24, 28, 33, 8, false);
                case WINDOWS -> new Layout(4, 8, 64, 16, 20, 24, 28, 36, 8, true);
            };
        }
    }

    /** pcsc-lite's SCARD_E_UNSUPPORTED_FEATURE (Windows uses that value for SCARD_E_UNEXPECTED). */
    private static final int PCSCLITE_E_UNSUPPORTED_FEATURE = 0x8010001F;
    private static final int NATIVE_PROTOCOL_RAW = 0x4;
    /** Windows SCARD_CTL_CODE(3400): CM_IOCTL_GET_FEATURE_REQUEST. */
    static final int CM_IOCTL_GET_FEATURE_REQUEST = windowsCtlCode(3400);
    private static final int MAX_NAMES_CHARS = 16384;

    private final Os os;
    private final Layout layout;
    private final NativeLibrary lib;
    private final Charset charset;

    private JnaPcsc(Os os, NativeLibrary lib) {
        this.os = os;
        this.layout = Layout.of(os);
        this.lib = lib;
        this.charset = layout.wide() ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8;
    }

    /** The system's PC/SC, or empty (with the reason logged once) when there is none jterm trusts. */
    public static Optional<Pcsc> load() {
        Os os = currentOs();
        Optional<Path> path = libraryPath(os);
        if (path.isEmpty()) {
            LOG.info("No PC/SC library found that jterm will load; smart cards are unavailable");
            return Optional.empty();
        }
        try {
            return Optional.of(new JnaPcsc(os, NativeLibrary.getInstance(path.get().toString())));
        } catch (UnsatisfiedLinkError e) {
            LOG.info("PC/SC library {} could not be loaded: {}", path.get(), e.getMessage());
            return Optional.empty();
        }
    }

    static Os currentOs() {
        String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return n.contains("win") ? Os.WINDOWS : n.contains("mac") ? Os.MAC : Os.LINUX;
    }

    static Optional<Path> libraryPath(Os os) {
        switch (os) {
            case WINDOWS -> {
                String root = System.getenv("SystemRoot");
                Path p = Path.of(root != null && !root.isBlank() ? root : "C:\\Windows", "System32", "winscard.dll");
                return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
            }
            case MAC -> {
                // In the dyld shared cache since macOS 11: there may be no file, but this path loads.
                return Optional.of(Path.of("/System/Library/Frameworks/PCSC.framework/PCSC"));
            }
            default -> {
                boolean flatpak = inFlatpak();
                for (String dir : linuxLibraryDirs(flatpak)) {
                    Path p = Path.of(dir, "libpcsclite.so.1");
                    if (Files.exists(p) && trustedOnLinux(p, flatpak)) {
                        return Optional.of(p);
                    }
                }
                return Optional.empty();
            }
        }
    }

    private static final List<String> LINUX_SYSTEM_DIRS = List.of("/usr/lib64", "/usr/lib/x86_64-linux-gnu",
            "/usr/lib/aarch64-linux-gnu", "/lib64", "/lib/x86_64-linux-gnu", "/lib/aarch64-linux-gnu", "/usr/lib");

    /**
     * Where libpcsclite is looked for. The Flatpak runtime has none, so the Flatpak builds pcsc-lite's
     * client library into its own read-only {@code /app/lib}; that folder is searched only there.
     */
    static List<String> linuxLibraryDirs(boolean flatpak) {
        if (!flatpak) {
            return LINUX_SYSTEM_DIRS;
        }
        List<String> dirs = new ArrayList<>();
        dirs.add("/app/lib");
        dirs.addAll(LINUX_SYSTEM_DIRS);
        return List.copyOf(dirs);
    }

    static boolean inFlatpak() {
        return Files.exists(Path.of("/.flatpak-info"));
    }

    static boolean trustedOnLinux(Path link) {
        return trustedOnLinux(link, inFlatpak());
    }

    /** Root-owned and not writable by group or others: the link, its target and their folders. */
    static boolean trustedOnLinux(Path link, boolean flatpak) {
        try {
            Path real = link.toRealPath();
            for (Path p : List.of(link.getParent(), real, real.getParent())) {
                PosixFileAttributes a = Files.readAttributes(p, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                boolean readOnlyMount = flatpak && isFlatpakImagePath(p) && Files.getFileStore(p).isReadOnly();
                if (!acceptable("root".equals(a.owner().getName()), readOnlyMount, a.permissions())) {
                    LOG.info("Not loading {}: {} is not root-owned or is writable by others", link, p);
                    return false;
                }
            }
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    /**
     * Inside the Flatpak the runtime ({@code /usr}) and the app ({@code /app}) are read-only mounts
     * whose files show the installing user as owner; nothing in the sandbox can change them.
     */
    static boolean isFlatpakImagePath(Path p) {
        return p.startsWith("/usr") || p.startsWith("/app");
    }

    /** Owned by root, or on one of the Flatpak's read-only image mounts; never group or other writable. */
    static boolean acceptable(boolean rootOwned, boolean flatpakReadOnlyMount, Set<PosixFilePermission> perms) {
        return (rootOwned || flatpakReadOnlyMount)
                && !perms.contains(PosixFilePermission.GROUP_WRITE)
                && !perms.contains(PosixFilePermission.OTHERS_WRITE);
    }

    // ---- calls ----

    /**
     * Serialised: pcsc-lite's client library sets itself up on the first call, and two first calls
     * at once failed one with SCARD_F_INTERNAL_ERROR (measured).
     */
    @Override
    public synchronized Context establish(int scope) {
        Memory out = new Memory(layout.handle());
        out.clear();
        int rc = call("SCardEstablishContext", dword(scope), null, null, out);
        return new Context(rc, readHandle(out));
    }

    /** macOS' PC/SC crashes on the PnP pseudo-reader (WinPR's note); there it is polled instead. */
    @Override
    public boolean supportsPnpNotification() {
        return os != Os.MAC;
    }

    @Override
    public int release(long context) {
        return call("SCardReleaseContext", handle(context));
    }

    @Override
    public int isValid(long context) {
        return call("SCardIsValidContext", handle(context));
    }

    @Override
    public Readers listReaders(long context) {
        String fn = layout.wide() ? "SCardListReadersW" : "SCardListReaders";
        Memory len = dwordOut(0);
        int rc = call(fn, handle(context), null, null, len);
        if (rc != Scard.S_SUCCESS) {
            return new Readers(rc, List.of());
        }
        long chars = readDword(len);
        if (chars <= 0 || chars > MAX_NAMES_CHARS) {
            return new Readers(chars == 0 ? Scard.S_SUCCESS : Scard.E_INSUFFICIENT_BUFFER, List.of());
        }
        Memory buf = new Memory(chars * charSize());
        buf.clear();
        writeDword(len, chars);
        rc = call(fn, handle(context), null, buf, len);
        if (rc != Scard.S_SUCCESS) {
            return new Readers(rc, List.of());
        }
        return new Readers(rc, multiString(buf, Math.min(chars, readDword(len))));
    }

    @Override
    public int getStatusChange(long context, int timeoutMs, StatusSlot[] slots) {
        if (slots.length == 0) {
            return call(layout.wide() ? "SCardGetStatusChangeW" : "SCardGetStatusChange",
                    handle(context), dword(Integer.toUnsignedLong(timeoutMs)), null, dword(0));
        }
        Memory states = new Memory((long) layout.stateSize() * slots.length);
        states.clear();
        Memory[] names = new Memory[slots.length];
        for (int i = 0; i < slots.length; i++) {
            long base = (long) i * layout.stateSize();
            names[i] = string(slots[i].reader);
            states.setPointer(base, names[i]);
            putDword(states, base + layout.currentAt(), Integer.toUnsignedLong(slots[i].currentState));
        }
        int rc = call(layout.wide() ? "SCardGetStatusChangeW" : "SCardGetStatusChange",
                handle(context), dword(Integer.toUnsignedLong(timeoutMs)), states, dword(slots.length));
        for (int i = 0; i < slots.length; i++) {
            long base = (long) i * layout.stateSize();
            slots[i].eventState = (int) getDword(states, base + layout.eventAt());
            int n = (int) Math.min(Math.max(getDword(states, base + layout.cbAtrAt()), 0), layout.atrMax());
            slots[i].atr = states.getByteArray(base + layout.atrAt(), n);
        }
        return rc;
    }

    @Override
    public int cancel(long context) {
        return call("SCardCancel", handle(context));
    }

    @Override
    public Connected connect(long context, String reader, int shareMode, int protocols) {
        Memory card = new Memory(layout.handle());
        card.clear();
        Memory active = dwordOut(0);
        int rc = call(layout.wide() ? "SCardConnectW" : "SCardConnect", handle(context), string(reader),
                dword(shareMode), dword(toNativeProtocols(protocols)), card, active);
        return new Connected(rc, readHandle(card), fromNativeProtocols((int) readDword(active)));
    }

    @Override
    public IntResult reconnect(long card, int shareMode, int protocols, int initialization) {
        Memory active = dwordOut(0);
        int rc = call("SCardReconnect", handle(card), dword(shareMode), dword(toNativeProtocols(protocols)),
                dword(initialization), active);
        return new IntResult(rc, fromNativeProtocols((int) readDword(active)));
    }

    @Override
    public int disconnect(long card, int disposition) {
        return call("SCardDisconnect", handle(card), dword(disposition));
    }

    @Override
    public int beginTransaction(long card) {
        return call("SCardBeginTransaction", handle(card));
    }

    @Override
    public int endTransaction(long card, int disposition) {
        return call("SCardEndTransaction", handle(card), dword(disposition));
    }

    @Override
    public CardStatus status(long card) {
        int chars = 1024;
        Memory names = new Memory((long) chars * charSize());
        names.clear();
        Memory namesLen = dwordOut(chars);
        Memory state = dwordOut(0);
        Memory protocol = dwordOut(0);
        Memory atr = new Memory(36);
        atr.clear();
        Memory atrLen = dwordOut(layout.atrMax());
        int rc = call(layout.wide() ? "SCardStatusW" : "SCardStatus", handle(card), names, namesLen, state,
                protocol, atr, atrLen);
        if (rc != Scard.S_SUCCESS) {
            return new CardStatus(rc, "", Scard.CARD_UNKNOWN, 0, new byte[0]);
        }
        List<String> list = multiString(names, Math.min(chars, readDword(namesLen)));
        int n = (int) Math.min(Math.max(readDword(atrLen), 0), layout.atrMax());
        return new CardStatus(rc, list.isEmpty() ? "" : list.get(0), toWindowsCardState((int) readDword(state)),
                fromNativeProtocols((int) readDword(protocol)), atr.getByteArray(0, n));
    }

    @Override
    public Transmitted transmit(long card, int protocol, byte[] send, int recvMax) {
        Memory sendPci = pci(toNativeProtocols(protocol));
        Memory recvPci = pci(0);
        Memory in = new Memory(Math.max(1, send.length));
        in.write(0, send, 0, send.length);
        int max = (int) Math.min(Integer.toUnsignedLong(recvMax), ScardNdr.MAX_BUFFER);
        Memory out = new Memory(Math.max(1, max));
        Memory outLen = dwordOut(max);
        int rc = call("SCardTransmit", handle(card), sendPci, in, dword(send.length), recvPci, out, outLen);
        if (rc != Scard.S_SUCCESS) {
            return new Transmitted(rc, 0, new byte[0]);
        }
        int n = (int) Math.min(Math.max(readDword(outLen), 0), max);
        return new Transmitted(rc, fromNativeProtocols((int) getDword(recvPci, 0)), out.getByteArray(0, n));
    }

    @Override
    public Bytes control(long card, int code, byte[] in, int outMax) {
        int nativeCode = os == Os.WINDOWS ? code : toPcscLiteCtlCode(code);
        Memory inBuf = new Memory(Math.max(1, in.length));
        inBuf.write(0, in, 0, in.length);
        int max = (int) Math.min(Integer.toUnsignedLong(outMax), ScardNdr.MAX_BUFFER);
        Memory out = new Memory(Math.max(1, max));
        Memory returned = dwordOut(0);
        int rc = call(os == Os.MAC ? "SCardControl132" : "SCardControl", handle(card), dword(nativeCode),
                inBuf, dword(in.length), out, dword(max), returned);
        if (rc != Scard.S_SUCCESS) {
            return new Bytes(rc, new byte[0]);
        }
        byte[] data = out.getByteArray(0, (int) Math.min(Math.max(readDword(returned), 0), max));
        if (os != Os.WINDOWS && code == CM_IOCTL_GET_FEATURE_REQUEST) {
            data = featuresToWindows(data);
        }
        return new Bytes(rc, data);
    }

    @Override
    public Bytes getAttrib(long card, int attrId) {
        Memory len = dwordOut(0);
        int rc = call("SCardGetAttrib", handle(card), dword(attrId), null, len);
        if (rc != Scard.S_SUCCESS) {
            return new Bytes(rc, new byte[0]);
        }
        long n = readDword(len);
        if (n <= 0 || n > ScardNdr.MAX_DATA) {
            return new Bytes(n == 0 ? rc : Scard.E_INSUFFICIENT_BUFFER, new byte[0]);
        }
        Memory buf = new Memory(n);
        rc = call("SCardGetAttrib", handle(card), dword(attrId), buf, len);
        if (rc != Scard.S_SUCCESS) {
            return new Bytes(rc, new byte[0]);
        }
        return new Bytes(rc, buf.getByteArray(0, (int) Math.min(n, readDword(len))));
    }

    // ---- translations (pure, tested) ----

    /** Windows SCARD_CTL_CODE(n): CTL_CODE(FILE_DEVICE_SMARTCARD, n, METHOD_BUFFERED, FILE_ANY_ACCESS). */
    static int windowsCtlCode(int function) {
        return 0x00310000 | (function << 2);
    }

    /** pcsc-lite's SCARD_CTL_CODE(n) for a Windows code; anything else passes unchanged. */
    static int toPcscLiteCtlCode(int windows) {
        if ((windows & 0xFFFF0000) == 0x00310000 && (windows & 0x3) == 0) {
            return 0x42000000 + ((windows >>> 2) & 0xFFF);
        }
        return windows;
    }

    static int toWindowsCtlCode(int pcscLite) {
        if ((pcscLite & 0xFFFFF000) == 0x42000000) {
            return windowsCtlCode(pcscLite & 0xFFF);
        }
        return pcscLite;
    }

    /** A feature request's answer is TLVs (tag, 4, big-endian control code): rewrite the codes. */
    static byte[] featuresToWindows(byte[] tlvs) {
        byte[] out = tlvs.clone();
        for (int i = 0; i + 6 <= out.length; i += 2 + (out[i + 1] & 0xFF)) {
            if ((out[i + 1] & 0xFF) != 4) {
                continue;
            }
            int v = (out[i + 2] & 0xFF) << 24 | (out[i + 3] & 0xFF) << 16 | (out[i + 4] & 0xFF) << 8 | (out[i + 5] & 0xFF);
            int w = toWindowsCtlCode(v);
            out[i + 2] = (byte) (w >>> 24);
            out[i + 3] = (byte) (w >>> 16);
            out[i + 4] = (byte) (w >>> 8);
            out[i + 5] = (byte) w;
        }
        return out;
    }

    int toNativeProtocols(int windows) {
        if (os == Os.WINDOWS) {
            return windows;
        }
        return (windows & 0x3) | ((windows & Scard.PROTOCOL_RAW) != 0 ? NATIVE_PROTOCOL_RAW : 0);
    }

    int fromNativeProtocols(int nativeProtocols) {
        if (os == Os.WINDOWS) {
            return nativeProtocols;
        }
        return (nativeProtocols & 0x3) | ((nativeProtocols & NATIVE_PROTOCOL_RAW) != 0 ? Scard.PROTOCOL_RAW : 0);
    }

    /**
     * pcsc-lite reports the card state as flags; Windows as the highest stage reached. pcsc-lite
     * never sets SPECIFIC, and a connected card reads as NEGOTIABLE: reported as such, Windows'
     * minidriver takes the card for unusable and disconnects (measured, certutil's key test), so
     * both are SPECIFIC, as WinPR maps them.
     */
    int toWindowsCardState(int nativeState) {
        return os == Os.WINDOWS ? nativeState : cardStateFromPcscLite(nativeState);
    }

    static int cardStateFromPcscLite(int flags) {
        if ((flags & 0x60) != 0) {
            return Scard.CARD_SPECIFIC;
        }
        if ((flags & 0x10) != 0) {
            return Scard.CARD_POWERED;
        }
        if ((flags & 0x08) != 0) {
            return Scard.CARD_SWALLOWED;
        }
        if ((flags & 0x04) != 0) {
            return Scard.CARD_PRESENT;
        }
        if ((flags & 0x02) != 0) {
            return Scard.CARD_ABSENT;
        }
        return Scard.CARD_UNKNOWN;
    }

    int toWindowsRc(int rc) {
        return os != Os.WINDOWS && rc == PCSCLITE_E_UNSUPPORTED_FEATURE ? Scard.E_UNSUPPORTED_FEATURE : rc;
    }

    // ---- JNA plumbing ----

    private int call(String name, Object... args) {
        Function f = lib.getFunction(name);
        int rc = os == Os.LINUX ? (int) f.invokeLong(args) : f.invokeInt(args);
        return toWindowsRc(rc);
    }

    private Object dword(long v) {
        return os == Os.LINUX ? new NativeLong(v) : (Object) (int) v;
    }

    private Object handle(long h) {
        return switch (os) {
            case LINUX -> new NativeLong(h);
            case MAC -> (int) h;
            case WINDOWS -> h;
        };
    }

    private long readHandle(Memory m) {
        return layout.handle() == 8 ? m.getLong(0) : m.getInt(0);
    }

    private Memory dwordOut(long initial) {
        Memory m = new Memory(layout.dword());
        writeDword(m, initial);
        return m;
    }

    private long readDword(Memory m) {
        return getDword(m, 0);
    }

    private void writeDword(Memory m, long v) {
        putDword(m, 0, v);
    }

    private long getDword(Pointer p, long off) {
        return layout.dword() == 8 ? p.getLong(off) & 0xFFFFFFFFL : p.getInt(off) & 0xFFFFFFFFL;
    }

    private void putDword(Pointer p, long off, long v) {
        if (layout.dword() == 8) {
            p.setLong(off, v);
        } else {
            p.setInt(off, (int) v);
        }
    }

    private Memory pci(int protocol) {
        Memory m = new Memory(layout.pciSize());
        m.clear();
        putDword(m, 0, protocol);
        putDword(m, layout.dword(), layout.pciSize());
        return m;
    }

    private int charSize() {
        return layout.wide() ? 2 : 1;
    }

    private Memory string(String s) {
        byte[] b = (s + "\0").getBytes(charset);
        Memory m = new Memory(b.length);
        m.write(0, b, 0, b.length);
        return m;
    }

    private List<String> multiString(Memory m, long chars) {
        byte[] raw = m.getByteArray(0, (int) (chars * charSize()));
        String all = new String(raw, charset);
        // Ends at the first empty entry (the double NUL); nothing after it is a name.
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < all.length()) {
            int nul = all.indexOf('\0', start);
            String part = all.substring(start, nul < 0 ? all.length() : nul);
            if (part.isEmpty()) {
                break;
            }
            out.add(part);
            if (nul < 0) {
                break;
            }
            start = nul + 1;
        }
        return out;
    }
}
