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
package com.katmoda.jterm.icon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.katmoda.jterm.config.AppPaths;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The pickable icon library: a bundled default set (the image resources under {@code /icons})
 * plus user-imported icons (PNG/JPG/GIF/SVG) copied into the icons dir and registered in
 * {@code icons.json}, so an imported icon stays available from the picker after being added once.
 *
 * <p>The bundled set is <em>discovered</em> from the {@code icons/} resources (both from an
 * exploded classes dir and from the shaded jar) so dropping a new image into
 * {@code src/main/resources/icons} makes it pickable without code changes. A few internal chrome
 * icons (window/toolbar/tab glyphs) are excluded.</p>
 */
public final class IconLibrary {

    /** Extensions that can be shown in the picker. */
    private static final Set<String> PICKABLE_EXT = Set.of("svg", "png", "jpg", "jpeg", "gif");

    /** Resource base names used as app chrome, not offered in the picker. */
    private static final Set<String> EXCLUDED = Set.of(
            "app", "folder-plus", "terminal-plus", "terminal-light", "terminal-dark");

    /** Used only if resource discovery fails, so the picker is never empty. */
    private static final String[] FALLBACK = {
            "folder", "folder-open", "server", "terminal", "database", "globe", "computer"
    };

    private static final IconLibrary INSTANCE = new IconLibrary();

    private final Path file = AppPaths.file("icons.json");
    private final Path dir = AppPaths.iconsDir();
    private final List<ImportedIcon> imported = new ArrayList<>();
    private final Map<String, Icon> cache = new LinkedHashMap<>();
    /** Built-in basename (e.g. {@code "alma-linux"}) → resource file name (e.g. {@code "alma-linux.svg"}). */
    private final Map<String, String> builtins = discoverBuiltins();

    private IconLibrary() {
        loadImported();
    }

    public static IconLibrary get() {
        return INSTANCE;
    }

    /** A choice shown in the picker. */
    public record Choice(String id, String displayName) {
    }

    /** All pickable icons: built-ins first (alphabetical), then imported. */
    public List<Choice> choices() {
        List<Choice> all = new ArrayList<>();
        for (String name : builtins.keySet()) {
            all.add(new Choice(builtinId(name), prettyName(name)));
        }
        for (ImportedIcon ii : imported) {
            all.add(new Choice(ii.getId(), ii.getDisplayName()));
        }
        return all;
    }

    /** Resolve an icon id to a sized {@link Icon}, or {@code null} if unknown/unset. */
    public Icon icon(String iconId, int size) {
        if (iconId == null || iconId.isBlank()) {
            return null;
        }
        String key = iconId + "@" + size;
        Icon cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Icon icon = load(iconId, size);
        if (icon != null) {
            cache.put(key, icon);
        }
        return icon;
    }

    private Icon load(String iconId, int size) {
        if (iconId.startsWith("builtin/")) {
            String name = iconId.substring("builtin/".length());
            // Fall back to the historical "<name>.svg" mapping for ids saved before discovery.
            String fileName = builtins.getOrDefault(name, name + ".svg");
            return loadResource(fileName, size);
        }
        for (ImportedIcon ii : imported) {
            if (ii.getId().equals(iconId)) {
                return loadImported(ii, size);
            }
        }
        return null;
    }

