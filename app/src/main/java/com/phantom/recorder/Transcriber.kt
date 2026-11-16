package com.phantom.recorder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * Fully on-device transcription using Vosk.
 * The small English model (~40 MB) is downloaded once on first use.
 * To change language, swap MODEL_NAME for another model from https://alphacephei.com/vosk/models
 */
private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"

class Transcriber(private val ctx: Context) {
    private val modelsDir = File(ctx.filesDir, "models")
    private val modelDir = File(modelsDir, MODEL_NAME)

    suspend fun run(file: File, onStatus: (String, Float) -> Unit): String = withContext(Dispatchers.IO) {
        if (!File(modelDir, "am").exists()) download(onStatus)
        recognize(file, onStatus)
    }

    private suspend fun download(onStatus: (String, Float) -> Unit) {
        val zip = File(ctx.cacheDir, "model.zip")
        val tmp = File(ctx.filesDir, "models_tmp")
        try {
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: 40_000_000L
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        onStatus("Downloading model", (done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            onStatus("Unpacking model", 1f)
            tmp.deleteRecursively(); tmp.mkdirs()
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    currentCoroutineContext().ensureActive()
                    val out = File(tmp, e.name)
                    require(out.canonicalPath.startsWith(tmp.canonicalPath)) { "Bad zip entry" }
                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
                    e = z.nextEntry
                }
            }
            modelsDir.mkdirs(); modelDir.deleteRecursively()
            check(File(tmp, MODEL_NAME).renameTo(modelDir)) { "Could not install model" }
        } finally {
            zip.delete(); tmp.deleteRecursively()
        }
    }

    private suspend fun recognize(file: File, onStatus: (String, Float) -> Unit): String {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val model = Model(modelDir.absolutePath)
        val rec = Recognizer(model, 16000f)
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        val parts = mutableListOf<String>()
        try {
            ex.setDataSource(file.absolutePath)
            val ti = (0 until ex.trackCount).first {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            ex.selectTrack(ti)
            val fmt = ex.getTrackFormat(ti)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1) else 1L
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var ratio = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 16000.0
            codec = MediaCodec.createDecoderByType(mime).also { it.configure(fmt, null, null, 0); it.start() }

            val pcm = ByteArray(8000); var pi = 0
            var acc = 0.0; var cnt = 0; var srcPos = 0L; var next = 0.0

            fun addText(json: String) {
                val t = JSONObject(json).optString("text")
                if (t.isNotBlank()) parts += t
            }
            fun flush() {
                if (pi > 0) { if (rec.acceptWaveForm(pcm, pi)) addText(rec.result); pi = 0 }
            }
            fun push(v: Short) {
                pcm[pi++] = (v.toInt() and 0xFF).toByte()
                pcm[pi++] = (v.toInt() shr 8).toByte()
                if (pi >= pcm.size) flush()
            }

            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            while (!outDone) {
                currentCoroutineContext().ensureActive()
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val ob = codec.getOutputBuffer(o)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val frames = sb.remaining() / ch
                    for (f in 0 until frames) {
                        var m = 0
                        for (c in 0 until ch) m += sb.get().toInt()
                        acc += m / ch.toDouble(); cnt++
                        if (srcPos + 1 >= next) { push((acc / cnt).toInt().toShort()); acc = 0.0; cnt = 0; next += ratio }
                        srcPos++
                    }
                    codec.releaseOutputBuffer(o, false)
                    onStatus("Transcribing", (info.presentationTimeUs.toFloat() / durUs).coerceIn(0f, 1f))
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    ratio = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 16000.0
                }
            }
            flush()
            addText(rec.finalResult)
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { ex.release() }
            runCatching { rec.close() }; runCatching { model.close() }
        }
        return parts.joinToString(" ") { it.replaceFirstChar(Char::uppercase) + "." }
    }
}
