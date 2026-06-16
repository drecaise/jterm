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
package com.katmoda.jterm.app;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Application metadata (name, version, author) read once from {@code application.properties}
 * on the classpath. The version placeholder there is filled in by Maven resource filtering at
 * build time, so it always matches the POM. Falls back to sensible defaults if the resource is
 * missing (e.g. running against raw classes without a build).
 */
final class AppInfo {

    private static final Properties PROPS = load();

    private AppInfo() {
    }

    static String name() {
        return PROPS.getProperty("application.name", "jterm");
    }

    static String version() {
        return PROPS.getProperty("application.version", "(dev)");
    }

    static String author() {
        return PROPS.getProperty("application.author", "");
    }

    private static Properties load() {
        Properties props = new Properties();
        try (InputStream in = AppInfo.class.getResourceAsStream("/application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // Fall back to defaults below.
        }
        return props;
    }
}
