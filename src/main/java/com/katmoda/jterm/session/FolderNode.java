package com.katmoda.jterm.session;

import java.util.ArrayList;
import java.util.List;

/** A recursive folder of {@link SessionNode}s. */
public final class FolderNode implements SessionNode {

    private String name = "Folder";
    private String iconId;
    private boolean expanded = true;
    private List<SessionNode> children = new ArrayList<>();

    // Per-folder default SSH username and tab color, inherited by sessions beneath this folder
    // (unless they or a nearer sub-folder override them). null/blank means "inherit" (fall back to
    // an ancestor folder, then the global default).
    private String user;
    private String tabColorHex;

    public FolderNode() {
    }

    public FolderNode(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getIconId() {
        return iconId;
    }

    @Override
    public void setIconId(String iconId) {
        this.iconId = iconId;
    }

    /** Whether this folder is shown expanded in the sidebar tree; persisted across restarts. */
    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }

    public List<SessionNode> getChildren() {
        return children;
    }

    public void setChildren(List<SessionNode> children) {
        this.children = (children != null) ? children : new ArrayList<>();
    }

    /** Per-folder default SSH username, or {@code null} to inherit. */
    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = (user != null && !user.isBlank()) ? user.trim() : null;
    }

    /** Per-folder default tab color as {@code "#RRGGBB"}, or {@code null} to inherit. */
    public String getTabColorHex() {
        return tabColorHex;
    }

    public void setTabColorHex(String tabColorHex) {
        this.tabColorHex = (tabColorHex != null && !tabColorHex.isBlank()) ? tabColorHex : null;
    }
}
