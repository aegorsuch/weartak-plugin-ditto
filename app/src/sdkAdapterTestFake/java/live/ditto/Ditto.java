// SPDX-License-Identifier: GPL-3.0-or-later
package live.ditto;

/** Synthetic test double; not the Ditto SDK. */
public final class Ditto {
    public int stopCalls;
    public int closeCalls;
    public boolean failStop;
    public boolean failClose;

    public void stopSync() {
        stopCalls++;
        if (failStop) {
            throw new IllegalStateException("synthetic stop failure");
        }
    }

    public void close() {
        closeCalls++;
        if (failClose) {
            throw new IllegalStateException("synthetic close failure");
        }
    }
}
