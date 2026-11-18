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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * A saved-sessions node whose {@code "type"} this build doesn't know — written by a newer jterm
 * that added a node type. It is {@link SessionNode}'s Jackson {@code defaultImpl}, so such a node
 * degrades to a placeholder instead of failing the whole {@code sessions.json} (which
 * {@code JsonStore} would then set aside as {@code .unreadable-N}, leaving the user an empty tree —
 * what adding {@code "rdp"} did to every older build).
 *
 * <p><b>The node is kept verbatim and written back verbatim.</b> {@link SessionStore} rewrites the
 * whole file on every save (and once on load, for the schema stamp), so anything this build drops
 * here would be destroyed for the newer build that wrote it. The placeholder therefore holds the
 * complete JSON object — the original {@code type} included — and serializes exactly that, never
 * a type id of its own. Getting the {@code type} at all needs {@code visible = true} on
 * {@link SessionNode}'s {@code @JsonTypeInfo}: without it Jackson consumes the property before a
 * {@code defaultImpl} deserializer runs, and the placeholder would be written back without one.</p>
 *
 * <p>It cannot be opened, edited, duplicated or exported; it can be moved and deleted. Nothing
 * about it is trusted beyond being JSON: imports drop it ({@link SessionExportService}).</p>
 */
@JsonSerialize(using = UnknownSessionNode.Serializer.class)
@JsonDeserialize(using = UnknownSessionNode.Deserializer.class)
public final class UnknownSessionNode implements SessionNode {

    /**
     * What a type id written by some jterm looks like ({@code folder}, {@code ssh}, {@code rdp}): a
     * lowercase word. Anything else — a missing, null, numeric, boolean or structured {@code type},
     * or an object that isn't one — no jterm wrote, so it fails the file exactly as it did before
     * this class existed rather than being kept under the "needs a newer jterm" label. Jackson hands
     * the placeholder a numeric or boolean id already converted to text (it re-inserts the visible
     * id as a string), so writing it back would not be lossless either; the pattern refuses those
     * ({@code true}/{@code false} by name). A future node type's id must match it.
     */
    static final Pattern TYPE_ID = Pattern.compile("[a-z][a-z0-9_-]*");

    private final ObjectNode json;

    UnknownSessionNode(ObjectNode json) {
        this.json = json;
    }

    /** The node's original {@code type} value, e.g. {@code "vnc"}. */
    public String typeId() {
        return json.get("type").textValue();
    }

    /** The node's own {@code name}, or {@code null} if it has no string one. */
    public String savedName() {
        JsonNode name = json.get("name");
        return (name != null && name.isTextual()) ? name.textValue() : null;
    }

    /** A copy of the complete JSON object as it will be written back. */
    public ObjectNode json() {
        return json.deepCopy();
    }

    /** The saved name, else the type id, so the node always has something to be listed under. */
    @Override
    public String getName() {
        String name = savedName();
        return (name != null && !name.isBlank()) ? name : typeId();
    }

    @Override
    public void setName(String name) {
        // Not editable: this build doesn't know what else a rename would have to keep consistent.
    }

    @Override
    public String getIconId() {
        // The type's own icon would be a guess; the renderer shows a neutral one.
        return null;
    }

    @Override
    public void setIconId(String iconId) {
        // Not editable, as setName.
    }

    @Override
    public String toString() {
        return getName();
    }

    /** Reads the whole object as a tree; refuses anything no jterm wrote (see {@link #TYPE_ID}). */
    static final class Deserializer extends JsonDeserializer<UnknownSessionNode> {
        @Override
        public UnknownSessionNode deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode tree = p.readValueAsTree();
            if (!(tree instanceof ObjectNode obj)) {
                return ctxt.reportInputMismatch(SessionNode.class, "A saved-sessions node must be a JSON object");
            }
            JsonNode type = obj.get("type");
            if (type == null || !type.isTextual() || !TYPE_ID.matcher(type.textValue()).matches()
                    || "true".equals(type.textValue()) || "false".equals(type.textValue())) {
                // A missing "type" fails here as it did before the placeholder existed: every jterm
                // has always written one, so this is a damaged or hand-edited file, which is
                // preserved aside intact rather than shown as a node from a newer version.
                return ctxt.reportInputMismatch(SessionNode.class,
                        "Saved-sessions node has a missing or malformed \"type\": %s", type);
            }
            return new UnknownSessionNode(obj);
        }
    }

    /**
     * Writes the stored object unchanged. {@code serializeWithType} ignores the type serializer on
     * purpose: it would add a {@code "type"} of its own (the class's default name), and the original
     * one is already in the object.
     */
    static final class Serializer extends JsonSerializer<UnknownSessionNode> {
        @Override
        public void serialize(UnknownSessionNode node, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            gen.writeTree(node.json);
        }

        @Override
        public void serializeWithType(UnknownSessionNode node, JsonGenerator gen, SerializerProvider provider,
                                      TypeSerializer typeSer) throws IOException {
            serialize(node, gen, provider);
        }
    }
}
