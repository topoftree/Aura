/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import android.content.Intent
import android.system.Os
import com.winlator.xenvironment.XEnvironment
import com.winlator.xenvironment.components.SharedGpuPresentComponent
import com.winlator.xserver.XServer
import com.winlator.xenvironment.RootFS
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A separate Mesa build is used only for an explicit game experiment. */
object SharedGpuRuntime {
    private const val DIRECTORY = "AURA_SHARED_GPU_LIBRARY_DIR"
    private const val SOCKET = "AURA_SHARED_GPU_SOCKET"

    /** Integrated candidates, not proof of device AHB import. Never choose a different driver. */
    fun supportsDriver(id: String?): Boolean = id == "turnip-26-r8" || id == "turnip-26.2.5"

    fun eligible(intent: Intent): Boolean = PerGameLaunch.hasSnapshot(intent) &&
        PerGameLaunch.automaticShaderCache(intent) && PerGameLaunch.read(intent).sharedGpu &&
        supportsDriver(intent.getStringExtra(GameGraphicsOverride.DRIVER))

    class Observation {
        private val seen = mutableSetOf<String>()
        @Synchronized fun accept(line: String): String? {
            if (line !in setOf("AURA_SHARED_GPU_V1 state=READY", "AURA_SHARED_GPU_V1 state=UNSUPPORTED",
                    "AURA_SHARED_GPU_V1 state=FALLBACK") || !seen.add(line)) return null
            return line + "; scope=OBSERVED_ZINK_CONTEXTS; activation=HOST_ACCEPTED_PRESENTS; fps=UNCONFIRMED"
        }
    }

    fun observe(env: EnvVars): Observation? = if (env.get(SOCKET).orEmpty().isNotEmpty()) Observation() else null

    @JvmStatic fun configure(context: Context, intent: Intent, server: XServer, environment: XEnvironment, env: EnvVars) {
        env.remove(DIRECTORY); env.remove(SOCKET)
        com.winlator.renderer.SharedGpuTexture.resetCounters()
        if (!PerGameLaunch.hasSnapshot(intent) || !PerGameLaunch.read(intent).sharedGpu) return
        if (!eligible(intent)) {
            RuntimeLaunchDiagnostics.detail(context, "SHARED_GPU_V2 state=INACTIVE; reason=DRIVER_OR_NON_GAME; saved=UNCHANGED")
            return
        }
        val prepared = runCatching {
            val record = context.assets.open("arm64ec/shared-gpu.json").bufferedReader().use { JSONObject(it.readText()) }
            val library = File(context.applicationInfo.nativeLibraryDir, "libaura_zink_shared.so")
            check(library.length() == record.getLong("bytes"))
            val hash = MessageDigest.getInstance("SHA-256")
            library.inputStream().use { stream ->
                val buffer = ByteArray(65536)
                while (true) { val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            }
            check(hash.digest().joinToString("") { "%02x".format(it) } == record.getString("sha256"))
            val dir = File(RootFS.find(context).rootDir, "usr/lib/aura-shared-gpu")
            check(dir.mkdirs() || dir.isDirectory)
            for (name in listOf("libGL.so", "libGL.so.1", "libGL.so.1.5.0")) {
                val link = File(dir, name)
                link.delete()
                Os.symlink(library.path, link.path)
            }
            // A late old worker must never unlink the next session's endpoint.
            val socket = File(RootFS.find(context).rootDir, "tmp/.aura-gpu-${System.nanoTime().toString(16)}").path
            environment.addComponent(SharedGpuPresentComponent(server, socket))
            env.put(DIRECTORY, dir.path).put(SOCKET, socket)
        }.isSuccess
        RuntimeLaunchDiagnostics.detail(context, "SHARED_GPU_V1 state=${if (prepared) "REQUESTED" else "MODULE_UNAVAILABLE"}; " +
            "path=ZINK_AHB; driver=${intent.getStringExtra(GameGraphicsOverride.DRIVER)}; slots=2; " +
            "activation=HOST_ACCEPTED_PRESENTS; performance=UNCONFIRMED")
    }

    fun environment(env: EnvVars) {
        val path = env.get(DIRECTORY).orEmpty()
        if (path.isNotEmpty() && env.get(SOCKET).orEmpty().isNotEmpty())
            env.put("LD_LIBRARY_PATH", path + ":" + env.get("LD_LIBRARY_PATH"))
    }
}
