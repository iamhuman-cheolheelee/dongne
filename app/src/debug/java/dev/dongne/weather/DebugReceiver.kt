package dev.dongne.weather

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * debug 빌드 전용 시험 훅. release APK 에는 이 클래스도, 매니페스트 항목도 들어가지 않는다.
 *   adb shell am broadcast -n dev.dongne.weather/.DebugReceiver -a dev.dongne.weather.MORNING
 *   adb shell am broadcast -n dev.dongne.weather/.DebugReceiver -a dev.dongne.weather.REFRESH
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            "dev.dongne.weather.MORNING" -> MorningReceiver.run(c, goAsync())
            "dev.dongne.weather.REFRESH" -> GlanceWidget.refresh(c, goAsync(), force = true)
        }
    }
}
