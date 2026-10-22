/* Aura integration changes for 0.1.118, 2026-10-22.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import java.io.File

/** The bundled D3D11 runtime accepts the opt-in flag; its default remains off. */
object DxvkD3D11Update {
    const val REVISION = "2.7.1-arm64ec-aura-batch1"

    @JvmStatic fun prepare(context: Context, container: File) = DxvkDllUpdate.prepare(
        context, container, "d3d11", REVISION, "DXVK_D3D11_BATCH_RUNTIME_V1",
        "policy=HINTS_6_PENDING_2; default=OFF; loaded_config=SEE_DXVK_LOG")
}
