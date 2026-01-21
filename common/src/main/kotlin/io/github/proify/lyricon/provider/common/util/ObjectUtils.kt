/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused")

package io.github.proify.lyricon.provider.common.util

import android.util.Log

object ObjectUtils {

    private const val TAG = "ObjectUtils"

    /**
     * 打印对象的字段和方法信息到 Log
     * @param obj 要打印的对象
     * @param tag 自定义 Log 标签，如果为 null 则使用默认 TAG
     * @param logLevel Log 级别，默认为 Log.DEBUG
     * @param prefix 只打印指定前缀的方法
     */
    fun print(
        obj: Any,
        tag: String? = null,
        logLevel: Int = Log.DEBUG,
        prefix: Array<String>? = null
    ) {
        val logTag = tag ?: TAG
        val className = obj.javaClass.simpleName

        try {
            // 打印类信息
            logMessage(logTag, logLevel, "╔═══════════════════════════════════════════")
            logMessage(logTag, logLevel, "║ Class: ${obj.javaClass.name}")
            logMessage(logTag, logLevel, "╠═══════════════════════════════════════════")

            // 打印字段信息
            val fields = obj.javaClass.fields
            if (fields.isNotEmpty()) {
                logMessage(logTag, logLevel, "║ Fields (${fields.size}):")
                for (field in fields) {
                    field.isAccessible = true
                    try {
                        val value = field.get(obj)
                        logMessage(logTag, logLevel, "║   ${field.name}: ${formatValue(value)}")
                    } catch (e: IllegalAccessException) {
                        logMessage(logTag, Log.WARN, "║   ${field.name}: <inaccessible>")
                    } catch (e: Exception) {
                        logMessage(logTag, Log.ERROR, "║   ${field.name}: <error: ${e.message}>")
                    }
                }
                logMessage(logTag, logLevel, "╠═══════════════════════════════════════════")
            } else {
                logMessage(logTag, logLevel, "║ No fields found")
                logMessage(logTag, logLevel, "╠═══════════════════════════════════════════")
            }

            // 打印方法信息（无参数方法）
            val declaredMethods = obj.javaClass.methods
            val noArgMethods = declaredMethods.filter { it.parameterCount == 0 }

            if (noArgMethods.isNotEmpty()) {
                logMessage(logTag, logLevel, "║ No-argument Methods (${noArgMethods.size}):")
                for (method in noArgMethods) {
                    if (method.name.startsWith("access$")) continue // 跳过编译器生成的方法
                    if (prefix != null && !prefix.any { method.name.startsWith(it) }) continue

                    method.isAccessible = true
                    try {
                        val value = method.invoke(obj)
                        logMessage(logTag, logLevel, "║   ${method.name}(): ${formatValue(value)}")
                    } catch (e: IllegalAccessException) {
                        logMessage(logTag, Log.WARN, "║   ${method.name}(): <inaccessible>")
                    } catch (e: Exception) {
                        logMessage(logTag, Log.ERROR, "║   ${method.name}(): <error: ${e.message}>")
                    }
                }
            } else {
                logMessage(logTag, logLevel, "║ No no-argument methods found")
            }

            logMessage(logTag, logLevel, "╚═══════════════════════════════════════════")

        } catch (e: Exception) {
            Log.e(logTag, "Error while printing object $className", e)
        }
    }

    /**
     * 打印对象的简要信息到 Log（字段值）
     * @param obj 要打印的对象
     * @param tag 自定义 Log 标签
     */
    fun printSimple(obj: Any, tag: String? = null) {
        val logTag = tag ?: TAG
        val className = obj.javaClass.simpleName

        try {
            val fields = obj.javaClass.declaredFields
            if (fields.isEmpty()) {
                logMessage(logTag, Log.DEBUG, "$className: No fields")
                return
            }

            val fieldValues = fields.joinToString(", ") { field ->
                field.isAccessible = true
                try {
                    "${field.name}=${formatValue(field.get(obj))}"
                } catch (e: Exception) {
                    "${field.name}=<error>"
                }
            }

            logMessage(logTag, Log.DEBUG, "$className { $fieldValues }")

        } catch (e: Exception) {
            Log.e(logTag, "Error while printing simple object $className", e)
        }
    }

    /**
     * 格式化值，使其在日志中更易读
     */
    private fun formatValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"$value\""
            is Array<*> -> value.contentToString()
            is List<*> -> value.toString()
            is Map<*, *> -> value.toString()
            is Set<*> -> value.toString()
            is Collection<*> -> value.toString()
            is Boolean, is Number, is Char -> value.toString()
            else -> {
                // 如果是自定义对象，显示类名和哈希码
                "${value.javaClass.simpleName}@${Integer.toHexString(value.hashCode())}"
            }
        }
    }

    /**
     * 根据级别输出 Log 消息
     */
    private fun logMessage(tag: String, level: Int, message: String) {
        when (level) {
            Log.VERBOSE -> Log.v(tag, message)
            Log.DEBUG -> Log.d(tag, message)
            Log.INFO -> Log.i(tag, message)
            Log.WARN -> Log.w(tag, message)
            Log.ERROR -> Log.e(tag, message)
            else -> Log.d(tag, message)
        }
    }

    /**
     * 将对象信息转为字符串（用于调试，不输出到 Log）
     */
    fun toString(obj: Any): String {
        return buildString {
            appendLine("Class: ${obj.javaClass.name}")

            val fields = obj.javaClass.declaredFields
            if (fields.isNotEmpty()) {
                appendLine("Fields:")
                for (field in fields) {
                    field.isAccessible = true
                    try {
                        val value = field.get(obj)
                        appendLine("  ${field.name}: ${formatValue(value)}")
                    } catch (e: Exception) {
                        appendLine("  ${field.name}: <error: ${e.message}>")
                    }
                }
            }
        }
    }
}