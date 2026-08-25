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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application metadata (name, version, author) read once from {@code application.properties}
 * on the classpath. The version placeholder there is filled in by Maven resource filtering at
 * build time, so it always matches the POM. Falls back to sensible defaults if the resource is
 * missing (e.g. running against raw classes without a build).
 *
 * <p>Public because the update check (see {@code com.katmoda.jterm.update}) needs the running
 * version to compare against GitHub's latest release.</p>
 */
public final class AppInfo {

    private static final Logger LOG = LoggerFactory.getLogger(AppInfo.class);

    private static final Properties PROPS = load();

    private AppInfo() {
    }

    public static String name() {
        return PROPS.getProperty("application.name", "jterm");
    }

    public static String version() {
        return PROPS.getProperty("application.version", "(dev)");
    }

    public static String author() {
        return PROPS.getProperty("application.author", "");
    }

    private static Properties load() {
        Properties props = new Properties();
        try (InputStream in = AppInfo.class.getResourceAsStream("/application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            // Fall back to defaults below.
            LOG.debug("could not load application.properties; using defaults", e);
        }
        return props;
    }
}
