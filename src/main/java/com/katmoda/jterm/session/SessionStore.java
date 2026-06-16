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
package com.katmoda.jterm.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.katmoda.jterm.config.AppPaths;
import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.security.VaultKeys;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * The chain of ancestor folders of {@code node}, ordered root → … → immediate parent.
     * Empty if the node is the root or isn't found in the tree.
     */
    public List<FolderNode> ancestorsOf(SessionNode node) {
        List<FolderNode> chain = new ArrayList<>();
        findChain(root, node, chain);
        return chain;
    }

    /** Depth-first search that records the folder chain leading to {@code target}. */
    private boolean findChain(FolderNode folder, SessionNode target, List<FolderNode> chain) {
        chain.add(folder);
        for (SessionNode child : folder.getChildren()) {
            if (child == target) {
                return true;
            }
            if (child instanceof FolderNode sub && findChain(sub, target, chain)) {
                return true;
            }
        }
        chain.remove(chain.size() - 1);
        return false;
    }

    /**
     * Resolves the effective SSH username for {@code cfg} via the inheritance cascade: the
     * session's own value, then ancestor folders nearest → root, then the global default, and
     * finally the OS user. Always returns a non-blank value.
     */
    public String effectiveUser(SshSessionConfig cfg) {
        String own = cfg.getUser();
        if (own != null && !own.isBlank()) {
            return own;
        }
        List<FolderNode> ancestors = ancestorsOf(cfg);
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            String folderUser = ancestors.get(i).getUser();
            if (folderUser != null && !folderUser.isBlank()) {
                return folderUser;
            }
        }
        String global = AppSettings.get().getDefaultUsername();
        if (global != null && !global.isBlank()) {
            return global;
        }
        return System.getProperty("user.name", "");
    }

    /**
     * Resolves the effective tab color for {@code cfg} via the inheritance cascade: the session's
     * own value, then ancestor folders nearest → root, then the global default. Returns
     * {@code null} when nothing is set (use the theme default).
     */
    public String effectiveTabColorHex(SshSessionConfig cfg) {
        String own = cfg.getTabColorHex();
        if (own != null && !own.isBlank()) {
            return own;
        }
        List<FolderNode> ancestors = ancestorsOf(cfg);
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            String folderColor = ancestors.get(i).getTabColorHex();
            if (folderColor != null && !folderColor.isBlank()) {
                return folderColor;
            }
        }
        return AppSettings.get().getDefaultTabColorHex();
    }

    /**
     * Resolves the effective SSH private-key path for {@code cfg} via the inheritance cascade: the
     * session's own value, then ancestor folders nearest → root, then the global default. Returns
     * {@code null} when nothing is set (fall back to the auto-discovered {@code ~/.ssh} identities).
     */
    public String effectiveKeyPath(SshSessionConfig cfg) {
        String own = cfg.getKeyPath();
        if (own != null && !own.isBlank()) {
            return own;
        }
        List<FolderNode> ancestors = ancestorsOf(cfg);
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            String folderKey = ancestors.get(i).getKeyPath();
            if (folderKey != null && !folderKey.isBlank()) {
                return folderKey;
            }
        }
        String global = AppSettings.get().getDefaultKeyPath();
        return (global != null && !global.isBlank()) ? global : null;
    }

    /**
     * The credential-vault keys to consult, in priority order, for {@code cfg}'s SSH key
     * passphrase: the session level, then ancestor folders nearest → root, then the global
     * default. The first key the vault actually holds wins.
     */
    public List<String> keyPassphraseVaultKeys(SshSessionConfig cfg) {
        List<String> keys = new ArrayList<>();
        keys.add(VaultKeys.sessionKeyPassphrase(cfg.getId()));
        List<FolderNode> ancestors = ancestorsOf(cfg);
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            keys.add(VaultKeys.folderKeyPassphrase(ancestors.get(i).getId()));
        }
        keys.add(VaultKeys.GLOBAL_KEY_PASSPHRASE);
        return keys;
    }

    /**
     * The credential-vault keys to consult, in priority order, for {@code cfg}'s default-password
     * fallback: ancestor folders nearest → root, then the global default. The session's own saved
     * password is handled separately (it is gated by the session's {@code savePassword} flag).
     */
    public List<String> defaultPasswordVaultKeys(SshSessionConfig cfg) {
        List<String> keys = new ArrayList<>();
        List<FolderNode> ancestors = ancestorsOf(cfg);
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            keys.add(VaultKeys.folderPassword(ancestors.get(i).getId()));
        }
        keys.add(VaultKeys.GLOBAL_PASSWORD);
        return keys;
    }
}
