package cn.binbin323.statuslyricext.misc

import android.content.Context
import android.provider.Settings

object LyricFeatureSettings {
    fun setEnabled(context: Context, enabled: Boolean) {
        try {
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.STATUS_BAR_SHOW_LYRIC,
                if (enabled) 1 else 0
            )
        } catch (_: Exception) {
        }
    }

    fun isEnabled(context: Context, defaultValue: Boolean = false): Boolean = try {
        Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.STATUS_BAR_SHOW_LYRIC,
            if (defaultValue) 1 else 0
        ) == 1
    } catch (_: Exception) {
        defaultValue
    }
}
