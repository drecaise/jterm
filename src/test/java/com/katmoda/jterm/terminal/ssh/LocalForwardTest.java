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
package com.katmoda.jterm.terminal.ssh;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real forward through MINA's own SSH server (in-process, password auth, forwarding allowed) to a
 * local echo server standing in for the RDP host.
 */
class LocalForwardTest {

    private SshServer server;
    private ServerSocket echo;
    private SshClient client;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve("hostkey")));
        server.setPasswordAuthenticator((user, password, session) -> "secret".equals(password));
        server.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        server.start();

        echo = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!echo.isClosed()) {
                try (Socket s = echo.accept()) {
                    s.getInputStream().transferTo(s.getOutputStream());
                } catch (IOException ignored) {
                    // closed at the end of the test
                }
            }
        }, "echo");
        t.setDaemon(true);
        t.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.stop();
        }
        echo.close();
        server.stop(true);
    }

    private SshConnect.Connected connect() throws IOException {
        client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.start();
        ClientSession session = client.connect("tester", "127.0.0.1", server.getPort())
                .verify(Duration.ofSeconds(10)).getSession();
        session.addPasswordIdentity("secret");
        session.auth().verify(Duration.ofSeconds(10));
        return new SshConnect.Connected(client, session, List.of());
    }

    private static String roundTrip(int port, String text) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(5000);
            byte[] sent = text.getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            out.write(sent);
            out.flush();
            // Read back exactly what was sent. No half-close: RDP never does one, and MINA's
            // forwarding channel turns the EOF into a full close before the echo returns.
            InputStream in = s.getInputStream();
            return new String(in.readNBytes(sent.length), StandardCharsets.UTF_8);
        }
    }

    @Test
    void bytesReachTheDestinationThroughTheSshServerAndComeBack() throws Exception {
        SshConnect.Connected conn = connect();
        try (LocalForward fwd = LocalForward.open(conn, "127.0.0.1", echo.getLocalPort())) {
            assertEquals("127.0.0.1", fwd.localHost());
            assertTrue(fwd.localPort() > 0);
            assertEquals("hello through the bastion", roundTrip(fwd.localPort(), "hello through the bastion"));
            // FreeRDP opens several connections (negotiation retries, reconnect): the forward stays up.
            assertEquals("again", roundTrip(fwd.localPort(), "again"));
        }
    }

    @Test
    void closingStopsTheListenerAndTheConnectionAndCanBeRepeated() throws Exception {
        SshConnect.Connected conn = connect();
        LocalForward fwd = LocalForward.open(conn, "127.0.0.1", echo.getLocalPort());
        int port = fwd.localPort();
        fwd.close();
        fwd.close(); // idempotent
        assertTrue(fwd.isClosed());
        assertFalse(conn.session().isOpen(), "the SSH connection is closed with the forward");
        assertThrows(ConnectException.class, () -> roundTrip(port, "x"));
    }
}
