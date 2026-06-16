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
package com.katmoda.jterm.terminal.ssh.agent;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinUser.COPYDATASTRUCT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.apache.sshd.agent.common.AbstractAgentProxy;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.threads.ThreadUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ssh-agent client for PuTTY's Pageant on Windows. Unlike the OpenSSH agent (a named pipe,
 * handled by {@link WindowsPipeAgentProxy}, which KeeAgent also emulates), Pageant has no
 * pipe/socket: a request is placed in a named shared-memory mapping whose name is then handed to
 * Pageant's hidden window via a {@code WM_COPYDATA} message; Pageant writes the reply back into
 * the same mapping. As with the sibling proxies we only implement this transport and reuse
 * {@link AbstractAgentProxy} for the agent protocol itself.
 *
 * <p>The mapping is created with default security attributes. Pageant checks that the mapping's
 * owner matches its own user, which holds for the common case of jterm and Pageant running as
 * the same user at the same integrity level. (A naive client at a different elevation can fail
 * that check — the reference jsch-agent-proxy {@code PageantConnector} this follows has the same
 * limitation.)</p>
 */
public final class PageantAgentProxy extends AbstractAgentProxy {

    private static final String WINDOW_CLASS = "Pageant";
    private static final String WINDOW_TITLE = "Pageant";
    /** PuTTY's {@code AGENT_COPYDATA_ID}: marks a {@code WM_COPYDATA} as an ssh-agent request. */
    private static final long AGENT_COPYDATA_ID = 0x804e50baL;
    /** PuTTY's {@code AGENT_MAX_MSGLEN}: the fixed size of Pageant's shared-memory mailbox. */
    private static final int AGENT_MAX_MSGLEN = 8192;
    private static final int WM_COPYDATA = 0x004A;

    /** Disambiguates concurrent/successive mappings within this process. */
    private static final AtomicInteger MAP_COUNTER = new AtomicInteger();

    /**
     * The two {@code user32} entry points we need, declared so JNA marshals the
     * {@link COPYDATASTRUCT} by reference for {@code SendMessage} (the {@code jna-platform}
     * {@code User32} only offers the generic {@code LPARAM} overload).
     */
    public interface User32Ext extends StdCallLibrary {
        User32Ext INSTANCE = Native.load("user32", User32Ext.class, W32APIOptions.DEFAULT_OPTIONS);

        HWND FindWindow(String className, String windowName);

        LRESULT SendMessage(HWND hWnd, int msg, WPARAM wParam, COPYDATASTRUCT lParam);
    }

    private volatile boolean open = true;

    public PageantAgentProxy() {
        super(ThreadUtils.newSingleThreadExecutor("jterm-ssh-agent"));
    }

    /** Whether a Pageant window is currently present. Windows-only; safe to call repeatedly. */
    public static boolean isPageantRunning() {
        try {
            return User32Ext.INSTANCE.FindWindow(WINDOW_CLASS, WINDOW_TITLE) != null;
        } catch (Throwable t) {
            // user32 not loadable (non-Windows) or any native failure -> treat as absent.
            return false;
        }
    }

    @Override
    protected Buffer request(Buffer buffer) throws IOException {
        HWND pageant = User32Ext.INSTANCE.FindWindow(WINDOW_CLASS, WINDOW_TITLE);
        if (pageant == null) {
            throw new IOException("Pageant is not running");
        }

        // The buffer is already prepared by AbstractAgentProxy: [uint32 length][payload].
        int n = buffer.available();
        if (n > AGENT_MAX_MSGLEN) {
            throw new IOException("ssh-agent request too large for Pageant (" + n + " > "
                    + AGENT_MAX_MSGLEN + ")");
        }

        String mapName = String.format("PageantRequest%08x%08x",
                Kernel32.INSTANCE.GetCurrentThreadId(), MAP_COUNTER.incrementAndGet());

        HANDLE mapping = Kernel32.INSTANCE.CreateFileMapping(
                WinBase.INVALID_HANDLE_VALUE, null, WinNT.PAGE_READWRITE, 0, AGENT_MAX_MSGLEN, mapName);
        if (mapping == null) {
            throw new IOException("CreateFileMapping failed (err " + Kernel32.INSTANCE.GetLastError() + ")");
        }

        Pointer view = null;
        byte[] nameBytes = mapName.getBytes(StandardCharsets.US_ASCII);
        // Kept reachable (and explicitly freed) for the duration of the synchronous send.
        try (Memory lpData = new Memory(nameBytes.length + 1L)) {
            view = Kernel32.INSTANCE.MapViewOfFile(mapping, WinNT.SECTION_MAP_WRITE, 0, 0, 0);
            if (view == null) {
                throw new IOException("MapViewOfFile failed (err " + Kernel32.INSTANCE.GetLastError() + ")");
            }
            view.write(0, buffer.array(), buffer.rpos(), n);

            lpData.write(0, nameBytes, 0, nameBytes.length);
            lpData.setByte(nameBytes.length, (byte) 0);

            COPYDATASTRUCT cds = new COPYDATASTRUCT();
            cds.dwData = new ULONG_PTR(AGENT_COPYDATA_ID);
            cds.cbData = nameBytes.length + 1;
            cds.lpData = lpData;
            cds.write();

            LRESULT res = User32Ext.INSTANCE.SendMessage(pageant, WM_COPYDATA, new WPARAM(0), cds);
            if (res == null || res.longValue() == 0) {
                throw new IOException("Pageant rejected the request "
                        + "(running as a different user or elevation than Pageant?)");
            }
            return readReply(view);
        } finally {
            if (view != null) {
                Kernel32.INSTANCE.UnmapViewOfFile(view);
            }
            Kernel32.INSTANCE.CloseHandle(mapping);
        }
    }

    private static Buffer readReply(Pointer view) throws IOException {
        byte[] header = view.getByteArray(0, 4);
        long length = ((header[0] & 0xFFL) << 24) | ((header[1] & 0xFFL) << 16)
                | ((header[2] & 0xFFL) << 8) | (header[3] & 0xFFL);
        if (length < 0 || length > AGENT_MAX_MSGLEN - 4) {
            throw new IOException("Invalid Pageant reply length: " + length);
        }
        return new ByteArrayBuffer(view.getByteArray(4, (int) length));
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() throws IOException {
        open = false;
        super.close();
    }
}
