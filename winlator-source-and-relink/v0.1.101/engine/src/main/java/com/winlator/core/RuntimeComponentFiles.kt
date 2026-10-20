/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import java.io.File

class RuntimeComponentException(val component: String, val reason: String) : IllegalStateException()

/** Tests inspect the same files as the launch path, including broken APK symlinks. */
object RuntimeComponentFiles {
    @JvmStatic fun requireFiles(root: File, component: String, vararg paths: String) {
        if (!hasFiles(root, *paths)) throw RuntimeComponentException(component, "FILES_MISSING_OR_UNREADABLE")
    }

    @JvmStatic fun hasFiles(root: File, vararg paths: String): Boolean = paths.all {
        File(root, it).let { file -> file.isFile && file.canRead() && file.length() > 0 }
    }

    @JvmStatic fun graphicsFiles(vulkan: String, openGL: String): Array<String> = buildList {
        when (vulkan) {
            "turnip" -> addAll(listOf("usr/lib/libvulkan_freedreno.so", "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json"))
            "vortek" -> addAll(listOf("usr/lib/libvulkan_vortek.so", "usr/share/vulkan/icd.d/vortek_icd.aarch64.json"))
        }
        if (openGL in setOf("zink", "virgl", "gladio")) add("usr/lib/libGL.so.1.7.0")
    }.toTypedArray()

    @JvmStatic fun checkBase(root: File, winePath: String) {
        val wine = File(root, "opt/wine")
        requireFiles(wine, "PROTON_ARM64EC", "bin/wine", "bin/wineserver", "lib/wine/aarch64-unix/ntdll.so",
            "lib/wine/aarch64-unix/libarm64ecfex.so", "lib/wine/aarch64-unix/libwow64fex.so",
            "lib/wine/aarch64-windows/libarm64ecfex.dll", "lib/wine/aarch64-windows/libwow64fex.dll",
            "share/wine/nls/l_intl.nls")
        if (!File(wine, "bin/wine").canExecute() || !File(wine, "bin/wineserver").canExecute())
            throw RuntimeComponentException("PROTON_ARM64EC", "EXECUTE_PERMISSION_MISSING")
    }

    @JvmStatic fun dxFiles(): Array<String> = listOf("system32", "syswow64").flatMap { arch ->
        listOf("d3d11.dll", "dxgi.dll", "d3d12.dll").map { "$arch/$it" }
    }.toTypedArray()

    @JvmStatic fun describe(wine: String, box: String, vulkan: String, openGL: String, wrapper: String, preset: String): String {
        fun safe(value: String) = value.takeIf { it.matches(Regex("[a-zA-Z0-9_.-]{1,40}")) } ?: "custom"
        return "组件配置：Wine=Proton-${Arm64EcRuntime.WINE}-ARM64EC; FEXCore=${Arm64EcRuntime.FEX}; ABI=Bionic; Vulkan=${safe(vulkan)}; OpenGL=${safe(openGL)}; DirectX=${safe(wrapper)}"
    }
}
