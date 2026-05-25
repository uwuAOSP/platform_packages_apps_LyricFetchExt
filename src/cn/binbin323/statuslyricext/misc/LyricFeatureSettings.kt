package cn.binbin323.statuslyricext.misc

import android.content.Context
import android.provider.Settings

object LyricFeatureSettings {
    private const val KEY_ALLOWED_PACKAGES = "status_bar_lyric_allowed_packages"

    fun getAllowedPackages(context: Context): List<String> = try {
        val value = Settings.Secure.getString(context.contentResolver, KEY_ALLOWED_PACKAGES).orEmpty()
        value.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }

    fun getAllowedPackagesKey(): String = KEY_ALLOWED_PACKAGES
}
