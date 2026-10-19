/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.core

/**
 * Persisted identities from releases through 0.1.15. These are data/protocol compatibility
 * values, not product branding. Keep them stable across upgrades: in particular an Android
 * Keystore key cannot be renamed, and changing its alias would invalidate the saved session.
 */
object LegacyDataNames {
    const val SESSION_KEY_ALIAS = "aurora.steam.session.v1"
    const val RUNTIME_PREFERENCES = "aurora-runtime"
    const val LAUNCH_PREFERENCES = "aurora-game-launch"
    const val GUEST_PROCESS_PREFERENCES = "aurora-guest-processes"
    const val RUNTIME_DEFAULTS_KEY = "auroraRuntimeDefaults"
    const val VULKAN_CONFIG_PREFIX = "auroraVkConfig_"
    const val COMPONENT_UPDATE_PREFIX = "auroraUpdate_"
    const val COMPONENT_CHECK_WORK = "aurora-component-update-check"
    const val QUALCOMM_COMPONENT = "Aurora_Qualcomm_v863_1"
    const val CONTAINER_NAME = "Aurora"
    const val INSTALLED_MARKER = ".aurora-installed"
    const val STAGING_DIRECTORY = ".aurora-staging"
    const val RESERVED_PATH_PREFIX = ".aurora-"
    const val NATIVE_LIBRARY_PREFIX = "libaurora_"
    const val GUEST_SESSION_VARIABLE = "AURORA_GUEST_SESSION"
    const val BOX64_MEMORY_MARKER = "aurora_guest_memory_nx_v1"
    const val DOWNLOAD_WORKER = "dev.aurora.console.steam.GameDownloadWorker"
    const val COMPONENT_CHECK_WORKER = "dev.aurora.console.runtime.updates.ComponentCheckWorker"
}
