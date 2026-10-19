/* Aura 0.1.82 local modified file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.alsaserver;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.winlator.contentdialog.AudioDriverConfigDialog;
import com.winlator.core.KeyValueSet;
import com.winlator.math.Mathf;
import com.winlator.sysvshm.SysVSharedMemory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ALSAClient {
    public static final boolean USE_SHARED_MEMORY = true;
    public static final byte BUFFER_OFFSET = 4;
    public enum DataType {
        U8(1), S16LE(2), S16BE(2), FLOATLE(4), FLOATBE(4);
        public final byte byteCount;

        DataType(int byteCount) {
            this.byteCount = (byte)byteCount;
        }
    }
    private DataType dataType = DataType.U8;
    private AlsaPlayback playback;
    private byte channels = 2;
    private int sampleRate = 0;
    private int bufferSize;
    private byte frameBytes;
    private ByteBuffer sharedBuffer;
    private ByteBuffer auxBuffer;
    protected final Options options;
    private static short framesPerBuffer = 256;

    public static class Options {
        public boolean reflector = false;
        public short latencyMillis = 64;
        public byte performanceMode = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY;
        public float volume = AudioDriverConfigDialog.DEFAULT_VOLUME;

        public static Options fromKeyValueSet(KeyValueSet config) {
            if (config == null || config.isEmpty()) return new Options();
            Options options = new Options();
            switch (config.get("performanceMode")) {
                case "0" :
                    options.performanceMode = AudioTrack.PERFORMANCE_MODE_NONE;
                    break;
                case "1" :
                    options.performanceMode = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY;
                    break;
                case "2" :
                    options.performanceMode = AudioTrack.PERFORMANCE_MODE_POWER_SAVING;
                    break;
            }

            options.volume = Math.max(0f, Math.min(1f, config.getFloat("volume", AudioDriverConfigDialog.DEFAULT_VOLUME)));
            options.latencyMillis = (short)Math.max(16, Math.min(200, config.getInt("latencyMillis", 64)));
            return options;
        }
    }

    public ALSAClient(Options options) {
        this.options = options;
    }

    public void release() {
        if (sharedBuffer != null) {
            SysVSharedMemory.unmapSHMSegment(sharedBuffer, sharedBuffer.capacity());
            sharedBuffer = null;
        }

        if (playback != null) { playback.close(); playback = null; }
        auxBuffer = null;
    }

    public static int getPCMEncoding(DataType dataType) {
        switch (dataType) {
            case U8:
                return AudioFormat.ENCODING_PCM_8BIT;
            case S16LE:
            case S16BE:
                return AudioFormat.ENCODING_PCM_16BIT;
            case FLOATLE:
            case FLOATBE:
                return AudioFormat.ENCODING_PCM_FLOAT;
            default:
                return AudioFormat.ENCODING_DEFAULT;
        }
    }

    public static int getChannelConfig(int channels) {
        return channels <= 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
    }

    public void prepare() {
        release();
        frameBytes = (byte)(channels * dataType.byteCount);
        playback = new AlsaPlayback(options, channels, getPCMEncoding(dataType), sampleRate, frameBytes);
        playback.open();
    }

    public void start() { if (playback != null) playback.start(); }
    public void stop() { if (playback != null) playback.stop(); }
    public void pause() { if (playback != null) playback.pause(); }
    public void drain() { if (playback != null) playback.drain(); }
    public void writeDataToTrack(ByteBuffer data) {
        if (playback != null) {
            data.position(0);
            playback.write(data);
        }
    }
    public int pointer() { return playback != null ? playback.pointer() : 0; }

    public void setDataType(DataType dataType) {
        this.dataType = dataType;
    }

    public void setChannels(int channels) {
        this.channels = (byte)channels;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public ByteBuffer getSharedBuffer() {
        return sharedBuffer;
    }

    public void setSharedBuffer(ByteBuffer sharedBuffer) {
        if (sharedBuffer != null) {
            auxBuffer = ByteBuffer.allocateDirect(getBufferSizeInBytes()).order(ByteOrder.LITTLE_ENDIAN);
            this.sharedBuffer = sharedBuffer.order(ByteOrder.LITTLE_ENDIAN);
        }
        else {
            auxBuffer = null;
            this.sharedBuffer = null;
        }
    }

    public ByteBuffer getAuxBuffer() {
        return auxBuffer;
    }

    public DataType getDataType() {
        return dataType;
    }

    public byte getChannels() {
        return channels;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public int getBufferSizeInBytes() {
        return bufferSize * frameBytes;
    }

    public static int bufferSizeToLatencyMillis(int bufferSizeInBytes, int channels, DataType dataType, int sampleRate) {
        byte frameBytes = (byte)(channels * dataType.byteCount);
        float bufferSize = (float)bufferSizeInBytes / frameBytes;
        return (int)((bufferSize / sampleRate) * 1000);
    }

    public static int latencyMillisToBufferSize(int latencyMillis, int channels, DataType dataType, int sampleRate) {
        byte frameBytes = (byte)(channels * dataType.byteCount);
        int bufferSize = (int)Mathf.roundTo((latencyMillis * sampleRate) / 1000.0f, framesPerBuffer, false);
        return bufferSize * frameBytes;
    }

    public static void assignFramesPerBuffer(Context context) {
        try {
            AudioManager am = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
            String framesPerBufferStr = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            framesPerBuffer = Short.parseShort(framesPerBufferStr);
            if (framesPerBuffer == 0) framesPerBuffer = 256;
        }
        catch (Exception e) {
            framesPerBuffer = 256;
        }
    }
}
