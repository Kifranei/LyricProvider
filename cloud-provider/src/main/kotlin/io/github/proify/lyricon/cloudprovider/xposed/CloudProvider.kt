/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cloudprovider.xposed

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.SystemClock
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.cloudlyric.LyricsResult
import io.github.proify.cloudlyric.ProviderLyrics
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object CloudProvider : YukiBaseHooker(), DownloadCallback {
    private const val TAG: String = "CloudProvider"

    // 播放状态相关
    private var currentPlayingState = false
    private var provider: LyriconProvider? = null
    private var lastMediaSignature: String? = null

    // 时间同步相关锚点
    private var lastSyncedPosition: Long = 0L
    private var lastUpdateTimeMillis: Long = 0L
    private var playbackRate: Float = 1.0f

    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var syncJob: Job? = null

    override fun onHook() {
        YLog.debug(tag = TAG, msg = "进程: $processName")

        onAppLifecycle {
            onCreate {
                initProvider()
            }
        }

        hookMediaSession()
    }

    private fun initProvider() {
        val context = appContext ?: return
        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = ProviderLogo.fromBase64(Constants.ICON),
            processName = processName
        ).apply { register() }
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass().resolve().apply {
            // Hook 播放状态更新（包含进度、倍速、状态）
            firstMethod {
                name = "setPlaybackState"
                parameters(PlaybackState::class.java)
            }.hook {
                after {
                    val stateObj = args[0] as? PlaybackState ?: return@after

                    // 更新同步锚点
                    updateSyncAnchor(stateObj.position, stateObj.playbackSpeed)

                    // 分发播放/暂停状态
                    dispatchPlaybackState(stateObj.state)
                }
            }

            // Hook 元数据更新（歌曲信息）
            firstMethod {
                name = "setMetadata"
                parameters("android.media.MediaMetadata")
            }.hook {
                after {
                    val metadata = args[0] as? MediaMetadata ?: return@after

                    val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                    val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)

                    val signature = calculateSignature(id, title, artist, album)
                    if (signature == lastMediaSignature) {
                        YLog.debug(tag = TAG, msg = "Same metadata, skip")
                        return@after
                    }
                    lastMediaSignature = signature

                    YLog.debug(
                        tag = TAG,
                        msg = "Metadata: id=$id, title=$title, artist=$artist, album=$album"
                    )

                    provider?.player?.setSong(
                        Song(
                            name = title,
                            artist = artist,
                        )
                    )

                    DownloadManager.cancel()

                    YLog.debug(
                        tag = TAG,
                        msg = "Searching lyrics... trackName=$title, artist=$artist, album=$album"
                    )
                    DownloadManager.search(this@CloudProvider) {
                        trackName = title
                        artistName = artist
                        albumName = album
                        perProviderLimit = 5
                        maxTotalResults = 1
                    }
                }
            }
        }
    }

    /**
     * 更新位置推算的基准锚点。
     * @param position 媒体当前播放的进度（毫秒）
     * @param rate 当前播放倍率
     */
    private fun updateSyncAnchor(position: Long, rate: Float) {
        lastSyncedPosition = position
        playbackRate = if (rate > 0) rate else 1.0f
        lastUpdateTimeMillis = SystemClock.elapsedRealtime()
    }

    /**
     * 根据锚点和流逝时间推算当前实时位置。
     * @return 推算出的当前播放位置（毫秒）
     */
    private fun calculateCurrentPosition(): Long {
        if (!currentPlayingState) return lastSyncedPosition
        val elapsed = SystemClock.elapsedRealtime() - lastUpdateTimeMillis
        return lastSyncedPosition + (elapsed * playbackRate).toLong()
    }

    private fun calculateSignature(vararg data: String?): String {
        return data.joinToString("") { it?.hashCode()?.toString() ?: "0" }.hashCode().toString()
    }

    private fun dispatchPlaybackState(state: Int) {
        when (state) {
            PlaybackState.STATE_PLAYING -> applyPlaybackUpdate(true)
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> applyPlaybackUpdate(false)
        }
    }

    private fun applyPlaybackUpdate(playing: Boolean) {
        if (this.currentPlayingState == playing) return
        this.currentPlayingState = playing

        YLog.debug(tag = TAG, msg = "Playback state changed: $playing")
        provider?.player?.setPlaybackState(playing)

        if (playing) {
            startPositionSync()
        } else {
            stopPositionSync()
        }
    }

    /**
     * 启动定时任务，周期性地上报推算出的进度。
     */
    private fun startPositionSync() {
        syncJob?.cancel()
        syncJob = coroutineScope.launch {
            while (isActive && currentPlayingState) {
                val currentPos = calculateCurrentPosition()
                provider?.player?.setPosition(currentPos)
                delay(ProviderConstants.DEFAULT_POSITION_UPDATE_INTERVAL)
            }
        }
    }

    private fun stopPositionSync() {
        syncJob?.cancel()
        syncJob = null
        // 停止时确保同步一次最后的确切位置
        provider?.player?.setPosition(lastSyncedPosition)
    }

    override fun onDownloadFinished(response: List<ProviderLyrics>) {
        YLog.debug(tag = TAG, msg = "Download finished: $response")
        val song = response.firstOrNull()?.lyrics?.toSong()
        provider?.player?.setSong(song)
    }

    override fun onDownloadFailed(e: Exception) {
        YLog.error(tag = TAG, msg = "Download failed: ${e.message}")
    }

    /**
     * 将搜索结果转换为应用内部的 Song 模型。
     */
    private fun LyricsResult.toSong() = Song().apply {
        name = trackName
        artist = artistName
        lyrics = rich
        // 取最后一句歌词的结束时间作为总时长（如果元数据未提供）
        duration = rich.lastOrNull()?.end ?: 0L
    }
}