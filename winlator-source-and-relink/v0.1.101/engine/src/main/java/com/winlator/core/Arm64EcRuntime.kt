/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.container.GraphicsDrivers
import com.winlator.xenvironment.RootFS
import com.winlator.xenvironment.RootFSInstaller
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

/** One architecture contract for installation, launch, graphics and component repair. */
object Arm64EcRuntime {
    const val ID = "proton11-fex2610-arm64ec-v1"
    const val ROOT = "rootfs-arm64ec"
    const val WINE = "11.0-2"
    const val FEX = "2610"
    const val DXVK = "2.7.1"
    const val VKD3D = "3.0.1"
    const val WINE_ID = "proton-11.0-2-arm64ec"
    const val AUDIO = "directaudio"

    @JvmStatic
    @Synchronized
    fun install(context: Context) {
        check(!RuntimeLaunchDiagnostics.isActive(context)) { "请先退出游戏再迁移运行环境" }
        NativeRuntime.configure(context)
        val root = RootFS.find(context).rootDir
        val marker = File(root, ".aura-runtime")
        if (marker.takeIf { it.isFile }?.readText() != ID) {
            check(!root.exists() || !File(root, "home").exists()) { "运行环境版本不匹配，请保留现有容器并更新迁移程序" }
            check(context.filesDir.usableSpace > 2L * 1024 * 1024 * 1024) { "FEXCore 运行环境安装需要至少 2 GB 空闲空间" }
            val staging = File(context.filesDir, ".arm64ec-installing")
            RuntimeTree.delete(staging); check(staging.mkdirs())
            RuntimeAssets.extract(context, "arm64ec/runtime.tzst", staging)
            RuntimeComponentFiles.requireFiles(staging, "PROTON_ARM64EC", "opt/wine/bin/wine", "opt/wine/bin/wineserver", "opt/wine/lib/wine/aarch64-unix/ntdll.so")
            File(staging, ".aura-runtime").writeText(ID)
            if (root.exists()) RuntimeTree.delete(root)
            check(staging.renameTo(root)) { "无法提交新的运行环境" }
            RootFS.find(context).createRFSVersionFile(RootFSInstaller.LATEST_VERSION.toInt())
        }
        NativeRuntime.repairInstalledLinks()
        NativeRuntime.ensureBundledLibrary("usr/lib/libSDL2.so")
        // Existing rootfs installations are not re-extracted on an APK upgrade.
        // Install newly bundled codecs from PackageManager's executable directory too.
        for (path in listOf("usr/lib/libgstcodecparsers-1.0.so",
            "usr/lib/gstreamer-1.0/libgstvideoparsersbad.so", "usr/lib/gstreamer-1.0/libgstlibav.so")) {
            NativeRuntime.ensureBundledLibrary(path)
        }
        ensureAlias(context, root)
        migrateContainers(context, root)
    }

    private fun ensureAlias(context: Context, root: File) {
        for ((alias, relative) in listOf("r" to "usr", "h" to "home/xuser")) {
            val link = File(context.filesDir, alias).toPath()
            val target = File(root, relative).toPath()
            if (Files.isSymbolicLink(link)) {
                if (link.toFile().canonicalFile == target.toFile().canonicalFile) continue
                Files.delete(link)
            } else check(!Files.exists(link, NOFOLLOW_LINKS)) { "原生组件路径已被占用" }
            Files.createSymbolicLink(link, target)
        }
    }

