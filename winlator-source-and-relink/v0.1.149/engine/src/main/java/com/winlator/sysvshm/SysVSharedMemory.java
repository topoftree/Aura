/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.sysvshm;

import com.winlator.xconnector.XConnectorEpoll;

import java.nio.ByteBuffer;

public class SysVSharedMemory {
    private final SharedMemoryRegistry registry = new SharedMemoryRegistry(new SharedMemoryRegistry.Memory() {
        public int create(int id, long size) { return ashmemCreateRegion(id, size); }
        public ByteBuffer map(int fd, long size) { return mapSHMSegment(fd, size, 0, true); }
        public void unmap(ByteBuffer data, long size) { unmapSHMSegment(data, size); }
        public void close(int fd) { XConnectorEpoll.closeFd(fd); }
    });

    static {
        System.loadLibrary("winlator");
    }

    public int getFd(int shmid) {
        return registry.getFd(shmid);
    }

    public int get(long size) {
        return registry.get(size);
    }

    public void delete(int shmid) {
        registry.delete(shmid);
    }

    public void deleteAll() {
        registry.close();
    }

    public ByteBuffer attach(int shmid) {
        return registry.attach(shmid);
    }

    public void detach(ByteBuffer data) {
        registry.detach(data);
    }

    public boolean retain(ByteBuffer data) { return registry.retain(data); }

    public String diagnostic() { return registry.diagnostic(); }

    public static native int createMemoryFd(String name, int size);

    private static native int ashmemCreateRegion(int index, long size);

    public static native ByteBuffer mapSHMSegment(int fd, long size, int offset, boolean readonly);

    public static native void unmapSHMSegment(ByteBuffer data, long size);
}
