package com.katmoda.jterm.session;

/**
 * A WSL2 distribution surfaced as a launchable session. These are <em>discovered at runtime</em>
 * (on Windows) and live only in memory under the sidebar's pinned "WSL" folder — they are never
 * added to the persisted session tree, so they're never serialized and never editable. Mutators
 * are intentionally no-ops; the icon is always the WSL glyph (decorated by the renderer).
 */
public final class WslDistroNode implements SessionNode {

    private final String distro;

    public WslDistroNode(String distro) {
        this.distro = distro;
    }

    /** The distribution name passed to {@code wsl.exe -d <distro>}. */
    public String distro() {
        return distro;
    }

    @Override
    public String getName() {
        return distro;
    }

    @Override
    public void setName(String name) {
        // Non-editable: detected distros can't be renamed.
    }

    @Override
    public String getIconId() {
        return "builtin/wsl";
    }

    @Override
    public void setIconId(String iconId) {
        // Non-editable: the icon is fixed.
    }
}
