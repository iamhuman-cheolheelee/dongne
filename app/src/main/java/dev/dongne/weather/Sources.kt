package dev.dongne.weather

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 날씨 데이터 출처.
 *
 * 전 세계 기본 예보(Open-Meteo) 위에, 좌표를 덮는 지역 출처(기상청 등)가 시간별·일별 값을 덮어쓴다.
 * 새 출처는 [LocalSource] 나 [AirSource] 를 구현해 [Sources.local]·[Sources.air] 목록에 넣으면 된다.
 * 목록 앞에서부터 covers 하는 출처를 시도하고, 실패하면 다음 출처로 넘어간다.
 * 캐시·재시도·지난 응답 사용은 [Net] 이 맡으므로 출처는 URL 과 파싱만 안다.
 */

/** 요청 좌표. 소수 둘째 자리(~1km)로 반올림 — 캐시가 잘 맞고, 정확한 위치는 기기 밖으로 나가지 않는다 */
data class Coord(val lat: Double, val lon: Double) {
    val key get() = "%.2f,%.2f".format(lat, lon)

    companion object {
        fun of(lat: Double, lon: Double) = Coord((lat * 100).roundToInt() / 100.0, (lon * 100).roundToInt() / 100.0)
    }
}

/** 지역 출처의 한 시간. code 는 WMO 날씨 코드(-1 = 모름) */
data class LocalHour(val date: String, val hour: Int, val code: Int, val temp: Int, val feels: Int,
                     val pop: Int, val rh: Int, val wind: Double)
/** code·pop: 오전/오후 날씨 중 나쁜 쪽·높은 쪽(-1 = 모름). 중기(4~10일)는 이것만 있다 */
data class LocalDay(val date: String, val min: Int?, val max: Int?, val code: Int = -1, val pop: Int = -1)

/** 지역 출처의 현재 관측(실측). 특보는 이 좌표에 지금 발효 중인 것 */
data class LocalNow(val temp: Double, val feels: Double?, val rh: Int?, val wind: Double?, val rain1h: Double?,
                    val warnings: List<String>, val pm10: Int?, val pm25: Int?, val station: String?)
data class LocalForecast(val source: String, val hours: List<LocalHour>, val days: List<LocalDay>, val now: LocalNow? = null)

data class Air(val pm10: Int, val pm25: Int, val source: String)

interface LocalSource {
    val name: String
    fun covers(p: Coord): Boolean
    fun fetch(net: Net, p: Coord, force: Boolean): LocalForecast?
}

interface AirSource {
    val name: String
    fun covers(p: Coord): Boolean = true
    fun fetch(net: Net, p: Coord, force: Boolean): Air?
}

object Sources {
    val local: List<LocalSource> = listOf(Kma)
    // 국내는 측정소 실측(에어코리아) 먼저, 실패·해외는 CAMS 모델
    val air: List<AirSource> = listOf(KmaAir, OpenMeteoAir)

    fun <S, T> first(list: List<S>, covers: (S) -> Boolean, fetch: (S) -> T?): T? =
        list.asSequence().filter(covers).mapNotNull { runCatching { fetch(it) }.getOrNull() }.firstOrNull()
}

/** 전 세계 기본 예보. 모델 갱신이 1시간 단위라 30분 캐시 */
object OpenMeteo {
    const val NAME = "Open-Meteo"
    private const val TTL = 30 * Net.MIN

    fun forecast(net: Net, p: Coord, force: Boolean): Net.Res = net.get(
        "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day" +
            "&hourly=temperature_2m,apparent_temperature,precipitation_probability,weather_code,is_day,relative_humidity_2m,wind_speed_10m" +
            "&past_hours=1&forecast_hours=26" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max,precipitation_probability_max,uv_index_max,sunrise,sunset" +
            "&timezone=auto&past_days=1&forecast_days=10&wind_speed_unit=ms",
        ttl = TTL, force = force)
}

/** 대기질: Copernicus CAMS 모델(측정소 실측 아님). 1시간 캐시 */
object OpenMeteoAir : AirSource {
    override val name = "CAMS 모델"

    override fun fetch(net: Net, p: Coord, force: Boolean): Air? {
        val cur = JSONObject(net.get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${p.lat}&longitude=${p.lon}" +
            "&current=pm10,pm2_5&timezone=auto", ttl = Net.HOUR, force = force).body).getJSONObject("current")
        if (cur.isNull("pm10") || cur.isNull("pm2_5")) return null
        return Air(cur.getDouble("pm10").roundToInt(), cur.getDouble("pm2_5").roundToInt(), name)
    }
}
