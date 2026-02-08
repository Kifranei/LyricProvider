/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.library.carprovider

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.common.util.Utils
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo

open class CarProvider(
    val providerPackageName: String,
    val logo: ProviderLogo = ProviderLogo.fromBase64(Constants.ICON)
) : YukiBaseHooker() {
    private val tag = "CarProvider"

    private var currentPlayingState = false
    private var lyriconProvider: LyriconProvider? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val pauseRunnable = Runnable { applyPlaybackUpdate(false) }

    private var isTranslationEnabled = false
    private val textSongId = "car-metadata-text"
    private val tickerLines = ArrayDeque<RichLyricLine>()
    private val positionRunnable = Runnable { tickPosition() }
    private var lastPositionMs: Long = 0L
    private var lastPositionUpdateElapsedMs: Long = 0L
    private var lastSpeed: Float = 1f

    override fun onHook() {
        Utils.openBluetoothA2dpOn(appClassLoader)
        YLog.debug(tag = tag, msg = "进程: $processName")

        onAppLifecycle {
            onCreate {
                initProvider()
            }
        }

        hookMediaSession()
    }

    private fun initProvider() {
        val context = appContext ?: return
        lyriconProvider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = providerPackageName,
            playerPackageName = context.packageName,
            logo = logo
        ).apply { register() }
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass().resolve().apply {
            firstMethod {
                name = "setPlaybackState"
                parameters(PlaybackState::class.java)
            }.hook {
                after {
                    val playback = args[0] as? PlaybackState ?: return@after
                    dispatchPlaybackState(playback.state)
                    updatePlaybackPosition(playback)
                }
            }

            firstMethod {
                name = "setMetadata"
                parameters("android.media.MediaMetadata")
            }.hook {
                after {
                    val metadata = args[0] as? MediaMetadata ?: return@after
                    metadata.keySet().forEach { key ->
                        val value = metadata.getString(key)
                        YLog.debug(tag = tag, msg = "Metadata: $key=$value")
                    }
                    val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                    if (!title.isNullOrBlank()) {
                        val parts = parseTranslationText(title)
                        if (parts != null) {
                            if (!isTranslationEnabled) {
                                lyriconProvider?.player?.setDisplayTranslation(true)
                                isTranslationEnabled = true
                            }
                            val position = estimateCurrentPositionMs().coerceAtLeast(0L)
                            tickerLines.lastOrNull()?.let { prev ->
                                if (position > prev.begin) {
                                    prev.end = position
                                    prev.duration = prev.end - prev.begin
                                }
                            }

                            val line = RichLyricLine(
                                text = parts.first,
                                begin = position,
                                end = position + 5000L,
                                duration = 5000L,
                                words = buildPseudoWords(parts.first, position, 5000L),
                                translation = parts.second
                            )
                            tickerLines.addLast(line)
                            while (tickerLines.size > 200) tickerLines.removeFirst()

                            val builtLyrics = tickerLines.toList()
                            val song = Song(id = textSongId).apply {
                                lyrics = builtLyrics
                                duration = builtLyrics.maxOfOrNull { l -> l.end } ?: Long.MAX_VALUE
                            }
                            lyriconProvider?.player?.setSong(song)
                            lyriconProvider?.player?.setPosition(position)
                        } else {
                            lyriconProvider?.player?.sendText(title)
                        }
                    } else {
                        lyriconProvider?.player?.sendText(null)
                        tickerLines.clear()
                    }
                }
            }
        }
    }

    private fun parseTranslationText(raw: String): Pair<String, String>? {
        val text = raw.trim()
        val caretIndex = text.indexOf('^')
        if (caretIndex !in 1 until text.lastIndex) return null
        val main = text.substring(0, caretIndex).trim()
        val trans = text.substring(caretIndex + 1).trim()
        if (main.isEmpty() || trans.isEmpty()) return null
        if (!trans.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return null
        return main to trans
    }

    private fun updatePlaybackPosition(playback: PlaybackState) {
        lastPositionMs = playback.position.coerceAtLeast(0L)
        lastSpeed = playback.playbackSpeed.takeIf { it > 0f } ?: 1f
        lastPositionUpdateElapsedMs = SystemClock.elapsedRealtime()
        lyriconProvider?.player?.setPosition(lastPositionMs)
    }

    private fun estimateCurrentPositionMs(): Long {
        val baseElapsed = lastPositionUpdateElapsedMs
        if (baseElapsed <= 0L) return lastPositionMs
        val now = SystemClock.elapsedRealtime()
        val delta = (now - baseElapsed).coerceAtLeast(0L)
        return lastPositionMs + (delta * lastSpeed).toLong()
    }

    private fun tickPosition() {
        if (!currentPlayingState) return
        val estimated = estimateCurrentPositionMs().coerceAtLeast(0L)
        lyriconProvider?.player?.setPosition(estimated)
        mainHandler.postDelayed(positionRunnable, 500L)
    }

    private fun startPositionLoop() {
        mainHandler.removeCallbacks(positionRunnable)
        mainHandler.post(positionRunnable)
    }

    private fun stopPositionLoop() {
        mainHandler.removeCallbacks(positionRunnable)
    }

    private fun buildPseudoWords(
        text: String,
        lineBegin: Long,
        lineDuration: Long
    ): List<LyricWord>? {
        val tokens = Regex("\\S+\\s*").findAll(text).map { it.value }.toList()
        if (tokens.isEmpty()) return null

        val units = tokens.map { token -> token.trim().length.coerceAtLeast(1) }
        val totalUnits = units.sum().coerceAtLeast(1)

        val words = ArrayList<LyricWord>(tokens.size)
        var cursor = lineBegin
        var allocated = 0L
        for (i in tokens.indices) {
            val isLast = i == tokens.lastIndex
            val dur = if (isLast) {
                (lineDuration - allocated).coerceAtLeast(0L)
            } else {
                val d = (lineDuration * units[i].toLong() / totalUnits.toLong()).coerceAtLeast(1L)
                allocated += d
                d
            }
            words += LyricWord(begin = cursor, text = tokens[i]).apply {
                end = (cursor + dur).coerceAtLeast(cursor)
                duration = end - begin
            }
            cursor += dur
        }
        return words
    }

    private fun dispatchPlaybackState(state: Int) {
        mainHandler.removeCallbacks(pauseRunnable)

        when (state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> applyPlaybackUpdate(true)

            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_NONE -> {
                mainHandler.postDelayed(
                    pauseRunnable,
                    300L
                )
            }
        }
    }

    private fun applyPlaybackUpdate(playing: Boolean) {
        if (this.currentPlayingState == playing) return
        this.currentPlayingState = playing

        YLog.debug(tag = tag, msg = "Playback state changed: $playing")
        lyriconProvider?.player?.setPlaybackState(playing)
        if (playing) startPositionLoop() else stopPositionLoop()
    }
}
