/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import java.io.File

/** Automatic, bounded Mesa copy helpers; independent of CPU profiles and Debug. */
object NativeFrameCopy {
    const val VARIABLE = "AURA_MESA_FRAME_COPY"
    const val GEOMETRY_VARIABLE = "AURA_MESA_FRAME_GEOMETRY"
    const val HOST_VARIABLE = "AURA_HOST_FRAME_COPY"
    const val DAMAGE_VARIABLE = "AURA_HOST_SHM_DAMAGE"
    const val REGIONS_VARIABLE = "AURA_MESA_COPY_REGIONS"

    fun enabled(env: EnvVars) = env.get(VARIABLE) == "1" || env.get(GEOMETRY_VARIABLE) == "1"

    fun configure(context: Context, env: EnvVars) {
        val optedOut = env.get(VARIABLE) == "0"
        val library = File(context.applicationInfo.nativeLibraryDir, NativeGraphicsTrace.LIBRARY)
        val available = !optedOut && library.isFile
        env.put(VARIABLE, if (available) "1" else "0")
        val regions = available && env.get(REGIONS_VARIABLE) != "0"
        env.put(REGIONS_VARIABLE, if (regions) "1" else "0")
        val geometry = !optedOut && env.get(GEOMETRY_VARIABLE) != "0" && library.isFile
        env.put(GEOMETRY_VARIABLE, if (geometry) "1" else "0")
        val host = env.get(HOST_VARIABLE) != "0"
        val hostConfigured = try {
            com.winlator.xserver.Drawable.configureHostFrameCopy(host)
            true
        } catch (_: LinkageError) { false }
        val damage = env.get(DAMAGE_VARIABLE) != "0"
        val damageConfigured = try {
            com.winlator.xserver.Drawable.configureHostShmDamage(damage)
            true
        } catch (_: LinkageError) { false }
        setLibrary(env, library.takeIf { available || geometry })
        RuntimeLaunchDiagnostics.detail(context,
            "MESA_FRAME_COPY_V1 state=${if (optedOut) "DISABLED" else if (available) "CONFIGURED" else "LIBRARY_MISSING"}; " +
                "scope=VERIFIED_MESA_RECTANGLES; min_bytes=8388608; max_workers=2; schedule=DYNAMIC_256K; " +
                "completion=SYNCHRONOUS; debug_required=NO; activation=NATIVE_COUNTERS; fps_gain=UNCONFIRMED")
        RuntimeLaunchDiagnostics.detail(context,
            "MESA_COPY_REGIONS_V1 state=${if (regions) "CONFIGURED" else "DISABLED"}; " +
                "scope=VERIFIED_FRONTBUFFER_COPY; compare=EXACT_FUSED_64B; equal_probe_bytes=4096; dense_fallback_bytes=4096; " +
                "completion=SYNCHRONOUS; gpu_readback=UNCHANGED; activation=NATIVE_COUNTERS; fps_gain=UNCONFIRMED")
        RuntimeLaunchDiagnostics.detail(context,
            "MESA_FRAME_GEOMETRY_V1 state=${if (geometry) "CONFIGURED" else "DISABLED"}; " +
                "scope=SINGLE_SWAP; invalidation=REQUEST_SERIAL_OR_EVENTS; activation=NATIVE_COUNTERS")
        RuntimeLaunchDiagnostics.detail(context,
            "HOST_FRAME_COPY_V1 state=${if (!hostConfigured) "UNAVAILABLE" else if (host) "CONFIGURED" else "DISABLED"}; " +
                "scope=CONTIGUOUS_DRAWABLE_COPY; max_workers=2; completion=SYNCHRONOUS; fps_gain=UNCONFIRMED")
        RuntimeLaunchDiagnostics.detail(context,
            "HOST_SHM_DAMAGE_V1 state=${if (!damageConfigured) "UNAVAILABLE" else if (damage) "CONFIGURED" else "DISABLED"}; " +
                "scope=FULL_PACKED_CPU_SURFACE; min_bytes=8388608; compare=EXACT_EDGE_ROWS_AND_16_BANDS; " +
                "upload=BOUNDED_ROW_REGIONS; max_regions=4; activation=NATIVE_COUNTERS; fps_gain=UNCONFIRMED")
    }

    internal fun hostReport(): String? = try {
        val counts = com.winlator.xserver.Drawable.hostFrameCopyCounters()
        if (counts == null || counts.size != 4 || counts.any { it < 0 } || counts[3] > 2) null else
            "HOST_FRAME_COPY_V1 scope=HOST_PROCESS_LIFETIME; parallel_calls=${counts[0]}; " +
                "parallel_bytes=${counts[1]}; serial_large_copies=${counts[2]}; workers=${counts[3]}; " +
                "counters_atomic_snapshot=NO; includes_helper_cpu=HOST_PROCESS_ONLY"
    } catch (_: LinkageError) { null }

    internal fun damageReport(): String? = try {
        val counts = com.winlator.xserver.Drawable.hostShmDamageCounters()
        if (counts == null || counts.size !in listOf(5, 7) || counts.any { it < 0 }) null else
            "HOST_SHM_DAMAGE_V1 scope=HOST_PROCESS_LIFETIME; checked_frames=${counts[0]}; " +
                "identical_frames=${counts[1]}; partial_frames=${counts[2]}; input_bytes=${counts[3]}; " +
                "copied_bytes=${counts[4]}; counters_atomic_snapshot=NO; comparison_reads=NOT_INCLUDED; gpu_readback=UNCHANGED" +
                if (counts.size == 7) "; policy=BOUNDED_ROW_REGIONS; multi_region_frames=${counts[5]}; copy_regions=${counts[6]}" else ""
    } catch (_: LinkageError) { null }

    internal fun setLibrary(env: EnvVars, library: File?) {
        val entries = env.get("LD_PRELOAD").orEmpty().split(Regex("[\\s:]+"))
            .filter { it.isNotBlank() && File(it).name != NativeGraphicsTrace.LIBRARY }
        val preload = (entries + listOfNotNull(library?.path)).joinToString(" ")
        if (preload.isEmpty()) env.remove("LD_PRELOAD") else env.put("LD_PRELOAD", preload)
    }
}
