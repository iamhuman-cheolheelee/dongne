package dev.dongne.weather

/** 앞으로 12시간 안의 "다음 변화"(비 시작·그침)와 시간별 줄 */
object Next {
    private fun snow(h: Hour) = h.code in 71..77 || h.code == 85 || h.code == 86
    fun wet(h: Hour) = h.code in 51..67 || h.code in 80..82 || h.code >= 95 || snow(h) || h.pop >= 50

    data class Span(val start: Hour, val end: Hour?, val popMax: Int, val snow: Boolean) {
        val what get() = if (snow) "눈" else "비"
        /** "08~11시 비" / "08시부터 비" */
        val range get() = if (end == null) "%02d시부터 %s".format(start.hour, what)
            else if (end.hour == start.hour) "%02d시 %s".format(start.hour, what)
            else "%02d~%02d시 %s".format(start.hour, end.hour, what)
    }

    /** 지금 이후 처음 시작하는 비·눈 구간(지금 오는 중이면 null) */
    fun upcoming(s: Snapshot, within: Int = 12): Span? {
        val hs = s.hours.take(within + 1)
        if (hs.isEmpty() || wet(hs[0])) return null
        val i = hs.indexOfFirst(::wet).takeIf { it > 0 } ?: return null
        val run = hs.drop(i).takeWhile(::wet)
        return Span(run.first(), run.last().takeIf { i + run.size < hs.size }, run.maxOf { it.pop }, run.any(::snow))
    }

    /** 결론 한 줄: "지금 비, 11시 그침" / "15시 비 시작 (60%)" / "12시간 비 소식 없음" */
    fun change(s: Snapshot): String {
        val hs = s.hours.take(13)
        if (hs.isEmpty()) return ""
        if (wet(hs[0])) {
            val w = if (hs.takeWhile(::wet).any(::snow)) "눈" else "비"
            val stop = hs.indexOfFirst { !wet(it) }
            return if (stop < 0) "지금 $w, 12시간 계속" else "지금 $w, %02d시 그침".format(hs[stop].hour)
        }
        val u = upcoming(s) ?: return "12시간 비 소식 없음"
        return "%02d시 %s 시작 (%d%%)".format(u.start.hour, u.what, u.popMax)
    }

    /** "08시 14° 약한 비 40%" — step 시간 간격 */
    fun lines(s: Snapshot, count: Int = 6, step: Int = 2): List<String> =
        s.hours.filterIndexed { i, _ -> i % step == 0 }.take(count).map {
            "%02d시 %d° %s%s".format(it.hour, it.temp, Wx.text(it.code), if (it.pop >= 20) " ${it.pop}%" else "")
        }
}
