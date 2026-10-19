/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.core

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.READ
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Debug-only Unity output. Each launch owns a fresh path; raw text is exported only on request. */
object UnityDebugLogs {
    const val STORE = "diagnostics/unity-player"
    private const val DIRECTORY = ".aura-unity-debug"
    private const val ARCHIVE = "unity-debug-latest.zip"
    private const val METADATA = "capture.properties"
    private const val PART_BYTES = 256 * 1024
    private val SESSION = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private val CUSTOM_LOG = Regex("(?:^|\\s)\"?-(?:logfile|nolog)(?:[\\s\"=]|$)", RegexOption.IGNORE_CASE)

    data class Prepared(val arguments: String?, val diagnostic: String?)
    private data class Capture(val root: File, val session: String, val game: Int, val container: Int, val started: Long) {
        fun directory(): File {
            var path = root.canonicalFile.toPath()
            for (name in listOf(".wine", "drive_c", DIRECTORY, session)) {
                path = path.resolve(name)
                check(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Invalid Unity log directory" }
            }
            return path.toFile()
        }
    }

    @Synchronized
    fun prepare(store: File, root: File, executable: File, arguments: String?, enabled: Boolean,
                session: String, game: Int, container: Int, started: Long = System.currentTimeMillis()): Prepared {
        if (!enabled || !isUnity(executable)) return Prepared(arguments, null)
        if (CUSTOM_LOG.containsMatchIn(arguments.orEmpty()))
            return Prepared(arguments, "UNITY_DEBUG_LOG_V1 state=EXISTING_ARGUMENTS_PRESERVED")
        var created: File? = null
        return try {
            require(SESSION.matches(session) && game > 0 && container > 0)
            require(root.isAbsolute)
            val previous = readCapture(store)
            var directory = root.canonicalFile.toPath()
            for (name in listOf(".wine", "drive_c", DIRECTORY)) {
                directory = directory.resolve(name)
                if (name == DIRECTORY && !Files.exists(directory, NOFOLLOW_LINKS)) Files.createDirectory(directory)
                check(Files.isDirectory(directory, NOFOLLOW_LINKS))
            }
            val destination = directory.resolve(session)
            Files.createDirectory(destination) // Never reuse another launch's output or follow links.
            created = destination.toFile()
            check(store.isDirectory || store.mkdirs())
            val capture = Capture(root.canonicalFile, session, game, container, started)
            val properties = Properties().apply {
                setProperty("root", capture.root.path); setProperty("session", session)
                setProperty("game", game.toString()); setProperty("container", container.toString())
                setProperty("started", started.toString())
            }
            atomic(store, METADATA) { output -> properties.store(output, "Aura Unity debug capture") }
            archive(store, capture)
            // Only remove our preceding raw file, after publishing a new capture. Do not scan C:.
            previous?.takeIf { it.session != session }?.let { old -> runCatching {
                val oldDir = old.directory()
                val oldLog = File(oldDir, "Player.log").toPath()
                if (Files.isRegularFile(oldLog, NOFOLLOW_LINKS)) Files.delete(oldLog)
                Files.deleteIfExists(oldDir.toPath())
            } }
            val suffix = "-logFile \"C:\\$DIRECTORY\\$session\\Player.log\""
            Prepared(listOfNotNull(arguments?.takeIf(String::isNotBlank), suffix).joinToString(" "),
                "UNITY_DEBUG_LOG_V1 state=PREPARED; export=UNITY_DEBUG_ZIP")
        } catch (_: IOException) {
            created?.delete(); Prepared(arguments, "UNITY_DEBUG_LOG_V1 state=PREPARE_FAILED")
        } catch (_: IllegalArgumentException) {
            created?.delete(); Prepared(arguments, "UNITY_DEBUG_LOG_V1 state=PREPARE_FAILED")
        } catch (_: IllegalStateException) {
            created?.delete(); Prepared(arguments, "UNITY_DEBUG_LOG_V1 state=PREPARE_FAILED")
        } catch (_: SecurityException) {
            Prepared(arguments, "UNITY_DEBUG_LOG_V1 state=PREPARE_FAILED")
        }
    }

