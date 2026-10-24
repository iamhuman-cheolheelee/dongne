package dev.dongne.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

/** 대전 계절별 전형적인 날로 옷차림 규칙을 훑는다 */
class OutfitTest {
    // 최저 6시·최고 15시인 하루 곡선
    private fun day(min: Int, max: Int, code: Int = 1, pop: Int = 0, rh: Int = 60, wind: Double = 1.5, rainFrom: Int = 99): Snapshot {
        fun t(h: Int): Int {
            val x = if (h in 6..15) (h - 6) / 9.0 else if (h > 15) 1 - (h - 15) / 15.0 else 1 - (h + 9) / 15.0
            return (min + (max - min) * (1 - cos(PI * x)) / 2).toInt()
        }
        val hours = (8..31).map { k -> val h = k % 24
            val r = h >= rainFrom && k < 24
            Hour("${h}시", h, t(h), if (r) code else 1, if (r) pop else 0, h in 7..18, rh, wind) }
        return Snapshot("둔산동", t(7), t(7), 1, true, rh, wind, max, min, max, 5.0, "06:40", "17:50",
            20, 10, hours, listOf(Day("오늘", 1, max, min, pop), Day("내일", 1, max, min, 0)), 0L)
    }

    private fun show(name: String, s: Snapshot, month: Int): Outfit {
        val o = Outfits.plan(s, month, 7)
        println("[$name] ${s.min}~${s.max}° 출근체감 ${o.low}° 한낮 ${o.high}° → ${o.headline}\n   겉옷 ${o.outer} | 상의 ${o.top} | 하의 ${o.bottom}" +
            "\n   챙길 것 ${o.items} 비 ${o.rain} 메모 ${o.notes}")
        return o
    }

    @Test fun seasons() {
        show("1월 한파", day(-11, -1, wind = 3.5), 1).also { assertEquals("롱패딩", it.outerShort); assertEquals("기모+내복", it.bottomShort) }
        show("1월 평범", day(-5, 5), 1)
        show("12월 눈", day(-3, 3, code = 73, pop = 80, rainFrom = 10), 12).also { assertNotNull(it.rain) }
        show("3월 꽃샘", day(0, 13), 3)
        show("4월 봄", day(7, 20), 4)
        show("5월 초여름", day(13, 26), 5)
        show("6월 장마", day(21, 27, code = 63, pop = 80, rh = 90, rainFrom = 8), 6).also { assertEquals("반팔", it.topShort) }
        show("8월 폭염", day(26, 35, rh = 70), 8).also { assertEquals("반바지", it.bottomShort); assertEquals(null, it.outerShort) }
        show("9월 초가을", day(18, 28), 9)
        show("10월 일교차", day(8, 21), 10)
        show("10월 오늘(실데이터 근사)", day(14, 21, code = 61, pop = 60, rainFrom = 8), 10)
        show("11월 늦가을", day(2, 12, wind = 3.0), 11)
    }
}
