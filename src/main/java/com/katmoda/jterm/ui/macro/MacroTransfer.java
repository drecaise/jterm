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
package com.katmoda.jterm.ui.macro;

import com.formdev.flatlaf.util.UIScale;
import com.katmoda.jterm.keymap.Keymap;
import com.katmoda.jterm.macro.EncryptedMacroExport;
import com.katmoda.jterm.macro.Macro;
import com.katmoda.jterm.macro.MacroCrypto;
import com.katmoda.jterm.macro.MacroExport;
import com.katmoda.jterm.macro.MacroExportService;
import com.katmoda.jterm.macro.MacroLibrary;
import com.katmoda.jterm.config.AppSettings;
import com.katmoda.jterm.security.VaultException;
import com.katmoda.jterm.security.VaultManager;
import com.katmoda.jterm.ui.ErrorDialog;
import com.katmoda.jterm.ui.component.FileNames;
import com.katmoda.jterm.ui.component.FooterFileChooser;
import com.katmoda.jterm.ui.component.ToggleSwitch;
import com.katmoda.jterm.ui.security.MasterPasswordDialog;

import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.ButtonGroup;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The export/import user flow for macros: file choosers, passphrase prompts, conflict resolution and
 * reporting. The file formats and the merge itself live in {@code macro.MacroExportService}, which
 * stays Swing-free — this class is the other half of that split, and holds no logic of its own that
 * is worth testing headlessly.
 *
 * <p>Shared by {@link MacroManagerDialog}'s buttons and the File menu's items so the two cannot
 * drift apart.</p>
 */
public final class MacroTransfer {

    private static final String EXPORT_TITLE = "Export Macros";
    private static final String IMPORT_TITLE = "Import Macros";

    private MacroTransfer() {
    }

    // ---- export ----

