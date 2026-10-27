/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.core

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.LocaleList
import androidx.preference.PreferenceManager
import java.util.Locale

/** An explicit Wine choice, independent of Aura and the Android system language. */
enum class WineLanguage(val tag: String, val nativeName: String) {
    ENGLISH("en-US", "English"), CHINESE("zh-CN", "简体中文");

    companion object {
        const val PREFERENCE = "wine_language_v1"

        // Old lc_index values 1/2 meant Portuguese/Russian, never Chinese.
        fun fromTag(tag: String?): WineLanguage = entries.firstOrNull { it.tag == tag } ?: ENGLISH
        fun fromIndex(index: Int): WineLanguage = entries.getOrNull(index) ?: ENGLISH
    }

    fun applyEnvironment(env: EnvVars) {
        // Bionic supports only C/C.UTF-8. Wine's Android locale adapter reads
        // this separate choice without changing Unix file-name encoding.
        env.put("LC_ALL", "C.UTF-8").put("AURA_WINE_LOCALE", tag)
    }
}

object LocaleHelper {
    @JvmStatic
    fun getLanguage(context: Context): WineLanguage = WineLanguage.fromTag(
        PreferenceManager.getDefaultSharedPreferences(context).getString(WineLanguage.PREFERENCE, null))

    @JvmStatic
    fun getLocaleIndex(context: Context): Int = getLanguage(context).ordinal

    @JvmStatic
    fun saveLocaleIndex(editor: SharedPreferences.Editor, index: Int) {
        editor.putString(WineLanguage.PREFERENCE, WineLanguage.fromIndex(index).tag).remove("lc_index")
    }

    @JvmStatic
    fun setSystemLocale(context: Context): Context {
        val configuration = Configuration(context.resources.configuration)
        val locale = Locale.forLanguageTag(getLanguage(context).tag)
        configuration.setLocales(LocaleList(locale))
        configuration.setLayoutDirection(locale)
        // Never change Locale.setDefault or mutate Aura's shared Configuration.
        return context.createConfigurationContext(configuration)
    }

    @JvmStatic
    fun setEnvVars(context: Context, envVars: EnvVars) = getLanguage(context).applyEnvironment(envVars)
}
