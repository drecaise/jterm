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
package com.katmoda.jterm.ui.component;

import com.katmoda.jterm.highlight.HighlightList;
import com.katmoda.jterm.highlight.HighlightListResolver;

import javax.swing.JComboBox;
import java.util.List;

/**
 * Builds the highlight-list drop-downs used in two places, sharing the same option type:
 *
 * <ul>
 *   <li>{@link #global} — the Preferences default selector: <i>(None)</i> plus every list.</li>
 *   <li>{@link #perSession} — the session-dialog override: <i>Default</i> (inherit the global
 *       default), <i>(None)</i>, then every list.</li>
 * </ul>
 *
 * The selected option's {@link Option#id()} is the value to store (see
 * {@link HighlightListResolver} for how those ids resolve).
 */
public final class HighlightListCombo {

    /** A choice in the combo. {@code id} is the stored value; {@code label} is what's shown. */
    public record Option(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private HighlightListCombo() {
    }

    /** Global default selector: {@code (None)} + each list; {@code currentId} null means none. */
    public static JComboBox<Option> global(String currentId, List<HighlightList> lists) {
        JComboBox<Option> combo = new JComboBox<>();
        rebuildGlobal(combo, currentId, lists);
        return combo;
    }

    /** Refills an existing global selector in place (used to track edits to the lists). */
    public static void rebuildGlobal(JComboBox<Option> combo, String currentId, List<HighlightList> lists) {
        combo.removeAllItems();
        Option none = new Option(null, "(None)");
        combo.addItem(none);
        combo.setSelectedItem(none);
        for (HighlightList list : lists) {
            Option option = new Option(list.getId(), list.getName());
            combo.addItem(option);
            if (list.getId().equals(currentId)) {
                combo.setSelectedItem(option);
            }
        }
    }

    /**
     * Per-session override selector. {@code currentId}: null selects "Default" (inherit the global
     * default); {@link HighlightListResolver#NONE} selects "(None)"; any other value selects that list.
     */
    public static JComboBox<Option> perSession(String currentId, List<HighlightList> lists) {
        JComboBox<Option> combo = new JComboBox<>();
        Option inherit = new Option(null, "Default");
        Option none = new Option(HighlightListResolver.NONE, "(None)");
        combo.addItem(inherit);
        combo.addItem(none);
        combo.setSelectedItem(inherit);
        if (HighlightListResolver.NONE.equals(currentId)) {
            combo.setSelectedItem(none);
        }
        for (HighlightList list : lists) {
            Option option = new Option(list.getId(), list.getName());
            combo.addItem(option);
            if (list.getId().equals(currentId)) {
                combo.setSelectedItem(option);
            }
        }
        return combo;
    }

    /** The id to store for the combo's current selection, or {@code null}. */
    public static String selectedId(JComboBox<Option> combo) {
        Option option = (Option) combo.getSelectedItem();
        return option != null ? option.id() : null;
    }
}
