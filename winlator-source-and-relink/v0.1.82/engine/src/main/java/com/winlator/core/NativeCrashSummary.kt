/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.core

import com.google.protobuf.CodedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Selected AOSP debuggerd/proto/tombstone.proto fields, never raw dumps, logs or command lines. */
object NativeCrashSummary {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_FRAMES = 64
    private const val REPORTED_FRAMES = 12
    private const val MAX_THREADS = 64

    private data class Frame(val module: String, val pc: Long, val buildId: String)
    private data class ThreadTrace(val id: Int, val frames: List<Frame>)

    fun summarize(stream: InputStream, expectedPid: Int): String = try {
        val input = CodedInputStream.newInstance(LimitedInput(stream)).apply {
            setSizeLimit(MAX_BYTES)
            setRecursionLimit(12)
        }
        var pid = 0
        var tid = 0
        var signal = 0
        var abort = "NOT_PROVIDED"
        val threads = linkedMapOf<Int, ThreadTrace>()
        input.fields { tag ->
            when (tag) {
                40 -> pid = input.readUInt32() // Tombstone.pid = 5
                48 -> tid = input.readUInt32() // Tombstone.tid = 6
                82 -> input.message { // Tombstone.signal_info = 10
                    input.fields { field -> if (field == 8) signal = input.readInt32() else input.skipField(field) }
                }
                114 -> abort = classifyAbort(input.prefix(2048)) // abort_message = 14
                130 -> input.message { // threads = 16: map entry { key, value }
                    var key = 0
                    var trace: ThreadTrace? = null
                    input.fields { field ->
                        when (field) {
                            8 -> key = input.readUInt32()
                            18 -> trace = input.message { readThread(input) }
                            else -> input.skipField(field)
                        }
                    }
                    trace?.takeIf { key > 0 && it.id == key }?.let {
                        // A map's value may precede its key. Always decode that bounded
                        // value before deciding whether to retain the crashed thread.
                        if (threads.size < MAX_THREADS || key == tid) {
                            if (threads.size == MAX_THREADS && key !in threads) threads.remove(threads.keys.first())
                            threads[key] = it
                        }
                    }
                }
                else -> input.skipField(tag)
            }
        }
        if (expectedPid <= 0 || pid != expectedPid) "NATIVE_TRACE PID_MISMATCH"
        else buildString {
            val name = when (signal) { 6 -> "SIGABRT"; 11 -> "SIGSEGV"; 7 -> "SIGBUS"; 4 -> "SIGILL"; else -> "OTHER" }
            appendLine("NATIVE_TRACE AVAILABLE; signal=$signal ($name); abort=$abort")
            val frames = threads[tid]?.frames.orEmpty()
            if (frames.isEmpty()) append("原生崩溃线程栈：系统未提供可用帧")
            else selectedFrames(frames).forEach { (index, frame) ->
                appendLine("#$index ${frame.module} pc=${java.lang.Long.toUnsignedString(frame.pc, 16)} buildId=${frame.buildId}")
            }
        }.trimEnd().take(1780)
    } catch (_: Exception) {
        "NATIVE_TRACE INVALID_OR_TOO_LARGE"
    }

    /** ART abort machinery can consume the first twelve frames; retain callers beyond it. */
    private fun selectedFrames(frames: List<Frame>): List<IndexedValue<Frame>> {
        val indexed = frames.withIndex().toList()
        if (frames.size <= REPORTED_FRAMES) return indexed
        val leading = indexed.take(4)
        val callers = indexed.drop(4).filter { it.value.module !in setOf("libart.so", "libbase.so", "libc.so", "other_module", "unknown") }
        return (leading + callers + indexed.drop(4)).distinctBy { it.index }
            .take(REPORTED_FRAMES).sortedBy { it.index }
    }

