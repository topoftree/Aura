/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

/** DXVK's INI file, keeping launch defaults before the upstream GTA V section. */
object DxvkConfig {
    @JvmStatic
    fun content(config: KeyValueSet): String = buildString {
        fun option(name: String, key: String) {
            val value = config.get(key).toIntOrNull()
            if (value != null && value > 0) appendLine("$name = $value")
        }
        // These describe memory to the game; DXVK does not enforce a hard allocation limit.
        option("dxgi.maxDeviceMemory", "maxDeviceMemory")
        option("dxgi.maxSharedMemory", "maxDeviceMemory")
        // DXVK also supports negative (vsync-relative) and fractional frame-rate limits.
        val framerate = config.get("framerate")
        if (framerate.toFloatOrNull()?.let { it.isFinite() && it != 0f } == true) {
            appendLine("dxgi.maxFrameRate = $framerate")
            appendLine("d3d9.maxFrameRate = $framerate")
        }
        option("dxgi.maxFrameLatency", "maxFrameLatency")
        option("d3d9.maxFrameLatency", "maxFrameLatency")
        option("dxvk.numCompilerThreads", "numCompilerThreads")
        if (config.get("auraBatchSubmissions") == "true") appendLine("d3d11.auraBatchSubmissions = True")
        when (config.get("trackPipelineLifetime").lowercase()) {
            "true" -> appendLine("dxvk.trackPipelineLifetime = True")
            "false" -> appendLine("dxvk.trackPipelineLifetime = False")
        }
        val device = config.get("customDevice").split(':', limit = 3)
        if (device.size == 3) {
            appendLine("dxgi.customDeviceId = ${device[0]}")
            appendLine("dxgi.customVendorId = ${device[1]}")
            appendLine("d3d9.customDeviceId = ${device[0]}")
            appendLine("d3d9.customVendorId = ${device[1]}")
            appendLine("dxgi.customDeviceDesc = \"${device[2]}\"")
            appendLine("d3d9.customDeviceDesc = \"${device[2]}\"")
        }
        appendLine("d3d11.constantBufferRangeCheck = \"True\"")
        appendLine()
        appendLine("[GTA5.exe]")
        appendLine("dxgi.maxDeviceMemory = 512")
        appendLine("dxgi.maxSharedMemory = 512")
        val mgsExecutable = config.get(MgsGroundZeroesCompatibility.OEM_EXECUTABLE_CONFIG)
        if (config.get(MgsGroundZeroesCompatibility.OEM_TILER_CONFIG) == "false" &&
            mgsExecutable.equals("MgsGroundZeroes.exe", true)) {
            appendLine()
            appendLine("[$mgsExecutable]")
            appendLine("dxvk.tilerMode = False")
        }
    }
}
