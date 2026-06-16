package com.katmoda.jterm.ui.sidebar;

import com.katmoda.jterm.icon.IconLibrary;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Modal icon picker: shows the {@link IconLibrary} choices as a compact, icon-only wrapping grid
 * (names shown as tooltips). Built-in icons are shown first; user-imported (custom) icons follow
 * under a labelled separator and each carries a small red delete badge in its top-right corner.
 * Buttons import a custom icon (PNG/JPG/GIF/SVG) and clear back to the type default. The grid wraps
 * to a fixed column count — there is never a horizontal scrollbar; a vertical one appears as needed.
 */
final class IconPickerDialog {

    private static final int ICON_SIZE = 22;
    private static final int CELL = 40;
    private static final int COLUMNS = 6;
    private static final int BADGE_SIZE = 14;
    private static final Color BADGE_BG = new Color(0xD9, 0x53, 0x4F);

    private String result; // null = cancelled, "" = clear, else icon id

    /** Invoked (on the EDT) with the id of a custom icon the user deleted from within the picker. */
    private final Consumer<String> onDelete;

    private IconPickerDialog(Consumer<String> onDelete) {
        this.onDelete = onDelete;
    }

    /**
     * @param onDelete invoked with the id of any custom icon the user deletes while the picker is
     *                 open, so callers can revert references to it (may be {@code null})
     * @return {@code null} if cancelled, {@code ""} to clear to default, or the chosen icon id
     */
    static String pick(Component parent, Consumer<String> onDelete) {
        return new IconPickerDialog(onDelete).show(parent);
    }

