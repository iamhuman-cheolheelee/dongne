package dev.dongne.weather

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var body: LinearLayout
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(18), px(36), px(18), px(28)) }
        setContentView(ScrollView(this).apply { addView(body) })
        Repo.cached(this)?.let(::show) ?: body.addView(text("불러오는 중…", 18f))
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != 0)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS), 1)
        else load()
        GlanceWidget.schedule(this)
        Alerts.scheduleMorning(this)
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) = load()

    /** 앱을 열 때는 캐시가 신선하면 네트워크를 쓰지 않는다. 새로고침 버튼만 force */
    private fun load(force: Boolean = false) = Thread {
        val s = runCatching { Repo.fetch(this, force) }
        runOnUiThread {
            s.onSuccess { show(it); GlanceWidget.render(this, it) }
                .onFailure { Toast.makeText(this, "갱신 실패: ${it.message}", Toast.LENGTH_SHORT).show() }
        }
    }.start()

    private fun px(v: Int) = (v * dp).toInt()
    private fun text(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun card(vararg vs: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(px(16), px(14), px(16), px(14))
        background = GradientDrawable().apply { cornerRadius = 22 * dp; setColor(0x22FFFFFF) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) }
        vs.forEach(::addView)
    }

    private fun show(s: Snapshot) {
        body.removeAllViews()
        window.decorView.setBackgroundResource(Wx.bg(s.code, s.isDay).let { if (it == R.drawable.bg_day) R.drawable.bg_day else it })
        body.addView(text(s.place, 18f, bold = true))
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(Wx.emoji(s.code, s.isDay), 64f))
            addView(text(" ${s.temp}°", 76f).apply { typeface = android.graphics.Typeface.create("sans-serif-light", 0) })
        })
        body.addView(text("${Wx.text(s.code)} · 체감 ${s.feels}° · 최고 ${s.max}° / 최저 ${s.min}°", 16f))
        body.addView(card(text("오늘 한 줄", 12f, 0xAAFFFFFF.toInt()), text(s.summary().replace("  ·  ", "\n"), 17f, bold = true)))

        // 24시간
        val row = LinearLayout(this)
        s.hours.forEach { h ->
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(px(9), 0, px(9), 0)
                addView(text(if (h.hour == 0) "자정" else "${h.hour}시", 12f, 0xCCFFFFFF.toInt()))
                addView(text(Wx.emoji(h.code, h.day), 22f))
                addView(text("${h.temp}°", 15f, bold = true))
                addView(text(if (h.pop >= 20) "${h.pop}%" else " ", 11f, 0xFF9FD8FF.toInt()))
            })
        }
        body.addView(card(text("시간별 (24시간)", 12f, 0xAAFFFFFF.toInt()),
            HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }))

        // 7일
        val lo = s.days.minOf { it.min }; val hi = s.days.maxOf { it.max }
        val week = s.days.map { d ->
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(6), 0, px(6))
                addView(text(d.label, 15f).apply { width = px(64) })
                addView(text(Wx.emoji(d.code), 20f).apply { width = px(40) })
                addView(text(if (d.pop >= 20) "${d.pop}%" else "", 12f, 0xFF9FD8FF.toInt()).apply { width = px(42) })
                addView(text("${d.min}°", 15f, 0xBBFFFFFF.toInt()).apply { width = px(40) })
                // 온도 막대 (주간 범위 대비)
                addView(LinearLayout(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, px(6), 1f)
                    background = GradientDrawable().apply { cornerRadius = 3 * dp; setColor(0x22FFFFFF) }
                    val span = (hi - lo).coerceAtLeast(1).toFloat()
                    addView(View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(0, -1, (d.min - lo) / span) })
                    addView(View(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, -1, ((d.max - d.min) / span).coerceAtLeast(0.05f))
                        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF7FD3FF.toInt(), 0xFFFFC857.toInt())).apply { cornerRadius = 3 * dp }
                    })
                    addView(View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(0, -1, (hi - d.max) / span) })
                })
                addView(text("${d.max}°", 15f, bold = true).apply { gravity = Gravity.END; width = px(40) })
            }
        }
        body.addView(card(text("7일 예보", 12f, 0xAAFFFFFF.toInt()), *week.toTypedArray()))

        // 오늘 옷차림
        val now = java.time.LocalDateTime.now()
        val o = Outfits.plan(s, now.monthValue, now.hour)
        fun line(k: String, v: String) = LinearLayout(this).apply {
            setPadding(0, px(5), 0, px(5))
            addView(text(k, 15f, 0xCCFFFFFF.toInt()).apply { width = px(92) })
            addView(text(v, 15f, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        }
        body.addView(card(
            text("${o.day} 옷차림 · 출근길 체감 ${o.low}° / 한낮 ${o.high}°", 12f, 0xAAFFFFFF.toInt()),
            *listOfNotNull(
                o.rain?.let { line("비·눈", it) },
                line("시간별", o.hourly.replace(" | ", "\n")),
                line("겉옷", o.outer), line("상의", o.top), line("하의", o.bottom),
                o.items.takeIf { it.isNotEmpty() }?.let { line("챙길 것", it.joinToString(", ")) },
            ).toTypedArray(),
            *o.notes.map { text("참고: $it", 13f, 0xEEFFFFFF.toInt()).apply { setPadding(0, px(4), 0, 0) } }.toTypedArray(),
            text("근거: 출퇴근 체감(기상청 체감온도 산식) · 계절 적응 보정(de Dear & Brager 1998) · 기온별 옷차림표(대전 기준 보정)", 11f, 0x88FFFFFF.toInt()).apply { setPadding(0, px(6), 0, 0) },
        ))

        // 생활 정보 (근거 포함)
        body.addView(card(text("생활 정보 · 근거", 12f, 0xAAFFFFFF.toInt()), *Life.tips(s).map { t ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, px(7), 0, px(7))
                addView(text(t.title, 15f, bold = true))
                addView(text(t.body, 14f, 0xEEFFFFFF.toInt()))
                addView(text("근거: ${t.source}", 11f, 0x88FFFFFF.toInt()))
            }
        }.toTypedArray()))

        // 상세
        fun cell(k: String, v: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(text(k, 12f, 0xAAFFFFFF.toInt())); addView(text(v, 17f, bold = true))
        }
        fun pair(a: View, b: View) = LinearLayout(this).apply { setPadding(0, px(6), 0, px(6)); addView(a); addView(b) }
        body.addView(card(
            pair(cell("미세먼지", "${s.aqEmoji} ${s.aqText}"), cell("PM10 / PM2.5", if (s.aqKnown) "${s.pm10} / ${s.pm25} ㎍" else "-")),
            pair(cell("습도", "${s.humidity}%"), cell("바람", "%.1f m/s".format(s.wind))),
            pair(cell("자외선", "%.0f · %s".format(s.uv, when { s.uv >= 8 -> "매우 높음"; s.uv >= 6 -> "높음"; s.uv >= 3 -> "보통"; else -> "낮음" })),
                cell("일출 / 일몰", "${s.sunrise} / ${s.sunset}")),
        ))

        // 정확한 알람이 꺼져 있으면 아침 안내가 몇 분 늦을 수 있다 — 켜는 곳으로 바로 보낸다
        if (!getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms())
            body.addView(Button(this).apply {
                text = "아침 7시 정각 안내 켜기 (알람 및 리마인더 허용)"
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) }
                setOnClickListener {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        android.net.Uri.parse("package:$packageName")))
                }
            })

        body.addView(LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) }
            addView(Button(this@MainActivity).apply { text = "새로고침"; setOnClickListener { load(force = true) }; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            addView(Button(this@MainActivity).apply {
                text = "홈 화면에 위젯 추가"; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener {
                    getSystemService(AppWidgetManager::class.java).requestPinAppWidget(ComponentName(this@MainActivity, GlanceWidget::class.java), null, null)
                }
            })
        })
        body.addView(text("업데이트 " + SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(s.fetchedAt)) + " · ${s.source}" + (if (s.airSource.isNotEmpty()) " · 대기질 ${s.airSource}" else "") + " · Weather data by Open-Meteo.com (CC BY 4.0) · 광고 없음", 11f, 0x88FFFFFF.toInt()).apply { setPadding(0, px(10), 0, 0) })
    }
}
