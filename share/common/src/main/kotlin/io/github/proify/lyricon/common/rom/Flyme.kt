/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.common.rom

import android.annotation.SuppressLint
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.CopyOnWriteArraySet

object Flyme {
    private val unhooks = CopyOnWriteArraySet<XC_MethodHook.Unhook>()

    fun unlock() {
        unhooks.forEach { it.unhook() }
        unhooks.clear()
    }

    private fun setStaticObjectField(clazz: Class<*>, fieldName: String, value: Any) {
        try {
            XposedHelpers.setStaticObjectField(clazz, fieldName, value)
        } catch (e: Exception) {
            XposedBridge.log(e)
        }
    }

    fun mock(loader: ClassLoader) {
        @SuppressLint("PrivateApi")
        val systemPropertiesClass = loader.loadClass("android.os.SystemProperties")

        unhooks += XposedBridge.hookAllMethods(
            systemPropertiesClass,
            "get",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val buildClass = loader.loadClass("android.os.Build")

                    setStaticObjectField(buildClass, "BRAND", "meizu")
                    setStaticObjectField(buildClass, "MANUFACTURER", "Meizu")
                    setStaticObjectField(buildClass, "DEVICE", "m1892")
                    setStaticObjectField(buildClass, "DISPLAY", "Flyme")
                    setStaticObjectField(buildClass, "PRODUCT", "meizu_16thPlus_CN")
                    setStaticObjectField(buildClass, "MODEL", "meizu 16th Plus")
                }

            })

        val className = Class::class.java.name
        unhooks += XposedHelpers.findAndHookMethod(
            className,
            loader,
            "getField",
            String::class.java,
            GetFieldMethodHook()
        )

        unhooks += XposedHelpers.findAndHookMethod(
            className,
            loader,
            "getDeclaredField",
            String::class.java,
            GetFieldMethodHook()
        )
    }

    private class GetFieldMethodHook : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val name = param.args[0] as? String ?: return
            when (name) {
                "FLAG_ALWAYS_SHOW_TICKER" -> {
                    param.result =
                        MeiZuNotification::class.java.getDeclaredField("FLAG_ALWAYS_SHOW_TICKER_HOOK")
                }

                "FLAG_ONLY_UPDATE_TICKER" -> {
                    param.result =
                        MeiZuNotification::class.java.getDeclaredField("FLAG_ONLY_UPDATE_TICKER_HOOK")
                }
            }
        }
    }
}