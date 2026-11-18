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
package com.katmoda.jterm.terminal.rdp.drive;

import com.katmoda.jterm.config.AppPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Which folders may be shared (design decision D4). jterm's config folder (the vault) and
 * {@code ~/.ssh} (the keys) can never be shared, nor anything inside them; {@link DriveShare} also
 * hides them inside any other share. The home folder itself, a folder above it, and a file-system or
 * drive root may be shared only after the user confirms.
 */
public final class ProtectedFolders {

    public enum Verdict {
        /** Fine to share. */
        OK,
        /** Allowed, but only after the user confirms: it exposes the whole home folder or a whole drive. */
        CONFIRM,
        /** Never: it is, or lies inside, a protected folder. */
        REFUSE
    }

    private ProtectedFolders() {
    }

    /** jterm's config folder and the user's {@code .ssh} folder. */
    public static List<Path> defaults() {
        return List.of(AppPaths.configDir(), Path.of(System.getProperty("user.home"), ".ssh"));
    }

    public static Verdict classify(Path folder) {
        return classify(folder, defaults(), Path.of(System.getProperty("user.home")));
    }

    static Verdict classify(Path folder, List<Path> protectedFolders, Path home) {
        Path real;
        try {
            real = folder.toRealPath();
        } catch (IOException e) {
            return Verdict.OK; // missing: refused as "not a folder" when it is shared
        }
        for (Path p : protectedFolders) {
            for (Path a = real; a != null; a = a.getParent()) {
                if (same(a, p)) {
                    return Verdict.REFUSE;
                }
            }
        }
        if (real.getParent() == null) {
            return Verdict.CONFIRM; // "/" or "C:\"
        }
        try {
            Path realHome = home.toRealPath();
            if (realHome.startsWith(real)) {
                return Verdict.CONFIRM; // the home folder itself, or a folder holding it
            }
        } catch (IOException e) {
            // no home folder: nothing to compare with
        }
        return Verdict.OK;
    }

    private static boolean same(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }
}
