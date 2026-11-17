// SPDX-License-Identifier: GPL-3.0-or-later
package live.ditto;

/** Synthetic test double; not the Ditto SDK. */
public final class Ditto {
    public int startCalls;
    public int stopCalls;
    public int closeCalls;
    public boolean failStart;
    public boolean failStop;
    public boolean failClose;
    public boolean localStoreOpen = true;

    public void startSync() {
        startCalls++;
        if (failStart) {
            throw new IllegalStateException("synthetic start failure");
        }
    }

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
        localStoreOpen = false;
    }

    public String readLocalStore() {
        if (!localStoreOpen) {
            throw new IllegalStateException("synthetic store is closed");
        }
        return "synthetic";
    }
}
