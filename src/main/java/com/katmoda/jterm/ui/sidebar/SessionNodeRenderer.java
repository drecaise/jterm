package com.katmoda.jterm.ui.sidebar;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.katmoda.jterm.icon.IconLibrary;
import com.katmoda.jterm.session.FolderNode;
import com.katmoda.jterm.session.SessionNode;
import com.katmoda.jterm.session.SshSessionConfig;
import com.katmoda.jterm.session.WslDistroNode;

import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.Color;
import java.awt.Component;

/**
 * Renders tree rows using the {@link SessionNode}'s display name and a type-appropriate
 * icon. Custom per-node icons from the icon library are a phase-1b concern; for now we
 * use the LaF's default folder/leaf icons. The pinned "WSL" folder and its distribution rows
 * get bespoke icons (a blue-tinted folder and bordered WSL glyphs respectively).
 */
final class SessionNodeRenderer extends DefaultTreeCellRenderer {

    /** The Windows-subsystem blue used by the WSL logo, reused to tint the WSL folder. */
    private static final Color WSL_BLUE = new Color(0x0078D4);

    /** The runtime "WSL" container, or {@code null} off Windows; matched by identity. */
    private final FolderNode wslFolder;
    private final Icon wslFolderOpen;
    private final Icon wslFolderClosed;

    SessionNodeRenderer(FolderNode wslFolder) {
        this.wslFolder = wslFolder;
        this.wslFolderOpen = (wslFolder != null) ? blueFolder("icons/folder-open.svg") : null;
        this.wslFolderClosed = (wslFolder != null) ? blueFolder("icons/folder.svg") : null;
    }

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                  boolean expanded, boolean leaf, int row,
                                                  boolean hasFocus) {
        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);

        if (value instanceof DefaultMutableTreeNode tn
                && tn.getUserObject() instanceof SessionNode node) {
            setText(node.getName());
            if (node instanceof WslDistroNode) {
                setIcon(new BorderedIcon(IconLibrary.get().icon("builtin/wsl", 12)));
                return this;
            }
            if (node == wslFolder) {
                setIcon(expanded ? wslFolderOpen : wslFolderClosed);
                return this;
            }
            Icon custom = IconLibrary.get().icon(node.getIconId(), 16);
            if (custom != null) {
                setIcon(custom);
            } else if (node instanceof FolderNode) {
                setIcon(expanded ? UIManager.getIcon("Tree.openIcon")
                        : UIManager.getIcon("Tree.closedIcon"));
            } else if (node instanceof SshSessionConfig) {
                setIcon(UIManager.getIcon("Tree.leafIcon"));
            }
        }
        return this;
    }

    /** A 16px folder glyph with every colour mapped to the WSL blue. */
    private static Icon blueFolder(String resource) {
        FlatSVGIcon icon = new FlatSVGIcon(resource).derive(16, 16);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(c -> WSL_BLUE));
        return icon;
    }
}
