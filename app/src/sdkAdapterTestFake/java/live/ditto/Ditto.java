// SPDX-License-Identifier: GPL-3.0-or-later
package live.ditto;

/** Synthetic test double; not the Ditto SDK. */
public final class Ditto {
    public int stopCalls;
    public boolean failStop;

    public void stopSync() {
        stopCalls++;
        if (failStop) {
            throw new IllegalStateException("synthetic stop failure");
        }
    }
}
