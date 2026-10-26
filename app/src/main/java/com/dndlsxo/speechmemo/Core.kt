package com.dndlsxo.speechmemo

import android.content.Context
import java.io.File

/** One saved script. Stored as filesDir/scripts/<id>.txt — first line is the title, the rest is the body. */
data class Script(val id: String, val title: String, val body: String, val modified: Long)

object ScriptStore {
    private fun dir(ctx: Context) = File(ctx.filesDir, "scripts").apply { mkdirs() }

    fun list(ctx: Context): List<Script> =
        (dir(ctx).listFiles { f -> f.name.endsWith(".txt") } ?: emptyArray())
            .mapNotNull { load(it) }
            .sortedByDescending { it.modified }

    fun get(ctx: Context, id: String): Script? {
        val f = File(dir(ctx), "$id.txt")
        return if (f.exists()) load(f) else null
    }

    fun save(ctx: Context, id: String?, title: String, body: String): String {
        val realId = id ?: System.currentTimeMillis().toString()
        val cleanTitle = title.replace('\n', ' ').trim().ifEmpty { "제목 없는 원고" }
        File(dir(ctx), "$realId.txt").writeText(cleanTitle + "\n" + body)
        return realId
    }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.txt").delete()
    }

    private fun load(f: File): Script? = try {
        val t = f.readText()
        val nl = t.indexOf('\n')
        val title = if (nl < 0) t else t.substring(0, nl)
        val body = if (nl < 0) "" else t.substring(nl + 1)
        Script(f.nameWithoutExtension, title, body, f.lastModified())
    } catch (e: Exception) {
        null
    }
}

/** A sentence and the paragraph it belongs to (0-based). */
data class Sentence(val text: String, val para: Int)

/**
 * Turns pasted text into sentences and paragraphs so the voice pauses in natural places.
 * - Blank lines separate paragraphs.
 * - Without blank lines, a line that ends with . ! ? closes a paragraph; other line breaks
 *   (hard wraps from PDFs or messengers) are joined back into one sentence.
 * - Bullet symbols at the start of lines are removed so they are not read aloud.
 * - Very long sentences are split at a comma so "repeat after me" chunks stay speakable.
 */
object TextParser {
    private val bullet = Regex("^\\s*[#>*•·▪◦‣\\-–—]+\\s*")
    private val spaces = Regex("\\s+")
    private const val ENDERS = ".!?…。？！"
    private const val CLOSERS = "\"'”’)]」』》〉"
    private const val LONG = 140

    fun parse(raw: String): List<Sentence> {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ')
            .split('\n')
            .map { it.replace(bullet, "").trim() }

        val paras = mutableListOf<String>()
        val nonEmpty = lines.count { it.isNotEmpty() }
        val hasBlankSeparators = nonEmpty > 1 && hasInnerBlank(lines)
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotBlank()) paras.add(cur.toString())
            cur.setLength(0)
        }
        for (l in lines) {
            if (l.isEmpty()) {
                if (hasBlankSeparators) flush()
                continue
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(l)
            if (!hasBlankSeparators && endsSentence(l)) flush()
        }
        flush()

        val out = mutableListOf<Sentence>()
        paras.forEachIndexed { pi, p ->
            for (s in splitSentences(p.replace(spaces, " ").trim())) {
                for (piece in splitLong(s)) out.add(Sentence(piece, pi))
            }
        }
        return out
    }

    fun paragraphCount(list: List<Sentence>): Int = if (list.isEmpty()) 0 else list.last().para + 1

    private fun hasInnerBlank(lines: List<String>): Boolean {
        val first = lines.indexOfFirst { it.isNotEmpty() }
        val last = lines.indexOfLast { it.isNotEmpty() }
        if (first < 0) return false
        for (i in first..last) if (lines[i].isEmpty()) return true
        return false
    }

    private fun endsSentence(line: String): Boolean {
        var k = line.length - 1
        while (k >= 0 && line[k] in CLOSERS) k--
        return k >= 0 && line[k] in ENDERS
    }

    private fun splitSentences(p: String): List<String> {
        val res = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < p.length) {
            if (p[i] in ENDERS) {
                var j = i + 1
                while (j < p.length && (p[j] in ENDERS || p[j] in CLOSERS)) j++
                if (j >= p.length || p[j] == ' ') {
                    val s = p.substring(start, j).trim()
                    if (s.isNotEmpty()) res.add(s)
                    start = j
                }
                i = j
            } else {
                i++
            }
        }
        val tail = p.substring(start).trim()
        if (tail.isNotEmpty()) res.add(tail)
        return res
    }

    private fun splitLong(s: String): List<String> {
        if (s.length <= LONG) return listOf(s)
        val res = mutableListOf<String>()
        var rest = s
        while (rest.length > LONG) {
            var cut = -1
            for (k in minOf(rest.length - 20, 110) downTo 30) {
                if (rest[k] == ',' || rest[k] == '，') {
                    cut = k
                    break
                }
            }
            if (cut < 0) break
            res.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) res.add(rest)
        return res
    }
}