    private fun migrateContainers(context: Context, root: File) {
        val legacyHome = File(context.filesDir, "rootfs/home")
        val destination = File(root, "home").apply { mkdirs() }
        for (old in legacyHome.listFiles().orEmpty().sortedBy { it.name }) {
            if (!old.name.matches(Regex("xuser-[1-9][0-9]*")) || !old.isDirectory || Files.isSymbolicLink(old.toPath())) continue
            val target = File(destination, old.name)
            if (target.exists()) {
                check(File(target, ".aura-arm64ec").takeIf { it.isFile }?.readText() == ID) { "容器迁移标记不完整" }
                continue
            }
            val config = File(old, ".container")
            check(config.isFile && !Files.isSymbolicLink(config.toPath())) { "原有容器配置缺失，已保留原文件" }
            check(!Files.isSymbolicLink(File(old, ".wine").toPath())) { "原有 Wine 容器路径异常，已保留原文件" }
            for (name in listOf("system.reg", "user.reg", "userdef.reg")) {
                check(!Files.isSymbolicLink(File(old, ".wine/$name").toPath())) { "注册表路径异常，已保留原文件" }
            }
            val required = RuntimeTree.bytes(old, RuntimeTree::migrateEntry) + 1024L * 1024 * 1024
            check(context.filesDir.usableSpace > required) { "容器迁移空间不足；原容器和存档已保留" }
            RuntimeTree.migrate(old, target, { createPrefix(context, it) }) { staging ->
                configureRegistry(staging)
                val json = JSONObject(File(staging, ".container").readText())
                json.put("wineVersion", WINE_ID).put("audioDriver", AUDIO)
                json.remove("box64Preset")
                // Old component markers cannot imply that DLLs exist in the new Windows tree.
                json.optJSONObject("extraData")?.remove("wincomponents")
                File(staging, ".container").writeText(json.toString())
                File(staging, ".aura-arm64ec").writeText(ID)
            }
        }
    }

    @JvmStatic
    fun createPrefix(context: Context, container: File): Boolean {
        RuntimeAssets.extract(context, "arm64ec/prefix.tzst", container)
        val wine = File(RootFS.find(context).rootDir, "opt/wine/lib/wine")
        val windows = File(container, ".wine/drive_c/windows")
        for ((source, target) in listOf("aarch64-windows" to "system32", "i386-windows" to "syswow64")) {
            RuntimeTree.copy(File(wine, source), File(windows, target).apply { mkdirs() })
        }
        // The legacy UI addresses these helpers without system32; use the new Wine executables.
        for (name in listOf("explorer.exe", "regedit.exe")) {
            File(windows, "system32/$name").copyTo(File(windows, name), overwrite = true)
        }
        // Apply the Winlator input helpers after builtins, then the matching DirectX DLLs.
        RuntimeAssets.extract(context, "arm64ec/prefix.tzst", container) { file, _ ->
            file.takeIf { it.name in INPUT_DLLS || it.name == "winhandler.exe" }
        }
        RuntimeAssets.extract(context, "arm64ec/directx.tzst", windows)
        File(container, ".wine/.update-timestamp").delete()
        configureRegistry(container)
        WineMonoPackage.prepare(context, container)
        WineWindowsDependencies.prepare(context, container)
        WineBuiltinFonts.prepare(context, container)
        File(container, ".aura-arm64ec").writeText(ID)
        File(container, ".aura-directx").writeText(ID)
        return true
    }

    @JvmStatic
    @JvmOverloads
    fun configureRegistry(container: File, audio: String = AUDIO) {
        val prefix = File(container, ".wine")
        WineRegistryEditor(File(prefix, "system.reg")).use { registry ->
            registry.setStringValue("Software\\Microsoft\\Wow64\\amd64", null, "libarm64ecfex.dll")
            registry.setStringValue("Software\\Microsoft\\Wow64\\x86", null, "libwow64fex.dll")
            // Proton winebus enumerates our SDL loopback backend, not Android evdev.
            val bus = "System\\CurrentControlSet\\Services\\WineBus"
            registry.setDwordValue(bus, "Enable SDL", 1)
            registry.setDwordValue(bus, "Map Controllers", 1)
        }
        WineRegistryEditor(File(prefix, "user.reg")).use { registry ->
            registry.setStringValue("Software\\Wine\\Drivers", "Graphics", "x11")
            registry.setStringValue("Software\\Wine\\Drivers", "Audio", AudioBackend.fromId(audio).wineDriver)
            for (name in INPUT_DLLS) registry.setStringValue("Software\\Wine\\DllOverrides", name.removeSuffix(".dll"), "native,builtin")
            for (name in listOf("dxgi", "d3d8", "d3d9", "d3d10core", "d3d11", "d3d12", "d3d12core"))
                registry.setStringValue("Software\\Wine\\DllOverrides", name, "native,builtin")
        }
    }

