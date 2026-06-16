package com.katmoda.jterm.session;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A recursive folder of {@link SessionNode}s. */
public final class FolderNode implements SessionNode {

    // Stable id used to key this folder's secrets (default key passphrase / default password) in
    // the credential vault. Generated once; persisted so saved secrets survive restarts.
    private String id = UUID.randomUUID().toString();
    private String name = "Folder";
    private String iconId;
    private boolean expanded = true;
    private List<SessionNode> children = new ArrayList<>();

    // Per-folder default SSH username, tab color and private-key path, inherited by sessions
    // beneath this folder (unless they or a nearer sub-folder override them). null/blank means
    // "inherit" (fall back to an ancestor folder, then the global default).
    private String user;
    private String tabColorHex;
    private String keyPath;

    public FolderNode() {
    }

    public FolderNode(String name) {
        this.name = name;
    }

    /** Stable id used as the credential-vault key prefix for this folder's saved secrets. */
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = (id != null && !id.isBlank()) ? id : UUID.randomUUID().toString();
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

    /** Per-folder default SSH private-key path, or {@code null} to inherit. */
    public String getKeyPath() {
        return keyPath;
    }

    public void setKeyPath(String keyPath) {
        this.keyPath = (keyPath != null && !keyPath.isBlank()) ? keyPath.trim() : null;
    }
}
