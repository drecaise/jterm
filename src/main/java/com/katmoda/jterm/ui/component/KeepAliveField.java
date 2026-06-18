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

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.Component;
import java.awt.FlowLayout;

/**
 * A three-state keep-alive picker for the session and folder dialogs: a dropdown
 * (Inherit / Disabled / Enabled) plus an interval spinner enabled only when "Enabled" is chosen.
 *
 * <p>The tri-state maps to a boxed value: {@link #value()} returns {@code null} for "Inherit",
 * {@code 0} for "Disabled", or the spinner's positive interval for "Enabled" — matching the
 * {@code null = inherit / 0 = off / > 0 = on} convention on {@code SshSessionConfig} and
 * {@code FolderNode}. The "Inherit" row shows what the value would resolve to (e.g.
 * {@code "Inherit (300s)"}), updatable via {@link #setInheritedHint(int)} when the resolved
 * default changes (the session dialog re-resolves it as the destination folder changes).</p>
 */
public final class KeepAliveField {

    private enum Mode { INHERIT, DISABLED, ENABLED }

    private static final int DEFAULT_INTERVAL = 300;

    private final JPanel panel;
    private final JComboBox<Mode> mode = new JComboBox<>(Mode.values());
    private final JSpinner interval =
            new JSpinner(new SpinnerNumberModel(DEFAULT_INTERVAL, 30, 86400, 30));
    private int inheritedResolved;

    /**
     * @param value             the stored value: {@code null} = inherit, {@code 0} = off,
     *                          {@code > 0} = on at that interval
     * @param inheritedResolved the value "Inherit" would resolve to (seconds; {@code 0} = off),
     *                          shown in the dropdown's Inherit row
     */
    public KeepAliveField(Integer value, int inheritedResolved) {
        this.inheritedResolved = Math.max(0, inheritedResolved);
        mode.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object v, int index,
                    boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, v, index, selected, focused);
                setText(label((Mode) v));
                return this;
            }
        });

        if (value == null) {
            mode.setSelectedItem(Mode.INHERIT);
        } else if (value <= 0) {
            mode.setSelectedItem(Mode.DISABLED);
        } else {
            mode.setSelectedItem(Mode.ENABLED);
            interval.setValue(value);
        }
        Runnable sync = () -> interval.setEnabled(mode.getSelectedItem() == Mode.ENABLED);
        mode.addActionListener(a -> sync.run());
        sync.run();

        this.panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        panel.add(mode);
        panel.add(interval);
    }

    /** The Swing component to drop into a form row. */
    public JPanel component() {
        return panel;
    }

    /** Updates the resolved value shown on the "Inherit" row (e.g. when the parent folder changes). */
    public void setInheritedHint(int resolved) {
        this.inheritedResolved = Math.max(0, resolved);
        mode.repaint();
    }

    /** The chosen value: {@code null} = inherit, {@code 0} = off, {@code > 0} = on at that interval. */
    public Integer value() {
        return switch ((Mode) mode.getSelectedItem()) {
            case INHERIT -> null;
            case DISABLED -> 0;
            case ENABLED -> (Integer) interval.getValue();
        };
    }

    private String label(Mode m) {
        return switch (m) {
            case INHERIT -> "Inherit (" + describe(inheritedResolved) + ")";
            case DISABLED -> "Disabled";
            case ENABLED -> "Enabled";
        };
    }

    private static String describe(int seconds) {
        return seconds > 0 ? seconds + "s" : "off";
    }
}
