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
