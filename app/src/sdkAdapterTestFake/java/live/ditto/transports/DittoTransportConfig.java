// SPDX-License-Identifier: GPL-3.0-or-later
package live.ditto.transports;

import live.ditto.transports.java.DittoConnect;

/** Synthetic test double; not the Ditto SDK. */
public final class DittoTransportConfig {
    private final DittoPeerToPeer peerToPeer = new DittoPeerToPeer();
    private final DittoConnect connect = new DittoConnect();

    public DittoPeerToPeer getPeerToPeer() { return peerToPeer; }
    public DittoConnect getConnect() { return connect; }
}
