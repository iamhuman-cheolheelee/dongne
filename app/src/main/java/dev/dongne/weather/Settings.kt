package dev.dongne.weather

import android.content.Context

/** 사용자 설정. 화면은 SettingsActivity, 읽는 쪽은 Alerts·StatusIcon */
object Settings {
    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun morningOn(c: Context) = p(c).getBoolean("morning", true)
    fun morningHour(c: Context) = p(c).getInt("morningHour", 7)
    fun rainOn(c: Context) = p(c).getBoolean("rain", true)
    fun statusOn(c: Context) = p(c).getBoolean("status", true)
    fun set(c: Context, k: String, v: Any) = p(c).edit().apply {
        when (v) { is Boolean -> putBoolean(k, v); is Int -> putInt(k, v) }
    }.apply()
}
