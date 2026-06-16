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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A node in the saved-sessions tree: a {@link FolderNode} (recursive container), an
 * {@link SshSessionConfig} (a saved SSH connection), or a {@link WslDistroNode} (a WSL2
 * distribution discovered at runtime). Folders and SSH sessions are persisted polymorphically
 * via a {@code "type"} discriminator; {@code WslDistroNode}s live only in memory and so have no
 * {@code @JsonSubTypes} entry — they are never written to {@code sessions.json}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FolderNode.class, name = "folder"),
        @JsonSubTypes.Type(value = SshSessionConfig.class, name = "ssh")
})
public sealed interface SessionNode permits FolderNode, SshSessionConfig, WslDistroNode {

    String getName();

    void setName(String name);

    /** Id into the icon library, or {@code null} for the type's default icon. */
    String getIconId();

    void setIconId(String iconId);
}
