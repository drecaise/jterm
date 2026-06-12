package com.katmoda.jterm.highlight;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.katmoda.jterm.config.AppPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The user's named highlight lists, persisted as {@code highlights.json} in the config dir. A small
 * mutable singleton (mirroring {@link com.katmoda.jterm.macro.MacroLibrary}) read by the Preferences
 * highlighting editor and resolved per-pane at terminal creation.
 *
 * <p>On first run (no file present) a built-in <b>Default</b> list is seeded with common
 * error/warn/success keywords so the feature is useful out of the box.</p>
 */
public final class HighlightLibrary {

    /** Stable id of the seeded "Default" list, so a fresh install can mark it the global default. */
    public static final String DEFAULT_LIST_ID = "default";

    private static final HighlightLibrary INSTANCE = load();

    private final List<HighlightList> lists = new ArrayList<>();

    private HighlightLibrary() {
    }

    public static HighlightLibrary get() {
        return INSTANCE;
    }

    /** Live view of the lists (do not mutate directly; use add/remove/replace). */
    public List<HighlightList> lists() {
        return lists;
    }

    public HighlightList byId(String id) {
        if (id == null) {
            return null;
        }
        for (HighlightList l : lists) {
            if (l.getId().equals(id)) {
                return l;
            }
        }
        return null;
    }

    public void add(HighlightList list) {
        lists.add(list);
    }

    public void remove(HighlightList list) {
        lists.removeIf(l -> l.getId().equals(list.getId()));
    }

    /** Replaces the stored list with the same id (or appends if not present). */
    public void replace(HighlightList list) {
        for (int i = 0; i < lists.size(); i++) {
            if (lists.get(i).getId().equals(list.getId())) {
                lists.set(i, list);
                return;
            }
        }
        lists.add(list);
    }

    /** Replaces the entire set of lists (used when committing the Preferences editor). */
    public void replaceAll(List<HighlightList> newLists) {
        lists.clear();
        if (newLists != null) {
            lists.addAll(newLists);
        }
    }

    /** Persist the current lists to {@code highlights.json} (best-effort). */
    public void save() {
        try {
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                    .writeValue(file().toFile(), new Persisted(new ArrayList<>(lists)));
        } catch (Exception ignored) {
            // Highlights are a convenience; a failed write shouldn't break the app.
        }
    }

    private static HighlightLibrary load() {
        HighlightLibrary library = new HighlightLibrary();
        Path file = file();
        if (Files.isRegularFile(file)) {
            try {
                Persisted p = new ObjectMapper().readValue(file.toFile(), Persisted.class);
                if (p.lists != null) {
                    library.lists.addAll(p.lists);
                }
            } catch (Exception ignored) {
                // Fall back to an empty set on a malformed file.
            }
            return library;
        }
        // First run: seed the Default list and write it out.
        library.lists.add(defaultList());
        library.save();
        return library;
    }

    /**
     * The built-in "Standard" list seeded on first run (stable {@link #DEFAULT_LIST_ID}). Derived
     * from MobaXterm's syntax-highlighting presets, translated to Java regex (the leading-{@code ]}
     * character classes escaped) with case-insensitive matching and word boundaries expressed as
     * lookarounds so a keyword highlights even at the start of a line and only the keyword is colored.
     */
    private static HighlightList defaultList() {
        HighlightList list = new HighlightList("Standard");
        list.setId(DEFAULT_LIST_ID);
        list.getRules().add(new HighlightRule("(?i)((?<![A-Za-z_&-])((bad|wrong|incorrect|improper|invalid|unsupported|bad)( file| memory)? (descriptor|alloc(ation)?|addr(ess)?|owner(ship)?|arg(ument)?|param(eter)?|setting|length|filename)|not properly|improperly|(operation |connection |authentication |access |permission )?(denied|disallowed|not allowed|refused|problem|failed|failure|not permitted)|no [A-Za-z]+( [A-Za-z]+)? found|invalid|unsupported|not supported|seg(mentation )?fault|corruption|corrupted|corrupt|overflow|underrun|not ok|unimplemented|unsuccessfull|not implemented|permerrors?|fehlers?|errore|errors?|erreurs?|fejl|virhe|greška|erro|crash|crashed|core dump|fel|\\(ee\\)|\\(ni\\))(?![A-Za-z_-])|[=>\"':.,;({\\[][ ]*(false|no|ko)[ ]*[\\]=>\"':.,;)} ])", "#E01B24"));
        list.getRules().add(new HighlightRule("(?i)((?<![A-Za-z_&-])(accepted|allowed|enabled|connected|erfolgreich|exitoso|successo|sucedido|framgångsrik|successfully|successful|succeeded|success)(?![A-Za-z_-])|[=>\"':.,;({\\[][ ]*(true|yes|ok)[ ]*[\\]=>\"':.,;)} ])", "#2EC27E"));
        list.getRules().add(new HighlightRule("(?i)(?<![A-Za-z_&-])(\\[\\-w[A-Za-z-]+\\]|caught signal [0-9]+|cannot|(connection (to (remote host|[a-z0-9.]+) )?)?(closed|terminated|stopped|not responding)|exited|no more [A-Za-z] available|unexpected|(command |binary |file )?not found|ooo?o?o?ps|out of (space|memory)|low (memory|disk)|unknown|disabled|disconnected|deprecated|refused|disconnect(ion)?|advertencia|avvertimento|attention|warnings?|achtung|exclamation|alerts?|warnungs?|advarsel|pedwarn|aviso|varoitus|upozorenje|peringatan|uyari|varning|avertissement|\\(ww\\)|\\(\\?\\?\\)|could not|unable to)(?![A-Za-z_-])", "#E5A50A"));
        list.getRules().add(new HighlightRule("(?i)(?<![0-9A-Za-z_&-])(localhost|([1-9]|[1-9][0-9]|1[0-9][0-9]|2[0-4][0-9]|25[0-4])\\.[0-9]+\\.[0-9]+\\.[0-9]+|null|none)(?![0-9A-Za-z_-])", "#C061CB"));
        list.getRules().add(new HighlightRule("(?i)(?<![A-Za-z_&-])(last (failed )?login:|launching|checking|loading|creating|building|important|booting|starting|notice|informational|informationen|informazioni|informação|oplysninger|informations?|info|información|informasi|note|\\(ii\\)|\\(\\!\\!\\))(?![A-Za-z_-])", "#33C7DE"));
        return list;
    }

    private static Path file() {
        return AppPaths.file("highlights.json");
    }

    /** On-disk shape (a wrapper object leaves room for a future schema/version field). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Persisted(List<HighlightList> lists) {
    }
}
