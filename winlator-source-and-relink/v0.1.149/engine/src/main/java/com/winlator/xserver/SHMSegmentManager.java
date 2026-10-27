/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.xserver;

import android.util.SparseArray;
import android.util.SparseIntArray;

import com.winlator.sysvshm.SysVSharedMemory;

import java.nio.ByteBuffer;

public class SHMSegmentManager {
    private final SysVSharedMemory sysVSharedMemory;
    private final SparseArray<ByteBuffer> shmSegments = new SparseArray<>();
    private final SparseIntArray owners = new SparseIntArray();

    public SHMSegmentManager(SysVSharedMemory sysVSharedMemory) {
        this.sysVSharedMemory = sysVSharedMemory;
    }

    public boolean attach(int xid, int shmid, int owner) {
        if (shmSegments.indexOfKey(xid) >= 0) return false;
        ByteBuffer data = sysVSharedMemory.attach(shmid);
        if (data == null) return false;
        shmSegments.put(xid, data);
        owners.put(xid, owner);
        return true;
    }

    public void detach(int xid) {
        ByteBuffer data = shmSegments.get(xid);
        if (data != null) {
            sysVSharedMemory.detach(data);
            shmSegments.remove(xid);
            owners.delete(xid);
        }
    }

    /** Called under the X server lock after the client's drawables have been freed. */
    public void detachOwner(int owner) {
        for (int i = owners.size() - 1; i >= 0; i--)
            if (owners.valueAt(i) == owner) detach(owners.keyAt(i));
    }

    public void detachAll() {
        for (int i = shmSegments.size() - 1; i >= 0; i--) detach(shmSegments.keyAt(i));
    }

    public boolean bind(Drawable drawable, ByteBuffer data) {
        if (drawable.getData() == data) return true;
        if (!sysVSharedMemory.retain(data)) return false;
        drawable.setSharedData(data, () -> sysVSharedMemory.detach(data));
        return true;
    }

    public String diagnostic() { return sysVSharedMemory.diagnostic(); }

    public ByteBuffer getData(int xid) {
        return shmSegments.get(xid);
    }
}
