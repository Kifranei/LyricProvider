/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("NonAsciiCharacters")

package io.github.proify.lyricon.library.meizuprovider

import org.junit.Assert.assertEquals
import org.junit.Test

class TickerTextFormatterTest {

    @Test
    fun `空值保持为空`() {
        assertEquals(null, parseMeizuTickerTextForLyricon(null))
        assertEquals(null, parseMeizuTickerTextForLyricon(""))
        assertEquals(null, parseMeizuTickerTextForLyricon("   "))
    }

    @Test
    fun `无翻译保持原样`() {
        assertEquals(TickerTextParts("Hello world"), parseMeizuTickerTextForLyricon("Hello world"))
        assertEquals(TickerTextParts("你好 世界"), parseMeizuTickerTextForLyricon("你好 世界"))
        assertEquals(
            TickerTextParts("Hello", "world"),
            parseMeizuTickerTextForLyricon("Hello\nworld")
        )
    }

    @Test
    fun `英译中拆分翻译`() {
        val raw = "You are a million miles away 你远在千里之外"
        val expected = TickerTextParts("You are a million miles away", "你远在千里之外")
        assertEquals(expected, parseMeizuTickerTextForLyricon(raw))
    }

    @Test
    fun `括号翻译也能拆分`() {
        val raw = "You are a million miles away (你远在千里之外)"
        val expected = TickerTextParts("You are a million miles away", "(你远在千里之外)")
        assertEquals(expected, parseMeizuTickerTextForLyricon(raw))
    }

    @Test
    fun `插入符号翻译拆分`() {
        val raw = "Its not until you fall that you fly ^唯有跌倒过才能学会飞翔"
        val expected = TickerTextParts("Its not until you fall that you fly", "唯有跌倒过才能学会飞翔")
        assertEquals(expected, parseMeizuTickerTextForLyricon(raw))
    }
}
