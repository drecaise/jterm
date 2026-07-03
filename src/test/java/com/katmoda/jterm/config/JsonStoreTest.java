/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.config;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonStoreTest {

    @TempDir
    Path dir;

    /** Simple JSON-serializable payload for round-trip tests. */
    record Widget(String name, int count) {
    }

    @Test
    void saveThenLoadRoundTrips() {
        Path file = dir.resolve("widget.json");
        Widget value = new Widget("gauge", 7);

        assertTrue(JsonStore.save(file, value));

        JsonStore.LoadResult<Widget> result = JsonStore.loadResult(file, Widget.class);
        assertEquals(JsonStore.LoadStatus.LOADED, result.status());
        assertEquals(value, result.value());

        // No temp file left behind after a successful save.
        assertFalse(Files.exists(dir.resolve("widget.json.tmp")));
    }

    @Test
    void loadOfMissingFileReportsMissing() {
        Path file = dir.resolve("does-not-exist.json");

        JsonStore.LoadResult<Widget> result = JsonStore.loadResult(file, Widget.class);
        assertEquals(JsonStore.LoadStatus.MISSING, result.status());
        assertTrue(result.isMissing());
        assertNull(result.value());

        // Convenience overload also returns null.
        assertNull(JsonStore.load(file, Widget.class));
    }

    @Test
    void corruptFileIsPreservedAsUnreadable() throws Exception {
        Path file = dir.resolve("widget.json");
        String corrupt = "{ this is not valid json";
        Files.writeString(file, corrupt);

        JsonStore.LoadResult<Widget> result = JsonStore.loadResult(file, Widget.class);
        assertEquals(JsonStore.LoadStatus.CORRUPT, result.status());
        assertTrue(result.isCorrupt());
        assertNull(result.value());

        // The corrupt file was moved aside, not left in place.
        assertFalse(Files.exists(file));
        Path preserved = dir.resolve("widget.json.unreadable-1");
        assertTrue(Files.exists(preserved));
        // The original (corrupt) bytes are intact.
        assertEquals(corrupt, Files.readString(preserved, StandardCharsets.UTF_8));
    }

    @Test
    void secondCorruptLoadIncrementsCounter() throws Exception {
        Path file = dir.resolve("widget.json");

        String first = "{ first corrupt";
        Files.writeString(file, first);
        assertTrue(JsonStore.loadResult(file, Widget.class).isCorrupt());

        String second = "{ second corrupt";
        Files.writeString(file, second);
        assertTrue(JsonStore.loadResult(file, Widget.class).isCorrupt());

        Path first_backup = dir.resolve("widget.json.unreadable-1");
        Path second_backup = dir.resolve("widget.json.unreadable-2");
        assertTrue(Files.exists(first_backup));
        assertTrue(Files.exists(second_backup));

        // The earlier backup is untouched; the counter incremented for the new one.
        assertEquals(first, Files.readString(first_backup, StandardCharsets.UTF_8));
        assertEquals(second, Files.readString(second_backup, StandardCharsets.UTF_8));
    }

    @Test
    void saveOverwritesExistingFileAtomically() {
        Path file = dir.resolve("widget.json");

        assertTrue(JsonStore.save(file, new Widget("old", 1)));
        Widget updated = new Widget("new", 2);
        assertTrue(JsonStore.save(file, updated));

        assertEquals(updated, JsonStore.load(file, Widget.class));
        // The temp file is gone after the atomic move.
        assertFalse(Files.exists(dir.resolve("widget.json.tmp")));
    }

    @Test
    void saveFailureLeavesExistingFileIntact() throws Exception {
        Path file = dir.resolve("widget.json");
        Widget good = new Widget("keep-me", 42);
        assertTrue(JsonStore.save(file, good));
        String beforeBytes = Files.readString(file, StandardCharsets.UTF_8);

        // A bare Object has no properties; Jackson's default FAIL_ON_EMPTY_BEANS throws, so the
        // serialize-before-write design must leave the existing good file untouched.
        assertFalse(JsonStore.save(file, new Object()));

        // Previous content unchanged, and no stray temp file remains.
        assertEquals(beforeBytes, Files.readString(file, StandardCharsets.UTF_8));
        assertFalse(Files.exists(dir.resolve("widget.json.tmp")));
        assertEquals(good, JsonStore.load(file, Widget.class));
    }

    @Test
    void typeReferenceOverloadRoundTripsPreservingOrder() {
        Path file = dir.resolve("map.json");
        LinkedHashMap<String, String> value = new LinkedHashMap<>();
        value.put("zulu", "1");
        value.put("alpha", "2");
        value.put("mike", "3");

        assertTrue(JsonStore.save(file, value));

        JsonStore.LoadResult<LinkedHashMap<String, String>> result =
                JsonStore.loadResult(file, new TypeReference<LinkedHashMap<String, String>>() {
                });
        assertEquals(JsonStore.LoadStatus.LOADED, result.status());
        assertEquals(value, result.value());

        // Insertion order is preserved across the round trip.
        assertEquals(
                java.util.List.of("zulu", "alpha", "mike"),
                java.util.List.copyOf(result.value().keySet()));

        // Convenience TypeReference overload also works.
        Map<String, String> viaConvenience =
                JsonStore.load(file, new TypeReference<LinkedHashMap<String, String>>() {
                });
        assertEquals(value, viaConvenience);
    }
}
