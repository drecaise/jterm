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
package com.katmoda.jterm.broadcast;

import com.jediterm.terminal.TtyConnector;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link BroadcastBus} implementation for one grid: owns the broadcast on/off state and the set
 * of {@link BroadcastTarget}s currently occupying its cells, and fans a source pane's keystrokes out
 * to the others. The grid {@link #register}s/{@link #unregister}s panes as cells fill and empty and
 * delegates its {@code BroadcastBus} interface method here, keeping input routing off the layout
 * container.
 */
public final class PaneBroadcastBus implements BroadcastBus {

    private static final Logger LOG = LoggerFactory.getLogger(PaneBroadcastBus.class);

    private final List<BroadcastTarget> targets = new ArrayList<>();
    private boolean active = false;

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /** Add a pane to the fan-out set (idempotent, so re-homing a moved pane is safe). */
    public void register(BroadcastTarget target) {
        if (target != null && !targets.contains(target)) {
            targets.add(target);
        }
    }

    /** Remove a pane from the fan-out set when its cell is emptied or the pane leaves this grid. */
    public void unregister(BroadcastTarget target) {
        targets.remove(target);
    }

    @Override
    public void broadcast(TtyConnector source, byte[] data) {
        if (!active) {
            return;
        }
        // An excluded (unchecked) source pane keeps its own input local — don't fan it out.
        BroadcastTarget sourceTarget = targetFor(source);
        if (sourceTarget != null && !sourceTarget.isBroadcastChecked()) {
            return;
        }
        for (BroadcastTarget target : targets) {
            if (target.realConnector() != source && target.isBroadcastChecked()) {
                try {
                    target.realConnector().write(data);
                } catch (Exception e) {
                    // A dead pane shouldn't break the fan-out to the others.
                    LOG.debug("broadcast write to a pane failed", e);
                }
            }
        }
    }

    /** The registered target whose real (unwrapped) connector is {@code connector}, or {@code null}. */
    private BroadcastTarget targetFor(TtyConnector connector) {
        for (BroadcastTarget target : targets) {
            if (target.realConnector() == connector) {
                return target;
            }
        }
        return null;
    }
}
