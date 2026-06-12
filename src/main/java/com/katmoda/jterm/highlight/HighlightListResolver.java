package com.katmoda.jterm.highlight;

import com.katmoda.jterm.config.AppSettings;

/**
 * Resolves which {@link HighlightList} applies to a terminal, given that terminal's per-session
 * override id. The three override states are encoded compactly:
 *
 * <ul>
 *   <li>{@code null} — <b>inherit</b>: fall back to the global default
 *       ({@link AppSettings#getGlobalHighlightListId()}).</li>
 *   <li>{@link #NONE} — explicit "(None)": no highlighting for this session.</li>
 *   <li>any other value — a {@link HighlightList} id.</li>
 * </ul>
 *
 * <p>A stale id (the list was deleted) resolves to {@code null} (no highlighting), never an error.</p>
 */
public final class HighlightListResolver {

    /** Sentinel meaning "(None)" — highlighting explicitly turned off for a session/globally. */
    public static final String NONE = "__none__";

    private HighlightListResolver() {
    }

    /** @param sessionOverrideId the session's stored override id (see class doc); may be {@code null}. */
    public static HighlightList resolve(String sessionOverrideId) {
        String id = sessionOverrideId;
        if (id == null) {
            // Inherit the global default.
            id = AppSettings.get().getGlobalHighlightListId();
        }
        if (id == null || NONE.equals(id)) {
            return null;
        }
        return HighlightLibrary.get().byId(id);
    }
}
