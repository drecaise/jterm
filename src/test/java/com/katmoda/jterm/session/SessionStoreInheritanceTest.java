/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the folder-level inheritance cascade moved out of the sidebar. Only the folder-chain part
 * is asserted (own value → nearest ancestor); the global-default tail reads the live
 * {@code AppSettings}, which is environment-specific.
 */
class SessionStoreInheritanceTest {

    /** root → parent (user=alice, keepAlive=30) → child (no values of its own). */
    private record Tree(SessionStore store, FolderNode parent, FolderNode child) {

        static Tree build() {
            SessionStore store = new SessionStore();
            FolderNode parent = new FolderNode("parent");
            parent.setUser("alice");
            parent.setKeyPath("/keys/parent_ed25519");
            parent.setKeepAliveSeconds(30);
            FolderNode child = new FolderNode("child");
            parent.getChildren().add(child);
            store.root().getChildren().add(parent);
            return new Tree(store, parent, child);
        }
    }

    @Test
    void folderOwnValueWins() {
        Tree t = Tree.build();
        assertEquals("alice", t.store().effectiveUser(t.parent()));
        assertEquals("/keys/parent_ed25519", t.store().effectiveKeyPath(t.parent()));
        assertEquals(30, t.store().effectiveKeepAliveSeconds(t.parent()));
    }

    @Test
    void childWithNoValuesInheritsFromNearestAncestor() {
        Tree t = Tree.build();
        assertEquals("alice", t.store().effectiveUser(t.child()));
        assertEquals("/keys/parent_ed25519", t.store().effectiveKeyPath(t.child()));
        assertEquals(30, t.store().effectiveKeepAliveSeconds(t.child()));
        // inherited* excludes the folder's own value, so the child's inherited chain is the same.
        assertEquals("alice", t.store().inheritedUser(t.child()));
        assertEquals(30, t.store().inheritedKeepAliveSeconds(t.child()));
    }

    @Test
    void inheritedExcludesTheFolderOwnValue() {
        Tree t = Tree.build();
        FolderNode grandchild = new FolderNode("grandchild");
        grandchild.setUser("bob");
        t.child().getChildren().add(grandchild);
        // effective sees bob (own value); inherited skips it and finds alice up the chain.
        assertEquals("bob", t.store().effectiveUser(grandchild));
        assertEquals("alice", t.store().inheritedUser(grandchild));
    }

    @Test
    void nearerAncestorShadowsFartherOne() {
        Tree t = Tree.build();
        t.child().setUser("carol");
        FolderNode grandchild = new FolderNode("grandchild");
        t.child().getChildren().add(grandchild);
        assertEquals("carol", t.store().effectiveUser(grandchild));
        assertEquals("carol", t.store().inheritedUser(grandchild));
    }
}