    /** No title/AppID heuristics; require Unity's actual adjacent data/runtime layout. */
    fun isUnity(executable: File): Boolean {
        return try {
            if (!executable.name.endsWith(".exe", true) || !Files.isRegularFile(executable.toPath(), NOFOLLOW_LINKS)) false
            else {
                val parent = executable.parentFile ?: return false
                fun child(directory: File, name: String): File? {
                    if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) return null
                    return Files.newDirectoryStream(directory.toPath()).use { paths ->
                        paths.asSequence().take(256).filter { it.fileName.toString().equals(name, true) }
                            .toList().singleOrNull()?.toFile()
                    }
                }
                val data = child(parent, executable.nameWithoutExtension + "_Data")
                if (data == null || !Files.isDirectory(data.toPath(), NOFOLLOW_LINKS)) false
                else child(parent, "UnityPlayer.dll")?.let { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) } == true ||
                    child(data, "Managed")?.let { managed ->
                        listOf("UnityEngine.dll", "UnityEngine.CoreModule.dll").any { name ->
                            child(managed, name)?.let { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) } == true
                        }
                    } == true
            }
        } catch (_: IOException) { false } catch (_: SecurityException) { false }
    }

    /** Safe to call after process death: metadata binds raw output to its original launch. */
    @Synchronized
    fun refresh(store: File, expectedSession: String): String? {
        val capture = readCapture(store)?.takeIf { it.session == expectedSession } ?: return null
        return try { archive(store, capture) }
        catch (_: IOException) { "UNITY_DEBUG_LOG_V1 state=CAPTURE_FAILED" }
        catch (_: IllegalStateException) { "UNITY_DEBUG_LOG_V1 state=CAPTURE_FAILED" }
        catch (_: SecurityException) { "UNITY_DEBUG_LOG_V1 state=CAPTURE_FAILED" }
    }

    private fun archive(store: File, capture: Capture): String {
        var state = "NOT_CREATED"
        var bytes = 0L
        var text: String? = null
        try {
            val file = File(capture.directory(), "Player.log").toPath()
            if (Files.exists(file, NOFOLLOW_LINKS)) {
                check(Files.isRegularFile(file, NOFOLLOW_LINKS))
                FileChannel.open(file, READ, NOFOLLOW_LINKS).use { input ->
                    bytes = input.size()
                    fun part(at: Long, length: Int): String {
                        input.position(at)
                        val buffer = ByteBuffer.allocate(length)
                        while (buffer.hasRemaining() && input.read(buffer) > 0) { /* Bounded read. */ }
                        return String(buffer.array(), 0, buffer.position(), Charsets.UTF_8)
                    }
                    text = if (bytes <= PART_BYTES * 2L) part(0, bytes.toInt()) else
                        part(0, PART_BYTES) + "\n[Aura: middle of large log omitted]\n" + part(bytes - PART_BYTES, PART_BYTES)
                }
                state = if (bytes == 0L) "EMPTY" else "CAPTURED"
            }
        } catch (_: IOException) { state = "UNREADABLE" }
          catch (_: IllegalStateException) { state = "UNREADABLE" }
          catch (_: SecurityException) { state = "UNREADABLE" }
        val signals = GuestOutputSummary().apply { text?.lineSequence()?.forEach { accept(it) } }.signalCodes()
        val summary = "UNITY_DEBUG_LOG_V1 state=$state; bytes=$bytes; truncated=${bytes > PART_BYTES * 2L}; " +
            "signals=" + signals.joinToString(",").ifEmpty { "NONE_CLASSIFIED" }
        atomic(store, ARCHIVE) { output ->
            ZipOutputStream(output).use { zip ->
                fun add(name: String, value: String) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                }
                add("capture.txt", "game=${capture.game}; container=${capture.container}\n" +
                    "launch=${capture.session}; started_ms=${capture.started}; captured_ms=${System.currentTimeMillis()}\n$summary\n" +
                    "Debug Unity output; raw text stays local until exported. A missing log does not prove game readiness.\n")
                text?.let { add("Player.log", it) }
            }
        }
        return summary
    }

    private fun readCapture(store: File): Capture? {
        return try {
            val file = File(store, METADATA)
            if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.length() > 16_384) null
            else {
                val p = Properties().apply { Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use { load(it) } }
                val session = p.getProperty("session")?.takeIf { SESSION.matches(it) } ?: return null
                val root = p.getProperty("root")?.let(::File)?.takeIf { it.isAbsolute } ?: return null
                val game = p.getProperty("game")?.toIntOrNull()?.takeIf { it > 0 } ?: return null
                val container = p.getProperty("container")?.toIntOrNull()?.takeIf { it > 0 } ?: return null
                val started = p.getProperty("started")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
                Capture(root, session, game, container, started)
            }
        } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }
          catch (_: SecurityException) { null }
    }

    private fun atomic(store: File, name: String, write: (OutputStream) -> Unit) {
        check(store.isDirectory || store.mkdirs())
        val temporary = File.createTempFile("unity-debug-", ".tmp", store)
        try {
            temporary.outputStream().use(write)
            Files.move(temporary.toPath(), File(store, name).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    fun available(store: File): Boolean {
        val archive = File(store, ARCHIVE)
        return Files.isRegularFile(archive.toPath(), NOFOLLOW_LINKS) && archive.length() > 0
    }

    @Synchronized
    fun export(store: File, output: OutputStream) {
        Files.newInputStream(File(store, ARCHIVE).toPath(), READ, NOFOLLOW_LINKS).use { it.copyTo(output) }
    }
}
