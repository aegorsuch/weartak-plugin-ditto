// SPDX-License-Identifier: GPL-3.0-or-later
package live.ditto.transports;

import live.ditto.transports.java.DittoLanConfig;
import live.ditto.transports.java.DittoWifiAwareConfig;

/** Synthetic test double; not the Ditto SDK. */
public final class DittoPeerToPeer {
    private final DittoBluetoothLeConfig bluetoothLe = new DittoBluetoothLeConfig();
    private final DittoLanConfig lan = new DittoLanConfig();
    private final DittoWifiAwareConfig wifiAware = new DittoWifiAwareConfig();

    public DittoBluetoothLeConfig getBluetoothLe() { return bluetoothLe; }
    public DittoLanConfig getLan() { return lan; }
    public DittoWifiAwareConfig getWifiAware() { return wifiAware; }
}