    private String show(Component parent) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent),
                "Choose Icon", Dialog.ModalityType.APPLICATION_MODAL);

        DefaultListModel<IconLibrary.Choice> builtinModel = new DefaultListModel<>();
        DefaultListModel<IconLibrary.Choice> customModel = new DefaultListModel<>();

        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "Search icons…");

        JList<IconLibrary.Choice> builtinList = newGrid(builtinModel, false);
        JList<IconLibrary.Choice> customList = newGrid(customModel, true);

        // Selecting in one grid clears the other so the highlighted choice is unambiguous.
        boolean[] syncing = {false};
        builtinList.addListSelectionListener(e -> {
            if (syncing[0] || e.getValueIsAdjusting() || builtinList.getSelectedIndex() < 0) return;
            syncing[0] = true;
            customList.clearSelection();
            syncing[0] = false;
        });
        customList.addListSelectionListener(e -> {
            if (syncing[0] || e.getValueIsAdjusting() || customList.getSelectedIndex() < 0) return;
            syncing[0] = true;
            builtinList.clearSelection();
            syncing[0] = false;
        });

        JLabel customLabel = new JLabel("Custom");
        customLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        customLabel.setBorder(BorderFactory.createEmptyBorder(10, 2, 2, 2));
        JSeparator separator = new JSeparator();
        separator.setAlignmentX(Component.LEFT_ALIGNMENT);
        separator.setMaximumSize(new Dimension(COLUMNS * CELL, 2));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(builtinList.getBackground());
        content.add(builtinList);
        content.add(separator);
        content.add(customLabel);
        content.add(customList);
        content.add(Box.createVerticalGlue());

        Runnable repopulate = () -> {
            String query = search.getText().trim().toLowerCase(Locale.ROOT);
            builtinModel.clear();
            customModel.clear();
            for (IconLibrary.Choice choice : IconLibrary.get().choices()) {
                if (!query.isEmpty()
                        && !choice.displayName().toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
                (IconLibrary.isCustom(choice.id()) ? customModel : builtinModel).addElement(choice);
            }
            sizeGrid(builtinList, builtinModel.size());
            sizeGrid(customList, customModel.size());
            boolean hasCustom = !customModel.isEmpty();
            separator.setVisible(hasCustom);
            customLabel.setVisible(hasCustom);
            customList.setVisible(hasCustom);
            content.revalidate();
            content.repaint();
        };
        repopulate.run();
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { repopulate.run(); }
            @Override public void removeUpdate(DocumentEvent e) { repopulate.run(); }
            @Override public void changedUpdate(DocumentEvent e) { repopulate.run(); }
        });

        builtinList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int index = indexAt(builtinList, e.getPoint());
                if (index >= 0) {
                    result = builtinModel.get(index).id();
                    dialog.dispose();
                }
            }
        });
        customList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int index = indexAt(customList, e.getPoint());
                if (index < 0) {
                    return;
                }
                IconLibrary.Choice choice = customModel.get(index);
                if (inDeleteBadge(customList, index, e.getPoint())) {
                    deleteCustom(dialog, choice, repopulate);
                } else {
                    result = choice.id();
                    dialog.dispose();
                }
            }
        });

        // Enter confirms the highlighted icon (keyboard navigation).
        bindEnter(builtinList, builtinModel, dialog);
        bindEnter(customList, customModel, dialog);

        JScrollPane scroll = new JScrollPane(content);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(CELL);
        scroll.setPreferredSize(new Dimension(COLUMNS * CELL + 28, 5 * CELL));

        JButton importBtn = new JButton("Import…");
        importBtn.addActionListener(e -> {
            if (importIcon(dialog)) {
                repopulate.run();
            }
        });
        JButton clearBtn = new JButton("Use Default");
        clearBtn.addActionListener(e -> {
            result = "";
            dialog.dispose();
        });
        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dialog.dispose());

        JPanel controls = new JPanel();
        controls.add(importBtn);
        controls.add(clearBtn);
        controls.add(cancelBtn);

        dialog.setLayout(new BorderLayout(8, 8));
        ((JPanel) dialog.getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        dialog.add(search, BorderLayout.NORTH);
        dialog.add(scroll, BorderLayout.CENTER);
        dialog.add(controls, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        SwingUtilities.invokeLater(search::requestFocusInWindow);
        dialog.setVisible(true); // blocks until disposed
        return result;
    }

    private JList<IconLibrary.Choice> newGrid(DefaultListModel<IconLibrary.Choice> model,
                                              boolean custom) {
        JList<IconLibrary.Choice> list = new JList<>(model);
        list.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        list.setVisibleRowCount(-1);
        list.setFixedCellWidth(CELL);
        list.setFixedCellHeight(CELL);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new IconCellRenderer(custom));
        list.setAlignmentX(Component.LEFT_ALIGNMENT);
        return list;
    }

    /** Sizes a fixed-cell grid to a whole number of {@link #COLUMNS}-wide rows for the BoxLayout. */
    private static void sizeGrid(JList<IconLibrary.Choice> list, int count) {
        int rows = (count + COLUMNS - 1) / COLUMNS;
        Dimension d = new Dimension(COLUMNS * CELL, rows * CELL);
        list.setPreferredSize(d);
        list.setMaximumSize(d);
        list.setMinimumSize(new Dimension(COLUMNS * CELL, 0));
    }

    private void bindEnter(JList<IconLibrary.Choice> list,
                           DefaultListModel<IconLibrary.Choice> model, JDialog dialog) {
        list.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "confirm");
        list.getActionMap().put("confirm", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                int index = list.getSelectedIndex();
                if (index >= 0) {
                    result = model.get(index).id();
                    dialog.dispose();
                }
            }
        });
    }

    /** The index of the cell actually under {@code p} (ignoring clicks in the wrap gutter). */
    private static int indexAt(JList<IconLibrary.Choice> list, Point p) {
        int index = list.locationToIndex(p);
        if (index < 0) {
            return -1;
        }
        Rectangle bounds = list.getCellBounds(index, index);
        return bounds != null && bounds.contains(p) ? index : -1;
    }

    /** The delete-badge rectangle for a custom cell, in list coordinates. */
    private static Rectangle badgeBounds(Rectangle cell) {
        return new Rectangle(cell.x + cell.width - BADGE_SIZE - 1, cell.y + 1, BADGE_SIZE, BADGE_SIZE);
    }

    private static boolean inDeleteBadge(JList<IconLibrary.Choice> list, int index, Point p) {
        Rectangle cell = list.getCellBounds(index, index);
        return cell != null && badgeBounds(cell).contains(p);
    }

    private void deleteCustom(JDialog dialog, IconLibrary.Choice choice, Runnable repopulate) {
        int ok = JOptionPane.showConfirmDialog(dialog,
                "Delete custom icon \"" + choice.displayName() + "\"?\n"
                        + "Folders and sessions using it will revert to their default icon.",
                "Delete Icon", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            return;
        }
        if (IconLibrary.get().deleteImported(choice.id()) && onDelete != null) {
            onDelete.accept(choice.id());
        }
        repopulate.run();
    }

    private boolean importIcon(JDialog dialog) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Images (png, jpg, gif, svg)", "png", "jpg", "jpeg", "gif", "svg"));
        if (chooser.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION) {
            return false;
        }
        return IconLibrary.get().importFile(chooser.getSelectedFile().toPath()) != null;
    }

    /**
     * Renders each choice as a centered icon with the name as a tooltip (no visible text). Custom
     * icons additionally get a small red delete badge painted in their top-right corner.
     */
    private static final class IconCellRenderer extends DefaultListCellRenderer {
        private final boolean custom;

        IconCellRenderer(boolean custom) {
            this.custom = custom;
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, "", index, selected, focused);
            if (value instanceof IconLibrary.Choice choice) {
                setText(null);
                setIcon(IconLibrary.get().icon(choice.id(), ICON_SIZE));
                setToolTipText(choice.displayName());
                setHorizontalAlignment(SwingConstants.CENTER);
                setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            }
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (!custom) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int bx = getWidth() - BADGE_SIZE - 1;
            int by = 1;
            g2.setColor(BADGE_BG);
            g2.fillOval(bx, by, BADGE_SIZE, BADGE_SIZE);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int pad = 4;
            g2.drawLine(bx + pad, by + pad, bx + BADGE_SIZE - pad, by + BADGE_SIZE - pad);
            g2.drawLine(bx + BADGE_SIZE - pad, by + pad, bx + pad, by + BADGE_SIZE - pad);
            g2.dispose();
        }
    }
}
