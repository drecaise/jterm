package com.katmoda.jterm.ui.pane;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.model.StyleState;
import com.katmoda.jterm.ui.theme.JTermSettingsProvider;

/**
 * A {@link StyleState} whose default foreground/background come from the (mutable) settings
 * provider rather than the stored default {@code TextStyle}. The provider hands JediTerm a
 * color-less default style ({@code TextStyle.EMPTY}) so default-pen cells recolor live; that
 * leaves {@code StyleState.getDefault*} — used to resolve inverse-video cells — returning the
 * style's null colors and throwing. Overriding them to read the provider keeps inverse cells
 * working and theme-aware.
 */
final class ThemeAwareStyleState extends StyleState {

    private final JTermSettingsProvider provider;

    ThemeAwareStyleState(JTermSettingsProvider provider) {
        this.provider = provider;
    }

    @Override
    public TerminalColor getDefaultForeground() {
        return provider.getDefaultForeground();
    }

    @Override
    public TerminalColor getDefaultBackground() {
        return provider.getDefaultBackground();
    }
}
