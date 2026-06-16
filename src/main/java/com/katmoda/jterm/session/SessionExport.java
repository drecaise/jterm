package com.katmoda.jterm.session;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON envelope for exporting/importing a folder subtree.
 *
 * <p>{@link #folder} is the exported folder with its full recursive contents. {@link #credentials}
 * maps a session's {@code id} to its plaintext SSH password and is populated only when the user
 * opts in (gated behind the master password); it is empty otherwise.</p>
 *
 * <p>{@link #root} marks an export of the top-level "Sessions" folder. On import its children are
 * merged into the destination rather than nesting another "Sessions" folder inside it.</p>
 */
public final class SessionExport {

    public FolderNode folder;
    public Map<String, String> credentials = new LinkedHashMap<>();
    public boolean root;
}
