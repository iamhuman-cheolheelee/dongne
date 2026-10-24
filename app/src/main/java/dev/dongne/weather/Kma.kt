package dev.dongne.weather

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * 기상청 동네예보(날씨누리 digital-forecast, 5km 격자·예보관 보정·1시간 간격).
 * 국내 단기(0~3일)는 이것을 우선하고, 실패·해외는 Open-Meteo 로 떨어진다.
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

    override fun fetch(net: Net, p: Coord, force: Boolean): LocalForecast? {
        val (x, y) = grid(p.lat, p.lon)
        // 격자 → 동 코드는 바뀌지 않는다: 한 달 캐시
        val zone = net.get("https://www.weather.go.kr/w/rest/zone/find/dong.do?x=$x&y=$y&lat=${p.lat}&lon=${p.lon}&lang=kor",
            ttl = 30 * Net.DAY, maxStale = 365 * Net.DAY, headers = HEADERS).body
        val code = CODE.find(zone)?.groupValues?.get(1) ?: return null
        // 초단기예보가 매시 반영된다: 20분 캐시
        val html = net.get("https://www.weather.go.kr/w/wnuri-fct2021/main/digital-forecast.do?code=$code&unit=m%2Fs&hr1=Y",
            ttl = 20 * Net.MIN, force = force, headers = HEADERS, timeout = 10_000).body
        return parse(html)
    }

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
