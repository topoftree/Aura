/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.core

/** Opt-in for a game's existing single-player mode; not an offline client or multiplayer emulation. */
object SinglePlayerCompatibility {
    fun environment(enabled: Boolean, input: EnvVars): EnvVars = EnvVars().apply {
        putAll(input)
        if (enabled) put("WINEDLLOVERRIDES", withoutOptionalPunkBuster(input.get("WINEDLLOVERRIDES")))
    }

    private fun withoutOptionalPunkBuster(overrides: String): String {
        val modules = setOf("pbcl", "pbcl64")
        val kept = overrides.split(';').mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator < 0) return@mapNotNull entry.takeIf { it.isNotBlank() }
            val names = entry.substring(0, separator).split(Regex("[, \\t]+")).filter(String::isNotEmpty)
            val remaining = names.filterNot { name ->
                name.replace('\\', '/').substringAfterLast('/').removePrefix("*").lowercase()
                    .removeSuffix(".dll") in modules
            }
            when {
                remaining.size == names.size -> entry
                remaining.isEmpty() -> null
                else -> remaining.joinToString(",") + entry.substring(separator)
            }
        }
        // Disable only the optional client modules, including full-path loads. Leave services,
        // authentication, other DLLs and networking untouched. Mandatory components may still fail.
        return (kept + "pbcl,*pbcl,pbcl64,*pbcl64=").joinToString(";")
    }
}
