/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.core

import android.content.Context
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/** Aura's Kotlin bridge keeps executable code in the PackageManager-installed directory. */
object NativeRuntime {
    private var nativeDirectory: File? = null
    private var rootDirectory: File? = null
    private var index: JSONObject? = null
    private var catalogue: Map<String, String> = emptyMap()
    private var bundledPaths: Map<String, String> = emptyMap()

    @JvmStatic
    @Synchronized
    fun configure(context: Context) {
        RuntimeDataPaths.ensureFilesAlias(context.dataDir, context.filesDir)
        nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
        rootDirectory = File(context.filesDir, Arm64EcRuntime.ROOT)
        val document = context.assets.open("aura-native-index.json").bufferedReader().use { JSONObject(it.readText()) }
        index = document.getJSONObject("files")
        check(document.getString("abi") == "bionic-arm64ec") { "原生组件架构不匹配" }
        val manifest = context.assets.open("arm64ec/manifest.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONObject("files")
        }
        val files = checkNotNull(index)
        catalogue = files.keys().asSequence().associateWith { files.getString(it) }
        bundledPaths = buildMap {
            for (path in manifest.keys()) {
                val name = files.optString(manifest.getJSONObject(path).getString("sha256"))
                if (name.isNotEmpty()) put(path, name)
            }
        }
    }

    @JvmStatic fun verifyInstalledFiles(): Int {
        val files = checkNotNull(index)
        val installed = checkNotNull(nativeDirectory)
        val names = files.keys().asSequence().map { files.getString(it) }.toSet()
        if (names.any { !File(installed, it).let { file -> file.isFile && file.canRead() && file.length() > 0 } })
            throw RuntimeComponentException("APK_NATIVE_LIBRARIES", "FILES_MISSING_OR_UNREADABLE")
        return names.size
    }

    @JvmStatic
    fun repairInstalledLinks(): Int {
        val files = checkNotNull(index) { "原生运行环境尚未初始化" }
        val names = files.keys().asSequence().map { files.getString(it) }.toSet()
        val root = checkNotNull(rootDirectory)
        val installed = checkNotNull(nativeDirectory)
        return NativeLibraryLinks.repair(root, installed, names, bundledPaths) +
            NativeLibraryLinks.repair(File(root.parentFile, "pulseaudio"), installed, names)
    }

    /** Add a newly bundled library on upgrades without re-extracting the user's rootfs. */
    @JvmStatic
    fun ensureBundledLibrary(path: String) {
        val name = bundledPaths[path] ?: throw IOException("Unbundled runtime path: $path")
        val digest = catalogue.entries.single { it.value == name }.key
        val root = checkNotNull(rootDirectory)
        installBundledReference(root, File(root, path), BundledNativeReference.PREFIX + digest)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun installBundledReference(root: File, file: File, reference: String) {
        val installed = nativeDirectory ?: throw IOException("Native runtime is not configured")
        BundledNativeReference.install(root, file, reference, installed, catalogue)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun stage(file: File) {
        val files = index ?: return
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) return
        try {
            val digest = file.inputStream().use { input ->
                val header = ByteArray(20)
                if (input.read(header) != 20 || header[0] != 127.toByte() || header[1] != 'E'.code.toByte() ||
                    header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte() ||
                    header[18].toInt() and 255 != 183 || header[19] != 0.toByte()) return
                val hash = MessageDigest.getInstance("SHA-256").apply { update(header) }
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
                hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            val name = files.optString(digest)
            if (name.isEmpty()) throw IOException("Native component is not bundled: ${file.name}")
            val target = File(nativeDirectory, name)
            if (!target.isFile) throw IOException("Missing installed native component: $name")
            if (!file.delete()) throw IOException("Unable to stage ${file.name}")
            Os.symlink(target.absolutePath, file.absolutePath)
        } catch (error: IOException) { throw error }
        catch (error: Exception) { throw IOException("Native staging failed", error) }
    }

    @JvmStatic fun command(command: Array<String>): Array<String> {
        // ARM64EC Wine is a Bionic executable with /system/bin/linker64.
        return command
    }
}
