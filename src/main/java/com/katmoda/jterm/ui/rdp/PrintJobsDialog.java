package com.katmoda.jterm.ui.rdp;

import com.formdev.flatlaf.util.UIScale;
import com.katmoda.jterm.terminal.rdp.drive.OriginMark;
import com.katmoda.jterm.terminal.rdp.print.PdfDocuments;
import com.katmoda.jterm.terminal.rdp.print.PrintJob;
import com.katmoda.jterm.terminal.rdp.print.PrintJobQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.print.PrinterJob;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * The print jobs waiting in one RDP pane (printer design): each with its page count, size and a
 * thumbnail of page 1, and Print… (the OS print dialog), Save as PDF… and Discard. Opened only by the
 * user, from the pane's bar, through {@code RdpPane.dialogParent()} so it never appears over a
 * full-screen desktop. A job PDFBox couldn't read within its limits can be saved, not printed.
 */
final class PrintJobsDialog extends JDialog {

    private static final Logger LOG = LoggerFactory.getLogger(PrintJobsDialog.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final PrintJobQueue queue;
    private final String sessionName;
    private final Path scratch;
    private final Consumer<String> printed;
    private final DefaultListModel<PrintJob> model = new DefaultListModel<>();
    private final JList<PrintJob> list = new JList<>(model);
    private final JButton print = new JButton("Print…");
    private final JButton save = new JButton("Save as PDF…");
    private final JButton discard = new JButton("Discard");
    private final JLabel note = new JLabel(" ");
    private boolean busy;
    private static File lastSaveFolder;

    /**
     * {@code printed} hears "Printed: 3 pages on X" after a successful print, for the pane's bar;
     * {@code scratch} is PDFBox's scratch folder.
     */
    PrintJobsDialog(Component parent, PrintJobQueue queue, String sessionName, Path scratch, Consumer<String> printed) {
        super(parent == null ? null : SwingUtilities.getWindowAncestor(parent), "Print jobs from " + sessionName,
                Dialog.ModalityType.DOCUMENT_MODAL);
        this.queue = queue;
        this.sessionName = sessionName;
        this.scratch = scratch;
        this.printed = printed;

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        list.addListSelectionListener(e -> updateButtons());
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new java.awt.Dimension(UIScale.scale(460), UIScale.scale(320)));

        print.addActionListener(e -> printSelected());
        save.addActionListener(e -> saveSelected());
        discard.addActionListener(e -> discardSelected());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(print);
        buttons.add(save);
        buttons.add(discard);
        buttons.add(close);

        note.setEnabled(false);
        JPanel south = new JPanel(new BorderLayout());
        south.add(note, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.SOUTH);

        JLabel intro = new JLabel("<html>The remote desktop printed these to \"jterm\". Nothing is printed "
                + "or saved until you choose.</html>");
        intro.setBorder(BorderFactory.createEmptyBorder(0, 0, UIScale.scale(6), 0));
        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(10), UIScale.scale(10), UIScale.scale(6), UIScale.scale(10)));
        content.add(intro, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(print);
        refresh();
        pack();
        setLocationRelativeTo(parent);
    }

    /** EDT. Re-reads the queue (a job arrived, left, or got its preview). */
    void refresh() {
        PrintJob selected = list.getSelectedValue();
        model.clear();
        for (PrintJob j : queue.jobs()) {
            model.addElement(j);
        }
        if (selected != null && model.contains(selected)) {
            list.setSelectedValue(selected, true);
        } else if (!model.isEmpty()) {
            list.setSelectedIndex(model.size() - 1);
        }
        updateButtons();
        if (model.isEmpty() && !busy) {
            dispose();
        }
    }

    private void updateButtons() {
        PrintJob j = list.getSelectedValue();
        PrintJob.Preview p = j != null ? j.preview() : null;
        print.setEnabled(!busy && p != null && p.printable());
        save.setEnabled(!busy && j != null);
        discard.setEnabled(!busy && j != null);
        note.setText(j == null ? " " : p == null ? "Reading the document…" : p.problem() != null ? p.problem() : " ");
    }

    private void printSelected() {
        PrintJob j = list.getSelectedValue();
        if (j == null || j.preview() == null || !j.preview().printable()) {
            return;
        }
        PrinterJob pj = PrinterJob.getPrinterJob();
        pj.setJobName(sessionName + " (remote desktop)");
        if (!pj.printDialog()) {
            return;
        }
        busy = true;
        updateButtons();
        note.setText("Printing…");
        String printer = pj.getPrintService() != null ? pj.getPrintService().getName() : "the printer";
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                PdfDocuments.print(j.file(), scratch, pj);
                return null;
            }

            @Override
            protected void done() {
                busy = false;
                try {
                    get();
                    printed.accept("Printed: " + pages(j) + " on " + printer);
                    queue.remove(j); // refresh follows through the queue's listener
                } catch (Exception e) {
                    LOG.warn("printing a remote print job failed", e);
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    JOptionPane.showMessageDialog(PrintJobsDialog.this, "Printing failed: " + cause.getMessage(),
                            getTitle(), JOptionPane.ERROR_MESSAGE);
                }
                refresh();
            }
        }.execute();
    }

    private void saveSelected() {
        PrintJob j = list.getSelectedValue();
        if (j == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser(lastSaveFolder);
        chooser.setDialogTitle("Save print job as PDF");
        chooser.setFileFilter(new FileNameExtensionFilter("PDF documents", "pdf"));
        chooser.setSelectedFile(new File(suggestedName(j)));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = chooser.getSelectedFile();
        if (!f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            f = new File(f.getParentFile(), f.getName() + ".pdf");
        }
        Path dest = f.toPath();
        if (Files.exists(dest, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (JOptionPane.showConfirmDialog(this, f.getName() + " already exists. Replace it?", getTitle(),
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                return;
            }
        }
        try {
            Files.copy(j.file(), dest, StandardCopyOption.REPLACE_EXISTING);
            OriginMark.forThisPlatform().mark(dest); // it came from the remote computer: the OS warns
            lastSaveFolder = f.getParentFile();
            queue.remove(j);
        } catch (Exception e) {
            LOG.warn("saving a remote print job failed", e);
            JOptionPane.showMessageDialog(this, "Could not save: " + e.getMessage(), getTitle(), JOptionPane.ERROR_MESSAGE);
        }
        refresh();
    }

    private void discardSelected() {
        PrintJob j = list.getSelectedValue();
        if (j != null) {
            queue.remove(j);
            refresh();
        }
    }

    private String suggestedName(PrintJob j) {
        String base = sessionName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        return (base.isEmpty() ? "print" : base) + " print " + TIME.format(j.received()).replace(':', '-') + ".pdf";
    }

    private static String pages(PrintJob j) {
        PrintJob.Preview p = j.preview();
        if (p == null || p.pages() < 1) {
            return "a document";
        }
        return p.pages() == 1 ? "1 page" : String.format("%,d pages", p.pages());
    }

    static String humanSize(long bytes) {
        if (bytes < 1024 * 1024) {
            return Math.max(1, bytes / 1024) + " KB";
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Thumbnail, then "3 pages · 45 KB · 11:44:25". */
    private static final class Renderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean sel, boolean focus) {
            super.getListCellRendererComponent(l, value, index, sel, focus);
            PrintJob j = (PrintJob) value;
            PrintJob.Preview p = j.preview();
            String what = p == null ? "Reading…" : p.problem() != null && p.pages() < 1 ? "Unreadable" : pages(j);
            setText("<html><b>" + what + "</b><br>" + humanSize(j.bytes()) + " · received " + TIME.format(j.received())
                    + "</html>");
            if (p != null && p.thumbnail() != null) {
                int w = UIScale.scale(48);
                int h = Math.max(1, p.thumbnail().getHeight() * w / Math.max(1, p.thumbnail().getWidth()));
                setIcon(new ImageIcon(p.thumbnail().getScaledInstance(w, Math.min(h, w * 3), Image.SCALE_SMOOTH)));
            } else {
                setIcon(null);
            }
            setIconTextGap(UIScale.scale(10));
            setBorder(BorderFactory.createEmptyBorder(UIScale.scale(4), UIScale.scale(4), UIScale.scale(4), UIScale.scale(4)));
            return this;
        }
    }
}
