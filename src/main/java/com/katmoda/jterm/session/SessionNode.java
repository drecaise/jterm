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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A node in the saved-sessions tree: a {@link FolderNode} (recursive container), an
 * {@link SshSessionConfig} (a saved SSH connection), an {@link RdpSessionConfig} (a saved RDP
 * connection), a {@link WslDistroNode} (a WSL2
 * distribution discovered at runtime), or an {@link UnknownSessionNode} (a node type from a newer
 * jterm). Folders, SSH and RDP sessions are persisted polymorphically
 * via a {@code "type"} discriminator; {@code WslDistroNode}s live only in memory and so have no
 * {@code @JsonSubTypes} entry — they are never written to {@code sessions.json}.
 *
 * <p>A node whose {@code type} this build doesn't know loads as an {@link UnknownSessionNode}
 * ({@code defaultImpl}) and is written back unchanged. That needs the type id {@code visible} to
 * the placeholder's deserializer, but a visible id is also passed to every known subtype as an
 * ordinary {@code "type"} property, which they would reject as unknown (measured, Jackson 2.21) —
 * hence the {@code @JsonIgnoreProperties}, which they inherit. The placeholder's own deserializer
 * reads the raw object and is unaffected by it. A root read as {@code FolderNode.class} ignores the
 * {@code defaultImpl} (not a {@code FolderNode}), so a broken root still fails as before.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type",
        visible = true, defaultImpl = UnknownSessionNode.class)
@JsonIgnoreProperties("type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FolderNode.class, name = "folder"),
        @JsonSubTypes.Type(value = SshSessionConfig.class, name = "ssh"),
        @JsonSubTypes.Type(value = RdpSessionConfig.class, name = "rdp")
})
public sealed interface SessionNode
        permits FolderNode, SshSessionConfig, RdpSessionConfig, WslDistroNode, UnknownSessionNode {

    String getName();

    void setName(String name);

    /** Id into the icon library, or {@code null} for the type's default icon. */
    String getIconId();

    void setIconId(String iconId);
}
