package dev.dongne.weather

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * 기상청 동네예보(날씨누리 digital-forecast, 5km 격자·예보관 보정·1시간 간격).
 * 국내는 시간별(0~3일)·일별(~10일, 중기 포함)·현재 관측·특보를 이것으로 덮고, 실패·해외는 Open-Meteo 로 떨어진다.
 * 공개 API 가 아니라 웹 페이지를 읽으므로, 형식이 바뀌어 거의 못 읽으면 null 을 돌려 다음 출처로 넘긴다.
 */
object Kma : LocalSource {
    override val name = "기상청 동네예보"
    private const val UA = "Mozilla/5.0 (Linux; Android 16) WeatherGlance"
    private val HEADERS = mapOf("User-Agent" to UA)

    override fun covers(p: Coord) = p.lat in 33.0..38.7 && p.lon in 124.5..131.0

    /** 기상청 LCC 격자 변환 (단기예보 API 명세) */
    fun grid(lat: Double, lon: Double): Pair<Int, Int> {
        val re = 6371.00877 / 5.0; val d = PI / 180
        val s1 = 30 * d; val s2 = 60 * d; val ol = 126 * d; val oa = 38 * d
        val sn = ln(cos(s1) / cos(s2)) / ln(tan(PI / 4 + s2 / 2) / tan(PI / 4 + s1 / 2))
        val sf = tan(PI / 4 + s1 / 2).pow(sn) * cos(s1) / sn
        val ro = re * sf / tan(PI / 4 + oa / 2).pow(sn)
        val ra = re * sf / tan(PI / 4 + lat * d / 2).pow(sn)
        var th = lon * d - ol
        if (th > PI) th -= 2 * PI; if (th < -PI) th += 2 * PI
        th *= sn
        return (ra * sin(th) + 43 + 0.5).toInt() to (ro - ra * cos(th) + 136 + 0.5).toInt()
    }

