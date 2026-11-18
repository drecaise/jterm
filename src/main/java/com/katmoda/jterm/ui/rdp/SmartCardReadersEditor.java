package com.katmoda.jterm.ui.rdp;

import com.formdev.flatlaf.util.UIScale;
import com.katmoda.jterm.terminal.rdp.smartcard.ScardPolicy;
import com.katmoda.jterm.terminal.rdp.smartcard.SmartCards;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The session dialog's reader picker (smart-card design S1): a tick for each reader present now,
 * the saved ones that aren't plugged in, and a field to keep a reader by name. Only ticked readers
 * are ever visible to the remote desktop; none are ticked by default.
 */
public final class SmartCardReadersEditor extends JPanel {

    private final JPanel list = new JPanel();
    private final Map<String, JCheckBox> boxes = new LinkedHashMap<>();
    private final JTextField addName = new JTextField(18);
    private final JButton add = new JButton("Add");
    private final List<String> saved;
    private boolean editable = true;

    public SmartCardReadersEditor(List<String> saved) {
        super(new BorderLayout(0, UIScale.scale(4)));
        this.saved = new ArrayList<>();
        for (String s : saved) {
            this.saved.add(ScardPolicy.baseName(s));
        }
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        JLabel looking = new JLabel("Looking for readers…");
        looking.setEnabled(false);
        list.add(looking);
        add(list, BorderLayout.CENTER);

        JPanel addRow = new JPanel();
        addRow.setLayout(new BoxLayout(addRow, BoxLayout.X_AXIS));
        addName.setToolTipText("A reader that isn't plugged in now, by the name this computer gives it");
        add.addActionListener(e -> {
            String n = ScardPolicy.baseName(addName.getText());
            if (!n.isEmpty()) {
                addReader(n, false, true);
                addName.setText("");
                refreshList();
            }
        });
        addRow.add(addName);
        addRow.add(Box.createHorizontalStrut(UIScale.scale(6)));
        addRow.add(add);
        add(addRow, BorderLayout.SOUTH);
        setBorder(BorderFactory.createEmptyBorder());

        // PC/SC may have to start its service (socket activation): never on the EDT.
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() {
                return SmartCards.presentReaders();
            }

            @Override
            protected void done() {
                List<String> present;
                try {
                    present = get();
                } catch (Exception e) {
                    present = List.of();
                }
                for (String r : present) {
                    addReader(r, true, SmartCardReadersEditor.this.saved.contains(r));
                }
                for (String r : SmartCardReadersEditor.this.saved) {
                    addReader(r, false, true);
                }
                refreshList();
            }
        }.execute();
    }

    private void addReader(String name, boolean present, boolean ticked) {
        JCheckBox box = boxes.get(name);
        if (box == null) {
            box = new JCheckBox(name + (present ? "" : "  (not connected)"));
            box.setEnabled(editable);
            boxes.put(name, box);
        }
        if (ticked) {
            box.setSelected(true);
        }
    }

    private void refreshList() {
        list.removeAll();
        if (boxes.isEmpty()) {
            JLabel none = new JLabel("No smart-card reader found on this computer.");
            none.setEnabled(false);
            list.add(none);
        }
        for (JCheckBox b : boxes.values()) {
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            list.add(b);
        }
        revalidate();
        repaint();
    }

    /** The ticked readers' names, as a session saves them. Before the lookup ends: what was saved. */
    public List<String> readers() {
        if (boxes.isEmpty()) {
            return List.copyOf(saved);
        }
        List<String> out = new ArrayList<>();
        boxes.forEach((name, box) -> {
            if (box.isSelected()) {
                out.add(name);
            }
        });
        return out;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        editable = enabled;
        for (JCheckBox b : boxes.values()) {
            b.setEnabled(enabled);
        }
        addName.setEnabled(enabled);
        add.setEnabled(enabled);
    }
}
