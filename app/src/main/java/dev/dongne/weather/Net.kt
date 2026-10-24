package dev.dongne.weather

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * HTTP GET + 응답 원문 파일 캐시.
 * - 출처마다 신선도(ttl)가 다르다: 기상청 동네 코드는 한 달, 예보는 20~30분, 대기질은 1시간
 * - 신선하면 네트워크를 쓰지 않고, 실패하면 maxStale 안의 지난 응답으로 버틴다(stale-if-error)
 * - 원문을 캐시하고 파싱은 매번 한다(파싱 ~1ms). 파서·출처를 바꿔도 캐시 형식은 그대로
 * - 큰 HTML(기상청 ~200KB)이 있어 SharedPreferences 가 아니라 cacheDir 파일에 둔다
 * - 같은 URL 을 동시에 부르면(위젯·앱·아침 안내, 예보·대기질 출처) 하나만 받고 나머지는 그 결과를 쓴다
 * - 좌표가 바뀔 때마다 파일이 쌓이므로 하루 한 번 [KEEP] 지난 파일을 지운다
 */
class Net(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    class Res(val body: String, val fetchedAt: Long, val stale: Boolean)

    private fun file(url: String) = File(dir, MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        .joinToString("") { "%02x".format(it) })

    /** force 면 신선해도 다시 받는다(실패하면 여전히 지난 응답) */
    fun get(url: String, ttl: Long, maxStale: Long = 6 * HOUR, force: Boolean = false,
            headers: Map<String, String> = emptyMap(), timeout: Int = 8000): Res {
        val f = file(url)
        val asked = now()
        cleanup()
        return synchronized(locks.computeIfAbsent(f.name) { Any() }) { load(f, url, ttl, maxStale, force, headers, timeout, asked) }
    }

    private fun load(f: File, url: String, ttl: Long, maxStale: Long, force: Boolean,
                     headers: Map<String, String>, timeout: Int, asked: Long): Res {
        val age = if (f.exists()) now() - f.lastModified() else Long.MAX_VALUE
        if (!force && age < ttl) return Res(f.readText(), f.lastModified(), false)
        // 기다리는 동안 다른 호출이 방금 받아 왔으면 force 여도 그것을 쓴다
        if (force && f.exists() && f.lastModified() >= asked - 1000) return Res(f.readText(), f.lastModified(), false)
        return try {
            val body = fetch(url, headers, timeout)
            dir.mkdirs()
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(body)
            tmp.renameTo(f) // 동시에 읽는 쪽이 반쯤 쓴 파일을 보지 않도록
            Res(body, f.lastModified(), false)
        } catch (e: Exception) {
            if (age < maxStale) Res(f.readText(), f.lastModified(), true) else throw e
        }
    }

    private fun fetch(url: String, headers: Map<String, String>, timeout: Int): String {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = timeout; con.readTimeout = timeout
            headers.forEach(con::setRequestProperty)
            if (con.responseCode !in 200..299) throw java.io.IOException("HTTP ${con.responseCode} $url")
            return con.inputStream.bufferedReader().use { it.readText() }
        } finally { con.disconnect() }
    }

    /** 마지막 정리 후 하루가 지났으면 KEEP 보다 오래된 캐시 파일을 지운다 */
    private fun cleanup() {
        val mark = File(dir, ".cleaned")
        if (mark.exists() && now() - mark.lastModified() < DAY) return
        dir.mkdirs()
        dir.listFiles()?.forEach { if (it.name != mark.name && now() - it.lastModified() > KEEP) it.delete() }
        mark.writeText(""); mark.setLastModified(now())
    }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        /** 지나간 위치의 응답이 쌓이지 않게. 지금 위치의 동 코드도 길어야 주 1회 다시 받는다(수백 바이트) */
        const val KEEP = 7 * 24 * 60 * 60_000L
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
        const val DAY = 24 * HOUR
    }
}
