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
