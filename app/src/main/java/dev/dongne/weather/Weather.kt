package dev.dongne.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

data class Hour(val label: String, val hour: Int, val temp: Int, val code: Int, val pop: Int, val day: Boolean, val rh: Int = 50, val wind: Double = 1.0, val feels: Int = temp)
data class Day(val label: String, val code: Int, val max: Int, val min: Int, val pop: Int, val feelsMax: Int = max)

data class Snapshot(
    val place: String, val temp: Int, val feels: Int, val code: Int, val isDay: Boolean,
    val humidity: Int, val wind: Double, val max: Int, val min: Int, val yMax: Int, val uv: Double,
    val sunrise: String, val sunset: String, val pm10: Int, val pm25: Int,
    val hours: List<Hour>, val days: List<Day>, val fetchedAt: Long, val yMin: Int = min,
    val source: String = OpenMeteo.NAME,
    /** 어제→오늘 최저기온 하강폭. 같은 출처(Open-Meteo) 두 값의 차이라 출처 섞임 오차가 없다 */
    val drop: Int = 0,
    val airSource: String = "",
    /** 지금 이 위치에 발효 중인 기상청 특보(예: "폭염주의보"). 국내·조회 성공 시에만 */
    val warnings: List<String> = emptyList(),
    /** 현재 기온·체감이 관측소 실측이면 true(예보값이면 false) */
    val observed: Boolean = false,
) {
    /** 대기질을 못 받았으면 pm10·pm25 = -1 (등급 0 으로 두되 '정보 없음'으로 표시) */
    val aqKnown: Boolean get() = pm10 >= 0 && pm25 >= 0
    val aqGrade: Int get() = if (aqKnown) maxOf(grade(pm10, 30, 80, 150), grade(pm25, 15, 35, 75)) else 0
    val aqText: String get() = if (aqKnown) listOf("좋음", "보통", "나쁨", "매우나쁨")[aqGrade] else "정보 없음"
    val aqEmoji: String get() = if (aqKnown) listOf("🟢", "🟡", "🟠", "🔴")[aqGrade] else "⚪"

    /** 위젯·상태바 한 줄: 비·눈 > 옷차림 > 미세먼지 */
    fun summary(): String {
        val now = java.time.LocalDateTime.now()
        val o = Outfits.plan(this, now.monthValue, now.hour)
        val tips = mutableListOf<String>()
        o.rain?.let { tips += it.substringBefore(" (") }
        tips += o.headline
        if (aqGrade >= 2) tips += "미세먼지 $aqText"
        // 실제 발효 중인 특보가 가장 먼저
        if (warnings.isNotEmpty()) tips.add(0, warnings.joinToString("·") + " 발효")
        return tips.take(2).joinToString(" · ")
    }

    fun toJson(): String = JSONObject().apply {
        put("place", place); put("temp", temp); put("feels", feels); put("code", code); put("isDay", isDay)
        put("humidity", humidity); put("wind", wind); put("max", max); put("min", min); put("yMax", yMax); put("uv", uv)
        put("sunrise", sunrise); put("sunset", sunset); put("pm10", pm10); put("pm25", pm25); put("fetchedAt", fetchedAt)
        put("hours", org.json.JSONArray(hours.map { JSONObject().put("l", it.label).put("h", it.hour).put("t", it.temp).put("c", it.code)
            .put("p", it.pop).put("d", it.day).put("rh", it.rh).put("w", it.wind).put("f", it.feels) }))
        put("days", org.json.JSONArray(days.map { JSONObject().put("l", it.label).put("c", it.code).put("x", it.max).put("n", it.min).put("p", it.pop).put("f", it.feelsMax) }))
        put("yMin", yMin); put("source", source); put("drop", drop); put("airSource", airSource)
        put("warnings", warnings.joinToString(",")); put("observed", observed)
    }.toString()

    companion object {
        private fun grade(v: Int, a: Int, b: Int, c: Int) = when { v <= a -> 0; v <= b -> 1; v <= c -> 2; else -> 3 }
        fun fromJson(s: String): Snapshot = JSONObject(s).run {
            val hs = getJSONArray("hours"); val ds = getJSONArray("days")
            Snapshot(getString("place"), getInt("temp"), getInt("feels"), getInt("code"), getBoolean("isDay"),
                getInt("humidity"), getDouble("wind"), getInt("max"), getInt("min"), getInt("yMax"), getDouble("uv"),
                getString("sunrise"), getString("sunset"), getInt("pm10"), getInt("pm25"),
                (0 until hs.length()).map { hs.getJSONObject(it).run { Hour(getString("l"), getInt("h"), getInt("t"), getInt("c"), getInt("p"), getBoolean("d"), optInt("rh", 50), optDouble("w", 1.0), optInt("f", getInt("t"))) } },
                (0 until ds.length()).map { ds.getJSONObject(it).run { Day(getString("l"), getInt("c"), getInt("x"), getInt("n"), getInt("p"), optInt("f", getInt("x"))) } },
                getLong("fetchedAt"), optInt("yMin", getInt("min")), optString("source", OpenMeteo.NAME),
                optInt("drop", 0), optString("airSource", ""),
                optString("warnings", "").split(",").filter { it.isNotBlank() }, optBoolean("observed", false))
        }
    }
}

