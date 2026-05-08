package cn.binbin323.statuslyricext

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import cn.binbin323.statuslyricext.misc.Constants
import cn.binbin323.statuslyricext.misc.LyricFeatureSettings
import com.android.settingslib.widget.MainSwitchPreference

class SettingsActivity : FragmentActivity() {

    companion object {
        private const val REQUEST_CODE_MEDIA_PERMISSION = 1001

        fun isNotificationListenerEnabled(context: Context?): Boolean {
            context ?: return false
            val pkgName = context.packageName
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Constants.SETTINGS_ENABLED_NOTIFICATION_LISTENERS
            )
            if (!TextUtils.isEmpty(flat)) {
                for (name in flat.split(":")) {
                    val cn = ComponentName.unflattenFromString(name) ?: continue
                    if (TextUtils.equals(pkgName, cn.packageName)) return true
                }
            }
            return false
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.collapsing_toolbar_base_layout)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.content_frame, SettingsFragment())
                .commit()
        }

        val collapsingToolbar = findViewById<Toolbar>(R.id.action_bar)
        setActionBar(collapsingToolbar)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_LRC, "LRC", NotificationManager.IMPORTANCE_MIN
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.createNotificationChannel(channel)
        }

        requestMediaPermissionIfNeeded()
    }

    private fun requestMediaPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val audioGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_MEDIA_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val imagesGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED
        val notifGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (audioGranted && imagesGranted && notifGranted) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS
            ),
            REQUEST_CODE_MEDIA_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_MEDIA_PERMISSION) return

        val granted = grantResults.isNotEmpty() && grantResults.all {
            it == PackageManager.PERMISSION_GRANTED
        }
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            permissions.any { !ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
        ) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: ActivityNotFoundException) {}
        }
    }

    class SettingsFragment : PreferenceFragmentCompat(),
        Preference.OnPreferenceClickListener {

        private var mMainSwitchPreference: MainSwitchPreference? = null
        private var mNotificationListenerPreference: Preference? = null
        private var mDetailsCategory: PreferenceCategory? = null
        private var mIgnoredPackagesPreference: Preference? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            mMainSwitchPreference = findPreference(Constants.PREFERENCE_KEY_MAIN_SWITCH)
            mNotificationListenerPreference =
                findPreference(Constants.PREFERENCE_KEY_NOTIFICATION_ACCESS)
            mDetailsCategory = findPreference(Constants.PREFERENCE_KEY_DETAILS_CATEGORY)
            mIgnoredPackagesPreference = findPreference(Constants.PREFERENCE_KEY_IGNORED_PACKAGES)
            mMainSwitchPreference?.isPersistent = false

            mMainSwitchPreference?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                val ctx = context ?: return@setOnPreferenceChangeListener false
                LyricFeatureSettings.setEnabled(ctx, enabled)
                updateDetailPreferencesEnabled(enabled)
                true
            }
            mNotificationListenerPreference?.onPreferenceClickListener = this
            syncState()
        }

        override fun onResume() {
            super.onResume()
            syncState()
        }

        override fun onPreferenceClick(preference: Preference): Boolean {
            if (preference == mNotificationListenerPreference) {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                return true
            }
            return false
        }

        private fun syncState() {
            val context = context ?: return
            val lyricEnabled = LyricFeatureSettings.isEnabled(context)
            mMainSwitchPreference?.isChecked = lyricEnabled
            updateNotificationListenerState()
            updateDetailPreferencesEnabled(lyricEnabled)
        }

        private fun updateNotificationListenerState() {
            val allowed = isNotificationListenerEnabled(context)
            mNotificationListenerPreference?.summary = getString(
                if (allowed) {
                    R.string.notification_listener_summary_on
                } else {
                    R.string.notification_listener_summary_off
                }
            )
        }

        private fun updateDetailPreferencesEnabled(enabled: Boolean) {
            mDetailsCategory?.isEnabled = enabled
            mNotificationListenerPreference?.isEnabled = enabled
            mIgnoredPackagesPreference?.isEnabled = enabled
        }
    }
}
