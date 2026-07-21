package cn.binbin323.statuslyricext.misc

import android.content.Context
import android.provider.Settings

object LyricFeatureSettings {
    fun getAllowedPackages(context: Context): List<String> = try {
        val value = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.STATUS_BAR_LYRIC_ALLOWED_PACKAGES
        ).orEmpty()
        value.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }

    fun getAllowedPackagesKey(): String = Settings.Secure.STATUS_BAR_LYRIC_ALLOWED_PACKAGES
}