/** Blank-out levels for recall practice: 0 full text, 1 hide 30%, 2 hide 60%, 3 first letter only, 4 hide all. */
object Masker {
    private const val BLANK = "＿"

    fun mask(text: String, level: Int, seed: Int): String {
        if (level <= 0) return text
        return text.split(' ').mapIndexed { wi, w -> maskWord(w, level, seed * 131 + wi * 7919) }.joinToString(" ")
    }

    private fun maskWord(w: String, level: Int, h: Int): String {
        var a = 0
        var b = w.length
        while (a < b && !w[a].isLetterOrDigit()) a++
        while (b > a && !w[b - 1].isLetterOrDigit()) b--
        if (a >= b) return w
        val core = w.substring(a, b)
        val hide = when (level) {
            1 -> roll(h) < 30
            2 -> roll(h) < 60
            else -> true
        }
        if (!hide) return w
        val masked = if (level == 3) core.substring(0, 1) + BLANK.repeat(core.length - 1) else BLANK.repeat(core.length)
        return w.substring(0, a) + masked + w.substring(b)
    }

    /** Stable pseudo-random 0..99 so the same words stay hidden every time. */
    private fun roll(h: Int): Int {
        var x = h * -0x61c88647 + 0x3c6ef372
        x = x xor (x ushr 15)
        x *= 0x2c1b3c6d
        x = x xor (x ushr 12)
        return (x and 0x7fffffff) % 100
    }
}

/** Pause lengths shared by live playback and audio-file export, so both sound the same. */
object Timing {
    const val MODE_PLAIN = 0
    const val MODE_SHADOW = 1
    const val MODE_PARA_REPEAT = 2
    const val PARA_REPEATS = 3

    fun isParaEnd(s: List<Sentence>, i: Int) = i + 1 >= s.size || s[i + 1].para != s[i].para

    fun paraStart(s: List<Sentence>, i: Int): Int {
        var k = i
        while (k > 0 && s[k - 1].para == s[i].para) k--
        return k
    }

    /** Natural breathing pause: short between sentences, longer between paragraphs. */
    fun baseGap(s: List<Sentence>, i: Int): Long = if (isParaEnd(s, i)) 900L else 320L

    /** Time to say the sentence back yourself (~0.17 s per syllable at 1.0x, plus a moment to start). */
    fun shadowMs(text: String, rate: Float): Long =
        (text.count { !it.isWhitespace() } * 170f / rate.coerceAtLeast(0.3f)).toLong() + 700L

    /** List of (sentence index, silence after it in ms) describing a whole listening session. */
    fun plan(s: List<Sentence>, mode: Int, rate: Float): List<Pair<Int, Long>> {
        val out = mutableListOf<Pair<Int, Long>>()
        when (mode) {
            MODE_SHADOW -> s.indices.forEach { out.add(it to baseGap(s, it) + shadowMs(s[it].text, rate)) }
            MODE_PARA_REPEAT -> {
                var i = 0
                while (i < s.size) {
                    var j = i
                    while (!isParaEnd(s, j)) j++
                    for (r in 0 until PARA_REPEATS) {
                        for (k in i..j) {
                            val gap = when {
                                k < j -> 320L
                                r < PARA_REPEATS - 1 -> 1000L
                                else -> 1600L
                            }
                            out.add(k to gap)
                        }
                    }
                    i = j + 1
                }
            }
            else -> s.indices.forEach { out.add(it to baseGap(s, it)) }
        }
        return out
    }
}
