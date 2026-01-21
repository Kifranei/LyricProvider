/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.provider.common.parser.lrc

import java.util.regex.Pattern

/**
 * LRC 格式解析工具
 */
object LrcParser {
    // 匹配时间标签，例如 [00:00.00] 或 [0:0.0]
    private val TIME_PATTERN = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)]")

    // 匹配元数据标签，例如 [ti:Song Title]
    private val META_PATTERN = Pattern.compile("\\[([a-z]+):(.*)]")

    /**
     * 将解析 LRC 文本字符串
     */
    fun parse(lrcText: String?): LrcData {
        if (lrcText.isNullOrBlank()) return LrcData(emptyMap(), emptyList())

        val metadata = mutableMapOf<String, String>()
        val entries = mutableListOf<LrcEntry>()

        lrcText.lines().forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) return@forEach

            // 1. 处理时间标签
            val timeMatcher = TIME_PATTERN.matcher(trimmedLine)
            val lineTimes = mutableListOf<Long>()
            var lastIndex = 0

            while (timeMatcher.find()) {
                // 使用 let 安全地获取捕获组并处理
                val min = timeMatcher.group(1)?.toLong() ?: 0L
                val secStr = timeMatcher.group(2) ?: "0"

                // 将秒（包含小数部分）转换为毫秒
                val totalTime = min * 60000 + (secStr.toDouble() * 1000).toLong()

                lineTimes.add(totalTime)
                lastIndex = timeMatcher.end()
            }

            if (lineTimes.isNotEmpty()) {
                // 提取时间标签后的歌词文本
                val content = if (lastIndex < trimmedLine.length) trimmedLine.substring(lastIndex)
                    .trim() else ""
                lineTimes.forEach { entries.add(LrcEntry(it, content)) }
            } else {
                // 2. 处理元数据标签
                val metaMatcher = META_PATTERN.matcher(trimmedLine)
                if (metaMatcher.matches()) {
                    val key = metaMatcher.group(1) ?: ""
                    val value = metaMatcher.group(2) ?: ""
                    metadata[key] = value
                }
            }
        }

        // 3. 应用 Offset 偏移量（LRC 标准：正值延迟，负值提前）
        val offset = metadata["offset"]?.toLongOrNull() ?: 0L
        val finalEntries = if (offset != 0L) {
            entries.map { it.copy(time = (it.time + offset).coerceAtLeast(0)) }
        } else {
            entries
        }.sorted() // 确保即使源文件顺序混乱也能正确排序

        return LrcData(metadata, finalEntries)
    }
}