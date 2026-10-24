package dev.dongne.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 출처별 신선도·지난 응답 사용. 네트워크 없이: 캐시 파일을 직접 만들고, 실패할 URL 로 요청한다 */
class NetTest {
    private val dir: File = Files.createTempDirectory("net").toFile()
    private val bad = "http://127.0.0.1:9/never" // discard 포트 — 연결이 즉시 실패한다

    private fun seed(net: Net, url: String, body: String, ageMs: Long) {
        // 첫 요청은 실패하므로 파일 이름을 얻으려고 같은 해시 규칙으로 직접 쓴다
        val name = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        File(dir, name).apply { writeText(body); setLastModified(System.currentTimeMillis() - ageMs) }
    }

    @Test fun freshCacheSkipsNetwork() {
        val net = Net(dir); seed(net, bad, "cached", 5 * Net.MIN)
        val r = net.get(bad, ttl = 20 * Net.MIN)
        assertEquals("cached", r.body); assertFalse(r.stale)
    }

    @Test fun staleIfErrorWithinMaxStale() {
        val net = Net(dir); seed(net, bad, "old", 2 * Net.HOUR)
        val r = net.get(bad, ttl = 20 * Net.MIN, maxStale = 6 * Net.HOUR, timeout = 500)
        assertEquals("old", r.body); assertTrue(r.stale)
    }

    @Test fun forceStillFallsBackWhenOffline() {
        val net = Net(dir); seed(net, bad, "old", 1 * Net.MIN)
        assertTrue(net.get(bad, ttl = Net.HOUR, force = true, timeout = 500).stale)
    }

    @Test(expected = Exception::class) fun tooOldThrows() {
        val net = Net(dir); seed(net, bad, "ancient", 7 * Net.HOUR)
        net.get(bad, ttl = 20 * Net.MIN, maxStale = 6 * Net.HOUR, timeout = 500)
    }

    @Test fun forceReusesFetchFinishedWhileWaiting() {
        // 다른 호출이 방금(요청 이후) 받아 둔 파일이면 force 여도 다시 받지 않는다
        val net = Net(dir); seed(net, bad, "just now", 0)
        val r = net.get(bad, ttl = Net.HOUR, force = true, timeout = 500)
        assertEquals("just now", r.body); assertFalse(r.stale)
    }

    @Test fun cleanupDropsOldFiles() {
        val net = Net(dir); seed(net, "http://old/", "x", 8 * Net.DAY); seed(net, bad, "cached", Net.MIN)
        net.get(bad, ttl = Net.HOUR)
        assertEquals(setOf("cached"), dir.listFiles()!!.filter { !it.name.startsWith(".") }.map { it.readText() }.toSet())
    }
}
