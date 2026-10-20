/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import com.winlator.xenvironment.RootFS
import java.io.File
import java.nio.file.Files
import java.util.Locale

/** An offline installer for Wine's normal appwiz/mscoree workflow, not Unity's private Mono. */
object WineMonoPackage {
    const val VERSION = "10.4.1"
    const val NAME = "wine-mono-$VERSION-x86.msi"
    const val SHA256 = "071f4b2887e1c97a11d791ff3d65be9429eed6dec4c2708888bfd546ba358e23"
    const val BYTES = 85_504_000L
    private val entry = WineStartupFile(NAME, BYTES, SHA256)

    @JvmStatic
    @Synchronized
    fun prepare(context: Context, container: File) {
        val directory = File(RootFS.find(context).rootDir, "opt/wine/share/wine/mono")
        val wine = File(RootFS.find(context).rootDir, "opt/wine").canonicalFile.toPath()
        if (Files.isSymbolicLink(directory.toPath()) || !directory.canonicalFile.toPath().startsWith(wine))
            throw RuntimeComponentException("WINE_MONO_PACKAGE", "DIRECTORY_OUTSIDE_WINE")
        if (!directory.isDirectory && !directory.mkdirs())
            throw RuntimeComponentException("WINE_MONO_PACKAGE", "CREATE_DIRECTORY_FAILED")
        // Verify the actual MSI against the hash compiled into this Proton's appwiz.cpl.
        // A damaged cache is repaired independently of the base runtime/container version.
        WineStartupIntegrity.verifyAndRepair(directory, listOf(entry)) {
            RuntimeAssets.extract(context, "arm64ec/wine-mono.tzst", directory)
        }
        val prefix = File(container, ".wine")
        val register = WineRegistryEditor(File(prefix, "user.reg")).use { registry ->
            // appwiz prepends \\?\unix itself. This must be an Android path, not Z:\....
            registry.setStringValue("Software\\Wine\\Dotnet", "MonoCabDir", directory.absolutePath)
            needsRegistration(prefix, registry.getStringValue("Software\\Wine\\DllOverrides", "mscoree"))
        }
        // A user may have cancelled/failed Mono installation on a previous boot, whose
        // update timestamp is already committed. Retry just mscoree registration on the
        // next Wine boot, without recreating or updating the whole prefix. HKLM RunOnce
        // is synchronous in this Wine; it completes before normal applications start.
        // Wine's own installer checks existing versions and installs from MonoCabDir.
        WineRegistryEditor(File(prefix, "system.reg")).use { registry ->
            val key = "Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce"
            val name = "AuraWineMono$VERSION"
            if (register) registry.setStringValue(key, name,
                "\"C:\\windows\\system32\\regsvr32.exe\" /s mscoree.dll")
            else registry.removeValue(key, name)
        }
        RuntimeLaunchDiagnostics.detail(context,
            "WINE_MONO_PACKAGE=$VERSION; SHA256_VERIFIED; INSTALLATION=WINE_MANAGED; REGISTRATION_QUEUED=$register")
    }

    fun needsRegistration(prefix: File, mscoreeOverride: String?): Boolean {
        // Respect a deliberately installed native .NET Framework or disabled mscoree.
        val mode = mscoreeOverride?.lowercase(Locale.ROOT)?.replace(" ", "")
        if (mode != null && (mode.isEmpty() || mode in setOf("disabled", "d", "n") || mode.startsWith("native") || mode.startsWith("n,"))) return false
        return listOf("libmono-2.0-x86.dll", "libmono-2.0-x86_64.dll", "mono-2.0.dll", "libmono-2.0.dll")
            .none { File(prefix, "drive_c/windows/mono/mono-2.0/bin/$it").isFile }
    }
}
