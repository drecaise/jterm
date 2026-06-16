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

import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.agent.SshAgentFactory;
import org.apache.sshd.agent.SshAgentServer;
import org.apache.sshd.agent.local.ChannelAgentForwardingFactory;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.session.ConnectionService;
import org.apache.sshd.common.session.Session;

import java.io.IOException;
import java.util.List;

/**
 * {@link SshAgentFactory} that talks to the local OS ssh-agent through {@link AgentSupport}
 * — a JDK Unix-domain socket on Linux/macOS, a named pipe on Windows. No Apache APR needed.
 *
 * <p>Agent forwarding uses the APR-free {@code agent.local} channel factories, whose
 * forwarding channel relays to {@link #createClient}.</p>
 */
public final class JdkAgentFactory implements SshAgentFactory {

    private static final List<ChannelFactory> FORWARDING_CHANNELS = List.of(
            ChannelAgentForwardingFactory.OPENSSH,
            ChannelAgentForwardingFactory.IETF);

    @Override
    public List<ChannelFactory> getChannelForwardingFactories(FactoryManager manager) {
        return FORWARDING_CHANNELS;
    }

    @Override
    public SshAgent createClient(Session session, FactoryManager manager) throws IOException {
        return AgentSupport.open(manager.getString(SshAgent.SSH_AUTHSOCKET_ENV_NAME));
    }

    @Override
    public SshAgentServer createServer(ConnectionService service) throws IOException {
        throw new UnsupportedOperationException("jterm acts only as an SSH client");
    }
}