object Wx {
    fun emoji(c: Int, day: Boolean = true) = when (c) {
        0 -> if (day) "☀️" else "🌙"; 1 -> if (day) "🌤️" else "🌙"; 2 -> "⛅"; 3 -> "☁️"
        45, 48 -> "🌫️"; in 51..57 -> "🌦️"; in 61..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "🌨️"; in 95..99 -> "⛈️"; else -> "🌡️"
    }
    fun text(c: Int) = when (c) {
        0 -> "맑음"; 1 -> "대체로 맑음"; 2 -> "구름 조금"; 3 -> "흐림"; 45, 48 -> "안개"
        in 51..57 -> "이슬비"; 61, 80 -> "약한 비"; 63, 81 -> "비"; 65, 82 -> "강한 비"; 66, 67 -> "어는 비"
        71, 85 -> "약한 눈"; 73, 75, 77, 86 -> "눈"; in 95..99 -> "뇌우"; else -> "-"
    }
    fun bg(c: Int, day: Boolean) = when {
        c in 51..99 -> R.drawable.bg_rain
        !day -> R.drawable.bg_night
        c >= 3 -> R.drawable.bg_cloud
        else -> R.drawable.bg_day
    }
}

object Repo {
    // 기본값: 대전시청 (위치 권한·신호가 없을 때)
    private const val DEF_LAT = 36.3504
    private const val DEF_LON = 127.3845
    /** 마지막 위치가 이보다 묵었으면 한 번 새로 잰다 */
    private const val LOC_MAX_AGE = 30 * Net.MIN
    private const val LOC_TIMEOUT = 10_000L

    private fun prefs(c: Context) = c.getSharedPreferences("wx", Context.MODE_PRIVATE)
    private fun net(c: Context) = Net(File(c.cacheDir, "http"))

    fun cached(c: Context): Snapshot? = prefs(c).getString("snap", null)?.let { runCatching { Snapshot.fromJson(it) }.getOrNull() }

