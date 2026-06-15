package com.katmoda.jterm.terminal.ssh.agent;

import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.agent.SshAgentKeyConstraint;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.session.SessionContext;

import java.io.IOException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An {@link SshAgent} that fronts several real agents at once and presents their identities as a
 * single set. Used on Windows, where the OpenSSH agent (named pipe) and Pageant
 * ({@link PageantAgentProxy}) can both be running — e.g. an empty OpenSSH agent service alongside
 * Pageant holding the actual key. {@link #getIdentities()} merges every delegate's keys
 * (de-duplicated by fingerprint) and {@link #sign} is routed to the delegate that owns the key,
 * so it doesn't matter which agent a key lives in.
 *
 * <p>jterm is a read/sign-only client, so the mutating operations are unsupported.</p>
 */
public final class CompositeSshAgent implements SshAgent {

    private final List<SshAgent> delegates;
    /** fingerprint -> delegate that listed it; rebuilt by {@link #getIdentities()}. */
    private final Map<String, SshAgent> signRouting = new LinkedHashMap<>();
    private volatile boolean open = true;

    public CompositeSshAgent(List<SshAgent> delegates) {
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public synchronized Iterable<? extends Map.Entry<PublicKey, String>> getIdentities() throws IOException {
        List<Map.Entry<PublicKey, String>> merged = new ArrayList<>();
        signRouting.clear();
        for (SshAgent delegate : delegates) {
            try {
                for (Map.Entry<PublicKey, String> id : delegate.getIdentities()) {
                    String fp = KeyUtils.getFingerPrint(id.getKey());
                    if (signRouting.putIfAbsent(fp, delegate) == null) {
                        merged.add(new AbstractMap.SimpleImmutableEntry<>(id.getKey(), id.getValue()));
                    }
                }
            } catch (IOException e) {
                // One unreachable agent must not blind the others.
                System.err.println("[ssh-agent] failed to list identities from an agent: " + e.getMessage());
            }
        }
        return merged;
    }

    @Override
    public Map.Entry<String, byte[]> sign(SessionContext session, PublicKey key, String algo, byte[] data)
            throws IOException {
        return routeFor(key).sign(session, key, algo, data);
    }

    private synchronized SshAgent routeFor(PublicKey key) throws IOException {
        String fp = KeyUtils.getFingerPrint(key);
        SshAgent target = signRouting.get(fp);
        if (target == null) {
            getIdentities(); // (re)populate the routing table, then retry
            target = signRouting.get(fp);
        }
        if (target == null) {
            throw new IOException("No ssh-agent holds the requested key (" + fp + ")");
        }
        return target;
    }

    @Override
    public void addIdentity(KeyPair key, String comment, SshAgentKeyConstraint... constraints) {
        throw new UnsupportedOperationException("jterm does not add agent identities");
    }

    @Override
    public void removeIdentity(PublicKey key) {
        throw new UnsupportedOperationException("jterm does not modify agent identities");
    }

    @Override
    public void removeAllIdentities() {
        throw new UnsupportedOperationException("jterm does not modify agent identities");
    }

    @Override
    public boolean isOpen() {
        if (!open) {
            return false;
        }
        for (SshAgent delegate : delegates) {
            if (delegate.isOpen()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        open = false;
        IOException first = null;
        for (SshAgent delegate : delegates) {
            try {
                delegate.close();
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
