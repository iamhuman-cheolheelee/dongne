package dev.dongne.weather

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/** 상단 상태바(갤럭시 좌측 상단)에 지금 상황 아이콘 하나 + 펼치면 기온·시간별 */
object StatusIcon {
    private const val CH = "now"
    private const val ID = 7

    /**
     * 상태바 아이콘 하나로 지금 상황을 고른다(위에서부터 우선).
     * 뇌우 > 눈·진눈깨비 > 지금 비 > 폭염·한파(특보 기준) > 3시간 내 비 > 미세먼지 매우나쁨 > 강풍(9m/s↑) > 하늘 상태
     * 아이콘: Material Symbols Rounded (Apache 2.0)
     */
    fun glyph(s: Snapshot, hour: Int = java.time.LocalTime.now().hour): Int {
        val c = s.code
        val feelsMax = s.days.firstOrNull()?.feelsMax ?: s.max
        fun warned(k: String) = s.warnings.any { it.startsWith(k) }
        return when {
            c >= 95 -> R.drawable.sb_thunderstorm
            c in 71..77 || c == 85 || c == 86 -> R.drawable.sb_weather_snowy
            c == 56 || c == 57 || c == 66 || c == 67 || (c in 51..82 && s.temp <= 1) -> R.drawable.sb_weather_mix
            c == 65 || c == 82 || c == 81 || c == 63 -> R.drawable.sb_rainy_heavy
            c in 51..55 || c == 61 || c == 80 -> R.drawable.sb_rainy
            c in 51..82 -> R.drawable.sb_rainy
            // 지금 내리지 않아도 특보가 발효 중이면 그 위험을 먼저
            warned("호우") -> R.drawable.sb_rainy_heavy
            warned("대설") -> R.drawable.sb_weather_snowy
            warned("폭염") -> R.drawable.sb_emergency_heat
            warned("한파") -> R.drawable.sb_severe_cold
            warned("태풍") || warned("강풍") -> R.drawable.sb_air
            s.feels >= 31 || (feelsMax >= 33 && hour in 9..18) -> R.drawable.sb_emergency_heat
            s.feels <= -10 || (s.min <= -12 && hour < 12) -> R.drawable.sb_severe_cold
            Next.upcoming(s, within = 3) != null -> R.drawable.sb_umbrella
            s.aqGrade >= 3 -> R.drawable.sb_masks
            s.wind >= 9 -> R.drawable.sb_air
            c == 45 || c == 48 -> R.drawable.sb_foggy
            c == 3 -> R.drawable.sb_cloud
            c in 1..2 -> if (s.isDay) R.drawable.sb_partly_cloudy_day else R.drawable.sb_partly_cloudy_night
            else -> if (s.isDay) R.drawable.sb_clear_day else R.drawable.sb_clear_night
        }
    }

    fun post(c: Context, s: Snapshot) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CH, "현재 기온(상태바)", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false); setSound(null, null); enableVibration(false)
        })
        val now = java.time.LocalDateTime.now()
        val n = Notification.Builder(c, CH)
            .setSmallIcon(Icon.createWithResource(c, glyph(s)))
            .setContentTitle((if (s.warnings.isNotEmpty()) s.warnings.joinToString("·") + " · " else "") +
                "${s.temp}° ${Wx.text(s.code)} · 최고 ${s.max}° 최저 ${s.min}° · 체감 ${s.feels}°")
            // 접근성 서비스가 읽는 요약(아이콘만으로는 날씨를 알 수 없다)
            .setTicker("${s.place} ${s.temp}도 ${Wx.text(s.code)}, ${Next.change(s)}")
            .setContentText("${Next.change(s)} · ${Outfits.plan(s, now.monthValue, now.hour).headline}")
            // 펼치면 2시간 간격 12시간. 날씨는 '주변 정보'라 Live Update(승격 알림) 대상이 아니다 — 일반 상시 알림 유지
            .setStyle(Notification.BigTextStyle().bigText(
                (listOf("${Next.change(s)}, 미세먼지 ${s.aqText}") + Next.lines(s)).joinToString("\n")))
            .setSubText(s.place)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        nm.notify(ID, n)
    }
}