    private fun location(c: Context): Pair<Double, Double> {
        val p = prefs(c)
        val granted = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            .any { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            val lm = c.getSystemService(LocationManager::class.java)
            val last = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.elapsedRealtimeNanos }
            val loc = last?.takeIf { age(it) < LOC_MAX_AGE } ?: current(lm) ?: last
            if (loc != null) {
                p.edit().putFloat("lat", loc.latitude.toFloat()).putFloat("lon", loc.longitude.toFloat()).apply()
                return loc.latitude to loc.longitude
            }
        }
        return p.getFloat("lat", DEF_LAT.toFloat()).toDouble() to p.getFloat("lon", DEF_LON.toFloat()).toDouble()
    }

    private fun age(l: Location) = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000

    /** 한 번 새로 잰다. 기상청 격자가 5km 라 저전력(fused·network) 정확도면 충분. 실패·시간 초과면 null */
    private fun current(lm: LocationManager): Location? {
        val enabled = lm.getProviders(true)
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in enabled } ?: return null
        val got = AtomicReference<Location?>(); val done = CountDownLatch(1); val cancel = CancellationSignal()
        runCatching { lm.getCurrentLocation(provider, cancel, { it.run() }) { got.set(it); done.countDown() } }
            .onFailure { return null }
        if (!done.await(LOC_TIMEOUT, TimeUnit.MILLISECONDS)) cancel.cancel()
        return got.get()
    }

    private fun placeName(c: Context, lat: Double, lon: Double): String {
        val key = Coord.of(lat, lon).key; val p = prefs(c)
        if (p.getString("placeKey", null) == key) p.getString("place", null)?.let { return it }
        return lookupPlace(c, lat, lon).also { p.edit().putString("placeKey", key).apply() }
    }

    @Suppress("DEPRECATION")
    private fun lookupPlace(c: Context, lat: Double, lon: Double): String = runCatching {
        Geocoder(c, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { a ->
            listOf(a.thoroughfare, a.subLocality, a.locality).firstOrNull { it != null && (it.endsWith("동") || it.endsWith("읍") || it.endsWith("면")) }
                ?: a.subLocality ?: a.locality
                ?: a.adminArea ?: a.countryName
        }
    }.getOrNull() ?: prefs(c).getString("place", null) ?: "현재 위치"

    /** 백그라운드에서 돌리고, timeout 안에 끝난 결과만 받는다 */
    private fun <T> async(timeout: Long, f: () -> T?): () -> T? {
        val r = AtomicReference<T?>()
        val t = Thread { r.set(runCatching(f).getOrNull()) }.apply { start() }
        return { t.join(timeout); r.get() }
    }

    /**
     * 블로킹. 기본 예보를 못 받고 6시간 안의 지난 응답도 없으면 예외.
     * force 가 아니면 출처별 캐시가 신선한 동안 네트워크를 쓰지 않는다(앱 열기·위젯 갱신이 잦아도 요청이 늘지 않는다).
     */
    fun fetch(c: Context, force: Boolean = false): Snapshot {
        val (lat, lon) = location(c)
        val p = Coord.of(lat, lon)
        val net = net(c)
        val air = async(12_000) { Sources.first(Sources.air, { it.covers(p) }) { it.fetch(net, p, force) } }
        val local = async(15_000) { Sources.first(Sources.local, { it.covers(p) }) { it.fetch(net, p, force) } }
        val res = OpenMeteo.forecast(net, p, force)
        val s = build(JSONObject(res.body), local(), air(), placeName(c, lat, lon), res.fetchedAt)
        prefs(c).edit().putString("snap", s.toJson()).putString("place", s.place).apply()
        return s
    }

    private val WEEK = arrayOf("일", "월", "화", "수", "목", "금", "토")

    /**
     * Open-Meteo 응답 위에 지역 예보를 덮어 한 장을 만든다.
     * '지금'은 응답 시각이 아니라 실제 시각 기준이라, 캐시·지난 응답을 써도 시간별 칸이 밀리지 않는다.
     */
    fun build(f: JSONObject, local: LocalForecast?, air: Air?, place: String, fetchedAt: Long,
              nowMs: Long = System.currentTimeMillis()): Snapshot {
        val offset = ZoneOffset.ofTotalSeconds(f.optInt("utc_offset_seconds", 9 * 3600))
        val wall = Instant.ofEpochMilli(nowMs).atOffset(offset).toLocalDateTime()
        val nowKey = "%sT%02d".format(wall.toLocalDate(), wall.hour)
        val nextKey = wall.plusHours(1).let { "%sT%02d".format(it.toLocalDate(), it.hour) }
        val kh = local?.hours?.associateBy { "%sT%02d".format(it.date, it.hour) } ?: emptyMap()
        val cur = f.getJSONObject("current"); val h = f.getJSONObject("hourly"); val d = f.getJSONObject("daily")

        val ht = h.getJSONArray("time")
        fun col(name: String) = h.getJSONArray(name)
        val curFresh = cur.getString("time").startsWith(nowKey)
        val start = (0 until ht.length()).firstOrNull { ht.getString(it).startsWith(nowKey) }
            ?: (0 until ht.length()).first { ht.getString(it).startsWith(cur.getString("time").substring(0, 13)) }
        val hours = (start + 1 until minOf(start + 25, ht.length())).map { i ->
            val hr = ht.getString(i).substring(11, 13).toInt()
            val temp = col("temperature_2m").getDouble(i).roundToInt()
            val base = Hour(if (hr == 0) "내일" else if (hr < 12) "오전 ${hr}시" else "오후 ${if (hr == 12) 12 else hr - 12}시",
                hr, temp, col("weather_code").getInt(i), col("precipitation_probability").optInt(i, 0),
                col("is_day").getInt(i) == 1, col("relative_humidity_2m").optInt(i, 50), col("wind_speed_10m").optDouble(i, 1.0),
                col("apparent_temperature").optDouble(i, temp.toDouble()).roundToInt())
            val k = kh[ht.getString(i).substring(0, 13)] ?: return@map base
            base.copy(temp = k.temp, code = k.code.takeIf { it >= 0 } ?: base.code, pop = k.pop, rh = k.rh, wind = k.wind, feels = k.feels)
        }

        val dt = d.getJSONArray("time")
        fun dcol(name: String) = d.getJSONArray(name)
        val today = (0 until dt.length()).firstOrNull { dt.getString(it) == wall.toLocalDate().toString() } ?: 1
        val kd = local?.days?.associateBy { it.date } ?: emptyMap()
        val days = (today until dt.length()).map { i ->
            val date = LocalDate.parse(dt.getString(i))
            val base = Day(if (i == today) "오늘" else if (i == today + 1) "내일" else WEEK[date.dayOfWeek.value % 7] + " ${date.dayOfMonth}",
                dcol("weather_code").getInt(i), dcol("temperature_2m_max").getDouble(i).roundToInt(),
                dcol("temperature_2m_min").getDouble(i).roundToInt(), dcol("precipitation_probability_max").optInt(i, 0),
                dcol("apparent_temperature_max").getDouble(i).roundToInt())
            val k = kd[dt.getString(i)] ?: return@map base
            val hs = local!!.hours.filter { it.date == dt.getString(i) }
            // 시간별이 있는 날(단기)은 시간별에서, 없는 날(중기 4~10일)은 기상청 오전·오후 값에서
            // 일 최고·최저가 그날 시간별 기온 범위를 벗어나면 안 된다 (발표 시각에 따라 일 값이 다른 날 것이거나 낡은 경우)
            val kMax = k.max ?: base.max; val kMin = k.min ?: base.min
            base.copy(max = maxOf(kMax, hs.maxOfOrNull { it.temp } ?: kMax), min = minOf(kMin, hs.minOfOrNull { it.temp } ?: kMin),
                pop = hs.maxOfOrNull { it.pop } ?: k.pop.takeIf { it >= 0 } ?: base.pop,
                code = hs.filter { it.hour in 6..21 }.map { it.code }.maxOrNull()?.takeIf { it >= 0 }
                    ?: k.code.takeIf { it >= 0 } ?: base.code,
                // 체감 최고도 같은 출처(기상청 시간별 체감)에서 — 기온은 기상청, 체감은 다른 모델인 섞임을 막는다
                feelsMax = hs.maxOfOrNull { it.feels } ?: base.feelsMax)
        }

        val y = (today - 1).coerceAtLeast(0)
        val kNow = kh[nowKey] ?: kh[nextKey]
        // 응답의 current 가 지난 시각이면(캐시) 이번 시각의 시간별 값을 쓴다
        val cTemp = if (curFresh) cur.getDouble("temperature_2m") else col("temperature_2m").getDouble(start)
        val cFeels = if (curFresh) cur.getDouble("apparent_temperature") else col("apparent_temperature").getDouble(start)
        val cCode = if (curFresh) cur.getInt("weather_code") else col("weather_code").getInt(start)
        val cRh = if (curFresh) cur.getInt("relative_humidity_2m") else col("relative_humidity_2m").optInt(start, 50)
        val cWind = if (curFresh) cur.getDouble("wind_speed_10m") else col("wind_speed_10m").optDouble(start, 1.0)
        val cDay = if (curFresh) cur.getInt("is_day") == 1 else col("is_day").getInt(start) == 1
        val yMin = dcol("temperature_2m_min").getDouble(y).roundToInt()
        // 현재값 우선순위: 관측소 실측 > 지역 예보의 이번 시각 > 기본 예보
        val obs = local?.now
        val fcCode = kNow?.code?.takeIf { it >= 0 } ?: cCode
        // 지금 비가 실제로 내리고 있는데(1시간 강수량 > 0) 예보 날씨가 맑음·흐림이면 약한 비로 고친다
        val code = if ((obs?.rain1h ?: 0.0) > 0 && fcCode < 51) 61 else fcCode
        val nowTemp = obs?.temp?.roundToInt() ?: kNow?.temp ?: cTemp.roundToInt()
        // 오늘 최고·최저는 지금 실측 기온도 포함해야 한다 (현재 21° 인데 최고 16° 로 보이는 문제)
        val week = days.mapIndexed { i, d -> if (i == 0) d.copy(max = maxOf(d.max, nowTemp), min = minOf(d.min, nowTemp)) else d }
        return Snapshot(place,
            nowTemp,
            obs?.let { (it.feels ?: it.temp).roundToInt() } ?: kNow?.feels ?: cFeels.roundToInt(),
            code, cDay,
            obs?.rh ?: kNow?.rh ?: cRh, obs?.wind ?: kNow?.wind ?: cWind,
            week[0].max, week[0].min, dcol("temperature_2m_max").getDouble(y).roundToInt(),
            dcol("uv_index_max").optDouble(today, 0.0),
            dcol("sunrise").getString(today).takeLast(5), dcol("sunset").getString(today).takeLast(5),
            air?.pm10 ?: -1, air?.pm25 ?: -1,
            hours, week, fetchedAt, yMin,
            local?.source ?: OpenMeteo.NAME,
            drop = yMin - dcol("temperature_2m_min").getDouble(today).roundToInt(),
            airSource = air?.source ?: "",
            warnings = obs?.warnings ?: emptyList(), observed = obs != null)
    }
}
