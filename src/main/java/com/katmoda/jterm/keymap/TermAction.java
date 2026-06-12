package com.katmoda.jterm.keymap;

/**
 * The set of bindable actions. Each has a stable id used as the JSON key in
 * {@code keymap.json} and a default {@link javax.swing.KeyStroke} string
 * (see {@link javax.swing.KeyStroke#getKeyStroke(String)}).
 */
public enum TermAction {
    NEW_TAB("new-tab", "New Tab", "control T"),
    CLOSE_TAB("close-tab", "Close Tab", "control W"),
    SPLIT_COLUMN("split-column", "Split Column", "control RIGHT"),
    SPLIT_ROW("split-row", "Split Row", "control DOWN"),
    CLOSE_PANE("close-pane", "Close Pane", "control UP"),
    OPEN_LOCAL("open-local", "Open Local Shell", "control shift T"),
    TOGGLE_BROADCAST("toggle-broadcast", "Toggle Broadcast", "control shift B"),
    TOGGLE_THEME("toggle-theme", "Toggle Light/Dark", "control shift D"),
    MOVE_SESSION_UP("move-session-up", "Move Session Up", "control shift UP"),
    MOVE_SESSION_DOWN("move-session-down", "Move Session Down", "control shift DOWN"),
    MOVE_TAB_LEFT("move-tab-left", "Move Tab Left", "control shift LEFT"),
    MOVE_TAB_RIGHT("move-tab-right", "Move Tab Right", "control shift RIGHT");

    private final String id;
    private final String label;
    private final String defaultStroke;

    TermAction(String id, String label, String defaultStroke) {
        this.id = id;
        this.label = label;
        this.defaultStroke = defaultStroke;
    }

    public String id() {
        return id;
    }

    /** Human-readable name shown in menus and the shortcut editor. */
    public String label() {
        return label;
    }

    public String defaultStroke() {
        return defaultStroke;
    }
}
