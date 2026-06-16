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

/**
 * A single output-highlighting rule: a regular expression whose matches are recolored, plus the
 * foreground color to paint them. The color is stored as a {@code "#RRGGBB"} hex string so the rule
 * round-trips cleanly through JSON (see {@link HighlightList} / {@link HighlightLibrary}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class HighlightRule {

    private String pattern = "";
    private String colorHex = "#FF0000";

    public HighlightRule() {
    }

    public HighlightRule(String pattern, String colorHex) {
        setPattern(pattern);
        setColorHex(colorHex);
    }

    /** The regular expression source matched against each line of new output. */
    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = (pattern != null) ? pattern : "";
    }

    /** Foreground color for matches, as a {@code "#RRGGBB"} hex string. */
    public String getColorHex() {
        return colorHex;
    }

    public void setColorHex(String colorHex) {
        this.colorHex = (colorHex != null && !colorHex.isBlank()) ? colorHex : "#FF0000";
    }

    public HighlightRule copy() {
        return new HighlightRule(pattern, colorHex);
    }
}
