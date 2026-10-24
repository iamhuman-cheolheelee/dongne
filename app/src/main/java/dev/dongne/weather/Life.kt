package dev.dongne.weather

import java.time.LocalDate
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sqrt

data class Tip(val icon: String, val title: String, val body: String, val source: String)

/** 생활 정보. 공식 지수·논문 공식이 있는 건 그대로 쓰고, 경험칙은 경험칙이라고 밝힌다 */
object Life {
    /** 기상청 겨울철 체감온도(JAG/TI, Osczevski & Bluestein 2005). T≤10℃, 풍속≥1.3m/s 에서만 정의 */
    fun windChill(t: Double, wind: Double): Double? {
        val v = wind * 3.6
        if (t > 10 || v < 4.8) return null
        val p = v.pow(0.16)
        return 13.12 + 0.6215 * t - 11.37 * p + 0.3965 * t * p
    }

    /** 기상청 여름철 체감온도(2022~): Stull(2011) 습구온도 기반 */
    fun summerFeel(t: Double, rh: Double): Double {
        val tw = t * atan(0.151977 * sqrt(rh + 8.313659)) + atan(t + rh) - atan(rh - 1.67633) +
            0.00391838 * rh.pow(1.5) * atan(0.023101 * rh) - 4.686035
        return -0.2442 + 0.55399 * tw + 0.45535 * t - 0.0022 * tw * tw + 0.00278 * tw * t + 3.0
    }

    fun tips(s: Snapshot): List<Tip> {
        val out = mutableListOf<Tip>()
        val month = LocalDate.now().monthValue
        val now = s.hours.firstOrNull()

        // 출퇴근 우산
        fun popAt(r: IntRange) = s.hours.take(24).filter { it.hour in r }.maxOfOrNull { it.pop } ?: 0
        val am = popAt(7..9); val pm = popAt(17..19)
        out += Tip("", "출퇴근 우산", when {
            am >= 40 && pm >= 40 -> "우산 필요: 출근 7~9시 ${am}%, 퇴근 17~19시 ${pm}%"
            am >= 40 -> "우산 필요: 출근 7~9시 ${am}%"
            pm >= 40 -> "접이식 우산: 퇴근 17~19시 ${pm}%"
            else -> "우산 불필요: 출근 ${am}%, 퇴근 ${pm}%"
        }, "강수확률 40% 이상 = 우산 권장(기상청 예보 해설 관행)")

        // 체감온도 (기상청 공식 산식)
        if (now != null) {
            val wc = windChill(s.temp.toDouble(), s.wind)
            if (month in 10..12 || month in 1..4) {
                if (wc != null) {
                    val lv = when { wc < -45 -> "위험"; wc < -25 -> "경고"; wc < -10 -> "주의"; else -> "관심" }
                    out += Tip("", "겨울 체감온도 %.0f° (%s)".format(wc, lv),
                        if (wc < -10) "동상 위험: 모자·장갑으로 피부 가리기" else "바람 때문에 기온보다 %.0f° 더 추움".format(s.temp - wc),
                        "기상청 겨울철 체감온도 · Osczevski & Bluestein (2005) BAMS 86(10)")
                }
            } else {
                val sf = summerFeel(s.max.toDouble(), now.rh.toDouble())
                val lv = when { sf >= 38 -> "위험"; sf >= 35 -> "경고"; sf >= 33 -> "주의"; sf >= 31 -> "관심"; else -> null }
                out += Tip("", "여름 체감온도 %.0f°%s".format(sf, lv?.let { " ($it)" } ?: ""),
                    if (lv != null) "12~17시 야외활동 줄이기. 물 15~20분마다 한 컵" else "열 스트레스 낮음",
                    "기상청 여름철 체감온도 · Stull (2011) J. Appl. Meteor. Climatol. 50 / 질병관리청 온열질환 예방수칙")
            }
        }

        // 일교차
        if (s.max - s.min >= 10) out += Tip("", "일교차 ${s.max - s.min}°",
            "아침·저녁 겉옷 필요. 큰 일교차는 호흡기·심혈관 질환 위험 증가",
            "Kan et al. (2007) Epidemiology 18(1) / Lim et al. (2015) Environ Health Perspect")

        // 자외선 (WHO)
        val uv = s.uv
        if (uv >= 3) out += Tip("", "자외선 %.0f, %s".format(uv, when { uv >= 11 -> "위험"; uv >= 8 -> "매우 높음"; uv >= 6 -> "높음"; else -> "보통" }),
            if (uv >= 6) "10~15시 그늘. SPF30+ 2시간마다, 모자·선글라스" else "한낮 그늘. SPF30+",
            "WHO Global Solar UV Index: A Practical Guide (2002)")

        // 미세먼지 (환경부)
        if (s.aqKnown) out += Tip("", "미세먼지 ${s.aqText} (PM10 ${s.pm10} · PM2.5 ${s.pm25})", when (s.aqGrade) {
            0 -> "환기·야외운동 가능"
            1 -> "민감군만 장시간 격한 운동 자제"
            2 -> "장시간 실외활동 자제. 외출 시 KF80 이상"
            else -> "실외활동 금지 수준. 창문 닫기, KF94"
        }, "환경부 미세먼지 예보등급·행동요령 (값은 CAMS 모델 추정치)")

        // 빨래
        val day = s.hours.take(12).filter { it.day }
        if (day.isNotEmpty()) {
            val rh = day.map { it.rh }.average(); val w = day.map { it.wind }.average(); val rain = day.maxOf { it.pop }
            val idx = when {
                rain >= 40 -> "실내 건조 추천"
                rh < 50 && w >= 2 -> "아주 잘 마름"
                rh < 65 -> "잘 마름"
                else -> "더디게 마름"
            }
            out += Tip("", "빨래: $idx", "낮 평균 습도 %.0f%% · 바람 %.1fm/s · 강수 최대 %d%%".format(rh, w, rain),
                "증발량은 습도가 낮고 바람이 셀수록 커진다(Penman, 1948) — 단계 구분은 경험칙")
        }
        return out
    }
}
