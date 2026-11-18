package com.katmoda.jterm.terminal.rdp.smartcard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-OS layouts and translations, against the platforms' headers (no PC/SC needed). */
class JnaPcscTest {

    @Test
    void readerStateLayoutsMatchEachPlatformsHeader() {
        // pcsc-lite (x86-64/arm64): char*, void*, 3 × unsigned long, BYTE[33], padded to 8 → 80.
        JnaPcsc.Layout linux = JnaPcsc.Layout.of(JnaPcsc.Os.LINUX);
        assertEquals(80, linux.stateSize());
        assertEquals(40, linux.atrAt());
        assertEquals(33, linux.atrMax());
        assertEquals(16, linux.pciSize());
        // macOS PCSC.framework: #pragma pack(1), uint32_t DWORDs → 8+8+12+33 = 61.
        JnaPcsc.Layout mac = JnaPcsc.Layout.of(JnaPcsc.Os.MAC);
        assertEquals(61, mac.stateSize());
        assertEquals(28, mac.atrAt());
        assertEquals(4, mac.handle());
        // winscard.h: LPCWSTR, LPVOID, 3 × DWORD, BYTE[36] → 64; handles are ULONG_PTR.
        JnaPcsc.Layout win = JnaPcsc.Layout.of(JnaPcsc.Os.WINDOWS);
        assertEquals(64, win.stateSize());
        assertEquals(36, win.atrMax());
        assertEquals(8, win.handle());
    }

    @Test
    void controlCodesTranslateBetweenWindowsAndPcscLite() {
        assertEquals(0x00313520, JnaPcsc.CM_IOCTL_GET_FEATURE_REQUEST);
        assertEquals(0x42000D48, JnaPcsc.toPcscLiteCtlCode(JnaPcsc.CM_IOCTL_GET_FEATURE_REQUEST));
        assertEquals(JnaPcsc.CM_IOCTL_GET_FEATURE_REQUEST, JnaPcsc.toWindowsCtlCode(0x42000D48));
        // A CCID reader's feature codes (0x4233xxxx) don't fit a Windows code: they pass both ways unchanged.
        assertEquals(0x42330006, JnaPcsc.toPcscLiteCtlCode(0x42330006));
        assertEquals(0x42330006, JnaPcsc.toWindowsCtlCode(0x42330006));
        assertArrayEquals(HexFormat.of().parseHex("06040031352012044233001200"),
                JnaPcsc.featuresToWindows(HexFormat.of().parseHex("060442000d4812044233001200")));
    }

    @Test
    void aConnectedCardIsSpecific_asWindowsExpects() {
        // pcsc-lite: PRESENT|POWERED|NEGOTIABLE for a connected card, never SPECIFIC (measured: 0x34).
        assertEquals(Scard.CARD_SPECIFIC, JnaPcsc.cardStateFromPcscLite(0x34));
        assertEquals(Scard.CARD_SPECIFIC, JnaPcsc.cardStateFromPcscLite(0x40));
        assertEquals(Scard.CARD_POWERED, JnaPcsc.cardStateFromPcscLite(0x14));
        assertEquals(Scard.CARD_ABSENT, JnaPcsc.cardStateFromPcscLite(0x02));
        assertEquals(Scard.CARD_UNKNOWN, JnaPcsc.cardStateFromPcscLite(0));
    }

    @Test
    void theFlatpaksOwnLibraryFolderIsSearchedOnlyInTheFlatpak() {
        assertFalse(JnaPcsc.linuxLibraryDirs(false).contains("/app/lib"));
        assertEquals("/app/lib", JnaPcsc.linuxLibraryDirs(true).get(0));
        assertTrue(JnaPcsc.linuxLibraryDirs(true).containsAll(JnaPcsc.linuxLibraryDirs(false)));
    }

    @Test
    void onlyTheFlatpaksImageMountsStandInForRootOwnership() {
        assertTrue(JnaPcsc.isFlatpakImagePath(Path.of("/app/lib/libpcsclite.so.1")));
        assertTrue(JnaPcsc.isFlatpakImagePath(Path.of("/usr/lib/x86_64-linux-gnu")));
        assertFalse(JnaPcsc.isFlatpakImagePath(Path.of("/application/lib")));
        assertFalse(JnaPcsc.isFlatpakImagePath(Path.of("/home/user/.var/app/com.katmoda.jterm/lib")));
        assertFalse(JnaPcsc.isFlatpakImagePath(Path.of("/tmp/app/lib")));
    }

    @Test
    void ownershipAndWriteBitsDecideTrust() {
        Set<PosixFilePermission> r755 = PosixFilePermissions.fromString("rwxr-xr-x");
        assertTrue(JnaPcsc.acceptable(true, false, r755));
        assertTrue(JnaPcsc.acceptable(false, true, r755));
        assertFalse(JnaPcsc.acceptable(false, false, r755));
        assertFalse(JnaPcsc.acceptable(true, false, PosixFilePermissions.fromString("rwxrwxr-x")));
        assertFalse(JnaPcsc.acceptable(false, true, PosixFilePermissions.fromString("rwxr-xrwx")));
    }

    @Test
    void aUserWritableLibraryIsRefusedEvenInTheFlatpak(@TempDir Path dir) throws Exception {
        Path lib = Files.createFile(dir.resolve("libpcsclite.so.1"));
        assertFalse(JnaPcsc.trustedOnLinux(lib, true));
        assertFalse(JnaPcsc.trustedOnLinux(lib, false));
    }
}
