package com.katmoda.jterm.session;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A node in the saved-sessions tree: either a {@link FolderNode} (recursive container)
 * or an {@link SshSessionConfig} (a saved SSH connection). Persisted polymorphically via
 * a {@code "type"} discriminator.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FolderNode.class, name = "folder"),
        @JsonSubTypes.Type(value = SshSessionConfig.class, name = "ssh")
})
public sealed interface SessionNode permits FolderNode, SshSessionConfig {

    String getName();

    void setName(String name);

    /** Id into the icon library, or {@code null} for the type's default icon. */
    String getIconId();

    void setIconId(String iconId);
}
