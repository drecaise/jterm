package com.katmoda.jterm.ui.theme;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.katmoda.jterm.config.AppSettings;

import java.awt.Font;

/**
 * Supplies JediTerm with colors and font derived from the current {@link ThemeColors}.
 *
 * <p>The terminal's default foreground/background ({@code getDefaultForeground} /
 * {@code getDefaultBackground}) and ANSI palette are all read from the theme captured
 * at construction time. A pane created after a theme switch picks up the new colors;
 * live recoloring of already-running terminals is a later (theming-polish) concern.</p>
 */
public final class JTermSettingsProvider extends DefaultSettingsProvider {

    private final ThemeColors theme;
    private final ColorPalette palette;
    private final Font font;

    public JTermSettingsProvider(ThemeColors theme) {
        this(theme, null, 0);
    }

    /**
     * @param fontFamily a specific installed font family, or {@code null}/blank to use the
     *                   application default ({@link AppSettings#getDefaultFontFamily()})
     * @param fontSize   point size, or {@code <= 0} to use the application default
     */
    public JTermSettingsProvider(ThemeColors theme, String fontFamily, int fontSize) {
        this.theme = theme;
        this.palette = new AnsiPalette(theme);
        AppSettings settings = AppSettings.get();
        int size = fontSize > 0 ? fontSize : settings.getDefaultFontSize();
        String family = (fontFamily != null && !fontFamily.isBlank())
                ? fontFamily : settings.getDefaultFontFamily();
        this.font = resolveFont(family, size);
    }

    /** Uses the requested family when it's installed; otherwise auto-picks a monospaced font. */
    private static Font resolveFont(String fontFamily, int size) {
        if (fontFamily != null && !fontFamily.isBlank() && isAvailable(fontFamily)) {
            return new Font(fontFamily, Font.PLAIN, size);
        }
        return resolveMonospacedFont(size);
    }

    private static boolean isAvailable(String family) {
        for (String name : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()) {
            if (name.equalsIgnoreCase(family)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Picks a genuinely monospaced installed font (JediTerm warns about the logical
     * "Monospaced" family), falling back to the logical family if none are present.
     */
    private static Font resolveMonospacedFont(int size) {
        String[] preferred = {
                "JetBrains Mono", "DejaVu Sans Mono", "Liberation Mono",
                "Noto Sans Mono", "Consolas", "Menlo", "Courier New"
        };
        java.util.Set<String> available = new java.util.HashSet<>(java.util.Arrays.asList(
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getAvailableFontFamilyNames()));
        for (String family : preferred) {
            if (available.contains(family)) {
                return new Font(family, Font.PLAIN, size);
            }
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    @Override
    public ColorPalette getTerminalColorPalette() {
        return palette;
    }

    @Override
    public TerminalColor getDefaultForeground() {
        return terminalColor(theme.foreground());
    }

    @Override
    public TerminalColor getDefaultBackground() {
        return terminalColor(theme.background());
    }

    /**
     * The style applied to cells written with no explicit color (the terminal's "default pen").
     * JediTerm's stock implementation returns a black-on-white style (ANSI index 0 on index 15),
     * which paints every default-styled cell white on a dark theme; we override it so default
     * cells use the theme's own foreground/background, matching {@link #getDefaultForeground()} /
     * {@link #getDefaultBackground()}.
     */
    @Override
    public TextStyle getDefaultStyle() {
        return new TextStyle(terminalColor(theme.foreground()), terminalColor(theme.background()));
    }

    @Override
    public TextStyle getSelectionColor() {
        return new TextStyle(terminalColor(theme.selectionFg()), terminalColor(theme.selectionBg()));
    }

    @Override
    public Font getTerminalFont() {
        return font;
    }

    @Override
    public float getTerminalFontSize() {
        return font.getSize();
    }

    private static TerminalColor terminalColor(java.awt.Color c) {
        return new TerminalColor(c.getRed(), c.getGreen(), c.getBlue());
    }
}
