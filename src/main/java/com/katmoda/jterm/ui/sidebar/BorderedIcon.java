package com.katmoda.jterm.ui.sidebar;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Wraps a glyph with inner padding and a 1px light-gray rounded border. Used to give WSL session
 * rows a distinct "chip" look. Sizes itself as {@code glyph + 2*(PAD + BORDER)} so the glyph keeps
 * its own dimensions while the whole icon stays compact (a ~12px glyph yields a ~16px icon).
 */
final class BorderedIcon implements Icon {

    private static final int PAD = 1;
    private static final int BORDER = 1;

    private final Icon delegate;

    BorderedIcon(Icon delegate) {
        this.delegate = delegate;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getIconWidth();
            int h = getIconHeight();
            if (delegate != null) {
                delegate.paintIcon(c, g2, x + PAD + BORDER, y + PAD + BORDER);
            }
            g2.setColor(borderColor());
            g2.drawRoundRect(x, y, w - 1, h - 1, 4, 4);
        } finally {
            g2.dispose();
        }
    }

    private static Color borderColor() {
        Color c = UIManager.getColor("Component.borderColor");
        return (c != null) ? c : new Color(0xC8C8C8);
    }

    @Override
    public int getIconWidth() {
        return (delegate != null ? delegate.getIconWidth() : 12) + 2 * (PAD + BORDER);
    }

    @Override
    public int getIconHeight() {
        return (delegate != null ? delegate.getIconHeight() : 12) + 2 * (PAD + BORDER);
    }
}
