/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.common.parser.lrc

/**
 * 解析后的 LRC 数据对象
 */
data class LrcData(
    /** 元数据，如 ti (标题), ar (歌手), offset (偏移量) 等 */
    val metadata: Map<String, String>,
    /** 按时间排序后的歌词行 */
    val entries: List<LrcEntry>
)