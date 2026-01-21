/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

enum class PlaybackState(private val value: Int) {
    UNKNOWN(-1),
    STOPPED(0),
    PLAYING(1),
    PAUSED(2);

    companion object {
        private val valueMap = entries.associateBy { it.value }

        fun of(n: Int): PlaybackState {
            return valueMap[n] ?: UNKNOWN
        }
    }
}