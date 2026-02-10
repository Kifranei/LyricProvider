/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.qrckit

import io.github.proify.qrckit.model.ParsedLyric
import kotlin.test.Test

class QrcDownloaderTest {
    @Test
    fun testDownload() {
        val response: LyricResponse = QrcDownloader.downloadLyrics("355898101")
        val data: ParsedLyric = response.parsedLyric

        response.parsedLyric.lyricsRaw?.lines()?.forEach {
            //  println(it)
        }

        data.richLyricLines.forEach {
            println(it)
        }
    }
}