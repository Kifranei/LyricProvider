/*
 * Copyright 2026 Proify
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.proify.lyricon.amprovider

import android.app.Application
import android.media.MediaMetadata
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.VagueType
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.callMethod
import io.github.proify.lyricon.amprovider.Constants.ICON
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.common.util.ObjectUtils
import io.github.proify.lyricon.provider.common.util.ScreenStateMonitor
import io.github.proify.lyricon.provider.remote.ConnectionListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import java.lang.reflect.Modifier


/**
 * @author Proify
 * @since 2026/1/15
 */
object Apple : YukiBaseHooker() {
    const val POSITION_UPDATE_INTERVAL = 1000L / 24

    private lateinit var application: Application
    private lateinit var classLoader: ClassLoader

    // 播放器状态
    private var isPlaying = false

    // 反射缓存
    private var exoMediaPlayerInstance: Any? = null
    private var getPositionMethod: Method? = null

    // 协程作用域
    private val mainScope by lazy { CoroutineScope(Dispatchers.Main + SupervisorJob()) }
    private var progressJob: Job? = null

    // 外部服务
    private var provider: LyriconProvider? = null

    override fun onHook() {
        onAppLifecycle {
            onCreate { onAppCreate() }
        }
    }

    private fun onAppCreate() {
        application = appContext ?: return
        classLoader = appClassLoader ?: return

        DiskSongManager.init(application)
        initProvider()
        initScreenStateMonitor()

        startHooks()
    }

    private fun initProvider() {
        val provider = LyriconProvider(
            application, Constants.APP_PACKAGE_NAME,
            Constants.APPLE_MUSIC_PACKAGE_NAME, ProviderLogo.fromBase64(ICON)
        )

        // 初始化业务管理器
        PlaybackManager.init(
            remotePlayer = provider.service.player,
            requester = LyricRequester(classLoader, application)
        )

        provider.service.addConnectionListener(object : ConnectionListener {
            override fun onConnected(provider: LyriconProvider) {
                sync(resume = false)
            }

            override fun onReconnected(provider: LyriconProvider) {
                sync(resume = true)
            }

            override fun onDisconnected(provider: LyriconProvider) {}
            override fun onConnectTimeout(provider: LyriconProvider) {}
        })

        provider.register()
        this.provider = provider
    }

    private fun startHooks() {
        hookExoMediaPlayer()
        hookMediaMetadataChange()
        hookLyricBuildMethod()

//        XposedHelpers.findAndHookMethod(
//            "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel",
//            classLoader,
//            "loadLyrics",
//            classLoader.loadClass("com.apple.android.music.model.PlaybackItem"),
//            object : XC_MethodHook() {
//                @Throws(Throwable::class)
//                override fun afterHookedMethod(param: MethodHookParam?) {
//                    val arg = param?.args?.get(0) ?: return
//                    ObjectUtils.print(arg)
//                }
//            })
    }

    // --- Hook 1: 歌曲切换监听 ---
    private fun hookMediaMetadataChange() {
        val method = findMediaMetadataChangeMethod() ?: return

        method.hook {
            after {
                val mediaMetadata = args[0] as? MediaMetadata ?: return@after
                val metadata = AppleMediaMetadata.putAndGet(mediaMetadata) ?: return@after

                // 委托给 Manager 处理
                PlaybackManager.onSongChanged(metadata.id)
            }
        }
    }

    // --- Hook 2: 歌词构建监听 ---
    private fun hookLyricBuildMethod() {
        val m =
            classLoader.loadClass("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel")
                .resolve()
                .firstMethod { name = "buildTimeRangeToLyricsMap" }
                .hook {
                    after {
                        YLog.debug("buildTimeRangeToLyricsMap:$args")
                        val arg: Any? = args[0]
                        if (arg == null) {
                            YLog.debug("args0 null")
                            return@after
                        }
                        val songNative = callMethod(arg, "get")
                        YLog.debug("songNative: $songNative")

                        // 委托给 Manager 处理
                        PlaybackManager.onLyricsBuilt(songNative)
                    }
                }
        YLog.debug("hookLyricBuildMethod Hooked: $m")
    }

    // --- Hook 3: 播放器控制  ---
    private fun hookExoMediaPlayer() {
        val exoPlayerClass =
            classLoader.loadClass("com.apple.android.music.playback.player.ExoMediaPlayer")

        exoPlayerClass.declaredConstructors.forEach { constructor ->
            constructor.hook {
                after {
                    exoMediaPlayerInstance = instanceOrNull
                    getPositionMethod = instanceClass?.getDeclaredMethod("getCurrentPosition")
                }
            }
        }

        exoPlayerClass.resolve().firstMethod {
            name = "seekToPosition"
            parameters(Long::class)
        }.hook {
            after {
                val position = args(0).cast<Long>() ?: 0L
                if (isPlaying) provider?.service?.player?.seekTo(position)
            }
        }

        classLoader.loadClass("com.apple.android.music.playback.controller.LocalMediaPlayerController")
            .resolve()
            .method {
                name = "onPlaybackStateChanged"
                parameters(VagueType, Int::class, Int::class)
            }.first().hook {
                after {
                    when (PlaybackState.of(args[2] as Int)) {
                        PlaybackState.PLAYING -> startSyncAction()
                        else -> stopSyncAction()
                    }
                }
            }
    }

    // --- 进度同步逻辑 ---

    private fun startSyncAction() {
        if (isPlaying) return
        isPlaying = true
        provider?.service?.player?.setPlaybackState(true)
        resumeCoroutineTask()
    }

    private fun stopSyncAction() {
        isPlaying = false
        provider?.service?.player?.setPlaybackState(false)
        pauseCoroutineTask()
    }

    private fun resumeCoroutineTask() {
        if (progressJob?.isActive == true) return
        progressJob = mainScope.launch {
            while (isActive && isPlaying) {
                try {
                    val pos = getPositionMethod?.invoke(exoMediaPlayerInstance) as? Long ?: 0L
                    provider?.service?.player?.setPosition(pos)
                } catch (_: Exception) {
                    // Ignore
                }
                delay(POSITION_UPDATE_INTERVAL)
            }
        }
    }

    private fun pauseCoroutineTask() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun sync(resume: Boolean) {
        if (resume) {
            provider?.service?.player?.setPlaybackState(isPlaying)
            if (isPlaying) PlaybackManager.syncCurrentSong()
        }
        provider?.service?.player?.setPositionUpdateInterval(POSITION_UPDATE_INTERVAL.toInt())
    }

    private fun initScreenStateMonitor() {
        ScreenStateMonitor.initialize(application)
        ScreenStateMonitor.addListener(object : ScreenStateMonitor.ScreenStateListener {
            override fun onScreenOn() {
                if (isPlaying) resumeCoroutineTask()
            }

            override fun onScreenOff() {
                pauseCoroutineTask()
            }

            override fun onScreenUnlocked() {
                if (isPlaying && progressJob == null) resumeCoroutineTask()
            }
        })
    }

    private fun findMediaMetadataChangeMethod() =
        classLoader.loadClass("android.support.v4.media.MediaMetadataCompat")
            .declaredMethods.firstOrNull {
                Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) &&
                        it.parameterCount == 1 && it.returnType.simpleName.contains("MediaMetadata")
            }
}