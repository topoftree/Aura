/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Four temporary relay values only. Recovery runs after guest cleanup, before any Wine launch. */
object WaitTraceRegistry {
    private const val HEADER = "[Software\\\\Wine\\\\Debug]"
    private val section = Regex("(?mi)^\\[Software\\\\\\\\Wine\\\\\\\\Debug]([^\\n]*)\\n")
    private val value = Regex("(?mi)^\"(?:RelayInclude|RelayExclude|RelayFromInclude|RelayFromExclude)\"=[^\\n]*(?:\\n[ \\t]+[^\\n]*)*\\n?")
    val functions = listOf(
        "ntdll.NtWaitForSingleObject", "ntdll.NtWaitForMultipleObjects", "ntdll.NtCreateEvent",
        "ntdll.NtSetEvent", "ntdll.NtResetEvent", "ntdll.NtCreateThreadEx", "ntdll.RtlCreateUserThread",
    ) + listOf("kernelbase", "kernel32").flatMap { module ->
        listOf("WaitForSingleObject", "WaitForSingleObjectEx", "WaitForMultipleObjects", "WaitForMultipleObjectsEx",
            "CreateThread", "CreateEventW", "CreateEventA", "SetEvent", "ResetEvent").map { "$module.$it" }
    } + listOf("kernelbase.CreateEventExW", "kernelbase.CreateEventExA")

    private fun backup(prefix: File) = AtomicFile(File(prefix, ".aura-wait-trace.json"))
    fun pending(prefix: File) = backup(prefix).let { it.baseFile.exists() || File(it.baseFile.path + ".bak").exists() }

    private fun read(file: File): String {
        return AtomicFile(file).openRead().use { stream ->
            val bytes = stream.readNBytes(16 * 1024 * 1024 + 1)
            check(bytes.size <= 16 * 1024 * 1024) { "Wine registry unavailable" }
            bytes.toString(Charsets.UTF_8)
        }.also { check(it.startsWith("WINE REGISTRY Version 2")) { "Unknown Wine registry format" } }
    }

    private fun bounds(text: String): IntRange? {
        val matches = section.findAll(text).toList()
        check(matches.size <= 1) { "Ambiguous Wine debug section" }
        val match = matches.singleOrNull() ?: return null
        val start = match.range.last + 1
        val end = Regex("(?m)^\\[").find(text, start)?.range?.first ?: text.length
        return start until end
    }

    private fun rewrite(text: String, replacement: String): String {
        val range = bounds(text) ?: return text.trimEnd() + "\n\n$HEADER\n" + replacement
        val body = text.substring(range)
        return text.replaceRange(range, value.replace(body, "").trimEnd() + "\n" + replacement + "\n")
    }

    private fun write(file: AtomicFile, text: String) {
        val stream = file.startWrite()
        try { stream.write(text.toByteArray()); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        check(file.openRead().bufferedReader().use { it.readText() } == text) { "Trace configuration was not saved" }
    }

    fun install(prefix: File, includedFunctions: List<String> = functions, fromModule: String? = null) {
        require(includedFunctions.isNotEmpty() && includedFunctions.all { it.matches(Regex("[A-Za-z0-9_.]+")) })
        require(fromModule == null || fromModule.matches(Regex("[A-Za-z0-9_.]+")))
        check(!pending(prefix)) { "Previous trace needs recovery" }
        val registry = File(prefix, "user.reg")
        val text = read(registry)
        val originals = bounds(text)?.let { value.findAll(text.substring(it)).map { match -> match.value }.toList() }.orEmpty()
        // In this Wine build even an empty RelayFromExclude takes precedence over FromInclude.
        // Omit it entirely for a caller-filtered capture, while retaining its original value in backup.
        val filters = "\"RelayInclude\"=\"${includedFunctions.joinToString(";")}\"\n\"RelayExclude\"=\"\"\n" +
            if (fromModule != null) "\"RelayFromInclude\"=\"$fromModule\"\n"
            else "\"RelayFromInclude\"=\"\"\n\"RelayFromExclude\"=\"\"\n"
        write(backup(prefix), JSONObject().put("version", 1).put("values", JSONArray(originals)).toString())
        write(AtomicFile(registry), rewrite(text, filters))
    }

    fun restore(prefix: File): Boolean {
        if (!pending(prefix)) return false
        val saved = backup(prefix)
        val snapshot = JSONObject(saved.openRead().bufferedReader().use { it.readText() })
        check(snapshot.getInt("version") == 1)
        val entries = snapshot.getJSONArray("values")
        val originals = (0 until entries.length()).joinToString("") { entries.getString(it).trimEnd() + "\n" }
        val registry = File(prefix, "user.reg")
        write(AtomicFile(registry), rewrite(read(registry), originals))
        saved.delete()
        check(!pending(prefix)) { "Trace recovery marker could not be cleared" }
        return true
    }
}
