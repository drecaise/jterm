/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Crash-safe JSON persistence shared by every config store.
 *
 * <p>Two guarantees the hand-rolled per-store code lacked:</p>
 * <ul>
 *   <li><b>Saves are atomic.</b> The value is serialized to a byte array first (so a
 *       serialization error never truncates the live file), written to a sibling
 *       {@code <name>.tmp}, then moved into place with {@code ATOMIC_MOVE} — a partial write
 *       or a full disk can no longer leave a half-written file that erases the previous good
 *       one on the next launch.</li>
 *   <li><b>Corrupt files are preserved, not overwritten.</b> On an unparsable load the file is
 *       renamed aside as {@code <name>.unreadable-N} and the caller is told the data was
 *       corrupt (distinct from simply missing), so a store can fall back to defaults without
 *       silently discarding the user's data.</li>
 * </ul>
 *
 * <p>All operations are best-effort: failures are logged (slf4j) rather than thrown, but
 * {@link #save} returns success so callers that care can react.</p>
 */
public final class JsonStore {

    private static final Logger LOG = LoggerFactory.getLogger(JsonStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonStore() {
    }

    /** The shared, pretty-printing mapper, for stores that need custom readers/writers. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Whether a load found the file missing, read it, or found it corrupt (and set it aside). */
    public enum LoadStatus {
        MISSING, LOADED, CORRUPT
    }

    /**
     * Outcome of a load: the parsed {@code value} (null unless {@link LoadStatus#LOADED}) plus the
     * {@code status} so callers can distinguish a first run ({@link LoadStatus#MISSING}) from a
     * damaged file ({@link LoadStatus#CORRUPT}) — a distinction that matters for the credential vault.
     */
    public record LoadResult<T>(T value, LoadStatus status) {
        public boolean isMissing() {
            return status == LoadStatus.MISSING;
        }

        public boolean isCorrupt() {
            return status == LoadStatus.CORRUPT;
        }
    }

    /**
     * Atomically persist {@code value} as pretty-printed JSON to {@code file}, restricting it to the
     * owner afterwards. Best-effort: on failure it logs a warning, cleans up the temp file and
     * returns {@code false} rather than throwing.
     *
     * @return {@code true} if the file was written and moved into place
     */
    public static boolean save(Path file, Object value) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            // Serialize before touching the filesystem so a serialization failure can't truncate
            // the existing good file.
            byte[] bytes = MAPPER.writeValueAsBytes(value);
            Files.write(tmp, bytes);
            AppPaths.restrictToOwner(tmp); // narrow the window where the temp copy is readable
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            AppPaths.restrictToOwner(file);
            return true;
        } catch (Exception e) {
            LOG.warn("Failed to save config file {}", file, e);
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception cleanup) {
                LOG.warn("Failed to remove temp file {}", tmp, cleanup);
            }
            return false;
        }
    }

    /** Load {@code file} as {@code type}, returning {@code null} when missing or corrupt. */
    public static <T> T load(Path file, Class<T> type) {
        return loadResult(file, type).value();
    }

    /** Load {@code file} as {@code type}, returning {@code null} when missing or corrupt. */
    public static <T> T load(Path file, TypeReference<T> type) {
        return loadResult(file, type).value();
    }

    /** Load {@code file} as {@code type}, reporting missing vs. loaded vs. corrupt. */
    public static <T> LoadResult<T> loadResult(Path file, Class<T> type) {
        return read(file, () -> MAPPER.readValue(file.toFile(), type));
    }

    /** Load {@code file} as {@code type}, reporting missing vs. loaded vs. corrupt. */
    public static <T> LoadResult<T> loadResult(Path file, TypeReference<T> type) {
        return read(file, () -> MAPPER.readValue(file.toFile(), type));
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read() throws Exception;
    }

    private static <T> LoadResult<T> read(Path file, Reader<T> reader) {
        if (!Files.isRegularFile(file)) {
            return new LoadResult<>(null, LoadStatus.MISSING);
        }
        try {
            return new LoadResult<>(reader.read(), LoadStatus.LOADED);
        } catch (Exception e) {
            LOG.warn("Corrupt config file {} — preserving it aside and falling back to defaults", file, e);
            preserveUnreadable(file);
            return new LoadResult<>(null, LoadStatus.CORRUPT);
        }
    }

    /**
     * Renames the unreadable {@code file} aside as {@code <name>.unreadable-<n>}, choosing the lowest
     * free counter so earlier backups are never overwritten. Best-effort: a failure is logged and the
     * caller still falls back to defaults.
     */
    private static void preserveUnreadable(Path file) {
        try {
            for (int counter = 1; ; counter++) {
                Path backup = file.resolveSibling(file.getFileName() + ".unreadable-" + counter);
                if (!Files.exists(backup)) {
                    Files.move(file, backup, StandardCopyOption.ATOMIC_MOVE);
                    return;
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not preserve unreadable config file {}", file, e);
        }
    }
}
