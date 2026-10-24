package dev.dongne.weather

import kotlin.math.roundToInt

/**
 * 오늘(밤 10시 이후면 내일) 옷차림.
 * - 아침·저녁 출퇴근 체감(7~9시·18~21시 최저)로 겉옷·하의를, 한낮(12~16시) 최고로 상의를 정한다
 * - 체감: 10℃ 이하 바람 → 기상청 겨울 체감(JAG/TI), 25℃ 이상 5~9월 → 기상청 여름 체감(Stull)
 * - 계절 적응: 같은 기온도 가을엔 춥게, 봄엔 덜 춥게 느낀다(적응형 열쾌적, de Dear & Brager 1998)
 *   → 가을 -1℃, 봄 +1℃ 보정
 * - 기온 구간은 국내 통용 기온별 옷차림표를 대전(분지·큰 일교차·습한 여름) 기준으로 다듬은 것
 */
data class Outfit(
    val day: String, val low: Int, val high: Int,
    val outer: String, val outerShort: String?, val top: String, val topShort: String,
    val bottom: String, val bottomShort: String, val items: List<String>, val notes: List<String>,
    val rain: String?, val timeline: List<Hour> = emptyList(),
) {
    /** "07시 15° 비 60% | 10시 17° 흐림 | ..." 3시간 간격 */
    val hourly: String get() = timeline.filterIndexed { i, _ -> i % 3 == 0 }.joinToString(" | ") {
        "%02d시 %d° %s%s".format(it.hour, it.temp, Wx.text(it.code), if (it.pop >= 20) " ${it.pop}%" else "")
    }

    /** 위젯 한 줄용: "숏패딩·니트·기모바지" */
    val headline: String get() = listOfNotNull(outerShort, topShort, bottomShort).joinToString("·")
}

object Outfits {
    private fun isSnow(c: Int) = c in 71..77 || c == 85 || c == 86
    private fun isWet(c: Int) = c in 51..67 || c in 80..82 || c >= 95 || isSnow(c)
    private fun isHeavy(c: Int) = c == 65 || c == 82 || c >= 95 || c == 75

    fun feel(h: Hour, month: Int): Double = when {
        h.temp <= 10 -> Life.windChill(h.temp.toDouble(), h.wind) ?: h.temp.toDouble()
        h.temp >= 25 && month in 5..9 -> Life.summerFeel(h.temp.toDouble(), h.rh.toDouble())
        else -> h.temp.toDouble()
    }