    /** Loads a bundled resource by file name: SVGs through FlatLaf, rasters scaled via ImageIcon. */
    private Icon loadResource(String fileName, int size) {
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".svg")) {
            FlatSVGIcon svg = new FlatSVGIcon("icons/" + fileName);
            int[] d = fit(svg.getIconWidth(), svg.getIconHeight(), size);
            return svg.derive(d[0], d[1]);
        }
        URL url = IconLibrary.class.getResource("/icons/" + fileName);
        if (url == null) {
            return null;
        }
        return scaledRaster(new ImageIcon(url), size);
    }

    private Icon loadImported(ImportedIcon ii, int size) {
        Path path = dir.resolve(ii.getFileName());
        if (!Files.isRegularFile(path)) {
            return null;
        }
        if (ii.getFileName().toLowerCase(Locale.ROOT).endsWith(".svg")) {
            FlatSVGIcon svg = new FlatSVGIcon(path.toFile());
            int[] d = fit(svg.getIconWidth(), svg.getIconHeight(), size);
            return svg.derive(d[0], d[1]);
        }
        return scaledRaster(new ImageIcon(path.toString()), size);
    }

    /** Scales a raster icon to fit within {@code size}×{@code size}, preserving its aspect ratio. */
    private static Icon scaledRaster(ImageIcon raw, int size) {
        int[] d = fit(raw.getIconWidth(), raw.getIconHeight(), size);
        return new ImageIcon(raw.getImage().getScaledInstance(d[0], d[1], Image.SCALE_SMOOTH));
    }

    /** Scales (w,h) down to fit a {@code box}×{@code box} square without distorting aspect ratio. */
    private static int[] fit(int w, int h, int box) {
        if (w <= 0 || h <= 0) {
            return new int[]{box, box};
        }
        double scale = Math.min((double) box / w, (double) box / h);
        return new int[]{Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale))};
    }

    /**
     * Imports an icon file: validates the extension, copies it into the icons dir,
     * registers it and persists {@code icons.json}. Returns the new icon id, or
     * {@code null} if the extension is unsupported / the copy failed.
     */
    public String importFile(Path source) {
        String fileName = source.getFileName().toString();
        String ext = extension(fileName);
        if (!List.of("png", "jpg", "jpeg", "gif", "svg").contains(ext)) {
            return null;
        }
        try {
            String stored = UUID.randomUUID() + "." + ext;
            Files.copy(source, dir.resolve(stored), StandardCopyOption.REPLACE_EXISTING);
            String id = "custom/" + stored;
            imported.add(new ImportedIcon(id, fileName, stored));
            save();
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether {@code iconId} refers to a user-imported (custom) icon (vs. a built-in). */
    public static boolean isCustom(String iconId) {
        return iconId != null && iconId.startsWith("custom/");
    }

    /**
     * Deletes a user-imported (custom) icon: removes its file, drops its registration and any
     * cached renderings, and persists {@code icons.json}. No-op for built-in / unknown ids.
     * Callers are responsible for reverting any sessions/folders that referenced the id.
     *
     * @return {@code true} if a custom icon was removed
     */
    public boolean deleteImported(String iconId) {
        if (!isCustom(iconId)) {
            return false;
        }
        ImportedIcon target = null;
        for (ImportedIcon ii : imported) {
            if (ii.getId().equals(iconId)) {
                target = ii;
                break;
            }
        }
        if (target == null) {
            return false;
        }
        try {
            Files.deleteIfExists(dir.resolve(target.getFileName()));
        } catch (Exception ignored) {
            // Leave the orphaned file; the registration is what matters for the picker.
        }
        imported.remove(target);
        cache.keySet().removeIf(key -> key.startsWith(iconId + "@"));
        save();
        return true;
    }

    // ---- built-in discovery ----

    /**
     * Lists the image files under the {@code icons/} resources. Works whether the app runs from
     * an exploded classes directory or from the shaded jar (resolved via the code source).
     */
    private static Map<String, String> discoverBuiltins() {
        Map<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try {
            URL location = IconLibrary.class.getProtectionDomain().getCodeSource().getLocation();
            Path root = Path.of(location.toURI());
            if (Files.isDirectory(root)) {
                Path iconsDir = root.resolve("icons");
                if (Files.isDirectory(iconsDir)) {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(iconsDir)) {
                        for (Path f : ds) {
                            if (Files.isRegularFile(f)) {
                                addBuiltin(map, f.getFileName().toString());
                            }
                        }
                    }
                }
            } else {
                try (ZipFile zip = new ZipFile(root.toFile())) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String name = entry.getName();
                        // Direct children of icons/ only (no nested dirs).
                        if (!entry.isDirectory() && name.startsWith("icons/")
                                && name.indexOf('/', "icons/".length()) < 0) {
                            addBuiltin(map, name.substring("icons/".length()));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall back below if anything about resource discovery goes wrong.
        }
        if (map.isEmpty()) {
            for (String name : FALLBACK) {
                addBuiltin(map, name + ".svg");
            }
        }
        return map;
    }

    private static void addBuiltin(Map<String, String> map, String fileName) {
        String ext = extension(fileName);
        if (!PICKABLE_EXT.contains(ext)) {
            return;
        }
        String base = fileName.substring(0, fileName.length() - ext.length() - 1);
        if (EXCLUDED.contains(base)) {
            return;
        }
        map.putIfAbsent(base, fileName);
    }

    private void loadImported() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<ImportedIcon> list = mapper.readValue(file.toFile(),
                    mapper.getTypeFactory().constructCollectionType(ArrayList.class, ImportedIcon.class));
            imported.addAll(list);
        } catch (Exception ignored) {
        }
    }

    private void save() {
        try {
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValue(file.toFile(), imported);
        } catch (Exception ignored) {
        }
    }

    private static String builtinId(String name) {
        return "builtin/" + name;
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String prettyName(String name) {
        String cleaned = name.replace('-', ' ');
        return Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
    }
}
