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
