package com.katmoda.jterm.session;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A node in the saved-sessions tree: a {@link FolderNode} (recursive container), an
 * {@link SshSessionConfig} (a saved SSH connection), an {@link RdpSessionConfig} (a saved RDP
 * connection), or a {@link WslDistroNode} (a WSL2 distribution discovered at runtime). Folders,
 * SSH and RDP sessions are persisted polymorphically via a {@code "type"} discriminator;
 * {@code WslDistroNode}s live only in memory and so have no {@code @JsonSubTypes} entry — they
 * are never written to {@code sessions.json}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FolderNode.class, name = "folder"),
        @JsonSubTypes.Type(value = SshSessionConfig.class, name = "ssh"),
        @JsonSubTypes.Type(value = RdpSessionConfig.class, name = "rdp")
})
public sealed interface SessionNode permits FolderNode, SshSessionConfig, RdpSessionConfig, WslDistroNode {

    String getName();

    void setName(String name);

    /** Id into the icon library, or {@code null} for the type's default icon. */
    String getIconId();

    void setIconId(String iconId);
}
