package com.katmoda.jterm.highlight;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A named collection of {@link HighlightRule}s. A terminal uses exactly one list at a time (the
 * global default, or a per-session override). References to a list store its {@link #id} (stable
 * across renames), not its display name.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class HighlightList {

    private String id = UUID.randomUUID().toString();
    private String name = "Highlights";
    private List<HighlightRule> rules = new ArrayList<>();

    public HighlightList() {
    }

    public HighlightList(String name) {
        this.name = name;
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
        this.name = (name != null && !name.isBlank()) ? name : "Highlights";
    }

    public List<HighlightRule> getRules() {
        return rules;
    }

    public void setRules(List<HighlightRule> rules) {
        this.rules = (rules != null) ? rules : new ArrayList<>();
    }

    /** A deep copy for editing in a dialog without mutating the stored instance. */
    public HighlightList copy() {
        HighlightList c = new HighlightList();
        c.id = id;
        c.name = name;
        c.rules = new ArrayList<>();
        for (HighlightRule r : rules) {
            c.rules.add(r.copy());
        }
        return c;
    }

    @Override
    public String toString() {
        return name;
    }
}
