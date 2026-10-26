package com.dndlsxo.speechmemo

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders a whole listening session into one WAV file in Music/SpeechMemo:
 * every unique sentence is synthesized once with the phone's own voice engine,
 * then the pieces are stitched together following [plan] with real silences in between.
 *
 * Callbacks from the TTS engine must be forwarded to [onDone] / [onError] on the main thread.
 */
class AudioExporter(
    private val ctx: Context,
    private val tts: TextToSpeech,
    private val sentences: List<Sentence>,
    private val plan: List<Pair<Int, Long>>,
    private val title: String,
    private val suffix: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onProgress(done: Int, total: Int)
        fun onSaved(uri: Uri, where: String)
        fun onFailed(message: String)
    }

    private class Pcm(val data: ByteArray, val sampleRate: Int, val channels: Int, val bits: Int)

    private val main = Handler(Looper.getMainLooper())
    private val dir = File(ctx.cacheDir, "export")
    private val prefix = "x_" + System.currentTimeMillis() + "_"
    private val doneIds = HashSet<String>()
    private var finished = false

    fun handles(id: String) = id.startsWith(prefix)

    fun start() {
        dir.deleteRecursively()
        dir.mkdirs()
        for ((i, s) in sentences.withIndex()) {
            val r = tts.synthesizeToFile(s.text, Bundle(), File(dir, "$i.wav"), prefix + i)
            if (r != TextToSpeech.SUCCESS) {
                fail("음성 합성을 시작하지 못했어요. 음성 엔진을 바꾸거나 음성 데이터를 설치한 뒤 다시 시도해 주세요.")
                return
            }
        }
    }

    fun onDone(id: String) {
        if (finished || !doneIds.add(id)) return
        listener.onProgress(doneIds.size, sentences.size)
        if (doneIds.size >= sentences.size) merge()
    }

    fun onError(id: String) {
        val n = id.removePrefix(prefix).toIntOrNull()?.plus(1)
        fail("${n ?: "?"}번째 문장을 음성으로 바꾸지 못했어요. 다른 목소리로 바꿔서 다시 시도해 주세요.")
    }

    fun cancel() {
        if (finished) return
        finished = true
        tts.stop()
        dir.deleteRecursively()
    }

    private fun fail(msg: String) {
        if (finished) return
        finished = true
        tts.stop()
        listener.onFailed(msg)
    }

    private fun merge() {
        finished = true
        Thread {
            try {
                val pcms = sentences.indices.map { readWav(File(dir, "$it.wav")) }
                val ref = pcms.firstOrNull { it.data.isNotEmpty() } ?: throw IOException("음성이 하나도 만들어지지 않았어요.")
                val block = ref.channels * ref.bits / 8
                val bytesPerMs = ref.sampleRate * block / 1000.0
                fun silenceBytes(ms: Long): Long = ((ms * bytesPerMs).toLong() / block) * block

                val lead = silenceBytes(400)
                var total = lead
                for ((idx, gap) in plan) total += pcms[idx].data.size + silenceBytes(gap)
                if (total > Int.MAX_VALUE - 64) throw IOException("원고가 너무 길어요. 나눠서 저장해 주세요.")

                val name = fileName()
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/SpeechMemo")
                    put(MediaStore.Audio.Media.TITLE, "$title ($suffix)")
                    put(MediaStore.Audio.Media.ALBUM, "SpeechMemo")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    ?: throw IOException("음악 폴더에 파일을 만들 수 없어요.")
                try {
                    val os = resolver.openOutputStream(uri) ?: throw IOException("파일을 열 수 없어요.")
                    BufferedOutputStream(os, 1 shl 16).use { out ->
                        out.write(header(total, ref))
                        writeSilence(out, lead, ref.bits)
                        for ((idx, gap) in plan) {
                            out.write(pcms[idx].data)
                            writeSilence(out, silenceBytes(gap), ref.bits)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                dir.deleteRecursively()
                main.post { listener.onSaved(uri, "음악/SpeechMemo/$name") }
            } catch (e: Exception) {
                main.post { listener.onFailed("파일 저장 중 문제가 생겼어요: ${e.message ?: e.javaClass.simpleName}") }
            }
        }.start()
    }

    private fun fileName(): String {
        val safe = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().take(40).ifEmpty { "원고" }
        return "${safe}_$suffix.wav"
    }

    private fun writeSilence(out: OutputStream, bytes: Long, bits: Int) {
        if (bytes <= 0) return
        val buf = ByteArray(8192)
        if (bits == 8) buf.fill(0x80.toByte())
        var left = bytes
        while (left > 0) {
            val n = minOf(left, buf.size.toLong()).toInt()
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun header(dataLen: Long, f: Pcm): ByteArray {
        val block = f.channels * f.bits / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + dataLen).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(f.channels.toShort())
            putInt(f.sampleRate)
            putInt(f.sampleRate * block)
            putShort(block.toShort())
            putShort(f.bits.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataLen.toInt())
        }.array()
    }

    /** Minimal RIFF/WAV reader that tolerates the "size = 0" headers some engines write while streaming. */
    private fun readWav(f: File): Pcm {
        if (!f.exists() || f.length() < 12) return Pcm(ByteArray(0), 24000, 1, 16)
        val b = f.readBytes()
        if (String(b, 0, 4, Charsets.US_ASCII) != "RIFF") return Pcm(ByteArray(0), 24000, 1, 16)
        var p = 12
        var sr = 24000
        var ch = 1
        var bits = 16
        var data = ByteArray(0)
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII)
            val start = p + 8
            var size = le32(b, p + 4)
            if (size < 0 || start + size > b.size || (id == "data" && size == 0)) size = b.size - start
            if (id == "fmt " && size >= 16) {
                ch = le16(b, start + 2)
                sr = le32(b, start + 4)
                bits = le16(b, start + 14)
            } else if (id == "data") {
                data = b.copyOfRange(start, start + size)
                break
            }
            p = start + size + (size and 1)
        }
        if (ch <= 0) ch = 1
        if (bits != 8 && bits != 16 && bits != 24 && bits != 32) bits = 16
        val block = ch * bits / 8
        if (data.size % block != 0) data = data.copyOf(data.size - data.size % block)
        return Pcm(data, sr, ch, bits)
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
}
