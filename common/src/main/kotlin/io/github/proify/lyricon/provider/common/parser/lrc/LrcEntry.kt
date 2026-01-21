/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.common.parser.lrc

/**
 * 单条歌词数据
 */
data class LrcEntry(
    /** 时间戳（毫秒） */
    val time: Long,
    /** 歌词内容 */
    val text: String
) : Comparable<LrcEntry> {
    override fun compareTo(other: LrcEntry): Int = time.compareTo(other.time)
}