    @JvmStatic
    fun normalize(container: Container) {
        check(File(container.rootDir, ".aura-arm64ec").readText() == ID) { "容器尚未完成 ARM64EC 迁移" }
        container.wineVersion = WINE_ID
        container.audioDriver = AudioBackend.normalize(container.audioDriver)
        val oldGraphics = GraphicsDrivers.parseIdentifiers(container.graphicsDriver)
        container.graphicsDriver = (if (oldGraphics[0] in setOf(GraphicsDrivers.TURNIP, GraphicsDrivers.PANVK)) oldGraphics[0] else "wrapper") + ",zink"
        if (container.graphicsDriverConfig.isNotEmpty()) {
            val configs = GraphicsDrivers.parseConfigs(container.graphicsDriver, container.graphicsDriverConfig)
            configs[0] = KeyValueSet().apply { for (pair in GraphicsDrivers.parseConfigs(container.graphicsDriver, container.graphicsDriverConfig)[0]) if (pair[0] != "version") put(pair[0], pair[1]) }
            container.graphicsDriverConfig = "${configs[0]}|${configs[1]}"
        }
        container.dxWrapper = "dxvk"
        container.putExtra("arm64ecRuntime", ID)
        container.saveData()
    }

    @JvmStatic
    fun preparePrefix(context: Context, container: Container) {
        check(File(container.rootDir, ".aura-arm64ec").takeIf { it.isFile }?.readText() == ID) { "容器架构不匹配" }
        WineServiceRuntime.prepare(context)
        WineWaitQueueRuntime.prepare(context, container.rootDir)
        configureRegistry(container.rootDir, container.audioDriver)
        RuntimeLaunchDiagnostics.detail(context, WineVirtualDisplayDefaults.prepare(
            File(container.rootDir, ".wine"), File(RootFS.find(context).rootDir, WineVirtualDisplayDefaults.LIBRARY)))
        val proxyDefaults = WineProxyDefaults.prepare(File(container.rootDir, ".wine/user.reg"))
        RuntimeLaunchDiagnostics.detail(context, "WINE_PROXY_DEFAULT_V1=$proxyDefaults")
        WineMonoPackage.prepare(context, container.rootDir)
        WineWindowsDependencies.prepare(context, container.rootDir)
        WineBuiltinFonts.prepare(context, container.rootDir)
        val manifest = context.assets.open("arm64ec/manifest.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("files") }
        val wine = File(RootFS.find(context).rootDir, "opt/wine/lib/wine/aarch64-windows")
        val fexFiles = listOf("libarm64ecfex.dll", "libwow64fex.dll").map { name ->
            val entry = manifest.getJSONObject("opt/wine/lib/wine/aarch64-windows/$name")
            WineStartupFile("drive_c/windows/system32/$name", entry.getLong("bytes"), entry.getString("sha256"))
        }
        WineStartupIntegrity.verifyAndRepair(File(container.rootDir, ".wine"), fexFiles) { paths ->
            for (path in paths) File(wine, File(path).name).copyTo(File(container.rootDir, ".wine/$path"), overwrite = true)
        }
        val windows = File(container.rootDir, ".wine/drive_c/windows")
        if (File(container.rootDir, ".aura-directx").takeIf { it.isFile }?.readText() != ID ||
            !RuntimeComponentFiles.hasFiles(windows, *RuntimeComponentFiles.dxFiles())) {
            RuntimeAssets.extract(context, "arm64ec/directx.tzst", windows)
            File(container.rootDir, ".aura-directx").writeText(ID)
        }
        DxvkD3D9Update.prepare(context, container.rootDir)
        File(container.rootDir, ".wine/dosdevices").mkdirs()
        WineUtils.createDosdevicesSymlinks(container, true)
        WineUtils.changeBrowsersRegistryKey(container, true)
    }

    @JvmStatic
    fun graphics(context: Context, vulkan: String, config: KeyValueSet, env: EnvVars) {
        val root = RootFS.find(context).rootDir
        check(vulkan in setOf("turnip", "panvk", "wrapper")) { "不兼容的图形组件；ARM64EC 需要 Bionic 图形库" }
        val icd = when (vulkan) { "turnip" -> "freedreno"; "panvk" -> "panvk"; else -> "wrapper" }
        val path = if (vulkan == "panvk") PanVkRuntime.configure(context, env)
            else File(root, "usr/share/vulkan/icd.d/${icd}_icd.aarch64.json")
        RuntimeComponentFiles.requireFiles(root, "BIONIC_GRAPHICS", "usr/lib/libGL.so.1", "usr/lib/libvulkan.so.1", path.relativeTo(root).path)
        env.put("VK_ICD_FILENAMES", path).put("VK_DRIVER_FILES", path)
            .put("GALLIUM_DRIVER", "zink").put("MESA_LOADER_DRIVER_OVERRIDE", "zink")
            .put("LIBGL_NO_DRAWARRAYS", "1").put("vblank_mode", "0")
        if (vulkan == "wrapper") {
            val driver = config.get("adrenotoolsDriver")
            val library = GeneralComponents.getDefinitivePath(GeneralComponents.Type.ADRENOTOOLS_DRIVER, context, driver)
            env.put("ADRENOTOOLS_HOOKS_PATH", File(root, "usr/lib").path + "/")
            if (library != null) {
                env.put("ADRENOTOOLS_DRIVER_PATH", File(library).parent + "/")
                env.put("ADRENOTOOLS_DRIVER_NAME", File(library).name)
            } else {
                check(driver.isEmpty() || driver == "System") { "所选驱动文件缺失" }
                env.remove("ADRENOTOOLS_DRIVER_PATH"); env.remove("ADRENOTOOLS_DRIVER_NAME")
            }
        }
        RuntimeLaunchDiagnostics.detail(context, "GRAPHICS_ABI=BIONIC; WSI=${if (vulkan == "panvk") "X11_PANVK" else "X11_DRI3"}; VULKAN=$icd; OPENGL=ZINK")
        RuntimeLaunchDiagnostics.detail(context, "GAMEPAD_BRIDGE=SDL2_WINLATOR_UDP_V1; HOTPLUG_POLL_MS=250")
    }

    @JvmStatic
    fun environment(context: Context, input: EnvVars): EnvVars {
        val root = RootFS.find(context).rootDir
        val wine = File(root, "opt/wine")
        val unix = File(wine, "lib/wine/aarch64-unix")
        val result = EnvVars()
        for (entry in input) { val key = requireNotNull(entry); if (!key.startsWith("BOX64_") && !key.startsWith("BOX86_")) result.put(key, input.get(key)) }
        result.put("HOME", File(root, "home/xuser")).put("USER", "xuser").put("WINEUSERNAME", "xuser")
            .put("WINEPREFIX", File(root, RootFS.WINEPREFIX)).put("TMPDIR", File(root, "tmp"))
            .put("PREFIX", File(root, "usr")).put("WINEARCH", "win64").put("DISPLAY", ":0").put("PATH", "${wine}/bin:/system/bin")
            .put("WINEDLLPATH", "${wine}/lib/wine").put("WINESERVER", File(wine, "bin/wineserver"))
            .put("LD_LIBRARY_PATH", "$unix:" + if (input.get("VK_DRIVER_FILES") == PanVkRuntime.icd(root).path)
                PanVkRuntime.libraryPath(root) else "${root}/usr/lib")
            .put("LD_PRELOAD", File(context.applicationInfo.nativeLibraryDir, "libaura_wine_paths.so"))
            .put("AURA_WINE_UNIX_DIR", unix)
            .put("AURA_WINE_NATIVE_LOADER", File(unix, "wine").canonicalPath)
            .put("AURA_WINE_NATIVE_NTDLL", File(unix, "ntdll.so").canonicalPath)
            // wineserver --version skips NLS initialization. Actual startup needs its
            // logical bin directory to locate ../share/wine/nls, not the APK hash path.
            .put("AURA_WINE_BIN_DIR", File(wine, "bin"))
            .put("AURA_WINE_NATIVE_SERVER", File(wine, "bin/wineserver").canonicalPath)
            .put("ANDROID_SYSVSHM_SERVER", File(root, "tmp/.sysvshm/SM0"))
            .put("WINEFSYNC", "0").put("WINENTSYNC", "0")
            .put("WINE_NO_DUPLICATE_EXPLORER", "0").put("LC_ALL", "C.UTF-8")
            .put("GALLIUM_DRIVER", "zink").put("MESA_LOADER_DRIVER_OVERRIDE", "zink")
            .put("VK_LOADER_DRIVERS_SELECT", "*").put("VK_LOADER_LAYERS_DISABLE", "~implicit~")
            .put("FONTCONFIG_PATH", File(root, "usr/etc/fonts"))
            .put("XDG_CACHE_HOME", File(root, "home/xuser/.cache"))
            .put("GST_PLUGIN_PATH_1_0", File(root, "usr/lib/gstreamer-1.0"))
            .put("GST_REGISTRY_FORK", "no")
        // Container/shortcut and per-launch choices take precedence over the default.
        // FSync and NTSync above remain unavailable in this Android runtime.
        if (!result.has("WINEESYNC")) result.put("WINEESYNC", "1")
        NativeFdTrace.configure(context, result)
        NativeWakeTrace.configure(context, result)
        RuntimeLaunchDiagnostics.detail(context, WineNetworkEnvironment.apply(result, WineNetworkEnvironment.read(context)))
        if (!RuntimeDiagnostics.sessionEnabled(context)) RuntimeDiagnostics.silence(result)
        return result
    }

    @JvmStatic
    fun verify(context: Context): String {
        val root = RootFS.find(context).rootDir
        val files = context.assets.open("arm64ec/manifest.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("files") }
        var count = 0
        for (name in listOf("opt/wine/lib/wine/aarch64-unix/wine", "opt/wine/bin/wineserver", "opt/wine/lib/wine/aarch64-unix/ntdll.so",
            "opt/wine/lib/wine/aarch64-unix/libarm64ecfex.so", "opt/wine/lib/wine/aarch64-unix/libwow64fex.so",
            "opt/wine/lib/wine/aarch64-windows/libarm64ecfex.dll", "opt/wine/lib/wine/aarch64-windows/libwow64fex.dll",
            "opt/wine/share/wine/nls/l_intl.nls", "usr/lib/libSDL2.so")) {
            val expected = files.getJSONObject(name); val file = File(root, name)
            check(file.isFile && file.length() == expected.getLong("bytes")) { "ARM64EC 组件缺失：${file.name}" }
            val hash = file.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            check(hash == expected.getString("sha256")) { "ARM64EC 组件校验失败：${file.name}" }; count++
        }
        return "Proton Wine $WINE / FEXCore $FEX / ARM64EC：$count 个启动文件 SHA-256 通过\n" +
            "WINE_NATIVE_NTDLL_V1 sha256=${files.getJSONObject("opt/wine/lib/wine/aarch64-unix/ntdll.so").getString("sha256")}; SHA256_VERIFIED"
    }

    private val INPUT_DLLS = setOf("xinput1_1.dll", "xinput1_2.dll", "xinput1_3.dll", "xinput1_4.dll", "xinput9_1_0.dll", "dinput.dll", "dinput8.dll")
}
