/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.library.meizuprovider

import android.app.Notification
import android.app.NotificationManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.lyricon.common.rom.Flyme
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

open class MeizuProvider(
    val providerPackageName: String,
    val logo: ProviderLogo = ProviderLogo.fromBase64(Constants.ICON)
) : YukiBaseHooker() {

    private companion object {
        private const val FLAG_MEIZU_TICKER = 0x1000000 or 0x2000000
        private const val TAG = "MeizhuProvider"
    }

    private var isPlaying = false
    private var isTranslationEnabled = false
    private val textSongId = "meizu-ticker-text"
    private val positionScheduler by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "Lyricon-Position-$providerPackageName").apply { isDaemon = true }
        }
    }
    private var positionFuture: ScheduledFuture<*>? = null
    @Volatile private var lastPositionMs: Long = 0L
    @Volatile private var lastPositionUpdateElapsedMs: Long = 0L
    @Volatile private var lastSpeed: Float = 1f

    private val provider: LyriconProvider by lazy {
        LyriconFactory.createProvider(
            appContext!!,
            providerPackageName,
            appContext!!.packageName,
            logo
        ).apply(LyriconProvider::register)
    }

    override fun onHook() {
        YLog.debug("Hooking processName: $processName")
        Flyme.mock(appClassLoader!!)
        onAppLifecycle {
            onCreate {
                hookMedia()
                hookNotify()
            }
        }
    }

    private fun hookMedia() {
        "android.media.session.MediaSession".toClass()
            .resolve()
            .apply {
                firstMethod {
                    name = "setPlaybackState"
                    parameters(PlaybackState::class.java)
                }.hook {
                    after {
                        val playback = args[0] as PlaybackState
                        val state = playback.state
                        Log.d(TAG, "state: $state")
                        when (state) {
                            PlaybackState.STATE_PLAYING -> updatePlaybackStatus(true)
                            PlaybackState.STATE_PAUSED,
                            PlaybackState.STATE_STOPPED -> updatePlaybackStatus(false)

                            else -> Unit
                        }
                        updatePlaybackPosition(playback)
                    }
                }
            }
    }

    private fun updatePlaybackStatus(state: Boolean) {
        if (isPlaying == state) return
        isPlaying = state
        provider.player.setPlaybackState(state)
        if (state) {
            startPositionLoop()
        } else {
            stopPositionLoop()
        }
    }

    private fun updatePlaybackPosition(playback: PlaybackState) {
        lastPositionMs = playback.position
        lastSpeed = playback.playbackSpeed
        lastPositionUpdateElapsedMs = SystemClock.elapsedRealtime()
        provider.player.setPosition(lastPositionMs)
    }

    private fun estimateCurrentPositionMs(): Long {
        val baseElapsed = lastPositionUpdateElapsedMs
        if (baseElapsed <= 0L) return lastPositionMs
        val now = SystemClock.elapsedRealtime()
        val delta = (now - baseElapsed).coerceAtLeast(0L)
        return lastPositionMs + (delta * lastSpeed).toLong()
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

    private fun startPositionLoop() {
        if (positionFuture != null) return
        val runnable = Runnable {
            if (!isPlaying) return@Runnable
            val now = SystemClock.elapsedRealtime()
            val delta = now - lastPositionUpdateElapsedMs
            val estimated = lastPositionMs + (delta * lastSpeed).toLong()
            provider.player.setPosition(estimated.coerceAtLeast(0L))
        }
        positionFuture = positionScheduler.scheduleAtFixedRate(runnable, 0L, 500L, TimeUnit.MILLISECONDS)
    }

    private fun stopPositionLoop() {
        positionFuture?.cancel(false)
        positionFuture = null
    }

    private fun hookNotify() {
        NotificationManager::class.java.name.toClass()
            .resolve()
            .apply {
                firstMethod {
                    name = "notify"
                    parameters(String::class, Int::class, Notification::class)
                }.hook {
                    after {
                        val notify = args[2] as Notification
                        //Log.d(TAG, "notify: $notify")
                        if ((notify.flags and FLAG_MEIZU_TICKER) != 0) {
                            Log.d(TAG, "ticker: ${notify.tickerText}")
                            val ticker = notify.tickerText?.toString()
                            val parts = parseMeizuTickerTextForLyricon(ticker) ?: return@after
                            if (parts.translation != null && !isTranslationEnabled) {
                                provider.player.setDisplayTranslation(true)
                                isTranslationEnabled = true
                            }
                            if (parts.translation != null) {
                                val lineBegin = estimateCurrentPositionMs().coerceAtLeast(0L)
                                val lineDuration =
                                    (parts.text.count { !it.isWhitespace() } * 140L).coerceIn(2500L, 12000L)
                                val lineEnd = lineBegin + lineDuration
                                val song = Song(id = textSongId).apply {
                                    lyrics = listOf(
                                        RichLyricLine(
                                            text = parts.text,
                                            begin = lineBegin,
                                            end = lineEnd,
                                            duration = lineDuration,
                                            words = buildPseudoWords(parts.text, lineBegin, lineDuration),
                                            translation = parts.translation
                                        )
                                    )
                                    duration = Long.MAX_VALUE
                                }
                                provider.player.setSong(song)
                                provider.player.setPosition(lineBegin)
                            } else {
                                provider.player.sendText(parts.text)
                            }
                        }
                    }
                }
            }
    }
}
