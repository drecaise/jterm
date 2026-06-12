package com.katmoda.jterm.highlight;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * An immutable, compiled snapshot of a {@link HighlightList}: each rule's regex is pre-compiled and
 * paired with a JediTerm {@link TextStyle} carrying only a foreground color. Resolved once per pane
 * at creation, so later edits to the source list don't retroactively affect open terminals.
 *
 * <p>Compilation is total: a rule with an invalid regex or unparseable color is skipped, the rest
 * still apply — bad input never throws.</p>
 */
public final class CompiledHighlightList {

    /** A compiled rule: a regex and the foreground style to apply to its matches. */
    public static final class CompiledRule {
        private final Pattern pattern;
        private final TextStyle style;

        CompiledRule(Pattern pattern, TextStyle style) {
            this.pattern = pattern;
            this.style = style;
        }

        public Pattern pattern() {
            return pattern;
        }

        public TextStyle style() {
            return style;
        }
    }

    private final List<CompiledRule> rules;

    private CompiledHighlightList(List<CompiledRule> rules) {
        this.rules = rules;
    }

    public static CompiledHighlightList compile(HighlightList list) {
        List<CompiledRule> compiled = new ArrayList<>();
        if (list != null) {
            for (HighlightRule rule : list.getRules()) {
                CompiledRule c = compileRule(rule);
                if (c != null) {
                    compiled.add(c);
                }
            }
        }
        return new CompiledHighlightList(compiled);
    }

    private static CompiledRule compileRule(HighlightRule rule) {
        if (rule.getPattern() == null || rule.getPattern().isEmpty()) {
            return null;
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(rule.getPattern());
        } catch (PatternSyntaxException e) {
            System.err.println("[highlight] skipping invalid regex: " + rule.getPattern());
            return null;
        }
        Color color;
        try {
            color = Color.decode(rule.getColorHex());
        } catch (NumberFormatException e) {
            System.err.println("[highlight] skipping invalid color: " + rule.getColorHex());
            return null;
        }
        TerminalColor fg = TerminalColor.rgb(color.getRed(), color.getGreen(), color.getBlue());
        return new CompiledRule(pattern, new TextStyle(fg, null));
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public List<CompiledRule> rules() {
        return rules;
    }
}
