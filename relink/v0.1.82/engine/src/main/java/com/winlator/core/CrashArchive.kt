/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties

enum class CrashKind(val label: String) {
    JAVA_CRASH("App 异常崩溃"), NATIVE_CRASH("原生崩溃"), ANR("应用无响应"),
    LOW_MEMORY("系统内存回收"), SIGNAL("进程被信号结束"), RESOURCE_LIMIT("系统资源限制"),
    INITIALIZATION("进程初始化失败"), GAME_FAILURE("游戏运行中断"),
    SELF_EXIT("游戏期间 App 进程自行退出"),
}

data class CrashRecord(
    val id: String,
    val occurredAt: Long,
    val kind: CrashKind,
    val details: String,
    val pid: Int,
    val sessionId: String,
    val source: String,
) {
    val timeLabel: String get() = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(occurredAt))
    val report: String get() {
        val origin = when (source) { "JAVA" -> "应用异常捕获"; "SYSTEM" -> "Android 系统退出记录"; "GAME" -> "游戏运行诊断"; else -> source }
        return "Aura 崩溃诊断\n时间：$timeLabel (${ZoneId.systemDefault()})\n类型：${kind.label}\n来源：$origin\n$details"
    }
}

data class CrashSession(val id: String, val pid: Int, val startedAt: Long, val header: String, val stage: String)

/** A small, atomic archive, independent of the current game and of dismissing an error dialog. */
class CrashArchive(private val file: File) {
    private data class State(
        val last: CrashRecord? = null, val session: CrashSession? = null,
        val seenExits: List<String> = emptyList(), val clearedThrough: Long = Long.MIN_VALUE,
    )
    private var state = read()

    @Synchronized fun last(): CrashRecord? = state.last
    @Synchronized fun session(): CrashSession? = state.session

    @Synchronized fun begin(session: CrashSession) = save(state.copy(session = session))
    @Synchronized fun endSession() = save(state.copy(session = null))

    @Synchronized fun stage(stage: String) {
        val current = state.session ?: return
        if (stage != current.stage) save(state.copy(session = current.copy(stage = stage.take(160))))
    }

    @Synchronized fun record(record: CrashRecord) = save(state.copy(last = record.copy(details = record.details.take(12_000))))

    @Synchronized fun importSystem(records: List<CrashRecord>) {
        val fresh = records.filter { it.id !in state.seenExits }
        if (fresh.isEmpty()) return
        val latest = fresh.filter { it.occurredAt > state.clearedThrough }.maxByOrNull { it.occurredAt }
        val old = state.last
        // The platform's Java-crash summary must not replace the locally captured stack trace.
        val sameJavaCrash = old?.source == "JAVA" && latest != null && latest.kind == CrashKind.JAVA_CRASH &&
            latest.sessionId.isNotEmpty() && latest.sessionId == old.sessionId && latest.pid == old.pid
        val keepOld = latest == null || sameJavaCrash || old != null && old.occurredAt > latest.occurredAt
        save(state.copy(last = if (keepOld) old else latest.copy(details = latest.details.take(12_000)),
            seenExits = (state.seenExits + fresh.map { it.id }).distinct().takeLast(64)))
    }

    // Retain processed system-exit IDs so opening settings/restarting cannot restore a cleared record.
    @Synchronized fun clear(at: Long = System.currentTimeMillis()) =
        save(state.copy(last = null, clearedThrough = maxOf(at, state.clearedThrough)))

    private fun save(next: State) {
        val properties = Properties()
        next.session?.let {
            properties.setProperty("session.id", it.id)
            properties.setProperty("session.pid", it.pid.toString())
            properties.setProperty("session.started", it.startedAt.toString())
            properties.setProperty("session.header", it.header)
            properties.setProperty("session.stage", it.stage)
        }
        next.last?.let {
            properties.setProperty("last.id", it.id)
            properties.setProperty("last.time", it.occurredAt.toString())
            properties.setProperty("last.kind", it.kind.name)
            properties.setProperty("last.details", it.details)
            properties.setProperty("last.pid", it.pid.toString())
            properties.setProperty("last.session", it.sessionId)
            properties.setProperty("last.source", it.source)
        }
        properties.setProperty("seen", next.seenExits.joinToString("\n"))
        properties.setProperty("clearedThrough", next.clearedThrough.toString())
        val directory = requireNotNull(file.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建诊断目录" }
        val temporary = File.createTempFile(".crash-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output -> properties.store(output, null); output.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            state = next
        } finally { temporary.delete() }
    }

    private fun read(): State = runCatching {
        if (!file.isFile || file.length() > 256 * 1024) return State()
        val properties = Properties().apply { file.inputStream().use(::load) }
        fun value(key: String) = properties.getProperty(key, "")
        val session = value("session.id").takeIf { it.isNotEmpty() }?.let {
            CrashSession(it, value("session.pid").toInt(), value("session.started").toLong(), value("session.header"), value("session.stage"))
        }
        val record = value("last.id").takeIf { it.isNotEmpty() }?.let {
            CrashRecord(it, value("last.time").toLong(), CrashKind.valueOf(value("last.kind")), value("last.details").take(12_000),
                value("last.pid").toInt(), value("last.session"), value("last.source"))
        }
        State(record, session, value("seen").lineSequence().filter { it.isNotEmpty() }.take(64).toList(),
            value("clearedThrough").toLongOrNull() ?: Long.MIN_VALUE)
    }.getOrDefault(State())
}
