package com.katmoda.jterm.terminal.rdp.smartcard;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScardPolicyTest {

    @Test
    void pcscLitesIndexSuffixIsIgnored_otherwiseExact() {
        assertEquals("Yubico YubiKey OTP+FIDO+CCID", ScardPolicy.baseName("Yubico YubiKey OTP+FIDO+CCID 00 00"));
        assertTrue(ScardPolicy.picked("Yubico YubiKey OTP+FIDO+CCID 01 00", List.of("Yubico YubiKey OTP+FIDO+CCID 00 00")));
        assertTrue(ScardPolicy.picked("Yubico YubiKey OTP+FIDO+CCID 0", List.of("Yubico YubiKey OTP+FIDO+CCID 0")),
                "Windows names have no suffix");
        assertFalse(ScardPolicy.picked("Yubico YubiKey OTP+FIDO+CCID 00 00", List.of("Yubico YubiKey")),
                "no prefix matching");
        assertFalse(ScardPolicy.picked("Anything", List.of("", " ")));
        assertEquals(List.of("A 00 00"), ScardPolicy.visible(List.of("A 00 00", "B 00 00"), List.of("A")));
    }

    @Test
    void pinOutcomesFollowIso7816() {
        assertEquals(ScardPolicy.PinOutcome.OK, ScardPolicy.pinOutcome(HexFormat.of().parseHex("9000")));
        assertEquals(ScardPolicy.PinOutcome.WRONG, ScardPolicy.pinOutcome(HexFormat.of().parseHex("63c2")));
        assertEquals(ScardPolicy.PinOutcome.ONE_LEFT, ScardPolicy.pinOutcome(HexFormat.of().parseHex("63c1")));
        assertEquals(ScardPolicy.PinOutcome.BLOCKED, ScardPolicy.pinOutcome(HexFormat.of().parseHex("63c0")));
        assertEquals(ScardPolicy.PinOutcome.BLOCKED, ScardPolicy.pinOutcome(HexFormat.of().parseHex("6983")));
        assertTrue(ScardPolicy.isPinCommand(HexFormat.of().parseHex("00200080")));
        assertTrue(ScardPolicy.isPinCommand(HexFormat.of().parseHex("002c0080")));
        assertFalse(ScardPolicy.isPinCommand(HexFormat.of().parseHex("ff200080")), "a reader pseudo-APDU");
        assertFalse(ScardPolicy.isPinCommand(HexFormat.of().parseHex("00a40400")));
    }

    @Test
    void sharingAndDispositionRules() {
        assertEquals(Scard.SHARE_SHARED, ScardPolicy.shareMode(Scard.SHARE_EXCLUSIVE));
        assertEquals(-1, ScardPolicy.shareMode(Scard.SHARE_DIRECT));
        assertEquals(Scard.RESET_CARD, ScardPolicy.disposition(Scard.EJECT_CARD));
        assertEquals(Scard.PROTOCOL_T0 | Scard.PROTOCOL_T1, ScardPolicy.protocols(Scard.PROTOCOL_UNDEFINED));
    }

    @Test
    void featureListsKeepPinPadEntriesOnly_andStopAtGarbage() {
        byte[] tlvs = HexFormat.of().parseHex("01044233000106044233000612044233001207ff");
        assertEquals("060442330006120442330012", HexFormat.of().formatHex(ScardPolicy.filterFeatures(tlvs)));
        assertEquals(2, ScardPolicy.featureCodes(ScardPolicy.filterFeatures(tlvs)).size());
    }
}
