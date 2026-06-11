package com.katmoda.jterm.keymap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.katmoda.jterm.config.AppPaths;

import javax.swing.KeyStroke;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configurable action-to-{@link KeyStroke} bindings, persisted as {@code keymap.json}.
 *
 * <p>Phase 1 ships defaults baked into {@link TermAction}; the file is read on startup
 * (and written with the defaults if absent) so users can already customize bindings by
 * editing JSON. A dedicated settings UI is a later concern.</p>
 */
public final class Keymap {

    private final Map<TermAction, KeyStroke> bindings = new EnumMap<>(TermAction.class);

    private Keymap() {
    }

    public static Keymap loadOrDefaults() {
        Keymap keymap = new Keymap();
        Path file = AppPaths.file("keymap.json");
        Map<String, String> raw = new LinkedHashMap<>();

        if (Files.isRegularFile(file)) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                raw = mapper.readValue(file.toFile(), mapper.getTypeFactory()
                        .constructMapType(LinkedHashMap.class, String.class, String.class));
            } catch (Exception ignored) {
                // Fall back to defaults on a malformed file.
            }
        }

        for (TermAction action : TermAction.values()) {
            String stroke = raw.getOrDefault(action.id(), action.defaultStroke());
            KeyStroke ks = KeyStroke.getKeyStroke(stroke);
            if (ks == null) {
                ks = KeyStroke.getKeyStroke(action.defaultStroke());
            }
            keymap.bindings.put(action, ks);
        }

        if (!Files.isRegularFile(file)) {
            keymap.writeDefaults(file);
        }
        return keymap;
    }

    /** Resolve the action bound to a key event, or {@code null}. */
    public TermAction actionFor(KeyStroke stroke) {
        for (Map.Entry<TermAction, KeyStroke> e : bindings.entrySet()) {
            if (e.getValue().equals(stroke)) {
                return e.getKey();
            }
        }
        return null;
    }

    public KeyStroke strokeFor(TermAction action) {
        return bindings.get(action);
    }

    /** A snapshot of all current bindings, for the shortcut editor. */
    public Map<TermAction, KeyStroke> bindings() {
        return new EnumMap<>(bindings);
    }

    /** Rebind a single action (in memory; call {@link #save()} to persist). */
    public void rebind(TermAction action, KeyStroke stroke) {
        if (stroke != null) {
            bindings.put(action, stroke);
        }
    }

    /** Restore every action to its compiled-in default (in memory). */
    public void resetDefaults() {
        for (TermAction action : TermAction.values()) {
            bindings.put(action, KeyStroke.getKeyStroke(action.defaultStroke()));
        }
    }

    /** Persist the current bindings to {@code keymap.json}. */
    public void save() {
        try {
            Map<String, String> out = new LinkedHashMap<>();
            for (TermAction action : TermAction.values()) {
                KeyStroke ks = bindings.get(action);
                out.put(action.id(), ks != null ? ks.toString() : action.defaultStroke());
            }
            new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValue(AppPaths.file("keymap.json").toFile(), out);
        } catch (Exception ignored) {
        }
    }

    private void writeDefaults(Path file) {
        try {
            Map<String, String> out = new LinkedHashMap<>();
            for (TermAction action : TermAction.values()) {
                out.put(action.id(), action.defaultStroke());
            }
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), out);
        } catch (Exception ignored) {
        }
    }
}
