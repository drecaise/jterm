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
package com.katmoda.jterm.ui.rdp;

import com.formdev.flatlaf.util.UIScale;
import com.katmoda.jterm.config.AppSettings;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.RenderingHints;
import java.awt.Window;

/**
 * The bar of a full-screen RDP desktop: jterm's own component drawn over the remote picture, so the
 * remote side can neither hide it nor keep the user from leaving. Shows the session, the folders the
 * server can reach, and the pane bar's actions. When it shows is decided by {@link FullScreenReveal}.
 *
 * <p>The buttons never take focus, so the keyboard stays with the desktop. While hidden the bar is
 * invisible and takes no events from the desktop beneath it.</p>
 */
final class FullScreenBar extends JPanel {

    /** What the buttons do; each runs on the EDT. */
    interface Actions {
        void exitFullScreen();

        void ctrlAltDel();

        void windowsKey();

        void saveFiles();

        /** Opens the print-job dialog (it leaves full screen first). */
        void printJobs();

        void toggleSound();

        /** Opens the microphone menu below {@code invoker}. */
        void microphoneMenu(java.awt.Component invoker);

        /** Opens the smart-card menu below {@code invoker}. */
        void smartCardMenu(java.awt.Component invoker);
    }

    /** How long the bar shows on entry, so the user sees where it is and what is shared. */
    static final long ENTRY_FLASH_MS = 3_000;

    private final JLabel who = new JLabel();
    private final JLabel shared = new JLabel();
    private final JButton saveFiles = new JButton();
    private final JButton printJobs = new JButton();
    private final JButton sound = new JButton();
    private final JButton microphone = new JButton();
    private final JButton smartCard = new JButton();
    private final FullScreenReveal reveal = new FullScreenReveal();
    private final Timer timer = new Timer(100, e -> tick());
    private Window window;

    FullScreenBar(Actions actions, String exitShortcut) {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setBorder(BorderFactory.createEmptyBorder(UIScale.scale(3), UIScale.scale(8), UIScale.scale(3), UIScale.scale(8)));

        JToggleButton pin = new JToggleButton("Pin");
        pin.setToolTipText("Keep this bar shown");
        pin.setSelected(AppSettings.get().isRdpFullScreenBarPinned());
        reveal.setPinned(pin.isSelected());
        pin.addActionListener(e -> {
            reveal.setPinned(pin.isSelected());
            AppSettings.get().setRdpFullScreenBarPinned(pin.isSelected());
            AppSettings.get().save();
        });
        JButton ctrlAltDel = new JButton("Ctrl+Alt+Del");
        ctrlAltDel.setToolTipText("Send Ctrl+Alt+Del to the remote desktop");
        ctrlAltDel.addActionListener(e -> actions.ctrlAltDel());
        JButton win = new JButton("Win");
        win.setToolTipText("Press the Windows key on the remote desktop (opens Start)");
        win.addActionListener(e -> actions.windowsKey());
        saveFiles.setToolTipText("Leave full screen and save the files copied on the remote desktop");
        saveFiles.addActionListener(e -> actions.saveFiles());
        printJobs.addActionListener(e -> actions.printJobs());
        printJobs.setVisible(false);
        sound.addActionListener(e -> actions.toggleSound());
        sound.setVisible(false);
        microphone.addActionListener(e -> actions.microphoneMenu(microphone));
        microphone.setVisible(false);
        smartCard.addActionListener(e -> actions.smartCardMenu(smartCard));
        smartCard.setVisible(false);
        JButton exit = new JButton("Exit full screen");
        exit.setToolTipText(exitShortcut == null ? "Exit full screen" : "Exit full screen (" + exitShortcut + ")");
        exit.addActionListener(e -> actions.exitFullScreen());
        for (AbstractButton b : new AbstractButton[]{pin, ctrlAltDel, win, printJobs, saveFiles, sound, microphone, smartCard, exit}) {
            b.setFocusable(false);
            b.putClientProperty("JButton.buttonType", "toolBarButton");
        }

        add(pin);
        add(Box.createHorizontalStrut(UIScale.scale(8)));
        add(who);
        add(Box.createHorizontalStrut(UIScale.scale(12)));
        add(shared);
        add(Box.createHorizontalStrut(UIScale.scale(12)));
        add(printJobs);
        add(saveFiles);
        add(sound);
        add(microphone);
        add(smartCard);
        add(ctrlAltDel);
        add(win);
        add(exit);
        setVisible(false);
    }

