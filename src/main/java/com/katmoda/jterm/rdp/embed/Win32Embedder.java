package com.katmoda.jterm.rdp.embed;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import java.awt.Canvas;
import java.util.List;

/**
 * Windows embedder. {@code wfreerdp} has no parent-window option, so we launch it normally, wait
 * for its top-level window to appear, then reparent that window into the canvas with
 * {@code SetParent} — converting it to a borderless child window sized to fill the canvas.
 *
 * <p>Because the child belongs to a different process/thread, keyboard input won't reach it until
 * its input queue is merged with the AWT UI thread's via {@code AttachThreadInput}; we do that on
 * {@link #attach} and forward focus to it in {@link #focus}.</p>
 *
 * <p>This reparenting of a foreign top-level window is inherently a little fragile (it depends on
 * the child being the process's main visible window); it is implemented to spec here and must be
 * verified on a real Windows host.</p>
 */
final class Win32Embedder implements WindowEmbedder {

    private static final long FIND_TIMEOUT_MS = 10_000;
    private static final long POLL_INTERVAL_MS = 100;

    private volatile HWND child;
    private int attachedParentThread;
    private int attachedChildThread;
    private boolean threadsAttached;
    // The EDT thread we attach to the child so SetFocus(child) actually succeeds.
    private int focusThread;
    private boolean focusThreadAttached;

    @Override
    public boolean canEmbed() {
        return true;
    }

    @Override
    public List<String> launchArgs(Canvas canvas) {
        return List.of();
    }

    @Override
    public void attach(Canvas canvas, Process process) throws Exception {
        User32 user32 = User32.INSTANCE;
        HWND childWindow = waitForProcessWindow(user32, process.pid());
        if (childWindow == null) {
            throw new IllegalStateException("Timed out waiting for the FreeRDP window to appear");
        }
        this.child = childWindow;
        log("found FreeRDP window for pid " + process.pid());

        HWND parent = new HWND(new Pointer(Native.getComponentID(canvas)));

        // Turn the foreign top-level window into a borderless child of our canvas.
        int style = user32.GetWindowLong(childWindow, WinUser.GWL_STYLE);
        style &= ~(WinUser.WS_POPUP | WinUser.WS_CAPTION | WinUser.WS_THICKFRAME | WinUser.WS_BORDER);
        style |= WinUser.WS_CHILD;
        user32.SetWindowLong(childWindow, WinUser.GWL_STYLE, style);

        user32.SetParent(childWindow, parent);
        user32.ShowWindow(childWindow, WinUser.SW_SHOW);
        log("reparented into canvas " + canvas.getWidth() + "x" + canvas.getHeight());

        // Merge the FreeRDP window's input queue with the AWT UI thread that owns the parent canvas,
        // so keyboard input routes to the embedded window. Without this, the reparented foreign
        // window never receives key events.
        try {
            int parentThread = user32.GetWindowThreadProcessId(parent, null);
            int childThread = user32.GetWindowThreadProcessId(childWindow, null);
            boolean ok = parentThread != 0 && childThread != 0 && parentThread != childThread
                    && user32.AttachThreadInput(new DWORD(parentThread), new DWORD(childThread), true);
            if (ok) {
                this.attachedParentThread = parentThread;
                this.attachedChildThread = childThread;
                this.threadsAttached = true;
            }
            log("AttachThreadInput parent=" + parentThread + " child=" + childThread + " -> " + ok);
        } catch (Throwable t) {
            log("AttachThreadInput failed: " + t);
        }

        onResize(canvas, canvas.getWidth(), canvas.getHeight());
        // Don't focus here — this runs on the launch thread, which can't SetFocus. RdpTab calls
        // focus() on the EDT once connected.
    }

    @Override
    public void onResize(Canvas canvas, int width, int height) {
        HWND c = child;
        if (c != null) {
            boolean ok = User32.INSTANCE.MoveWindow(c, 0, 0, width, height, true);
            log("MoveWindow " + width + "x" + height + " -> " + ok);
        }
    }

    @Override
    public void focus() {
        HWND c = child;
        if (c == null) {
            return;
        }
        User32 user32 = User32.INSTANCE;
        // SetFocus only works if the calling thread's input queue is attached to the child's. This
        // runs on the EDT, so attach the EDT to the child once before focusing.
        if (!focusThreadAttached && attachedChildThread != 0) {
            int myThread = Kernel32.INSTANCE.GetCurrentThreadId();
            if (myThread != attachedChildThread && myThread != attachedParentThread) {
                boolean ok = user32.AttachThreadInput(
                        new DWORD(myThread), new DWORD(attachedChildThread), true);
                focusThread = myThread;
                focusThreadAttached = ok;
                log("focus-thread AttachThreadInput " + myThread + "->" + attachedChildThread
                        + " = " + ok);
            }
        }
        HWND prev = user32.SetFocus(c);
        log("SetFocus(child) prevFocus=" + prev);
    }

    private static void log(String message) {
        System.out.println("RDP-EMBED(win): " + message);
    }

    @Override
    public void detach() {
        if (focusThreadAttached) {
            try {
                User32.INSTANCE.AttachThreadInput(
                        new DWORD(focusThread), new DWORD(attachedChildThread), false);
            } catch (Throwable ignored) {
                // Best effort.
            }
            focusThreadAttached = false;
        }
        if (threadsAttached) {
            try {
                User32.INSTANCE.AttachThreadInput(
                        new DWORD(attachedParentThread), new DWORD(attachedChildThread), false);
            } catch (Throwable ignored) {
                // Process is going away anyway.
            }
            threadsAttached = false;
        }
    }

    /** Poll for the first visible top-level window owned by {@code pid}. */
    private HWND waitForProcessWindow(User32 user32, long pid) throws InterruptedException {
        long deadline = System.currentTimeMillis() + FIND_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            HWND found = findProcessWindow(user32, pid);
            if (found != null) {
                return found;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return null;
    }

    private HWND findProcessWindow(User32 user32, long pid) {
        HWND[] result = new HWND[1];
        user32.EnumWindows((hwnd, data) -> {
            IntByReference windowPid = new IntByReference();
            user32.GetWindowThreadProcessId(hwnd, windowPid);
            // EnumWindows yields only top-level windows, so a visible one owned by our process
            // is the FreeRDP main window.
            if (windowPid.getValue() == pid && user32.IsWindowVisible(hwnd)) {
                result[0] = hwnd;
                return false; // stop enumerating
            }
            return true;
        }, null);
        return result[0];
    }
}
