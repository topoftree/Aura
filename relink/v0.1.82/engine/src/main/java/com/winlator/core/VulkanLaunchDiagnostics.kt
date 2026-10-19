/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.core

import android.content.Context

/** Only fixed startup events and numeric Vulkan results; never called for frame rendering. */
object VulkanLaunchDiagnostics {
    fun eventName(event: Int): String? = when (event) {
        1 -> "INSTANCE_EXTENSIONS_BEGIN"
        2 -> "INSTANCE_EXTENSIONS_END"
        3 -> "CREATE_INSTANCE_BEGIN"
        4 -> "CREATE_INSTANCE_RESULT"
        5 -> "INSTANCE_SETUP_RETURNED"
        6 -> "CREATE_DEVICE_BEGIN"
        7 -> "CREATE_DEVICE_RESULT"
        8 -> "DEVICE_SETUP_READY"
        else -> null
    }

    @JvmStatic fun loaderBegin(context: Context, custom: Boolean) =
        RuntimeLaunchDiagnostics.detail(context, "VULKAN_LOADER_BEGIN source=${if (custom) "CUSTOM_ADRENOTOOLS" else "SYSTEM"}")

    @JvmStatic fun loaderResult(context: Context, result: Int) {
        val label = when (result) { 0 -> "READY"; 1 -> "OPEN_FAILED"; 2 -> "MISSING_ENTRY_POINTS"; else -> "UNKNOWN_FAILURE" }
        RuntimeLaunchDiagnostics.detail(context, "VULKAN_LOADER_$label")
        if (result != 0) throw RuntimeComponentException("VULKAN_LOADER", label)
    }

    @JvmStatic fun event(context: Context, event: Int, result: Int) {
        eventName(event)?.let {
            val key = if (event == 5) "driverId" else "result"
            RuntimeLaunchDiagnostics.detail(context, "VORTEK_$it $key=$result")
        }
    }
}
