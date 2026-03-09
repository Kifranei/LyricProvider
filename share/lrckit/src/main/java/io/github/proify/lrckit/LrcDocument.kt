/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lrckit

import io.github.proify.lyricon.lyric.model.LyricLine
import kotlinx.serialization.Serializable

@Serializable
data class LrcDocument(
    val metadata: Map<String, String> = emptyMap(),
    val lines: List<LyricLine> = emptyList()
) {

    /**
     * 应用全局时间偏移。
     * @param offsetMs 偏移毫秒数
     * @return 新的 LrcDocument
     */
    fun applyOffset(offsetMs: Long): LrcDocument {
        val newLines = lines.map { line ->
            val newBegin = line.begin + offsetMs
            val newEnd = newBegin + line.duration
            val newDuration = newEnd - newBegin
            line.copy(begin = newBegin, end = newEnd, duration = newDuration)
        }
        return LrcDocument(metadata, newLines)
    }
}