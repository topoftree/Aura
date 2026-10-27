/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.alsaserver;

import com.winlator.sysvshm.SysVSharedMemory;
import com.winlator.xconnector.ConnectedClient;
import com.winlator.xconnector.RequestHandler;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;

import java.io.IOException;
import java.nio.ByteBuffer;

public class ALSARequestHandler implements RequestHandler {
    private int maxSHMemoryId = 0;

    @Override
    public boolean handleRequest(ConnectedClient client) throws IOException {
        ALSAClient alsaClient = (ALSAClient)client.getTag();
        XInputStream inputStream = client.getInputStream();
        XOutputStream outputStream = client.getOutputStream();

        if (inputStream.available() < 5) return false;
        byte requestCode = inputStream.readByte();
        int requestLength = inputStream.readInt();
        AlsaProtocol.validateHeader(requestCode, requestLength);
        if (requestCode != RequestCodes.WRITE && inputStream.available() < requestLength) return false;

        try {
        switch (requestCode) {
            case RequestCodes.CLOSE:
                alsaClient.release();
                break;
            case RequestCodes.START:
                alsaClient.start();
                break;
            case RequestCodes.STOP:
                alsaClient.stop();
                break;
            case RequestCodes.PAUSE:
                alsaClient.pause();
                break;
            case RequestCodes.PREPARE:
                if (inputStream.available() < requestLength) return false;

                int channelsPrepared = inputStream.readByte();
                int typePrepared = inputStream.readByte();
                int ratePrepared = inputStream.readInt();
                int framesPrepared = inputStream.readInt();
                AlsaProtocol.validateBuffer(channelsPrepared, typePrepared, ratePrepared, framesPrepared);
                alsaClient.setChannels(channelsPrepared);
                alsaClient.setDataType(ALSAClient.DataType.values()[typePrepared]);
                alsaClient.setSampleRate(ratePrepared);
                alsaClient.setBufferSize(framesPrepared);
                alsaClient.prepare();

                if (ALSAClient.USE_SHARED_MEMORY) createSharedMemory(alsaClient, outputStream);
                break;
            case RequestCodes.WRITE:
                if (alsaClient.getBufferSizeInBytes() <= 0 || requestLength > alsaClient.getBufferSizeInBytes() ||
                    requestLength % (alsaClient.getChannels() * alsaClient.getDataType().byteCount) != 0)
                    throw new IOException("Invalid ALSA write size");
                ByteBuffer sharedBuffer = alsaClient.getSharedBuffer();
                if (sharedBuffer != null) {
                    copySharedBuffer(alsaClient, requestLength, outputStream);
                    alsaClient.writeDataToTrack(alsaClient.getAuxBuffer());
                    sharedBuffer.putInt(0, alsaClient.pointer());
                }
                else {
                    if (inputStream.available() < requestLength) return false;
                    alsaClient.writeDataToTrack(inputStream.readByteBuffer(requestLength));
                }
                break;
            case RequestCodes.DRAIN:
                alsaClient.drain();
                break;
            case RequestCodes.POINTER:
                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeInt(alsaClient.pointer());
                }
                break;
            case RequestCodes.MIN_BUFFER_SIZE:
                byte channels = inputStream.readByte();
                byte type = inputStream.readByte();
                int sampleRate = inputStream.readInt();
                AlsaProtocol.validateFormat(channels, type, sampleRate);
                ALSAClient.DataType dataType = ALSAClient.DataType.values()[type];
                int minBufferSize = ALSAClient.latencyMillisToBufferSize(alsaClient.options.latencyMillis, channels, dataType, sampleRate);

                try (XStreamLock lock = outputStream.lock()) {
                    outputStream.writeInt(minBufferSize);
                }
                break;
        }
        } catch (RuntimeException error) { throw new IOException("ALSA stream failed", error); }
        return true;
    }

    private void copySharedBuffer(ALSAClient alsaClient, int requestLength, XOutputStream outputStream) throws IOException {
        ByteBuffer sharedBuffer = alsaClient.getSharedBuffer();
        ByteBuffer auxBuffer = alsaClient.getAuxBuffer();

        auxBuffer.position(0).limit(requestLength);
        sharedBuffer.position(ALSAClient.BUFFER_OFFSET).limit(ALSAClient.BUFFER_OFFSET + requestLength);
        auxBuffer.put(sharedBuffer);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte((byte)1);
        }
    }

    private void createSharedMemory(ALSAClient alsaClient, XOutputStream outputStream) throws IOException {
        int shmSize = alsaClient.getBufferSizeInBytes() + ALSAClient.BUFFER_OFFSET;
        int fd = SysVSharedMemory.createMemoryFd("alsa-shm"+(++maxSHMemoryId), shmSize);

        if (fd >= 0) {
            ByteBuffer buffer = SysVSharedMemory.mapSHMSegment(fd, shmSize, 0, false);
            if (buffer != null) alsaClient.setSharedBuffer(buffer);
            else { XConnectorEpoll.closeFd(fd); fd = -1; }
        }

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte((byte)0);
            outputStream.setAncillaryFd(fd);
        }
        finally {
            if (fd >= 0) XConnectorEpoll.closeFd(fd);
        }
    }
}
