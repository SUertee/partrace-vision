package com.anker.esiotkit.sample.utils

import android.content.Context
import android.content.SharedPreferences

/**
@date: 2023/7/25
@desc:
 */

fun SharedPreferences.putValue(key: String, value: Any?) {
  val editor = edit()
  when (value) {
    is String? -> editor.putString(key, value)
    is Int -> editor.putInt(key, value)
    is Boolean -> editor.putBoolean(key, value)
    is Float -> editor.putFloat(key, value)
    is Long -> editor.putLong(key, value)
    is Set<*> -> editor.putStringSet(key, value as Set<String>)
    else -> throw IllegalArgumentException("Unsupported value type")
  }
  editor.apply()
}

inline fun <reified T> SharedPreferences.getValue(key: String, defaultValue: T? = null): T? {
  return when (T::class) {
    String::class -> getString(key, defaultValue as? String) as T?
    Int::class -> getInt(key, defaultValue as? Int ?: 0) as T
    Boolean::class -> getBoolean(key, defaultValue as? Boolean ?: false) as T
    Float::class -> getFloat(key, defaultValue as? Float ?: 0f) as T
    Long::class -> getLong(key, defaultValue as? Long ?: 0) as T
    Set::class -> getStringSet(key, defaultValue as? Set<String> ?: emptySet()) as T
    else -> throw IllegalArgumentException("Unsupported value type")
  }
}

fun Context.getMySharedPreferences(): SharedPreferences {
  return getSharedPreferences("my_prefs", Context.MODE_PRIVATE)
}
