/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.alsaserver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/** Owned by one ALSA connection. Device callbacks only increment routeGeneration;
 * all AudioTrack creation, writes and release happen on the connection thread. */
class AlsaPlayback(
    private val options: ALSAClient.Options,
    private val channels: Int,
    private val encoding: Int,
    private val sampleRate: Int,
    private val frameBytes: Int,
) {
    private var track: AudioTrack? = null
    private var closed = false
    private var paused = false
    private var generation = routeGeneration.get()
    private var retryAt = 0L
    private var frames = 0L
    private var outputFrames = 0L
    private val capacity = maxOf(sampleRate * options.latencyMillis / 1000, 256)
    private val clock = ReflectorClock(sampleRate, capacity)
    private val pending = ByteBuffer.allocateDirect(capacity * frameBytes * 2).apply { limit(0) }

    @Synchronized fun open() { ensureTrack() }

    private fun ensureTrack() {
        if (closed) return
        if (generation != routeGeneration.get()) {
            generation = routeGeneration.get()
            releaseTrack()
            retryAt = 0
        }
        if (track != null || System.nanoTime() < retryAt) return
        try {
            val mask = ALSAClient.getChannelConfig(channels)
            val minimum = AudioTrack.getMinBufferSize(sampleRate, mask, encoding)
            check(minimum > 0) { "Unsupported PCM format" }
            val created = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                    .setChannelMask(mask).setEncoding(encoding).build())
                .setPerformanceMode(options.performanceMode.toInt())
                .setBufferSizeInBytes(maxOf(minimum, capacity * frameBytes))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            track = created
            outputFrames = 0
            check(created.state == AudioTrack.STATE_INITIALIZED)
            created.setVolume(options.volume)
            if (!paused) created.play()
        } catch (error: RuntimeException) {
            releaseTrack()
            retryAt = System.nanoTime() + 250_000_000L
            Log.w("AuraAudio", "ALSA output unavailable; reflector=${options.reflector}", error)
            if (!options.reflector) throw IOException("Android ALSA output unavailable", error)
        }
    }

    @Synchronized fun write(data: ByteBuffer) {
        if (closed || paused) return
        val bytes = data.remaining()
        if (options.reflector) {
            val delay = clock.accept(bytes / frameBytes, System.nanoTime())
            val until = System.nanoTime() + delay
            while (!Thread.currentThread().isInterrupted) {
                val left = until - System.nanoTime()
                if (left <= 0) break
                LockSupport.parkNanos(minOf(left, 10_000_000L))
            }
            if (Thread.currentThread().isInterrupted) throw IOException("ALSA stream interrupted")
            ensureTrack()
            if (track != null) {
                // Bounded mirror queue: route loss never grows memory or blocks the guest clock.
                flushPending()
                pending.compact()
                val limit = data.limit()
                val count = minOf(data.remaining(), pending.remaining()) / frameBytes * frameBytes
                data.limit(data.position() + count)
                pending.put(data)
                data.limit(limit)
                pending.flip()
                flushPending()
            }
            data.position(data.limit())
            frames += bytes / frameBytes
        } else {
            ensureTrack()
            val output = track ?: throw IOException("ALSA output disconnected")
            val deadline = System.nanoTime() + 2_000_000_000L
            while (data.hasRemaining()) {
                val count = output.write(data, data.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                if (count < 0) { releaseTrack(); throw IOException("ALSA write failed: $count") }
                if (count > 0) { frames += count / frameBytes; outputFrames += count / frameBytes }
                else {
                    if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted)
                        throw IOException("ALSA output stalled")
                    LockSupport.parkNanos(1_000_000L)
                }
            }
        }
    }

    private fun flushPending() {
        if (!pending.hasRemaining()) return
        val count = track?.write(pending, pending.remaining(), AudioTrack.WRITE_NON_BLOCKING) ?: return
        if (count > 0) outputFrames += count / frameBytes
        if (count < 0) { releaseTrack(); retryAt = System.nanoTime() + 250_000_000L }
    }

    @Synchronized fun pointer(): Int = frames.toInt()
    @Synchronized fun start() { if (!closed) { paused = false; ensureTrack(); track?.play() } }
    @Synchronized fun pause() { paused = true; clock.reset(); track?.pause() }
    @Synchronized fun stop() {
        paused = true; clock.reset(); frames = 0; outputFrames = 0
        track?.pause(); track?.flush(); pending.clear().limit(0)
    }
    @Synchronized fun drain() {
        // Wait for a bounded tail; flushing would discard the end of short sounds.
        if (closed || paused) return
        val deadline = System.nanoTime() + 2_000_000_000L
        while (track != null && !Thread.currentThread().isInterrupted && System.nanoTime() < deadline) {
            if (options.reflector) flushPending()
            val output = track ?: break
            val played = output.playbackHeadPosition.toLong() and 0xffffffffL
            if (!pending.hasRemaining() && played >= outputFrames) break
            LockSupport.parkNanos(1_000_000L)
        }
    }
    @Synchronized fun close() { closed = true; releaseTrack(); clock.reset() }
    private fun releaseTrack() {
        val old = track
        track = null
        pending.clear().limit(0)
        if (old != null) {
            try { old.pause(); old.flush() } catch (_: IllegalStateException) { }
            finally { old.release() }
        }
    }

    companion object { @JvmField val routeGeneration = AtomicLong() }
}
