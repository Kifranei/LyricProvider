/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.library.meizuprovider

private fun Char.isHan(): Boolean =
    Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN

internal data class TickerTextParts(
    val text: String,
    val translation: String? = null
)

private fun String.countHan(): Int = count { it.isHan() }

internal fun parseMeizuTickerTextForLyricon(raw: String?): TickerTextParts? {
    val text = raw?.trim()
    if (text.isNullOrEmpty()) return null
    val firstNewline = text.indexOf('\n')
    if (firstNewline >= 0) {
        val main = text.substring(0, firstNewline).trim()
        val trans = text.substring(firstNewline + 1).trim().takeIf { it.isNotEmpty() }
        return TickerTextParts(main, trans)
    }

    val looksLikeMeta =
        text.contains("Lyrics by", ignoreCase = true) ||
            text.contains("Composed by", ignoreCase = true) ||
            text.contains("Produced by", ignoreCase = true) ||
            text.contains("作词") ||
            text.contains("作曲") ||
            text.contains("编曲") ||
            text.contains("制作") ||
            text.contains(" - ")

    var hasLatin = false
    var firstHanIndex = -1
    for (i in text.indices) {
        val c = text[i]
        if (c in 'a'..'z' || c in 'A'..'Z') hasLatin = true
        if (hasLatin && c.isHan()) {
            firstHanIndex = i
            break
        }
    }
    if (firstHanIndex == -1) return TickerTextParts(text)

    var splitIndex = text.lastIndexOf("  ", startIndex = firstHanIndex).takeIf { it >= 0 }
    if (splitIndex == null) {
        for (i in firstHanIndex downTo 0) {
            if (text[i].isWhitespace()) {
                splitIndex = i
                break
            }
        }
    }
    if (splitIndex == null || splitIndex <= 0) return TickerTextParts(text)

    val original = text.substring(0, splitIndex).trimEnd()
    val translation = text.substring(splitIndex + 1).trimStart()
    if (original.isEmpty() || translation.isEmpty()) return TickerTextParts(text)

    if (looksLikeMeta && translation.startsWith("(")) return TickerTextParts(text)
    if (translation.countHan() < 2) return TickerTextParts(text)

    return TickerTextParts(original, translation)
}
