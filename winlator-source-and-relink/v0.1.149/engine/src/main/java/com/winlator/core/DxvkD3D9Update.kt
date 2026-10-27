/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import java.io.File

typealias DxvkD3D9File = DxvkDllFile
typealias DxvkD3D9Result = DxvkDllResult

object DxvkD3D9Update {
    const val REVISION = "2.7.1-arm64ec-aura-flow1"

    @JvmStatic fun prepare(context: Context, container: File) = DxvkDllUpdate.prepare(
        context, container, "d3d9", REVISION, "DXVK_D3D9_FIX_V1", "fix=EMPTY_STRETCH_RECT; manual_flow=AVAILABLE")

    fun repair(container: File, entries: List<DxvkD3D9File>,
               extract: (File, Set<String>) -> Unit): DxvkD3D9Result =
        DxvkDllUpdate.repair(container, "d3d9", entries, extract)
}
