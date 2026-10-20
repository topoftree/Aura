/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import com.winlator.xenvironment.RootFS
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Direct X11 ICD: PanVK does not need Qualcomm's AdrenoTools hook. */
object PanVkRuntime {
    const val VERSION = "0.1.0 RC3 (Mesa 26.3.0-devel)"
    const val LIBRARY = "libaura_panvk.so"
    const val SHA256 = "b46b9f0416f207a05482349dffb37b6f3d84c20762151c623774831ce3939445"
    const val PROBE = "libaura_panvk_probe.so"
    const val XFIXES_LIBRARY = "libaura_panvk_xcb_xfixes.so"
    const val XFIXES_SHA256 = "41af7ab9535ff73913889255cb7a8c87d8f19c1e6749b88f4d4a98df66caa1b2"
    private const val ICD = "usr/share/vulkan/icd.d/aura_panvk_icd.aarch64.json"
    private val x11Aliases = mapOf("libxcb.so.1" to "libxcb.so", "libX11-xcb.so.1" to "libX11-xcb.so",
        "libxcb-dri3.so.0" to "libxcb-dri3.so", "libxcb-present.so.0" to "libxcb-present.so",
        "libxcb-shm.so.0" to "libxcb-shm.so", "libxcb-sync.so.1" to "libxcb-sync.so",
        "libxcb-randr.so.0" to "libxcb-randr.so", "libxshmfence.so.1" to "libxshmfence.so")

    fun icd(root: File): File = File(root, ICD)
    fun libraryPath(root: File): String = "${root}/usr/lib/aura-panvk:${root}/usr/lib"

    fun isPrepared(context: Context): Boolean {
        val root = RootFS.find(context).rootDir
        val native = File(context.applicationInfo.nativeLibraryDir)
        return icd(root).isFile && File(native, LIBRARY).isFile && File(native, XFIXES_LIBRARY).isFile &&
            (x11Aliases.keys + "libxcb-xfixes.so.0").all { File(root, "usr/lib/aura-panvk/$it").isFile }
    }

    private fun verify(library: File, expected: String) {
        val digest = library.inputStream().use { input ->
            val hash = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        check(digest == expected) { "PanVK 内置文件校验失败" }
    }

    /** Validate installer-owned bytes and write paths specific to this APK installation. */
    fun prepare(context: Context): File {
        val library = File(context.applicationInfo.nativeLibraryDir, LIBRARY)
        val xfixes = File(context.applicationInfo.nativeLibraryDir, XFIXES_LIBRARY)
        verify(library, SHA256); verify(xfixes, XFIXES_SHA256)
        val root = RootFS.find(context).rootDir
        val cache = File(context.cacheDir, "panvk")
        check(cache.isDirectory || cache.mkdirs())
        // Upstream PanVK dlopens Linux-style SONAMEs; Aura's existing Bionic XCB
        // libraries have unversioned names. Limit these aliases to PanVK's search path.
        val aliases = File(root, "usr/lib/aura-panvk")
        check(!java.nio.file.Files.isSymbolicLink(aliases.toPath()))
        check(aliases.isDirectory || aliases.mkdirs())
        val targets = x11Aliases.mapValues { File(root, "usr/lib/${it.value}") } +
            ("libxcb-xfixes.so.0" to xfixes)
        for ((name, target) in targets) {
            check(target.isFile) { "PanVK X11 依赖缺失" }
            val link = File(aliases, name)
            java.nio.file.Files.deleteIfExists(link.toPath())
            android.system.Os.symlink(target.canonicalPath, link.path)
        }
        val file = icd(root)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        // Absolute APK path avoids picking up an imported driver with the same SONAME.
        val text = JSONObject().put("file_format_version", "1.0.0").put("ICD", JSONObject()
            .put("library_path", library.absolutePath).put("api_version", "1.3.0")).toString()
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
        return file
    }

    /** The driver can crash or hang. It is loaded only in a disposable child process here. */
    fun probe(context: Context): String {
        val root = RootFS.find(context).rootDir
        val file = prepare(context)
        val executable = File(context.applicationInfo.nativeLibraryDir, PROBE)
        check(executable.isFile && executable.canExecute()) { "PanVK 检查组件缺失" }
        val env = EnvVars().put("LD_LIBRARY_PATH", libraryPath(root))
            .put("VK_DRIVER_FILES", file).put("VK_ICD_FILENAMES", file)
            .put("VK_LOADER_DRIVERS_SELECT", "*").put("VK_LOADER_LAYERS_DISABLE", "~implicit~")
            .put("XDG_CACHE_HOME", File(context.cacheDir, "panvk")).put("PANVK_BC_AFRC", "0")
        return RuntimeProcessProbe.run(arrayOf(executable.path, File(root, "usr/lib/libvulkan.so.1").path),
            env, root, 8_000)
    }

    fun probeSucceeded(result: String): Boolean = result.startsWith("status=0;")

    fun configure(context: Context, env: EnvVars): File {
        val root = RootFS.find(context).rootDir
        val file = icd(root)
        check(isPrepared(context)) { "PanVK 未准备完成" }
        env.remove("ADRENOTOOLS_DRIVER_PATH"); env.remove("ADRENOTOOLS_DRIVER_NAME")
        env.remove("ADRENOTOOLS_HOOKS_PATH")
        // Preserve exact BCn decoding rather than the community build's lossy AFRC default.
        env.put("PANVK_BC_AFRC", "0").put("XDG_CACHE_HOME", File(context.cacheDir, "panvk"))
        return file
    }
}
