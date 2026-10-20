/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core;

import com.winlator.math.Mathf;
import com.winlator.xserver.XServer;

import java.util.Timer;
import java.util.TimerTask;

public class CursorLocker extends TimerTask {
    private final XServer xServer;
    private float damping = 0.25f;
    private short maxDistance;
    private boolean enabled = true;
    private final Object pauseLock = new Object();

    public CursorLocker(XServer xServer) {
        this.xServer = xServer;
        maxDistance = (short)(xServer.viewportInfo.width * 0.05f);
        Timer timer = new Timer();
        timer.schedule(this, 0, 1000 / 60);
    }

    public short getMaxDistance() {
        return maxDistance;
    }

    public void setMaxDistance(short maxDistance) {
        this.maxDistance = maxDistance;
    }

    public float getDamping() {
        return damping;
    }

    public void setDamping(float damping) {
        this.damping = damping;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (enabled) {
            synchronized (pauseLock) {
                this.enabled = true;
                pauseLock.notifyAll();
            }
        }
        else this.enabled = enabled;
    }

    @Override
    public void run() {
        synchronized (pauseLock) {
            if (!enabled) {
                try {
                    pauseLock.wait();
                }
                catch (InterruptedException e) {}
            }
        }

        short x = (short)Mathf.clamp(xServer.pointer.getX(), -maxDistance, xServer.viewportInfo.width + maxDistance);
        short y = (short)Mathf.clamp(xServer.pointer.getY(), -maxDistance, xServer.viewportInfo.height + maxDistance);

        if (x < 0) {
            xServer.pointer.setX((short)Math.ceil(x * damping));
        }
        else if (x >= xServer.viewportInfo.width) {
            xServer.pointer.setX((short)Math.floor(xServer.viewportInfo.width + (x - xServer.viewportInfo.width) * damping));
        }
        if (y < 0) {
            xServer.pointer.setY((short)Math.ceil(y * damping));
        }
        else if (y >= xServer.viewportInfo.height) {
            xServer.pointer.setY((short)Math.floor(xServer.viewportInfo.height + (y - xServer.viewportInfo.height) * damping));
        }
    }
}