    private val ITEM = Regex("""data-date="(\d{4}-\d\d-\d\d)"\s*data-time="(\d\d):00">(.*?)</ul>""", RegexOption.DOT_MATCHES_ALL)
    private val TITLE = Regex("""class="wic [^"]*" title="([^"]+)"""")
    // <span class="hid feel">15℃<span class="chill">(15℃)</span>  — 앞이 기온, 괄호가 체감
    private val TEMP = Regex("""기온\(체감온도\) </span><span class="hid feel">(-?\d+)℃""")
    private val FEEL = Regex("""체감온도: </span><span>(-?\d+)℃""")
    private val POP = Regex("""강수확률: </span><span>(\d+)%""")
    private val RH = Regex("""습도: </span><span>(\d+)%""")
    private val WSPD = Regex("""<span class="wspd">([\d.]+)<""")
    private val DAY = Regex("""class="daily" data-date="(\d{4}-\d\d-\d\d)".*?class="tminmax">(.*?)</span></span></span>""", RegexOption.DOT_MATCHES_ALL)
    private val MIN = Regex("""minval">(-?\d+)℃"""); private val MAX = Regex("""maxval">(-?\d+)℃""")
    private val CODE = Regex(""""code":"(\d+)"""")

    /** 격자 → 동 코드는 바뀌지 않는다: 한 달 캐시 */
    private fun dong(net: Net, p: Coord): String? {
        val (x, y) = grid(p.lat, p.lon)
        val zone = net.get("https://www.weather.go.kr/w/rest/zone/find/dong.do?x=$x&y=$y&lat=${p.lat}&lon=${p.lon}&lang=kor",
            ttl = 30 * Net.DAY, maxStale = 365 * Net.DAY, headers = HEADERS).body
        return CODE.find(zone)?.groupValues?.get(1)
    }

    override fun fetch(net: Net, p: Coord, force: Boolean): LocalForecast? {
        val code = dong(net, p) ?: return null
        // 초단기예보가 매시 반영된다: 20분 캐시
        val html = net.get("https://www.weather.go.kr/w/wnuri-fct2021/main/digital-forecast.do?code=$code&unit=m%2Fs&hr1=Y",
            ttl = 20 * Net.MIN, force = force, headers = HEADERS, timeout = 10_000).body
        return parse(html)?.copy(now = runCatching { now(net, p, force) }.getOrNull())
    }

    /** 현재 관측·특보·측정소 대기질. 관측은 매시 정각 갱신: 10분 캐시. 대기질 출처(KmaAir)와 같은 URL 이라 Net 이 한 번만 받는다 */
    fun now(net: Net, p: Coord, force: Boolean): LocalNow? {
        val code = dong(net, p) ?: return null
        return parseNow(net.get("https://www.weather.go.kr/w/wnuri-fct2021/main/current-weather.do?code=$code&unit=m%2Fs&aws=N",
            ttl = 10 * Net.MIN, maxStale = 2 * Net.HOUR, force = force, headers = HEADERS).body)
    }

    private val N_TMP = Regex("""class="tmp">(-?[\d.]+)<small>""")
    private val N_CHILL = Regex("""class="chill">체감\((-?[\d.]+)℃""")
    private val N_RH = Regex("""ic-hm">습도.*?class="val">(\d+)""", RegexOption.DOT_MATCHES_ALL)
    private val N_WIND = Regex("""ic-wind">바람.*?class="val">[^\d<-]*([\d.]+)""", RegexOption.DOT_MATCHES_ALL)
    private val N_RN = Regex("""ic-rn">1시간강수량</span><span class="val">([\d.]+)""")
    private val N_WARN = Regex("""data-impact-fct="">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    private val WARN = Regex("""(폭염|한파|호우|대설|강풍|태풍|건조|풍랑|황사|폭풍해일|지진해일)\s*(주의보|경보)""")
    private val N_PM25 = Regex("""\(PM2\.5\)</small>.*?class="air-lvv">(\d+)<""", RegexOption.DOT_MATCHES_ALL)
    private val N_PM10 = Regex("""\(PM10\)</small>.*?class="air-lvv">(\d+)<""", RegexOption.DOT_MATCHES_ALL)
    private val N_STN = Regex("""class="right">\s*([^<]*측정소)""")

    fun parseNow(html: String): LocalNow? {
        val t = N_TMP.find(html)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        fun d(r: Regex) = r.find(html)?.groupValues?.get(1)?.toDoubleOrNull()
        fun i(r: Regex) = r.find(html)?.groupValues?.get(1)?.toIntOrNull()
        val warnText = N_WARN.find(html)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), " ") ?: ""
        val warnings = WARN.findAll(warnText).map { it.groupValues[1] + it.groupValues[2] }.distinct().toList()
        return LocalNow(t, d(N_CHILL), i(N_RH), d(N_WIND), d(N_RN), warnings, i(N_PM10), i(N_PM25),
            N_STN.find(html)?.groupValues?.get(1)?.trim())
    }

    // 일별 슬라이드(단기+중기 ~10일): 오전·오후(8일 뒤부터는 종일) 날씨, 최저·최고, 강수확률
    private val SLIDE = Regex("""class="dfs-daily-slide"\s.*?data-date="(\d{4}-\d\d-\d\d)"(.*?)(?=class="dfs-daily-slide"\s|$)""", RegexOption.DOT_MATCHES_ALL)
    private val S_WX = Regex("""title="(?:오[전후] )?날씨 ([^"]+)"""")
    private val S_MIN = Regex("""최저 : </strong><span>(-?\d+)℃"""); private val S_MAX = Regex("""최고 : </strong><span>(-?\d+)℃""")
    private val S_POP = Regex("""강수확률</strong><span>(\d+)%""")

    fun parseDaily(html: String): List<LocalDay> = SLIDE.findAll(html).map { m ->
        val b = m.groupValues[2].take(1500) // 마지막 슬라이드는 페이지 끝까지 잡히므로 슬라이드 한 장(~1KB) 만큼만
        LocalDay(m.groupValues[1], S_MIN.find(b)?.groupValues?.get(1)?.toInt(), S_MAX.find(b)?.groupValues?.get(1)?.toInt(),
            S_WX.findAll(b).map { code(it.groupValues[1]) }.maxOrNull() ?: -1,
            S_POP.findAll(b).map { it.groupValues[1].toInt() }.maxOrNull() ?: -1)
    }.distinctBy { it.date }.toList()

    fun parse(html: String): LocalForecast? {
        val hours = ITEM.findAll(html).mapNotNull { m ->
            val b = m.groupValues[3]
            val t = TEMP.find(b)?.groupValues?.get(1)?.toInt() ?: return@mapNotNull null
            LocalHour(m.groupValues[1], m.groupValues[2].toInt(), code(TITLE.find(b)?.groupValues?.get(1) ?: ""), t,
                FEEL.find(b)?.groupValues?.get(1)?.toInt() ?: t,
                POP.find(b)?.groupValues?.get(1)?.toInt() ?: 0, RH.find(b)?.groupValues?.get(1)?.toInt() ?: 50,
                WSPD.find(b)?.groupValues?.get(1)?.toDouble() ?: 1.0)
        }.distinctBy { it.date to it.hour }.toList()
        val days = DAY.findAll(html).map { m ->
            LocalDay(m.groupValues[1], MIN.find(m.groupValues[2])?.groupValues?.get(1)?.toInt(), MAX.find(m.groupValues[2])?.groupValues?.get(1)?.toInt())
        }.distinctBy { it.date }.toList()
            .let { short -> // 단기 최저·최고가 있으면 그것, 없으면(중기) 슬라이드 값. 날씨·강수확률은 슬라이드
                val slides = parseDaily(html).associateBy { it.date }
                (short.map { it.date } + slides.keys).distinct().map { date ->
                    val a = short.firstOrNull { it.date == date }; val sl = slides[date]
                    LocalDay(date, a?.min ?: sl?.min, a?.max ?: sl?.max, sl?.code ?: -1, sl?.pop ?: -1)
                }
            }
        // 형식이 바뀌어 거의 못 읽었으면 쓰지 않는다
        return if (hours.size >= 6) LocalForecast(name, hours, days) else null
    }

    /** 기상청 날씨 문구 → WMO 코드 */
    fun code(text: String): Int = when {
        "뇌우" in text || "천둥" in text -> 95
        "소나기" in text -> 80
        "강한비" in text -> 65
        "약한비" in text || "빗방울" in text && "눈" !in text -> 61
        "비" in text && "눈" in text -> 67
        "강한눈" in text -> 75
        "약한눈" in text || "눈날림" in text -> 71
        "눈" in text -> 73
        "비" in text -> 63
        "흐림" in text -> 3
        "구름많" in text -> 2
        "구름조금" in text -> 1
        "맑" in text -> 0
        else -> -1
    }
}

/** 에어코리아 측정소 실측(날씨누리 현재 날씨에 실린 값). 국내 전용 */
object KmaAir : AirSource {
    override val name = "에어코리아 측정소"
    override fun covers(p: Coord) = Kma.covers(p)
    override fun fetch(net: Net, p: Coord, force: Boolean): Air? {
        val n = Kma.now(net, p, force) ?: return null
        return Air(n.pm10 ?: return null, n.pm25 ?: return null, n.station?.let { "에어코리아 $it" } ?: name)
    }
}
