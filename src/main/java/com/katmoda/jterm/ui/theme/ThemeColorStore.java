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
package com.katmoda.jterm.ui.theme;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.katmoda.jterm.config.AppPaths;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The user's per-scheme palette customizations, persisted as {@code colors.json} in the config
 * dir. The built-in {@link ThemeColors#DARK}/{@link ThemeColors#LIGHT} presets stay the
 * authoritative defaults; this store only holds the slots the user has actually changed (a sparse
 * override). {@link #effective(boolean)} layers the overrides over the matching preset to produce
 * the live {@link ThemeColors}.
 *
 * <p>Storing only the differences (rather than a full copied palette) means untouched slots keep
 * tracking the built-in defaults across app updates, and a fresh install writes no file at all
 * until something is customized. A malformed file degrades silently to the built-in presets.</p>
 */
public final class ThemeColorStore {

    private static final int ANSI_COUNT = 16;

    private static final ThemeColorStore INSTANCE = load();

    private SchemeOverride dark;
    private SchemeOverride light;

    private ThemeColorStore() {
    }

    public static ThemeColorStore get() {
        return INSTANCE;
    }

    /** The effective colors for a scheme: the built-in preset with any user overrides applied. */
    public ThemeColors effective(boolean dark) {
        ThemeColors base = dark ? ThemeColors.DARK : ThemeColors.LIGHT;
        SchemeOverride o = dark ? this.dark : this.light;
        if (o == null) {
            o = SchemeOverride.EMPTY;
        }
        // Always build a fresh array — never hand back (or mutate) the preset's shared ansi[].
        Color[] ansi = base.ansi().clone();
        String[] ansiHex = o.ansi();
        if (ansiHex != null) {
            for (int i = 0; i < ansi.length && i < ansiHex.length; i++) {
                Color c = decodeOrNull(ansiHex[i]);
                if (c != null) {
                    ansi[i] = c;
                }
            }
        }
        return new ThemeColors(
                base.dark(),
                or(o.foreground(), base.foreground()),
                or(o.background(), base.background()),
                or(o.selectionFg(), base.selectionFg()),
                or(o.selectionBg(), base.selectionBg()),
                ansi);
    }

    /** Stores {@code edited} as a sparse override (only slots differing from the preset), then saves. */
    public void setScheme(boolean dark, ThemeColors edited) {
        ThemeColors base = dark ? ThemeColors.DARK : ThemeColors.LIGHT;
        SchemeOverride o = diff(base, edited);
        if (dark) {
            this.dark = o.isEmpty() ? null : o;
        } else {
            this.light = o.isEmpty() ? null : o;
        }
        save();
    }

    /** Drops all overrides for a scheme, restoring its built-in preset, then saves. */
    public void reset(boolean dark) {
        if (dark) {
            this.dark = null;
        } else {
            this.light = null;
        }
        save();
    }

    /** Whether a scheme has any user overrides (drives the editor's "modified" affordances). */
    public boolean isCustomized(boolean dark) {
        SchemeOverride o = dark ? this.dark : this.light;
        return o != null && !o.isEmpty();
    }

    private static SchemeOverride diff(ThemeColors base, ThemeColors edited) {
        String[] ansi = null;
        for (int i = 0; i < ANSI_COUNT; i++) {
            if (!sameRgb(base.ansi()[i], edited.ansi()[i])) {
                if (ansi == null) {
                    ansi = new String[ANSI_COUNT];
                }
                ansi[i] = hex(edited.ansi()[i]);
            }
        }
        return new SchemeOverride(
                diffColor(base.foreground(), edited.foreground()),
                diffColor(base.background(), edited.background()),
                diffColor(base.selectionFg(), edited.selectionFg()),
                diffColor(base.selectionBg(), edited.selectionBg()),
                ansi);
    }

    private static String diffColor(Color base, Color edited) {
        return sameRgb(base, edited) ? null : hex(edited);
    }

    private static Color or(String hex, Color fallback) {
        Color c = decodeOrNull(hex);
        return c != null ? c : fallback;
    }

    private static boolean sameRgb(Color a, Color b) {
        return (a.getRGB() & 0xFFFFFF) == (b.getRGB() & 0xFFFFFF);
    }

    private static String hex(Color c) {
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }

    private static Color decodeOrNull(String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        try {
            return Color.decode(hex.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void save() {
        try {
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValue(file().toFile(), new Persisted(dark, light));
        } catch (Exception ignored) {
            // Color customizations are a convenience; a failed write shouldn't break the app.
        }
    }

    private static ThemeColorStore load() {
        ThemeColorStore store = new ThemeColorStore();
        Path file = file();
        if (Files.isRegularFile(file)) {
            try {
                Persisted p = new ObjectMapper().readValue(file.toFile(), Persisted.class);
                store.dark = nullIfEmpty(p.dark);
                store.light = nullIfEmpty(p.light);
            } catch (Exception ignored) {
                // Fall back to the built-in presets on a malformed file.
            }
        }
        return store;
    }

    private static SchemeOverride nullIfEmpty(SchemeOverride o) {
        return (o == null || o.isEmpty()) ? null : o;
    }

    private static Path file() {
        return AppPaths.file("colors.json");
    }

    /**
     * One scheme's overrides. Each field is a {@code "#RRGGBB"} string, or {@code null} to inherit
     * the built-in preset's value; {@code ansi} is a 16-slot array with the same null-means-inherit
     * rule per entry.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SchemeOverride(String foreground, String background,
                                 String selectionFg, String selectionBg,
                                 String[] ansi) {

        static final SchemeOverride EMPTY = new SchemeOverride(null, null, null, null, null);

        boolean isEmpty() {
            if (foreground != null || background != null || selectionFg != null || selectionBg != null) {
                return false;
            }
            if (ansi != null) {
                for (String a : ansi) {
                    if (a != null) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    /** On-disk shape (a wrapper object leaves room for a future schema/version field). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Persisted(SchemeOverride dark, SchemeOverride light) {
    }
}
