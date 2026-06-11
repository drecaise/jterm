package com.katmoda.jterm.ui.sidebar;

import com.katmoda.jterm.icon.IconLibrary;
import com.katmoda.jterm.session.FolderNode;
import com.katmoda.jterm.session.SessionNode;
import com.katmoda.jterm.session.SshSessionConfig;

import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.Component;

/**
 * Renders tree rows using the {@link SessionNode}'s display name and a type-appropriate
 * icon. Custom per-node icons from the icon library are a phase-1b concern; for now we
 * use the LaF's default folder/leaf icons.
 */
final class SessionNodeRenderer extends DefaultTreeCellRenderer {

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                  boolean expanded, boolean leaf, int row,
                                                  boolean hasFocus) {
        super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);

        if (value instanceof DefaultMutableTreeNode tn
                && tn.getUserObject() instanceof SessionNode node) {
            setText(node.getName());
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
}