    /**
     * Writes {@code macros} to a user-chosen file, optionally encrypted under a passphrase.
     *
     * <p>The "protect with a passphrase" switch defaults to <b>on</b>, the opposite of the sessions
     * export. A sessions file is mostly hostnames and only carries secrets when the user explicitly
     * asks for credentials; a macro is arbitrary text the user typed, which is exactly the case
     * where secrets show up unannounced. Making the safe path the default one costs a keystroke to
     * opt out of and nothing otherwise.</p>
     */
    public static void export(Component parent, List<Macro> macros) {
        if (macros.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "There are no macros to export.",
                    EXPORT_TITLE, JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // An export always carries plaintext steps, so anything sealed has to be opened first.
        List<Macro> plain = resolveAll(parent, macros);
        if (plain == null) {
            return; // cancelled, or already reported
        }

        ToggleSwitch encrypt = new ToggleSwitch(true);
        encrypt.setText("Protect this file with a passphrase");
        encrypt.setToolTipText("Macros often contain commands you would not want read from a backup");

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(EXPORT_TITLE);
        String base = (macros.size() == 1) ? FileNames.safe(macros.get(0).getName(), "macros") : "macros";
        chooser.setSelectedFile(new File(base + ".json"));
        chooser.setPreferredSize(UIScale.scale(new Dimension(700, 460)));
        if (FooterFileChooser.showSaveDialog(parent, chooser, encrypt) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        Path target = chooser.getSelectedFile().toPath();
        if (Files.exists(target)) {
            int choice = JOptionPane.showConfirmDialog(parent,
                    target.getFileName() + " already exists.\nDo you want to replace it?",
                    EXPORT_TITLE, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }

        MacroExport export = MacroExportService.build(plain);
        char[] passphrase = null;
        if (encrypt.isSelected()) {
            passphrase = MasterPasswordDialog.promptCreateExportPassphrase(parent);
            if (passphrase == null) {
                return;
            }
        }
        try {
            if (passphrase == null) {
                MacroExportService.writePlain(target, export);
            } else {
                MacroExportService.writeEncrypted(target, export, passphrase);
            }
        } catch (Exception e) {
            ErrorDialog.show(parent, EXPORT_TITLE, "Export failed:", e);
            return;
        }

        String note = encrypt.isSelected() ? ""
                : "\n\nThis file is not encrypted — anything the macros type is readable in it.";
        JOptionPane.showMessageDialog(parent,
                "Exported " + count(plain.size(), "macro") + " to:\n" + target + note,
                EXPORT_TITLE, JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Plaintext copies of {@code macros}, unlocking the vault if any are sealed. Returns {@code null}
     * if the user cancelled the unlock or a macro could not be decrypted (already reported). The
     * originals are left untouched — a read must not flip the library to plaintext as a side effect.
     */
    private static List<Macro> resolveAll(Component parent, List<Macro> macros) {
        if (!MacroCrypto.anySealed(macros)) {
            return new ArrayList<>(macros);
        }
        if (!VaultManager.get().ensureUnlocked(parent)) {
            return null;
        }
        List<Macro> plain = new ArrayList<>(macros.size());
        try {
            for (Macro macro : macros) {
                plain.add(MacroCrypto.resolved(macro, VaultManager.get().vault()));
            }
        } catch (VaultException e) {
            ErrorDialog.show(parent, EXPORT_TITLE, "Could not decrypt the macros:", e);
            return null;
        }
        return plain;
    }

    // ---- import ----

    /**
     * Reads a macro export and merges it into the library, saving on success.
     *
     * @return {@code true} if the library changed, so the caller can refresh the Macros menu
     */
    public static boolean importMacros(Component parent, Keymap keymap) {
        if (MacroLibrary.get().isLoadFailed()) {
            JOptionPane.showMessageDialog(parent,
                    "macros.json could not be read and has been set aside as\n"
                            + "macros.json.unreadable-* in the config folder.\n\n"
                            + "Importing now would write over your existing macros, so it is\n"
                            + "blocked until that file is restored or removed.",
                    IMPORT_TITLE, JOptionPane.ERROR_MESSAGE);
            return false;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(IMPORT_TITLE);
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return false;
        }

        MacroExport export = read(parent, chooser.getSelectedFile());
        if (export == null) {
            return false; // cancelled, or already reported
        }
        if (export.macros == null || export.macros.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "That file is not a valid macros export.",
                    IMPORT_TITLE, JOptionPane.ERROR_MESSAGE);
            return false;
        }

        // A macro is a canned sequence of keystrokes typed straight into a shell, and an imported one
        // can also be bound to a hotkey or replayed automatically on connect. Say so plainly: the
        // file may well have come from someone else.
        int proceed = JOptionPane.showConfirmDialog(parent,
                "Import " + count(export.macros.size(), "macro") + " from this file?\n\n"
                        + "Imported macros type commands into your terminals, so only import\n"
                        + "files you trust. You can review each one afterwards under Macros →\n"
                        + "Manage Macros.",
                IMPORT_TITLE, JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (proceed != JOptionPane.OK_OPTION) {
            return false;
        }

        ConflictPrompt conflicts = new ConflictPrompt(parent);
        MacroExportService.MergeResult result = MacroExportService.merge(
                export.macros, MacroLibrary.get().macros(), conflicts, keymap);
        if (conflicts.cancelled) {
            return false;
        }
        if (result.added() == 0 && result.replaced() == 0) {
            JOptionPane.showMessageDialog(parent, "Nothing was imported.",
                    IMPORT_TITLE, JOptionPane.INFORMATION_MESSAGE);
            return false;
        }

        // Sealing the newly imported macros needs the vault, so unlock before touching the library:
        // a refused save would otherwise leave the imports in memory but not on disk.
        if (AppSettings.get().isEncryptMacros() && !VaultManager.get().ensureUnlocked(parent)) {
            return false;
        }

        List<Macro> previous = new ArrayList<>(MacroLibrary.get().macros());
        MacroLibrary.get().replaceAll(result.macros());
        if (!MacroLibrary.get().save()) {
            MacroLibrary.get().replaceAll(previous); // keep memory and disk consistent
            JOptionPane.showMessageDialog(parent,
                    "The imported macros could not be saved, so nothing was changed.",
                    IMPORT_TITLE, JOptionPane.ERROR_MESSAGE);
            return false;
        }

        JOptionPane.showMessageDialog(parent, summary(result), IMPORT_TITLE,
                JOptionPane.INFORMATION_MESSAGE);
        return true;
    }

    /** Reads an export, transparently decrypting an envelope (prompting, and retrying, for its passphrase). */
    private static MacroExport read(Component parent, File file) {
        try {
            if (!MacroExportService.isEncrypted(file)) {
                return MacroExportService.readPlain(file);
            }
            EncryptedMacroExport envelope = MacroExportService.readEnvelope(file);
            String error = null;
            while (true) {
                char[] passphrase = MasterPasswordDialog.promptExportPassphrase(parent, error);
                if (passphrase == null) {
                    return null; // cancelled
                }
                try {
                    MacroExport export = MacroExportService.openEnvelope(envelope, passphrase);
                    if (export != null) {
                        return export;
                    }
                    error = "Incorrect passphrase — try again.";
                } finally {
                    java.util.Arrays.fill(passphrase, '\0');
                }
            }
        } catch (Exception e) {
            ErrorDialog.show(parent, IMPORT_TITLE, "Import failed:", e);
            return null;
        }
    }

    private static String summary(MacroExportService.MergeResult result) {
        StringBuilder text = new StringBuilder("Imported ")
                .append(count(result.added(), "new macro")).append('.');
        if (result.replaced() > 0) {
            text.append('\n').append("Replaced ").append(count(result.replaced(), "existing macro"))
                    .append('.');
        }
        if (result.skipped() > 0) {
            text.append('\n').append("Skipped ").append(count(result.skipped(), "macro")).append('.');
        }
        if (!result.clearedHotkeys().isEmpty()) {
            text.append("\n\nThese hotkeys were already in use and have been cleared:");
            for (String cleared : result.clearedHotkeys()) {
                text.append("\n  • ").append(cleared);
            }
        }
        return text.toString();
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /**
     * Asks the user what to do about an imported macro whose id already exists, remembering the
     * answer when they tick "apply to all". Handed to {@code MacroExportService.merge} as its policy
     * function, so the merge itself stays pure.
     */
    private static final class ConflictPrompt
            implements java.util.function.Function<Macro, MacroExportService.Conflict> {

        private final Component parent;
        private MacroExportService.Conflict remembered;
        private boolean cancelled;

        ConflictPrompt(Component parent) {
            this.parent = parent;
        }

        @Override
        public MacroExportService.Conflict apply(Macro macro) {
            if (cancelled) {
                return MacroExportService.Conflict.SKIP;
            }
            if (remembered != null) {
                return remembered;
            }

            JRadioButton replace = new JRadioButton("Replace the existing macro");
            JRadioButton keepBoth = new JRadioButton("Keep both — import as a new macro", true);
            JRadioButton skip = new JRadioButton("Skip this macro");
            ButtonGroup group = new ButtonGroup();
            group.add(replace);
            group.add(keepBoth);
            group.add(skip);
            ToggleSwitch applyToAll = new ToggleSwitch(false);
            applyToAll.setText("Apply to all remaining conflicts");

            JPanel form = new JPanel(new GridLayout(0, 1, 0, 4));
            form.add(new JLabel("\"" + macro.getName() + "\" already exists."));
            form.add(replace);
            form.add(keepBoth);
            form.add(skip);
            form.add(applyToAll);

            int choice = JOptionPane.showConfirmDialog(parent, form, IMPORT_TITLE,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                // Cancel means abandon the whole import, not "skip this one".
                cancelled = true;
                return MacroExportService.Conflict.SKIP;
            }

            MacroExportService.Conflict decision = replace.isSelected()
                    ? MacroExportService.Conflict.REPLACE
                    : skip.isSelected() ? MacroExportService.Conflict.SKIP
                    : MacroExportService.Conflict.KEEP_BOTH;
            if (applyToAll.isSelected()) {
                remembered = decision;
            }
            return decision;
        }
    }
}
