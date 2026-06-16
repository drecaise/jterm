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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.katmoda.jterm.config.AppPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The user's SSH tunnels, persisted as {@code tunnels.json} in the config dir. A small mutable
 * singleton (mirroring {@code macro.MacroLibrary}) read live by the Tunneling manager dialog and
 * the launch-time auto-start pass.
 */
public final class TunnelStore {

    private static final TunnelStore INSTANCE = load();

    private final List<TunnelConfig> tunnels = new ArrayList<>();

    private TunnelStore() {
    }

    public static TunnelStore get() {
        return INSTANCE;
    }

    /** Live view of the tunnels (do not mutate directly; use add/remove/replace). */
    public List<TunnelConfig> tunnels() {
        return tunnels;
    }

    public TunnelConfig byId(String id) {
        if (id == null) {
            return null;
        }
        for (TunnelConfig t : tunnels) {
            if (t.getId().equals(id)) {
                return t;
            }
        }
        return null;
    }

    public void add(TunnelConfig tunnel) {
        tunnels.add(tunnel);
    }

    public void remove(TunnelConfig tunnel) {
        tunnels.removeIf(t -> t.getId().equals(tunnel.getId()));
    }

    /** Replaces the stored tunnel with the same id (or appends if not present). */
    public void replace(TunnelConfig tunnel) {
        for (int i = 0; i < tunnels.size(); i++) {
            if (tunnels.get(i).getId().equals(tunnel.getId())) {
                tunnels.set(i, tunnel);
                return;
            }
        }
        tunnels.add(tunnel);
    }

    /** Persist the current tunnels to {@code tunnels.json} (best-effort). */
    public void save() {
        try {
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValue(file().toFile(), tunnels);
        } catch (Exception ignored) {
            // Tunnels are a convenience; a failed write shouldn't break the app.
        }
    }

    private static TunnelStore load() {
        TunnelStore store = new TunnelStore();
        Path file = file();
        if (Files.isRegularFile(file)) {
            try {
                List<TunnelConfig> loaded = new ObjectMapper()
                        .readValue(file.toFile(), new TypeReference<List<TunnelConfig>>() { });
                store.tunnels.addAll(loaded);
            } catch (Exception ignored) {
                // Fall back to an empty list on a malformed file.
            }
        }
        return store;
    }

    private static Path file() {
        return AppPaths.file("tunnels.json");
    }
}
