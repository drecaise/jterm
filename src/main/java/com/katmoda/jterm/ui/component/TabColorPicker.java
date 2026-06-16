package com.katmoda.jterm.ui.component;

import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.FlowLayout;

/**
 * A reusable tab-color control: a swatch button that opens a {@link JColorChooser}, plus a
 * "Clear" button to fall back to the inherited/theme default. Stores the choice as a
 * {@code "#RRGGBB"} hex string, or {@code null} when cleared.
 *
 * <p>Used for the per-session, per-folder and global-default tab color; the empty-state label
 * (e.g. "Inherit" vs "Default") is configurable so each context reads correctly.</p>
 */
public final class TabColorPicker {

    private final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JButton swatch = new JButton();
    private final String emptyLabel;
    private Color color;

    public TabColorPicker(String initialHex, String emptyLabel) {
        this.emptyLabel = emptyLabel;
        this.color = decodeOrNull(initialHex);
        updateSwatch();
        swatch.addActionListener(a -> {
            Color picked = JColorChooser.showDialog(panel, "Tab Color",
                    color != null ? color : Color.GRAY);
            if (picked != null) {
                color = picked;
                updateSwatch();
            }
        });
        JButton clear = new JButton("Clear");
        clear.addActionListener(a -> {
            color = null;
            updateSwatch();
        });
        panel.add(swatch);
        panel.add(clear);
    }

    /** The control to add to a form. */
    public JPanel component() {
        return panel;
    }

    /** The chosen color as {@code "#RRGGBB"}, or {@code null} when cleared (inherit/default). */
    public String hex() {
        return color != null ? String.format("#%06X", color.getRGB() & 0xFFFFFF) : null;
    }

    private void updateSwatch() {
        if (color != null) {
            swatch.setText("      ");
            swatch.setBackground(color);
            swatch.setOpaque(true);
            swatch.setToolTipText("Click to change the tab color");
        } else {
            swatch.setText(emptyLabel);
            swatch.setBackground(null);
            swatch.setOpaque(false);
            swatch.setToolTipText("Click to choose a tab color");
        }
    }

    private static Color decodeOrNull(String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        try {
            return Color.decode(hex);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
