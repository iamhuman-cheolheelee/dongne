package dev.dongne.weather

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class SourcesTest {
    @Test fun coordRoundsToAboutOneKm() {
        val p = Coord.of(36.350412, 127.384548)
        assertEquals(36.35, p.lat, 1e-9); assertEquals(127.38, p.lon, 1e-9)
        assertEquals("36.35,127.38", p.key)
    }

    @Test fun kmaGridMatchesOfficialTable() {
        assertEquals(67 to 100, Kma.grid(36.3504, 127.3845)) // 대전시청
        assertEquals(60 to 127, Kma.grid(37.5665, 126.9780)) // 서울시청
    }

    /** 실제 날씨누리 페이지 일부로 파서 회귀 확인. 기온과 체감을 따로 읽는다 */
    @Test fun kmaParsesRealPage() {
        val html = javaClass.getResource("/kma_digital.html")!!.readText()
        val f = Kma.parse(html)!!
        assertEquals(7, f.hours.size)
        val h = f.hours.first()
        assertEquals("2026-10-25", h.date); assertEquals(3, h.hour)
        assertEquals(15, h.temp); assertEquals(15, h.feels); assertEquals(61, h.code) // 약한비
        assertEquals(LocalDay("2026-10-25", 14, 21), f.days.first())
    }

    @Test fun kmaRejectsBrokenPage() {
        assertEquals(null, Kma.parse("<html>점검 중</html>"))
    }

    @Test fun firstSkipsFailingAndUncoveredSources() {
        val r = Sources.first(listOf(1, 2, 3, 4), { it != 2 }) { if (it == 1) error("down") else "src$it" }
        assertEquals("src3", r)
    }

    // ---- Repo.build: 기본 예보 + 지역 예보 합치기 ----

    private val now = LocalDateTime.of(2026, 1, 15, 7, 20)
    private val nowMs = now.toInstant(ZoneOffset.ofHours(9)).toEpochMilli()

    /** 어제~6일 뒤, 한 시간 전부터 26시간. 기온은 시각 그대로(t = hour), 체감은 기온-3 */
    private fun openMeteo(curTime: String = "2026-01-15T07:15"): JSONObject {
        val times = (-1..25).map { now.withMinute(0).plusHours(it.toLong()) }
        fun arr(f: (LocalDateTime) -> Any) = JSONArray(times.map(f))
        val days = (-1..6).map { now.toLocalDate().plusDays(it.toLong()) }
        fun darr(f: (Int) -> Any) = JSONArray(days.indices.map(f))
        return JSONObject()
            .put("utc_offset_seconds", 9 * 3600)
            .put("current", JSONObject().put("time", curTime).put("temperature_2m", -2.0).put("apparent_temperature", -6.0)
                .put("relative_humidity_2m", 60).put("weather_code", 0).put("wind_speed_10m", 3.0).put("is_day", 1))
            .put("hourly", JSONObject().put("time", arr { it.toString().substring(0, 16) })
                .put("temperature_2m", arr { it.hour.toDouble() }).put("apparent_temperature", arr { it.hour - 3.0 })
                .put("precipitation_probability", arr { 0 }).put("weather_code", arr { 1 }).put("is_day", arr { if (it.hour in 7..17) 1 else 0 })
                .put("relative_humidity_2m", arr { 50 }).put("wind_speed_10m", arr { 2.0 }))
            .put("daily", JSONObject().put("time", JSONArray(days.map { it.toString() }))
                .put("weather_code", darr { 1 }).put("temperature_2m_max", darr { 5.0 })
                // 어제 최저 2°, 오늘 -9° → 11° 하강
                .put("temperature_2m_min", darr { if (it == 0) 2.0 else -9.0 })
                .put("apparent_temperature_max", darr { 1.0 }).put("precipitation_probability_max", darr { 10 })
                .put("uv_index_max", darr { 2.0 }).put("sunrise", darr { "2026-01-15T07:40" }).put("sunset", darr { "2026-01-15T17:40" }))
    }

    @Test fun buildOverlaysLocalForecastWithConsistentFeels() {
        val local = LocalForecast("기상청 동네예보",
            (7..23).map { LocalHour("2026-01-15", it, 3, -4, -10, 30, 70, 4.0) }, listOf(LocalDay("2026-01-15", -11, 0)))
        val s = Repo.build(openMeteo(), local, Air(40, 20, "CAMS 모델"), "둔산동", 0L, nowMs)
        // 현재: 기온·체감 모두 기상청(섞이지 않는다)
        assertEquals(-4, s.temp); assertEquals(-10, s.feels); assertEquals(3, s.code)
        assertEquals("기상청 동네예보", s.source)
        // 오늘: 최고·최저는 기상청 일별, 체감 최고는 기상청 시간별 체감에서
        assertEquals("오늘", s.days[0].label); assertEquals(-11, s.days[0].min); assertEquals(-10, s.days[0].feelsMax)
        // 하강폭은 Open-Meteo 끼리(2 → -9)
        assertEquals(11, s.drop)
        assertEquals(1, s.aqGrade); assertTrue(s.aqKnown)
    }

    @Test fun buildFromStaleCacheUsesWallClock() {
        // 캐시가 2시간 전 응답이어도 시간별 칸은 '지금' 다음 시각부터
        val s = Repo.build(openMeteo(curTime = "2026-01-15T05:15"), null, null, "둔산동", 0L, nowMs)
        assertEquals(8, s.hours.first().hour)
        assertEquals(7, s.temp) // current(05시) 대신 이번 시각(07시) 시간별 값
        assertEquals(false, s.aqKnown); assertEquals("정보 없음", s.aqText)
        assertEquals("Open-Meteo", s.source)
    }

    @Test fun snapshotJsonRoundTrip() {
        val s = Repo.build(openMeteo(), null, Air(10, 5, "CAMS 모델"), "둔산동", 123L, nowMs)
        assertEquals(s, Snapshot.fromJson(s.toJson()))
    }

    // ---- 날씨누리 현재 날씨(관측·특보·측정소) / 일별 슬라이드(중기 포함) ----

    @Test fun kmaParsesCurrentObservation() {
        val n = Kma.parseNow(javaClass.getResource("/kma_current.html")!!.readText())!!
        assertEquals(15.1, n.temp, 1e-9); assertEquals(15.1, n.feels!!, 1e-9)
        assertEquals(98, n.rh); assertEquals(0.9, n.wind!!, 1e-9)
        assertEquals(emptyList<String>(), n.warnings)
        assertEquals(5, n.pm25); assertEquals(17, n.pm10); assertEquals("둔산동측정소", n.station)
    }

    @Test fun kmaParsesWarnings() {
        val html = """<span class="tmp">33.0<small>℃</small></span><div class="cmp-impact-fct" data-impact-fct="">
            <a>대전: 폭염경보</a> <a>강풍 주의보</a> <a>폭염경보</a></div>"""
        assertEquals(listOf("폭염경보", "강풍주의보"), Kma.parseNow(html)!!.warnings)
    }

    @Test fun kmaParsesDailySlidesIncludingMidTerm() {
        val days = Kma.parseDaily(javaClass.getResource("/kma_daily.html")!!.readText())
        assertEquals(7, days.size)
        assertEquals("2026-10-25", days.first().date)
        assertEquals(LocalDay("2026-10-30", 10, 21, 63, 60), days.first { it.date == "2026-10-30" })
    }

    @Test fun buildUsesObservationAndMidTermDays() {
        val obs = LocalNow(-3.4, -8.0, 80, 5.5, 0.5, listOf("한파주의보"), 30, 12, "둔산동측정소")
        val local = LocalForecast("기상청 동네예보",
            (7..23).map { LocalHour("2026-01-15", it, 1, -4, -10, 30, 70, 4.0) },
            listOf(LocalDay("2026-01-15", -11, 0), LocalDay("2026-01-19", -7, 2, 71, 70)), obs)
        val s = Repo.build(openMeteo(), local, null, "둔산동", 0L, nowMs)
        // 현재: 관측값, 비가 오고 있으니 맑음 예보를 약한 비로
        assertEquals(-3, s.temp); assertEquals(-8, s.feels); assertEquals(80, s.humidity); assertEquals(61, s.code)
        assertEquals(listOf("한파주의보"), s.warnings); assertTrue(s.observed)
        // 4일 뒤(시간별 없음): 기상청 중기 값
        val d = s.days[4]
        assertEquals(-7, d.min); assertEquals(2, d.max); assertEquals(71, d.code); assertEquals(70, d.pop)
        assertTrue(s.summary().startsWith("한파주의보 발효"))
    }
}
