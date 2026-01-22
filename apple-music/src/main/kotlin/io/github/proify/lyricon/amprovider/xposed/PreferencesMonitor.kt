/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.amprovider.xposed

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

@SuppressLint("StaticFieldLeak")
object PreferencesMonitor {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    var listener: Listener? = null

    fun initialize(context: Context) {
        if (::context.isInitialized) return
        this.context = context.applicationContext
        prefs = context.getSharedPreferences("preferences", Context.MODE_PRIVATE)

        XposedHelpers.findAndHookMethod(
            "com.apple.android.music.utils.AppSharedPreferences",
            context.classLoader,
            "setLyricsTranslationSelected",
            Boolean::class.javaPrimitiveType,
            object : XC_MethodHook() {
                @Throws(Throwable::class)
                override fun afterHookedMethod(param: MethodHookParam?) {
                    listener?.onTranslationSelectedChanged(param?.args[0] as Boolean)
                }
            })
    }

    fun isTranslationSelected(): Boolean =
        prefs.getBoolean("key_player_lyrics_translation_selected", false)

    interface Listener {
        fun onTranslationSelectedChanged(selected: Boolean)
    }
}