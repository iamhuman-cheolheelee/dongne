package dev.dongne.weather

import org.junit.Assert.assertEquals
import org.junit.Test

class NextTest {
    private fun snap(pops: List<Int>, codes: List<Int> = pops.map { if (it >= 50) 61 else 3 }) = Snapshot(
        "둔산동", 15, 15, codes[0], true, 70, 2.0, 21, 14, 20, 3.0, "06:40", "17:40", 30, 15,
        pops.indices.map { Hour("${(7 + it) % 24}시", (7 + it) % 24, 15, codes[it], pops[it], true) }, emptyList(), 0L)

    @Test fun dry() = assertEquals("12시간 비 소식 없음", Next.change(snap(List(13) { 10 })))
    @Test fun starts() = assertEquals("09시 비 시작 (70%)", Next.change(snap(listOf(10, 30, 70, 60, 20) + List(8) { 0 })))
    @Test fun stops() = assertEquals("지금 비, 09시 그침", Next.change(snap(listOf(80, 60, 20) + List(10) { 0 })))
    @Test fun span() = assertEquals("09~10시 비", Next.upcoming(snap(listOf(10, 30, 70, 60, 20) + List(8) { 0 }))!!.range)
    @Test fun snow() = assertEquals("08시 눈 시작 (40%)",
        Next.change(snap(listOf(10, 40, 10) + List(10) { 0 }, listOf(3, 73, 3) + List(10) { 3 })))

    // ---- 갱신 주기·상태바 아이콘 ----

    @Test fun refreshFasterBeforeRain() {
        val wet = snap(listOf(10, 70) + List(11) { 0 }); val dry = snap(List(13) { 10 })
        assertEquals(15 * Net.MIN, GlanceWidget.interval(wet, 14, false))
        assertEquals(30 * Net.MIN, GlanceWidget.interval(dry, 14, false))
        assertEquals(2 * Net.HOUR, GlanceWidget.interval(dry, 3, false))
        assertEquals(2 * Net.HOUR, GlanceWidget.interval(wet, 14, true))
    }

    @Test fun warningPicksIcon() {
        val dry = snap(List(13) { 10 })
        assertEquals(R.drawable.sb_cloud, StatusIcon.glyph(dry, 14))
        assertEquals(R.drawable.sb_emergency_heat, StatusIcon.glyph(dry.copy(warnings = listOf("폭염주의보")), 14))
        assertEquals(R.drawable.sb_air, StatusIcon.glyph(dry.copy(warnings = listOf("강풍주의보")), 14))
    }
}