    /**
     * Mirrors the pane's bar. {@code sharedText} null hides the indicator; {@code saveText} null
     * hides "Save files…"; {@code soundText} null hides the speaker button; {@code mic} null hides
     * the microphone button, which is red while the remote desktop hears the microphone (A3);
     * {@code printText} null hides the print-job button; {@code cardText} null hides the smart-card
     * button, which is red while the remote desktop holds a card ({@code cardInUse}).
     */
    void refresh(String whoText, String sharedText, String sharedTip, String saveText, boolean saveEnabled,
                 String soundText, String soundTip, MicButtonLook mic, String printText, String printTip,
                 String cardText, String cardTip, boolean cardInUse) {
        smartCard.setVisible(cardText != null);
        smartCard.setText(cardText);
        smartCard.setToolTipText(cardTip);
        smartCard.setForeground(cardInUse ? new Color(MicButtonLook.RECORDING_RGB) : null);
        printJobs.setVisible(printText != null);
        printJobs.setText(printText);
        printJobs.setToolTipText(printTip);
        microphone.setVisible(mic != null);
        if (mic != null) {
            microphone.setText(mic.text());
            microphone.setToolTipText(mic.tip());
            microphone.setForeground(mic.recording() ? new Color(MicButtonLook.RECORDING_RGB) : null);
        }
        sound.setVisible(soundText != null);
        sound.setText(soundText);
        sound.setToolTipText(soundTip);
        who.setText(whoText);
        shared.setVisible(sharedText != null);
        shared.setText(sharedText);
        shared.setToolTipText(sharedTip);
        saveFiles.setVisible(saveText != null);
        saveFiles.setText(saveText);
        saveFiles.setEnabled(saveEnabled);
        if (getParent() != null) {
            setSize(getPreferredSize());
            getParent().doLayout();
        }
    }

    /** Starts following the pointer in {@code window}, showing the bar for a few seconds first. */
    void start(Window window) {
        this.window = window;
        reveal.flash(System.currentTimeMillis() + ENTRY_FLASH_MS);
        setVisible(true);
        timer.start();
    }

    void stop() {
        timer.stop();
        setVisible(false);
        window = null;
    }

    private void tick() {
        JComponent host = (JComponent) getParent();
        if (window == null || host == null) {
            return;
        }
        Point p = null;
        // Only while the window is in front: the pointer over another app is not "at our edge".
        PointerInfo info = window.isActive() ? MouseInfo.getPointerInfo() : null;
        if (info != null) {
            p = info.getLocation();
            SwingUtilities.convertPointFromScreen(p, host);
            if (p.x < 0 || p.y < 0 || p.x >= host.getWidth() || p.y >= host.getHeight()) {
                p = null;
            }
        }
        boolean show = reveal.update(System.currentTimeMillis(), p, host.getWidth(), getBounds());
        if (show != isVisible()) {
            setVisible(show);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color bg = UIManager.getColor("Panel.background");
        Color line = UIManager.getColor("Component.borderColor");
        int arc = UIScale.scale(12);
        g2.setColor(bg != null ? bg : Color.DARK_GRAY);
        g2.fillRoundRect(0, -arc, getWidth() - 1, getHeight() - 1 + arc, arc, arc);
        if (line != null) {
            g2.setColor(line);
            g2.drawRoundRect(0, -arc, getWidth() - 1, getHeight() - 1 + arc, arc, arc);
        }
        g2.dispose();
    }
}
