package io.github.proify.lyricon.cmprovider.xposed

import kotlinx.serialization.Serializable

@Serializable
data class LocalLyricCache(
    val lrc: String? = null,
    val lrcTranslateLyric: String? = null,
    val yrc: String? = null,
    val yrcTranslateLyric: String? = null,
    val pureMusic: Boolean = false,
    val musicId: Long = 0,
)