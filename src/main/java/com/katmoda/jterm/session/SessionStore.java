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

import com.katmoda.jterm.config.AppPaths;
import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.config.JsonStore;
import com.katmoda.jterm.security.VaultKeys;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads/saves the saved-sessions tree as {@code sessions.json} in the config dir.
 * The root is a {@link FolderNode} whose children form the recursive structure.
 */
public final class SessionStore {

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
        // A corrupt file is preserved aside by JsonStore (not overwritten on the next save), so the
        // user's sessions aren't silently lost; we still fall back to a fresh tree so launch proceeds.
        FolderNode loaded = JsonStore.load(file, FolderNode.class);
        return loaded != null ? loaded : new FolderNode("Sessions");
    }

    public void save() {
        JsonStore.save(file, root);
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

    /** All SSH sessions under {@code folder}, recursively, in depth-first tree order. */
    public static List<SshSessionConfig> collectSshSessions(FolderNode folder) {
        List<SshSessionConfig> out = new ArrayList<>();
        collectSshSessions(folder, out);
        return out;
    }

    private static void collectSshSessions(FolderNode folder, List<SshSessionConfig> out) {
        for (SessionNode child : folder.getChildren()) {
            if (child instanceof SshSessionConfig ssh) {
                out.add(ssh);
            } else if (child instanceof FolderNode sub) {
                collectSshSessions(sub, out);
            }
        }
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
     * Resolves the effective keep-alive interval (seconds; {@code 0} = off) for {@code cfg} via the
     * inheritance cascade: the session's own value, then ancestor folders nearest → root, then the
     * global default. A {@code null} at the session/folder level means "inherit" (keep walking up);
     * an explicit {@code 0} means off and stops the walk, so a session can disable keep-alive even
     * when a parent enables it.
     */
    public int effectiveKeepAliveSeconds(SshSessionConfig cfg) {
        Integer own = cfg.getKeepAliveSeconds();
        if (own != null) {
            return Math.max(0, own);
        }
        List<FolderNode> ancestors = ancestorsOf(cfg);
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            Integer folderValue = ancestors.get(i).getKeepAliveSeconds();
            if (folderValue != null) {
                return Math.max(0, folderValue);
            }
        }
        return Math.max(0, AppSettings.get().getDefaultKeepAliveSeconds());
    }

    // ---- folder-level resolution (for the new/edit dialogs' "(inherit: …)" hints) ----
    //
    // Two flavours per attribute, mirroring the session-level effective* cascade:
    //   effective*(FolderNode)  — the folder's own value, then ancestors nearest → root, then the
    //                             global default: what a blank session field placed in this folder
    //                             would resolve to. A null folder resolves to just the global chain.
    //   inherited*(FolderNode)  — excludes the folder's own value (ancestors → global): what a blank
    //                             field on the folder *itself* would inherit.

    /**
     * The username a blank session field would resolve to if the session lived in {@code folder}:
     * the folder's own default, then ancestors nearest → root, then the global default, then the OS
     * user. Always returns a non-blank value. A {@code null} folder resolves against the global
     * default → OS user.
     */
    public String effectiveUser(FolderNode folder) {
        if (folder != null) {
            String own = folder.getUser();
            if (own != null && !own.isBlank()) {
                return own;
            }
            return inheritedUser(folder); // ancestors → global → OS user
        }
        String global = AppSettings.get().getDefaultUsername();
        return (global != null && !global.isBlank()) ? global : System.getProperty("user.name", "");
    }

    /**
     * The username a blank field on {@code folder} would inherit: the nearest ancestor folder's
     * value, then the global default, then the OS user. (Excludes the folder's own value.)
     */
    public String inheritedUser(FolderNode folder) {
        List<FolderNode> ancestors = ancestorsOf(folder);
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            String folderUser = ancestors.get(i).getUser();
            if (folderUser != null && !folderUser.isBlank()) {
                return folderUser;
            }
        }
        String global = AppSettings.get().getDefaultUsername();
        return (global != null && !global.isBlank()) ? global : System.getProperty("user.name", "");
    }

    /**
     * The key path a blank session field would resolve to if the session lived in {@code folder}:
     * the folder's own value, then ancestors nearest → root, then the global default. {@code null}
     * if nothing is set. A {@code null} folder resolves against the global default only.
     */
    public String effectiveKeyPath(FolderNode folder) {
        if (folder != null) {
            String own = folder.getKeyPath();
            if (own != null && !own.isBlank()) {
                return own;
            }
            return inheritedKeyPath(folder); // ancestors → global
        }
        String global = AppSettings.get().getDefaultKeyPath();
        return (global != null && !global.isBlank()) ? global : null;
    }

    /** The key path a blank field on {@code folder} would inherit (ancestors, then global). */
    public String inheritedKeyPath(FolderNode folder) {
        List<FolderNode> ancestors = ancestorsOf(folder);
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
     * The keep-alive interval (seconds; {@code 0} = off) a blank/"inherit" session field would
     * resolve to if the session lived in {@code folder}: the folder's own explicit value, then
     * ancestors nearest → root, then the global default. A {@code null} folder resolves against the
     * global default only.
     */
    public int effectiveKeepAliveSeconds(FolderNode folder) {
        if (folder != null) {
            Integer own = folder.getKeepAliveSeconds();
            if (own != null) {
                return Math.max(0, own);
            }
            return inheritedKeepAliveSeconds(folder); // ancestors → global
        }
        return Math.max(0, AppSettings.get().getDefaultKeepAliveSeconds());
    }

    /**
     * The keep-alive interval (seconds; {@code 0} = off) an "inherit" setting on {@code folder}
     * would resolve to: the nearest ancestor folder's explicit value, then the global default.
     * (Excludes the folder's own value.)
     */
    public int inheritedKeepAliveSeconds(FolderNode folder) {
        List<FolderNode> ancestors = ancestorsOf(folder);
        for (int i = ancestors.size() - 1; i >= 0; i--) {
            Integer value = ancestors.get(i).getKeepAliveSeconds();
            if (value != null) {
                return Math.max(0, value);
            }
        }
        return Math.max(0, AppSettings.get().getDefaultKeepAliveSeconds());
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
