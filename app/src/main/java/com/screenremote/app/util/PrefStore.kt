package com.screenremote.app.util

import android.content.Context

/** تخزين بسيط للتوكِن / مفتاح الاقتران لكل جهاز. */
class PrefStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("screen_remote", Context.MODE_PRIVATE)

    fun get(key: String): String? = prefs.getString(key, null)

    fun put(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }

    fun clear(key: String) {
        prefs.edit().remove(key).apply()
    }
}
