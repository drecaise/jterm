package com.katmoda.jterm.macro;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A named, replayable sequence of {@link MacroStep}s. Optionally bound to a global
 * {@code hotkey} (stored as a {@link javax.swing.KeyStroke#toString()} value, e.g.
 * {@code "shift ctrl F1"}; {@code null} when unbound). Persisted as part of
 * {@code macros.json} (see {@link MacroLibrary}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Macro {

    private String id = UUID.randomUUID().toString();
    private String name = "Macro";
    private String hotkey;
    private List<MacroStep> steps = new ArrayList<>();

    public Macro() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** The bound hotkey as a {@code KeyStroke} string, or {@code null} if unbound. */
    public String getHotkey() {
        return hotkey;
    }

    public void setHotkey(String hotkey) {
        this.hotkey = (hotkey != null && !hotkey.isBlank()) ? hotkey : null;
    }

    public List<MacroStep> getSteps() {
        return steps;
    }

    public void setSteps(List<MacroStep> steps) {
        this.steps = (steps != null) ? steps : new ArrayList<>();
    }

    /** A deep-ish copy for editing (steps are immutable records, so the list copy suffices). */
    public Macro copy() {
        Macro c = new Macro();
        c.id = id;
        c.name = name;
        c.hotkey = hotkey;
        c.steps = new ArrayList<>(steps);
        return c;
    }

    @Override
    public String toString() {
        return name;
    }
}
