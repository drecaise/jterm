/*
 * jterm — a Java terminal emulator.
 * Copyright (C) 2026 Mark Moses
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.katmoda.jterm.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.katmoda.jterm.config.JsonStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A node type from a newer jterm must load as a placeholder and survive this build's saves
 * unchanged — {@code SessionStore} rewrites the whole file on load (schema stamp) and on every save.
 */
class UnknownSessionNodeTest {

    private static final ObjectMapper MAPPER = JsonStore.mapper();

    @TempDir
    Path dir;

    /** What a newer jterm might write: nested objects, arrays, nulls, and "type" not first. */
    private static ObjectNode futureNode(String name) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", "6b0d2b1e-0000-4000-8000-000000000001");
        node.put("type", "vnc");
        node.put("name", name);
        node.put("port", 5901);
        ObjectNode display = node.putObject("display");
        display.put("depth", 24);
        display.putArray("scales").add(1.0).add(1.5).addNull();
        ObjectNode nested = display.putObject("nested");
        nested.put("flag", true);
        nested.putNull("nothing");
        ArrayNode monitors = node.putArray("monitors");
        monitors.addObject().put("x", 0).putArray("tags").add("left");
        monitors.addObject().put("x", 1920);
        // A future container holding children of known and unknown types: kept as raw JSON too.
        ArrayNode children = node.putArray("children");
        children.addObject().put("type", "ssh").put("name", "inner");
        return node;
    }

    /**
     * A sessions.json as Jackson writes this build's own nodes (so the known parts are already in
     * canonical form), with future nodes spliced in at the root and inside a sub-folder. The root
     * carries no schemaVersion, so loading runs every migration and then saves.
     */
    private static ObjectNode fixture() {
        SshSessionConfig ssh = new SshSessionConfig();
        ssh.setName("web-1");
        ssh.setHost("web1.example.com");
        ssh.setFontSize(20);
        RdpSessionConfig rdp = new RdpSessionConfig();
        rdp.setName("desktop");
        rdp.setHost("desktop.example.com");
        FolderNode sub = new FolderNode("Production");
        sub.getChildren().add(ssh);
        FolderNode root = new FolderNode("Sessions");
        root.getChildren().add(sub);
        root.getChildren().add(rdp);

        ObjectNode tree = MAPPER.valueToTree(root);
        ((ArrayNode) tree.get("children")).add(futureNode("top-level vnc"));
        ArrayNode subChildren = (ArrayNode) tree.get("children").get(0).get("children");
        subChildren.insert(0, futureNode("nested vnc"));
        return tree;
    }

    private Path write(JsonNode tree) throws Exception {
        Path file = dir.resolve("sessions.json");
        Files.write(file, MAPPER.writeValueAsBytes(tree));
        return file;
    }

    private static List<UnknownSessionNode> unknownNodes(FolderNode folder) {
        List<UnknownSessionNode> out = new ArrayList<>();
        for (SessionNode child : folder.getChildren()) {
            if (child instanceof UnknownSessionNode unknown) {
                out.add(unknown);
            } else if (child instanceof FolderNode sub) {
                out.addAll(unknownNodes(sub));
            }
        }
        return out;
    }

    @Test
    void loadingAndSavingKeepsAnUnknownNodeExactly() throws Exception {
        ObjectNode original = fixture();
        Path file = write(original);

        SessionStore store = new SessionStore(file);

        // The constructor ran the migrations and saved: the only difference is the version stamp.
        ObjectNode expected = original.deepCopy();
        expected.put("schemaVersion", SessionMigrations.CURRENT_VERSION);
        assertEquals(expected, MAPPER.readTree(file.toFile()));
        assertFalse(Files.exists(dir.resolve("sessions.json.unreadable-1")), "nothing was quarantined");

        // And an ordinary save after that changes nothing either.
        store.save();
        assertEquals(expected, MAPPER.readTree(file.toFile()));

        // A second build-cycle (load the rewritten file, save) is stable too.
        new SessionStore(file).save();
        assertEquals(expected, MAPPER.readTree(file.toFile()));
    }

    @Test
    void anUnknownNodeKeepsItsPropertyOrder() throws Exception {
        Path file = write(fixture());
        new SessionStore(file).save();
        JsonNode saved = MAPPER.readTree(file.toFile()).get("children").get(2);
        List<String> names = new ArrayList<>();
        saved.fieldNames().forEachRemaining(names::add);
        assertEquals(List.of("id", "type", "name", "port", "display", "monitors", "children"), names);
    }

    @Test
    void anUnknownNodeLoadsAsAPlaceholderAndIsNeverOpenable() throws Exception {
        SessionStore store = new SessionStore(write(fixture()));
        List<UnknownSessionNode> unknown = unknownNodes(store.root());
        assertEquals(2, unknown.size());
        assertEquals("nested vnc", unknown.get(0).getName());
        assertEquals("vnc", unknown.get(0).typeId());

        List<SessionNode> openable = SessionStore.collectOpenableSessions(store.root());
        assertEquals(2, openable.size(), "the SSH and RDP sessions only");
        assertTrue(openable.stream().noneMatch(n -> n instanceof UnknownSessionNode));
        assertEquals(1, SessionStore.collectSshSessions(store.root()).size());
    }

    @Test
    void aPlaceholderWithoutAStringNameIsListedUnderItsType() throws Exception {
        ObjectNode tree = fixture();
        ObjectNode future = (ObjectNode) tree.get("children").get(2);
        future.putObject("name").put("en", "localised");
        SessionStore store = new SessionStore(write(tree));
        UnknownSessionNode node = unknownNodes(store.root()).get(1);
        assertNull(node.savedName());
        assertEquals("vnc", node.getName());
        node.setName("renamed"); // ignored: a rename would rewrite a node this build doesn't know
        store.save();
        assertEquals(tree.get("children").get(2), MAPPER.readTree(dir.resolve("sessions.json").toFile())
                .get("children").get(2));
    }

    /** No jterm ever wrote a node without a "type", so that stays a damaged file, as before. */
    @Test
    void aMissingTypeStillQuarantinesTheFile() throws Exception {
        assertQuarantined(tree -> ((ObjectNode) tree.get("children").get(2)).remove("type"));
    }

    @Test
    void aMalformedTypeStillQuarantinesTheFile() throws Exception {
        // Jackson hands a scalar id to the placeholder as text, so 7 and true would come back as
        // "7" and "true": not lossless, and not something any jterm wrote.
        assertQuarantined(tree -> ((ObjectNode) tree.get("children").get(2)).put("type", 7));
        assertQuarantined(tree -> ((ObjectNode) tree.get("children").get(2)).put("type", true));
        assertQuarantined(tree -> ((ObjectNode) tree.get("children").get(2)).putNull("type"));
        assertQuarantined(tree -> ((ObjectNode) tree.get("children").get(2)).putObject("type"));
        assertQuarantined(tree -> ((ObjectNode) tree.get("children").get(2)).put("type", "Not An Id"));
    }

    @Test
    void aNonObjectChildStillQuarantinesTheFile() throws Exception {
        assertQuarantined(tree -> ((ArrayNode) tree.get("children")).add("just a string"));
    }

    /** The root is read as a FolderNode, where the defaultImpl doesn't apply. */
    @Test
    void anUnknownRootStillQuarantinesTheFile() throws Exception {
        assertQuarantined(tree -> tree.put("type", "vnc"));
    }

    private void assertQuarantined(java.util.function.Consumer<ObjectNode> damage) throws Exception {
        ObjectNode tree = fixture();
        damage.accept(tree);
        Path file = write(tree);
        byte[] written = Files.readAllBytes(file);

        SessionStore store = new SessionStore(file);

        assertTrue(store.root().getChildren().isEmpty(), "falls back to an empty tree");
        Path aside = dir.resolve("sessions.json.unreadable-1");
        assertTrue(Files.exists(aside), "the damaged file is preserved aside");
        assertEquals(new String(written), Files.readString(aside), "byte for byte");
        Files.delete(aside);
        Files.delete(file);
    }

    @Test
    void migrationsWalkPastAPlaceholder() {
        FolderNode root = new FolderNode("Sessions");
        root.getChildren().add(new UnknownSessionNode(futureNode("vnc")));
        SshSessionConfig forced = new SshSessionConfig();
        forced.setFontSize(14);
        root.getChildren().add(forced);
        assertTrue(SessionMigrations.migrate(root));
        assertEquals(0, forced.getFontSize());
    }

    @Test
    void theKnownTypesStillRejectNothingTheyUsedToAccept() throws Exception {
        // The visible type id reaches the known subtypes as a "type" property; they must ignore it.
        FolderNode root = MAPPER.readValue("""
                {"type":"folder","name":"Sessions","children":[
                  {"type":"ssh","name":"a","host":"h"},
                  {"type":"rdp","name":"b","host":"d"},
                  {"type":"folder","name":"f","children":[]}]}""", FolderNode.class);
        assertInstanceOf(SshSessionConfig.class, root.getChildren().get(0));
        assertInstanceOf(RdpSessionConfig.class, root.getChildren().get(1));
        assertInstanceOf(FolderNode.class, root.getChildren().get(2));
        assertEquals("h", ((SshSessionConfig) root.getChildren().get(0)).getHost());
    }
}
