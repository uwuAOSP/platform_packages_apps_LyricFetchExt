package cn.binbin323.statuslyricext.misc

object Constants {
    const val SHARED_PREFERENCES_NAME = "preferences"

    const val PREFERENCE_KEY_MAIN_SWITCH = "status_bar_lyric"
    const val PREFERENCE_KEY_DETAILS_CATEGORY = "details"
    const val PREFERENCE_KEY_NOTIFICATION_ACCESS = "notification_listener"
    const val PREFERENCE_KEY_IGNORED_PACKAGES = "ignored_packages"

    const val NOTIFICATION_CHANNEL_LRC = "lrc"

    const val FLAG_ALWAYS_SHOW_TICKER = 0x1000000
    // Tells the system to only refresh the ticker without re-showing the full notification.
    // Value matches APlayer's implementation.
    const val FLAG_ONLY_UPDATE_TICKER = 0x2000000

    const val BROADCAST_IGNORED_APP_CHANGED = "ignored_app_changed"

    const val SETTINGS_ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
}
