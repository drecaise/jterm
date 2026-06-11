package com.katmoda.jterm.session;

import java.util.ArrayList;
import java.util.List;

/** A recursive folder of {@link SessionNode}s. */
public final class FolderNode implements SessionNode {

    private String name = "Folder";
    private String iconId;
    private boolean expanded = true;
    private List<SessionNode> children = new ArrayList<>();

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
}
