package com.katmoda.jterm.ui.preferences;

import com.katmoda.jterm.keymap.Keymap;
import com.katmoda.jterm.keymap.TermAction;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.Map;

/**
 * Editor for the configurable keyboard shortcuts. Each action shows its current binding as a
 * button; clicking it records the next key combination pressed. Saving validates that no two
 * actions share a stroke, writes {@code keymap.json}, and runs an {@code onSaved} callback so
 * the caller can refresh menu accelerators.
 *
 * <p>Capture goes through a {@link KeyEventDispatcher} (the same mechanism the global terminal
 * shortcuts use) so it intercepts the keystroke before component key bindings — including the
 * button's own Space/Enter activation — can consume it.</p>
 */
public final class ShortcutsDialog extends JDialog {

    private final Keymap keymap;
    private final Runnable onSaved;
    private final Map<TermAction, KeyStroke> pending = new EnumMap<>(TermAction.class);
    private final Map<TermAction, JButton> buttons = new EnumMap<>(TermAction.class);

    private KeyEventDispatcher recorder;
    private TermAction recording;

    private ShortcutsDialog(Window owner, Keymap keymap, Runnable onSaved) {
        super(owner, "Keyboard Shortcuts", ModalityType.APPLICATION_MODAL);
        this.keymap = keymap;
        this.onSaved = onSaved;
        this.pending.putAll(keymap.bindings());

        setContentPane(buildContent());
        pack();
        setMinimumSize(new Dimension(380, getHeight()));
        setLocationRelativeTo(owner);
    }

    /** Shows the modal editor. */
    public static void show(Window owner, Keymap keymap, Runnable onSaved) {
        new ShortcutsDialog(owner, keymap, onSaved).setVisible(true);
    }

    private JPanel buildContent() {
        JPanel rows = new JPanel(new java.awt.GridBagLayout());
        rows.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        java.awt.GridBagConstraints g = new java.awt.GridBagConstraints();
        g.insets = new java.awt.Insets(3, 3, 3, 3);
        g.anchor = java.awt.GridBagConstraints.WEST;
        g.gridy = 0;
        for (TermAction action : TermAction.values()) {
            g.gridx = 0;
            g.weightx = 0;
            g.fill = java.awt.GridBagConstraints.NONE;
            rows.add(new JLabel(action.label()), g);

            JButton button = new JButton(format(pending.get(action)));
            button.setHorizontalAlignment(SwingConstants.CENTER);
            button.addActionListener(e -> startRecording(action));
            buttons.put(action, button);

            g.gridx = 1;
            g.weightx = 1;
            g.fill = java.awt.GridBagConstraints.HORIZONTAL;
            rows.add(button, g);
            g.gridy++;
        }

        JButton reset = new JButton("Reset to Defaults");
        reset.addActionListener(e -> resetDefaults());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JButton save = new JButton("Save");
        save.addActionListener(e -> save());

        JPanel buttonBar = new JPanel(new BorderLayout());
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(cancel);
        right.add(save);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(reset);
        buttonBar.add(left, BorderLayout.WEST);
        buttonBar.add(right, BorderLayout.EAST);
        buttonBar.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 12));

        JLabel hint = new JLabel("Click a shortcut, then press the new key combination (Esc to cancel).");
        hint.setEnabled(false);
        hint.setBorder(BorderFactory.createEmptyBorder(0, 12, 6, 12));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        rows.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttonBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(rows);
        content.add(hint);
        content.add(Box.createVerticalGlue());
        content.add(buttonBar);
        return content;
    }

    // ---- recording ----

    private void startRecording(TermAction action) {
        stopRecording();
        recording = action;
        buttons.get(action).setText("Press shortcut…");
        recorder = e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED) {
                return true; // swallow release/typed while recording
            }
            if (isModifierKey(e.getKeyCode())) {
                return true; // wait for a non-modifier key
            }
            if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                cancelRecording();
                return true;
            }
            pending.put(action, KeyStroke.getKeyStrokeForEvent(e));
            buttons.get(action).setText(format(pending.get(action)));
            stopRecording();
            return true;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(recorder);
    }

    private void cancelRecording() {
        TermAction action = recording;
        stopRecording();
        if (action != null) {
            buttons.get(action).setText(format(pending.get(action)));
        }
    }

    private void stopRecording() {
        if (recorder != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(recorder);
            recorder = null;
        }
        recording = null;
    }

    // ---- actions ----

    private void resetDefaults() {
        stopRecording();
        for (TermAction action : TermAction.values()) {
            KeyStroke ks = KeyStroke.getKeyStroke(action.defaultStroke());
            pending.put(action, ks);
            buttons.get(action).setText(format(ks));
        }
    }

    private void save() {
        stopRecording();
        TermAction conflict = findConflict();
        if (conflict != null) {
            JOptionPane.showMessageDialog(this,
                    "Two actions can't share the same shortcut (" + format(pending.get(conflict)) + ").",
                    "Shortcut conflict", JOptionPane.WARNING_MESSAGE);
            return;
        }
        for (TermAction action : TermAction.values()) {
            keymap.rebind(action, pending.get(action));
        }
        keymap.save();
        if (onSaved != null) {
            onSaved.run();
        }
        dispose();
    }

    /** Returns an action whose stroke collides with another's, or {@code null} if all unique. */
    private TermAction findConflict() {
        Map<KeyStroke, TermAction> seen = new java.util.HashMap<>();
        for (TermAction action : TermAction.values()) {
            KeyStroke ks = pending.get(action);
            if (ks == null) {
                continue;
            }
            TermAction prior = seen.put(ks, action);
            if (prior != null) {
                return action;
            }
        }
        return null;
    }

    @Override
    public void dispose() {
        stopRecording();
        super.dispose();
    }

    // ---- helpers ----

    private static boolean isModifierKey(int keyCode) {
        return keyCode == KeyEvent.VK_SHIFT || keyCode == KeyEvent.VK_CONTROL
                || keyCode == KeyEvent.VK_ALT || keyCode == KeyEvent.VK_ALT_GRAPH
                || keyCode == KeyEvent.VK_META;
    }

    private static String format(KeyStroke ks) {
        if (ks == null) {
            return "(unset)";
        }
        String mods = InputEvent.getModifiersExText(ks.getModifiers());
        String key = KeyEvent.getKeyText(ks.getKeyCode());
        return mods.isEmpty() ? key : mods + "+" + key;
    }
}
