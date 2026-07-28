package cn.binbin323.statuslyricext

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import cn.binbin323.statuslyricext.misc.Constants
import cn.binbin323.statuslyricext.misc.LyricFeatureSettings
import cn.zhaiyifan.lyric.LyricUtils
import cn.zhaiyifan.lyric.model.Lyric
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MusicListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MusicListenerService"
        private const val NOTIFICATION_ID_LRC = 1
        private const val POLL_INTERVAL_MS = 50L
    }

    private val mMainHandler = Handler(Looper.getMainLooper())
    // Executor recreated lazily if shut down (handles onListenerDisconnected → onListenerConnected cycle)
    private var mFetchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var mPendingFetch: Future<*>? = null

    private var mMediaSessionManager: MediaSessionManager? = null
    private var mMediaController: MediaController? = null
    private var mNotificationManager: NotificationManager? = null

    private val mAllowedPackageList = mutableListOf<String>()

    private var mLyric: Lyric? = null
    private var mTranslatedLyric: Lyric? = null
    private var mLastDisplayedFromTime = -1L
    private var mCurrentSentenceIndex = 0
    private var mCurrentTranslatedSentenceIndex = 0
    private var mIsPlaying = false
    // Tracks the title for which a fetch was most recently started; used for stale-result detection.
    private var mFetchingTitle: String? = null
    private var mCurrentTrackKey: String? = null
    // Current song title shown in the notification content area.
    private var mCurrentSongTitle: String = ""
    private var mCurrentMediaPackage: String? = null

    private val mAllowedPackagesObserver = object : ContentObserver(mMainHandler) {
        override fun onChange(selfChange: Boolean) {
            updateAllowedPackageList()
            unbindSession()
            bindSession()
        }
    }

    // ── Tick runnable ─────────────────────────────────────────────────────────

    private val mTickRunnable = object : Runnable {
        override fun run() {
            if (!mIsPlaying || mMediaController == null) return
            val ps = mMediaController?.playbackState
            if (ps == null || ps.state != PlaybackState.STATE_PLAYING) {
                onPlaybackStopped()
                return
            }
            mLyric?.let { displayLyricAt(ps.position) }
            mMainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // ── Lyric fetch ───────────────────────────────────────────────────────────

    private fun fetchLyric(metadata: MediaMetadata) {
        val trackKey = buildTrackKey(metadata)
        if (trackKey == mCurrentTrackKey &&
            (mLyric != null || mPendingFetch?.isDone == false)) {
            return
        }
        mCurrentTrackKey = trackKey

        // Only clear the current lyric after confirming that the track actually changed.
        mNotificationManager?.cancel(NOTIFICATION_ID_LRC)
        mPendingFetch?.cancel(true)
        mPendingFetch = null
        mLyric = null
        mTranslatedLyric = null
        mCurrentSentenceIndex = 0
        mCurrentTranslatedSentenceIndex = 0
        mLastDisplayedFromTime = -1L

        val fetchTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (fetchTitle.isNullOrEmpty()) {
            Log.i(TAG, "fetchLyric: no title in metadata, skipping")
            return
        }
        // Record now so onLyricFetched can detect stale results without querying the controller.
        mFetchingTitle = fetchTitle
        mCurrentSongTitle = fetchTitle
        Log.i(TAG, "fetchLyric: $fetchTitle")

        // Recreate executor if a previous onListenerDisconnected shut it down.
        if (mFetchExecutor.isShutdown) {
            mFetchExecutor = Executors.newSingleThreadExecutor()
        }

        mPendingFetch = mFetchExecutor.submit {
            val result = LrcGetter.getLyric(applicationContext, metadata)
            if (Thread.currentThread().isInterrupted) return@submit
            mMainHandler.post { onLyricFetched(fetchTitle, result) }
        }
    }

    private fun buildTrackKey(metadata: MediaMetadata): String = listOf(
        mCurrentMediaPackage.orEmpty(),
        metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
        metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
        metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
        metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).toString()
    ).joinToString("\u0000")

    private fun onLyricFetched(fetchTitle: String, payload: LrcGetter.LyricPayload?) {
        // Compare against the title we last requested, NOT against the live controller metadata.
        // Querying mMediaController?.metadata at callback time is unreliable – the controller can
        // be null or metadata can temporarily be null during transitions, causing valid results to
        // be silently discarded.
        if (fetchTitle != mFetchingTitle) {
            Log.i(TAG, "onLyricFetched: stale, discarding [$fetchTitle] (current: $mFetchingTitle)")
            return
        }
        val lyric = payload?.lyric
        if (lyric == null || lyric.sentenceList.isEmpty()) {
            Log.i(TAG, "onLyricFetched: no lyric for $fetchTitle")
            return
        }
        Log.i(TAG, "onLyricFetched: ${lyric.sentenceList.size} lines loaded for $fetchTitle")
        mLyric = lyric
        mTranslatedLyric = payload.translatedLyric
        mCurrentSentenceIndex = 0
        mCurrentTranslatedSentenceIndex = 0
        mLastDisplayedFromTime = -1L
    }

    // ── Playback state helpers ────────────────────────────────────────────────

    private fun onPlaybackStarted() {
        Log.i(TAG, "onPlaybackStarted")
        mIsPlaying = true
        mCurrentSentenceIndex = 0
        mCurrentTranslatedSentenceIndex = 0
        mMainHandler.removeCallbacks(mTickRunnable)
        mMainHandler.post(mTickRunnable)
    }

    private fun onPlaybackStopped() {
        Log.i(TAG, "onPlaybackStopped")
        mIsPlaying = false
        mMainHandler.removeCallbacks(mTickRunnable)
        mNotificationManager?.cancel(NOTIFICATION_ID_LRC)
    }

    // ── Lyric display ─────────────────────────────────────────────────────────

    private fun displayLyricAt(positionMs: Long) {
        val lyric = mLyric ?: return
        if (lyric.sentenceList.isEmpty()) return

        val index = LyricUtils.getSentenceIndex(lyric, positionMs, mCurrentSentenceIndex, lyric.offset)
        if (index < 0) return

        mCurrentSentenceIndex = index
        val sentence = lyric.sentenceList[index]
        if (sentence.fromTime == mLastDisplayedFromTime) return
        mLastDisplayedFromTime = sentence.fromTime

        if (TextUtils.isEmpty(sentence.content)) return

        Log.i(TAG, "Lyric → ${sentence.content}")
        postLyricNotification(sentence.content, getTranslatedSentenceContent(sentence.fromTime))
    }

    private fun postLyricNotification(text: String, translationText: String?) {
        val nm = mNotificationManager ?: return
        val songTitle = mCurrentSongTitle.ifEmpty { text }

        val notification = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_LRC)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(songTitle)
            .setContentText(songTitle)
            .setTicker(text)
            .setShowWhen(false)
            .setOngoing(true)
            .build()

        notification.extras.putInt("ticker_icon", R.drawable.ic_music)
        notification.extras.putBoolean("ticker_icon_switch", false)
        notification.extras.putString(
            Constants.EXTRA_TICKER_ICON_PACKAGE,
            mCurrentMediaPackage
        )
        findCurrentMediaSmallIcon()?.let {
            notification.extras.putParcelable(Constants.EXTRA_TICKER_SMALL_ICON, it)
        }
        notification.extras.putString(Constants.EXTRA_TICKER_TRANSLATION, translationText)
        notification.flags = notification.flags or
                Constants.FLAG_ALWAYS_SHOW_TICKER or
                Constants.FLAG_ONLY_UPDATE_TICKER
        nm.notify(NOTIFICATION_ID_LRC, notification)
    }

    private fun findCurrentMediaSmallIcon(): Icon? {
        val controller = mMediaController ?: return null
        val packageName = controller.packageName
        val sessionToken = controller.sessionToken
        val mediaNotifications = activeNotifications
            ?.asSequence()
            ?.filter { it.packageName == packageName && it.notification.isMediaNotification }
            ?.toList()
            .orEmpty()

        return mediaNotifications
            .firstOrNull { getMediaSessionToken(it) == sessionToken }
            ?.notification
            ?.smallIcon
            ?: mediaNotifications.maxByOrNull { it.postTime }?.notification?.smallIcon
    }

    private fun getMediaSessionToken(sbn: StatusBarNotification): MediaSession.Token? =
        sbn.notification.extras.getParcelable(
            Notification.EXTRA_MEDIA_SESSION,
            MediaSession.Token::class.java
        )

    private fun getTranslatedSentenceContent(fromTime: Long): String? {
        val translatedLyric = mTranslatedLyric ?: return null
        if (translatedLyric.sentenceList.isEmpty()) return null

        val index = LyricUtils.getSentenceIndex(
            translatedLyric,
            fromTime,
            mCurrentTranslatedSentenceIndex,
            translatedLyric.offset
        )
        if (index < 0) return null

        mCurrentTranslatedSentenceIndex = index
        return translatedLyric.sentenceList[index].content.takeUnless { it.isBlank() }
    }

    // ── MediaController callbacks ─────────────────────────────────────────────

    private val mMediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            Log.i(TAG, "onPlaybackStateChanged: ${state?.state ?: "null"}")
            if (state == null) return
            if (state.state == PlaybackState.STATE_PLAYING) onPlaybackStarted()
            else onPlaybackStopped()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            Log.i(TAG, "onMetadataChanged: ${metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "null"}")
            if (metadata == null) return
            fetchLyric(metadata)
        }

        override fun onSessionDestroyed() {
            Log.i(TAG, "onSessionDestroyed")
            onPlaybackStopped()
            super.onSessionDestroyed()
        }
    }

    // ── Session management ────────────────────────────────────────────────────

    private val mSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            Log.i(TAG, "onActiveSessionsChanged: ${controllers?.size ?: "null"}")
            mMediaController?.unregisterCallback(mMediaCallback)
            mMediaController = null

            if (controllers.isNullOrEmpty()) {
                onPlaybackStopped()
                return@OnActiveSessionsChangedListener
            }

            var best: MediaController? = null
            for (c in controllers) {
                if (!mAllowedPackageList.contains(c.packageName)) {
                    continue
                }
                if (getControllerState(c) == PlaybackState.STATE_PLAYING) {
                    best = c
                    break
                }
                if (best == null) best = c
            }
            if (best == null) {
                onPlaybackStopped()
                return@OnActiveSessionsChangedListener
            }

            Log.i(TAG, "binding to: ${best.packageName}")
            mCurrentMediaPackage = best.packageName
            mMediaController = best
            mMediaController!!.registerCallback(mMediaCallback)
            mMediaController!!.metadata?.let { mMediaCallback.onMetadataChanged(it) }
            mMediaController!!.playbackState?.let { mMediaCallback.onPlaybackStateChanged(it) }
        }

    private fun getControllerState(c: MediaController): Int =
        c.playbackState?.state ?: PlaybackState.STATE_NONE

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "onListenerConnected")
        mNotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel()
        mMediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(LyricFeatureSettings.getAllowedPackagesKey()),
            false,
            mAllowedPackagesObserver
        )
        updateAllowedPackageList()
        bindSession()
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "onListenerDisconnected")
        onPlaybackStopped()
        unbindSession()
        mPendingFetch?.cancel(true)
        mPendingFetch = null
        // Do NOT shutdownNow() – if the service reconnects in the same process lifetime,
        // the executor must still be usable. fetchLyric() recreates it lazily when needed.
        contentResolver.unregisterContentObserver(mAllowedPackagesObserver)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    // ── Session bind / unbind ─────────────────────────────────────────────────

    private fun bindSession() {
        val msm = mMediaSessionManager ?: return
        val listener = ComponentName(this, MusicListenerService::class.java)
        msm.addOnActiveSessionsChangedListener(mSessionsListener, listener)
        mSessionsListener.onActiveSessionsChanged(msm.getActiveSessions(listener))
    }

    private fun unbindSession() {
        mMediaSessionManager?.removeOnActiveSessionsChangedListener(mSessionsListener)
        mMediaController?.unregisterCallback(mMediaCallback)
        mMediaController = null
    }

    private fun updateAllowedPackageList() {
        mAllowedPackageList.clear()
        mAllowedPackageList.addAll(LyricFeatureSettings.getAllowedPackages(this))
    }

    private fun ensureNotificationChannel() {
        val nm = mNotificationManager ?: return
        if (nm.getNotificationChannel(Constants.NOTIFICATION_CHANNEL_LRC) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    Constants.NOTIFICATION_CHANNEL_LRC,
                    "LRC",
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }
    }
}
