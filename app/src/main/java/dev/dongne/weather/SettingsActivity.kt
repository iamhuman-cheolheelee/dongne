package dev.dongne.weather

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as Sys
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** 권한·알림·위젯·배터리 등 설정은 모두 여기. 메인 화면은 날씨만 */
class SettingsActivity : Activity() {
    private lateinit var body: LinearLayout
    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        body = object : LinearLayout(this) {
            override fun onMeasure(w: Int, h: Int) {
                val max = px(640)
                super.onMeasure(if (MeasureSpec.getSize(w) > max) MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY) else w, h)
            }
        }.apply { orientation = LinearLayout.VERTICAL; setPadding(px(18), px(36), px(18), px(28)) }
        window.decorView.setBackgroundColor(0xFF15202B.toInt())
        setContentView(ScrollView(this).apply {
            addView(body, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL))
        })
    }

    // 시스템 설정에서 돌아오면 상태를 다시 그린다
    override fun onResume() { super.onResume(); render() }
    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) = render()

    private fun has(p: String) = checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private val appSettings get() = Intent(Sys.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    private fun text(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun section(title: String, vararg rows: View) {
        body.addView(text(title, 13f, 0xAAFFFFFF.toInt(), bold = true).apply { setPadding(px(4), px(18), 0, px(6)) })
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(16), px(4), px(16), px(4))
            background = GradientDrawable().apply { cornerRadius = 22 * dp; setColor(0x22FFFFFF) }
            rows.forEachIndexed { i, v ->
                if (i > 0) addView(View(this@SettingsActivity).apply { setBackgroundColor(0x18FFFFFF); layoutParams = LinearLayout.LayoutParams(-1, 1) })
                addView(v)
            }
        })
    }

    /** 제목·설명·오른쪽 값. ok=false 면 노란색으로 손볼 곳임을 표시 */
    private fun row(title: String, desc: String, value: String? = null, ok: Boolean = true, go: (() -> Unit)? = null) =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(12), 0, px(12)); minimumHeight = px(56)
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                addView(text(title, 16f))
                if (desc.isNotEmpty()) addView(text(desc, 13f, if (ok) 0x99FFFFFF.toInt() else 0xFFFFD27A.toInt()))
            })
            value?.let { addView(text(it, 14f, if (ok) 0xCCFFFFFF.toInt() else 0xFFFFD27A.toInt(), bold = !ok).apply { setPadding(px(10), 0, 0, 0) }) }
            go?.let { setOnClickListener { it() }; isClickable = true; setBackgroundResource(android.R.drawable.list_selector_background) }
        }

    private fun toggle(title: String, desc: String, on: Boolean, set: (Boolean) -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(12), 0, px(12))
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(text(title, 16f)); addView(text(desc, 13f, 0x99FFFFFF.toInt()))
        })
        val sw = Switch(this@SettingsActivity).apply { isChecked = on; setOnCheckedChangeListener { _, v -> set(v) } }
        addView(sw); setOnClickListener { sw.toggle() }
    }

    private fun render() {
        body.removeAllViews()
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text("‹", 30f).apply { setPadding(0, 0, px(14), 0); setOnClickListener { finish() }; contentDescription = "뒤로" })
            addView(text("설정", 24f, bold = true))
        })

        // 위치
        val fg = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
        val bg = has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        section("위치",
            row("위치 권한", if (fg) "" else "꺼져 있어 기본 위치(대전시청) 기준 날씨입니다", if (fg) "허용됨" else "허용하기", fg) {
                if (!fg && (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) || !asked(this, "loc")))
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
                else startActivity(appSettings) // 두 번 거부하면 시스템 창이 더 뜨지 않는다
            },
            row("위젯·알림도 이동 따라가기", if (bg) "위치를 '항상 허용'으로 쓰고 있습니다" else "지금은 앱을 마지막으로 연 곳 기준입니다",
                if (bg) "항상 허용" else "바꾸기", bg || !fg) {
                // Android 11+: 백그라운드 위치는 시스템이 설정 화면으로 보낸다
                if (fg && !bg) requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2) else startActivity(appSettings)
            })

        // 알림
        val notif = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val exact = getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        val h = Settings.morningHour(this)
        section("알림",
            row("알림 허용", if (notif) "" else "꺼져 있어 아래 알림이 모두 오지 않습니다", if (notif) "켜짐" else "켜기", notif) {
                if (!notif && !asked(this, "notif")) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
                else startActivity(Intent(Sys.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Sys.EXTRA_APP_PACKAGE, packageName))
            },
            toggle("아침 안내", "비·옷차림을 매일 아침 알려 줍니다", Settings.morningOn(this)) {
                Settings.set(this, "morning", it); Alerts.scheduleMorning(this); render()
            },
            row("아침 안내 시각", "", "%s %d시".format(if (h < 12) "오전" else "오후", if (h % 12 == 0) 12 else h % 12)) {
                TimePickerDialog(this, { _, hh, _ -> Settings.set(this, "morningHour", hh); Alerts.scheduleMorning(this); render() }, h, 0, false).show()
            },
            row("정시에 보내기", if (exact) "알람 및 리마인더 허용됨" else "꺼져 있으면 아침 안내가 몇 분 늦을 수 있습니다",
                if (exact) "켜짐" else "켜기", exact) {
                startActivity(Intent(Sys.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            },
            toggle("곧 비 소식", "2시간 안에 비·눈이 오면 알려 줍니다", Settings.rainOn(this)) { Settings.set(this, "rain", it) },
            toggle("상태바 현재 기온", "상태바에 날씨 아이콘과 기온을 띄웁니다", Settings.statusOn(this)) { on ->
                Settings.set(this, "status", on)
                Repo.cached(this)?.let { StatusIcon.post(this, it) } // 끄면 post 가 알림을 지운다
            })

        // 위젯·배터리
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        section("위젯·갱신",
            row("홈 화면에 위젯 추가", "", "추가") {
                getSystemService(AppWidgetManager::class.java).requestPinAppWidget(ComponentName(this, GlanceWidget::class.java), null, null)
            },
            row("배터리 최적화", if (battery) "제한 없음" else "위젯 갱신·아침 안내가 늦거나 멈출 수 있습니다 (삼성: 절전 예외 앱에 추가)",
                if (battery) "해제됨" else "해제하기", battery) {
                startActivity(if (battery) appSettings else Intent(Sys.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            })

        // 정보
        val v = packageManager.getPackageInfo(packageName, 0).versionName
        section("정보",
            row("버전", "", v),
            row("소스 코드 · 라이선스", "GPL-3.0 · 광고·추적 없음", "GitHub") {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/iamhuman-cheolheelee/dongne")))
            },
            row("데이터 출처", "기상청 · 에어코리아 · Open-Meteo", null))
    }

    companion object {
        /** 처음 한 번은 시스템 권한 창, 그 뒤로는 설정 화면 */
        fun asked(c: Context, k: String) = c.getSharedPreferences("perm", MODE_PRIVATE).run {
            getBoolean(k, false).also { edit().putBoolean(k, true).apply() }
        }

        /** 메인 화면 배너용: 손봐야 할 설정 개수 */
        fun issues(c: Context): Int {
            fun has(p: String) = c.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val fg = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
            return listOf(
                !fg,
                fg && !has(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                !c.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
                !c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName),
            ).count { it }
        }
    }
}
