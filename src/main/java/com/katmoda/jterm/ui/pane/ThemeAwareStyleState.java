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
