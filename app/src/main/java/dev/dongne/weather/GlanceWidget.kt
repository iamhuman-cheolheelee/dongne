package dev.dongne.weather

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GlanceWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        Repo.cached(c)?.let { render(c, it) }
        schedule(c)
        refresh(c, goAsync())
    }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) {
        Repo.cached(c)?.let { render(c, it) }
    }

    override fun onDisabled(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(refreshIntent(c))
    }

    companion object {
        private val HOUR_IDS = (0..5).map { listOf("h${it}t", "h${it}i", "h${it}c", "h${it}p") }

        fun refreshIntent(c: Context, force: Boolean = false) = PendingIntent.getBroadcast(c, if (force) 3 else 1,
            Intent(c, WidgetActions::class.java).setAction(WidgetActions.REFRESH).putExtra(WidgetActions.FORCE, force),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** 다음 갱신까지: 비·눈이 2시간 안이면 15분, 밤(0~5시)·절전 중이면 2시간, 그 외 30분 */
        fun interval(s: Snapshot?, hour: Int, powerSave: Boolean): Long = when {
            s != null && Next.upcoming(s, within = 2) != null && !powerSave -> 15 * Net.MIN
            hour in 0..5 || powerSave -> 2 * Net.HOUR
            else -> 30 * Net.MIN
        }

        /** 갱신이 끝날 때마다 다음 한 번을 예약한다(반복 알람 대신). 기기가 잠들어 있으면 깨우지 않고 깬 뒤에 */
        fun schedule(c: Context, s: Snapshot? = Repo.cached(c)) {
            val ms = interval(s, java.time.LocalTime.now().hour, c.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode)
            val am = c.getSystemService(AlarmManager::class.java)
            // 이전 버전이 GlanceWidget 앞으로 걸어 둔 30분 반복 알람(업데이트해도 남는다)
            PendingIntent.getBroadcast(c, 1, Intent(c, GlanceWidget::class.java).setAction("dev.dongne.weather.REFRESH"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
            am.setWindow(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + ms, ms / 4, refreshIntent(c))
        }

        /** force: 사용자가 누른 새로고침. 주기 갱신은 출처별 캐시를 따른다 */
        fun refresh(c: Context, pending: PendingResult? = null, force: Boolean = false) {
            Thread {
                val s = runCatching { Repo.fetch(c, force).also { render(c, it); Alerts.danger(c, it); Alerts.rainSoon(c, it) } }
                    .getOrElse { Repo.cached(c)?.also { render(c, it, failed = true) } }
                schedule(c, s)
                pending?.finish()
            }.start()
        }

        fun render(c: Context, s: Snapshot, failed: Boolean = false) {
            StatusIcon.post(c, s)
            val m = AppWidgetManager.getInstance(c)
            val ids = m.getAppWidgetIds(ComponentName(c, GlanceWidget::class.java))
            if (ids.isEmpty()) return
            // 크기별 레이아웃: 런처가 위젯 실제 크기에 맞는 것을 고른다 (1x1 ~ 전체 화면)
            val v = RemoteViews(mapOf(
                SizeF(40f, 40f) to build(c, s, R.layout.widget_s, failed),       // 1x1·2x1
                SizeF(100f, 100f) to build(c, s, R.layout.widget_sq, failed),    // 2x2
                SizeF(180f, 40f) to build(c, s, R.layout.widget_m, failed),      // 3x1·4x1
                SizeF(240f, 100f) to build(c, s, R.layout.widget, failed),       // 4x2
                SizeF(240f, 250f) to build(c, s, R.layout.widget_xl, failed),    // 4x3 이상
            ))
            m.updateAppWidget(ids, v)
            preview(c, m, s)
        }

        /** 위젯 선택 화면 미리보기를 실제 내 날씨로 (Android 15+, 시스템이 횟수 제한 — 6시간에 한 번) */
        private fun preview(c: Context, m: AppWidgetManager, s: Snapshot) {
            if (android.os.Build.VERSION.SDK_INT < 35) return
            val p = c.getSharedPreferences("w", Context.MODE_PRIVATE)
            if (System.currentTimeMillis() - p.getLong("preview", 0) < 6 * 3600_000) return
            runCatching {
                if (m.setWidgetPreview(ComponentName(c, GlanceWidget::class.java),
                        android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, build(c, s, R.layout.widget, false)))
                    p.edit().putLong("preview", System.currentTimeMillis()).apply()
            }
        }

        /** 레이아웃에 없는 id 에 대한 설정은 RemoteViews 가 무시한다 */
        private fun build(c: Context, s: Snapshot, layout: Int, failed: Boolean): RemoteViews {
            val v = RemoteViews(c.packageName, layout)
            v.setInt(android.R.id.background, "setBackgroundResource", Wx.bg(s.code, s.isDay))
            v.setTextViewText(R.id.icon, Wx.emoji(s.code, s.isDay))
            // 이모지는 화면 낭독기가 '해 그림' 처럼 읽는다 — 날씨 낱말로
            v.setContentDescription(R.id.icon, Wx.text(s.code))
            v.setContentDescription(R.id.refresh, "새로고침")
            v.setTextViewText(R.id.temp, "${s.temp}°")
            v.setTextViewText(R.id.cond, "${Wx.text(s.code)} · 체감 ${s.feels}°")
            v.setTextViewText(R.id.hilo, "최고 ${s.max}° 최저 ${s.min}° · 습도 ${s.humidity}%")
            v.setTextViewText(R.id.place, s.place)
            v.setTextViewText(R.id.aq, "미세먼지 ${s.aqText}")
            v.setTextViewText(R.id.updated, (if (failed) "갱신 실패 · 탭해 재시도 · " else "") + (if (s.source.startsWith("기상청")) "기상청 ↻ " else "↻ ") + SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(s.fetchedAt)))
            v.setTextViewText(R.id.summary, s.summary())
            // 3시간 간격 6칸 = 앞으로 18시간
            s.hours.filterIndexed { i, _ -> i % 3 == 0 }.take(6).forEachIndexed { k, h ->
                val (t, ic, tc, p) = HOUR_IDS[k].map { c.resources.getIdentifier(it, "id", c.packageName) }
                v.setTextViewText(t, if (h.hour == 0) "자정" else "${h.hour}시")
                v.setTextViewText(ic, Wx.emoji(h.code, h.day))
                v.setContentDescription(ic, Wx.text(h.code))
                v.setTextViewText(tc, "${h.temp}°")
                v.setTextViewText(p, if (h.pop >= 20) "${h.pop}%" else "")
            }
            v.setOnClickPendingIntent(android.R.id.background, PendingIntent.getActivity(c, 0,
                Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            v.setOnClickPendingIntent(R.id.refresh, refreshIntent(c, force = true))
            v.setTextViewText(R.id.hilo_s, "최고 ${s.max}° 최저 ${s.min}°")
            v.setTextViewText(R.id.next, Next.change(s))
            v.setTextViewText(R.id.headline, java.time.LocalDateTime.now().let { Outfits.plan(s, it.monthValue, it.hour).headline })
            // 1x1·2x1 은 한 줄: 다음 변화가 있으면 그것, 없으면 최고/최저
            v.setTextViewText(R.id.rain_s, Next.change(s).takeUnless { it.endsWith("없음") }?.substringBefore(" (") ?: "${s.max}°/${s.min}°")
            s.days.take(5).forEachIndexed { k, d ->
                fun id(x: String) = c.resources.getIdentifier("d$k$x", "id", c.packageName)
                v.setTextViewText(id("l"), d.label)
                v.setTextViewText(id("i"), Wx.emoji(d.code))
                v.setContentDescription(id("i"), Wx.text(d.code))
                v.setTextViewText(id("p"), if (d.pop >= 20) "${d.pop}%" else "")
                v.setTextViewText(id("r"), "${d.min}° / ${d.max}°")
            }
            return v
        }
    }
}

/**
 * 앱 내부 동작(새로고침·부팅·정확한 알람 권한 변경). exported=false 라 다른 앱은 보낼 수 없다.
 * 위젯 제공자(GlanceWidget)는 런처가 불러야 해서 exported=true 여야 하므로, 내부 동작을 거기서 분리했다.
 */
class WidgetActions : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            REFRESH -> { Alerts.scheduleMorning(c); GlanceWidget.refresh(c, goAsync(), i.getBooleanExtra(FORCE, false)) }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                GlanceWidget.schedule(c); Alerts.scheduleMorning(c); GlanceWidget.refresh(c, goAsync())
            }
            // 사용자가 '알람 및 리마인더'를 켜면 정각 예약으로 다시 건다
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> Alerts.scheduleMorning(c)
        }
    }

    companion object {
        const val REFRESH = "dev.dongne.weather.REFRESH"
        const val FORCE = "force"
    }
}
