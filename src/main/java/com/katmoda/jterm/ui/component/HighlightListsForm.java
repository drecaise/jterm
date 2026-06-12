package com.katmoda.jterm.ui.component;

import com.katmoda.jterm.highlight.HighlightLibrary;
import com.katmoda.jterm.highlight.HighlightList;
import com.katmoda.jterm.highlight.HighlightRule;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable editor for the named highlight lists: a list of lists on the left
 * (New / Rename / Delete) and, on the right, the selected list's rules in a table with an editable
 * regex column and a color swatch column (double-click to pick). Edits happen on a working copy;
 * {@link #commit()} writes it back to {@link HighlightLibrary}. Used by the Preferences dialog.
 */
public final class HighlightListsForm {

    private final List<HighlightList> workingLists = new ArrayList<>();
    private final DefaultListModel<HighlightList> listModel = new DefaultListModel<>();
    private final JList<HighlightList> listView = new JList<>(listModel);
    private final RulesTableModel rulesModel = new RulesTableModel();
    private final JTable rulesTable = new JTable(rulesModel);
    private final JComponent root;

    private JButton addRule;
    private JButton removeRule;
    private JButton renameList;
    private JButton deleteList;
    private Runnable onListsChanged;

    public HighlightListsForm() {
        for (HighlightList list : HighlightLibrary.get().lists()) {
            workingLists.add(list.copy());
        }
        this.root = build();
        for (HighlightList list : workingLists) {
            listModel.addElement(list);
        }
        if (!listModel.isEmpty()) {
            listView.setSelectedIndex(0);
        }
        syncSelection();
    }

    public JComponent component() {
        return root;
    }

    /** The current (possibly edited) lists; useful for keeping an external combo in sync. */
    public List<HighlightList> currentLists() {
        return workingLists;
    }

    /** Invoked whenever a list is added, renamed or removed (the set of lists changed). */
    public void setOnListsChanged(Runnable onListsChanged) {
        this.onListsChanged = onListsChanged;
    }

    /** Writes the working copy back to the library and persists it. */
    public void commit() {
        if (rulesTable.isEditing()) {
            rulesTable.getCellEditor().stopCellEditing();
        }
        HighlightLibrary.get().replaceAll(workingLists);
        HighlightLibrary.get().save();
    }

    private JComponent build() {
        listView.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        listView.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                syncSelection();
            }
        });

        JButton newList = new JButton("New…");
        newList.addActionListener(e -> newList());
        renameList = new JButton("Rename…");
        renameList.addActionListener(e -> renameList());
        deleteList = new JButton("Delete");
        deleteList.addActionListener(e -> deleteList());
        JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        listButtons.add(newList);
        listButtons.add(renameList);
        listButtons.add(deleteList);

        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.add(new JScrollPane(listView), BorderLayout.CENTER);
        left.add(listButtons, BorderLayout.SOUTH);
        left.setBorder(BorderFactory.createTitledBorder("Lists"));

        rulesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        rulesTable.getColumnModel().getColumn(1).setMaxWidth(120);
        rulesTable.getColumnModel().getColumn(1).setCellRenderer(new SwatchRenderer());
        rulesTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = rulesTable.rowAtPoint(e.getPoint());
                    int col = rulesTable.columnAtPoint(e.getPoint());
                    if (row >= 0 && col == 1) {
                        pickColor(row);
                    }
                }
            }
        });

        addRule = new JButton("Add Rule");
        addRule.addActionListener(e -> addRule());
        removeRule = new JButton("Remove Rule");
        removeRule.addActionListener(e -> removeRule());
        JPanel ruleButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        ruleButtons.add(addRule);
        ruleButtons.add(removeRule);
        ruleButtons.add(hint("Double-click a color to change it."));

        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.add(new JScrollPane(rulesTable), BorderLayout.CENTER);
        right.add(ruleButtons, BorderLayout.SOUTH);
        right.setBorder(BorderFactory.createTitledBorder("Rules"));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.35);
        split.setBorder(null);
        split.setPreferredSize(new Dimension(560, 280));
        return split;
    }

    private HighlightList selectedList() {
        return listView.getSelectedValue();
    }

    private void syncSelection() {
        HighlightList list = selectedList();
        rulesModel.setRules(list != null ? list.getRules() : null);
        boolean hasList = list != null;
        renameList.setEnabled(hasList);
        deleteList.setEnabled(hasList);
        addRule.setEnabled(hasList);
        removeRule.setEnabled(hasList);
    }

    private void newList() {
        String name = JOptionPane.showInputDialog(root, "List name:", "New List",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) {
            return;
        }
        HighlightList list = new HighlightList(name.trim());
        workingLists.add(list);
        listModel.addElement(list);
        listView.setSelectedValue(list, true);
        listsChanged();
    }

    private void renameList() {
        HighlightList list = selectedList();
        if (list == null) {
            return;
        }
        String name = (String) JOptionPane.showInputDialog(root, "List name:", "Rename List",
                JOptionPane.PLAIN_MESSAGE, null, null, list.getName());
        if (name == null || name.isBlank()) {
            return;
        }
        list.setName(name.trim());
        listView.repaint();
        listsChanged();
    }

    private void deleteList() {
        HighlightList list = selectedList();
        if (list == null) {
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(root,
                "Delete list \"" + list.getName() + "\"?", "Delete List",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.OK_OPTION) {
            return;
        }
        int index = listView.getSelectedIndex();
        workingLists.remove(list);
        listModel.removeElement(list);
        if (!listModel.isEmpty()) {
            listView.setSelectedIndex(Math.min(index, listModel.size() - 1));
        }
        syncSelection();
        listsChanged();
    }

    private void addRule() {
        HighlightList list = selectedList();
        if (list == null) {
            return;
        }
        list.getRules().add(new HighlightRule("", "#FF0000"));
        rulesModel.fireTableRowsInserted(list.getRules().size() - 1, list.getRules().size() - 1);
        int row = list.getRules().size() - 1;
        rulesTable.setRowSelectionInterval(row, row);
        rulesTable.editCellAt(row, 0);
    }

    private void removeRule() {
        HighlightList list = selectedList();
        int row = rulesTable.getSelectedRow();
        if (list == null || row < 0) {
            return;
        }
        if (rulesTable.isEditing()) {
            rulesTable.getCellEditor().cancelCellEditing();
        }
        list.getRules().remove(row);
        rulesModel.fireTableRowsDeleted(row, row);
    }

    private void pickColor(int row) {
        HighlightList list = selectedList();
        if (list == null || row >= list.getRules().size()) {
            return;
        }
        HighlightRule rule = list.getRules().get(row);
        Color current = safeDecode(rule.getColorHex());
        Color picked = JColorChooser.showDialog(root, "Highlight Color", current);
        if (picked != null) {
            rule.setColorHex(String.format("#%06X", picked.getRGB() & 0xFFFFFF));
            rulesModel.fireTableRowsUpdated(row, row);
        }
    }

    private void listsChanged() {
        if (onListsChanged != null) {
            onListsChanged.run();
        }
    }

    private static Color safeDecode(String hex) {
        try {
            return Color.decode(hex);
        } catch (NumberFormatException e) {
            return Color.WHITE;
        }
    }

    private static JLabel hint(String text) {
        JLabel hint = new JLabel(text);
        hint.setEnabled(false);
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1f));
        return hint;
    }

    /** Two-column model over a list's rules: editable regex + a color (shown as a swatch). */
    private static final class RulesTableModel extends AbstractTableModel {
        private List<HighlightRule> rules = new ArrayList<>();

        void setRules(List<HighlightRule> rules) {
            this.rules = (rules != null) ? rules : new ArrayList<>();
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rules.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Pattern (regex)" : "Color";
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0;
        }

        @Override
        public Object getValueAt(int row, int column) {
            HighlightRule rule = rules.get(row);
            return column == 0 ? rule.getPattern() : rule.getColorHex();
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 0) {
                rules.get(row).setPattern(value != null ? value.toString() : "");
            }
        }
    }

    /** Paints the color cell as a filled swatch labelled with its hex value. */
    private static final class SwatchRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            String hex = value != null ? value.toString() : "#FFFFFF";
            setBackground(safeDecode(hex));
            setText(hex);
            setHorizontalAlignment(CENTER);
            setForeground(contrast(safeDecode(hex)));
            return this;
        }

        private static Color contrast(Color c) {
            double luminance = (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue());
            return luminance > 140 ? Color.BLACK : Color.WHITE;
        }
    }
}