    private fun readThread(input: CodedInputStream): ThreadTrace {
        var id = 0
        val frames = mutableListOf<Frame>()
        input.fields { tag ->
            when (tag) {
                8 -> id = input.readInt32()
                34 -> if (frames.size < MAX_FRAMES) frames += input.message { readFrame(input) } else input.skipField(tag)
                else -> input.skipField(tag) // names, registers, memory and notes are never retained
            }
        }
        return ThreadTrace(id, frames)
    }

    private fun readFrame(input: CodedInputStream): Frame {
        var pc = 0L
        var module = "unknown"
        var buildId = "?"
        input.fields { tag ->
            when (tag) {
                8 -> pc = input.readUInt64() // rel_pc, not an ASLR address
                50 -> {
                    val file = input.prefix(1024).removeSuffix(" (deleted)").substringAfterLast('/')
                    module = if (file.matches(Regex("[A-Za-z0-9_.+-]{1,72}\\.so")) || file == "linker64") file else "other_module"
                }
                66 -> buildId = input.prefix(64).takeIf { it.matches(Regex("[0-9a-fA-F]{8,64}")) } ?: "?"
                else -> input.skipField(tag) // no full paths, symbols, stack pointers or memory
            }
        }
        return Frame(module, pc, buildId)
    }

    private fun classifyAbort(message: String): String = when {
        message.contains("thread suspension timed out", true) || message.contains("suspend all timeout", true) -> "ART_THREAD_SUSPEND_TIMEOUT"
        message.contains("unexpected pending exception", true) || message.contains("pending exception", true) && message.contains("JNI", true) -> "JNI_PENDING_EXCEPTION"
        message.contains("use of deleted", true) && message.contains("reference", true) -> "JNI_DELETED_REFERENCE"
        message.contains("local reference table overflow", true) -> "JNI_LOCAL_REFERENCE_OVERFLOW"
        message.contains("global reference table overflow", true) -> "JNI_GLOBAL_REFERENCE_OVERFLOW"
        message.contains("using JNIEnv* from thread", true) -> "JNI_WRONG_THREAD"
        message.contains("JNI DETECTED ERROR", true) -> "JNI_ABORT"
        message.contains("FORTIFY", true) -> "FORTIFY_ABORT"
        message.contains("Scudo ERROR", true) -> "SCUDO_HEAP_ERROR"
        message.contains("uncaught exception", true) -> "CPP_UNCAUGHT_EXCEPTION"
        message.contains("assertion", true) || message.contains("assert failed", true) -> "ASSERTION_FAILURE"
        message.isBlank() -> "NOT_PROVIDED"
        else -> "OTHER_ABORT"
    }

    private inline fun CodedInputStream.fields(read: (Int) -> Unit) {
        while (true) {
            val tag = readTag()
            if (tag == 0) break
            check(tag and 7 != 4) // an unmatched protobuf end-group is malformed
            read(tag)
        }
    }

    private inline fun <T> CodedInputStream.message(read: () -> T): T {
        val limit = pushLimit(readRawVarint32())
        return try { read().also { check(bytesUntilLimit == 0) } } finally { popLimit(limit) }
    }

    private fun CodedInputStream.prefix(max: Int): String {
        val size = readRawVarint32()
        require(size >= 0)
        val kept = minOf(size, max)
        val value = String(readRawBytes(kept), Charsets.UTF_8)
        skipRawBytes(size - kept)
        return value
    }

    // CodedInputStream's size limit does not cover every InputStream.skip path.
    // Bound skipped unknown fields as well as fields that we decode.
    private class LimitedInput(stream: InputStream) : FilterInputStream(stream) {
        private var remaining = MAX_BYTES

        override fun read(): Int {
            val value = `in`.read()
            if (value >= 0) {
                if (remaining == 0) throw IOException("Native trace size limit")
                remaining--
            }
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0) return read()
            val count = `in`.read(buffer, offset, minOf(length, remaining))
            if (count > 0) remaining -= count
            return count
        }

        override fun skip(count: Long): Long = `in`.skip(minOf(count, remaining.toLong())).also {
            remaining -= it.toInt()
        }
    }
}
