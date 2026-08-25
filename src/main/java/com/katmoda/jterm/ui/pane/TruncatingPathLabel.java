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

import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.util.List;

/**
 * The pane title bar's label. Shows as much of a working directory as fits, dropping leading path
 * components and prefixing {@code "..."} when it does not — {@code /a/very/long/path/to/some/cwd}
 * becomes {@code .../to/some/cwd} — and re-deciding whenever the pane is resized.
 *
 * <p>Adds no mouse or key handling, so the two listeners the pane attaches to this label (the
 * right-click pane menu and the pane-move drag handle) are unaffected.</p>
 */
final class TruncatingPathLabel extends JLabel {

    /** Renderings from widest to narrowest; the first that fits is shown. */
    private List<String> candidates;
    private String fullText = "";

    TruncatingPathLabel(String text, Icon icon) {
        super(text, icon, SwingConstants.LEADING);
        // A working directory is remote-controlled text and both JLabel and JTabbedPane render a
        // string opening with <html> as markup. PaneTitle defuses that in the string as well; this
        // is the structural half.
        putClientProperty("html.disable", Boolean.TRUE);
    }

    /** Sets what this label should show, longest form first. The tooltip always carries it in full. */
    void setPaneLabel(PaneTitle.PaneLabel label) {
        String full = label.full();
        if (full.equals(fullText) && candidates != null) {
            return;
        }
        fullText = full;
        candidates = PaneTitle.truncationCandidates(label);
        setToolTipText(full);
        retruncate();
    }

    /** The label as it would read untruncated; the pane uses it for the save-output file name. */
    String fullText() {
        return fullText;
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        boolean widthChanged = width != getWidth();
        super.setBounds(x, y, width, height);
        if (widthChanged) {
            retruncate();
        }
    }

    @Override
    public void setFont(Font font) {
        super.setFont(font);
        retruncate();
    }

    /**
     * Reports zero width (the height still comes from the icon and font). In the title bar's
     * {@code BorderLayout.CENTER} slot the label is handed the leftover width regardless, so nothing
     * is lost visually — but this stops a long path from pinning the whole pane's minimum width, and
     * it makes the {@code setText} inside {@link #setBounds} non-reentrant, since the text can no
     * longer feed back into the size the layout just used.
     */
    @Override
    public Dimension getPreferredSize() {
        return new Dimension(0, super.getPreferredSize().height);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, super.getMinimumSize().height);
    }

    private void retruncate() {
        // setFont runs from the JLabel constructor and from every look-and-feel change, both of
        // which can land before there is anything to show.
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        String pick = candidates.get(0);
        int available = availableTextWidth();
        Font font = getFont();
        if (available > 0 && font != null) {
            FontMetrics metrics = getFontMetrics(font);
            // Narrowest candidate as the floor: if even that overflows, JLabel clips it with an
            // ellipsis of its own, which is the right end state.
            pick = candidates.get(candidates.size() - 1);
            for (String candidate : candidates) {
                if (SwingUtilities.computeStringWidth(metrics, candidate) <= available) {
                    pick = candidate;
                    break;
                }
            }
        }
        if (!pick.equals(getText())) {
            setText(pick);
        }
    }

    private int availableTextWidth() {
        Insets insets = getInsets();
        int width = getWidth() - insets.left - insets.right;
        Icon icon = getIcon();
        if (icon != null) {
            width -= icon.getIconWidth() + getIconTextGap();
        }
        return width;
    }
}
