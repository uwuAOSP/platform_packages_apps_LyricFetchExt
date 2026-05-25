package cn.binbin323.statuslyricext

import android.app.NotificationManager
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        grantNotificationListenerAccessIfNeeded(context)
    }

    private fun grantNotificationListenerAccessIfNeeded(context: Context) {
        if (isNotificationListenerEnabled(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val listener = ComponentName(context, MusicListenerService::class.java)
        try {
            manager.setNotificationListenerAccessGranted(listener, true, false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to grant notification listener access", e)
        }
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        )
        if (TextUtils.isEmpty(flat)) {
            return false
        }
        for (name in flat.split(":")) {
            val component = ComponentName.unflattenFromString(name) ?: continue
            if (TextUtils.equals(context.packageName, component.packageName)) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
