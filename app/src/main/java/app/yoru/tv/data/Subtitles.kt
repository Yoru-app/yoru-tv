package app.yoru.tv.data

/** Реплика субтитров. Рисуем их сами поверх видео, чтобы управлять размером и фоном. */
data class Cue(val start: Long, val end: Long, val text: String)

object Subtitles {
    fun parse(text: String): List<Cue> {
        val t = text.removePrefix("﻿")
        val cues = if (t.trimStart().startsWith("WEBVTT")) parseVtt(t) else if (t.contains("[Events]", true) || t.contains("Dialogue:")) parseAss(t) else parseVtt(t)
        return cues.sortedBy { it.start }
    }

    fun active(cues: List<Cue>, posMs: Long): List<Cue> {
        if (cues.isEmpty()) return emptyList()
        // бинарный поиск последней реплики, начавшейся до posMs
        var lo = 0
        var hi = cues.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].start <= posMs) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (idx < 0) return emptyList()
        val out = ArrayList<Cue>(2)
        var i = idx
        // перекрывающиеся реплики: смотрим немного назад
        while (i >= 0 && i >= idx - 12) {
            val c = cues[i]
            if (posMs < c.end) out.add(0, c)
            i--
        }
        return out
    }

    private val vttTime = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})")

    private fun vttMs(s: String): Long? {
        val m = vttTime.find(s.trim()) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0
        val mi = m.groupValues[2].toLong()
        val se = m.groupValues[3].toLong()
        val frac = m.groupValues[4].padEnd(3, '0').take(3).toLong()
        return ((h * 60 + mi) * 60 + se) * 1000 + frac
    }

    private fun parseVtt(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n{2,}"))
        for (b in blocks) {
            val lines = b.split('\n')
            val ti = lines.indexOfFirst { it.contains("-->") }
            if (ti < 0) continue
            val parts = lines[ti].split("-->")
            val start = vttMs(parts[0]) ?: continue
            val end = vttMs(parts.getOrElse(1) { "" }.trim().split(' ').first()) ?: continue
            val body = lines.drop(ti + 1).joinToString("\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
                .trim()
            if (body.isNotEmpty()) out.add(Cue(start, end, body))
        }
        return out
    }

    private val assTime = Regex("(\\d+):(\\d{2}):(\\d{2})[.,](\\d{1,3})")

    private fun assMs(s: String): Long? {
        val m = assTime.find(s.trim()) ?: return null
        val frac = m.groupValues[4]
        val ms = when (frac.length) {
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return ((m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 60 + m.groupValues[3].toLong()) * 1000 + ms
    }

    private fun parseAss(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        var inEvents = false
        var fmt: List<String>? = null
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                fmt = line.substring(7).split(',').map { it.trim().lowercase() }
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val f = fmt ?: continue
            val parts = line.substring(9).split(',', limit = f.size)
            if (parts.size < f.size) continue
            val row = f.indices.associate { f[it] to parts[it] }
            val start = assMs(row["start"] ?: continue) ?: continue
            val end = assMs(row["end"] ?: continue) ?: continue
            var t = row["text"] ?: continue
            if (Regex("\\\\p[1-9]").containsMatchIn(t)) continue // векторная графика
            t = t.replace(Regex("\\{[^}]*\\}"), "")
                .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                .trim()
            if (t.isNotEmpty()) out.add(Cue(start, end, t))
        }
        // одинаковые реплики из разных стилей (тени, обводки) склеиваем
        return out.distinctBy { Triple(it.start, it.end, it.text) }
    }
}
