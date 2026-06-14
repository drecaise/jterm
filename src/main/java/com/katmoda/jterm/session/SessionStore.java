package com.katmoda.jterm.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.katmoda.jterm.config.AppPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads/saves the saved-sessions tree as {@code sessions.json} in the config dir.
 * The root is a {@link FolderNode} whose children form the recursive structure.
 */
public final class SessionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path file;
    private FolderNode root;

    public SessionStore() {
        this.file = AppPaths.file("sessions.json");
        this.root = load();
    }

    public FolderNode root() {
        return root;
    }

    private FolderNode load() {
        if (Files.isRegularFile(file)) {
            try {
                return MAPPER.readValue(file.toFile(), FolderNode.class);
            } catch (Exception ignored) {
                // Corrupt file: preserve it before starting fresh, so the user's
                // sessions aren't silently lost on the next save().
                preserveUnreadable();
            }
        }
        FolderNode fresh = new FolderNode("Sessions");
        return fresh;
    }

    /**
     * Renames the unreadable {@code sessions.json} aside as
     * {@code sessions.json.unreadable-<n>}, choosing the lowest free counter so
     * earlier backups are never overwritten. Best-effort: failures are swallowed
     * so launch still proceeds.
     */
    private void preserveUnreadable() {
        try {
            for (int counter = 1; ; counter++) {
                Path backup = file.resolveSibling(file.getFileName() + ".unreadable-" + counter);
                if (!Files.exists(backup)) {
                    Files.move(file, backup, StandardCopyOption.ATOMIC_MOVE);
                    return;
                }
            }
        } catch (Exception ignored) {
            // Couldn't back it up (e.g. permissions); fall through and start fresh.
        }
    }

    public void save() {
        try {
            MAPPER.writeValue(file.toFile(), root);
        } catch (Exception ignored) {
        }
    }
}
