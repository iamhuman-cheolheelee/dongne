package dev.dongne.weather

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 실제 날씨누리·Open-Meteo 를 불러 파서가 아직 맞는지 본다(페이지 형식 변경 감지).
 * 네트워크가 필요해 평소엔 건너뛴다: LIVE=1 ./gradlew testDebugUnitTest --tests '*LiveTest'
 */
class LiveTest {
    private val net = Net(Files.createTempDirectory("live").toFile())
    private val cityHall = Coord.of(36.3504, 127.3845) // 대전시청

    @Test fun kmaPagesStillParse() {
        assumeTrue(System.getenv("LIVE") == "1")
        val f = Kma.fetch(net, cityHall, force = true)
        assertNotNull("단기예보 파싱 실패", f)
        assertTrue("시간별 ${f!!.hours.size}개", f.hours.size >= 24)
        assertTrue("일별 ${f.days.size}개(중기 포함 8일 이상이어야)", f.days.size >= 8)
        assertTrue("중기 날씨 코드 없음", f.days.drop(4).all { it.code >= 0 && it.min != null })
        val n = assertNotNullAndGet(f.now, "현재 관측 파싱 실패")
        assertTrue("기온 ${n.temp}", n.temp in -40.0..50.0)
        assertNotNull("측정소 대기질 없음", n.pm10)
    }

    @Test fun openMeteoAnswers() {
        assumeTrue(System.getenv("LIVE") == "1")
        assertNotNull(OpenMeteoAir.fetch(net, cityHall, force = true))
    }

    private fun <T> assertNotNullAndGet(v: T?, msg: String): T { assertNotNull(msg, v); return v!! }
}
