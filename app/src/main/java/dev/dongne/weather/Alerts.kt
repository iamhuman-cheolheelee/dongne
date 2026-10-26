package dev.dongne.weather

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 아침 7시 비·눈 안내 + 기상청 특보 기준 폭염·한파 위험 안내.
 * 기준(기상청 기상특보 발표기준):
 *  폭염주의보 일 최고 체감온도 33℃↑ 2일 이상 / 폭염경보 35℃↑ 2일 이상
 *  한파주의보 아침 최저 -12℃↓ 2일 이상, 또는 전날보다 10℃↑ 하강하고 3℃↓
 *  한파경보   아침 최저 -15℃↓ 2일 이상, 또는 전날보다 15℃↑ 하강하고 3℃↓
 */
object Alerts {
    private const val CH = "alert"          // 폭염·한파: 높음(헤드업)
    private const val CH_MORNING = "morning" // 아침 7시: 기본
    private const val CH_RAIN = "rain_soon"  // 비·눈 1~2시간 전: 기본
    private val SEOUL = ZoneId.systemDefault()

    private fun pi(c: Context) = PendingIntent.getBroadcast(c, 2, Intent(c, MorningReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** 다음 아침 7:00 정각 (도즈 중에도) */
    fun scheduleMorning(c: Context) {
        val am0 = c.getSystemService(AlarmManager::class.java)
        if (!Settings.morningOn(c)) { am0.cancel(pi(c)); return }
        val now = LocalDateTime.now(SEOUL)
        var at = now.toLocalDate().atTime(Settings.morningHour(c), 0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        val ms = at.atZone(SEOUL).toInstant().toEpochMilli()
        val am = c.getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi(c))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi(c))
    }

    private fun isSnow(c: Int) = c in 71..77 || c == 85 || c == 86
    private fun isWet(c: Int) = c in 51..67 || c in 80..82 || c >= 95 || isSnow(c)

    /** 아침 7시: 결론(비·옷차림) 먼저, 인사말·이모지 없음 */
    fun morning(c: Context, s: Snapshot) {
        if (!Settings.morningOn(c)) return
        val now = LocalDateTime.now(SEOUL)
        val o = Outfits.plan(s, now.monthValue, now.hour)
        val title = listOfNotNull(o.rain?.substringBefore(" ("), o.headline).joinToString(" / ")
        val body = buildString {
            o.rain?.let { append("$it\n") }
            append("겉옷: ${o.outer}\n상의: ${o.top}\n하의: ${o.bottom}\n")
            if (o.items.isNotEmpty()) append("챙길 것: ${o.items.joinToString(", ")}\n")
            append("시간별: ${o.hourly}\n")
            append("기온: ${s.min}~${s.max}° (출근길 체감 ${o.low}°, 한낮 ${o.high}°), ${s.place}")
            o.notes.forEach { append("\n참고: $it") }
        }
        val short = "${s.min}~${s.max}° | " + o.hourly
        notify(c, 100, CH_MORNING, title, short, body, until = 22)
    }

    /** 발효 중 특보별 행동 요령(행정안전부 국민행동요령 요지) */
    private val ACTION = mapOf(
        "폭염" to "12~17시 야외활동 피하기. 물 15~20분마다 한 컵. 어지럼·두통 시 바로 시원한 곳에서 휴식",
        "한파" to "외출 최소화. 모자·장갑·목도리. 수도관 동파 주의",
        "호우" to "하천·지하차도·저지대 피하기. 외출 시 우산·방수 신발",
        "대설" to "대중교통 이용. 미끄럼 주의, 밑창 깊은 신발",
        "강풍" to "간판·공사장 주변 피하기. 우산 대신 우비",
        "태풍" to "외출 자제. 창문 고정, 하천·해안 접근 금지",
        "건조" to "불씨 관리. 실내 습도 40~60%",
        "황사" to "KF80 이상 마스크. 외출 후 손·얼굴 씻기",
    )

    /** 기상청 특보(발효 중) 우선, 없으면 예보로 판단한 위험 기온. 하루에 종류별 한 번만 */
    fun danger(c: Context, s: Snapshot) {
        val p = c.getSharedPreferences("wx", Context.MODE_PRIVATE)
        val today = LocalDate.now(SEOUL).toString()
        fun once(key: String) = (p.getString("sent_$key", null) != today).also { if (it) p.edit().putString("sent_$key", today).apply() }
        s.warnings.forEach { w ->
            val kind = w.removeSuffix("주의보").removeSuffix("경보")
            if (once("wrn_$w")) notify(c, 250 + Math.floorMod(w.hashCode(), 40), "$w 발효 중 · ${s.place}",
                (ACTION[kind] ?: "기상청 특보를 확인하세요") + ". (출처: 기상청 특보)")
        }
        val official = s.warnings.map { it.take(2) }.toSet()
        val d = s.days; if (d.size < 2) return
        val (t, n) = d[0] to d[1]
        val drop = s.drop
        val msg = when {
            "폭염" in official || "한파" in official -> null // 실제 특보 알림으로 갈음
            t.feelsMax >= 35 && n.feelsMax >= 35 -> Triple("heat2", "예보 기준 폭염경보 수준: 체감 ${t.feelsMax}°",
                "12~17시 야외활동 피하기. 물 15~20분마다 한 컵. 어지럼·두통 시 바로 시원한 곳에서 휴식. (기준: 체감 35° 이상 2일)")
            t.feelsMax >= 33 && n.feelsMax >= 33 -> Triple("heat1", "예보 기준 폭염주의보 수준: 체감 ${t.feelsMax}°",
                "한낮엔 그늘에서 쉬기. 물 자주 마시기. (기준: 체감 33° 이상 2일)")
            (t.min <= -15 && n.min <= -15) || (drop >= 15 && t.min <= 3) -> Triple("cold2", "예보 기준 한파경보 수준: 아침 ${t.min}°${if (drop >= 10) ", 어제보다 ${drop}° 하락" else ""}",
                "외출 최소화. 모자·장갑·목도리 필수. 수도관 동파 주의. (기준: 최저 -15° 이하 2일 또는 15° 이상 급강하)")
            (t.min <= -12 && n.min <= -12) || (drop >= 10 && t.min <= 3) -> Triple("cold1", "예보 기준 한파주의보 수준: 아침 ${t.min}°${if (drop >= 10) ", 어제보다 ${drop}° 하락" else ""}",
                "겹쳐 입기. 목도리·장갑 챙기기. (기준: 최저 -12° 이하 2일 또는 10° 이상 급강하)")
            else -> null
        } ?: return
        if (once(msg.first)) notify(c, 200 + Math.floorMod(msg.first.hashCode(), 50), msg.second, msg.third)
    }

    /** 비·눈이 1~2시간 안에 시작하면 한 번(구간 시작 시각마다). 6~22시만 */
    fun rainSoon(c: Context, s: Snapshot) {
        if (!Settings.rainOn(c)) return
        val now = LocalDateTime.now(SEOUL)
        if (now.hour !in 6..22) return
        val u = Next.upcoming(s, within = 2) ?: return
        if (u.popMax < 60 && !Next.wet(u.start.copy(pop = 0))) return
        val key = "rain_${now.toLocalDate()}_${u.start.hour}"
        val p = c.getSharedPreferences("alerts", Context.MODE_PRIVATE)
        if (p.getBoolean(key, false)) return
        p.edit().putBoolean(key, true).apply()
        val full = Next.upcoming(s, within = 12) ?: u
        notify(c, 300, CH_RAIN, "%02d시 %s 시작 (%d%%)".format(u.start.hour, u.what, full.popMax),
            "${full.range}, 최대 ${full.popMax}%", "${full.range}, 최대 ${full.popMax}%\n" +
            (if (u.snow) "미끄럼 주의. 방수 신발" else "우산 챙기기") + "\n" + Next.lines(s, 4, 1).joinToString("\n"),
            until = now.hour + 3)
    }

    private fun channels(nm: NotificationManager) = nm.createNotificationChannels(listOf(
        NotificationChannel(CH, "기상 특보·위험 기온", NotificationManager.IMPORTANCE_HIGH),
        NotificationChannel(CH_MORNING, "아침 옷차림·비 안내", NotificationManager.IMPORTANCE_DEFAULT),
        NotificationChannel(CH_RAIN, "비·눈 시작 1~2시간 전", NotificationManager.IMPORTANCE_DEFAULT),
    ))

    private fun notify(c: Context, id: Int, title: String, body: String) = notify(c, id, CH, title, body, body, until = 23)

    /** until: 그 시각(시)이 지나면 오래된 안내라 자동으로 지운다 */
    private fun notify(c: Context, id: Int, ch: String, title: String, short: String, body: String, until: Int) {
        val nm = c.getSystemService(NotificationManager::class.java)
        channels(nm)
        val now = LocalDateTime.now(SEOUL)
        val end = now.toLocalDate().atTime(until.coerceAtMost(23), 59)
        nm.notify(id, Notification.Builder(c, ch)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle(title).setContentText(short)
            .setShowWhen(true)
            .setTimeoutAfter(java.time.Duration.between(now, end).toMillis().coerceAtLeast(60_000))
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build())
    }
}

class MorningReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Alerts.scheduleMorning(c)
        run(c, goAsync())
    }

    companion object {
        fun run(c: Context, pending: BroadcastReceiver.PendingResult) = Thread {
            // 실패해도 Net 이 6시간 안의 지난 응답으로 버티므로, 여기서 예외는 '받은 적 없음'뿐이다.
            // 도즈 직후 네트워크가 늦게 붙는 경우만 짧게 한 번 더 — 리시버 제한(~60초) 안에 끝나도록
            val s = runCatching { Repo.fetch(c, force = true) }.getOrElse {
                Thread.sleep(5_000); runCatching { Repo.fetch(c, force = true) }.getOrNull()
            }
            if (s != null) {
                GlanceWidget.render(c, s)
                Alerts.morning(c, s)
                Alerts.danger(c, s)
            }
            pending.finish()
        }.start()
    }
}
