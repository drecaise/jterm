package com.katmoda.jterm.icon;

/** A user-imported icon, persisted in {@code icons.json} (the file lives in the icons dir). */
public final class ImportedIcon {

    private String id;
    private String displayName;
    private String fileName;

    public ImportedIcon() {
    }

    public ImportedIcon(String id, String displayName, String fileName) {
        this.id = id;
        this.displayName = displayName;
        this.fileName = fileName;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }
}
