package com.katmoda.jterm.ui.sidebar;

import com.katmoda.jterm.icon.IconLibrary;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Modal icon picker: shows the {@link IconLibrary} choices as a compact, icon-only wrapping
 * grid (names shown as tooltips), with buttons to import a custom icon (PNG/JPG/GIF/SVG) and
 * to clear back to the type default. The list wraps to the available width — there is never a
 * horizontal scrollbar; a vertical one appears only when needed.
 */
final class IconPickerDialog {

    private static final int ICON_SIZE = 22;
    private static final int CELL = 40;

    private String result; // null = cancelled, "" = clear, else icon id

    private IconPickerDialog() {
    }

    /**
     * @return {@code null} if cancelled, {@code ""} to clear to default, or the chosen icon id
     */
    static String pick(Component parent) {
        return new IconPickerDialog().show(parent);
    }

    private String show(Component parent) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(parent),
                "Choose Icon", Dialog.ModalityType.APPLICATION_MODAL);

        DefaultListModel<IconLibrary.Choice> model = new DefaultListModel<>();
        IconLibrary.get().choices().forEach(model::addElement);

        JList<IconLibrary.Choice> list = new JList<>(model);
        list.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        list.setVisibleRowCount(-1); // wrap to width
        list.setFixedCellWidth(CELL);
        list.setFixedCellHeight(CELL);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new IconCellRenderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int index = list.locationToIndex(new Point(e.getX(), e.getY()));
                if (index >= 0 && list.getCellBounds(index, index).contains(e.getPoint())) {
                    result = model.get(index).id();
                    dialog.dispose();
                }
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setPreferredSize(new Dimension(6 * CELL + 24, 5 * CELL));

        JButton importBtn = new JButton("Import…");
        importBtn.addActionListener(e -> {
            if (importIcon(dialog)) {
                model.clear();
                IconLibrary.get().choices().forEach(model::addElement);
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
        dialog.add(scroll, BorderLayout.CENTER);
        dialog.add(controls, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true); // blocks until disposed
        return result;
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

    /** Renders each choice as a centered icon with the name as a tooltip (no visible text). */
    private static final class IconCellRenderer extends DefaultListCellRenderer {
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
    }
}
