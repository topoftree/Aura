/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import com.winlator.alsaserver.ALSAClient
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.XEnvironment
import com.winlator.xenvironment.components.ALSAServerComponent
import com.winlator.xenvironment.components.PulseAudioComponent
import java.io.File

object RuntimeAudio {
    @JvmStatic fun configure(context: Context, id: String, config: KeyValueSet, environment: XEnvironment, env: EnvVars) {
        val backend = AudioBackend.fromId(id)
        val root = environment.rootFS.rootDir
        val latency = config.getInt("latencyMillis", 64).coerceIn(16, 200)
        val volume = config.getFloat("volume", 1f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        val performance = config.getInt("performanceMode", 1).coerceIn(0, 2)
        clearEnvironment(env)
        when (backend) {
            AudioBackend.ALSA, AudioBackend.ALSA_REFLECTOR -> {
                val plugin = File(context.applicationInfo.nativeLibraryDir, "libaura_alsa_plugin.so")
                check(plugin.isFile) { "ALSA PCM plugin missing" }
                RuntimeComponentFiles.requireFiles(root, "ALSA", "usr/lib/libasound.so", "opt/wine/lib/wine/aarch64-unix/winealsa.so")
                val conf = File(context.filesDir, "audio/alsa.conf")
                check(conf.parentFile!!.isDirectory || conf.parentFile!!.mkdirs())
                conf.writeText(alsaConfiguration(plugin.path))
                env.put("ALSA_CONFIG_PATH", conf.path)
                    .put("ANDROID_ALSA_SERVER", root.path + UnixSocketConfig.ALSA_SERVER_PATH)
                    .put("ANDROID_ASERVER_USE_SHM", "true")
                val options = ALSAClient.Options.fromKeyValueSet(config).apply {
                    latencyMillis = latency.toShort(); this.volume = volume
                    reflector = backend == AudioBackend.ALSA_REFLECTOR
                }
                environment.addComponent(ALSAServerComponent(UnixSocketConfig.create(root.path, UnixSocketConfig.ALSA_SERVER_PATH), options))
            }
            AudioBackend.PULSE_AUDIO -> {
                RuntimeComponentFiles.requireFiles(root, "PULSEAUDIO", "usr/lib/libpulse.so", "opt/wine/lib/wine/aarch64-unix/winepulse.so")
                val socket = UnixSocketConfig.create(root.path, UnixSocketConfig.PULSE_SERVER_PATH)
                env.put("PULSE_SERVER", "unix:${socket.path}").put("PULSE_LATENCY_MSEC", latency)
                environment.addComponent(PulseAudioComponent(socket).apply {
                    this.volume = volume; performanceMode = performance
                })
            }
            AudioBackend.DIRECT_AUDIO -> Unit
        }
        RuntimeLaunchDiagnostics.detail(context, "AUDIO_BACKEND=${backend.id}; WINE_AUDIO=${backend.wineDriver}; " +
            if (backend == AudioBackend.DIRECT_AUDIO) "BUFFER=DRIVER_DEFAULT" else "LATENCY_MS=$latency; PERFORMANCE=$performance; CHANNELS=STEREO")
    }

    @JvmStatic fun clearEnvironment(env: EnvVars) {
        listOf("PULSE_SERVER", "PULSE_LATENCY_MSEC", "ALSA_CONFIG_PATH", "ALSA_PLUGIN_DIR",
            "ANDROID_ALSA_SERVER", "ANDROID_ASERVER_USE_SHM").forEach(env::remove)
    }

    @JvmStatic fun alsaConfiguration(pluginPath: String): String {
        require('"' !in pluginPath && '\n' !in pluginPath && '\r' !in pluginPath && '\\' !in pluginPath)
        return """
            pcm_type.android_aserver { lib "$pluginPath" }
            pcm.!default {
                type android_aserver
                hint { show on description "Aura Android Stereo" ioid "Output" }
            }
        """.trimIndent() + "\n"
    }
}
