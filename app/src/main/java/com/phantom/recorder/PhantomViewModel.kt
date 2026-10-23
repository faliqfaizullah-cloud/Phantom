package com.phantom.recorder

import android.app.Application
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.sqrt

const val SAMPLE_MS = 60L

class PhantomViewModel(private val app: Application) : AndroidViewModel(app) {
    var recording by mutableStateOf(false); private set
    var playing by mutableStateOf(false); private set
    var elapsedMs by mutableLongStateOf(0L); private set
    var level by mutableFloatStateOf(0f); private set
    var cursor by mutableIntStateOf(0); private set
    var volumeStep by mutableIntStateOf(2); private set       // 0 mute, 1 half, 2 full
    var highQuality by mutableStateOf(true)
    var current by mutableStateOf<File?>(null); private set
    var transcript by mutableStateOf<String?>(null); private set
    var transStatus by mutableStateOf<String?>(null); private set
    var transProgress by mutableFloatStateOf(0f); private set
    var error by mutableStateOf<String?>(null)

    val samples = mutableStateListOf<Float>()
    val marks = mutableStateListOf<Long>()
    val files = mutableStateListOf<File>()

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var job: Job? = null
    private var transJob: Job? = null
    private val transcriber = Transcriber(app)
    private val volumes = floatArrayOf(0f, 0.5f, 1f)
    private val dir: File = (app.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: app.filesDir)
        .also { it.mkdirs() }

    init { refresh() }

    fun refresh() {
        files.clear()
        files.addAll(dir.listFiles { f -> f.extension == "m4a" }?.sortedByDescending { it.lastModified() } ?: emptyList())
    }

    // ---------- Recording ----------
    fun startRecording(): Boolean {
        stopPlayback()
        val next = (files.mapNotNull { it.nameWithoutExtension.removePrefix("Phantom-").toIntOrNull() }.maxOrNull() ?: 0) + 1
        val f = File(dir, "Phantom-%03d.m4a".format(next))
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(if (highQuality) 256_000 else 96_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            current = f
            transcript = null
            samples.clear(); marks.clear(); cursor = 0; elapsedMs = 0
            recording = true
            val t0 = SystemClock.elapsedRealtime()
            job = viewModelScope.launch {
                var lastSample = 0L
                while (isActive) {
                    val now = SystemClock.elapsedRealtime() - t0
                    elapsedMs = now
                    val raw = try { r.maxAmplitude / 32767f } catch (e: Exception) { 0f }
                    level = (level * 0.4f + sqrt(raw).coerceIn(0f, 1f) * 0.6f)
                    if (now - lastSample >= SAMPLE_MS) {
                        samples.add(level); cursor = samples.size; lastSample = now
                    }
                    delay(30)
                }
            }
            true
        } catch (e: Exception) {
            runCatching { r.release() }
            f.delete()
            false
        }
    }

    fun stopRecording() {
        job?.cancel()
        recorder?.let {
            try { it.stop() } catch (e: RuntimeException) { current?.delete(); current = null }
            it.release()
        }
        recorder = null
        recording = false
        level = 0f
        current?.takeIf { it.exists() }?.let { saveSidecar(it) }
        refresh()
        PhantomWidget.refresh(app)
    }

    fun addMark(): Boolean {
        if (!recording) return false
        marks.add(elapsedMs)
        return true
    }

    // ---------- Playback ----------
    fun play(f: File) {
        if (recording) return
        stopPlayback()
        load(f)
        cursor = 0
        try {
            val v = volumes[volumeStep]
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath); prepare(); setVolume(v, v); start()
                setOnCompletionListener { stopPlayback() }
            }
        } catch (e: Exception) { player = null; return }
        playing = true
        job = viewModelScope.launch {
            while (isActive) {
                val p = player?.currentPosition ?: 0
                elapsedMs = p.toLong()
                cursor = (p / SAMPLE_MS).toInt().coerceAtMost(samples.size)
                level = samples.getOrElse(cursor - 1) { 0f }
                delay(40)
            }
        }
    }

    fun togglePlay(): Boolean {
        if (playing) { stopPlayback(); return true }
        val f = current?.takeIf { it.exists() } ?: files.firstOrNull() ?: return false
        play(f); return true
    }

    fun stopPlayback() {
        if (!playing && player == null) return
        job?.cancel()
        player?.runCatching { stop() }
        player?.release(); player = null
        playing = false; level = 0f; cursor = samples.size
    }

    fun cycleVolume() {
        volumeStep = (volumeStep + 1) % 3
        val v = volumes[volumeStep]
        player?.setVolume(v, v)
    }

    fun delete(f: File) {
        if (current == f) { stopPlayback(); current = null; transcript = null; samples.clear(); marks.clear(); cursor = 0; elapsedMs = 0 }
        f.delete(); File(f.path + ".wave").delete(); File(f.path + ".txt").delete()
        refresh()
        PhantomWidget.refresh(app)
    }

    // ---------- Transcription ----------
    fun transcribe() {
        if (recording || transStatus != null) return
        val f = current?.takeIf { it.exists() } ?: files.firstOrNull()
        if (f == null) { error = "No recording to transcribe"; return }
        if (current != f) load(f)
        transStatus = "Preparing"; transProgress = 0f
        transJob = viewModelScope.launch {
            try {
                val text = transcriber.run(f) { s, p -> transStatus = s; transProgress = p }
                val result = text.ifBlank { "(No speech detected)" }
                File(f.path + ".txt").writeText(result)
                if (current == f) transcript = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = "Transcription failed: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                transStatus = null
                PhantomWidget.refresh(app)
            }
        }
    }

    fun cancelTranscribe() { transJob?.cancel() }

    // ---------- Waveform sidecar ----------
    private fun saveSidecar(f: File) = runCatching {
        File(f.path + ".wave").writeText(samples.joinToString(",") + "\n" + marks.joinToString(","))
    }

    private fun load(f: File) {
        current = f
        samples.clear(); marks.clear()
        runCatching {
            val lines = File(f.path + ".wave").readLines()
            lines.getOrNull(0)?.split(",")?.mapNotNull { it.toFloatOrNull() }?.let { samples.addAll(it) }
            lines.getOrNull(1)?.split(",")?.mapNotNull { it.toLongOrNull() }?.let { marks.addAll(it) }
        }
        cursor = samples.size
        elapsedMs = samples.size * SAMPLE_MS
        transcript = File(f.path + ".txt").takeIf { it.exists() }?.readText()
    }

    override fun onCleared() {
        job?.cancel()
        runCatching { recorder?.stop() }; recorder?.release()
        player?.release()
    }
}
