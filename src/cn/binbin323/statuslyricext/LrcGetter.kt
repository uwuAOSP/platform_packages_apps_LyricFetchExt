package cn.binbin323.statuslyricext

import android.content.Context
import android.media.MediaMetadata
import android.text.TextUtils
import android.util.Log
import cn.binbin323.statuslyricext.provider.ILrcProvider
import cn.binbin323.statuslyricext.provider.KugouProvider
import cn.binbin323.statuslyricext.provider.NeteaseProvider
import cn.binbin323.statuslyricext.provider.utils.LyricSearchUtil
import cn.zhaiyifan.lyric.LyricUtils
import cn.zhaiyifan.lyric.model.Lyric
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

object LrcGetter {

    private const val TAG = "LrcGetter"
    private val HEX = "0123456789ABCDEF".toCharArray()
    private val sNeteaseProvider = NeteaseProvider()
    private val sKugouProvider = KugouProvider()
    private val sExecutor = Executors.newCachedThreadPool()

    data class LyricPayload(
        val lyric: Lyric,
        val translatedLyric: Lyric? = null
    )

    fun getLyric(context: Context, metadata: MediaMetadata): LyricPayload? {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (TextUtils.isEmpty(title)) return null

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val cacheKey = "$title,$artist,$album,$duration"
        val cacheBase = sha1Hex("v2:$cacheKey")
        val cacheFile = File(context.cacheDir, "$cacheBase.lrc")
        val translatedCacheFile = File(context.cacheDir, "$cacheBase.tlrc")

        // Try cache first
        if (cacheFile.exists()) {
            val cached = LyricUtils.parseLyric(cacheFile, "UTF-8")
            if (cached.sentenceList.isNotEmpty()) {
                Log.i(TAG, "cache hit (${cached.sentenceList.size} lines): $title")
                val translatedCached =
                    if (translatedCacheFile.exists()) {
                        LyricUtils.parseLyric(translatedCacheFile, "UTF-8").takeIf {
                            it.sentenceList.isNotEmpty()
                        }
                    } else {
                        null
                    }
                return LyricPayload(cached, translatedCached)
            }
            // Corrupted cache – delete and re-fetch
            cacheFile.delete()
            translatedCacheFile.delete()
        }

        // Query active providers in parallel, pick the best metadata match.
        val providers: List<Pair<String, ILrcProvider>> = listOf(
            "kugou" to sKugouProvider,
            "netease" to sNeteaseProvider
        )
        val futures: List<Pair<String, Future<ILrcProvider.LyricResult?>>> = providers.map { (name, provider) ->
            name to sExecutor.submit<ILrcProvider.LyricResult?> {
                try {
                    provider.getLyric(metadata)
                } catch (e: Exception) {
                    Log.w(TAG, "$name provider failed for: $title", e)
                    null
                }
            }
        }

        val validResults = futures
            .mapNotNull { (name, future) ->
                try {
                    val res = future.get(5, TimeUnit.SECONDS)
                    if (res != null && LyricSearchUtil.isLyricContent(res.mLyric)) {
                        Log.i(TAG, "$name provider returned result (distance=${res.mDistance}) for: $title")
                        name to res
                    } else {
                        Log.i(TAG, "$name provider returned no valid lyric for: $title")
                        null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "waiting for provider result failed for: $title", e)
                    null
                }
            }

        val translatedResults = validResults.filter { (_, res) ->
            LyricSearchUtil.isLyricContent(res.mTranslatedLyric)
        }

        val bestResult = (if (translatedResults.isNotEmpty()) translatedResults else validResults)
            .minByOrNull { (_, res) -> res.mDistance }
            ?.also { (name, res) ->
                Log.i(
                    TAG,
                    "best provider: $name (distance=${res.mDistance}, translated=${
                        LyricSearchUtil.isLyricContent(res.mTranslatedLyric)
                    }) for: $title"
                )
            }

        val result = bestResult?.second

        if (result == null || !LyricSearchUtil.isLyricContent(result.mLyric)) {
            Log.i(TAG, "no valid lyric for: $title")
            return null
        }

        // Parse directly from string (no intermediate file I/O path required)
        val lyric = LyricUtils.parseLyric(result.mLyric, "UTF-8")
        if (lyric.sentenceList.isEmpty()) {
            Log.i(TAG, "empty sentence list after parse for: $title")
            return null
        }
        val translatedLyric =
            result.mTranslatedLyric
                ?.takeIf { LyricSearchUtil.isLyricContent(it) }
                ?.let { LyricUtils.parseLyric(it, "UTF-8") }
                ?.takeIf { it.sentenceList.isNotEmpty() }

        // Persist to cache asynchronously-safe (write may fail silently)
        try {
            FileOutputStream(cacheFile).use { out ->
                out.write(result.mLyric.toByteArray(Charsets.UTF_8))
            }
            val translatedLyricContent = result.mTranslatedLyric
            if (!translatedLyricContent.isNullOrBlank()) {
                FileOutputStream(translatedCacheFile).use { out ->
                    out.write(translatedLyricContent.toByteArray(Charsets.UTF_8))
                }
            } else if (translatedCacheFile.exists()) {
                translatedCacheFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to write lyric cache", e)
        }

        Log.i(TAG, "fetched ${lyric.sentenceList.size} lines for: $title")
        return LyricPayload(lyric, translatedLyric)
    }

    private fun sha1Hex(input: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
            buildString(bytes.size * 2) {
                for (b in bytes) {
                    append(HEX[(b.toInt() shr 4) and 0xF])
                    append(HEX[b.toInt() and 0xF])
                }
            }
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }
}
