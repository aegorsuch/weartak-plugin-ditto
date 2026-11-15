// SPDX-License-Identifier: GPL-3.0-or-later
package live.ditto.transports.java;

import java.util.HashSet;
import java.util.Set;

/** Synthetic test double; not the Ditto SDK. */
public class DittoConnect {
    private Set<String> websocketUrls = new HashSet<>();
    private Set<String> tcpServers = new HashSet<>();
    private long retryIntervalMs;

    public Set<String> getWebsocketUrls() { return websocketUrls; }
    public void setWebsocketUrls(Set<String> urls) { websocketUrls = urls; }
    public Set<String> getTcpServers() { return tcpServers; }
    public void setTcpServers(Set<String> servers) { tcpServers = servers; }
    public long getRetryIntervalMs() { return retryIntervalMs; }
    public void setRetryIntervalMs(long interval) { retryIntervalMs = interval; }
}