    fun plan(s: Snapshot, month: Int, nowHour: Int): Outfit {
        val cur = Hour("지금", nowHour, s.temp, s.code, if (isWet(s.code)) 100 else 0, s.isDay, s.humidity, s.wind)
        val seq = listOf(cur) + s.hours.take(24)
        val from = seq.indexOfFirst { it.hour in 7..21 }.coerceAtLeast(0)
        val block = seq.drop(from).takeWhile { it.hour in 7..21 }.ifEmpty { seq.take(12) }
        val day = if (nowHour >= 22) "내일" else "오늘"

        val commute = block.filter { it.hour in 7..9 || it.hour in 18..21 }.ifEmpty { block }
        val midday = block.filter { it.hour in 12..16 }.ifEmpty { block }
        val rawLow = commute.minOf { feel(it, month) }
        val rawHigh = midday.maxOf { if (it.temp >= 25 && month in 5..9) feel(it, month) else it.temp.toDouble() }
        val adj = when (month) { in 9..11 -> -1; in 3..5 -> 1; else -> 0 }
        val low = rawLow.roundToInt(); val high = rawHigh.roundToInt()
        val L = low + adj; val H = high + adj
        val range = H - L
        val wind = block.maxOf { it.wind }
        val wet = block.filter { isWet(it.code) || it.pop >= 40 }
        val snow = wet.any { isSnow(it.code) }
        val heavy = wet.any { isHeavy(it.code) }
        val popMax = block.maxOf { it.pop }

        // 상의 — 한낮 기준
        val (top, topS) = when {
            H >= 27 -> "반팔 (린넨·기능성처럼 통풍 잘 되는 소재)" to "반팔"
            H >= 23 -> "반팔" to "반팔"
            H >= 20 -> "얇은 긴팔 또는 반팔" to "얇은긴팔"
            H >= 16 -> "긴팔 티·셔츠" to "긴팔"
            H >= 11 -> "맨투맨·얇은 니트" to "맨투맨"
            H >= 5 -> "두꺼운 니트·후드" to "니트"
            else -> "히트텍 + 두꺼운 니트" to "히트텍+니트"
        }

        // 겉옷 — 출퇴근 체감 기준, 길이까지
        val longNeeded = wet.isNotEmpty() || wind >= 5
        val (outer, outerS) = when {
            L >= 23 -> (if (month in 6..8 && H >= 28) "없음 (냉방 센 실내용 얇은 가디건 선택)" else "없음") to null
            L >= 20 -> (if (range >= 8) "얇은 가디건 (아침저녁용, 낮엔 벗기)" else "없음") to (if (range >= 8) "가디건" else null)
            L >= 17 -> "가디건 또는 얇은 셔츠 자켓" to "가디건"
            L >= 13 -> (if (wet.isNotEmpty()) "방수 바람막이 (짧은 기장)" else "자켓: 청자켓·바람막이·블레이저 (짧은 기장)") to (if (wet.isNotEmpty()) "바람막이" else "자켓")
            L >= 10 -> (if (longNeeded) "트렌치코트 (긴 기장, 비·바람 막기)" else "트렌치코트·야상·두꺼운 자켓 (짧은 기장도 OK)") to (if (longNeeded) "트렌치" else "두꺼운자켓")
            L >= 6 -> (if (longNeeded) "롱코트 (긴 기장)" else "코트 또는 경량패딩 (하프·짧은 기장 OK)") to (if (longNeeded) "롱코트" else "코트")
            L >= 1 -> (if (longNeeded) "두꺼운 롱코트 또는 패딩" else "두꺼운 코트 또는 숏패딩 (짧은 기장)") to (if (longNeeded) "롱코트" else "숏패딩")
            L >= -4 -> (if (longNeeded || L <= -3) "패딩 (긴 기장, 바람·눈)" else "숏패딩") to (if (longNeeded || L <= -3) "롱패딩" else "숏패딩")
            else -> "롱패딩 (긴 기장)" to "롱패딩"
        }

        // 하의
        val (bottom, bottomS) = when {
            H >= 27 && L >= 20 -> "반바지 OK (린넨·얇은 면)" to "반바지"
            H >= 23 && L >= 17 -> "얇은 면바지·슬랙스 (반바지도 OK)" to "면바지"
            L >= 12 -> "청바지·면바지" to "청바지"
            L >= 8 -> "두꺼운 청바지·코듀로이 (기모 불필요)" to "두꺼운바지"
            L >= 5 -> "두꺼운 바지 · 추위 타면 얇은 기모" to "두꺼운바지"
            L >= -4 -> "기모 바지" to "기모바지"
            else -> "기모 바지 + 히트텍 하의(내복)" to "기모+내복"
        }

        // 챙길 것
        val items = mutableListOf<String>()
        var rainLine: String? = null
        if (wet.isNotEmpty()) {
            // 연속 구간으로 묶기: "07~09시, 15시"
            val spans = mutableListOf<IntRange>()
            block.forEach { h -> if (h in wet) {
                val last = spans.lastOrNull()
                if (last != null && last.last + 1 == h.hour) spans[spans.size - 1] = last.first..h.hour else spans += h.hour..h.hour
            } }
            val when_ = spans.joinToString(", ") { if (it.first == it.last) "%02d시".format(it.first) else "%02d~%02d시".format(it.first, it.last) }
            rainLine = "$when_ ${if (snow) "눈" else "비"} (최대 ${popMax}%)"
            items += when {
                snow -> "우산 + 미끄럼 덜한 신발"
                heavy -> "장우산 + 방수 신발"
                popMax >= 60 -> "우산"
                else -> "접이식 우산 (가능성 ${popMax}%)"
            }
        }
        if (L <= 2) items += "목도리"
        if (L <= 0) items += "장갑"
        if (L <= -5) items += "핫팩"
        if (low <= -10) items += "귀마개·모자 (체감 ${low}°, 동상 주의)"
        if (s.uv >= 6 && H >= 20) items += "선크림 SPF30+"
        if (month in 5..9 && high >= 31) items += "양산·모자·물"

        // 대전·계절 메모
        val notes = mutableListOf<String>()
        if (range >= 10) notes += "일교차 ${range}°: 낮에 벗을 수 있게 겹쳐 입기"
        when (month) {
            3, 4 -> if (L <= 5) notes += "꽃샘추위: 분지라 아침이 특히 쌀쌀"
            6, 7 -> if (block.any { it.rh >= 80 } && H >= 25) notes += "습도 높음: 빨리 마르는 기능성 소재"
            8 -> if (high >= 33) notes += "분지 열섬: 12~17시 외출 줄이기"
            10, 11 -> if (range >= 10) notes += "저녁 기온 급하강: 겉옷 필수"
            12, 1, 2 -> if (wind >= 3 && L <= 0) notes += "바람 강함: 체감 ${low}°"
        }
        if (wet.isNotEmpty() && !snow && L >= 10) notes += "밑단 안 끌리는 기장"

        return Outfit(day, low, high, outer, outerS, top, topS, bottom, bottomS, items, notes, rainLine, block)
    }
}
