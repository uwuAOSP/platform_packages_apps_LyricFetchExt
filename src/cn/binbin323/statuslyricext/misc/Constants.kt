package cn.binbin323.statuslyricext.misc

object Constants {
    const val NOTIFICATION_CHANNEL_LRC = "lrc"
    const val EXTRA_TICKER_ICON_PACKAGE = "ticker_icon_package"
    const val EXTRA_TICKER_SMALL_ICON = "ticker_small_icon"
    const val EXTRA_TICKER_TRANSLATION = "ticker_translation"

    const val FLAG_ALWAYS_SHOW_TICKER = 0x1000000
    // Tells the system to only refresh the ticker without re-showing the full notification.
    // Value matches APlayer's implementation.
    const val FLAG_ONLY_UPDATE_TICKER = 0x2000000
}
